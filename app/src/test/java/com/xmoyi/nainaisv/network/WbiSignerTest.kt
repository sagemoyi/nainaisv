package com.xmoyi.nainaisv.network

import org.junit.Assert.assertEquals
import org.junit.Test

class WbiSignerTest {
    private val signer = WbiSigner()

    @Test
    fun `creates documented mixin key`() {
        assertEquals(
            "ea1db124af3c7062474693fa704f4ff8",
            signer.mixinKey(
                "7cd084941338484aae1ad9425b84077c",
                "4932caff0ff746eab6f01bf08b70ac45",
            ),
        )
    }

    @Test
    fun `signs parameters deterministically`() {
        val url = signer.sign(
            baseUrl = "https://api.bilibili.com/x/space/wbi/arc/search",
            parameters = mapOf("foo" to "114", "bar" to "514", "baz" to "1919810"),
            imageKey = "7cd084941338484aae1ad9425b84077c",
            subKey = "4932caff0ff746eab6f01bf08b70ac45",
            timestampSeconds = 1_702_204_169,
        )
        assertEquals(
            "https://api.bilibili.com/x/space/wbi/arc/search?bar=514&baz=1919810&foo=114&wts=1702204169&w_rid=6149fdadf571698ca7e6a567265cd0ee",
            url,
        )
    }
}
