package com.xmoyi.nainaisv.data

import com.xmoyi.nainaisv.network.BilibiliClient
import com.xmoyi.nainaisv.recommendation.RecommendationEngine
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

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
            dao.upsertDrama(
                DramaEntity(
                    id = "${item.bvid}:0",
                    bvid = item.bvid,
                    cid = 0,
                    page = 1,
                    title = item.title,
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
                ),
            )
        }
        settings.setLastGlobalSearch(now)
        return accepted.size
    }

    suspend fun trustCreator(mid: Long, fallbackName: String = "") {
        val old = dao.getCreator(mid)
        val name = old?.name?.ifBlank { fallbackName } ?: fallbackName.ifBlank {
            runCatching { bilibili.creatorName(mid) }.getOrDefault(mid.toString())
        }
        dao.upsertCreator(CreatorEntity(mid, name, trusted = true, blocked = false, lastSyncAt = old?.lastSyncAt ?: 0))
        dao.setDramasCandidate(mid, false)
        syncCreator(mid, force = true)
    }

    suspend fun blockCreator(mid: Long) {
        val old = dao.getCreator(mid) ?: return
        dao.upsertCreator(old.copy(trusted = false, blocked = true))
    }

    suspend fun unblockCreator(mid: Long) {
        val old = dao.getCreator(mid) ?: return
        dao.upsertCreator(old.copy(trusted = false, blocked = false))
        dao.setDramasCandidate(mid, true)
    }

    suspend fun untrustCreator(mid: Long) {
        val old = dao.getCreator(mid) ?: return
        dao.upsertCreator(old.copy(trusted = false))
        dao.setDramasCandidate(mid, true)
    }

    suspend fun refreshTrustedCreators(force: Boolean = false) {
        dao.getTrustedCreators().forEach { creator ->
            runCatching { syncCreator(creator.mid, force = force) }
        }
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
        val entities = uploads.filter {
            recommendation.isAllowed(it.title, blocked) && durationAllowed(it.title, it.durationMs)
        }.map { item ->
            DramaEntity(
                id = "${item.bvid}:0",
                bvid = item.bvid,
                cid = 0,
                page = 1,
                title = item.title,
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
        runCatching { syncCreator(first.ownerMid, force = true) }
        return "已添加《${first.title}》及作者 ${first.ownerName}"
    }

    suspend fun ensureResolved(entity: DramaEntity): DramaEntity = ensureResolvedAll(entity).first()

    suspend fun ensureResolvedAll(entity: DramaEntity): List<DramaEntity> {
        if (entity.cid > 0) return listOf(entity)
        val positive = terms(settings.positiveTerms.first())
        val blocked = terms(settings.blockedTerms.first())
        val details = bilibili.videoDetails(entity.bvid, candidate = entity.candidate)
        if (details.paidOrRestricted || details.items.isEmpty()) {
            dao.markUnplayable(entity.id)
            throw IOException("视频已失效或需要付费")
        }
        val resolved = details.items
            .filter {
                recommendation.isAllowed(it.title, blocked) &&
                    (details.items.size > 1 || durationAllowed(it.title, it.durationMs))
            }
            .map { item ->
                item.copy(score = recommendation.score(item.title, item.width, item.height, positive)).toEntity()
            }
        if (resolved.isEmpty()) {
            dao.markUnplayable(entity.id)
            throw IOException("视频不符合内容规则")
        }
        dao.upsertDramas(resolved)
        dao.deleteDrama(entity.id)
        return resolved
    }

    suspend fun playback(entity: DramaEntity, quality: Int = 64): PlaybackSource {
        val resolved = ensureResolved(entity)
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

    private fun terms(value: String) = value.split(',', '，').map(String::trim).filter(String::isNotBlank)

    private fun durationAllowed(title: String, durationMs: Long): Boolean =
        durationMs <= 0 || durationMs >= 90_000 || listOf("全集", "完结", "合集").any(title::contains)

    companion object {
        private val BVID_REGEX = Regex("BV[0-9A-Za-z]{10}", RegexOption.IGNORE_CASE)
        private val SPACE_REGEX = Regex("space\\.bilibili\\.com/(\\d+)")
    }
}
