package com.meinklicker.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** 半透明圆点 + 编号 + 中心十字。十字中心就是点击坐标。 */
internal class MarkerView(context: Context, private val fillColor: Int) : View(context) {
    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 1 * density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12 * density
        setShadowLayer(2 * density, 0f, 0f, 0x99000000.toInt())
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - stroke.strokeWidth
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, stroke)
        val arm = 4 * density
        canvas.drawLine(cx - arm, cy, cx + arm, cy, cross)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, cross)
        // 编号放在中心上方，避免挡住十字
        canvas.drawText(label, cx, cy - 4 * density, text)
    }
}
