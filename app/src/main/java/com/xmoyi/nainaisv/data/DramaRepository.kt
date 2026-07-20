package com.xmoyi.nainaisv.data

import com.xmoyi.nainaisv.network.BilibiliClient
import com.xmoyi.nainaisv.recommendation.RecommendationEngine
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** 视频确认无法播放（下架、付费、被过滤），数据库中已标记；临时网络错误不用这个异常。 */
class UnplayableDramaException(message: String) : IOException(message)

class DramaRepository(
    private val dao: DramaDao,
    private val settings: SettingsStore,
    private val bilibili: BilibiliClient,
    private val recommendation: RecommendationEngine,
) {
    val trustedCreators = dao.observeTrustedCreators()
    val candidateCreators = dao.observeCandidateCreators()
    val blockedCreators = dao.observeBlockedCreators()
    val candidates = dao.observeCandidates()
    val history = dao.observeHistoryWithDrama()

    private val _contentVersion = MutableStateFlow(0L)

    /** 家属改动内容（信任、屏蔽、导入、同步）后递增，奶奶模式据此刷新队列。 */
    val contentVersion: StateFlow<Long> = _contentVersion.asStateFlow()

    private fun bumpContentVersion() {
        _contentVersion.value += 1
    }

    suspend fun buildQueue(): List<DramaEntity> = recommendation.buildQueue(dao.getPlayableWithWatch())

    suspend fun searchCandidates(keyword: String): Int {
        val positive = terms(settings.positiveTerms.first())
        val blocked = terms(settings.blockedTerms.first())
        val results = bilibili.search(keyword)
        val now = System.currentTimeMillis()
        val accepted = results.filter {
            recommendation.isAllowed(it.title, blocked) && durationAllowed(it.title, it.durationMs)
        }
        accepted.forEach { item ->
            val creator = dao.getCreator(item.ownerMid)
            if (creator?.blocked == true) return@forEach
            dao.upsertCreator(
                creator?.copy(name = item.ownerName.ifBlank { creator.name })
                    ?: CreatorEntity(item.ownerMid, item.ownerName),
            )
            // 已解析过的视频不再重建占位，避免和已有剧集条目重复。
            if (dao.getByBvid(item.bvid).isEmpty()) {
                dao.upsertDrama(placeholderEntity(item, creator, positive, now))
            }
        }
        settings.setLastGlobalSearch(now)
        if (accepted.isNotEmpty()) bumpContentVersion()
        return accepted.size
    }

    /** 只写数据库，立即生效；作品同步由调用方在后台执行。 */
    suspend fun trustCreator(mid: Long, fallbackName: String = "") {
        val old = dao.getCreator(mid)
        val name = old?.name?.ifBlank { fallbackName } ?: fallbackName.ifBlank {
            runCatching { bilibili.creatorName(mid) }.getOrDefault(mid.toString())
        }
        dao.upsertCreator(CreatorEntity(mid, name, trusted = true, blocked = false, lastSyncAt = old?.lastSyncAt ?: 0))
        dao.approveCreatorDramas(mid)
        bumpContentVersion()
    }

    suspend fun blockCreator(mid: Long) {
        val old = dao.getCreator(mid) ?: return
        dao.upsertCreator(old.copy(trusted = false, blocked = true))
        bumpContentVersion()
    }

    suspend fun unblockCreator(mid: Long) {
        val old = dao.getCreator(mid) ?: return
        dao.upsertCreator(old.copy(blocked = false))
        bumpContentVersion()
    }

    suspend fun untrustCreator(mid: Long) {
        val old = dao.getCreator(mid) ?: return
        dao.upsertCreator(old.copy(trusted = false))
        bumpContentVersion()
    }

    suspend fun creatorDramaCount(mid: Long): Int = dao.countCreatorDramas(mid)

    suspend fun refreshTrustedCreators(force: Boolean = false) {
        var changed = 0
        dao.getTrustedCreators().forEach { creator ->
            runCatching { changed += syncCreator(creator.mid, force = force) }
        }
        if (changed > 0) bumpContentVersion()
    }

    suspend fun autoDiscoverCandidates() {
        val now = System.currentTimeMillis()
        if (now - settings.lastGlobalSearch.first() < TimeUnit.DAYS.toMillis(7)) return
        settings.setLastGlobalSearch(now)
        runCatching { searchCandidates("AI短剧 全集") }
    }

    suspend fun syncCreator(mid: Long, force: Boolean = false): Int {
        val creator = dao.getCreator(mid) ?: throw IOException("作者不存在")
        val now = System.currentTimeMillis()
        if (!force && now - creator.lastSyncAt < TimeUnit.HOURS.toMillis(6)) return 0
        // 先记录尝试时间；即使触发风控，也不会在每次启动时立即重复请求。
        dao.setCreatorSyncTime(mid, now)
        val positive = terms(settings.positiveTerms.first())
        val blocked = terms(settings.blockedTerms.first())
        val uploads = bilibili.creatorUploads(mid, creator.name)
        val known = uploads.mapNotNull { upload ->
            dao.getByBvid(upload.bvid).firstOrNull()?.let { upload.bvid }
        }.toSet()
        val entities = uploads.filter {
            it.bvid !in known &&
                recommendation.isAllowed(it.title, blocked) &&
                durationAllowed(it.title, it.durationMs)
        }.map { item ->
            DramaEntity(
                id = "${item.bvid}:0",
                bvid = item.bvid,
                cid = 0,
                page = 1,
                title = item.title,
                seriesKey = "bv:${item.bvid}",
                seriesTitle = item.title,
                ownerMid = mid,
                ownerName = creator.name,
                coverUrl = item.coverUrl,
                durationMs = item.durationMs,
                width = 0,
                height = 0,
                publishedAt = item.publishedAt,
                playable = true,
                candidate = !creator.trusted,
                score = recommendation.score(item.title, 0, 0, positive),
                updatedAt = now,
            )
        }
        dao.upsertDramas(entities)
        if (entities.isNotEmpty()) bumpContentVersion()
        return entities.size
    }

    suspend fun importLink(rawLink: String): String {
        var link = rawLink.trim()
        if (link.contains("b23.tv", ignoreCase = true)) {
            link = bilibili.resolveShareUrl(link)
        }
        val mid = SPACE_REGEX.find(link)?.groupValues?.getOrNull(1)?.toLongOrNull()
        if (mid != null) {
            trustCreator(mid)
            runCatching { syncCreator(mid, force = true) }
            return "已添加可信作者"
        }
        val bvid = BVID_REGEX.find(link)?.value
            ?: throw IllegalArgumentException("没有识别到 BV 号或 UP 主空间地址")
        val details = bilibili.videoDetails(bvid, candidate = false)
        if (details.paidOrRestricted || details.items.isEmpty()) throw IOException("该视频无法公开完整播放")
        val positive = terms(settings.positiveTerms.first())
        val blocked = terms(settings.blockedTerms.first())
        val allowed = details.items.filter {
            recommendation.isAllowed(it.title, blocked) &&
                (details.items.size > 1 || durationAllowed(it.title, it.durationMs))
        }
        if (allowed.isEmpty()) throw IOException("内容被当前过滤词拦截")
        val first = allowed.first()
        val oldCreator = dao.getCreator(first.ownerMid)
        dao.upsertCreator(
            CreatorEntity(
                mid = first.ownerMid,
                name = first.ownerName,
                trusted = true,
                blocked = false,
                lastSyncAt = oldCreator?.lastSyncAt ?: 0,
            ),
        )
        dao.upsertDramas(allowed.map {
            it.copy(candidate = false, score = recommendation.score(it.title, it.width, it.height, positive)).toEntity()
        })
        bumpContentVersion()
        runCatching { syncCreator(first.ownerMid, force = true) }
        return "已添加《${first.seriesTitle.ifBlank { first.title }}》及作者 ${first.ownerName}"
    }

    /**
     * 占位条目（cid = 0）展开为完整剧集，按集数排序返回；已解析条目原样返回。
     * 展开结果写入数据库并删除占位。
     */
    suspend fun ensureResolved(entity: DramaEntity): List<DramaEntity> {
        if (entity.cid > 0) return listOf(entity)
        val positive = terms(settings.positiveTerms.first())
        val blocked = terms(settings.blockedTerms.first())
        val details = bilibili.videoDetails(entity.bvid, candidate = entity.candidate)
        if (details.paidOrRestricted || details.items.isEmpty()) {
            dao.markUnplayable(entity.id)
            throw UnplayableDramaException("视频已失效或需要付费")
        }
        val resolved = details.items
            .filter {
                recommendation.isAllowed(it.title, blocked) &&
                    (details.items.size > 1 || durationAllowed(it.title, it.durationMs))
            }
            .map { item ->
                item.copy(score = recommendation.score(item.title, item.width, item.height, positive)).toEntity()
            }
            .sortedBy { it.page }
        if (resolved.isEmpty()) {
            dao.markUnplayable(entity.id)
            throw UnplayableDramaException("视频不符合内容规则")
        }
        dao.upsertDramas(resolved)
        dao.deleteDrama(entity.id)
        return resolved
    }

    suspend fun playback(entity: DramaEntity, quality: Int = 64): PlaybackSource {
        val resolved = if (entity.cid > 0) entity else ensureResolved(entity).first()
        return bilibili.playback(resolved.bvid, resolved.cid, quality)
    }

    suspend fun watchState(id: String): WatchStateEntity? = dao.getWatchState(id)

    suspend fun saveProgress(
        drama: DramaEntity,
        positionMs: Long,
        durationMs: Long,
        skipped: Boolean = false,
    ) {
        val old = dao.getWatchState(drama.id)
        val safeDuration = durationMs.coerceAtLeast(drama.durationMs).coerceAtLeast(1)
        val completion = (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
        dao.upsertWatchState(
            WatchStateEntity(
                dramaId = drama.id,
                positionMs = if (completion >= 0.95f) 0 else positionMs,
                durationMs = safeDuration,
                completion = completion,
                skipCount = (old?.skipCount ?: 0) + if (skipped && positionMs < 15_000) 1 else 0,
                lastWatchedAt = System.currentTimeMillis(),
                completed = completion >= 0.8f,
            ),
        )
        settings.setLastDrama(drama.id)
    }

    suspend fun markUnplayable(id: String) = dao.markUnplayable(id)

    private fun placeholderEntity(
        item: SearchCandidate,
        creator: CreatorEntity?,
        positive: List<String>,
        now: Long,
    ) = DramaEntity(
        id = "${item.bvid}:0",
        bvid = item.bvid,
        cid = 0,
        page = 1,
        title = item.title,
        seriesKey = "bv:${item.bvid}",
        seriesTitle = item.title,
        ownerMid = item.ownerMid,
        ownerName = item.ownerName,
        coverUrl = item.coverUrl,
        durationMs = item.durationMs,
        width = 0,
        height = 0,
        publishedAt = item.publishedAt,
        playable = true,
        candidate = creator?.trusted != true,
        score = recommendation.score(item.title, 0, 0, positive),
        updatedAt = now,
    )

    private fun terms(value: String) = value.split(',', '，').map(String::trim).filter(String::isNotBlank)

    private fun durationAllowed(title: String, durationMs: Long): Boolean =
        durationMs <= 0 || durationMs >= 90_000 || listOf("全集", "完结", "合集").any(title::contains)

    companion object {
        private val BVID_REGEX = Regex("BV[0-9A-Za-z]{10}", RegexOption.IGNORE_CASE)
        private val SPACE_REGEX = Regex("space\\.bilibili\\.com/(\\d+)")
    }
}
