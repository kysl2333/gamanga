package app.gamanga.desktop.ui.recommend

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamanga.desktop.ui.theme.Tokens

@Composable
internal fun BangumiAccountDialog(state: RecommendState, access: String, onAccess: (String) -> Unit,
    onCreate: () -> Unit, onDismiss: () -> Unit) {
    var reveal by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var appeared by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    LaunchedEffect(closing) {
        if (closing) {
            kotlinx.coroutines.delay(if (state.reduceMotion) 0 else Tokens.Motion.HoverMs.toLong())
            onDismiss()
        }
    }
    val dismiss = { closing = true }
    val entrance by animateFloatAsState(if (appeared && !closing) 1f else 0f, tween(if (state.reduceMotion) 0 else Tokens.Motion.HoverMs))
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(RecommendTokens.AccountWidth).graphicsLayer {
            alpha = entrance; translationY = (1f - entrance) * Tokens.Space.Md.toPx()
        }.background(Color.White, RoundedCornerShape(Tokens.Radius.Drawer)).padding(Tokens.Space.Xxl)
            .testTag("bangumi-account-panel"), verticalArrangement = Arrangement.spacedBy(Tokens.Space.Lg)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    androidx.compose.material3.Text("Bangumi 账号", style = Tokens.Type.Title.toTextStyle(), color = RecommendTokens.Ink)
                    Copy("收藏与评分，连接到你的 Bangumi", small = true, color = RecommendTokens.Muted)
                }
                RoundAction(CloseIcon, "关闭账号面板", onClick = dismiss)
            }
            Column(Modifier.fillMaxWidth().background(RecommendTokens.Pale, RecommendTokens.Shape).padding(Tokens.Space.Lg),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.Xs)) {
                Copy(if (state.username.isBlank()) "尚未连接" else state.username, strong = true)
                Copy(if (state.username.isBlank()) "使用个人访问令牌连接账号" else if (state.token.isBlank()) "账号资料已缓存，需要令牌才能同步" else "已连接 · ${state.accountItems.size} 部漫画已缓存", small = true, color = RecommendTokens.Muted)
            }
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.Sm)) {
                Copy("个人访问令牌", strong = true)
                Row(Modifier.fillMaxWidth().height(RecommendTokens.FieldHeight)
                    .background(Tokens.Color.BgSunken, RecommendTokens.NavShape).padding(horizontal = Tokens.Space.Lg),
                    verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(access, onAccess, singleLine = true,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    textStyle = Tokens.Type.Body.toTextStyle().copy(color = RecommendTokens.Ink),
                    cursorBrush = SolidColor(RecommendTokens.Pink),
                    modifier = Modifier.weight(1f)
                        .testTag("bangumi-access-token"),
                    decorationBox = { field -> Box { if (access.isBlank()) Copy("粘贴访问令牌", color = RecommendTokens.Muted); field() } })
                Chip(if (reveal) "隐藏" else "显示") { reveal = !reveal }
                }
                Copy("令牌在本机加密保存，关闭面板后清空本次输入。", small = true, color = RecommendTokens.Muted)
                Chip("创建访问令牌", onClick = onCreate)
            }
            state.message?.let { Copy(it, small = true, color = RecommendTokens.Pink) }
            if (state.syncing) Copy("正在同步 · ${state.syncProgress} 部", small = true, color = RecommendTokens.Pink)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Sm)) {
                if (state.token.isNotBlank() || state.username.isNotBlank()) Chip("断开连接", enabled = !state.syncing) { confirmDisconnect = true }
                Spacer(Modifier.weight(1f))
                Chip("完成", onClick = dismiss)
                Chip(if (state.syncing) "同步中…" else if (access.isBlank() && state.token.isNotBlank()) "同步漫画" else "连接并同步",
                    selected = true, enabled = !state.syncing && (access.isNotBlank() || state.token.isNotBlank())) {
                    state.connect(access.ifBlank { state.token }); onAccess("")
                }
            }
        }
    }
    if (confirmDisconnect) AlertDialog(onDismissRequest = { confirmDisconnect = false }, containerColor = Color.White,
        title = { Copy("断开 Bangumi 连接？", strong = true) }, text = { Copy("移除本机账号凭据与收藏缓存，不影响 Bangumi 网站上的数据。") },
        confirmButton = { Chip("断开连接", selected = true) { state.disconnect(); onAccess(""); confirmDisconnect = false } },
        dismissButton = { Chip("取消") { confirmDisconnect = false } })
}
