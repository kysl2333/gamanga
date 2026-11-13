package app.gamanga.desktop.ui.library

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.gamanga.desktop.data.CoverCache
import app.gamanga.desktop.data.CustomCoverStore
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.ui.RemoteImage
import app.gamanga.desktop.ui.shell.DockIcons
import app.gamanga.desktop.ui.theme.MixText
import app.gamanga.desktop.ui.theme.Tokens
import kotlin.math.roundToInt

/**
 * 书架卡片（新书架页，2026-10-26）。
 *
 * 视觉依据 `docs/ui/prototype/bookshelf-3x2.html` 的 `.card`：
 * 封面 3:4 + 底部进度条 + 书名一行 + 「第 N 话　68%」一行。
 *
 * **刻意保留的两个细节**（原型 `bookshelf.md` 里写过的理由）：
 * 1. 第二行**不用中点串**（「第 12 话 · 68%」），而是「第 12 话」+ 空白 + 百分比，
 *    百分比用更浅的 tertiary —— 否则一排卡片看过去会连成一行字。
 * 2. hover 只抬 3dp，不放大、不加发光。整墙几十张卡同时动会晕。
 *
 * 封面来源见 [CustomCoverStore]：用户设了自定义封面就用它（Crop 填满 3:4），
 * 否则走本地缓存的源站封面。**hover 规格（3dp / 5→18 / 170ms / 只动封面）一个字都没动**——
 * 那是用户已验收的，换图不该顺手动交互。
 *
 * 右键不在这里弹菜单：菜单是**一个**（页面级），这里只上报「按下的窗口坐标」。
 * 每张卡各持一个 Popup 会在滚动时残留（旧实现踩过）。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ShelfCard(
    item: LibraryRepository.ShelfItem,
    multi: Boolean,
    picked: Boolean,
    /** F-271：这本有几话是没看过的（>0 时封面右上角出绿点）。 */
    newChapters: Int = 0,
    onClick: () -> Unit,
    /** 右键：上报按下点的**窗口**绝对坐标，由页面统一弹菜单。 */
    onContextMenu: (Offset) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // 多选态下不做 hover 抬升：那时用户要的是「点选」，抬升会让人以为已经选中
    val lifted = hovered && (!multi || picked)
    val lift by animateDpAsState(
        targetValue = if (lifted) 3.dp else 0.dp,
        animationSpec = tween(170),
        label = "shelfLift",
    )
    val elevation by animateDpAsState(
        targetValue = if (lifted) 18.dp else 5.dp,
        animationSpec = tween(170),
        label = "shelfElevation",
    )

    var originInWindow by remember { mutableStateOf(IntOffset.Zero) }

    Column(
        Modifier
            .onGloballyPositioned { coords ->
                // 1.12 起没有 positionInWindow()，用 localToWindow(零点) 拿窗口坐标
                val o = coords.localToWindow(Offset.Zero)
                originInWindow = IntOffset(o.x.roundToInt(), o.y.roundToInt())
            }
            // 右键必须在点击之前截胡并 consume：长按/点击判定不区分左右键
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed) {
                    val press = event.changes.first().position
                    event.changes.forEach { it.consume() }
                    onContextMenu(
                        Offset(
                            originInWindow.x + press.x,
                            originInWindow.y + press.y,
                        )
                    )
                }
            }
            .hoverable(interaction)
            .shelfClickable(interaction) { onClick() }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = -lift.toPx() }
                .shadow(
                    elevation = elevation,
                    shape = RoundedCornerShape(Tokens.Radius.Card),
                    clip = false,
                    // 阴影用中性黑：彩色阴影会让整墙发脏
                    ambientColor = Color(0x14000000),
                    spotColor = Color(0x1F000000),
                )
                .clip(RoundedCornerShape(Tokens.Radius.Card))
                .aspectRatio(3f / 4f)
                // 选中态：3dp 强调色描边（原型 `.card.picked .cover{box-shadow:0 0 0 3px accent}`）
                .then(
                    if (picked) Modifier.border(3.dp, Tokens.Color.Accent, RoundedCornerShape(Tokens.Radius.Card))
                    else Modifier
                )
                .background(Color(item.coverColorArgb))
        ) {
            // 封面优先级：**自定义封面 > 本地缓存的源站封面 > 源站地址**。
            // 自定义封面必须最先判——用户特意换的图不该被缓存或网络结果盖掉。
            // `coverVersion` 进 remember 的 key：换封面后 URL 变了要重新查一次
            // （只查一次字符串，不是每帧）。
            val remoteCover = item.entry.coverUrl
            val coverVersion by CustomCoverStore.version.collectAsState()
            val customCover = remember(coverVersion, item.entry.id) {
                CustomCoverStore.localUri(item.entry.id)
            }
            var coverUri by remember(remoteCover) { mutableStateOf(CoverCache.localUri(remoteCover)) }
            LaunchedEffect(remoteCover) {
                if (remoteCover != null) coverUri = CoverCache.resolve(remoteCover)
            }
            // 「最优适配」：自定义封面也走 Crop——填满 3:4 框、居中裁切、不变形不拉伸。
            // 用户选这张图就是为了看见它，填满比留白更接近「封面」的语义；
            // 与源站封面同一套参数，整墙看过去不会一半铺满一半留边。
            val cover = customCover ?: coverUri ?: remoteCover
            if (cover != null) {
                RemoteImage(
                    url = cover,
                    contentDescription = item.entry.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }

            // 底部进度条：黑 20% 轨道 + 白条（原型 `.bar`）
            if (item.percent > 0) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0x33000000))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(item.percent / 100f)
                            .fillMaxSize()
                            .background(Color.White)
                    )
                }
            }

            if (item.pinned) {
                MixText(
                    text = "置顶",
                    color = Tokens.Color.TextPrimary,
                    fontSize = Tokens.Type.Micro.size,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0xE6FFFFFF), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            if (multi) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .shadow(2.dp, CircleShape)
                        .clip(CircleShape)
                        .background(if (picked) Tokens.Color.Accent else Color(0xEBFFFFFF))
                        .border(
                            width = 1.5.dp,
                            color = if (picked) Tokens.Color.Accent else Color(0x2E000000),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (picked) {
                        Icon(
                            imageVector = DockIcons.Check,
                            contentDescription = null,
                            tint = Tokens.Color.OnAccent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            } else if (newChapters > 0) {
                // F-271 新章节提示：封面框右上角一颗绿点（用户 2026-10-29 口径）。
                // 多选态不显示——那时右上角是勾选圈，两个圆叠在一起会互相打架。
                // 白描边是为了在任何封面上都读得出来（纯绿点压在深色封面上会糊掉）。
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(9.dp)
                        .size(11.dp)
                        .shadow(2.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Tokens.Color.Success)
                        .border(1.5.dp, Color.White, CircleShape),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        MixText(
            text = item.entry.title,
            color = Tokens.Color.TextPrimary,
            fontSize = Tokens.Type.Body.size,
            style = Tokens.Type.Body.copy(weight = FontWeight.Medium).toTextStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            MixText(
                text = item.chapterLabel,
                color = Tokens.Color.TextSecondary,
                fontSize = Tokens.Type.Caption.size,
                style = Tokens.Type.Caption.toTextStyle(),
                maxLines = 1,
            )
            if (item.percent > 0) {
                Spacer(Modifier.size(7.dp))
                MixText(
                    text = "${item.percent}%",
                    color = Tokens.Color.TextTertiary,
                    fontSize = Tokens.Type.Caption.size,
                    // 百分比会变，用等宽数字（UI-SPEC 4.3 硬约束）
                    style = Tokens.Type.Caption.toTextStyle()
                        .copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 卡片点击（左键）。
 *
 * 为什么不用 `Modifier.clickable`：它会把右键也算作点击（桌面端 `clickable` 只看按下/抬起，
 * 不区分按钮），右键时就会既弹菜单又打开详情页。这里显式只认主键。
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.shelfClickable(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this.onPointerEvent(PointerEventType.Press) { event ->
    if (event.buttons.isPrimaryPressed) {
        event.changes.forEach { it.consume() }
        onClick()
    }
}
