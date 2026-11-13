package app.gamanga.desktop.ui.reader

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import app.gamanga.desktop.ui.theme.Tokens

/** 目标尺寸只测一次，三个绘制层读取同一进度，避免逐帧Fit或退场结束才跳变。 */
@Composable
internal fun ReaderViewportMotion(
    visible: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    toolbar: @Composable () -> Unit,
    progress: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val fraction = animateFloatAsState(if (visible) 1f else 0f,
        tween(if (reducedMotion) 0 else Tokens.Motion.PageSwitchMs), label = "readerViewport")
    Layout(content = {
        Box { toolbar() }
        Box { progress() }
        Box { content() }
    }, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val header = measurables[0].measure(Constraints.fixedWidth(width).copy(maxHeight = height))
        val line = measurables[1].measure(Constraints.fixedWidth(width).copy(maxHeight = height))
        val targetTop = if (visible) header.height else 0
        val targetHeight = (height - targetTop - line.height).coerceAtLeast(1)
        val body = measurables[2].measure(Constraints.fixed(width, targetHeight))
        layout(width, height) {
            body.placeWithLayer(0, targetTop + line.height) {
                val top = header.height * fraction.value
                translationY = top - targetTop
                val scale = (height - top - line.height).coerceAtLeast(1f) / targetHeight
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(.5f, 0f)
            }
            line.placeWithLayer(0, targetTop) { translationY = header.height * fraction.value - targetTop }
            if (fraction.value > 0f) header.placeWithLayer(0, 0) {
                translationY = -header.height * (1f - fraction.value)
                alpha = fraction.value
            }
        }
    }
}

@Composable
internal fun ReaderPanelMotion(
    settingsOpen: Boolean,
    chaptersOpen: Boolean,
    reducedMotion: Boolean,
    settings: @Composable () -> Unit,
    chapters: @Composable () -> Unit,
) {
    var retainedChapters by remember { mutableStateOf(chaptersOpen) }
    if (settingsOpen || chaptersOpen) retainedChapters = chaptersOpen
    val duration = if (reducedMotion) 0 else Tokens.ReaderChrome.PanelMs
    AnimatedVisibility(settingsOpen || chaptersOpen,
        enter = slideInVertically(tween(duration)) { it / 5 } + fadeIn(tween(duration)),
        exit = slideOutVertically(tween(duration)) { it / 5 } + fadeOut(tween(duration))) {
        Box(Modifier.padding(bottom = ReaderSettingsGap)) {
            if (retainedChapters) chapters() else settings()
        }
    }
}
