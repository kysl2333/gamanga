package app.gamanga.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.core.AppVersion
import app.gamanga.desktop.core.RenderBackend
import app.gamanga.desktop.data.AppGraph
import app.gamanga.desktop.data.CacheUsage
import app.gamanga.desktop.data.CoverCache
import app.gamanga.desktop.data.CustomCoverStore
import app.gamanga.desktop.core.webview.CefRuntime
import app.gamanga.desktop.data.DownloadManager
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.data.SourceConfig
import app.gamanga.desktop.data.SourceEndpoint
import app.gamanga.desktop.data.SourceHealth
import app.gamanga.desktop.data.SourceStatus
import app.gamanga.desktop.data.WebDavClient
import app.gamanga.desktop.data.WebDavEntry
import app.gamanga.desktop.data.backup.BackupManager
import app.gamanga.desktop.data.backup.MihonImportMode
import app.gamanga.desktop.data.backup.MihonImportPreview
import app.gamanga.desktop.data.backup.MihonImportProgress
import app.gamanga.desktop.data.backup.MihonImportReport
import app.gamanga.desktop.data.joinUrl
import app.gamanga.desktop.ui.settings.MihonImportDialog
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.Http
import app.gamanga.desktop.source.Sources
import app.gamanga.desktop.source.sourceNameOf
import app.gamanga.desktop.ui.shell.DockIcons
import app.gamanga.desktop.ui.theme.AppFont
import app.gamanga.desktop.ui.theme.MixText
import app.gamanga.desktop.ui.theme.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置页（2026-10-28 重做）。
 *
 * ## 版式
 *
 * 左侧固定导航 + 右侧独立滚动的分组白卡，参照主流桌面应用的设置中心：
 * 搜索框 → 分组标题 → 条目；右侧每张卡是一组相关设置，**每行一个设置项**：
 * 左列标题 + 一句说明，右列控件。视觉只靠留白、圆角和极淡投影分层，
 * 不再用「每块一个描边方框」——那是上一版看起来像配置表单的原因。
 *
 * ## 交互约定（用户 2026-10-28 明确要求）
 *
 * - **左侧切换不做动画**：点哪一类立即换右侧内容。
 * - 清理类操作一律二次确认，确认框白底、圆角、危险项红字。
 * - 操作结果统一走底部浮空提示，3.6 秒自动消失，不再在卡片里留一行常驻文字。
 *
 * ## 业务边界
 *
 * 备份（本地 / mihon 导入 / WebDAV）、源站地址、下载缓存位置与清理、封面缓存、
 * 阅读统计、清除数据、网络代理全部沿用原实现，本次只重排视觉与提示。
 */
