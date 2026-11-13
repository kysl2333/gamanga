package app.gamanga.desktop.source

import app.gamanga.desktop.model.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 源契约测试（F-20 剩余）。
 *
 * **目的**：新增/修改源适配器时，不用每次手工点一遍 UI —— 同一组断言对任意
 * [SourceAdapter] 跑一遍：能搜到 → 详情有标题 → 章节按 index 升序且不重复 → 图片是绝对地址。
 * 断言消息里带步骤名，挂了能直接看出断在哪一环。
 *
 * **默认关闭**：这组测试要打真实网络（拷贝漫画一次 10 秒起），默认跑会让
 * `:app:desktopTest` 变慢、并且随站点抖动而红。开启方式：
 *
 * ```bash
 * JAVA_HOME=... ./gradlew :app:desktopTest -Dgamanga.contract=1
 * ```
 *
 * 关闭时用 [Assume] 标记跳过（不是静默通过）——跳过数在测试报告里能看见。
 */
abstract class SourceContract {

    /** 被测适配器（各源自己提供）。 */
    protected abstract val adapter: SourceAdapter

    /**
     * 探针搜索词：**直接用适配器自己的 `probeKeyword`**。
     *
     * 那个词是「已知有结果」的（自检也用它），契约测试拿同一个词才不至于因为
     * 搜不到而误判成适配器坏了。
     */
    protected open val keyword: String get() = adapter.probeKeyword

    /** 取图是否跳过（例如站点当天不可达时只想验前半段）。 */
    protected open val skipPages: Boolean = false

    @Before
    fun optIn() {
        Assume.assumeTrue(
            "契约测试默认关闭；用 -Dgamanga.contract=1 开启",
            System.getProperty("gamanga.contract") == "1",
        )
    }

    @Test
    fun `contract - search detail chapters pages`() = runBlocking {
        val type = adapter.type

        // 1) 搜索
        val hits = adapter.search(keyword, page = 0, pageSize = 5)
        assertTrue(hits.isNotEmpty(), "[$type] 搜索「$keyword」返回 0 条：站点改版或路径/选择器配错")
        val first = hits.first()
        assertTrue(first.sourceId.isNotBlank(), "[$type] 搜索结果的 sourceId 为空")
        assertTrue(first.title.isNotBlank(), "[$type] 搜索结果的标题为空（sourceId=${first.sourceId}）")

        // 2) 详情
        val detail = adapter.detail(first.sourceId)
        assertTrue(detail != null, "[$type] 详情返回 null（sourceId=${first.sourceId}）")
        assertTrue(detail!!.title.isNotBlank(), "[$type] 详情标题为空（sourceId=${first.sourceId}）")

        // 3) 章节：升序、不重复、id 非空
        val chapters = adapter.chapters(first.sourceId)
        assertTrue(chapters.isNotEmpty(), "[$type] 章节列表为空（sourceId=${first.sourceId}）")
        assertTrue(
            chapters.map { it.id }.distinct().size == chapters.size,
            "[$type] 章节 id 有重复：${chapters.groupBy { it.id }.filterValues { it.size > 1 }.keys.take(3)}",
        )
        assertTrue(
            chapters.map { it.index } == chapters.indices.toList(),
            "[$type] 章节 index 不是 0..n-1 升序（前 5 个=${chapters.take(5).map { it.index }}）",
        )

        if (skipPages) return@runBlocking

        // 4) 取图：非空 + 绝对地址（拼 URL 时最容易在这里出错）
        //    配额/风控挡住的取图不是代码问题：跳过而不是判失败（Komiic 每日图片配额会被测试烧光）
        val pages = try {
            adapter.pages(chapters.first().id)
        } catch (e: Exception) {
            if (!e.isQuotaError) throw e
            Assume.assumeTrue("取图被配额挡住，跳过：${e.message}", false)
            return@runBlocking
        }
        assertTrue(pages.isNotEmpty(), "[$type] 第一章图片列表为空（chapterId=${chapters.first().id}）")
        assertTrue(
            pages.all { it.url.startsWith("http://") || it.url.startsWith("https://") },
            "[$type] 图片 URL 不是绝对地址：${pages.firstOrNull { !it.url.startsWith("http") }?.url}",
        )
    }
}

/** Komiic：纯 GraphQL，最稳的一条链。 */
class KomiicContractTest : SourceContract() {
    override val adapter: SourceAdapter get() = Sources.of(SourceType.KOMIIC)!!
}

/** G社（包子系）：取图要起隐藏浏览器，最慢，但结构最完整。 */
class GodaContractTest : SourceContract() {
    override val adapter: SourceAdapter get() = Sources.of(SourceType.GODA)!!
}

/** MangaBZ：asHx 返回 JS packer，取图链最容易随改版断。 */
class MangabzContractTest : SourceContract() {
    override val adapter: SourceAdapter get() = Sources.of(SourceType.MANGABZ)!!
}

/**
 * 拷贝漫画：搜索/详情走隐藏浏览器（10 秒级），且受登录态与风控影响。
 * 取图需要页面内 AES 解密，契约里保留（真断了要能第一时间知道）。
 */
class CopymangaContractTest : SourceContract() {
    override val adapter: SourceAdapter get() = Sources.of(SourceType.COPYMANGA)!!
}
