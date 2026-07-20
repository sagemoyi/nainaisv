package com.xmoyi.nainaisv.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BilibiliClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: BilibiliClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = BilibiliClient(
            http = OkHttpClient(),
            apiBase = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `videoDetails parses multi page video as one series`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"data":{
                  "bvid":"BV1abc","title":"田园故事","pic":"//i0.hdslb.com/cover.jpg",
                  "pubdate":1700000000,"state":0,
                  "owner":{"mid":42,"name":"作者甲"},
                  "rights":{"arc_pay":0,"ugc_pay":0},
                  "dimension":{"width":1080,"height":1920},
                  "pages":[
                    {"cid":11,"page":1,"part":"第一集","duration":300,"dimension":{"width":1080,"height":1920}},
                    {"cid":12,"page":2,"part":"第二集","duration":301}
                  ]
                }}
                """.trimIndent(),
            ),
        )
        val details = client.videoDetails("BV1abc")
        assertFalse(details.paidOrRestricted)
        assertEquals(2, details.items.size)
        val first = details.items[0]
        assertEquals("BV1abc:11", first.id)
        assertEquals("bv:BV1abc", first.seriesKey)
        assertEquals("田园故事", first.seriesTitle)
        assertEquals("第一集", first.title)
        assertEquals(1, first.page)
        assertEquals("https://i0.hdslb.com/cover.jpg", first.coverUrl)
        assertEquals(2, details.items[1].page)
        assertEquals("第二集", details.items[1].title)
    }

    @Test
    fun `videoDetails parses ugc season with global episode order`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"data":{
                  "bvid":"BV1s1","title":"合集第一集","pic":"//i0.hdslb.com/e1.jpg",
                  "pubdate":1700000000,"state":0,
                  "owner":{"mid":42,"name":"作者甲"},
                  "rights":{},
                  "pages":[{"cid":21,"page":1,"part":"","duration":200}],
                  "ugc_season":{
                    "id":999,"title":"年代大剧全集",
                    "sections":[{"episodes":[
                      {"bvid":"BV1s1","cid":21,"title":"第1集","arc":{"pic":"//i0.hdslb.com/e1.jpg","duration":200,"pubdate":1700000000,"state":0}},
                      {"bvid":"BV1s2","cid":22,"title":"第2集","arc":{"pic":"//i0.hdslb.com/e2.jpg","duration":210,"pubdate":1700000100,"state":0}}
                    ]}]
                  }
                }}
                """.trimIndent(),
            ),
        )
        val details = client.videoDetails("BV1s1")
        assertEquals(2, details.items.size)
        val ep1 = details.items.first { it.id == "BV1s1:21" }
        val ep2 = details.items.first { it.id == "BV1s2:22" }
        // 同一合集条目共享 seriesKey，并按合集内顺序编号
        assertEquals("season:999", ep1.seriesKey)
        assertEquals("season:999", ep2.seriesKey)
        assertEquals("年代大剧全集", ep1.seriesTitle)
        assertEquals(1, ep1.page)
        assertEquals(2, ep2.page)
        assertEquals("第1集", ep1.title)
    }

    @Test
    fun `videoDetails marks paid video restricted`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"data":{
                  "bvid":"BV1pay","title":"付费内容","pic":"","pubdate":1,"state":0,
                  "owner":{"mid":1,"name":"n"},
                  "rights":{"ugc_pay":1},
                  "pages":[{"cid":31,"page":1,"part":"","duration":100}]
                }}
                """.trimIndent(),
            ),
        )
        val details = client.videoDetails("BV1pay")
        assertTrue(details.paidOrRestricted)
        assertFalse(details.items.first().playable)
    }

    @Test
    fun `playback falls back to 480p when 720p fails`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val quality = request.requestUrl?.queryParameter("qn")
                return if (quality == "64") {
                    MockResponse().setBody("""{"code":-404,"message":"啥都木有"}""")
                } else {
                    MockResponse().setBody(
                        """
                        {"code":0,"data":{"quality":32,"durl":[
                          {"url":"https://cdn.example.com/video.mp4",
                           "backup_url":["https://cdn2.example.com/video.mp4"]}
                        ]}}
                        """.trimIndent(),
                    )
                }
            }
        }
        val source = client.playback("BV1abc", 11, preferredQuality = 64)
        assertEquals(32, source.quality)
        assertEquals(listOf("https://cdn.example.com/video.mp4"), source.urls)
        assertEquals(listOf("https://cdn2.example.com/video.mp4"), source.backupUrls)
        assertTrue(source.headers.containsKey("Referer"))
        assertTrue(source.expiresAt > System.currentTimeMillis())
    }

    @Test
    fun `search parses video results and strips html`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"data":{"result":[
                  {"result_type":"activity","data":[]},
                  {"result_type":"video","data":[
                    {"bvid":"BV1x1","mid":7,"author":"作者乙",
                     "title":"<em class=\"keyword\">AI短剧</em>全集",
                     "pic":"//i0.hdslb.com/x1.jpg","duration":"12:30","pubdate":1700000000},
                    {"bvid":"","mid":8,"author":"无效","title":"t","pic":"","duration":"1:00","pubdate":1}
                  ]}
                ]}}
                """.trimIndent(),
            ),
        )
        val results = client.search("AI短剧")
        assertEquals(1, results.size)
        val item = results.first()
        assertEquals("BV1x1", item.bvid)
        assertEquals("AI短剧全集", item.title)
        assertEquals((12 * 60 + 30) * 1000L, item.durationMs)
        assertEquals("https://i0.hdslb.com/x1.jpg", item.coverUrl)
    }

    @Test
    fun `creatorUploads signs request with wbi and parses vlist`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                return when {
                    path.endsWith("/x/web-interface/nav") -> MockResponse().setBody(
                        """
                        {"code":-101,"message":"未登录","data":{"wbi_img":{
                          "img_url":"https://i0.hdslb.com/bfs/wbi/abc123.png",
                          "sub_url":"https://i0.hdslb.com/bfs/wbi/def456.png"
                        }}}
                        """.trimIndent(),
                    )
                    path.endsWith("/x/space/wbi/arc/search") -> {
                        if (request.requestUrl?.queryParameter("w_rid").isNullOrBlank()) {
                            MockResponse().setBody("""{"code":-403,"message":"缺少签名"}""")
                        } else {
                            MockResponse().setBody(
                                """
                                {"code":0,"data":{"list":{"vlist":[
                                  {"bvid":"BV1up1","title":"上传1","author":"作者丙",
                                   "pic":"//i0.hdslb.com/u1.jpg","length":"05:00","created":1700000000}
                                ]}}}
                                """.trimIndent(),
                            )
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val uploads = client.creatorUploads(9, "作者丙")
        assertEquals(1, uploads.size)
        assertEquals("BV1up1", uploads.first().bvid)
        assertEquals(5 * 60 * 1000L, uploads.first().durationMs)
        assertEquals(9, uploads.first().ownerMid)
    }
}