@Composable
fun SettingsScreen(
    repository: LibraryRepository,
    backupManager: BackupManager,
    downloadManager: DownloadManager = AppGraph.downloadManager,
) {
    val scope = rememberCoroutineScope()

    // ---- WebDAV 状态 ----
    var url by remember { mutableStateOf(repository.getPref(WebDavClient.PREF_URL) ?: "") }
    var user by remember { mutableStateOf(repository.getPref(WebDavClient.PREF_USER) ?: "") }
    var pass by remember { mutableStateOf(repository.getPref(WebDavClient.PREF_PASS) ?: "") }
    var dir by remember { mutableStateOf(repository.getPref(WebDavClient.PREF_DIR) ?: WebDavClient.BACKUP_DIR) }
    var status by remember { mutableStateOf("") }
    var statusError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf<List<WebDavEntry>>(emptyList()) }
    var webdavBusy by remember { mutableStateOf(false) }
    var webdavStatus by remember { mutableStateOf("") }
    var webdavError by remember { mutableStateOf(false) }

    val client = remember(url, user, pass, dir) { WebDavClient(url.trim(), user.trim(), pass, dir.trim()) }

    fun say(msg: String, error: Boolean = false) {
        status = msg
        statusError = error
    }

    fun sayWebDav(msg: String, error: Boolean = false) {
        webdavStatus = msg
        webdavError = error
    }

    fun refreshList() {
        if (url.isBlank()) return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { client.list() } }
                .onSuccess {
                    backups = it
                    if (webdavError && webdavStatus.startsWith("读取云端列表失败")) {
                        webdavStatus = ""; webdavError = false
                    }
                }
                .onFailure {
                    backups = emptyList()
                    sayWebDav("读取云端列表失败：${it.message ?: "未知错误"}", error = true)
                    AppLog.w("WebDav", "list failed url=${url.trim()}", it)
                }
        }
    }

    LaunchedEffect(Unit) { refreshList() }

    var category by remember { mutableStateOf("备份与恢复") }
    var query by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    fun confirm(text: String, action: () -> Unit) { confirmation = text to action }

    // F-275（2026-10-29）：mihon 导入改成向导——选文件 → 预览（可勾选 / 改别称 / 换源）→ 按选择导入。
    // 旧流程是「解析完直接开干」，认不出的源默默跳过，用户既看不到是哪几部也没机会救。
    var mihonBytes by remember { mutableStateOf<ByteArray?>(null) }
    var mihonPreview by remember { mutableStateOf<MihonImportPreview?>(null) }
    var mihonBusy by remember { mutableStateOf(false) }
    var mihonResult by remember { mutableStateOf<MihonImportReport?>(null) }
    // R-172：导入进度（正在导第几本），null = 没在导
    var mihonProgress by remember { mutableStateOf<MihonImportProgress?>(null) }
    // R-193（2026-10-30）：导入作业句柄。导入一部要拉一次源站章节列表，几十部就是几分钟，
    // 期间弹窗把整屏都吃掉——没有「取消」用户只能强杀进程（他报的「按钮点了没反应」）。
    var mihonJob by remember { mutableStateOf<Job?>(null) }

    // 操作结果 3.6 秒后自动消失（用户要求浮空提示，不再常驻页面）
    LaunchedEffect(status, webdavStatus) {
        if (status.isNotEmpty() || webdavStatus.isNotEmpty()) {
            delay(3600)
            status = ""; webdavStatus = ""
        }
    }

    fun doBackup(force: Boolean) {
        webdavBusy = true
        sayWebDav(if (force) "正在强制覆盖云端…" else "正在导出并上传…")
        scope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    backupManager.backupToWebDav(client, force = force)
                }
                val sizeKb = report.sizeBytes / 1024
                val del = if (report.deleted.isEmpty()) ""
                else "；清理旧时间戳备份 ${report.deleted.size} 份"
                sayWebDav("已覆盖 ${report.fileName}（$sizeKb KB）$del")
                AppLog.i("WebDav", "backup ok name=${report.fileName} bytes=${report.sizeBytes}")
                refreshList()
            } catch (e: Exception) {
                sayWebDav("备份失败：${e.message ?: "未知错误"}", error = true)
                AppLog.e("WebDav", "backup failed url=${url.trim()}", e)
            }
            webdavBusy = false
        }
    }

    val notice = status.ifEmpty { webdavStatus }
    val noticeError = if (status.isNotEmpty()) statusError else webdavError

    Box(Modifier.fillMaxSize().background(Tokens.Color.BgBase)) {
    Row(Modifier.fillMaxSize()) {
        SettingsSidebar(
            category = category,
            query = query,
            onQuery = { query = it },
            onSelect = { category = it },
        )

        Box(Modifier.weight(1f).fillMaxHeight()) {
            val scroll = rememberScrollState()
            SettingsContentFrame(scroll) {
                    when (category) {
                        "备份与恢复" -> BackupRestoreSettings(
                            url = url, user = user, pass = pass, dir = dir,
                            onUrl = { url = it }, onUser = { user = it },
                            onPass = { pass = it }, onDir = { dir = it },
                            webdavBusy = webdavBusy, backups = backups,
                            onSave = {
                                repository.setPref(WebDavClient.PREF_URL, url.trim())
                                repository.setPref(WebDavClient.PREF_USER, user.trim())
                                repository.setPref(WebDavClient.PREF_PASS, pass)
                                repository.setPref(WebDavClient.PREF_DIR, dir.trim())
                                sayWebDav("配置已保存")
                            },
                            onTest = {
                                webdavBusy = true
                                sayWebDav("正在连接…")
                                scope.launch {
                                    try {
                                        val n = withContext(Dispatchers.IO) { client.testConnection() }
                                        sayWebDav(
                                            "连接正常：" + (if (dir.isBlank()) "根目录" else "$dir/") +
                                                "下现有 $n 个备份"
                                        )
                                        AppLog.i("WebDav", "test ok url=${url.trim()} backups=$n")
                                        refreshList()
                                    } catch (e: Exception) {
                                        sayWebDav("连接失败：${e.message ?: "未知错误"}", error = true)
                                        AppLog.e("WebDav", "test failed url=${url.trim()}", e)
                                    }
                                    webdavBusy = false
                                }
                            },
                            onRefresh = { refreshList() },
                            onBackup = { doBackup(force = false) },
                            onForceBackup = { doBackup(force = true) },
                            onRestore = { entry ->
                                webdavBusy = true
                                scope.launch {
                                    try {
                                        val bytes = withContext(Dispatchers.IO) {
                                            client.download(joinUrl(WebDavClient.BACKUP_DIR, entry.name))
                                        }
                                        val report = backupManager.restoreBackup(bytes)
                                        sayWebDav(
                                            "已从 ${entry.name} 恢复：${report.mangaCount} 部漫画、" +
                                                "${report.chapterCount} 章、${report.progressCount} 条进度"
                                        )
                                    } catch (e: Exception) {
                                        sayWebDav("恢复失败：${e.message ?: "未知错误"}", error = true)
                                        AppLog.e("WebDav", "restore failed name=${entry.name}", e)
                                    }
                                    webdavBusy = false
                                }
                            },
                            onDelete = { entry ->
                                webdavBusy = true
                                scope.launch {
                                    val ok = runCatching {
                                        withContext(Dispatchers.IO) {
                                            client.delete(joinUrl(WebDavClient.BACKUP_DIR, entry.name))
                                        }
                                    }.getOrDefault(false)
                                    sayWebDav(if (ok) "已删除 ${entry.name}" else "删除失败", error = !ok)
                                    refreshList()
                                    webdavBusy = false
                                }
                            },
                            localBusy = busy,
                            onExport = {
                                val target = showFileDialog(java.awt.FileDialog.SAVE, "gamanga-backup.zip") {
                                    it.lowercase().endsWith(".zip")
                                } ?: return@BackupRestoreSettings
                                busy = true
                                scope.launch {
                                    try {
                                        val bytes = backupManager.exportBackup()
                                        withContext(Dispatchers.IO) { target.writeBytes(bytes) }
                                        say("已导出到 ${target.absolutePath}")
                                    } catch (e: Exception) {
                                        say("导出失败：${e.message ?: "未知错误"}", error = true)
                                    }
                                    busy = false
                                }
                            },
                            onRestoreFile = {
                                val f = showFileDialog(java.awt.FileDialog.LOAD, null) {
                                    it.lowercase().endsWith(".zip")
                                } ?: return@BackupRestoreSettings
                                busy = true
                                scope.launch {
                                    try {
                                        val bytes = withContext(Dispatchers.IO) { f.readBytes() }
                                        val report = backupManager.restoreBackup(bytes)
                                        say(
                                            "已恢复：${report.mangaCount} 部漫画、${report.chapterCount} 章、" +
                                                "${report.progressCount} 条进度、${report.prefsCount} 项设置"
                                        )
                                    } catch (e: Exception) {
                                        say("恢复失败：${e.message ?: "文件不是合法备份"}", error = true)
                                    }
                                    busy = false
                                }
                            },
                            onImportMihon = {
                                val f = showFileDialog(java.awt.FileDialog.LOAD, null) {
                                    val n = it.lowercase()
                                    n.endsWith(".tachibk") || n.endsWith(".gz")
                                } ?: return@BackupRestoreSettings
                                mihonBusy = true
                                scope.launch {
                                    try {
                                        val bytes = withContext(Dispatchers.IO) { f.readBytes() }
                                        val preview = backupManager.previewMihonImport(bytes)
                                        mihonBytes = bytes
                                        mihonPreview = preview
                                        mihonResult = null
                                    } catch (e: Exception) {
                                        say("读取失败：${e.message ?: "文件不是合法 mihon 备份"}", error = true)
                                    }
                                    mihonBusy = false
                                }
                            },
                        )

                        "源站更换" -> SourceConfigBlock { text, error -> say(text, error) }

                        "书架刷新" -> ChapterRefreshSettings(repository = repository, say = ::say)

                        "缓存与清理" -> CacheSettings(
                            repository = repository,
                            downloadManager = downloadManager,
                            confirm = ::confirm,
                            say = ::say,
                        )

                        "网络代理" -> ProxySettings(repository = repository, say = ::say)

                        else -> AboutSettings(
                            repository = repository,
                            downloadManager = downloadManager,
                            onNotice = ::say,
                        )
                    }
                    Spacer(Modifier.height(28.dp))
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    .width(10.dp).padding(vertical = 12.dp),
            )
        }
    }

    // 浮空提示：从下往上淡入，避让底部 dock
    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)) {
        AnimatedVisibility(
            visible = notice.isNotEmpty(),
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(180)) { it / 3 },
        ) {
            SettingsToast(text = notice, error = noticeError)
        }
    }

    confirmation?.let { pending ->
        SettingsConfirm(
            title = "确认清理？",
            message = pending.first,
            confirmLabel = "确认清理",
            onConfirm = { confirmation = null; pending.second() },
            onDismiss = { confirmation = null },
        )
    }

    // mihon 导入向导（F-275）：预览 → 勾选 / 改别称 / 换源 → 按选择导入
    val preview = mihonPreview
    val bytes = mihonBytes
    if (preview != null && bytes != null) {
        MihonImportDialog(
            preview = preview,
            busy = mihonBusy,
            result = mihonResult,
            onDismiss = {
                if (!mihonBusy) {
                    mihonPreview = null
                    mihonBytes = null
                    mihonResult = null
                    mihonProgress = null
                }
            },
            // R-166：流式并发搜索（谁先回来先显示），不再等最慢的源
            progress = mihonProgress,
            onSearch = { alias, title -> backupManager.searchMihonCandidatesStreaming(alias, title) },
            onImport = { choices, mode ->
                mihonBusy = true
                mihonProgress = MihonImportProgress(done = 0, total = choices.size, title = "")
                mihonJob = scope.launch {
                    try {
                        val report = backupManager.importMihonSelection(bytes, choices, mode) { p ->
                            // **不要在这里 launch 到 Dispatchers.Main**（R-193）：
                            // 桌面端缺 Main dispatcher 时 launch 会**同步抛异常**，异常顺着
                            // 回调冒进导入循环，整批导入当场中断——这就是用户「恢复备份后
                            // 书架/历史还是空的、按钮没反应」的根因。Compose 的 snapshot
                            // 状态写入本身线程安全，直接赋值即可（回调在 IO 线程）。
                            mihonProgress = p
                        }
                        mihonResult = report
                        say(
                            "导入完成：新入架 ${report.mangaImported} 部（${report.chaptersImported} 章），" +
                                "回填观看历史 ${report.historyRestored} 章" +
                                if (report.skipped.isEmpty()) "" else "，跳过 ${report.skipped.size} 部",
                        )
                    } catch (e: CancellationException) {
                        // 用户点了「取消导入」：已导入的保留，不是错误
                        say("已取消导入（已导入的书保留在书架里）")
                        throw e
                    } catch (e: Exception) {
                        say("导入失败：${e.message ?: "未知错误"}", error = true)
                    } finally {
                        mihonBusy = false
                        mihonProgress = null
                        mihonJob = null
                    }
                }
            },
            onCancelImport = {
                mihonJob?.cancel()
                mihonJob = null
                mihonBusy = false
                mihonProgress = null
            },
        )
    }
    }
}

// ---------------------------------------------------------------------------
// 左侧导航
// ---------------------------------------------------------------------------

private val SettingsGroups = listOf(
    "设置" to listOf("备份与恢复"),
    "功能" to listOf("源站更换", "书架刷新", "网络代理"),
    "数据与安全" to listOf("缓存与清理", "应用信息"),
)

private val SettingsIcons = mapOf(
    "备份与恢复" to DockIcons.Shelf,
    "源站更换" to DockIcons.Globe,
    "书架刷新" to DockIcons.Refresh,
    "网络代理" to DockIcons.External,
    "缓存与清理" to DockIcons.Trash,
    "应用信息" to DockIcons.Settings,
)

@Composable
internal fun SettingsSidebar(
    category: String,
    query: String,
    onQuery: (String) -> Unit,
    onSelect: (String) -> Unit,
    showHealth: Boolean = true,
) {
    Column(
        Modifier.width(238.dp).fillMaxHeight()
            .padding(start = 22.dp, end = 12.dp, top = 26.dp, bottom = 18.dp)
    ) {
        MixText(
            "设置",
            style = Tokens.Type.Title.toTextStyle().copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(start = 4.dp, bottom = 16.dp),
        )
        SettingsSearchField(query, onQuery)
        Spacer(Modifier.height(6.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SettingsGroups.forEach { (group, items) ->
                val visible = items.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
                if (visible.isNotEmpty()) {
                    MixText(
                        group,
                        color = Tokens.Color.TextTertiary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 10.dp, top = 14.dp, bottom = 5.dp),
                    )
                    visible.forEach { item ->
                        SettingsNavRow(
                            label = item,
                            icon = SettingsIcons[item] ?: DockIcons.Settings,
                            selected = category == item,
                            onClick = { onSelect(item) },
                        )
                    }
                }
            }
        }
        if (showHealth) SettingsSourceHealth()
    }
}

@Composable
private fun SettingsSearchField(value: String, onValueChange: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier.fillMaxWidth().height(34.dp)
            .clip(shape)
            .background(Tokens.Color.BgSunken)
            .border(1.dp, if (focused) Tokens.Color.Accent.copy(alpha = .45f) else Color.Transparent, shape)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DockIcons.Search, null, Modifier.size(13.dp), tint = Tokens.Color.TextTertiary)
        Spacer(Modifier.width(7.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                MixText("搜索设置项", color = Tokens.Color.TextTertiary, fontSize = 12.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = Tokens.Color.TextPrimary, fontFamily = AppFont.Ui),
                cursorBrush = SolidColor(Tokens.Color.Accent),
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(16.dp).clip(CircleShape).background(Color(0x14000000))
                    .primaryPress { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) { MixText("×", color = Tokens.Color.TextSecondary, fontSize = 11.sp) }
        }
    }
}

