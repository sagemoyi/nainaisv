package com.xmoyi.nainaisv.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.xmoyi.nainaisv.NaiNaiApplication
import com.xmoyi.nainaisv.data.DramaEntity
import com.xmoyi.nainaisv.data.DramaRepository
import com.xmoyi.nainaisv.data.SettingsStore
import com.xmoyi.nainaisv.data.UnplayableDramaException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class PlayerUiState(
    val queue: List<DramaEntity> = emptyList(),
    val currentIndex: Int = 0,
    val current: DramaEntity? = null,
    val player: Player? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val errorMessage: String? = null,
    val loading: Boolean = true,
    val showHint: Boolean = false,
    val episodeLabel: String? = null,
)

@UnstableApi
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as NaiNaiApplication).container
    private val repository: DramaRepository = container.repository
    private val settings: SettingsStore = container.settings

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val slots = listOf(createSlot(), createSlot())
    private var activeSlot = 0
    private var startJob: Job? = null
    private var loadJob: Job? = null
    private var preloadJob: Job? = null
    private val errorCounts = mutableMapOf<String, Int>()
    private var lastProgressSave = 0L
    private var contentChanged = false

    init {
        slots.forEachIndexed { index, slot -> attachListener(index, slot) }
        viewModelScope.launch {
            while (true) {
                delay(500)
                updateProgress()
            }
        }
        viewModelScope.launch {
            repository.contentVersion.drop(1).collect {
                if (_state.value.queue.isEmpty() && !_state.value.loading) {
                    refreshQueue()
                } else {
                    contentChanged = true
                }
            }
        }
    }

    fun start() {
        if (startJob?.isActive == true) return
        if (!_state.value.loading && _state.value.queue.isNotEmpty()) return
        startJob = viewModelScope.launch {
            val hintSeen = settings.hintSeen.first()
            var queue = repository.buildQueue()
            if (queue.isEmpty()) {
                repository.refreshTrustedCreators()
                queue = repository.buildQueue()
            }
            val startIndex = resolveStartIndex(queue)
            _state.value = _state.value.copy(
                queue = queue,
                currentIndex = startIndex,
                loading = false,
                showHint = !hintSeen,
                episodeLabel = episodeLabel(queue, startIndex),
            )
            if (queue.isNotEmpty()) playAt(startIndex, savePrevious = false)
            launch { repository.autoDiscoverCandidates() }
        }
    }

    /** 每次奶奶屏幕重新出现时调用：应用家属改动、处理过期地址并恢复播放。 */
    fun onShown() {
        if (_state.value.loading) return
        if (contentChanged) {
            contentChanged = false
            viewModelScope.launch { refreshQueue() }
        } else {
            resume()
        }
    }

    fun onPageSelected(index: Int) {
        if (index !in _state.value.queue.indices || index == _state.value.currentIndex) return
        playAt(index, savePrevious = true)
    }

    fun next() {
        val current = _state.value
        if (current.queue.isEmpty()) return
        if (current.currentIndex >= current.queue.lastIndex) {
            viewModelScope.launch {
                saveCurrentNow(false)
                var queue = repository.buildQueue()
                if (queue.isEmpty()) {
                    repository.refreshTrustedCreators()
                    queue = repository.buildQueue()
                }
                _state.value = _state.value.copy(queue = queue)
                if (queue.isNotEmpty()) playAt(0, savePrevious = false)
            }
        } else {
            playAt(current.currentIndex + 1, savePrevious = true)
        }
    }

    fun previous() {
        val previous = (_state.value.currentIndex - 1).coerceAtLeast(0)
        if (previous != _state.value.currentIndex) playAt(previous, savePrevious = true)
    }

    fun togglePlayback() {
        val slot = slots[activeSlot]
        when {
            slot.player.isPlaying -> {
                slot.player.pause()
                saveCurrent(false)
            }
            slot.expired() -> _state.value.current?.let { playAtId(it.id, forceReload = true) }
            else -> slot.player.play()
        }
    }

    fun pause() {
        slots[activeSlot].player.pause()
        saveCurrent(false)
    }

    fun resume() {
        val current = _state.value.current ?: return
        val slot = slots[activeSlot]
        if (slot.expired() && !slot.player.isPlaying) {
            playAtId(current.id, forceReload = true)
        } else {
            slot.player.play()
        }
    }

    fun retry() {
        val current = _state.value.current ?: _state.value.queue.getOrNull(_state.value.currentIndex) ?: return
        errorCounts.remove(current.id)
        _state.value = _state.value.copy(errorMessage = null)
        playAtId(current.id, forceReload = true)
    }

    fun dismissHint() {
        if (!_state.value.showHint) return
        _state.value = _state.value.copy(showHint = false)
        viewModelScope.launch { settings.setHintSeen() }
    }

    private suspend fun resolveStartIndex(queue: List<DramaEntity>): Int {
        val lastId = settings.lastDramaId.first() ?: return 0
        val index = queue.indexOfFirst { it.id == lastId }
        if (index < 0) return 0
        val watch = repository.watchState(lastId)
        // 上次看完的那集不重播，直接从下一集（同一部剧的下一集排在紧后面）开始。
        return if (watch?.completed == true && index < queue.lastIndex) index + 1 else index
    }

    private suspend fun refreshQueue() {
        val currentId = _state.value.current?.id
        var queue = repository.buildQueue()
        if (queue.isEmpty()) {
            repository.refreshTrustedCreators()
            queue = repository.buildQueue()
        }
        if (queue.isEmpty()) {
            slots.forEach { it.player.stop() }
            _state.value = _state.value.copy(queue = emptyList(), current = null, player = null, loading = false)
            return
        }
        val index = currentId?.let { id -> queue.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0) {
            _state.value = _state.value.copy(
                queue = queue,
                currentIndex = index,
                episodeLabel = episodeLabel(queue, index),
            )
            resume()
        } else {
            _state.value = _state.value.copy(queue = queue)
            playAt(0, savePrevious = false)
        }
    }

    private fun playAtId(id: String, forceReload: Boolean = false, preferredQuality: Int = 64) {
        val index = _state.value.queue.indexOfFirst { it.id == id }
        if (index >= 0) playAt(index, savePrevious = false, forceReload, preferredQuality)
    }

    private fun playAt(
        index: Int,
        savePrevious: Boolean,
        forceReload: Boolean = false,
        preferredQuality: Int = 64,
    ) {
        val queue = _state.value.queue
        if (index !in queue.indices) return
        loadJob?.cancel()
        preloadJob?.cancel()
        if (savePrevious) saveCurrent(skipped = slots[activeSlot].player.currentPosition < 15_000)
        val item = queue[index]
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                currentIndex = index,
                errorMessage = null,
                isBuffering = true,
                episodeLabel = episodeLabel(queue, index),
            )
            if (!forceReload) {
                val preloaded = slots.indexOfFirst { it.dramaId == item.id && it.ready && !it.expired() }
                if (preloaded >= 0) {
                    switchTo(preloaded)
                    preloadNext()
                    return@launch
                }
            }
            val targetSlot = 1 - activeSlot
            try {
                prepareSlot(targetSlot, item, playWhenReady = true, quality = preferredQuality)
                switchTo(targetSlot)
                preloadNext()
            } catch (error: CancellationException) {
                throw error
            } catch (error: UnplayableDramaException) {
                dropAndAdvance(item, "这个故事看不了，正在换下一个")
            } catch (error: Exception) {
                handleLoadError(item)
            }
        }
    }

    /**
     * 解析并装载一个条目。占位条目会展开成整部剧并拼接进队列。
     * 任何异常（包括取消）都向上抛出，由调用方决定如何处理；
     * 绝不能吞掉取消异常，否则快速滑动会把正常视频误判为失败。
     */
    private suspend fun prepareSlot(
        slotIndex: Int,
        item: DramaEntity,
        playWhenReady: Boolean,
        quality: Int = 64,
    ) {
        val slot = slots[slotIndex]
        slot.ready = false
        slot.dramaId = null
        slot.drama = null
        slot.player.stop()
        slot.player.clearMediaItems()
        val episodes = repository.ensureResolved(item)
        val resolved = if (item.cid > 0) item else spliceResolved(item.id, episodes, anchorFirst = playWhenReady)
        val source = repository.playback(resolved, quality)
        slot.dataSourceFactory.setDefaultRequestProperties(source.headers)
        slot.player.setMediaItems(source.urls.map { MediaItem.fromUri(it) })
        val watch = repository.watchState(resolved.id)
        slot.player.seekTo(watch?.positionMs ?: 0)
        slot.player.playWhenReady = playWhenReady
        slot.player.prepare()
        slot.drama = resolved
        slot.dramaId = resolved.id
        slot.expiresAt = source.expiresAt
        slot.ready = true
    }

    /** 把占位条目替换成整部剧的所有集，去掉队列里其他位置的重复条目。 */
    private fun spliceResolved(
        placeholderId: String,
        episodes: List<DramaEntity>,
        anchorFirst: Boolean,
    ): DramaEntity {
        val queue = _state.value.queue
        val index = queue.indexOfFirst { it.id == placeholderId }
        if (index < 0) return episodes.first()
        val episodeIds = episodes.map { it.id }.toSet()
        val newQueue = buildList {
            queue.forEachIndexed { i, entry ->
                when {
                    i == index -> addAll(episodes)
                    entry.id in episodeIds -> Unit
                    else -> add(entry)
                }
            }
        }
        val anchorId = if (anchorFirst) episodes.first().id else _state.value.current?.id
        val anchorIndex = anchorId?.let { id -> newQueue.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
        _state.value = _state.value.copy(
            queue = newQueue,
            currentIndex = anchorIndex,
            episodeLabel = episodeLabel(newQueue, anchorIndex),
        )
        return episodes.first()
    }

    private fun switchTo(slotIndex: Int) {
        if (slotIndex != activeSlot) slots[activeSlot].player.pause()
        activeSlot = slotIndex
        val slot = slots[activeSlot]
        slot.player.playWhenReady = true
        slot.player.play()
        val queue = _state.value.queue
        val index = queue.indexOfFirst { it.id == slot.dramaId }.takeIf { it >= 0 } ?: _state.value.currentIndex
        _state.value = _state.value.copy(
            currentIndex = index,
            current = slot.drama,
            player = slot.player,
            isBuffering = slot.player.playbackState == Player.STATE_BUFFERING,
            isPlaying = slot.player.isPlaying,
            errorMessage = null,
            episodeLabel = episodeLabel(queue, index),
        )
    }

    private fun preloadNext() {
        preloadJob?.cancel()
        val queue = _state.value.queue
        val nextIndex = _state.value.currentIndex + 1
        if (nextIndex !in queue.indices) return
        val item = queue[nextIndex]
        val inactive = 1 - activeSlot
        preloadJob = viewModelScope.launch {
            try {
                prepareSlot(inactive, item, playWhenReady = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 预加载失败不打扰当前播放；真正切换时会重试或自动换片。
            }
        }
    }

    private fun attachListener(index: Int, slot: Slot) {
        slot.player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (index == activeSlot) _state.value = _state.value.copy(isPlaying = isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (index != activeSlot) return
                _state.value = _state.value.copy(isBuffering = playbackState == Player.STATE_BUFFERING)
                when (playbackState) {
                    Player.STATE_READY -> slot.dramaId?.let(errorCounts::remove)
                    Player.STATE_ENDED -> {
                        saveCurrent(false)
                        viewModelScope.launch {
                            delay(800)
                            next()
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (index != activeSlot) return
                val drama = slot.drama ?: return
                saveCurrent(false)
                handleLoadError(drama)
            }
        })
    }

    /** 加载或播放出错：重新解析一次 → 降到 480P 再试 → 提示后自动换片（不永久标记）。 */
    private fun handleLoadError(item: DramaEntity) {
        val count = (errorCounts[item.id] ?: 0) + 1
        errorCounts[item.id] = count
        when {
            count <= 2 -> viewModelScope.launch {
                delay(600)
                playAtId(item.id, forceReload = true, preferredQuality = if (count >= 2) 32 else 64)
            }
            else -> {
                _state.value = _state.value.copy(errorMessage = "网络开小差了，正在换下一个", isBuffering = false)
                viewModelScope.launch {
                    delay(1_500)
                    if (_state.value.errorMessage != null) advancePast(item)
                }
            }
        }
    }

    private fun dropAndAdvance(item: DramaEntity, message: String) {
        _state.value = _state.value.copy(errorMessage = message, isBuffering = false)
        viewModelScope.launch {
            delay(1_200)
            if (_state.value.errorMessage != null) advancePast(item)
        }
    }

    private fun advancePast(item: DramaEntity) {
        val queue = _state.value.queue.filterNot { it.id == item.id }
        if (queue.isEmpty()) {
            _state.value = _state.value.copy(queue = emptyList(), current = null, player = null, errorMessage = null)
            viewModelScope.launch { refreshQueue() }
            return
        }
        val index = _state.value.currentIndex.coerceIn(0, queue.lastIndex)
        _state.value = _state.value.copy(queue = queue, errorMessage = null)
        playAt(index, savePrevious = false)
    }

    private fun updateProgress() {
        val slot = slots[activeSlot]
        val player = slot.player
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: slot.drama?.durationMs ?: 0
        val position = player.currentPosition.coerceAtLeast(0)
        val playing = player.isPlaying
        val buffering = player.playbackState == Player.STATE_BUFFERING
        val current = _state.value
        if (current.positionMs != position || current.durationMs != duration ||
            current.isPlaying != playing || current.isBuffering != buffering
        ) {
            _state.value = current.copy(
                positionMs = position,
                durationMs = duration,
                isPlaying = playing,
                isBuffering = buffering,
            )
        }
        val now = System.currentTimeMillis()
        if (playing && now - lastProgressSave >= 5_000) {
            lastProgressSave = now
            saveCurrent(false)
        }
    }

    private fun episodeLabel(queue: List<DramaEntity>, index: Int): String? {
        val item = queue.getOrNull(index) ?: return null
        if (item.cid <= 0) return null
        val key = item.seriesKey.ifBlank { "bv:${item.bvid}" }
        val episodes = queue.filter { (it.seriesKey.ifBlank { "bv:${it.bvid}" }) == key }
        if (episodes.size <= 1) return null
        val position = episodes.indexOfFirst { it.id == item.id } + 1
        return "第 $position 集 · 共 ${episodes.size} 集"
    }

    private fun saveCurrent(skipped: Boolean) {
        viewModelScope.launch { saveCurrentNow(skipped) }
    }

    private suspend fun saveCurrentNow(skipped: Boolean) {
        val slot = slots[activeSlot]
        val drama = slot.drama ?: return
        val player = slot.player
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: drama.durationMs
        repository.saveProgress(drama, player.currentPosition.coerceAtLeast(0), duration, skipped)
    }

    private fun createSlot(): Slot {
        val dataSourceFactory = OkHttpDataSource.Factory(BilibiliClientHolder.client)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val player = ExoPlayer.Builder(getApplication(), mediaSourceFactory)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        return Slot(player, dataSourceFactory)
    }

    override fun onCleared() {
        slots.forEach { it.player.release() }
        super.onCleared()
    }

    private class Slot(
        val player: ExoPlayer,
        val dataSourceFactory: OkHttpDataSource.Factory,
        var dramaId: String? = null,
        var drama: DramaEntity? = null,
        var ready: Boolean = false,
        var expiresAt: Long = 0,
    ) {
        /** 播放地址临近过期就视为过期，避免恢复播放时立刻 403。 */
        fun expired(): Boolean = expiresAt > 0 && System.currentTimeMillis() > expiresAt - 60_000
    }

    private object BilibiliClientHolder {
        val client = com.xmoyi.nainaisv.network.BilibiliClient.defaultHttpClient()
    }
}
