package app.gamanga.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * 书架**自定义封面**仓库（用户 2026-10-27：「书柜中的漫画封面支持右键自定义封面」）。
 *
 * ## 为什么用 pref 不用表
 *
 * 「这本漫画显示哪张图」是**纯展示字段**：没有外键、没有查询、没有联表，
 * 也没人会按封面筛选。为它加一张表 / 给 manga 加一列，就要动 schema 并走
 * `GamangaDatabaseFactory.STEPS` 的迁移梯子（老库升级、新库建表两条路都要写）。
 * 收益是零、风险是整库 —— `appPrefs` 本来就是干这个的（置顶列表、浏览历史都在这儿）。
 *
 * 所以整份映射塞进**一个** pref 键 [PREF_KEY]，值是一个 JSON 对象
 * `{ "<mangaId>": "<绝对路径>" }`。手写树 API 而不是 `@Serializable`：项目只引了
 * kotlinx-serialization-json 的树 API，没上编译器插件（同 [BrowseHistoryStore]）。
 *
 * ## 为什么 `clear` 不删文件
 *
 * 「取消自定义封面」要给回撤：回撤必须能把封面**指回原来那张图**。
 * 文件一删，回撤就只能恢复一个路径字符串，封面却永远是空白。
 * 这些图是本机小文件（用户自己选的几张图），留着比丢回撤便宜得多。
 *
 * ## 为什么文件名带内容哈希（对规格的一处偏离）
 *
 * 规格原文写「文件名 = `sha1(mangaId).img`」。实测不可行，两个原因：
 * 1. [app.gamanga.desktop.ui.ImageCache] 以 **URL 字符串**为键。文件名固定 → 换封面后
 *    URL 不变 → 缓存直接命中旧位图，用户会看到「换了封面还是老图」；
 * 2. 撤销要靠 `setToPath(prevPath)` 指回旧文件。文件名固定 = 新图覆盖旧文件，
 *    撤销后路径相同、图却是新的，等于没撤销。
 *
 * 所以保留 `sha1(mangaId)` 前缀（仍按 id 归档、易排查），后缀接一段**内容哈希**：
 * 同一张图重复选 → 同一个路径（不产生垃圾），换一张图 → 新路径（缓存自然失效、旧图留给撤销）。
 * 扩展名仍统一 `.img`：Skia 按魔术数字识别真实格式，与 [CoverCache] 一致。
 *
 * ## 容错
 *
 * 所有操作都 `runCatching` 包住：这条 pref 或某个文件坏掉，最坏结果是这本书回落到
 * 源站封面，**绝不能影响书架启动**（与 [BrowseHistoryStore.decode] 同一口径）。
 */
object CustomCoverStore {

    /** 唯一的 pref 键。 */
    const val PREF_KEY = "custom.covers"

    /** 落盘目录：`%LOCALAPPDATA%\Gamanga\custom-covers\`。 */
    private val dir: File by lazy {
        File(
            System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
            "Gamanga/custom-covers"
        ).apply { mkdirs() }
    }

    /** 内存态：懒加载一次（第一次被书架读到时才碰数据库）。 */
    private val state: MutableStateFlow<Map<String, String>> by lazy {
        MutableStateFlow(decode(AppGraph.libraryRepository.getPref(PREF_KEY)))
    }

    private val covers: Map<String, String> get() = state.value

    private val _version = MutableStateFlow(0)

    /**
     * 变更计数：UI 拿它当 `remember` 的 key（封面换了立刻重画）。
     *
     * 为什么不直接让 UI 收 [state]：书架卡片只关心「我这张变没变」，
     * 收整张 map 会让每张卡在**别人**换封面时也重组（一屏几十张）。
     */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** 是否有（且文件还在的）自定义封面。文件被手动删掉时当作没有，回落源站封面。 */
    fun has(mangaId: String): Boolean = alive(covers[mangaId])

    /**
     * 自定义封面的 `file:` URI（没有则 null），直接喂给 `RemoteImage`。
     *
     * 同步查文件存在性——和 [CoverCache.localUri] 一样，只是一次 stat，可以在组合期调用。
     */
    fun localUri(mangaId: String): String? {
        val path = covers[mangaId]?.takeIf { alive(it) } ?: return null
        return runCatching { File(path).toPath().toUri().toString() }.getOrNull()
    }

    /**
     * 原始落盘路径（撤销时 `setToPath` 要的是路径，不是 URI）。
     *
     * 不做文件存在性检查：撤销的意义就是「把状态改回去」，路径死了也不该让撤销失效。
     */
    fun localPath(mangaId: String): String? = covers[mangaId]?.takeIf { it.isNotBlank() }