@Composable
internal fun SettingsNavRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // 选中态不加过渡动画：用户要求左侧「点哪立即到哪」。
    Row(
        Modifier.fillMaxWidth().height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> Tokens.Color.BgSunken
                    hovered -> Color(0x80FFFFFF)
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, null, Modifier.size(15.dp),
            tint = if (selected) Tokens.Color.TextPrimary else Tokens.Color.TextSecondary,
        )
        Spacer(Modifier.width(9.dp))
        MixText(
            label,
            color = if (selected) Tokens.Color.TextPrimary else Tokens.Color.TextSecondary,
            fontSize = 13.sp,
            style = Tokens.Type.Body.toTextStyle().copy(
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            ),
        )
    }
}

/** 源状态小浮卡：只给灯和名字，检测按钮收成右上角图标。 */
@Composable
private fun SettingsSourceHealth() {
    val reports by SourceHealth.reports.collectAsState()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp)
            .shadow(3.dp, RoundedCornerShape(12.dp), clip = false, ambientColor = Color(0x0A000000), spotColor = Color(0x12000000))
            .clip(RoundedCornerShape(12.dp))
            .background(Tokens.Color.BgElevatedSolid)
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MixText("源状态", Modifier.weight(1f), color = Tokens.Color.TextSecondary, fontSize = 11.sp)
            IconButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    scope.launch { try { SourceHealth.checkAll() } finally { checking = false } }
                },
                modifier = Modifier.size(22.dp),
            ) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Tokens.Color.Accent)
                } else {
                    MixText(
                        "↻",
                        color = Tokens.Color.TextTertiary,
                        fontSize = 13.sp,
                        modifier = Modifier.semantics { contentDescription = "检测全部源" },
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Sources.all().forEach { source ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(6.dp).background(
                        when (reports[source.type]?.status) {
                            SourceStatus.OK -> Tokens.Color.Success
                            SourceStatus.SUSPECT -> Tokens.Color.Warning
                            SourceStatus.FAIL -> Tokens.Color.Danger
                            null -> Tokens.Color.TextTertiary
                        },
                        CircleShape,
                    )
                )
                Spacer(Modifier.width(7.dp))
                MixText(source.displayName, color = Tokens.Color.TextPrimary, fontSize = 11.5.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 备份与恢复
// ---------------------------------------------------------------------------

@Composable
private fun BackupRestoreSettings(
    url: String, user: String, pass: String, dir: String,
    onUrl: (String) -> Unit, onUser: (String) -> Unit,
    onPass: (String) -> Unit, onDir: (String) -> Unit,
    webdavBusy: Boolean, backups: List<WebDavEntry>,
    onSave: () -> Unit, onTest: () -> Unit, onRefresh: () -> Unit,
    onBackup: () -> Unit, onForceBackup: () -> Unit,
    onRestore: (WebDavEntry) -> Unit, onDelete: (WebDavEntry) -> Unit,
    localBusy: Boolean,
    onExport: () -> Unit, onRestoreFile: () -> Unit, onImportMihon: () -> Unit,
) {
    SettingsPageHeader("备份与恢复", "把书架、阅读进度和设置备份到本地文件或云端网盘。")

    SettingsGroup("云端备份") {
        SettingsCard {
            SettingsStackRow("WebDAV 地址", "坚果云、Nextcloud 等支持 Basic 认证的网盘。") {
                SettingsField(url, onUrl, "https://dav.jianguoyun.com/dav/")
            }
            SettingsDivider()
            SettingsRow("账号与密码", "多数网盘要求填写应用专用密码，而不是登录密码。") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsField(user, onUser, "用户名", Modifier.width(148.dp))
                    SettingsField(pass, onPass, "密码 / 授权码", Modifier.width(148.dp), password = true)
                }
            }
            SettingsDivider()
            SettingsStackRow(
                "备份子目录",
                "留空表示直接放在上面的地址里。云端只保留最近 ${BackupManager.DEFAULT_KEEP} 份。",
            ) {
                SettingsField(dir, onDir, "Gamanga")
            }
            SettingsDivider()
            SettingsRow("连接", "先测试连通性，再上传或恢复备份。") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsButton(onClick = onSave, primary = true, enabled = !webdavBusy && url.isNotBlank()) {
                        MixText("保存配置", color = Color.White, fontSize = 12.5.sp)
                    }
                    SettingsButton(onClick = onTest, enabled = !webdavBusy && url.isNotBlank()) {
                        MixText("测试连接", fontSize = 12.5.sp)
                    }
                    SettingsButton(onClick = onRefresh, enabled = !webdavBusy && url.isNotBlank()) {
                        MixText("刷新", fontSize = 12.5.sp)
                    }
                    if (webdavBusy) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.8.dp, color = Tokens.Color.Accent)
                }
            }
            SettingsDivider()
            SettingsRow(
                "上传备份",
                "覆盖式：云端保留最新与上一版；云端比本地新时会跳过，防止旧数据覆盖新数据。",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsButton(onClick = onBackup, primary = true, enabled = !webdavBusy && url.isNotBlank()) {
                        MixText("立即备份", color = Color.White, fontSize = 12.5.sp)
                    }
                    SettingsTextButton(onClick = onForceBackup, enabled = !webdavBusy && url.isNotBlank()) {
                        MixText("强制覆盖", color = Tokens.Color.TextSecondary, fontSize = 12.5.sp)
                    }
                }
            }
        }
    }

    SettingsGroup("本地文件") {
        SettingsCard {
            SettingsRow("导出备份", "生成一个 zip，包含书架、章节、进度、历史与设置。") {
                SettingsButton(onClick = onExport, enabled = !localBusy) {
                    MixText("导出到文件…", fontSize = 12.5.sp)
                }
            }
            SettingsDivider()
            SettingsRow("从文件恢复", "合并式恢复：备份内容覆盖本地，阅读进度以较新的为准。") {
                SettingsButton(onClick = onRestoreFile, enabled = !localBusy) {
                    MixText("选择备份文件…", fontSize = 12.5.sp)
                }
            }
            SettingsDivider()
            SettingsRow("导入 mihon 备份", "支持 .tachibk：先列出每部（可直接导入 / 需要换源），可改搜索别称、换源后再导入；章节按名字对齐回填进度。") {
                SettingsButton(onClick = onImportMihon, enabled = !localBusy) {
                    MixText("选择文件…", fontSize = 12.5.sp)
                }
            }
        }
    }

    SettingsGroup("云端文件") {
        SettingsCard {
            if (backups.isEmpty()) {
                SettingsRow("还没有云端备份", if (url.isBlank()) "先填写并保存 WebDAV 地址。" else "点上面的「立即备份」上传第一份。") {
                    Spacer(Modifier.width(1.dp))
                }
            } else {
                backups.forEachIndexed { index, entry ->
                    if (index > 0) SettingsDivider()
                    SettingsRow(
                        entry.name,
                        "${entry.sizeBytes / 1024} KB · ${entry.lastModified}",
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            SettingsButton(onClick = { onRestore(entry) }, enabled = !webdavBusy) {
                                MixText("恢复", fontSize = 12.5.sp)
                            }
                            SettingsTextButton(onClick = { onDelete(entry) }, enabled = !webdavBusy) {
                                MixText("删除", color = Tokens.Color.Danger, fontSize = 12.5.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 缓存与清理
// ---------------------------------------------------------------------------

@Composable
private fun CacheSettings(
    repository: LibraryRepository,
    downloadManager: DownloadManager,
    confirm: (String, () -> Unit) -> Unit,
    say: (String, Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dm = downloadManager
    var cacheUsage by remember { mutableStateOf<List<CacheUsage>>(emptyList()) }
    var cacheBusy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { cacheUsage = withContext(Dispatchers.IO) { dm.cacheUsage() } }
    val totalBytes = cacheUsage.sumOf { it.bytes }

    SettingsPageHeader("缓存与清理", "查看本地占用，清理不需要的文件。删除前都会二次确认。")

    SettingsGroup("下载缓存") {
        SettingsCard {
            SettingsRow("缓存位置", dm.rootDir().toAbsolutePath().toString()) {
                SettingsButton(
                    onClick = {
                        val target = showDirectoryDialog("选择下载缓存位置")
                            ?: return@SettingsButton
                        cacheBusy = true
                        scope.launch {
                            val err = withContext(Dispatchers.IO) { dm.relocate(target.toPath()) }
                            if (err == null) {
                                repository.setPref(LibraryRepository.PREF_DOWNLOAD_ROOT, target.absolutePath)
                                cacheUsage = withContext(Dispatchers.IO) { dm.cacheUsage() }
                                say("缓存位置已更改，文件已搬到 ${target.absolutePath}", false)
                            } else {
                                say(err, true)
                            }
                            cacheBusy = false
                        }
                    },
                    enabled = !cacheBusy,
                ) { MixText("更改…", fontSize = 12.5.sp) }
            }
            SettingsDivider()
            SettingsRow(
                "已占用",
                "${DownloadManager.formatBytes(totalBytes)} · ${cacheUsage.size} 部漫画",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsButton(
                        onClick = {
                            confirm("删除全部已下载章节图片。书架和阅读进度保留，离线阅读需重新下载。") {
                                cacheBusy = true
                                scope.launch {
                                    val freed = withContext(Dispatchers.IO) {
                                        val bytes = dm.totalCacheBytes()
                                        if (!dm.clearAllCache()) -1L else bytes
                                    }
                                    cacheUsage = emptyList()
                                    if (freed < 0) {
                                        say(
                                            "已拒绝清理：当前位置不是 Gamanga 下载缓存目录（缺少归属标记）。" +
                                                "请先「更改…」到本应用管理的目录。",
                                            true,
                                        )
                                    } else {
                                        say("已清空全部下载缓存（释放 ${DownloadManager.formatBytes(freed)}）", false)
                                    }
                                    cacheBusy = false
                                }
                            }
                        },
                        danger = true,
                        enabled = !cacheBusy && cacheUsage.isNotEmpty(),
                    ) { MixText("全部清理", color = Tokens.Color.Danger, fontSize = 12.5.sp) }
                    if (cacheBusy) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.8.dp, color = Tokens.Color.Accent)
                }
            }
            if (cacheUsage.isEmpty()) {
                SettingsDivider()
                SettingsRow("还没有已下载的章节", "在浏览页详情里选章下载，之后可在这里管理。") {
                    Spacer(Modifier.width(1.dp))
                }
            } else {
                cacheUsage.forEach { entry ->
                    SettingsDivider()
                    SettingsRow(
                        entry.mangaTitle.ifBlank { entry.mangaId },
                        DownloadManager.formatBytes(entry.bytes),
                    ) {
                        SettingsTextButton(
                            enabled = !cacheBusy,
                            onClick = {
                                val title = entry.mangaTitle.ifBlank { entry.mangaId }
                                confirm("删除《$title》的下载图片，书架与进度保留。") {
                                    cacheBusy = true
                                    scope.launch {
                                        val freed = withContext(Dispatchers.IO) {
                                            val bytes = entry.bytes
                                            dm.deleteMangaDownloads(entry.mangaId)
                                            bytes
                                        }
                                        cacheUsage = withContext(Dispatchers.IO) { dm.cacheUsage() }
                                        say("已清理《$title》（释放 ${DownloadManager.formatBytes(freed)}）", false)
                                        cacheBusy = false
                                    }
                                }
                            },
                        ) { MixText("清理", color = Tokens.Color.Danger, fontSize = 12.5.sp) }
                    }
                }
            }
        }
    }

    SettingsGroup("其他缓存") {
        SettingsCard {
            var coverCount by remember { mutableStateOf(0) }
            var coverBytes by remember { mutableStateOf(0L) }
            fun refreshCover() {
                val (c, b) = CoverCache.usage()
                coverCount = c
                coverBytes = b
            }
            LaunchedEffect(Unit) { refreshCover() }
            SettingsRow(
                "封面缓存",
                "$coverCount 张 · ${coverBytes / 1024 / 1024} MB，断网时书架靠它显示封面。",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsButton(
                        onClick = {
                            confirm("清理本地封面缓存。下次联网会重新下载封面，书架与阅读进度保留。") {
                                scope.launch {
                                    val n = withContext(Dispatchers.IO) { CoverCache.clearAll() }
                                    refreshCover()
                                    say("已清理封面缓存（$n 张）", false)
                                }
                            }
                        },
                        danger = true,
                    ) { MixText("清理", color = Tokens.Color.Danger, fontSize = 12.5.sp) }
                    SettingsTextButton(onClick = { refreshCover() }) {
                        MixText("刷新", color = Tokens.Color.TextSecondary, fontSize = 12.5.sp)
                    }
                }
            }
        }
    }

    // 2026-11-13（A6）：浏览器内核管理 —— 用户要求"告知占多大、能二次确认移除、
    // 提醒卸载前自行清理配置与内核"。
    SettingsGroup("浏览器内核") {
        SettingsCard {
            val dataDir = remember {
                java.io.File(
                    System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
                    "Gamanga",
                ).absolutePath
            }
            var cefPath by remember { mutableStateOf("") }
            var cefBytes by remember { mutableStateOf(0L) }
            var cefCacheBytes by remember { mutableStateOf(0L) }
            var cefBusy by remember { mutableStateOf(false) }

            fun refreshCef() {
                cefBusy = true
                scope.launch {
                    val (p, b, c) = withContext(Dispatchers.IO) {
                        Triple(
                            CefRuntime.cefRootDir().absolutePath,
                            CefRuntime.runtimeBytes(),
                            CefRuntime.cacheBytes(),
                        )
                    }
                    cefPath = p
                    cefBytes = b
                    cefCacheBytes = c
                    cefBusy = false
                }
            }
            LaunchedEffect(Unit) { refreshCef() }

            SettingsRow(
                "内核位置",
                cefPath.ifBlank { "未安装（首次使用浏览器功能时自动下载）" },
            ) {
                SettingsTextButton(onClick = { refreshCef() }, enabled = !cefBusy) {
                    MixText("刷新", color = Tokens.Color.TextSecondary, fontSize = 12.5.sp)
                }
            }
            SettingsDivider()
            SettingsRow(
                "已占用",
                "${DownloadManager.formatBytes(cefBytes)} 内核 · ${DownloadManager.formatBytes(cefCacheBytes)} 缓存",
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsButton(
                        danger = true,
                        enabled = !cefBusy && (cefBytes > 0 || cefCacheBytes > 0),
                        onClick = {
                            confirm(
                                "删除浏览器内核（约 ${DownloadManager.formatBytes(cefBytes + cefCacheBytes)}）。" +
                                    "删掉后需要登录或需要执行脚本的源将无法使用，直到重新下载内核。" +
                                    "书架、下载、阅读进度不受影响。",
                            ) {
                                cefBusy = true
                                scope.launch {
                                    val freed = withContext(Dispatchers.IO) { CefRuntime.removeRuntime() }
                                    refreshCef()
                                    say(
                                        "已移除浏览器内核（释放 ${DownloadManager.formatBytes(freed)}）。" +
                                            "已打开的浏览器需重启应用后彻底释放。",
                                        false,
                                    )
                                }
                            }
                        },
                    ) { MixText("移除内核", color = Tokens.Color.Danger, fontSize = 12.5.sp) }
                    if (cefBusy) {
                        CircularProgressIndicator(
                            Modifier.size(15.dp),
                            strokeWidth = 1.8.dp,
                            color = Tokens.Color.Accent,
                        )
                    }
                }
            }
            SettingsDivider()
            SettingsRow(
                "卸载前请自行清理",
                "本软件卸载时不会删除任何数据。配置目录 $dataDir 内含数据库、封面、下载与浏览器内核，" +
                    "不再使用时请手动删除该目录。",
            ) { Spacer(Modifier.width(1.dp)) }
        }
    }

    SettingsGroup("阅读数据") {
        SettingsCard {
            SettingsRow(
                "观看时长统计",
                "书架页统计小框记录今日与累计时长；清空后归零，书架与进度不受影响。",
            ) {
                SettingsButton(
                    danger = true,
                    onClick = {
                        confirm("只清除观看时长记录，书架与阅读进度不受影响。清空后无法恢复。") {
                            repository.clearReadingTime()
                            say("已清空观看时长统计", false)
                        }
                    },
                ) { MixText("清空统计", color = Tokens.Color.Danger, fontSize = 12.5.sp) }
            }
        }
    }

    SettingsGroup("重置") {
        SettingsCard {
            SettingsRow(
                "清除所有数据",
                "清空书架、进度、历史、统计与全部设置；已下载的漫画会保留，仍可离线阅读。",
            ) {
                var confirmWipe by remember { mutableStateOf(false) }
                SettingsButton(danger = true, onClick = { confirmWipe = true }) {
                    MixText("清除所有数据", color = Tokens.Color.Danger, fontSize = 12.5.sp)
                }
                if (confirmWipe) {
                    SettingsConfirm(
                        title = "清除所有数据？",
                        message = "书架、阅读进度、已读标记、浏览历史、阅读统计和所有设置项都会被清空，无法恢复。\n" +
                            "已下载的漫画与章节不会删除，之后仍能在下载页阅读。\n" +
                            "只清理本软件自己的数据，不会碰电脑上的其它文件。建议先做一次本地备份。",
                        confirmLabel = "全部清除",
                        onConfirm = {
                            val result = repository.clearAllUserData()
                            AppGraph.browseHistory.clear()
                            val purged = CustomCoverStore.purgeFiles()
                            confirmWipe = false
                            say(
                                buildString {
                                    append("已恢复到初始状态")
                                    if (result.keptForDownloads > 0) {
                                        append("（保留 ${result.keptForDownloads} 部已下载漫画）")
                                    }
                                    if (purged > 0) append("，清理 $purged 个封面文件")
                                },
                                false,
                            )
                        },
                        onDismiss = { confirmWipe = false },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 书架刷新（F-271，2026-10-29）
// ---------------------------------------------------------------------------

/**
 * 「书架刷新」设置页。
 *
 * 用户 2026-10-29 口径：「设置页增加刷新书架详情页章节的选项：
 * 手动刷新 / 每次开启应用刷新 / 每七天刷新 / 每一天刷新」，默认手动。
 *
 * 交互约定与其它分组一致：**选哪一档立即生效**（不需要再点保存），
 * 因为这是个「策略开关」，多一步保存只会让人以为没点上。
 */
@Composable
private fun ChapterRefreshSettings(repository: LibraryRepository, say: (String, Boolean) -> Unit) {
    var mode by remember { mutableStateOf(app.gamanga.desktop.data.ChapterRefresh.mode(repository)) }
    var refreshing by remember { mutableStateOf(false) }
    var lastRun by remember { mutableStateOf(app.gamanga.desktop.data.ChapterRefresh.lastRun(repository)) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val pending by app.gamanga.desktop.data.ChapterRefresh.pending.collectAsState()

    SettingsPageHeader("书架刷新", "检查书架里每本漫画的章节数，有新章节就在封面右上角标一个绿点。")

    SettingsGroup("刷新时机") {
        SettingsCard {
            app.gamanga.desktop.data.ChapterRefresh.MODE_LABELS.forEachIndexed { index, (value, label) ->
                if (index > 0) SettingsDivider()
                // 整行可点：选策略不需要再按保存（与「协议」那种分段控件同一套即时生效口径）
                Box(
                    Modifier.fillMaxWidth().primaryPress {
                        mode = value
                        app.gamanga.desktop.data.ChapterRefresh.setMode(repository, value)
                        say("刷新时机：$label", false)
                    }
                ) {
                    SettingsRow(
                        label,
                        when (value) {
                            app.gamanga.desktop.data.ChapterRefresh.MODE_MANUAL ->
                                "只在点书架「全部漫画」旁的刷新图标时检查。"
                            app.gamanga.desktop.data.ChapterRefresh.MODE_LAUNCH ->
                                "每次启动后台检查一遍，最及时但最费流量。"
                            app.gamanga.desktop.data.ChapterRefresh.MODE_DAILY ->
                                "距上次成功刷新满 24 小时才检查一次。"
                            else ->
                                "距上次成功刷新满 7 天才检查一次。"
                        },
                    ) {
                        if (mode == value) {
                            Icon(
                                imageVector = DockIcons.Check,
                                contentDescription = "当前选择",
                                tint = Tokens.Color.Accent,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Box(Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }

    SettingsGroup("立即刷新") {
        SettingsCard {
            SettingsRow(
                "刷新整个书架",
                result ?: if (lastRun > 0) "上次刷新：${formatTimestamp(lastRun)}" else "还没刷新过。",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsButton(
                        enabled = !refreshing,
                        onClick = {
                            refreshing = true
                            result = null
                            scope.launch {
                                try {
                                    val found = app.gamanga.desktop.data.ChapterRefresh.run(repository, "设置页手动刷新")
                                    lastRun = app.gamanga.desktop.data.ChapterRefresh.lastRun(repository)
                                    result = found.message
                                } finally {
                                    refreshing = false
                                }
                            }
                        },
                    ) { MixText("立即刷新", fontSize = 12.5.sp) }
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.8.dp, color = Tokens.Color.Accent)
                    }
                }
            }
            if (pending.isNotEmpty()) {
                SettingsDivider()
                SettingsRow("待看的新章节", "进入对应漫画的详情页后绿点会消失。") {
                    MixText(
                        "${pending.values.sum()} 话",
                        color = Tokens.Color.TextSecondary,
                        fontSize = 12.5.sp,
                    )
                }
            }
        }
    }
}

/** 时间戳 → 「10-29 17:20」（设置页只给人看个大概，不做相对时间）。 */
private fun formatTimestamp(ms: Long): String = runCatching {
    java.time.Instant.ofEpochMilli(ms)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrDefault("-")

// ---------------------------------------------------------------------------
// 网络代理
// ---------------------------------------------------------------------------

/** 代理设置页的「草稿快照」：普通 Map，避免 SnapshotStateMap 的引用比较陷阱。 */
internal data class ProxySnapshot(
    val on: Boolean,
    val host: String,
    val port: String,
    val protocol: String,
    val perSource: Map<String, Boolean>,
)

/**
 * 草稿与上次保存是否不同。
 *
 * 逐项比较 perSource 而不是 `map != map`：调用方传进来的可能是 `SnapshotStateMap`
 * （Compose 的 `mutableStateMapOf`），它**没有重写 equals**，直接比会退化成引用比较、
 * 永远返回「脏」——这正是 2026-10-29 用户看到「保存了还显示有未保存更改」的原因。
 */
internal fun isProxyDirty(current: ProxySnapshot, saved: ProxySnapshot): Boolean {
    if (current.on != saved.on || current.host != saved.host ||
        current.port != saved.port || current.protocol != saved.protocol
    ) return true
    val keys = current.perSource.keys + saved.perSource.keys
    return keys.any { key -> (current.perSource[key] ?: true) != (saved.perSource[key] ?: true) }
}

@Composable
private fun ProxySettings(repository: LibraryRepository, say: (String, Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var proxyOn by remember { mutableStateOf(repository.getPref(LibraryRepository.PREF_PROXY_ENABLED) == "1") }
    var proxyHost by remember { mutableStateOf(repository.getPref(LibraryRepository.PREF_PROXY_HOST) ?: "") }
    var proxyPort by remember { mutableStateOf(repository.getPref(LibraryRepository.PREF_PROXY_PORT) ?: "") }
    var proxyProtocol by remember { mutableStateOf(repository.getPref(LibraryRepository.PREF_PROXY_PROTOCOL) ?: "HTTP") }
    var proxyTesting by remember { mutableStateOf(false) }
    val sourceUseProxy = remember {
        mutableStateMapOf<String, Boolean>().apply {
            Sources.all().forEach { adapter ->
                this[adapter.type.name] =
                    repository.getPref(LibraryRepository.proxySourcePrefKey(adapter.type)) != "0"
            }
        }
    }
    // 2026-10-29 修「保存后仍显示有未保存更改」：原来是拿 SnapshotStateMap 直接和 HashMap 比
    // `!=`，而 SnapshotStateMap 没有重写 equals（javap 实测），退化成**引用比较**永远为真。
    // 现在把「当前草稿」与「上次保存」都收进普通 Map 快照，用 [isProxyDirty] 逐项比较。
    val currentSnapshot = ProxySnapshot(
        on = proxyOn,
        host = proxyHost.trim(),
        port = proxyPort.trim(),
        protocol = proxyProtocol,
        perSource = sourceUseProxy.toMap(),
    )
    var savedSnapshot by remember { mutableStateOf(currentSnapshot) }
    var effectiveProxy by remember { mutableStateOf(Http.currentProxy()) }
    val editingText = "${proxyProtocol}://${proxyHost.trim()}:${proxyPort.trim()}"
    val effectiveText = if (effectiveProxy.enabled)
        "${effectiveProxy.protocol}://${effectiveProxy.host}:${effectiveProxy.port}"
    else "未启用（全部直连）"
    val editingDiffers = proxyOn != effectiveProxy.enabled || (proxyOn && editingText != effectiveText)
    val proxyDirty = isProxyDirty(currentSnapshot, savedSnapshot)

    SettingsPageHeader("网络代理", "为源站请求和内置浏览器配置代理，按源可以单独直连。")

    SettingsGroup("代理") {
        SettingsCard {
            SettingsRow(
                "当前生效",
                if (editingDiffers) "编辑中：${if (proxyOn) editingText else "不启用"}，尚未保存" else effectiveText,
            ) {
                if (proxyDirty) {
                    MixText("有未保存更改", color = Tokens.Color.Warning, fontSize = 12.sp)
                } else {
                    Box(Modifier.size(7.dp).background(
                        if (effectiveProxy.enabled) Tokens.Color.Success else Tokens.Color.TextTertiary,
                        CircleShape,
                    ))
                }
            }
            SettingsDivider()
            SettingsRow("启用代理", "关闭后所有源站请求直连。") {
                SettingsSwitch(checked = proxyOn, onCheckedChange = { proxyOn = it })
            }
            SettingsDivider()
            SettingsRow("代理地址", "本机代理软件一般填 127.0.0.1。") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsField(proxyHost, { proxyHost = it }, "127.0.0.1", Modifier.width(150.dp))
                    SettingsField(
                        proxyPort,
                        { proxyPort = it.filter { c -> c.isDigit() }.take(5) },
                        "7890",
                        Modifier.width(84.dp),
                    )
                }
            }
            SettingsDivider()
            SettingsRow("协议", "协议不匹配时会出现「能连代理但全网不通」。") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("HTTP", "SOCKS5").forEach { proto ->
                        SettingsSegmented(
                            label = proto,
                            selected = proxyProtocol == proto,
                            onClick = { proxyProtocol = proto },
                        )
                    }
                }
            }
            SettingsDivider()
            SettingsRow("测试连接", "两个探针验证代理是否可用；协议选错会直接告诉你该选哪个。") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsButton(
                        enabled = !proxyTesting && proxyHost.isNotBlank() && proxyPort.isNotBlank(),
                        onClick = {
                            proxyTesting = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    Http.testProxyConnection(
                                        proxyHost,
                                        proxyPort.trim().toIntOrNull() ?: 0,
                                        proxyProtocol,
                                    )
                                }
                                say(
                                    result?.let { "测试失败：$it" }
                                        ?: "连通正常（谷歌 204 探针通过）—— 仅测试，尚未保存",
                                    result != null,
                                )
                                proxyTesting = false
                            }
                        },
                    ) { MixText("测试", fontSize = 12.5.sp) }
                    if (proxyTesting) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.8.dp, color = Tokens.Color.Accent)
                }
            }
        }
    }

    SettingsGroup("按源生效") {
        SettingsCard {
            Sources.all().forEachIndexed { index, adapter ->
                if (index > 0) SettingsDivider()
                val key = adapter.type.name
                SettingsRow(
                    sourceNameOf(adapter.type),
                    if (sourceUseProxy[key] ?: true) "跟随全局代理" else "直连，不走代理",
                ) {
                    SettingsSwitch(
                        checked = sourceUseProxy[key] ?: true,
                        onCheckedChange = { sourceUseProxy[key] = it },
                    )
                }
            }
        }
    }

    SettingsGroup("保存") {
        SettingsCard {
            SettingsRow(
                "保存代理配置",
                "保存后新请求立即生效；内置浏览器与取图探针需重启应用。",
            ) {
                SettingsButton(
                    primary = true,
                    enabled = !proxyOn || (proxyHost.isNotBlank() && proxyPort.isNotBlank()),
                    onClick = {
                        repository.setPref(LibraryRepository.PREF_PROXY_ENABLED, if (proxyOn) "1" else "0")
                        repository.setPref(LibraryRepository.PREF_PROXY_HOST, proxyHost.trim())
                        repository.setPref(LibraryRepository.PREF_PROXY_PORT, proxyPort.trim())
                        repository.setPref(LibraryRepository.PREF_PROXY_PROTOCOL, proxyProtocol)
                        Sources.all().forEach { adapter ->
                            repository.setPref(
                                LibraryRepository.proxySourcePrefKey(adapter.type),
                                if (sourceUseProxy[adapter.type.name] ?: true) "1" else "0",
                            )
                        }
                        Http.configureProxy(
                            enabled = proxyOn,
                            host = proxyHost.trim(),
                            port = proxyPort.trim().toIntOrNull() ?: 0,
                            protocol = proxyProtocol,
                        )
                        Http.proxyPolicy = { type -> sourceUseProxy[type.name] ?: true }
                        effectiveProxy = Http.currentProxy()
                        savedSnapshot = ProxySnapshot(
                            on = proxyOn,
                            host = proxyHost.trim(),
                            port = proxyPort.trim(),
                            protocol = proxyProtocol,
                            perSource = sourceUseProxy.toMap(),
                        )
                        say(
                            if (proxyOn) "代理已启用：$editingText（内置浏览器重启应用后生效）"
                            else "代理已关闭，全部源直连",
                            false,
                        )
                    },
                ) { MixText("保存", color = Color.White, fontSize = 12.5.sp) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 应用信息
// ---------------------------------------------------------------------------

@Composable
private fun AboutSettings(
    repository: LibraryRepository,
    downloadManager: DownloadManager,
    onNotice: (String, Boolean) -> Unit,
) {
    val version = AppVersion.VERSION
    val dataDir = remember {
        File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Gamanga")
    }
    val logFile = remember { runCatching { File(AppLog.logPath()) }.getOrNull() }
    var showLicense by remember { mutableStateOf(false) }
    var licenseText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showLicense) {
        if (showLicense && licenseText == null) {
            licenseText = runCatching {
                javaClass.getResourceAsStream("/LICENSE")?.bufferedReader()?.use { it.readText() }
            }.getOrNull() ?: "没有找到许可文件。"
        }
    }
    val javaVersion = System.getProperty("java.version") ?: "未知"
    val osName = System.getProperty("os.name") ?: "未知"
    val osArch = System.getProperty("os.arch") ?: ""
    val buildInfo = "${AppVersion.NAME_ZH} $version (${AppVersion.NAME_EN})\nJava $javaVersion\n$osName $osArch\n数据目录：${dataDir.absolutePath}"

    // ---- 渲染与性能（2026-10-30 批次46）----
    val mainWindow = app.gamanga.desktop.ui.shell.LocalComposeWindow.current
    var hwAccel by remember { mutableStateOf(repository.getPref(RenderBackend.PREF_HW_ACCEL) != "0") }
    var renderBackend by remember { mutableStateOf(RenderBackend.describe(mainWindow)) }
    var hardware by remember { mutableStateOf(RenderBackend.isHardware(mainWindow)) }
    // 进页面那一刻 SkiaLayer 可能还没定下 renderApi（值是 UNKNOWN），
    // 稍等一拍再读一次，避免永远停在"初始化中…"。
    LaunchedEffect(mainWindow) {
        delay(300)
        renderBackend = RenderBackend.describe(mainWindow)
        hardware = RenderBackend.isHardware(mainWindow)
    }

    fun openPath(target: File?) {
        if (target == null) {
            onNotice("路径不可用", true)
            return
        }
        runCatching {
            target.mkdirs()
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(target)
            } else {
                error("当前系统不支持打开目录")
            }
        }.onFailure { onNotice("打开失败：${it.message ?: "未知错误"}", true) }
    }

    SettingsPageHeader("应用信息", "版本、运行环境、数据位置与许可说明。")

    SettingsGroup("关于") {
        SettingsCard {
            AboutIdentity(version)
            SettingsDivider()
            SettingsRow("运行环境", "Java $javaVersion · $osName $osArch") {
                Spacer(Modifier.width(1.dp))
            }
            SettingsDivider()
            SettingsRow("数据目录", dataDir.absolutePath) {
                SettingsButton(onClick = { openPath(dataDir) }) { MixText("打开", fontSize = 12.5.sp) }
            }
            SettingsDivider()
            SettingsRow("运行日志", logFile?.absolutePath ?: "不可用") {
                SettingsButton(onClick = { openPath(logFile?.parentFile) }) { MixText("打开", fontSize = 12.5.sp) }
            }
            SettingsDivider()
            SettingsRow("反馈问题时", "复制版本信息，附在问题描述里。") {
                SettingsButton(
                    onClick = {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                            java.awt.datatransfer.StringSelection(buildInfo), null
                        )
                        onNotice("已复制版本信息", false)
                    }
                ) { MixText("复制", fontSize = 12.5.sp) }
            }
        }
    }

    SettingsGroup("渲染与性能") {
        SettingsCard {
            // 「当前后端」是**实测值**，不是偏好值：skiko 找不到 GPU 时会静默回退到
            // CPU 光栅，界面照样能开，只是每帧都慢。把真实后端摆在设置页里，
            // 「卡」到底是不是渲染造成的就不用猜了（2026-10-30 批次46）。
            SettingsRow("当前后端", renderBackend) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(7.dp).background(
                            when (hardware) {
                                true -> Tokens.Color.Success
                                false -> Tokens.Color.Warning
                                null -> Tokens.Color.TextTertiary
                            },
                            CircleShape,
                        )
                    )
                    Spacer(Modifier.width(7.dp))
                    MixText(
                        when (hardware) {
                            true -> "GPU"
                            false -> "CPU"
                            null -> "识别中"
                        },
                        color = Tokens.Color.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            SettingsDivider()
            SettingsRow(
                "硬件加速",
                "默认开启（Direct3D）。关掉后改用 CPU 光栅：画面一样，但滚动和翻页更吃 CPU。" +
                    "改动需重启程序生效。",
            ) {
                SettingsSwitch(
                    checked = hwAccel,
                    onCheckedChange = { on ->
                        hwAccel = on
                        repository.setPref(RenderBackend.PREF_HW_ACCEL, if (on) "1" else "0")
                        onNotice(
                            if (on) "已开启硬件加速 · 重启程序后生效" else "已关闭硬件加速（软件渲染）· 重启程序后生效",
                            false,
                        )
                    },
                )
            }
        }
    }

    SettingsGroup("许可与声明") {
        SettingsCard {
            SettingsRow("开源许可", "Apache License 2.0 · 允许修改与再分发") {
                SettingsButton(onClick = { showLicense = true }) { MixText("查看", fontSize = 12.5.sp) }
            }
            SettingsDivider()
            SettingsRow(
                "内容声明",
                "本程序不提供、不托管、不分发任何漫画内容，仅作为本地阅读与管理工具。",
            ) { Spacer(Modifier.width(1.dp)) }
            SettingsDivider()
            SettingsRow(
                "备份建议",
                "备份前暂停下载；恢复后检查源站地址与代理配置。",
            ) { Spacer(Modifier.width(1.dp)) }
        }
    }

    SettingsGroup("第三方组件") {
        SettingsCard {
            // 许可说明只列名称 + 许可类型，全文在各自仓库里，不塞进程序
            AboutComponent("Compose Multiplatform", "Apache License 2.0 · 界面框架")
            SettingsDivider()
            AboutComponent("Kotlin / kotlinx.coroutines", "Apache License 2.0 · 语言与协程")
            SettingsDivider()
            AboutComponent("SQLDelight", "Apache License 2.0 · 本地数据库")
            SettingsDivider()
            AboutComponent("JCEF / jcefmaven", "BSD 3-Clause · 内嵌 Chromium")
            SettingsDivider()
            AboutComponent("JNA", "Apache License 2.0 / LGPL 2.1 · 系统调用")
        }
    }

    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White,
            tonalElevation = 0.dp,
            title = { MixText("Apache License 2.0", style = Tokens.Type.Subtitle.toTextStyle().copy(fontWeight = FontWeight.SemiBold)) },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    MixText(licenseText ?: "读取中…", color = Tokens.Color.TextSecondary, fontSize = 11.sp)
                }
            },
            confirmButton = {
                SettingsButton(onClick = { showLicense = false }) { MixText("关闭", fontSize = 12.5.sp) }
            },
        )
    }
}

/**
 * 关于页顶部的身份块：图标 + 中英文名 + 版本胶囊。
 *
 * 图标走 classpath 的 `brand/logo-badge-512.png`（与窗口图标同一份，圆角外真透明），
 * 用 Skia 解码而不是 Compose 的 res 包，跨版本最稳（同 Main.kt 的 `decodeBrand`）。
 */
@Composable
private fun AboutIdentity(version: String) {
    val icon = remember { brandImage("brand/logo-badge-512.png") }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)),
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            MixText(
                AppVersion.NAME_ZH,
                fontSize = 17.sp,
                color = Tokens.Color.TextPrimary,
                style = androidx.compose.material3.LocalTextStyle.current.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(3.dp))
            MixText("Gamanga · 桌面漫画阅读器", fontSize = 12.sp, color = Tokens.Color.TextSecondary)
        }
        Box(
            Modifier.clip(RoundedCornerShape(6.dp)).background(Tokens.Color.AccentSoft)
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            MixText(version, color = Tokens.Color.Accent, fontSize = 11.sp)
        }
    }
}

/** 第三方组件行：左名称、右许可类型，不可交互（纯声明）。 */
@Composable
private fun AboutComponent(name: String, license: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MixText(name, fontSize = 13.sp, color = Tokens.Color.TextPrimary, modifier = Modifier.weight(1f))
        MixText(license, fontSize = 11.5.sp, color = Tokens.Color.TextTertiary)
    }
}

/** 从 classpath 读品牌图（与 Main.kt 的 decodeBrand 同一套做法）。 */
private fun brandImage(path: String): ImageBitmap? = runCatching {
    val stream = object {}.javaClass.classLoader?.getResourceAsStream(path) ?: return null
    val bytes = stream.use { it.readBytes() }
    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()

// ---------------------------------------------------------------------------
// 源站地址
// ---------------------------------------------------------------------------

@Composable
private fun SourceConfigBlock(onNotice: (String, Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(SourceConfig.rawSnapshot()) }
    var saved by remember { mutableStateOf(SourceConfig.rawSnapshot()) }
    var expanded by remember { mutableStateOf<Set<SourceType>>(emptySet()) }
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf<SourceType?>(null) }
    var testResult by remember { mutableStateOf<Pair<SourceType, String>?>(null) }
    var saveResult by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    fun endpointOf(type: SourceType): SourceEndpoint = editing[type] ?: SourceEndpoint()
    fun update(type: SourceType, f: (SourceEndpoint) -> SourceEndpoint) {
        editing = editing + (type to f(endpointOf(type)))
        saveResult = null
    }

    val dirty = editing.filterValues { !it.isEmpty } != saved.filterValues { !it.isEmpty }

    SettingsPageHeader("源站更换", "站点换域名时在这里修改，保存后立即生效。平时只填主站地址就够了。")

    SettingsGroup("源站地址") {
        SettingsCard {
            Sources.all().forEachIndexed { index, adapter ->
                val type = adapter.type
                val def = SourceConfig.DEFAULTS[type] ?: SourceEndpoint()
                val e = endpointOf(type)
                val more = type in expanded
                if (index > 0) SettingsDivider()
                SettingsStackRow(
                    sourceNameOf(type),
                    "生效地址：${SourceConfig.baseUrl(type)}",
                ) {
                    SettingsField(
                        e.baseUrl,
                        { v -> update(type) { it.copy(baseUrl = v) } },
                        def.baseUrl,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsButton(
                            enabled = testing != type,
                            onClick = {
                                testing = type
                                testResult = null
                                scope.launch {
                                    val base = SourceConfig.previewBase(type, endpointOf(type).baseUrl)
                                    val ua = SourceConfig.previewUserAgent(type, endpointOf(type).userAgent)
                                    val headers = if (ua != null) mapOf("User-Agent" to ua) else emptyMap()
                                    val msg = try {
                                        val code = withContext(Dispatchers.IO) { Http.statusOf(base, headers, type) }
                                        when (code) {
                                            in 200..399 -> "连通 · HTTP $code · $base"
                                            in 400..499 -> "地址可达，HTTP $code 拒绝了请求 · $base"
                                            else -> "站点异常 · HTTP $code · $base"
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        "连不上 · ${e.message ?: e::class.simpleName ?: "未知错误"} · $base"
                                    }
                                    testResult = type to "$msg · 仅测试，未保存"
                                    testing = null
                                }
                            },
                        ) { MixText("测试连接", fontSize = 12.5.sp) }
                        SettingsTextButton(
                            enabled = !e.isEmpty,
                            onClick = { update(type) { SourceEndpoint() }; testResult = null },
                        ) { MixText("清空", color = Tokens.Color.TextSecondary, fontSize = 12.5.sp) }
                        SettingsTextButton(
                            onClick = { expanded = if (more) expanded - type else expanded + type },
                        ) { MixText(if (more) "收起" else "更多", color = Tokens.Color.TextSecondary, fontSize = 12.5.sp) }
                        if (testing == type) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.8.dp, color = Tokens.Color.Accent)
                    }
                    if (more) {
                        Spacer(Modifier.height(10.dp))
                        SettingsField(
                            e.backupBaseUrls.joinToString(", "),
                            { v ->
                                update(type) {
                                    it.copy(backupBaseUrls = v.split(',').map(String::trim).filter(String::isNotBlank))
                                }
                            },
                            "备用地址，多个用英文逗号分隔（留空 = 无）",
                        )
                        Spacer(Modifier.height(8.dp))
                        SettingsField(
                            e.apiBaseUrls.joinToString(", "),
                            { v ->
                                update(type) {
                                    it.copy(apiBaseUrls = v.split(',').map(String::trim).filter(String::isNotBlank))
                                }
                            },
                            def.apiBaseUrls.joinToString(", ").ifBlank { "接口域名（留空 = 与主站同域）" },
                        )
                        Spacer(Modifier.height(8.dp))
                        SettingsField(
                            e.userAgent,
                            { v -> update(type) { it.copy(userAgent = v) } },
                            "浏览器标识 UA（留空 = 内置桌面 Chrome）",
                        )
                    }
                    testResult?.takeIf { it.first == type }?.let { (_, msg) ->
                        Spacer(Modifier.height(8.dp))
                        MixText(
                            msg,
                            color = if (msg.startsWith("连通")) Tokens.Color.Success else Tokens.Color.Danger,
                            fontSize = 11.5.sp,
                        )
                    }
                }
            }
        }
    }

    SettingsGroup("保存") {
        SettingsCard {
            SettingsRow(
                "保存配置",
                "保存后立即重建源并重新自检。配置文件：${SourceConfig.defaultFile().absolutePath}",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsButton(
                        primary = true,
                        enabled = !saving && dirty,
                        onClick = {
                            saving = true
                            scope.launch {
                                saveResult = try {
                                    val normalized = withContext(Dispatchers.IO) { SourceConfig.save(editing) }
                                    saved = normalized
                                    editing = normalized
                                    SourceHealth.checkAllInBackground()
                                    "已保存并生效 · 正在重新自检" to false
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    "保存失败 · ${e.message ?: "未知错误"}" to true
                                }
                                saving = false
                            }
                        },
                    ) { MixText("保存", color = Color.White, fontSize = 12.5.sp) }
                    if (saving) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.8.dp, color = Tokens.Color.Accent)
                    if (dirty && !saving) MixText("有未保存的更改", color = Tokens.Color.Warning, fontSize = 11.5.sp)
                }
            }
        }
    }
    LaunchedEffect(saveResult) { saveResult?.let { (msg, err) -> onNotice(msg, err) } }
}

