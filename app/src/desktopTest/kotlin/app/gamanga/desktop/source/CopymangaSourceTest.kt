package app.gamanga.desktop.source

import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 拷贝漫画源解析纯函数测试（第八轮 #9）。
 *
 * fixture 用社区逆向文档里的真实响应样本（51CTO 实战文的抓包结果）——
 * 字段名/层级以那份样本为准。站点本身当前网络不可达（未实测），
 * 这些测试锁的是「给定这个结构的响应，解析输出正确」。
 */
class CopymangaSourceTest {

    @Test
    fun `single named short story is complete while navigation is not`() = kotlinx.coroutines.runBlocking {
        val named = parseCopymangaDetailDom("""{"title":"短篇","chapters":[{"id":"a","name":"全一篇"}]}""", "pw")!!
        assertTrue(SourceDetails(named.first, named.second).chaptersComplete)
        val nav = parseCopymangaDetailDom("""{"title":"短篇","chapters":[{"id":"a","name":"開始閱讀"}]}""", "pw")!!
        assertTrue(!SourceDetails(nav.first, nav.second).chaptersComplete)
    }

    @Test
    fun `detail and chapters share incomplete snapshot and refresh bypasses cache`() = kotlinx.coroutines.runBlocking {
        var apiCalls = 0
        var domCalls = 0
        var now = 100L
        val source = CopymangaSource(apiReader = { apiCalls++; "{}" }, clock = { now }, detailReader = {
            domCalls++
            if (domCalls == 1) """{"title":"书","chapters":[{"id":"a","name":"開始閱讀"}]}"""
            else """{"title":"书","chapters":[{"id":"a","name":"第1卷"},{"id":"b","name":"第1話"}]}"""
        })
        source.detail("pw")
        source.chapters("pw")
        assertEquals(1, domCalls, "不完整目录也不能重复加载整页")
        now++
        val refreshed = CachedPages(source).refreshChapters("pw")
        assertEquals(listOf("第1卷", "第1話"), refreshed.map { it.name })
        assertEquals(2, domCalls)
        assertEquals(1, apiCalls, "详情失败进入全源冷却")
        now += 5 * 60_000
        source.loadDetails("pw")
        assertEquals(3, domCalls, "完整详情5分钟过期")
    }

