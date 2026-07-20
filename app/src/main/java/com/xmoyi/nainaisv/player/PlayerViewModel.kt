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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
    val noticeMessage: String? = null,
    val loading: Boolean = true,
    val showHint: Boolean = false,
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
    private var recoveryJob: Job? = null
    private var endedJob: Job? = null
    private val errorCounts = ConcurrentHashMap<String, Int>()
    private var lastProgressSave = 0L
    private var pausedByUser = false

    init {
        slots.forEachIndexed { index, slot -> attachListener(index, slot) }
        viewModelScope.launch {
            while (true) {
                delay(500)
                updateProgress()
            }
        }
    }

    fun start() {
        if (startJob?.isActive == true) return
        startJob = viewModelScope.launch {
            if (_state.value.queue.isNotEmpty()) {
                // 从家属管理返回：本地队列静默刷新，正在播放的内容不中断。
                refreshQueuePreservingPlayback()
                return@launch
            }
            val hintSeen = settings.hintSeen.first()
            val queue = awaitQueue()
            if (!isActive) return@launch
            val lastId = settings.lastDramaId.first()
            val startIndex = queue.indexOfFirst { it.id == lastId }.takeIf { it >= 0 } ?: 0
            _state.value = _state.value.copy(
                queue = queue,
                currentIndex = startIndex,
                loading = false,
                showHint = !hintSeen,
            )
            playAt(startIndex, savePrevious = false)
            launch { repository.autoDiscoverCandidates() }
        }
    }

    fun onPageSelected(index: Int) {
        if (index !in _state.value.queue.indices || index == _state.value.currentIndex) return
        playAt(index, savePrevious = true)
    }

    fun next() {
        val queue = _state.value.queue
        if (queue.isEmpty()) return
        val next = (_state.value.currentIndex + 1).coerceAtMost(queue.lastIndex)
        if (next == _state.value.currentIndex) {
            viewModelScope.launch {
                saveCurrentNow(false)
                var refreshed = repository.buildQueue()
                if (refreshed.isEmpty()) {
                    runCatching { repository.refreshTrustedCreators() }
                    refreshed = repository.buildQueue()
                }
                if (refreshed.isNotEmpty()) {
                    _state.value = _state.value.copy(queue = refreshed)
                    playAt(0, savePrevious = false)
                }
            }
        } else {
            playAt(next, savePrevious = true)
        }
    }

    fun previous() {
        val previous = (_state.value.currentIndex - 1).coerceAtLeast(0)
        if (previous != _state.value.currentIndex) playAt(previous, savePrevious = true)
    }

    fun togglePlayback() {
        val player = slots[activeSlot].player
        if (player.isPlaying) {
            player.pause()
            pausedByUser = true
        } else {
            player.play()
            pausedByUser = false
        }
    }

    fun pause() {
        slots[activeSlot].player.pause()
        saveCurrent(false)
    }

    fun resume() {
        if (_state.value.current != null && !pausedByUser) slots[activeSlot].player.play()
    }

    fun dismissHint() {
        _state.value = _state.value.copy(showHint = false)
        viewModelScope.launch { settings.setHintSeen() }
    }

    /** 启动或恢复时等待本地队列；没有网络时按退避策略持续重试，不让奶奶卡在空白页。 */
    private suspend fun awaitQueue(): List<DramaEntity> {
        var attempt = 0
        while (true) {
            var queue = repository.buildQueue()
            if (queue.isEmpty()) {
                runCatching { repository.refreshTrustedCreators() }
                queue = repository.buildQueue()
            }
            if (queue.isNotEmpty()) return queue
            attempt++
            delay(minOf(5_000L * (1L shl (attempt - 1).coerceAtMost(4)), 60_000L))
        }
    }

    private suspend fun refreshQueuePreservingPlayback() {
        val refreshed = repository.buildQueue()
        if (refreshed.isEmpty()) return
        val currentId = slots[activeSlot].drama?.id ?: return
        val newIndex = refreshed.indexOfFirst { it.id == currentId }
        if (newIndex >= 0) {
            _state.value = _state.value.copy(queue = refreshed, currentIndex = newIndex)
        }
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
        recoveryJob?.cancel()
        endedJob?.cancel()
        if (savePrevious) saveCurrent(skipped = slots[activeSlot].player.currentPosition < 15_000)
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                currentIndex = index,
                noticeMessage = null,
                isBuffering = true,
            )
            val preloaded = slots.indexOfFirst { it.queueIndex == index && it.ready }
            if (preloaded >= 0 && !forceReload) {
                switchTo(preloaded, index)
                preload(index + 1)
                return@launch
            }
            val targetSlot = if (activeSlot == 0) 1 else 0
            val prepared = prepareSlot(targetSlot, index, playWhenReady = true, preferredQuality)
            if (!isActive) return@launch
            if (prepared) {
                switchTo(targetSlot, index)
                preload(index + 1)
            } else {
                val failed = _state.value.queue.getOrNull(index)
                if (failed != null) {
                    handlePermanentFailure(failed, "这个故事暂时播不了，马上换一个")
                }
            }
        }
    }

    private suspend fun prepareSlot(
        slotIndex: Int,
        queueIndex: Int,
        playWhenReady: Boolean,
        preferredQuality: Int = 64,
    ): Boolean {
        val original = _state.value.queue.getOrNull(queueIndex) ?: return false
        val slot = slots[slotIndex]
        slot.ready = false
        slot.queueIndex = -1
        slot.player.stop()
        slot.player.clearMediaItems()
        val resolvedItems = try {
            repository.ensureResolvedAll(original)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return false
        }
        // 占位展开成多 P / 合集后，把全部剧集插进队列，奶奶可以连着看。
        spliceIntoQueue(original, queueIndex, resolvedItems)
        val resolved = resolvedItems.first()
        return try {
            val source = repository.playback(resolved, preferredQuality)
            slot.dataSourceFactory.setDefaultRequestProperties(source.headers)
            slot.player.setMediaItems(source.urls.map { MediaItem.fromUri(it) })
            val watch = repository.watchState(resolved.id)
            slot.player.seekTo(watch?.positionMs ?: 0)
            slot.player.playWhenReady = playWhenReady
            slot.player.prepare()
            slot.queueIndex = queueIndex
            slot.drama = resolved
            slot.ready = true
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            false
        }
    }

    private fun spliceIntoQueue(original: DramaEntity, queueIndex: Int, resolvedItems: List<DramaEntity>) {
        val current = _state.value.queue
        val at = if (current.getOrNull(queueIndex)?.id == original.id) {
            queueIndex
        } else {
            current.indexOfFirst { it.id == original.id }
        }
        if (at < 0) return
        val updated = current.toMutableList().apply {
            removeAt(at)
            addAll(at, resolvedItems)
        }
        _state.value = _state.value.copy(queue = updated)
    }

    private fun switchTo(slotIndex: Int, queueIndex: Int) {
        if (slotIndex != activeSlot) slots[activeSlot].player.pause()
        activeSlot = slotIndex
        val slot = slots[activeSlot]
        slot.player.playWhenReady = true
        slot.player.play()
        slot.drama?.let { errorCounts.remove(it.id) }
        pausedByUser = false
        _state.value = _state.value.copy(
            currentIndex = queueIndex,
            current = slot.drama,
            player = slot.player,
            isBuffering = slot.player.playbackState == Player.STATE_BUFFERING,
            isPlaying = slot.player.isPlaying,
            noticeMessage = null,
        )
    }

    private fun preload(index: Int) {
        preloadJob?.cancel()
        if (index !in _state.value.queue.indices) return
        val inactive = if (activeSlot == 0) 1 else 0
        preloadJob = viewModelScope.launch { prepareSlot(inactive, index, playWhenReady = false) }
    }

    private fun attachListener(index: Int, slot: Slot) {
        slot.player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (index == activeSlot) _state.value = _state.value.copy(isPlaying = isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (index != activeSlot) return
                _state.value = _state.value.copy(isBuffering = playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_ENDED) {
                    saveCurrent(false)
                    endedJob?.cancel()
                    endedJob = viewModelScope.launch {
                        delay(1_000)
                        next()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (index != activeSlot) return
                val drama = slot.drama ?: return
                val count = (errorCounts[drama.id] ?: 0) + 1
                errorCounts[drama.id] = count
                if (count <= 2) {
                    playAt(
                        slot.queueIndex,
                        savePrevious = false,
                        forceReload = true,
                        preferredQuality = if (count >= 2) 32 else 64,
                    )
                } else {
                    handlePermanentFailure(drama, "网络开小差了，正在换一个故事")
                }
            }
        })
    }

    private fun handlePermanentFailure(drama: DramaEntity, message: String) {
        recoveryJob?.cancel()
        _state.value = _state.value.copy(noticeMessage = message, isBuffering = false)
        recoveryJob = viewModelScope.launch {
            repository.markUnplayable(drama.id)
            delay(1_500)
            var queue = repository.buildQueue()
            if (queue.isEmpty()) {
                runCatching { repository.refreshTrustedCreators() }
                queue = repository.buildQueue()
            }
            if (!isActive) return@launch
            if (queue.isNotEmpty()) {
                _state.value = _state.value.copy(queue = queue, noticeMessage = null)
                playAt(_state.value.currentIndex.coerceAtMost(queue.lastIndex), savePrevious = false)
            } else {
                _state.value = _state.value.copy(
                    queue = emptyList(),
                    current = null,
                    loading = true,
                    noticeMessage = null,
                )
                val fresh = awaitQueue()
                if (!isActive) return@launch
                _state.value = _state.value.copy(queue = fresh, loading = false)
                playAt(0, savePrevious = false)
            }
        }
    }

    private fun updateProgress() {
        val slot = slots[activeSlot]
        val player = slot.player
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: slot.drama?.durationMs ?: 0
        _state.value = _state.value.copy(
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
        )
        val now = System.currentTimeMillis()
        if (player.isPlaying && now - lastProgressSave >= 5_000) {
            lastProgressSave = now
            saveCurrent(false)
        }
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
        saveCurrent(false)
        slots.forEach { it.player.release() }
        super.onCleared()
    }

    private data class Slot(
        val player: ExoPlayer,
        val dataSourceFactory: OkHttpDataSource.Factory,
        var queueIndex: Int = -1,
        var drama: DramaEntity? = null,
        var ready: Boolean = false,
    )

    private object BilibiliClientHolder {
        val client = com.xmoyi.nainaisv.network.BilibiliClient.defaultHttpClient()
    }
}