// ---------------------------------------------------------------------------
// 设置页共用件
// ---------------------------------------------------------------------------

private val SettingsCardShape = RoundedCornerShape(16.dp)

/**
 * 设置页右侧内容的统一外框：**居中 + 固定最大宽度 760dp**。
 *
 * 用户 2026-10-28 提了两轮，方向是连着的：
 * 1. 「拉长界面间距会加大」→ 内容不能吃满可用宽度，否则卡片变宽、行内元素重新分配；
 * 2. 「左边间距好了右边又开始拉长，设置页应该居中才对」→ 光靠左也不行，要两侧对称。
 * 结论：外层 `CenterHorizontally`，内层 `widthIn(max = 760.dp).fillMaxWidth()`。
 * 窗口变宽只会在两侧对称多出留白，卡片宽度和行间距都不变。
 *
 * 抽成独立 composable 是为了让 `SettingsUiTest` 能直接量左右边距，防止被改回靠左/吃满。
 */
@Composable
internal fun SettingsContentFrame(
    scroll: ScrollState,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll)
            .padding(horizontal = 24.dp)
            .padding(top = 28.dp, bottom = 110.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingsPageHeader(title: String, subtitle: String) {
    Column(Modifier.padding(start = 2.dp, bottom = 2.dp)) {
        MixText(
            title,
            style = Tokens.Type.Title.toTextStyle().copy(fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(6.dp))
        MixText(subtitle, color = Tokens.Color.TextSecondary, fontSize = 12.5.sp)
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        MixText(
            title,
            color = Tokens.Color.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            // 极淡投影代替描边：大厂设置页的卡片靠「浮起 1px」分层，不靠灰边
            .shadow(
                3.dp, SettingsCardShape, clip = false,
                ambientColor = Color(0x08000000), spotColor = Color(0x0F000000),
            )
            .clip(SettingsCardShape)
            .background(Tokens.Color.BgElevatedSolid)
            .padding(vertical = 2.dp),
        content = content,
    )
}

@Composable
private fun SettingsDivider() {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(1.dp)
            .background(Tokens.Color.BorderHairline)
    )
}

/** 左标题右控件的一行设置。 */
@Composable
internal fun SettingsRow(
    title: String,
    desc: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MixText(title, color = Tokens.Color.TextPrimary, fontSize = 13.sp)
            if (desc != null) {
                Spacer(Modifier.height(3.dp))
                MixText(desc, color = Tokens.Color.TextSecondary, fontSize = 11.5.sp)
            }
        }
        Spacer(Modifier.width(18.dp))
        trailing()
    }
}

