package app.gamanga.desktop.core

/**
 * 应用名称与版本号（**唯一事实源**）。
 *
 * 用户口径（2026-10-28）：**每完成一批改动，版本号 +0.0.1**。
 *
 * 改版本只改这里：窗口标题、关于页、WebDAV / Bangumi 的 User-Agent 全读它。
 *
 * 注意 `app/build.gradle.kts` 的 `packageVersion` 是 Gradle DSL 的字面量，
 * 读不到这个常量，**必须手工同步**（那边有注释提醒）。
 */
object AppVersion {
    const val NAME_ZH = "Gaaa漫画"
    const val NAME_EN = "Gamanga"
    const val VERSION = "1.2.1"

    /** 窗口标题。 */
    const val TITLE = "$NAME_ZH $VERSION"

    /** HTTP User-Agent 的产品标识。 */
    const val UA_PRODUCT = "$NAME_EN/$VERSION"
}
