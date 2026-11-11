package com.meinklicker.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView

internal const val BAR_BG = 0xE0263238.toInt()
internal const val STEP_COLOR = 0xCC1E88E5.toInt()
internal const val TRIGGER_COLOR = 0xCCE53935.toInt()

internal fun roundedBg(color: Int, radiusPx: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx
}

internal fun circleBg(color: Int, strokePx: Int = 0) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
    if (strokePx > 0) setStroke(strokePx, Color.WHITE)
}

/** 悬浮栏上的文字按钮。禁用时半透明。 */
internal class BarButton(context: Context, label: String, sizePx: Int, onClick: () -> Unit) :
    TextView(context) {
    init {
        text = label
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minWidth = sizePx
        minHeight = sizePx
        setOnClickListener { onClick() }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.35f
    }
}

/** 固定大小的纯图标按钮，长按可查看说明。禁用时半透明。 */
internal class IconBarButton(
    context: Context,
    iconRes: Int,
    description: String,
    sizePx: Int,
    onClick: () -> Unit,
) : ImageView(context) {
    init {
        layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        contentDescription = description
        tooltipText = description
        setImageResource(iconRes)
        scaleType = ScaleType.FIT_CENTER
        val padding = (10 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        setOnClickListener { onClick() }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.35f
    }
}

/** 图标 + 下方小字说明的按钮，用于编辑栏。禁用时半透明。 */
internal class CaptionButton(
    context: Context,
    icon: String,
    caption: String,
    widthPx: Int,
    onClick: () -> Unit,
) : LinearLayout(context) {
    private val iconView = TextView(context).apply {
        text = icon
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
    }
    private val captionView = TextView(context).apply {
        text = caption
        textSize = 10f
        setTextColor(0xFFB0BEC5.toInt())
        gravity = Gravity.CENTER
        isSingleLine = true
    }

    var caption: CharSequence
        get() = captionView.text
        set(value) {
            captionView.text = value
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        minimumWidth = widthPx
        val pad = (4 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        addView(iconView)
        addView(captionView)
        setOnClickListener { onClick() }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.35f
    }
}

/** 编辑栏按钮组之间的竖向分隔线。 */
internal fun verticalDivider(context: Context, heightPx: Int) = View(context).apply {
    setBackgroundColor(0x40FFFFFF)
    layoutParams = LinearLayout.LayoutParams((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1), heightPx).apply {
        val m = (2 * resources.displayMetrics.density).toInt()
        setMargins(m, 0, m, 0)
    }
}
