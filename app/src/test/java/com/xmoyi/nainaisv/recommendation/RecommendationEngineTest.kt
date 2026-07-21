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
    fun `episodes of one series stay together and in order`() {
        val queue = engine.buildQueue(
            listOf(
                DramaWithWatch(drama("a:3", series = "season:1", page = 3, score = 50), null),
                DramaWithWatch(drama("b:1", series = "bv:other", page = 1, score = 90), null),
                DramaWithWatch(drama("a:1", series = "season:1", page = 1, score = 50), null),
                DramaWithWatch(drama("a:2", series = "season:1", page = 2, score = 50), null),
            ),
        )
        val seriesA = queue.filter { it.seriesKey == "season:1" }.map { it.id }
        assertEquals(listOf("a:1", "a:2", "a:3"), seriesA)
        val firstA = queue.indexOfFirst { it.seriesKey == "season:1" }
        assertEquals(listOf("a:1", "a:2", "a:3"), queue.subList(firstA, firstA + 3).map { it.id })
    }

    @Test
    fun `series in progress comes before fresh and finished series`() {
        val inProgress = drama("watching:1", series = "s:watching", page = 1, score = 10)
        val fresh = drama("fresh:1", series = "s:fresh", page = 1, score = 99)
        val done = drama("done:1", series = "s:done", page = 1, score = 99)
        val queue = engine.buildQueue(
            listOf(
                DramaWithWatch(fresh, null),
                DramaWithWatch(
                    done,
                    WatchStateEntity(done.id, completion = 0.9f, completed = true, lastWatchedAt = 50),
                ),
                DramaWithWatch(
                    inProgress,
                    WatchStateEntity(inProgress.id, completion = 0.4f, completed = false, lastWatchedAt = 100),
                ),
            ),
        )
        assertEquals(listOf("watching:1", "fresh:1", "done:1"), queue.map { it.id })
    }

    @Test
    fun `partially watched series keeps unwatched episodes with it`() {
        val queue = engine.buildQueue(
            listOf(
                DramaWithWatch(
                    drama("a:1", series = "s:a", page = 1, score = 10),
                    WatchStateEntity("a:1", completion = 0.95f, completed = true, lastWatchedAt = 100),
                ),
                DramaWithWatch(drama("a:2", series = "s:a", page = 2, score = 10), null),
                DramaWithWatch(drama("b:1", series = "s:b", page = 1, score = 80), null),
            ),
        )
        // 看了一半的剧整体排最前，集数顺序不变
        assertEquals(listOf("a:1", "a:2", "b:1"), queue.map { it.id })
    }

    @Test
    fun `fresh series ordered by score`() {
        val queue = engine.buildQueue(
            listOf(
                DramaWithWatch(drama("low:1", series = "s:low", page = 1, score = 10), null),
                DramaWithWatch(drama("high:1", series = "s:high", page = 1, score = 90), null),
            ),
        )
        assertEquals(listOf("high:1", "low:1"), queue.map { it.id })
    }

    @Test
    fun `repeatedly skipped series drops behind fresh series`() {
        val skipped = drama("skipped:1", series = "s:skipped", page = 1, score = 90)
        val fresh = drama("fresh:1", series = "s:fresh", page = 1, score = 10)
        val queue = engine.buildQueue(
            listOf(
                DramaWithWatch(
                    skipped,
                    WatchStateEntity(skipped.id, completion = 0.05f, skipCount = 2, lastWatchedAt = 100),
                ),
                DramaWithWatch(fresh, null),
            ),
        )
        assertEquals(listOf("fresh:1", "skipped:1"), queue.map { it.id })
    }

    @Test
    fun `series with real progress stays first even after one skip`() {
        val watching = drama("watching:1", series = "s:watching", page = 1, score = 10)
        val fresh = drama("fresh:1", series = "s:fresh", page = 1, score = 90)
        val queue = engine.buildQueue(
            listOf(
                DramaWithWatch(
                    watching,
                    WatchStateEntity(watching.id, completion = 0.4f, skipCount = 1, lastWatchedAt = 100),
                ),
                DramaWithWatch(fresh, null),
            ),
        )
        assertEquals(listOf("watching:1", "fresh:1"), queue.map { it.id })
    }

    @Test
    fun `blank series key falls back to bvid`() {
        val queue = engine.buildQueue(
            listOf(
                DramaWithWatch(drama("x:2", series = "", page = 2, score = 10, bvid = "BVX"), null),
                DramaWithWatch(drama("x:1", series = "", page = 1, score = 10, bvid = "BVX"), null),
            ),
        )
        assertEquals(listOf("x:1", "x:2"), queue.map { it.id })
    }

    private fun drama(
        id: String,
        series: String,
        page: Int,
        score: Int,
        bvid: String = "BV1234567890",
    ) = DramaEntity(
        id = id,
        bvid = bvid,
        cid = id.hashCode().toLong(),
        page = page,
        title = id,
        seriesKey = series,
        seriesTitle = "剧集",
        ownerMid = 1,
        ownerName = "作者",
        coverUrl = "",
        durationMs = 100_000,
        width = 1080,
        height = 1920,
        publishedAt = 1,
        playable = true,
        candidate = false,
        score = score,
        updatedAt = 1,
    )
}
