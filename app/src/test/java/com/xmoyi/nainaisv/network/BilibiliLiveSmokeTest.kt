package com.xmoyi.nainaisv.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BilibiliLiveSmokeTest {
    @Test
    fun publicVideoCanResolve() = runBlocking {
        assumeTrue(System.getenv("LIVE_BILI_TEST") == "1")
        val client = BilibiliClient()
        val details = client.videoDetails("BV1GJ411x7h7")
        assertTrue(details.items.isNotEmpty())
        val first = details.items.first()
        val source = client.playback(first.bvid, first.cid)
        assertTrue(source.urls.isNotEmpty())
        assertTrue(source.quality == 64 || source.quality == 32)
    }

    @Test
    fun searchAndWbiCreatorUploadsWork() = runBlocking {
        assumeTrue(System.getenv("LIVE_BILI_TEST") == "1")
        val client = BilibiliClient()
        val candidates = client.search("AI短剧 全集")
        assertTrue(candidates.isNotEmpty())
        val first = candidates.first()
        assertTrue(client.creatorName(first.ownerMid).isNotBlank())
        val uploads = client.creatorUploads(first.ownerMid, first.ownerName)
        assertTrue(uploads.isNotEmpty())
    }
}
