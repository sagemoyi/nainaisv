package com.xmoyi.nainaisv.recommendation

import com.xmoyi.nainaisv.data.DramaEntity
import com.xmoyi.nainaisv.data.DramaWithWatch

class RecommendationEngine {
    fun score(title: String, width: Int, height: Int, positiveTerms: List<String>): Int {
        var value = positiveTerms.count { it.isNotBlank() && title.contains(it, ignoreCase = true) } * 20
        if (height > width && width > 0) value += 18
        if (title.contains("全集") || title.contains("完结")) value += 25
        return value
    }

    fun isAllowed(title: String, blockedTerms: List<String>): Boolean = blockedTerms.none {
        it.isNotBlank() && title.contains(it, ignoreCase = true)
    }

    /**
     * 队列以剧集为单位：同一部剧的所有集连续且按集数排列，保证“播完自动下一部”
     * 播的是下一集而不是别的剧。剧集之间的顺序：
     * 1. 看了一半的剧排最前（最近看的优先），奶奶打开就能接着看；
     * 2. 没看过的剧按推荐分和发布时间排后面；
     * 3. 全部看完的剧排最后（最久没看的优先，可以循环重看）。
     */
    fun buildQueue(items: List<DramaWithWatch>): List<DramaEntity> {
        val series = items
            .distinctBy { it.drama.id }
            .groupBy { it.drama.seriesKey.ifBlank { "bv:${it.drama.bvid}" } }
            .map { (key, episodes) ->
                val ordered = episodes.sortedWith(
                    compareBy<DramaWithWatch> { it.drama.page }
                        .thenBy { it.drama.publishedAt }
                        .thenBy { it.drama.id },
                )
                SeriesGroup(
                    key = key,
                    episodes = ordered,
                    score = ordered.maxOf { it.drama.score },
                    publishedAt = ordered.maxOf { it.drama.publishedAt },
                    lastWatchedAt = ordered.maxOf { it.watch?.lastWatchedAt ?: 0L },
                    finished = ordered.all { it.watch?.completed == true },
                )
            }
        val inProgress = series
            .filter { it.lastWatchedAt > 0 && !it.finished }
            .sortedByDescending { it.lastWatchedAt }
        val fresh = series
            .filter { it.lastWatchedAt == 0L && !it.finished }
            .sortedWith(
                compareByDescending<SeriesGroup> { it.score }
                    .thenByDescending { it.publishedAt }
                    .thenBy { it.key },
            )
        val finished = series
            .filter { it.finished }
            .sortedBy { it.lastWatchedAt }
        return (inProgress + fresh + finished).flatMap { group -> group.episodes.map { it.drama } }
    }

    private data class SeriesGroup(
        val key: String,
        val episodes: List<DramaWithWatch>,
        val score: Int,
        val publishedAt: Long,
        val lastWatchedAt: Long,
        val finished: Boolean,
    )
}