    @Test
    fun `simultaneous detail and chapter callers load one page`() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val source = CopymangaSource(apiReader = { "{}" }, detailReader = {
            calls++
            kotlinx.coroutines.delay(30)
            """{"title":"书","chapters":[{"id":"a","name":"短篇"}]}"""
        })
        kotlinx.coroutines.coroutineScope {
            val first = async { source.detail("pw") }
            val second = async { source.chapters("pw") }
            first.await(); second.await()
        }
        assertEquals(1, calls)
    }

    @Test
    fun `cancelled api does not start dom fallback`() = kotlinx.coroutines.runBlocking {
        var domCalls = 0
        val source = CopymangaSource(apiReader = { throw kotlinx.coroutines.CancellationException("closed") }, detailReader = { domCalls++; null })
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { source.loadDetails("pw") }
        assertEquals(0, domCalls)
    }

    @Test
    fun `concurrent forced refreshes coalesce even when clock does not advance`() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val source = CopymangaSource(apiReader = { "{}" }, clock = { 100L }, detailReader = {
            calls++
            if (calls == 2) { entered.complete(Unit); release.await() }
            """{"title":"书","chapters":[{"id":"a","name":"全一篇"}]}"""
        })
        source.loadDetails("pw")
        kotlinx.coroutines.coroutineScope {
            val first = async { source.refreshChapters("pw") }
            entered.await()
            val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { source.refreshChapters("pw") }
            release.complete(Unit)
            first.await(); second.await()
        }
        assertEquals(2, calls, "同时排队的强刷共享新结果，不依赖毫秒时钟变化")
        source.refreshChapters("pw")
        assertEquals(3, calls, "后续主动刷新仍须重新查询")
    }

    @Test
    fun `named volume wins over navigation and short stories are not volumes`() {
        val result = parseCopymangaDetailDom("""{"title":"书","chapters":[
            {"id":"a","name":"開始閱讀"},{"id":"a","name":"第2卷"},
            {"id":"b","name":"第1卷"},{"id":"c","name":"第1話"},
            {"id":"d","name":"全一篇"}]}""", "pw")!!
        assertEquals(listOf("第1卷", "第2卷", "第1話", "全一篇"), result.second.map { it.name })
        assertTrue(isCopyVolume("第1卷"))
        assertTrue(!isCopyVolume("全一篇"))
        assertTrue(!isCopyVolume("番外篇"))
        val chinese = parseCopymangaDetailDom("""{"title":"书","chapters":[
            {"id":"a","name":"第十一卷"},{"id":"b","name":"第二卷"},
            {"id":"c","name":"第一卷"},{"id":"d","name":"第二话"},
            {"id":"e","name":"第一话"}]}""", "pw")!!
        assertEquals(listOf("第一卷", "第二卷", "第十一卷", "第一话", "第二话"), chinese.second.map { it.name })
    }

    @Test
    fun `repeated explicit denial backs off without blocking normal web details`() = kotlinx.coroutines.runBlocking {
        var now = 100L
        var apiCalls = 0
        var domCalls = 0
        val source = CopymangaSource(clock = { now }, apiReader = {
            apiCalls++
            """{"code":210,"message":"denied"}"""
        }, detailReader = {
            domCalls++
            """{"title":"书","chapters":[{"id":"a","name":"全一篇"}]}"""
        })
        source.loadDetails("pw")
        now += 10 * 60_000
        source.loadDetails("pw", true)
        now += 10 * 60_000
        assertEquals(1, source.refreshChapters("pw").size)
        assertEquals(2, apiCalls, "第二次明确拒绝后20分钟内不再探API")
        assertEquals(3, domCalls, "网页正常使用、主动刷新不被延迟")
        now += 10 * 60_000
        source.loadDetails("pw", true)
        assertEquals(3, apiCalls, "截止后仅由真实需求发起一次再探")
    }

    @Test
    fun `detail reads real groups and pages without an extra final request`() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        var domCalls = 0
        val source = CopymangaSource(apiReader = { path ->
            calls += path
            when {
                !path.contains("/chapters?") -> """{"code":200,"results":{"comic":{"name":"书","path_word":"pw"},"groups":{"default":{},"volume":{}}}}"""
                path.contains("/volume/") -> """{"code":200,"results":{"total":1,"offset":0,"limit":100,"list":[{"uuid":"v","name":"第1卷","ordered":1}]}}"""
                path.contains("offset=2") -> """{"code":200,"results":{"total":4,"offset":2,"limit":2,"list":[{"uuid":"c","name":"终幕","ordered":30},{"uuid":"d","name":"番外","ordered":40}]}}"""
                else -> """{"code":200,"results":{"total":4,"offset":0,"limit":2,"list":[{"uuid":"b","name":"开幕","ordered":20},{"uuid":"a","name":"序幕","ordered":10}]}}"""
            }
        }, detailReader = { domCalls++; null })
        val details = source.loadDetails("pw")
        assertEquals(listOf("第1卷", "序幕", "开幕", "终幕", "番外"), details.chapters.map { it.name })
        assertTrue(details.chapters.all { it.id.startsWith("pw#") })
        assertEquals(4, calls.size, "详情加3页，整除不多请求尾页")
        assertEquals(0, domCalls, "完整API目录不加载网页")
    }

    @Test
    fun `stalled pagination stops and falls back without publishing partial api chapters`() = kotlinx.coroutines.runBlocking {
        var calls = 0
        var domCalls = 0
        val source = CopymangaSource(apiReader = { path ->
            calls++
            if (!path.contains("/chapters?")) """{"results":{"comic":{"name":"书","path_word":"pw"}}}"""
            else """{"results":{"total":4,"limit":2,"list":[{"uuid":"a","name":"第1话"},{"uuid":"b","name":"第2话"}]}}"""
        }, detailReader = {
            domCalls++
            """{"title":"书","chapters":[{"id":"ok","name":"全一篇"}]}"""
        })
        val result = source.loadDetails("pw")
        assertEquals(listOf("全一篇"), result.chapters.map { it.name })
        assertEquals(3, calls, "重复页最多发现一次后终止")
        assertEquals(1, domCalls)
    }

    @Test
    fun `concurrent identical searches reuse the newly cached result`() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val source = CopymangaSource(apiReader = {
            calls++
            entered.complete(Unit)
            release.await()
            searchJson
        })
        kotlinx.coroutines.coroutineScope {
            val first = async { source.search("书", 0, 20) }
            entered.await()
            val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { source.search("书", 0, 20) }
            release.complete(Unit)
            assertEquals(first.await(), second.await())
        }
        assertEquals(1, calls, "同词并发只发一次请求")
    }

    @Test
    fun `empty search is shared briefly then retries while success cache stays fast`() = kotlinx.coroutines.runBlocking {
        var now = 100L
        var apiCalls = 0
        var webCalls = 0
        val source = CopymangaSource(clock = { now }, apiReader = { apiCalls++; "{}" }, searchReader = {
            webCalls++
            if (webCalls == 1) "[]" else """[{"sourceId":"pw","title":"书"}]"""
        })
        assertTrue(source.search("书", 0, 20).isEmpty())
        assertTrue(source.search("书", 0, 20).isEmpty())
        assertEquals(1, webCalls)
        now += 15_000
        assertEquals(1, source.search("书", 0, 20).size)
        source.search("书", 0, 20)
        assertEquals(2, webCalls)
        assertEquals(1, apiCalls)
    }

    @Test
    fun `business denial with misleading data never starts directory requests`() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val source = CopymangaSource(apiReader = {
            calls++
            """{"code":210,"results":{"comic":{"name":"不可用","path_word":"pw"}}}"""
        }, detailReader = { """{"title":"网页正常","chapters":[{"id":"a","name":"全一篇"}]}""" })
        assertEquals("网页正常", source.loadDetails("pw").manga?.title)
        assertEquals(1, calls)
    }

    @Test
    fun `api words define page order and malformed mapping is rejected`() {
        val json = """{"results":{"chapter":{"contents":[{"url":"https://s/third"},{"url":"https://s/first"},{"url":"https://s/second"}],"words":[2,0,1]}}}"""
        assertEquals(listOf("https://s/first", "https://s/second", "https://s/third"), parseCopymangaPages(json).map { it.url })
        for (mapping in listOf("[0,0,1]", "[0,1]", "[-1,0,1]", "[0,1,9]", "[0,1,\"x\"]")) {
            assertTrue(parseCopymangaPages(json.replace("[2,0,1]", mapping)).isEmpty(), "坏页序不能当完整图片列表：$mapping")
        }
    }

    // 搜索响应样本（真实结构：results.list[]，字段 name/path_word/cover/author[]）
    private val searchJson = """
        {
          "code": 200,
          "message": "请求成功",
          "results": {
            "list": [
              {
                "cover": "https://mirror2.mangafunc.fun/comic/qiankezhe/cover/3c1a.jpg!kb_m_item",
                "img_type": 2,
                "author": [
                  {"name": "月岛冬二", "alias": null, "path_word": "yuedaodonger"},
                  {"name": "香川まさひと", "alias": null, "path_word": "xiangchuan"}
                ],
                "name": "前科者",
                "alias": "前科者,前科者",
                "path_word": "qiankezhe",
                "popular": 13042
              }
            ],
            "total": 1816,
            "limit": 1,
            "offset": 0
          }
        }
    """.trimIndent()

    // 章节列表响应样本（results.list[]：index/uuid/name/comic_path_word）
    private val chaptersJson = """
        {
          "code": 200,
          "message": "请求成功",
          "results": {
            "list": [
              {"index": 0, "uuid": "52f9d522-0d71-11eb-93ea-00163e0ca5bd", "count": 28,
               "ordered": 10, "size": 41, "name": "第一话", "comic_id": "3c19",
               "comic_path_word": "qiankezhe", "group_path_word": "default", "type": 1},
              {"index": 1, "uuid": "cffb84a2-15d8-11eb-9e11-00163e0ca5bd", "count": 24,
               "ordered": 9, "size": 36, "name": "第二话", "comic_id": "3c19",
               "comic_path_word": "qiankezhe", "group_path_word": "default", "type": 1}
            ],
            "total": 28,
            "limit": 1,
            "offset": 0
          }
        }
    """.trimIndent()

    // 章节图片响应样本（results.chapter.contents[].url）
    private val pagesJson = """
        {
          "code": 200,
          "message": "请求成功",
          "results": {
            "show_app": false,
            "is_lock": false,
            "comic": {"name": "前科者", "uuid": "3c192200", "path_word": "qiankezhe",
                      "restrict": {"value": 0, "display": "一般向(免費)"}},
            "chapter": {
              "index": 0,
              "uuid": "52f9d522-0d71-11eb-93ea-00163e0ca5bd",
              "name": "第01话",
              "contents": [
                {"uuid": "8aff0712", "url": "https://mirror2.mangafunc.fun/comic/qiankezhe/420b9/8afbbff8.jpg!kb_m_read_large"},
                {"uuid": "8aff0713", "url": "https://mirror2.mangafunc.fun/comic/qiankezhe/420b9/8afbbff9.jpg!kb_m_read_large"}
              ],
              "words": [0, 1],
              "is_long": false
            }
          }
        }
    """.trimIndent()

    @Test
    fun `search parses list with author join`() {
        val list = parseCopymangaSearch(searchJson)
        assertEquals(1, list.size)
        val m = list.single()
        assertEquals("前科者", m.title)
        assertEquals("qiankezhe", m.sourceId)
        assertEquals("月岛冬二、香川まさひと", m.author)
        assertTrue(m.coverUrl!!.startsWith("https://mirror2.mangafunc.fun/"))
    }

    @Test
    fun `chapters encode path word and uuid, sorted by index`() {
        val list = parseCopymangaChapters(chaptersJson)
        assertEquals(2, list.size)
        assertEquals("第一话", list[0].name)
        assertEquals(0, list[0].index)
        // 图片接口要 path_word + uuid：id 编成 "path_word#uuid"
        assertEquals("qiankezhe#52f9d522-0d71-11eb-93ea-00163e0ca5bd", list[0].id)
        assertEquals("qiankezhe#cffb84a2-15d8-11eb-9e11-00163e0ca5bd", list[1].id)
    }

    @Test
    fun `chapters fall back to per-row comic_path_word when results lacks it`() {
        val json = chaptersJson.replace("\"comic_path_word\": \"qiankezhe\",\n", "")
            .replace("\"comic_path_word\": \"qiankezhe\"", "\"comic_path_word\": \"qiankezhe\"")
        // 注意：样本里 results 下没有 comic_path_word，逐行兜底也能拿到
        val list = parseCopymangaChapters(json)
        assertEquals(2, list.size)
        assertTrue(list.all { it.id.startsWith("qiankezhe#") })
    }

    @Test
    fun `pages preserve contents order`() {
        val pages = parseCopymangaPages(pagesJson)
        assertEquals(2, pages.size)
        assertTrue(pages[0].url.endsWith("8afbbff8.jpg!kb_m_read_large"))
        assertTrue(pages[1].url.endsWith("8afbbff9.jpg!kb_m_read_large"))
    }

    @Test
    fun `detail parses comic block`() {
        val detailJson = """
            {
              "code": 200,
              "results": {
                "comic": {
                  "name": "前科者",
                  "path_word": "qiankezhe",
                  "cover": "https://mirror2.mangafunc.fun/comic/qiankezhe/cover/3c1a.jpg",
                  "author": [{"name": "月岛冬二"}],
                  "description": "一个关于更生人的故事",
                  "restrict": {"value": 0, "display": "一般向(免費)"}
                }
              }
            }
        """.trimIndent()
        val m = parseCopymangaDetail(detailJson)
        assertEquals("前科者", m?.title)
        assertEquals("qiankezhe", m?.sourceId)
        assertEquals("月岛冬二", m?.author)
        assertEquals("一个关于更生人的故事", m?.description)
        assertEquals("一般向(免費)", m?.status)
    }

    @Test
    fun `empty and malformed responses do not crash`() {
        assertEquals(emptyList(), parseCopymangaSearch("""{"code":404,"results":{"list":[]}}"""))
        assertEquals(emptyList(), parseCopymangaSearch("not json at all"))
        assertEquals(emptyList(), parseCopymangaChapters("""{"code":500}"""))
        assertEquals(emptyList(), parseCopymangaPages("""{"results":{"chapter":{}}}"""))
        assertNull(parseCopymangaDetail("""{"results":{}}"""))
        // 浏览器 DOM 兜底的产物（数组形态）也不能崩
        assertEquals(emptyList(), parseCopymangaSearchDom("""[]"""))
        assertEquals(emptyList(), parseCopymangaSearchDom("not json"))
    }

    @Test
    fun `search dom fallback parses browser scraped cards`() {
        // 隐藏浏览器里 COPYMANGA_SEARCH_DOM_JS 的返回形态（2026-10-24 兜底路径）
        val json = """
            [{"sourceId":"qiankezhe","title":"潜科者","coverUrl":"https://s3/x.jpg"},
             {"sourceId":"haizeiwang","title":"海贼王","coverUrl":null},
             {"sourceId":"","title":"坏数据","coverUrl":null}]
        """.trimIndent()
        val list = parseCopymangaSearchDom(json)
        assertEquals(2, list.size, "空 sourceId 应被丢弃")
        assertEquals("潜科者", list[0].title)
        assertEquals("https://s3/x.jpg", list[0].coverUrl)
        assertEquals("海贼王", list[1].title)
        assertNull(list[1].coverUrl)
    }

    @Test
    fun `detail dom parses title and reverses new-first chapter list`() {
        // 详情页 DOM（curl 实测：书名在 h6[title]，章节 a[href*=/chapter/]）
        // 站点给的是「新话在前」→ 解析后应翻成阅读顺序，且 index 等于数组下标
        val json = """
            {"title":"鬼滅之刃","cover":"https://s3/c.jpg","author":"吾峠呼世晴",
             "chapters":[{"id":"uuid-3","name":"第3话"},{"id":"uuid-2","name":"第2话"},
                         {"id":"uuid-1","name":"第1话"},{"id":"uuid-1","name":"第1话"}]}
        """.trimIndent()
        val (manga, chapters) = parseCopymangaDetailDom(json, "guimiezhiren")!!
        assertEquals("鬼滅之刃", manga.title)
        assertEquals("吾峠呼世晴", manga.author)
        assertEquals(3, chapters.size, "重复 uuid 去重")
        assertEquals("第1话", chapters[0].name)
        assertEquals("第3话", chapters[2].name)
        // id 必须拼成 path_word#uuid（pages 靠它拆解）
        assertEquals("guimiezhiren#uuid-1", chapters[0].id)
        chapters.forEachIndexed { i, ch -> assertEquals(i, ch.index) }
    }

    @Test
    fun `detail dom sorts chapters by number not by dom order`() {
        // 站点真实顺序（2026-10-24 用户截图实测）：最新话置顶 + 特别短篇置顶 + 其余正序，
        // 例如 [第206話, 第206話特別短篇, 第2話, 第3話…]。旧实现比首末编号判不出方向，
        // 详情页显示成 206 → 短篇 → 2 → 3…，中间还插一条「缺失 3-205」的假提示。
        val json = """
            {"title":"鬼滅之刃","cover":null,"author":null,
             "chapters":[{"id":"u206","name":"第206話"},{"id":"us","name":"第206話特別短篇"},
                         {"id":"u2","name":"第2話"},{"id":"u3","name":"第3話"},
                         {"id":"u1","name":"第1話"},{"id":"uv","name":"第1卷"}]}
        """.trimIndent()
        val (_, chapters) = parseCopymangaDetailDom(json, "guimiezhiren")!!
        assertEquals(
            listOf("第1卷", "第1話", "第2話", "第3話", "第206話", "第206話特別短篇"),
            chapters.map { it.name },
            "卷优先，话按数字升序，附录保留相对顺序"
        )
        chapters.forEachIndexed { i, ch -> assertEquals(i, ch.index) }
    }

    @Test
    fun `decrypted contentKey payload is walked for image urls`() {
        // 站点没公开解密后 JSON 的结构，所以按「递归收集所有 http 字符串」实现。
        // 三种常见形态都要能吃：
        val flat = """["https://s3/a.jpg","https://s3/b.jpg"]"""
        assertEquals(
            listOf("https://s3/a.jpg", "https://s3/b.jpg"),
            parseCopymangaDecryptedPages(flat)
        )
        val nested = """
            {"path":"https://s3/base","contents":[{"url":"https://s3/1.jpg"},
             {"url":"https://s3/2.jpg","width":800},{"note":"不是链接"}]}
        """.trimIndent()
        assertEquals(
            listOf("https://s3/base", "https://s3/1.jpg", "https://s3/2.jpg"),
            parseCopymangaDecryptedPages(nested),
            "按出现顺序、非 http 字符串丢弃"
        )
        // 去重 + 容错
        assertEquals(
            listOf("https://s3/a.jpg"),
            parseCopymangaDecryptedPages("""["https://s3/a.jpg","https://s3/a.jpg"]""")
        )
        assertEquals(emptyList(), parseCopymangaDecryptedPages("not json"))
        assertEquals(emptyList(), parseCopymangaDecryptedPages("""{"err":"no contentKey"}"""))
    }

    @Test
    fun `detail dom drops nav button name and keeps the real chapter`() {
        // 2026-10-24 用户截图：「開始閱讀」混进章节列表，而「第1話」不见了。
        // 两者指向同一个 uuid（按钮 = 第1话的快捷入口），旧实现按 DOM 顺序 distinctBy
        // 保留了按钮那条。
        val json = """
            {"title":"鬼滅之刃","chapters":[
              {"id":"u1","name":"開始閱讀"},
              {"id":"u1","name":"第1話"},
              {"id":"u2","name":"第2話"}]}
        """.trimIndent()
        val (_, chapters) = parseCopymangaDetailDom(json, "pw")!!
        assertEquals(listOf("第1話", "第2話"), chapters.map { it.name }, "保留真名，丢掉按钮名")

        // 只有导航按钮时不猜编号；真实入口可以是卷、话或其他章节。
        val onlyButton = """{"title":"x","chapters":[{"id":"u9","name":"開始閱讀"}]}"""
        val (_, only) = parseCopymangaDetailDom(onlyButton, "pw")!!
        assertEquals(listOf("阅读入口"), only.map { it.name })
    }

    @Test
    fun `pages dom keeps order and drops blanks`() {
        val json = """["https://s3/a.jpg","https://s3/b.jpg","","https://s3/a.jpg"]"""
        val urls = parseCopymangaPagesDom(json)
        assertEquals(listOf("https://s3/a.jpg", "https://s3/b.jpg"), urls)
        assertEquals(emptyList(), parseCopymangaPagesDom("not json"))
    }

    @Test
    fun `search skips entries missing path_word`() {
        val json = """
            {"code":200,"results":{"list":[
              {"name":"有id","path_word":"hasid"},
              {"name":"没id"}
            ]}}
        """.trimIndent()
        val list = parseCopymangaSearch(json)
        assertEquals(1, list.size)
        assertEquals("hasid", list.single().sourceId)
    }
}
