package com.meinklicker.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** 编辑预览与待命触发区使用相同的颜色、边界和中心点。 */
internal class TriggerCirclePainter(density: Float) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40E53935 }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCE53935.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dotRadius = 2 * density

    fun draw(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val r = (radius - stroke.strokeWidth).coerceAtLeast(0f)
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, stroke)
        canvas.drawCircle(cx, cy, dotRadius, dot)
    }
}