    /**
     * 把用户选的图片**复制**进自定义封面目录并生效。返回是否成功。
     *
     * 复制而不是记原路径：用户随时可能删掉/移动自己那张图（甚至是桌面上的临时文件），
     * 记原路径过几天就成了裂图。
     */
    fun setFromFile(mangaId: String, picked: File): Boolean {
        if (mangaId.isBlank()) return false
        return runCatching {
            if (!picked.isFile || picked.length() <= 0L) return@runCatching false
            setFromBytes(mangaId, picked.readBytes())
        }.getOrDefault(false)
    }

    /**
     * 从**内存字节**设置封面（网络链接下载走这条，2026-10-27 第五轮新增）。
     *
     * 与 [setFromFile] 共用同一段落盘逻辑：文件名 = `sha1(id)-sha1(内容前16位).img`。
     * 内容哈希参与命名有两个用处 ——
     * 同一张图重复设置不产生垃圾文件；换一张图路径就变，内存图片缓存（按路径）自然失效。
     */
    fun setFromBytes(mangaId: String, bytes: ByteArray): Boolean {
        if (mangaId.isBlank() || bytes.isEmpty()) return false
        return runCatching {
            val target = File(dir, "${sha1(mangaId)}-${sha1(bytes).take(16)}.img")
            if (target.length() <= 0L) {
                // 先写 .part 再原子改名：避免半截文件被当成「已设置」的封面
                val tmp = File(target.parentFile, target.name + ".part")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return@runCatching false
                }
            }
            commit(covers + (mangaId to target.absolutePath))
            true
        }.getOrDefault(false)
    }

    /** 直接指向一个已落盘文件（撤销「取消自定义封面」时用）。 */
    fun setToPath(mangaId: String, path: String) {
        if (mangaId.isBlank() || path.isBlank()) return
        runCatching { commit(covers + (mangaId to path)) }
    }

    /**
     * 全部清空（设置页「清除所有数据」用，2026-10-27 第五轮）。
     *
     * **只清映射、不删磁盘文件**：与 [clear] 同一口径（撤销要能指回去），
     * 也避免「清数据」变成一次不可恢复的文件删除。
     */
    fun reset() {
        if (covers.isEmpty()) return
        runCatching { commit(emptyMap()) }
    }

    /**
     * 清映射 **并删掉本目录里的图片文件**（2026-10-27 第六轮：「清除所有数据」要恢复到
     * 刚安装的状态，第五轮遗留里写着「只清映射，磁盘会残留」）。
     *
     * ## 数据安全（用户明确要求「不要把其他软件的数据给清理掉」）
     *
     * 只删 [dir] 这个目录**直接子项**里的普通文件，而且要求：
     * 1. 路径 `canonicalPath` 必须真的在 [dir] 之内（防符号链接/`..` 逃逸）；
     * 2. 只删文件、不递归进子目录（本目录本就不该有子目录，有也不碰）；
     * 3. 单文件失败不影响其它（`runCatching`），最后返回删掉的数量。
     *
     * 绝不按通配符扫 `%LOCALAPPDATA%` 或用户目录——那是别人的数据。
     */
    fun purgeFiles(): Int {
        reset()
        val root = runCatching { dir.canonicalFile }.getOrNull() ?: return 0
        val children = runCatching { root.listFiles()?.toList().orEmpty() }
            .getOrDefault(emptyList())
        var removed = 0
        children.forEach { f ->
            val ok = runCatching {
                val target = f.canonicalFile
                // 双重保险：必须是本目录的**直接子项**且是普通文件
                target.parentFile == root && target.isFile && target.delete()
            }.getOrDefault(false)
            if (ok) removed++
        }
        return removed
    }

    /** 取消自定义封面。**不删文件**——撤销要靠它指回去（见类注释）。 */
    fun clear(mangaId: String) {
        if (!covers.containsKey(mangaId)) return
        runCatching { commit(covers - mangaId) }
    }

    // ---- 内部 ----

    /** 一次变更 = 改内存 + 落 pref + 版本自增（UI 靠版本重画）。 */
    private fun commit(next: Map<String, String>) {
        state.value = next
        runCatching { AppGraph.libraryRepository.setPref(PREF_KEY, encode(next)) }
        _version.value += 1
    }

    private fun alive(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching { File(path).length() > 0L }.getOrDefault(false)
    }

    private fun sha1(text: String): String = sha1(text.toByteArray(Charsets.UTF_8))

    private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun encode(map: Map<String, String>): String = buildJsonObject {
        map.forEach { (id, path) -> put(id, path) }
    }.toString()

    /**
     * 反序列化。**任何异常都退化成空 map**：这条 pref 坏掉只该让封面回落，
     * 不该像 schema 校验那样把整库判成损坏。
     */
    private fun decode(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(json).jsonObject.mapNotNull { (id, el) ->
                val path = el.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                id to path
            }.toMap()
        }.getOrElse { emptyMap() }
    }
}
