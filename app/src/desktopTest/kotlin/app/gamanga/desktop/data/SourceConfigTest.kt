package app.gamanga.desktop.data

import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.PageCache
import app.gamanga.desktop.source.PageImage
import app.gamanga.desktop.source.Sources
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F-22 源配置化：`sources.json` 的读取/归一化/回落规则。
 *
 * 这些用例锁死三条容易退化的约定：
 * 1. **没配置文件 = 全部内置默认**（老用户升级后行为不变）；
 * 2. **字段留空 = 用默认**（不是空字符串、不是空列表）；
 * 3. **一份手改坏的 JSON 不能让应用起不来**（保持默认 + 记日志）。
 */
class SourceConfigTest {

    private lateinit var tmp: File

    @BeforeTest
    fun setUp() {
        SourceConfig.reset()
        tmp = kotlin.io.path.createTempDirectory("gamanga-srcconf").toFile()
    }

    @AfterTest
    fun tearDown() {
        SourceConfig.reset()
        Sources.rebuild() // 别把测试用的地址留给后面的测试类
        PageCache.clear()
        tmp.deleteRecursively()
    }

    private fun write(text: String): File = File(tmp, "sources.json").apply { writeText(text) }

    @Test
    fun `no file means built-in defaults`() {
        SourceConfig.load(File(tmp, "missing.json"))
        assertEquals("https://m.g-mh.org", SourceConfig.baseUrl(SourceType.GODA))
        assertEquals("https://komiic.cc", SourceConfig.baseUrl(SourceType.KOMIIC))
        assertEquals("https://www.mangabz.com", SourceConfig.baseUrl(SourceType.MANGABZ))
        assertEquals("https://www.mangacopy.com", SourceConfig.baseUrl(SourceType.COPYMANGA))
        assertTrue(SourceConfig.loaded)
    }

    @Test
    fun `file overrides base url and strips trailing slash`() {
        SourceConfig.load(write("""{ "GODA": { "baseUrl": "https://godamh.com/" } }"""))
        assertEquals("https://godamh.com", SourceConfig.baseUrl(SourceType.GODA))
        // 其它源不受影响
        assertEquals("https://komiic.cc", SourceConfig.baseUrl(SourceType.KOMIIC))
    }

    @Test
    fun `retired komiic domain in saved file migrates to current default`() {
        // 老用户 sources.json 里存的还是已停用的 komiic.com，读时纠正，不要求手改文件。
        SourceConfig.load(write("""{ "KOMIIC": { "baseUrl": "https://komiic.com/" } }"""))
        assertEquals("https://komiic.cc", SourceConfig.baseUrl(SourceType.KOMIIC))
        SourceConfig.load(write("""{ "KOMIIC": { "baseUrl": "https://www.komiic.com" } }"""))
        assertEquals("https://komiic.cc", SourceConfig.baseUrl(SourceType.KOMIIC))
        // 用户自己填的其他域名不受影响
        SourceConfig.load(write("""{ "KOMIIC": { "baseUrl": "https://komiic.example" } }"""))
        assertEquals("https://komiic.example", SourceConfig.baseUrl(SourceType.KOMIIC))
    }

    @Test
    fun `scheme is added when hand-edited without it`() {
        SourceConfig.load(write("""{ "GODA": { "baseUrl": "baozimh.org" } }"""))
        assertEquals("https://baozimh.org", SourceConfig.baseUrl(SourceType.GODA))
    }

    @Test
    fun `blank field falls back to default instead of empty`() {
        SourceConfig.load(
            write(
                """
                { "GODA": { "baseUrl": "  ", "backupBaseUrls": [], "apiBaseUrls": [""], "userAgent": "" } }
                """.trimIndent()
            )
        )
        val e = SourceConfig.effective(SourceType.GODA)
        assertEquals("https://m.g-mh.org", e.baseUrl)
        assertEquals(emptyList(), e.backupBaseUrls)
        assertEquals(listOf("https://v2.apikk.top"), e.apiBaseUrls)
        assertNull(SourceConfig.userAgent(SourceType.GODA))
    }

