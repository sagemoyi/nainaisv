package com.xmoyi.nainaisv.network

import com.xmoyi.nainaisv.data.CreatorUpload
import com.xmoyi.nainaisv.data.DramaItem
import com.xmoyi.nainaisv.data.PlaybackSource
import com.xmoyi.nainaisv.data.SearchCandidate
import com.xmoyi.nainaisv.data.VideoDetails
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class BilibiliClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val signer: WbiSigner = WbiSigner(),
    private val apiBase: String = API_BASE,
    private val webBase: String = WEB_BASE,
) {
    @Volatile private var wbiKeys: WbiKeys? = null

    suspend fun search(keyword: String): List<SearchCandidate> = withContext(Dispatchers.IO) {
        val url = "$apiBase/x/web-interface/search/all/v2".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", keyword)
            .addQueryParameter("page", "1")
            .build()
        val root = getJson(url.toString(), SEARCH_REFERER)
        val result = root.dataObject().optJSONArray("result") ?: JSONArray()
        val videos = mutableListOf<SearchCandidate>()
        for (i in 0 until result.length()) {
            val group = result.optJSONObject(i) ?: continue
            if (group.optString("result_type") != "video") continue
            val data = group.optJSONArray("data") ?: continue
            for (j in 0 until data.length()) {
                val item = data.optJSONObject(j) ?: continue
                val bvid = item.optString("bvid")
                val mid = item.optLong("mid")
                if (bvid.isBlank() || mid <= 0) continue
                videos += SearchCandidate(
                    bvid = bvid,
                    title = stripHtml(item.optString("title")),
                    ownerMid = mid,
                    ownerName = item.optString("author"),
                    coverUrl = https(item.optString("pic")),
                    durationMs = parseDuration(item.optString("duration")),
                    publishedAt = item.optLong("pubdate") * 1000,
                )
            }
        }
        videos.distinctBy { it.bvid }
    }

    suspend fun resolveShareUrl(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response -> response.request.url.toString() }
    }

    suspend fun creatorUploads(mid: Long, ownerName: String = ""): List<CreatorUpload> =
        withContext(Dispatchers.IO) {
            val keys = getWbiKeys()
            val parameters = mapOf(
                "mid" to mid.toString(),
                "pn" to "1",
                "ps" to "30",
                "order" to "pubdate",
                "index" to "1",
            )
            val url = signer.sign(
                "$apiBase/x/space/wbi/arc/search",
                parameters,
                keys.imageKey,
                keys.subKey,
            )
            val root = getJson(url, "https://space.bilibili.com/$mid/video")
            val list = root.dataObject().optJSONObject("list")
                ?.optJSONArray("vlist") ?: JSONArray()
            buildList {
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val bvid = item.optString("bvid")
                    if (bvid.isBlank()) continue
                    add(
                        CreatorUpload(
                            bvid = bvid,
                            title = item.optString("title"),
                            ownerMid = mid,
                            ownerName = ownerName.ifBlank { item.optString("author") },
                            coverUrl = https(item.optString("pic")),
                            durationMs = parseDuration(item.optString("length")),
                            publishedAt = item.optLong("created") * 1000,
                        ),
                    )
                }
            }
        }

    suspend fun videoDetails(bvid: String, candidate: Boolean = false): VideoDetails =
        withContext(Dispatchers.IO) {
            val url = "$apiBase/x/web-interface/view".toHttpUrl().newBuilder()
                .addQueryParameter("bvid", bvid)
                .build()
            val data = getJson(url.toString(), "$webBase/video/$bvid").dataObject()
            val owner = data.optJSONObject("owner") ?: JSONObject()
            val rights = data.optJSONObject("rights") ?: JSONObject()
            val restricted = rights.optInt("arc_pay") == 1 ||
                rights.optInt("ugc_pay") == 1 ||
                rights.optInt("is_upower_exclusive") == 1 ||
                rights.optInt("is_upower_play") == 1 ||
                rights.optInt("ugc_pay_preview") == 1 ||
                data.optBoolean("is_upower_exclusive") ||
                data.optBoolean("is_upower_play") ||
                data.optBoolean("is_chargeable_season")

            val defaultDimension = data.optJSONObject("dimension") ?: JSONObject()
            val commonTitle = data.optString("title")
            val commonCover = https(data.optString("pic"))
            val commonMid = owner.optLong("mid")
            val commonName = owner.optString("name")
            val commonPubdate = data.optLong("pubdate") * 1000
            val season = data.optJSONObject("ugc_season")
            val items = mutableListOf<DramaItem>()

            val pages = data.optJSONArray("pages") ?: JSONArray()
            val multiPage = pages.length() > 1
            for (i in 0 until pages.length()) {
                val page = pages.optJSONObject(i) ?: continue
                val cid = page.optLong("cid")
                if (cid <= 0) continue
                val dimension = page.optJSONObject("dimension") ?: defaultDimension
                val part = page.optString("part")
                items += DramaItem(
                    id = "$bvid:$cid",
                    bvid = bvid,
                    cid = cid,
                    page = page.optInt("page", i + 1),
                    title = if (multiPage && part.isNotBlank()) part else commonTitle,
                    seriesKey = "bv:$bvid",
                    seriesTitle = commonTitle,
                    ownerMid = commonMid,
                    ownerName = commonName,
                    coverUrl = commonCover,
                    durationMs = page.optLong("duration") * 1000,
                    width = dimension.optInt("width"),
                    height = dimension.optInt("height"),
                    publishedAt = commonPubdate,
                    playable = !restricted && data.optInt("state") >= 0,
                    candidate = candidate,
                )
            }
            if (season != null) {
                val seasonId = season.optLong("id")
                val seasonTitle = season.optString("title").ifBlank { commonTitle }
                val seriesKey = if (seasonId > 0) "season:$seasonId" else "bv:$bvid"
                val sections = season.optJSONArray("sections") ?: JSONArray()
                var episodeNumber = 0
                for (sectionIndex in 0 until sections.length()) {
                    val episodes = sections.optJSONObject(sectionIndex)?.optJSONArray("episodes") ?: continue
                    for (episodeIndex in 0 until episodes.length()) {
                        val episode = episodes.optJSONObject(episodeIndex) ?: continue
                        val episodeBvid = episode.optString("bvid")
                        val episodeCid = episode.optLong("cid")
                        if (episodeBvid.isBlank() || episodeCid <= 0) continue
                        episodeNumber += 1
                        val arc = episode.optJSONObject("arc") ?: JSONObject()
                        val episodePage = episode.optJSONObject("page") ?: JSONObject()
                        val dimension = episodePage.optJSONObject("dimension")
                            ?: arc.optJSONObject("dimension")
                            ?: defaultDimension
                        items += DramaItem(
                            id = "$episodeBvid:$episodeCid",
                            bvid = episodeBvid,
                            cid = episodeCid,
                            page = episodeNumber,
                            title = episode.optString("title").ifBlank { arc.optString("title", commonTitle) },
                            seriesKey = seriesKey,
                            seriesTitle = seasonTitle,
                            ownerMid = commonMid,
                            ownerName = commonName,
                            coverUrl = https(arc.optString("pic", commonCover)),
                            durationMs = (episodePage.optLong("duration").takeIf { it > 0 }
                                ?: arc.optLong("duration").takeIf { it > 0 }
                                ?: 0) * 1000,
                            width = dimension.optInt("width"),
                            height = dimension.optInt("height"),
                            publishedAt = arc.optLong("pubdate").takeIf { it > 0 }?.times(1000) ?: commonPubdate,
                            playable = !restricted && arc.optInt("state", 0) >= 0,
                            candidate = candidate,
                        )
                    }
                }
            }
            // 同一视频既出现在自身分 P，又出现在合集里时，保留合集条目（带全局集数）。
            val deduped = items.reversed().distinctBy { it.id }.reversed()
            VideoDetails(deduped, restricted)
        }

    suspend fun playback(bvid: String, cid: Long, preferredQuality: Int = 64): PlaybackSource =
        withContext(Dispatchers.IO) {
            val qualities = if (preferredQuality >= 64) listOf(64, 32) else listOf(32)
            var lastError: Throwable? = null
            for (quality in qualities) {
                try {
                    val url = "$apiBase/x/player/playurl".toHttpUrl().newBuilder()
                        .addQueryParameter("bvid", bvid)
                        .addQueryParameter("cid", cid.toString())
                        .addQueryParameter("qn", quality.toString())
                        .addQueryParameter("fnval", "0")
                        .addQueryParameter("fourk", "0")
                        .addQueryParameter("platform", "html5")
                        .build()
                    val data = getJson(url.toString(), "$webBase/video/$bvid").dataObject()
                    val durl = data.optJSONArray("durl") ?: JSONArray()
                    val urls = mutableListOf<String>()
                    val backups = mutableListOf<String>()
                    for (i in 0 until durl.length()) {
                        val segment = durl.optJSONObject(i) ?: continue
                        segment.optString("url").takeIf(String::isNotBlank)?.let(urls::add)
                        val backupArray = segment.optJSONArray("backup_url") ?: JSONArray()
                        for (j in 0 until backupArray.length()) {
                            backupArray.optString(j).takeIf(String::isNotBlank)?.let(backups::add)
                        }
                    }
                    if (urls.isNotEmpty()) {
                        return@withContext PlaybackSource(
                            urls = urls,
                            backupUrls = backups,
                            headers = mapOf("Referer" to "$WEB_BASE/", "User-Agent" to USER_AGENT),
                            quality = data.optInt("quality", quality),
                            expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(90),
                        )
                    }
                } catch (error: Throwable) {
                    lastError = error
                }
            }
            throw IOException("无法获取公开播放地址", lastError)
        }

    suspend fun creatorName(mid: Long): String = withContext(Dispatchers.IO) {
        val url = "$apiBase/x/space/wbi/acc/info"
        val keys = getWbiKeys()
        val signed = signer.sign(url, mapOf("mid" to mid.toString()), keys.imageKey, keys.subKey)
        getJson(signed, "https://space.bilibili.com/$mid").dataObject().optString("name", mid.toString())
    }

    private suspend fun getWbiKeys(): WbiKeys {
        val cached = wbiKeys
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) return cached
        return withContext(Dispatchers.IO) {
            val data = getJson(
                "$apiBase/x/web-interface/nav",
                webBase,
                allowedCodes = setOf(0, -101),
            ).dataObject()
            val wbi = data.optJSONObject("wbi_img") ?: throw IOException("WBI key missing")
            val keys = WbiKeys(
                imageKey = fileKey(wbi.optString("img_url")),
                subKey = fileKey(wbi.optString("sub_url")),
                expiresAt = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(6),
            )
            wbiKeys = keys
            keys
        }
    }

    private fun getJson(url: String, referer: String, allowedCodes: Set<Int> = setOf(0)): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty response")
            val root = JSONObject(body)
            if (root.optInt("code") !in allowedCodes) {
                throw IOException(root.optString("message", "Bilibili API error"))
            }
            return root
        }
    }

    private fun JSONObject.dataObject(): JSONObject = optJSONObject("data")
        ?: throw IOException("Missing data")

    private fun fileKey(url: String): String = url.substringAfterLast('/').substringBefore('.')
    private fun stripHtml(value: String) = value.replace(Regex("<[^>]+>"), "")
    private fun https(value: String) = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> value.replaceFirst("http://", "https://")
        else -> value
    }

    private fun parseDuration(value: String): Long {
        val parts = value.split(':').mapNotNull(String::toLongOrNull)
        if (parts.isEmpty()) return 0
        return parts.fold(0L) { total, part -> total * 60 + part } * 1000
    }

    private data class WbiKeys(val imageKey: String, val subKey: String, val expiresAt: Long)

    companion object {
        const val API_BASE = "https://api.bilibili.com"
        const val WEB_BASE = "https://www.bilibili.com"
        const val SEARCH_REFERER = "https://search.bilibili.com/"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36 NaiNaiSV/1.0"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