/** 标题在上、控件占满整行的一行设置（输入框这类宽控件用它）。 */
@Composable
private fun SettingsStackRow(
    title: String,
    desc: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp)) {
        MixText(title, color = Tokens.Color.TextPrimary, fontSize = 13.sp)
        if (desc != null) {
            Spacer(Modifier.height(3.dp))
            MixText(desc, color = Tokens.Color.TextSecondary, fontSize = 11.5.sp)
        }
        Spacer(Modifier.height(9.dp))
        content()
    }
}

/**
 * 统一输入框：浅灰填充、无描边、聚焦时蓝边。
 *
 * 不用 Material 的 OutlinedTextField——它在白卡里会再套一层灰边方框，
 * 正是用户说的「方框设计不好看、风格不统一」。
 */
@Composable
internal fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(9.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(fontSize = 12.5.sp, color = Tokens.Color.TextPrimary, fontFamily = AppFont.Ui),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        cursorBrush = SolidColor(Tokens.Color.Accent),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().height(34.dp)
            .clip(shape)
            .background(Tokens.Color.BgBase)
            .border(
                1.dp,
                if (focused) Tokens.Color.Accent.copy(alpha = .5f) else Tokens.Color.BorderHairline,
                shape,
            )
            .padding(horizontal = 10.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    MixText(placeholder, color = Tokens.Color.TextTertiary, fontSize = 12.sp, maxLines = 1)
                }
                inner()
            }
        },
    )
}