    @Test
    fun `backup bases accept both array and comma separated string`() {
        SourceConfig.load(
            write(
                """
                {
                  "GODA": { "backupBaseUrls": ["https://godamh.com", "https://baozimh.org/", "https://godamh.com"] },
                  "MANGABZ": { "backupBaseUrls": "https://a.example.com, https://b.example.com" }
                }
                """.trimIndent()
            )
        )
        // 归一化（补协议/去尾斜杠）+ 去重 + 保序
        assertEquals(listOf("https://godamh.com", "https://baozimh.org"), SourceConfig.effective(SourceType.GODA).backupBaseUrls)
        assertEquals(listOf("https://a.example.com", "https://b.example.com"), SourceConfig.effective(SourceType.MANGABZ).backupBaseUrls)
        // bases = 主站 + 备用
        assertEquals(
            listOf("https://m.g-mh.org", "https://godamh.com", "https://baozimh.org"),
            SourceConfig.bases(SourceType.GODA)
        )
    }

    @Test
    fun `api bases fall back to site base when not configured`() {
        SourceConfig.load(File(tmp, "missing.json"))
        // Komiic 接口与站点同域
        assertEquals(listOf("https://komiic.cc"), SourceConfig.apiBaseUrls(SourceType.KOMIIC))
        // 拷贝漫画有自己的接口域名列表（主 + 备用）
        assertEquals(
            listOf("https://api.mangacopy.com", "https://api.copy4000.com"),
            SourceConfig.apiBaseUrls(SourceType.COPYMANGA)
        )
        // 改了站点地址、没改接口地址时，接口仍走默认（换域名是两件事）
        SourceConfig.load(write("""{ "COPYMANGA": { "baseUrl": "https://copy2000.com" } }"""))
        assertEquals("https://copy2000.com", SourceConfig.baseUrl(SourceType.COPYMANGA))
        assertEquals("https://api.mangacopy.com", SourceConfig.apiBaseUrl(SourceType.COPYMANGA))
    }

    @Test
    fun `unknown source keys are ignored`() {
        // 未接入的源（Komga）默认没有地址，别给个假域名
        assertEquals("", SourceConfig.baseUrl(SourceType.KOMGA))
        SourceConfig.load(
            write(
                """
                { "_comment": "手写注释", "FOO": { "baseUrl": "https://nope.example" },
                  "KOMGA": { "baseUrl": "https://komga.local" },
                  "GODA": { "baseUrl": "https://godamh.com" } }
                """.trimIndent()
            )
        )
        assertEquals("https://godamh.com", SourceConfig.baseUrl(SourceType.GODA))
        // 不认识的键忽略（不炸）；KOMGA 是枚举里存在但未接入的源，写了也只是存着
        assertEquals("https://komga.local", SourceConfig.baseUrl(SourceType.KOMGA))
    }

    @Test
    fun `broken json keeps defaults instead of crashing`() {
        SourceConfig.load(write("""{ "GODA": { "baseUrl": } """))
        assertEquals("https://m.g-mh.org", SourceConfig.baseUrl(SourceType.GODA))
        // 字段类型不对（数字当 baseUrl）也不炸，回落默认
        SourceConfig.load(write("""{ "GODA": { "baseUrl": 42, "backupBaseUrls": 7 } }"""))
        assertEquals("https://m.g-mh.org", SourceConfig.baseUrl(SourceType.GODA))
        assertEquals(emptyList(), SourceConfig.effective(SourceType.GODA).backupBaseUrls)
    }

