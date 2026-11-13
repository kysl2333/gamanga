package app.gamanga.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.gamanga.desktop.source.BangumiClient
import app.gamanga.desktop.ui.recommend.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class RecommendUiTest {
    @get:Rule val rule = createComposeRule()
    private fun item(id: Int) = BangumiClient.Item(id, "漫画$id", null, 8.0, id)
    @Test fun accountPanelMasksTokenAndRequiresDisconnectConfirmation() {
        val prefs = java.util.prefs.Preferences.userRoot().node("Gamanga/tests/account-ui-${java.util.UUID.randomUUID()}")
        val job = SupervisorJob().apply { cancel() }
        val state = RecommendState(CoroutineScope(job + Dispatchers.Default), prefs)
        state.username = "fixture-account"
        state.setReducedMotion(true)
        var access by mutableStateOf("")
        try {
            rule.setContent { MaterialTheme { BangumiAccountDialog(state, access, { access = it }, {}, {}) } }
            rule.onNodeWithTag("bangumi-account-panel").assertExists()
            rule.onNodeWithTag("bangumi-access-token").performTextInput("fixture-token")
            rule.onNodeWithText("显示").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            rule.onNodeWithText("隐藏").assertExists()
            rule.onNodeWithText("断开连接").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            rule.onNodeWithText("断开 Bangumi 连接？").assertExists()
            assertEquals("fixture-account", state.username)
            rule.onNodeWithText("取消").performClick()
            assertEquals("fixture-account", state.username)
        } finally { prefs.removeNode() }
    }
    @Test fun dockGapDoesNotClickContentBehindIt() {
        var clicks = 0
        rule.setContent { MaterialTheme {
            Box(Modifier.requiredSize(800.dp, 600.dp).testTag("shell-test")) {
                app.gamanga.desktop.ui.shell.NormalShell(
                    app.gamanga.desktop.ui.shell.NavItem.LIBRARY, {}, {}) {
                    Box(Modifier.fillMaxSize().then(Modifier.clickable { clicks++ }))
                }
            }
        } }
        rule.onNodeWithTag("shell-test").performMouseInput {
            // dock左边缘302dp，第一个44dp按钮后为6dp空隙。
            click(androidx.compose.ui.geometry.Offset(357.dp.toPx(), 548.dp.toPx()))
        }
        rule.runOnIdle { assertEquals(0, clicks) }
    }

    @Test fun thumbnailRoutingOnlyResizesOfficialCovers() {
        assertEquals("https://lain.bgm.tv/r/400/pic/cover/l/a.jpg", RecommendImageRouting.thumbnail("https://lain.bgm.tv/pic/cover/l/a.jpg"))
        assertEquals("https://lain.bgm.tv/r/400/pic/cover/l/a.jpg", RecommendImageRouting.thumbnail("https://lain.bgm.tv/r/800/pic/cover/l/a.jpg"))
        // 判据是 Bangumi 的图片路径结构（镜像站换域名但路径照抄），不是域名白名单。
        assertEquals("https://bgmimg.anibt.net/r/400/pic/cover/l/a.jpg", RecommendImageRouting.thumbnail("https://bgmimg.anibt.net/pic/cover/l/a.jpg"))
        assertEquals("https://example.com/api/cover/l/a.jpg", RecommendImageRouting.thumbnail("https://example.com/api/cover/l/a.jpg"))
        assertEquals("https://lain.bgm.tv/pic/user/l/a.jpg", RecommendImageRouting.thumbnail("https://lain.bgm.tv/pic/user/l/a.jpg"))
    }

    @Test fun navigationAnimationKeepsContentBoundsStable() {
        var selected by mutableStateOf(false)
        rule.setContent { Column {
            NavigationLabel("发现", selected) { selected = !selected }
            Box(Modifier.size(100.dp).testTag("below-navigation"))
        } }
        val before = rule.onNodeWithTag("below-navigation").fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("发现").performClick()
        repeat(6) {
            rule.mainClock.advanceTimeBy(40)
            assertEquals(before, rule.onNodeWithTag("below-navigation").fetchSemanticsNode().boundsInRoot)
        }
        rule.mainClock.autoAdvance = true
    }

    @Test fun detailImageRightClickShowsUnifiedActions() {
        rule.setContent { CompositionLocalProvider(LocalRecommendImageMenu provides true) {
            RecommendImage("http://127.0.0.1:1/test.jpg", "测试图片", Modifier.size(150.dp).testTag("menu-image"))
        } }
        rule.onNodeWithTag("menu-image").performMouseInput { click(button = MouseButton.Secondary) }
        rule.onNodeWithText("复制图片链接").assertExists()
        rule.onNodeWithText("保存图片").assertExists()
    }

    @Test fun downloadHeaderSeparatesCoverToggleAndHoverDelete() {
        var expanded by mutableStateOf(true)
        var opened = 0
        var deleted = 0
        rule.setContent {
            MaterialTheme { Box(Modifier.width(620.dp)) {
                DownloadGroupHeader("测试漫画", null, 3, expanded,
                    onToggle = { expanded = !expanded }, onOpenDetail = { opened++ }, onDelete = { deleted++ })
            } }
        }
        rule.onNodeWithContentDescription("删除整部下载：测试漫画").assertDoesNotExist()
        rule.onNodeWithContentDescription("查看详情：测试漫画").performMouseInput { click() }
        rule.runOnIdle { assertEquals(1, opened); assertTrue(expanded) }
        rule.onNodeWithContentDescription("收起下载：测试漫画").performMouseInput { click() }
        rule.runOnIdle { assertFalse(expanded); assertEquals(1, opened) }
        rule.onNodeWithContentDescription("展开下载：测试漫画").performMouseInput { enter() }
        rule.onNodeWithContentDescription("删除整部下载：测试漫画").performMouseInput { click() }
        rule.runOnIdle { assertEquals(1, deleted); assertFalse(expanded); assertEquals(1, opened) }
    }

    @Test fun wholeDownloadDeletionRequiresExplicitConfirmation() {
        var pending by mutableStateOf(true)
        var deleted = 0
        rule.setContent { MaterialTheme {
            if (pending) ConfirmDownloadDeletion("测试漫画", 3,
                onDismiss = { pending = false }, onConfirm = { deleted++; pending = false })
        } }
        rule.runOnIdle { assertEquals(0, deleted) }
        rule.onNodeWithText("取消").performMouseInput { click() }
        rule.runOnIdle { assertEquals(0, deleted); pending = true }
        rule.onNodeWithText("删除下载").performMouseInput { click() }
        rule.runOnIdle { assertEquals(1, deleted); assertFalse(pending) }
    }

    @Test fun sixRelatedCoversShareOneRow() {
        rule.setContent { Box(Modifier.width(660.dp)) { MiniRow((1..6).map(::item)) {} } }
        val rects = (1..6).map { rule.onNodeWithText("漫画$it").fetchSemanticsNode().boundsInRoot }
        assertTrue(rects.maxOf { it.top } - rects.minOf { it.top } < 1f)
        assertTrue(rects.last().right <= 660f * rule.density.density + 1)
        assertTrue(rects.zipWithNext().all { (a,b) -> a.right <= b.left })
    }
    @Test fun signedOutCollectionIsNotSavedAndSearchKeepsDetail() {
        val job = SupervisorJob()
        val prefs = java.util.prefs.Preferences.userRoot().node("Gamanga/tests/recommend-${java.util.UUID.randomUUID()}")
        val state = RecommendState(CoroutineScope(job + Dispatchers.Main), prefs)
        state.setReducedMotion(true)
        val entry = RecommendState.Detail(item(987654))
        entry.loading = false
        state.stack.add(entry)
        var searched = ""
        rule.setContent { MaterialTheme { Box(Modifier.requiredSize(1100.dp, 900.dp)) { DetailPanel(state, entry) { searched = it } } } }
        val cover = rule.onNodeWithTag("recommend-cover").fetchSemanticsNode().boundsInRoot
        val rating = rule.onNodeWithTag("recommend-rating").fetchSemanticsNode().boundsInRoot
        val intro = rule.onNodeWithText("作品介绍").fetchSemanticsNode().boundsInRoot
        assertTrue(rating.left > cover.right)
        assertTrue(intro.top >= maxOf(cover.bottom, rating.bottom))
        rule.onNodeWithText("已读话数", substring = true).assertDoesNotExist()
        rule.onNodeWithText("已读卷数", substring = true).assertDoesNotExist()
        rule.onAllNodesWithText("搁置", useUnmergedTree = true).onLast().onParent().assertIsNotEnabled()
        rule.onAllNodesWithText("搁置").onLast().performMouseInput { click() }
        rule.runOnIdle { assertTrue(state.records.isEmpty()) }
        rule.onNodeWithContentDescription("在浏览页搜索").performMouseInput { click() }
        rule.runOnIdle { assertEquals("漫画987654", searched); assertSame(entry, state.stack.last()) }
        runBlocking { delay(250) }
        val restored = RecommendState(CoroutineScope(job + Dispatchers.Main), prefs)
        restored.restoreSnapshot()
        assertTrue(restored.records.isEmpty())
        job.cancel()
        prefs.removeNode()
    }
    @Test fun oldSnapshotMigratesToHistoryOnlyAndSupportsDeleteAndClear() = runBlocking {
        val prefs = java.util.prefs.Preferences.userRoot().node("Gamanga/tests/recommend-${java.util.UUID.randomUUID()}")
        val job = SupervisorJob()
        try {
            prefs.putInt("snapshot.count", 1)
            prefs.put("snapshot.0", """{"history":[11,12],"items":[{"id":11,"name_cn":"漫画11"},{"id":12,"name_cn":"漫画12"}],"records":{"11":{"type":4,"rate":8,"episodes":12}}}""")
            val scope = CoroutineScope(job + Dispatchers.Main)
            val state = RecommendState(scope, prefs)
            state.restoreSnapshot()
            assertEquals(listOf(11,12), state.history.toList())
            assertTrue(state.records.isEmpty())
            assertTrue(state.accountItems.isEmpty())
            state.removeHistory(11)
            assertEquals(listOf(12), state.history.toList())
            assertFalse(state.known.containsKey(11))
            withTimeout(5000) { while (prefs.get("snapshot.0", "").contains("records") || prefs.get("snapshot.0", "").contains("漫画11")) delay(25) }
            val restored = RecommendState(scope, prefs)
            restored.restoreSnapshot()
            assertEquals(listOf(12), restored.history.toList())
            restored.clearHistory()
            withTimeout(5000) { while (!prefs.get("snapshot.0", "").contains("\"history\":[]")) delay(25) }
            val cleared = RecommendState(scope, prefs)
            cleared.restoreSnapshot()
            assertTrue(cleared.history.isEmpty())
            assertTrue(cleared.known.isEmpty())
        } finally {
            job.cancelAndJoin()
            prefs.removeNode()
        }
    }
    @Test fun thumbnailDecodeLimitsActualBitmapAndPreservesPortrait() {
        val original = java.awt.image.BufferedImage(1800, 2700, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val data = java.io.ByteArrayOutputStream().use { out -> javax.imageio.ImageIO.write(original, "png", out); out.toByteArray() }
        original.flush()
        val image = RecommendImageCache.decode(data)
        assertEquals(256, image.width)
        assertEquals(384, image.height)
        assertTrue(image.width.toLong() * image.height * 4 < 400_000)
        assertEquals(100 to 150, RecommendImageCache.thumbnailSize(100, 150))
    }
    @Test fun historyDeleteAppearsOnHoverAndDoesNotOpenCard() {
        var deleted = false
        var opened = false
        rule.setContent { Box(Modifier.width(180.dp)) { CoverCard(item(7), onDelete = { deleted = true }) { opened = true } } }
        rule.onNodeWithContentDescription("删除最近浏览：漫画7").assertDoesNotExist()
        rule.onNodeWithText("漫画7").performMouseInput { enter() }
        rule.onNodeWithContentDescription("删除最近浏览：漫画7").assertExists().performMouseInput { click() }
        rule.runOnIdle { assertTrue(deleted); assertFalse(opened) }
    }
    @Test fun ratingChartIsCompactAndRightAligned() {
        rule.setContent { MaterialTheme { Box(Modifier.width(900.dp)) { RatingBlock(item(1), Modifier.fillMaxWidth().testTag("rating")) } } }
        val block = rule.onNodeWithTag("rating").fetchSemanticsNode().boundsInRoot
        val chart = rule.onNodeWithTag("recommend-vote-chart").fetchSemanticsNode().boundsInRoot
        assertTrue(chart.width <= block.width * .45f)
        assertTrue(chart.width >= 300f * rule.density.density)
        assertTrue(kotlin.math.abs(chart.right - block.right) < 2f)
    }
    @Test fun randomEntryClearsSearchAndCategory() {
        val prefs = java.util.prefs.Preferences.userRoot().node("Gamanga/tests/recommend-${java.util.UUID.randomUUID()}")
        val job = SupervisorJob().apply { cancel() }
        try {
            val state = RecommendState(CoroutineScope(job + Dispatchers.Default), prefs)
            state.keyword = "搜索漫画"; state.category = "恋爱"
            state.setRandomMode(true)
            assertEquals("", state.keyword); assertEquals("全部", state.category); assertTrue(state.random)
            state.keyword = "另一次搜索"; state.category = "科幻"
            state.nextRandomBatch()
            assertEquals("", state.keyword); assertEquals("全部", state.category)
        } finally { prefs.removeNode() }
    }
    @Test fun syncCompletionDoesNotReplayAfterLeavingPage() {
        val prefs = java.util.prefs.Preferences.userRoot().node("Gamanga/tests/recommend-${java.util.UUID.randomUUID()}")
        val job = SupervisorJob().apply { cancel() }
        val state = RecommendState(CoroutineScope(job + Dispatchers.Default), prefs)
        var mounted by mutableStateOf(true)
        try {
            state.setReducedMotion(true)
            state.notifySynced("同步完成测试")
            rule.setContent { if (mounted) SyncBubble(state) }
            rule.onNodeWithTag("recommend-sync-bubble").assertExists()
            rule.runOnIdle { mounted = false }
            rule.waitForIdle()
            rule.runOnIdle { assertNull(state.syncNotice); mounted = true }
            rule.onNodeWithTag("recommend-sync-bubble").assertDoesNotExist()
            rule.runOnIdle {
                state.notifySynced("旧事件")
                val old = state.syncNoticeVersion
                state.notifySynced("新事件")
                state.consumeSyncNotice(old)
                assertEquals("新事件", state.syncNotice)
            }
        } finally { prefs.removeNode() }
    }
    @Test fun navigationClickPreservesOrderAndChangesSelection() {
        var selected by mutableStateOf("发现")
        rule.setContent { Row { listOf("发现", "我的漫画", "最近浏览").forEach { label -> NavigationLabel(label, selected == label) { selected = label } } } }
        rule.onNodeWithText("我的漫画").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("我的漫画").assertIsSelected()
        val rects = listOf("发现", "我的漫画", "最近浏览").map { rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        assertTrue(rects.zipWithNext().all { (a, b) -> a.right <= b.left })
    }

    @Test fun randomPreferenceRestoresWithoutSavingResults() {
        val prefs = java.util.prefs.Preferences.userRoot().node("Gamanga/tests/recommend-${java.util.UUID.randomUUID()}")
        val job = SupervisorJob().apply { cancel() }
        try {
            val scope = CoroutineScope(job + Dispatchers.Default)
            RecommendState(scope, prefs).setRandomMode(true)
            val restored = RecommendState(scope, prefs)
            assertTrue(restored.random)
            assertTrue(restored.items.isEmpty())
        } finally { prefs.removeNode() }
    }
    @Test fun accountSnapshotRestoresStatusWithoutReadingProgress() = runBlocking {
        val prefs = java.util.prefs.Preferences.userRoot().node("Gamanga/tests/recommend-${java.util.UUID.randomUUID()}")
        val job = SupervisorJob()
        try {
            val state = RecommendState(CoroutineScope(job + Dispatchers.Default), prefs)
            state.username = "cache-test"; state.avatar = "https://example.test/avatar.png"
            state.accountItems[7] = item(7); state.records[7] = RecommendState.Record(4, 8)
            state.persistAccountSnapshot()
            withTimeout(5000) { while (prefs.getInt("account.count", 0) == 0) delay(20) }
            val restored = RecommendState(CoroutineScope(job + Dispatchers.Default), prefs)
            restored.restoreAccountSnapshot()
            assertEquals("cache-test", restored.username)
            assertEquals(RecommendState.Record(4, 8), restored.records[7])
            assertEquals("漫画7", restored.accountItems[7]?.title)
            assertFalse(prefs.get("account.0", "").contains("episodes"))
            assertNull(restored.similarTag(listOf("漫画", "日本", "2026")))
            assertEquals("日常", restored.similarTag(listOf("漫画", "漫画系列", "日常")))
        } finally { job.cancelAndJoin(); prefs.removeNode() }
    }
    @Test fun mirrorSwitchesApiAndImageHostsInBothDirections() {
        try {
            app.gamanga.desktop.source.BangumiMirrors.apply(app.gamanga.desktop.source.BangumiMirror("test", "测试线路", "https://api.mirror.test", "https://img.mirror.test"))
            assertEquals("https://api.mirror.test", BangumiClient.apiBase)
            assertEquals("https://img.mirror.test/pic/cover/l/abc.jpg", RecommendImageRouting.resolve("https://lain.bgm.tv/pic/cover/l/abc.jpg"))
            // 接口地址不是图片，绝不能被改写。
            assertEquals("https://api.bgm.tv/v0/me", RecommendImageRouting.resolve("https://api.bgm.tv/v0/me"))
            // 切回官方后，镜像域名要能纠回 lain.bgm.tv（账号快照里存的是旧域名）。
            app.gamanga.desktop.source.BangumiMirrors.apply(app.gamanga.desktop.source.BangumiMirrors.Official)
            assertEquals("https://lain.bgm.tv/pic/cover/l/abc.jpg", RecommendImageRouting.resolve("https://img.mirror.test/pic/cover/l/abc.jpg"))
            // 只换域名，/r/{宽}/ 前缀原样保留（头像不缩略，所以这里带着 r/100）。
            assertEquals("https://lain.bgm.tv/r/100/pic/user/l/avatar.jpg", RecommendImageRouting.resolve("https://bgmimg.anibt.net/r/100/pic/user/l/avatar.jpg"))
        } finally { app.gamanga.desktop.source.BangumiMirrors.apply(app.gamanga.desktop.source.BangumiMirrors.Official) }
    }

    @Test fun customMirrorRequiresTwoHttpsAddresses() {
        assertNull(app.gamanga.desktop.source.BangumiMirrors.custom("http://api.x.test", "https://img.x.test"))
        assertNull(app.gamanga.desktop.source.BangumiMirrors.custom("https://api.x.test", ""))
        assertNull(app.gamanga.desktop.source.BangumiMirrors.custom("https://user:pw@api.x.test", "https://img.x.test"))
        val ok = app.gamanga.desktop.source.BangumiMirrors.custom(" https://api.x.test/ ", "https://img.x.test/")
        assertNotNull(ok)
        assertEquals("https://api.x.test", ok.api)
        assertEquals("https://img.x.test", ok.image)
        assertEquals("custom", ok.id)
    }

    @Test fun mirrorChoiceIsPersistedAndRestored() {
        val prefs = java.util.prefs.Preferences.userRoot().node("Gamanga/tests/mirror-${java.util.UUID.randomUUID()}")
        try {
            val anibt = app.gamanga.desktop.source.BangumiMirrors.catalog.first { it.id == "anibt" }
            app.gamanga.desktop.source.BangumiMirrors.applyAndSave(anibt, prefs)
            app.gamanga.desktop.source.BangumiMirrors.apply(app.gamanga.desktop.source.BangumiMirrors.Official)
            app.gamanga.desktop.source.BangumiMirrors.restore(prefs)
            assertEquals("anibt", app.gamanga.desktop.source.BangumiMirrors.current.id)
            assertEquals("https://bgmapi.anibt.net", BangumiClient.apiBase)
            // 坏配置不能把线路弄成空地址。
            prefs.put("mirror.id", "custom"); prefs.put("mirror.api", "not-a-url"); prefs.put("mirror.image", "")
            app.gamanga.desktop.source.BangumiMirrors.restore(prefs)
            assertEquals("official", app.gamanga.desktop.source.BangumiMirrors.current.id)
        } finally {
            app.gamanga.desktop.source.BangumiMirrors.apply(app.gamanga.desktop.source.BangumiMirrors.Official)
            prefs.removeNode()
        }
    }
    @Test fun downloadTaskActionsStaySeparateFromDetailAndShowProgress() {
        var read = false; var deleted = false
        val task = app.gamanga.desktop.data.DownloadTask("test", "komiic:7", "8", "第八话", "测试漫画", 20, 20, app.gamanga.desktop.data.DownloadStatus.COMPLETED)
        rule.setContent { MaterialTheme { Box(Modifier.width(800.dp)) { DownloadTaskCard(task, false, { read = true }, {}, { deleted = true }) } } }
        rule.onNodeWithText("第八话").assertExists()
        rule.onNodeWithText("阅读").performClick()
        rule.runOnIdle { assertTrue(read); assertFalse(deleted) }
        rule.onNodeWithText("删除").performClick()
        rule.runOnIdle { assertTrue(deleted) }
    }
    @Test fun windowsCredentialIsEncryptedRestoredAndCleared() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val prefs = java.util.prefs.Preferences.userRoot().node("Gamanga/tests/recommend-${java.util.UUID.randomUUID()}")
        try {
            val value = "test-only-${java.util.UUID.randomUUID()}"
            BangumiCredential.save(value, prefs)
            assertNotEquals(value, prefs.get("credential.dpapi", ""))
            assertEquals(value, BangumiCredential.read(prefs))
            BangumiCredential.clear(prefs)
            assertNull(BangumiCredential.read(prefs))
        } finally { prefs.removeNode() }
    }
}
