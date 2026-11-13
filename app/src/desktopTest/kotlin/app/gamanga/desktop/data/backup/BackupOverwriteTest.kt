package app.gamanga.desktop.data.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * F-44 覆盖式备份的判定逻辑（纯函数，不碰网络）。
 *
 * 核心是「防反向备份」：云端比本地新时**不许上传**，否则会用旧数据盖掉新数据。
 */
class BackupOverwriteTest {

    private val local = 1_000_000_000_000L

    @Test
    fun `remote newer refuses to overwrite`() {
        assertEquals(
            BackupDecision.RefuseRemoteNewer,
            backupDecision(local, local + 10 * 60_000, force = false),
        )
    }

    @Test
    fun `remote slightly newer within tolerance still overwrites`() {
        // 秒级精度 + 时钟偏差：60 秒内不算「云端更新」，否则刚备份完再备份会被误拒
        assertEquals(
            BackupDecision.Overwrite,
            backupDecision(local, local + 30_000, force = false),
        )
    }

    @Test
    fun `remote older overwrites`() {
        assertEquals(BackupDecision.Overwrite, backupDecision(local, local - 60_000, force = false))
    }

    @Test
    fun `no remote backup overwrites`() {
        assertEquals(BackupDecision.Overwrite, backupDecision(local, null, force = false))
    }

    @Test
    fun `force always overwrites`() {
        assertEquals(
            BackupDecision.Overwrite,
            backupDecision(local, local + 999 * 60_000, force = true),
        )
    }

    @Test
    fun `fixed backup name is used`() {
        assertEquals("gamanga-latest.zip", BackupManager.LATEST_BACKUP_NAME)
        assertEquals("gamanga-prev.zip", BackupManager.PREV_BACKUP_NAME)
    }

    @Test
    fun `parse rfc1123 and iso dates`() {
        assertEquals(1_445_412_480_000L, parseHttpDate("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(1_445_437_680_000L, parseHttpDate("2015-10-21T14:28:00Z"))
    }

    @Test
    fun `unparsable date is treated as unknown`() {
        assertNull(parseHttpDate(null))
        assertNull(parseHttpDate(""))
        assertNull(parseHttpDate("昨天"))
    }
}