    @Test
    fun `save then load round trip`() {
        val file = File(tmp, "sources.json")
        SourceConfig.save(
            mapOf(
                SourceType.GODA to SourceEndpoint(
                    baseUrl = "https://godamh.com/",
                    backupBaseUrls = listOf("https://m.g-mh.org"),
                    userAgent = "MyUA/1.0",
                ),
            ),
            to = file,
        )
        val text = file.readText()
        assertTrue(text.contains("\"GODA\""), "配置要落地成可读 JSON：$text")
        assertTrue(text.contains("https://godamh.com"), "结尾斜杠要被归一化掉：$text")
        assertFalse(text.contains("KOMIIC"), "没设置的源不该写进文件：$text")

        SourceConfig.reset()
        SourceConfig.load(file)
        val e = SourceConfig.effective(SourceType.GODA)
        assertEquals("https://godamh.com", e.baseUrl)
        assertEquals(listOf("https://m.g-mh.org"), e.backupBaseUrls)
        assertEquals("MyUA/1.0", e.userAgent)
        assertEquals("MyUA/1.0", SourceConfig.userAgent(SourceType.GODA))
        assertEquals("godamh.com", SourceConfig.domain(SourceType.GODA))
    }

    @Test
    fun `saving all-empty config writes empty object and falls back`() {
        val file = File(tmp, "sources.json")
        SourceConfig.save(mapOf(SourceType.GODA to SourceEndpoint()), to = file)
        assertEquals("{}\n", file.readText())
        assertEquals("https://m.g-mh.org", SourceConfig.baseUrl(SourceType.GODA))
    }

    @Test
    fun `domain follows configured base and drops port`() {
        assertEquals("www.mangacopy.com", SourceConfig.domain(SourceType.COPYMANGA))
        SourceConfig.load(write("""{ "MANGABZ": { "baseUrl": "http://mangabz.mirror:8080" } }"""))
        assertEquals("mangabz.mirror", SourceConfig.domain(SourceType.MANGABZ))
    }

    @Test
    fun `save takes effect immediately and rebuilds adapters`() {
        val file = File(tmp, "sources.json")
        SourceConfig.loadOnce(File(tmp, "missing.json"))
        val before = Sources.of(SourceType.GODA)
        assertEquals("https://m.g-mh.org", SourceConfig.baseUrl(SourceType.GODA))

        val normalized = SourceConfig.save(
            mapOf(SourceType.GODA to SourceEndpoint(baseUrl = "https://godamh.com")),
            to = file,
        )
        assertEquals("https://godamh.com", normalized[SourceType.GODA]?.baseUrl)
        // 2026-10-25 用户要求：保存即生效，不重启。生效值 / cookie 域 / 适配器实例三处同时切
        assertEquals("https://godamh.com", SourceConfig.baseUrl(SourceType.GODA))
        assertEquals("godamh.com", SourceConfig.domain(SourceType.GODA))
        assertNotSame(before, Sources.of(SourceType.GODA), "保存后必须重建适配器，否则请求还打旧地址")
    }

    @Test
    fun `save clears page cache so old-domain image urls are not reused`() {
        val file = File(tmp, "sources.json")
        val chapterId = "123|456|slug"
        PageCache.put(SourceType.GODA, chapterId, listOf(PageImage("https://old.example/1.jpg")))
        assertNotNull(PageCache.get(SourceType.GODA, chapterId))

        SourceConfig.save(
            mapOf(SourceType.GODA to SourceEndpoint(baseUrl = "https://godamh.com")),
            to = file,
        )
        // 旧域名拼出来的图片 URL 必须作废，否则换域名后第一次读同一话仍打旧地址
        assertNull(PageCache.get(SourceType.GODA, chapterId))
    }

    @Test
    fun `blank list entries do not count as configured`() {
        // UI 把空输入 split(',') 会得到 [""]，不能因此显示「已自定义」
        assertTrue(SourceEndpoint(backupBaseUrls = listOf("", " "), apiBaseUrls = listOf("")).isEmpty)
        assertFalse(SourceEndpoint(backupBaseUrls = listOf("https://x.com")).isEmpty)
    }

