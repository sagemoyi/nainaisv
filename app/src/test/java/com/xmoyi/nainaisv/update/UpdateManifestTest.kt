package com.xmoyi.nainaisv.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateManifestTest {
    @Test
    fun `parses valid r2 manifest`() {
        val manifest = UpdateManifest.parse(
            """
            {
              "versionCode": 2,
              "versionName": "1.1.0",
              "minSupportedVersionCode": 1,
              "apkUrl": "https://app.xmoyi.com/nainaisv/releases/nainaisv-1.1.0.apk",
              "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "size": 12345678,
              "publishedAt": "2026-07-17T12:00:00+08:00",
              "releaseNotes": "修复播放问题"
            }
            """.trimIndent(),
        )
        assertEquals(2, manifest.versionCode)
        assertEquals("1.1.0", manifest.versionName)
        assertEquals(12_345_678, manifest.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non https apk`() {
        UpdateManifest.parse(
            """{"versionCode":2,"versionName":"1.1.0","apkUrl":"http://app.xmoyi.com/nainaisv/releases/app.apk","sha256":"${"a".repeat(64)}","size":1}""",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects apk from another host`() {
        UpdateManifest.parse(
            """{"versionCode":2,"versionName":"1.1.0","apkUrl":"https://example.com/nainaisv/releases/app.apk","sha256":"${"a".repeat(64)}","size":1}""",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non semantic version name`() {
        UpdateManifest.parse(
            """{"versionCode":2,"versionName":"release-2","apkUrl":"https://app.xmoyi.com/nainaisv/releases/app.apk","sha256":"${"a".repeat(64)}","size":1}""",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects semantic version with leading zero`() {
        UpdateManifest.parse(
            """{"versionCode":2,"versionName":"01.1.0","apkUrl":"https://app.xmoyi.com/nainaisv/releases/app.apk","sha256":"${"a".repeat(64)}","size":1}""",
        )
    }
}
