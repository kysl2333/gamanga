package app.gamanga.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 应用主题（阶段 3.1 建，2026-10-26 接入字体与排版角色）。
 *
 * **只有亮色**：UI-SPEC 第 6 节写明「暗色（后补）」，用户 2026-10-25 也确认
 * 「亮色为主」。旧实现跟系统切暗色，会让同一份令牌在两套底色上算出不同对比度——
 * 等阶段 4 把暗色令牌定下来再开。
 *
 * 角色映射为什么这么配（都是实测过的坑）：
 * - `outline` → [Tokens.Color.TextTertiary]：空态文字「(·_·;)」和未选中导航图标用它，
 *   若映射成 `border/hairline`（黑 6%）在 #F6F6F8 上几乎看不见。
 * - `tertiary` / `onTertiary` → 警告色 + 深字：「置顶」角标是白字压在琥珀底上，
 *   白字对比度只有 2.2，必须换成深色前景。
 * - `surfaceVariant` → 沉底灰：浏览页用它做选中项底。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppLightScheme, typography = AppTypography, content = content)
}

/** 由令牌拼出来的亮色方案（不要在别处再写一遍颜色值）。 */
private val AppLightScheme = lightColorScheme(
    primary = Tokens.Color.Accent,
    onPrimary = Tokens.Color.OnAccent,
    primaryContainer = Tokens.Color.AccentSoft,
    onPrimaryContainer = Tokens.Color.Accent,
    secondary = Tokens.Color.TextSecondary,
    onSecondary = Tokens.Color.OnAccent,
    tertiary = Tokens.Color.Warning,
    onTertiary = Tokens.Color.TextPrimary,
    background = Tokens.Color.BgBase,
    onBackground = Tokens.Color.TextPrimary,
    surface = Tokens.Color.BgElevatedSolid,
    onSurface = Tokens.Color.TextPrimary,
    surfaceVariant = Tokens.Color.BgSunken,
    onSurfaceVariant = Tokens.Color.TextSecondary,
    outline = Tokens.Color.TextTertiary,
    error = Tokens.Color.Danger,
    onError = Tokens.Color.OnAccent,
)

/**
 * 排版：直接由 [Tokens.Type] 的「角色表」生成（UI-SPEC 4.3）。
 *
 * 为什么一个角色映射到多个 M3 slot：Material3 的 15 个 slot 是给手机端层级准备的，
 * 本项目只有 5 个角色。硬凑成 15 个不同值等于自己造第二套规格，
 * 所以这里**多对一**（bodyLarge/bodyMedium 都是 Body 等），保持单一事实源。
 *
 * 字体：家族统一 [AppFont.Ui]（MiSans）。中英混排靠 [MixText] 在文本内部按字符分段
 * ——家族级回落不生效，原因见 `AppFont.kt` 文件头。
 */
private val AppTypography: Typography = Typography(
    displayLarge = Tokens.Type.Title.toTextStyle(),
    displayMedium = Tokens.Type.Title.toTextStyle(),
    displaySmall = Tokens.Type.Title.toTextStyle(),
    headlineLarge = Tokens.Type.Title.toTextStyle(),
    headlineMedium = Tokens.Type.Title.toTextStyle(),
    headlineSmall = Tokens.Type.Title.toTextStyle(),
    titleLarge = Tokens.Type.Title.toTextStyle(),
    titleMedium = Tokens.Type.Subtitle.toTextStyle(),
    titleSmall = Tokens.Type.Subtitle.toTextStyle(),
    bodyLarge = Tokens.Type.Body.toTextStyle(),
    bodyMedium = Tokens.Type.Body.toTextStyle(),
    bodySmall = Tokens.Type.Caption.toTextStyle(),
    labelLarge = Tokens.Type.Body.toTextStyle(),
    labelMedium = Tokens.Type.Caption.toTextStyle(),
    labelSmall = Tokens.Type.Micro.toTextStyle(),
)
