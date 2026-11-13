package app.gamanga.desktop.source

import app.gamanga.desktop.model.SourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MangaBZ 源解析纯函数测试（第八轮 #9）。
 *
 * 站点当前网络不可达（未实测）。fixture 按社区爬虫文档（CSDN 2023 实战文）
 * 的页面形态构造：翻页器内嵌 MANGABZ_CID/MID/VIEWSIGN 四件套、
 * asHx 响应 `页码_文件名|`、搜索/详情页的链接形态。
 */
class MangabzSourceTest {

    @Test
    fun `snapshot reads detail once and force refresh stays remote`() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val src = MangabzSource(textReader = {
            calls++
            """<h1>漫画甲</h1><a href="/m1/" class="detail-list-form-item">第一卷</a>"""
        })
        val result = src.loadDetails("42")
        assertEquals("漫画甲", result.manga?.title)
        assertEquals("第一卷", result.chapters.single().name)
        assertEquals(1, calls)
        src.loadDetails("42", forceRefresh = true)
        assertEquals(2, calls)
    }

    @Test
    fun `image denial stops without fetching a new signature`() = kotlinx.coroutines.runBlocking {
        val signatureCalls = java.util.concurrent.atomic.AtomicInteger()
        val batchCalls = java.util.concurrent.atomic.AtomicInteger()
        val src = MangabzSource(textReader = { path ->
            if (path.contains("chapterimage")) {
                batchCalls.incrementAndGet()
                throw HttpException("test", 429, "")
            }
            signatureCalls.incrementAndGet()
            """var MANGABZ_CID=1;var MANGABZ_MID=42;var MANGABZ_VIEWSIGN_DT="date";var MANGABZ_VIEWSIGN="sign";"""
        })
        kotlin.test.assertFailsWith<HttpException> { src.pages("1") }
        assertEquals(1, signatureCalls.get())
        assertTrue(batchCalls.get() in 1..6, "仅允许已经启动的当前并发轮")
    }

    @Test
    fun `image cancellation propagates without signature retry`() = kotlinx.coroutines.runBlocking {
        val src = MangabzSource(textReader = { throw kotlinx.coroutines.CancellationException("closed") })
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { src.pages("1") }
        Unit
    }

    @Test
    fun `view sign parses four fields from reader page`() {
        val html = """
            <html><body><script>
            var MANGABZ_CID=91436;
            var MANGABZ_MID=1864;
            var MANGABZ_VIEWSIGN_DT="2024-01-15 10:30:00";
            var MANGABZ_VIEWSIGN="d41d8cd98f00b204e9800998ecf8427e";
            </script></body></html>
        """.trimIndent()
        val sign = parseMangabzViewSign(html)
        assertEquals("91436", sign?.cid)
        assertEquals("1864", sign?.mid)
        assertEquals("2024-01-15 10:30:00", sign?.dt)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", sign?.sign)
    }

    @Test
    fun `view sign returns null when page lacks fields`() {
        assertNull(parseMangabzViewSign("<html>加载中</html>"))
        // 有 cid/mid 但没有 viewsign 也不行（asHx 会拒）
        assertNull(parseMangabzViewSign("MANGABZ_CID=1;MANGABZ_MID=2;"))
    }

    @Test
    fun `image key takes everything before the bar`() {
        assertEquals("12_abc123def", parseMangabzImageKey("12_abc123def|"))
        assertEquals("1_a", parseMangabzImageKey(" 1_a |尾巴内容"))
        assertNull(parseMangabzImageKey("no bar here"))
        assertNull(parseMangabzImageKey("|leading"))
        assertNull(parseMangabzImageKey(""))
    }

    @Test
    fun `search parses result entries and dedupes`() {
        // 真实结构（2026-10-23 实测）：每个结果两个 <a>——封面 a（inner 是 img，
        // 尾部空白）+ 标题 a（a 内是纯文本）。旧实现把封面的空白当标题、真标题
        // 反而丢弃（用户实测「有封面没文字」）。
        val html = """
            <html><body>
            <ul class="mh-list">
              <li>
                <div class="mh-item">
                  <a href="/1864bz/">
                    <img class="mh-cover" src="https://cover.example.com/1.jpg"/>
                  </a>
                  <div class="mh-item-detali">
                    <h2 class="title"><a href="/1864bz/" title="漫画甲">漫画甲</a></h2>
                    <p class="chapter"><a href="/m1/" title="漫画甲 第1话">第1话</a></p>
                  </div>
                </div>
              </li>
              <li>
                <div class="mh-item">
                  <a href="/1865bz/">
                    <img class="mh-cover" data-src="https://cover.example.com/2.jpg"/>
                  </a>
                  <div class="mh-item-detali">
                    <h2 class="title"><a href="/1865bz/" title="漫画乙">漫画乙</a></h2>
                  </div>
                </div>
              </li>
            </ul>
            </body></html>
        """.trimIndent()
        val list = parseMangabzSearch(html)
        assertEquals(2, list.size)
        assertEquals("1864", list[0].sourceId)
        assertEquals("漫画甲", list[0].title)
        assertEquals("https://cover.example.com/1.jpg", list[0].coverUrl)
        assertEquals("1865", list[1].sourceId)
        assertEquals("漫画乙", list[1].title)
        // 关键回归：标题不能是空白（旧 bug：封面 a 的尾部空白被当标题）
        assertTrue(list.all { it.title.isNotBlank() })
    }

    @Test
    fun `search returns empty for unrelated html`() {
        assertEquals(emptyList(), parseMangabzSearch("<html>没有结果</html>"))
    }

    /**
     * asHx 真实响应（2026-10-24 curl 实测 `/m528110/chapterimage.ashx?page=1`，636 字节）。
     *
     * **这是「阅读器一直转圈、只有 1 页」的直接证据**：站点返回的不是 `12_abc.jpg|`
     * 单页 key，而是 JS packer 混淆的整批清单（pix + key + pvalue，一次 2 页）。
     */
    private val realAsHxBody = """eval(function(p,a,c,k,e,d){e=function(c){return(c<a?"":e(parseInt(c/a)))+((c=c%a)>35?String.fromCharCode(c+29):c.toString(36))};if(!''.replace(/^/,String)){while(c--)d[e(c)]=k[c]||e(c);k=[function(e){return d[e]}];e=function(){return'\\w+'};c=1;};while(c--)if(k[c])p=p.replace(new RegExp('\\b'+e(c)+'\\b','g'),k[c]);return p;}('e 9(){2 6=4;2 5=\'a\';2 7="g://j.h.f/1/b/4";2 3=["/c.8","/k.8"];o(2 i=0;i<3.l;i++){3[i]=7+3[i]+\'?6=4&5=a&m=\'}n 3}2 d;d=9();',25,25,'||var|pvalue|528110|key|cid|pix|jpg|dm5imagefun|b70dc2f77739446ca696837638438050|157|1_1273||function|com|https|mangabz||image|2_7597|length|uk|return|for'.split('|'),0,{}))"""

    @Test
    fun `image batch unpacks packer js into two pages`() {
        val refs = parseMangabzImageBatch(realAsHxBody)
        assertEquals(2, refs.size, "一次 asHx 返回 2 页")
        assertEquals(1, refs[0].page)
        assertEquals(2, refs[1].page)
        // 图片域路径由解包出的 pix 决定（/1/<mid>/<cid>），不是旧代码硬拼的 /2/.../<key>.jpg
        assertEquals(
            "https://image.mangabz.com/1/157/528110/1_1273.jpg" +
                "?cid=528110&key=b70dc2f77739446ca696837638438050&uk=",
            refs[0].url
        )
        assertEquals(
            "https://image.mangabz.com/1/157/528110/2_7597.jpg" +
                "?cid=528110&key=b70dc2f77739446ca696837638438050&uk=",
            refs[1].url
        )
    }

    @Test
    fun `image batch returns empty for legacy pipe format without pix`() {
        // 老式 `7_abc123|` 只有文件名、没有 pix 前缀 → 拼不出真实 URL，
        // 宁可返回空（上层显示「没有图片」）也不返回一批必然 404 的假 URL。
        assertEquals(emptyList(), parseMangabzImageBatch("7_abc123|"))
    }

    @Test
    fun `image batch returns empty for unrecognized body`() {
        assertEquals(emptyList(), parseMangabzImageBatch("<html>404</html>"))
        assertNull(unpackJsPacker("<html>not packed</html>"))
    }

    @Test
    fun `detail parses title and reverses chapters to reading order`() {
        val html = """
            <html><head><title>漫画甲-MangaBZ</title></head><body>
            <h1>漫画甲</h1>
            <img src="https://img.example.com/cover.jpg"/>
            <p>作者：</span> <a href="/manga-author/x"><span> 某作者 </span></a></p>
            <div class="chapter-list">
              <a href="/m91438/" class="detail-list-form-item" title="">第三话</a>
              <a href="/m91437/" class="detail-list-form-item" title="">第二话</a>
              <a href="/m91436/" class="detail-list-form-item" title="">第一话</a>
            </div>
            </body></html>
        """.trimIndent()
        val (manga, chapters) = parseMangabzDetail(html, "1864")!!
        assertEquals("漫画甲", manga.title)
        assertEquals("1864", manga.sourceId)
        assertEquals(3, chapters.size)
        // 真实结构（第十二轮 #4）：detail-info-title 是干净书名、
        // detail-info-cover 是封面（页面第一个 img 是网站 logo）
        val real = """
            <html><head><title>漫画甲漫畫_230已更新_漫畫甲漫畫在線閱讀</title></head><body>
            <img class="logo-img" src="https://css.mangabz.com/logo.png"/>
            <div class="detail-info">
              <img src="https://cover.mangabz.com/1/157/x.jpg" class="detail-info-cover"/>
              <div class="detail-info-title">   漫画甲  </div>
            </div>
            <a href="/m1/" class="detail-list-form-item" title="">第1话</a>
            </body></html>
        """.trimIndent()
        val (rm, _) = parseMangabzDetail(real, "1864")!!
        assertEquals("漫画甲", rm.title, "标题取 detail-info-title，不用 SEO title 串")
        assertEquals("https://cover.mangabz.com/1/157/x.jpg", rm.coverUrl, "封面取 detail-info-cover，不是 logo")
        // 站点新话在前 → 翻转为阅读顺序
        assertEquals("第一话", chapters[0].name)
        assertEquals("91436", chapters[0].id)
        assertEquals("第三话", chapters[2].name)
        // **index 必须等于数组下标**（2026-10-24 用户实测「第 1 卷打开是第 230 话」）：
        // reversed 之后 index 若还是倒序前的序号，点第 1 项会跳到最后一话。
        chapters.forEachIndexed { i, ch ->
            assertEquals(i, ch.index, "第 $i 项的 index 应等于数组下标（实际 ${ch.index}）")
        }
    }

    @Test
    fun `image request headers carry referer for hotlink protection`() {
        // image.mangabz.com 不带 Referer 直接 403（2026-10-24 实测，下载日志铁证）
        val page = PageImage(url = "https://image.mangabz.com/1/157/1/1_1.jpg", referer = "https://www.mangabz.com/m1/")
        assertEquals("https://www.mangabz.com/m1/", page.requestHeaders()["Referer"])
        // Komiic 的票据与 MangaBZ 的 Referer 互不干扰
        val komiic = PageImage(url = "https://x/y.jpg", ticket = "tk")
        assertEquals("tk", komiic.requestHeaders()["X-Image-Ticket"])
        assertNull(komiic.requestHeaders()["Referer"])
        assertEquals(emptyMap(), PageImage(url = "file:///a.jpg").requestHeaders())
    }

    @Test
    fun `detail dedupes chapter ids from button zone and list zone`() {
        // 真实结构（2026-10-23 实测）：每话出现两次——「开始阅读」按钮区
        // （detail-info-btn-1，最新话）+ 章节列表区（detail-list-form-item）。
        // 旧实现两份都抓 → 重复 id → LazyColumn items(key) 崩
        // （Key "122414" was already used → 闪退，用户实测 #2）。
        val html = """
            <html><head><title>漫画乙-MangaBZ</title></head><body>
            <h1>漫画乙</h1>
            <a href="/m122413/" title="第1卷" class="detail-info-btn-1">開始閱讀</a>
            <a href="/m122414/" title="第2卷" style="color:#ff3f60;" target="_blank">第2卷</a>
            <a href="/m122414/" class="detail-list-form-item" title="" target="_blank">第2卷</a>
            <a href="/m122413/" class="detail-list-form-item" title="" target="_blank">第1卷</a>
            </body></html>
        """.trimIndent()
        val (_, chapters) = parseMangabzDetail(html, "8680")!!
        assertEquals(2, chapters.size, "重复 cid 必须去重（实际=${chapters.map { it.id }}）")
        assertEquals(2, chapters.map { it.id }.distinct().size)
        // 列表区新话在前 → 翻转后第 1 卷在前
        assertEquals("第1卷", chapters[0].name)
        assertEquals("第2卷", chapters[1].name)
    }

    @Test
    fun `detail returns null when no title`() {
        assertNull(parseMangabzDetail("<html>404 页面不存在</html>", "9999"))
    }

    @Test
    fun `source links know the new domains`() {
        assertEquals("https://www.mangabz.com/1864bz/", browserUrl(SourceType.MANGABZ, "1864"))
        // 拷贝漫画：用户 2026-10-24 指定官方地址 mangacopy.com（不再用 copy4000）
        assertEquals("https://www.mangacopy.com/comic/qiankezhe", browserUrl(SourceType.COPYMANGA, "qiankezhe"))
        assertEquals("www.mangabz.com", domainOf(SourceType.MANGABZ))
        assertEquals("www.mangacopy.com", domainOf(SourceType.COPYMANGA))
        assertEquals("Mangabz", sourceNameOf(SourceType.MANGABZ))
        assertEquals("拷贝漫画", sourceNameOf(SourceType.COPYMANGA))
        // 空结果兜底入口：搜索页地址（ADR-0002 降级路径）
        assertEquals(
            "https://www.mangacopy.com/search?q=%E6%B5%B7%E8%B4%BC%E7%8E%8B",
            searchUrlOf(SourceType.COPYMANGA, "海贼王")
        )
        assertEquals(
            "https://www.mangabz.com/search?title=%E9%BB%91%E6%89%A7%E4%BA%8B",
            searchUrlOf(SourceType.MANGABZ, "黑执事")
        )
    }

    @Test
    fun `copymanga chapter id splits into path word and uuid`() {
        // pages() 依赖 "path_word#uuid" 复合 id 的拆分契约
        val id = "qiankezhe#52f9d522-0d71-11eb-93ea-00163e0ca5bd"
        val (pw, uuid) = id.split("#")
        assertEquals("qiankezhe", pw)
        assertEquals("52f9d522-0d71-11eb-93ea-00163e0ca5bd", uuid)
        assertTrue(uuid.isNotBlank() && pw.isNotBlank())
    }
}
