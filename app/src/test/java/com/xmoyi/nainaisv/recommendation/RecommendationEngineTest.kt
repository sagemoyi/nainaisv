package com.xmoyi.nainaisv.recommendation

import com.xmoyi.nainaisv.data.DramaEntity
import com.xmoyi.nainaisv.data.DramaWithWatch
import com.xmoyi.nainaisv.data.WatchStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    private val engine = RecommendationEngine()

    @Test
    fun `filters blocked terms`() {
        assertFalse(engine.isAllowed("AI短剧制作教程", listOf("教程", "接稿")))
        assertTrue(engine.isAllowed("治愈田园生活全集", listOf("教程", "接稿")))
    }

    @Test
    fun `prefers unseen portrait complete stories`() {
        val unseen = drama("new", score = 60, width = 1080, height = 1920)
        val watched = drama("old", score = 80, width = 1080, height = 1920)
        val queue = engine.buildQueue(
            listOf(
                DramaWithWatch(
                    watched,
                    WatchStateEntity(watched.id, completion = 0.9f, completed = true, lastWatchedAt = 100),
                ),
                DramaWithWatch(unseen, null),
            ),
        )
        assertEquals(listOf("new", "old"), queue.map { it.id })
    }

    @Test
    fun `demotes repeatedly skipped stories`() {
        val skipped = drama("skipped", score = 90, width = 1080, height = 1920)
        val kept = drama("kept", score = 10, width = 1080, height = 1920)
        val queue = engine.buildQueue(
            listOf(
                DramaWithWatch(
                    skipped,
                    WatchStateEntity(skipped.id, skipCount = 3, completion = 0.1f, lastWatchedAt = 100),
                ),
                DramaWithWatch(kept, null),
            ),
        )
        assertEquals(listOf("kept", "skipped"), queue.map { it.id })
    }

    private fun drama(id: String, score: Int, width: Int, height: Int) = DramaEntity(
        id = id,
        bvid = "BV1234567890",
        cid = id.hashCode().toLong(),
        page = 1,
        title = id,
        ownerMid = 1,
        ownerName = "作者",
        coverUrl = "",
        durationMs = 100_000,
        width = width,
        height = height,
        publishedAt = 1,
        playable = true,
        candidate = false,
        score = score,
        updatedAt = 1,
    )
}