/**
 * 统一按钮：白底 + 极淡描边 + hover 投影 + 按下 3% 缩放。
 *
 * 用户 2026-10-28：「按钮触碰阴影也要改」——旧 Material 按钮是静态灰边，
 * 触碰时只有底色变化；这里把「触碰」做成真的浮起一层。
 */
@Composable
private fun SettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 13.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(9.dp)
    val background = when {
        !enabled -> Tokens.Color.BgSunken
        primary -> Tokens.Color.Accent
        hovered -> Color(0xFFFAFAFC)
        else -> Color.White
    }
    val lift by animateDpAsState(
        targetValue = if (hovered && enabled) 5.dp else 0.dp,
        animationSpec = tween(140),
        label = "settingsButtonLift",
    )
    Row(
        modifier
            .height(32.dp)
            .graphicsLayer {
                scaleX = if (pressed && enabled) .97f else 1f
                scaleY = if (pressed && enabled) .97f else 1f
            }
            .shadow(
                lift, shape, clip = false,
                ambientColor = Color(0x0F000000), spotColor = Color(0x1A000000),
            )
            .clip(shape)
            .background(background)
            .border(
                1.dp,
                when {
                    !enabled -> Tokens.Color.BorderHairline
                    danger -> Tokens.Color.Danger.copy(alpha = .22f)
                    primary -> Color.Transparent
                    else -> Tokens.Color.BorderHairline
                },
                shape,
            )
            .hoverable(interaction)
            .primaryPress(enabled = enabled, onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content,
    )
}

