package com.meinklicker.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.view.WindowManager.LayoutParams

/**
 * 点击特效：在每次自动点击的位置画一个扩散后淡出的圆圈。
 * 全屏、不可触摸（FLAG_NOT_TOUCHABLE），注入的手势会直接落到下层应用。只在运行期间显示。
 */
class TapEffectOverlay(context: Context, private val overlays: OverlayManager) {
    private class Ripple(val x: Float, val y: Float, val startAt: Long)

    private val ripples = ArrayDeque<Ripple>()
    private val view = RippleView(context)
    private val params = overlays.newParams(width = LayoutParams.MATCH_PARENT, height = LayoutParams.MATCH_PARENT)
        .apply { flags = flags or LayoutParams.FLAG_NOT_TOUCHABLE }

    fun show() {
        if (overlays.isShown(view)) return
        val screen = overlays.screen
        params.width = screen.width
        params.height = screen.height
        overlays.add(view, params)
    }

    fun hide() {
        ripples.clear()
        overlays.remove(view)
    }

    /** 在主线程调用。 */
    fun tapAt(x: Int, y: Int) {
        if (!overlays.isShown(view)) return
        ripples.addLast(Ripple(x.toFloat(), y.toFloat(), SystemClock.uptimeMillis()))
        // 无限循环时限制数量，避免堆积
        while (ripples.size > MAX_RIPPLES) ripples.removeFirst()
        view.postInvalidateOnAnimation()
    }

    private inner class RippleView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
        }

        override fun onDraw(canvas: Canvas) {
            val now = SystemClock.uptimeMillis()
            while (ripples.isNotEmpty() && now - ripples.first().startAt > DURATION_MS) ripples.removeFirst()
            ripples.forEach { r ->
                val t = (now - r.startAt).toFloat() / DURATION_MS
                val radius = (8 + 22 * t) * density
                val alpha = ((1 - t) * 255).toInt().coerceIn(0, 255)
                fill.color = (alpha / 3 shl 24) or 0xFFFFFF
                ring.color = (alpha shl 24) or 0x1E88E5
                canvas.drawCircle(r.x, r.y, radius, fill)
                canvas.drawCircle(r.x, r.y, radius, ring)
            }
            if (ripples.isNotEmpty()) postInvalidateOnAnimation()
        }
    }

    private companion object {
        const val DURATION_MS = 350L
        const val MAX_RIPPLES = 16
    }
}
