package app.gamanga.desktop.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 镜像目录（bangumi.wiki/api/v1/mirrors）解析与清单持久化。
 *
 * 2026-10-28 用户指出：bangumi.wiki 不是镜像站，而是「哪些镜像现在能用」的目录站，
 * 线路清单必须从这个公开 JSON 接口拿，不能写死在程序里。这里锁住解析规则：
 * `services` 里的 `api` / `lain` 主机名成对取出，缺了就按 `api.<base>` / `lain.<base>` 兜底。
 */
class MirrorDirectoryTest {

    private val sample = """
        {"mirrors":[{"id":"bangumi-lat","name":"bangumi.lat","url":"https://bangumi.lat",
        "base":"bangumi.lat","weight":1000,"note":"本站维护的镜像，不是官方域名。","recommended":true,
        "services":[
          {"id":"main","name":"主站","label":null,"hosts":["bangumi.lat","www.bangumi.lat"]},
          {"id":"api","name":"api","label":null,"hosts":["api.bangumi.lat"]},
          {"id":"lain","name":"lain","label":"图床","hosts":["lain.bangumi.lat"]}
        ]}],
        "meta":{"total":1,"updatedAt":"2026-10-23T16:25:34.311Z"}}
    """.trimIndent()

    @Test fun directoryYieldsPairedApiAndImageHosts() {
        val list = BangumiMirrors.parseDirectory(sample)
        assertEquals(1, list.size)
        val mirror = list.first()
        assertEquals("dir:bangumi-lat", mirror.id)
        assertEquals("bangumi.lat", mirror.name)
        assertEquals("https://api.bangumi.lat", mirror.api)
        assertEquals("https://lain.bangumi.lat", mirror.image)
        assertEquals("本站维护的镜像，不是官方域名。", mirror.note)
    }

    @Test fun directoryFallsBackToBaseWhenServicesMissing() {
        val list = BangumiMirrors.parseDirectory("""{"mirrors":[{"id":"x","name":"x","base":"example.test"}]}""")
        assertEquals("https://api.example.test", list.first().api)
        assertEquals("https://lain.example.test", list.first().image)
    }

    @Test fun directorySkipsBrokenEntriesInsteadOfLosingWholeList() {
        val body = """{"mirrors":[
            {"id":"bad","base":"http://insecure.test"},
            {"id":"good","name":"good","base":"good.test","services":[{"id":"api","hosts":["api.good.test"]}]}
        ]}"""
        val list = BangumiMirrors.parseDirectory(body)
        assertEquals(listOf("good"), list.map { it.name })
        assertEquals("https://lain.good.test", list.first().image)
    }

    @Test fun catalogKeepsOfficialFirstAndDropsDuplicates() {
        val prefs = java.util.prefs.Preferences.userRoot().node("Gamanga/tests/mirrors-${UUID.randomUUID()}")
        try {
            val remote = BangumiMirrors.parseDirectory(sample)
            // 目录里再放一条与内置 ANIBT 同主机的线路，合并后不能出现两次。
            val duplicate = BangumiMirror("dir:dup", "重复 ANIBT", "https://bgmapi.anibt.net", "https://bgmimg.anibt.net")
            BangumiMirrors.saveCatalog(prefs, remote + duplicate)
            val loaded = BangumiMirrors.loadCatalog(prefs)
            assertEquals(BangumiMirrors.OFFICIAL_ID, loaded.first().id)
            assertTrue(loaded.any { it.id == "dir:bangumi-lat" })
            assertEquals(1, loaded.count { BangumiMirrors.hostOf(it.api) == "bgmapi.anibt.net" })
            assertTrue(loaded.size >= 3, "官方 + 目录线路 + 内置兜底：$loaded")
        } finally { prefs.removeNode() }
    }

    @Test fun stateStartsOnOfficialAndHidesMirrorEntryUntilNeeded() {
        val prefs = java.util.prefs.Preferences.userRoot().node("Gamanga/tests/mirrors-${UUID.randomUUID()}")
        val job = SupervisorJob()
        try {
            val state = app.gamanga.desktop.ui.recommend.RecommendState(CoroutineScope(job + Dispatchers.Default), prefs)
            // 默认必须是官方线路，且「官方不通」为假 —— 界面据此隐藏换源入口。
            assertEquals(BangumiMirrors.OFFICIAL_ID, state.mirror.id)
            assertFalse(state.officialUnreachable)
            assertEquals(null, state.suggestedMirror)
            assertTrue(state.mirrors.isNotEmpty())
        } finally { kotlinx.coroutines.runBlocking { job.cancelAndJoin() }; prefs.removeNode() }
    }
}