/** 无边框的次级操作（恢复/删除/刷新这类）。 */
@Composable
private fun SettingsTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier.height(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (hovered && enabled) Tokens.Color.BgSunken else Color.Transparent)
            .hoverable(interaction)
            .primaryPress(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content,
    )
}

/** 分段选择（HTTP / SOCKS5）。 */
@Composable
private fun SettingsSegmented(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.height(28.dp)
            .clip(shape)
            .background(if (selected) Color.White else Tokens.Color.BgBase)
            .border(
                1.dp,
                if (selected) Tokens.Color.Accent.copy(alpha = .35f) else Tokens.Color.BorderHairline,
                shape,
            )
            .primaryPress(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        MixText(
            label,
            color = if (selected) Tokens.Color.Accent else Tokens.Color.TextSecondary,
            fontSize = 11.5.sp,
        )
    }
}

@Composable
private fun SettingsSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = Modifier.scale(.84f),
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Tokens.Color.Accent,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = Color(0xFFD6D6DC),
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
private fun SettingsToast(text: String, error: Boolean) {
    Row(
        Modifier
            .shadow(12.dp, RoundedCornerShape(14.dp), clip = false, ambientColor = Color(0x14000000), spotColor = Color(0x1F000000))
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier.size(16.dp).clip(CircleShape)
                .background(if (error) Tokens.Color.DangerSoft else Tokens.Color.Success.copy(alpha = .14f)),
            contentAlignment = Alignment.Center,
        ) {
            MixText(
                if (error) "!" else "✓",
                color = if (error) Tokens.Color.Danger else Tokens.Color.Success,
                fontSize = 10.sp,
            )
        }
        MixText(text, color = Tokens.Color.TextPrimary, fontSize = 12.5.sp, maxLines = 2)
    }
}

