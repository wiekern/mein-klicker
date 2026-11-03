package com.meinklicker.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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
