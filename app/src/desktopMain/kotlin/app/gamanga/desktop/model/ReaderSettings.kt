package app.gamanga.desktop.model

/** 阅读方式。 */
enum class ReaderMode(val label: String) {
    /**
     * 竖向连续滚动（默认，适合条漫与常规页漫）。
     * 2026-10-23 用户要求 UI 文案改叫「条漫模式」；2026-10-24 又要求去掉「模式」两字
     * （按钮上就写「条漫」）。内部枚举名保持 VERTICAL 不动（prefs 里存的是 name）。
     */
    VERTICAL("条漫"),

    /** 单页：一次一页，铺满可视区。 */
    SINGLE("单页"),

    /** 双页：一次两页并排（实体书观感）。 */
    DOUBLE("双页");

    companion object {
        fun of(value: String?): ReaderMode = entries.firstOrNull { it.name == value } ?: VERTICAL
    }
}

/** 翻页方向。只影响单页/双页；竖向连续不区分方向。 */
enum class ReaderDirection(val label: String) {
    LTR("左→右"),
    RTL("右→左");

    companion object {
        fun of(value: String?): ReaderDirection = entries.firstOrNull { it.name == value } ?: LTR
    }
}

/**
 * 阅读器显示设置。
 *
 * [zoom] 是「适应高度」基准宽度的倍率：1.0 = 页面按视口高度完整显示（用户要求的默认
 * 显示方式，R-30）。竖向连续模式下表现为列宽；单页/双页模式下经 graphicsLayer 直接
 * 缩放图片本体。0.4×~2.0× 两种模式通用。
 */
data class ReaderSettings(
    val mode: ReaderMode = ReaderMode.VERTICAL,
    val zoom: Float = 1.0f,
    val direction: ReaderDirection = ReaderDirection.LTR
) {
    fun zoomIn() = copy(zoom = (zoom + ZOOM_STEP).coerceAtMost(ZOOM_MAX))
    fun zoomOut() = copy(zoom = (zoom - ZOOM_STEP).coerceAtLeast(ZOOM_MIN))

    companion object {
        const val ZOOM_MIN = 0.4f
        const val ZOOM_MAX = 2.0f
        const val ZOOM_STEP = 0.1f

        // prefs 的 key 都带 reader. 前缀，避免和别的设置撞
        const val KEY_MODE = "reader.mode"
        const val KEY_ZOOM = "reader.zoom"
        const val KEY_DIRECTION = "reader.direction"
    }
}