@Composable
private fun SettingsConfirm(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = Color.White,
        tonalElevation = 0.dp,
        title = {
            MixText(
                title,
                style = Tokens.Type.Subtitle.toTextStyle().copy(fontWeight = FontWeight.SemiBold),
            )
        },
        text = { MixText(message, color = Tokens.Color.TextSecondary, fontSize = 12.5.sp) },
        confirmButton = {
            SettingsButton(onClick = onConfirm, danger = true) {
                MixText(confirmLabel, color = Tokens.Color.Danger, fontSize = 12.5.sp)
            }
        },
        dismissButton = {
            SettingsTextButton(onClick = onDismiss) {
                MixText("取消", color = Tokens.Color.TextSecondary, fontSize = 12.5.sp)
            }
        },
    )
}

/** 桌面端只认主键：clickable 会把右键也算点击（R-09）。 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.primaryPress(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.onPointerEvent(PointerEventType.Press) { event ->
        if (!enabled) return@onPointerEvent
        if (event.buttons.isPrimaryPressed && event.changes.none { it.isConsumed }) {
            event.changes.forEach { it.consume() }
            onClick()
        }
    }

// ---------------------------------------------------------------------------
// 原生文件框
// ---------------------------------------------------------------------------

private fun showDirectoryDialog(title: String): File? {
    val previousLaf = runCatching { javax.swing.UIManager.getLookAndFeel() }.getOrNull()
    return try {
        runCatching {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName())
        }
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        val result = chooser.showOpenDialog(java.awt.Frame())
        if (result == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    } catch (e: Exception) {
        null
    } finally {
        runCatching {
            if (previousLaf != null) {
                javax.swing.UIManager.setLookAndFeel(previousLaf)
            } else {
                javax.swing.UIManager.setLookAndFeel(
                    javax.swing.UIManager.getCrossPlatformLookAndFeelClassName()
                )
            }
        }
    }
}

private fun showFileDialog(
    mode: Int,
    defaultName: String?,
    accept: (String) -> Boolean
): File? {
    val owner = java.awt.Frame()
    return try {
        val dialog = java.awt.FileDialog(owner, "Gaaa漫画", mode)
        if (defaultName != null) dialog.file = defaultName
        dialog.setFilenameFilter { _, name -> accept(name) }
        dialog.isVisible = true
        val dir = dialog.directory
        val name = dialog.file
        if (dir.isNullOrBlank() || name.isNullOrBlank()) null else File(dir, name)
    } finally {
        owner.dispose()
    }
}
