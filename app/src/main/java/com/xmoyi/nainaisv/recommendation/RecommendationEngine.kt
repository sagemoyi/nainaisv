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

    fun buildQueue(items: List<DramaWithWatch>): List<DramaEntity> {
        val now = System.currentTimeMillis()
        return items
            .distinctBy { it.drama.id }
            .sortedWith(
                compareByDescending<DramaWithWatch> { item ->
                    val watch = item.watch
                    watch == null || (!watch.completed && watch.completion < 0.8f)
                }.thenByDescending { item ->
                    // 奶奶连续快速滑走的内容往后放。
                    (item.watch?.skipCount ?: 0) < 2
                }.thenByDescending { it.drama.score }
                    .thenByDescending { it.drama.height > it.drama.width }
                    .thenBy { item ->
                        item.watch?.lastWatchedAt?.takeIf { it > 0 } ?: Long.MIN_VALUE
                    }
                    .thenByDescending { item ->
                        val ageDays = (now - item.drama.publishedAt).coerceAtLeast(0) / 86_400_000
                        -ageDays
                    }
                    .thenBy { it.drama.bvid }
                    .thenBy { it.drama.page },
            )
            .map { it.drama }
    }
}