    @Test
    fun `loadOnce reads the file only once`() {
        val a = File(tmp, "a.json").apply { writeText("""{ "GODA": { "baseUrl": "https://godamh.com" } }""") }
        val b = File(tmp, "b.json").apply { writeText("""{ "GODA": { "baseUrl": "https://baozimh.org" } }""") }
        SourceConfig.loadOnce(a)
        assertEquals("https://godamh.com", SourceConfig.baseUrl(SourceType.GODA))
        // 第二次（模拟重组/再次进入）不会重读：配置要重启才换，语义才干净
        SourceConfig.loadOnce(b)
        assertEquals("https://godamh.com", SourceConfig.baseUrl(SourceType.GODA))
    }

    @Test
    fun `path templates default per source and override per key`() {
        SourceConfig.loadOnce(File(tmp, "missing.json"))
        assertEquals("/s/{kw}", SourceConfig.paths(SourceType.GODA)["search"])
        assertEquals("/manga/{id}", SourceConfig.paths(SourceType.GODA)["detail"])
        assertEquals("/api/query", SourceConfig.paths(SourceType.KOMIIC)["api"])
        assertEquals("/search?title={kw}", SourceConfig.paths(SourceType.MANGABZ)["search"])
        assertEquals("/comic/{id}/chapter/{chapter}", SourceConfig.paths(SourceType.COPYMANGA)["chapter"])

        // 只覆盖一个 key：其余仍用默认（逐 key 合并，不是整段替换）
        SourceConfig.load(write("""{ "GODA": { "paths": { "search": "s?q={kw}" } } }"""))
        val p = SourceConfig.paths(SourceType.GODA)
        assertEquals("/s?q={kw}", p["search"], "缺前导斜杠要补上")
        assertEquals("/manga/{id}", p["detail"])
        assertEquals("/manga/{manga}/{chapter}", p["chapter"])
    }

    @Test
    fun `render path replaces only given vars`() {
        assertEquals("/s/%E6%B5%8B", renderPath("/s/{kw}", mapOf("kw" to "%E6%B5%8B")))
        assertEquals(
            "/manga/x/y",
            renderPath("/manga/{manga}/{chapter}", mapOf("manga" to "x", "chapter" to "y"))
        )
        // 写错占位符要能一眼看出来，而不是悄悄变空串
        assertEquals("/x/{unknown}", renderPath("/x/{unknown}", mapOf("kw" to "a")))
    }

    @Test
    fun `paths survive save and load round trip`() {
        val file = File(tmp, "sources.json")
        SourceConfig.save(
            mapOf(
                SourceType.GODA to SourceEndpoint(
                    paths = mapOf("search" to "/find/{kw}", "detail" to "/book/{id}"),
                ),
            ),
            to = file,
        )
        val text = file.readText()
        assertTrue(text.contains("\"paths\""), "paths 要落地成可读 JSON：$text")
        SourceConfig.reset()
        SourceConfig.load(file)
        assertEquals("/find/{kw}", SourceConfig.paths(SourceType.GODA)["search"])
        assertEquals("/book/{id}", SourceConfig.paths(SourceType.GODA)["detail"])
        // 没写的 key 仍然回落默认
        assertEquals("/manga/{manga}/{chapter}", SourceConfig.paths(SourceType.GODA)["chapter"])
    }

    @Test
    fun `normalize helpers`() {
        assertEquals("", normalizeBase("   "))
        assertEquals("https://a.com", normalizeBase(" a.com/// "))
        assertEquals("http://a.com", normalizeBase("http://a.com/"))
        assertEquals(listOf("https://a.com", "https://b.com"), normalizeBases(listOf("a.com", "", " b.com ", "a.com")))
        assertEquals("www.mangacopy.com", hostOf("https://www.mangacopy.com/comic/x?y=1"))
        assertEquals("komiic.com", hostOf("https://KOMIIC.com/api/query"))
    }
}
