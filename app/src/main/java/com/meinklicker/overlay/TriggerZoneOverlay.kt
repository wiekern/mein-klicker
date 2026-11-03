package com.meinklicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.meinklicker.data.TriggerConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 待命时以触发点为圆心的可触摸区域，屏幕其余部分不受影响。
 * 手指抬起时回调 [onTapped]，坐标取按下时的位置（最接近用户想点的点）。
 */
class TriggerZoneOverlay(
    context: Context,
    private val overlays: OverlayManager,
    private val onTapped: (x: Int, y: Int) -> Unit,
) {
    private val view = ZoneView(context)
    private val params = overlays.newParams()
    private var radius = 0

    val isShown: Boolean get() = overlays.isShown(view)

    /** 显示（或恢复）为可触摸的待命状态。 */
    fun show(trigger: TriggerConfig) {
        radius = overlays.dp(trigger.radiusDp)
        params.width = radius * 2
        params.height = radius * 2
        params.x = trigger.x - radius
        params.y = trigger.y - radius
        view.visibility = View.VISIBLE
        // 已显示时更新位置并恢复可触摸；未显示时 setTouchable 只改 flag，由 add 生效
        overlays.setTouchable(view, params, true)
        overlays.add(view, params)
    }

    /** 运行期间：不再拦截触摸并隐藏，窗口保留以便快速恢复。 */
    fun suspendTouch() {
        if (!isShown) return
        view.visibility = View.INVISIBLE
        overlays.setTouchable(view, params, false)
    }

    fun hide() = overlays.remove(view)

    /** 等待一帧，让 FLAG_NOT_TOUCHABLE 在系统侧生效，再补发点击。 */
    suspend fun awaitFrame() = suspendCancellableCoroutine { cont ->
        val callback = Choreographer.FrameCallback { if (cont.isActive) cont.resume(Unit) }
        Choreographer.getInstance().postFrameCallback(callback)
        cont.invokeOnCancellation { Choreographer.getInstance().removeFrameCallback(callback) }
    }

    private inner class ZoneView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40E53935 }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCE53935.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private var downX = 0f
        private var downY = 0f
        private var downInside = false

        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            val r = c - stroke.strokeWidth
            canvas.drawCircle(c, c, r, fill)
            canvas.drawCircle(c, c, r, stroke)
            canvas.drawCircle(c, c, 2 * density, dot)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    // 窗口是正方形，只认圆内的按下
                    downInside = hypot(e.x - width / 2f, e.y - height / 2f) <= width / 2f
                }
                MotionEvent.ACTION_UP -> if (downInside) {
                    downInside = false
                    onTapped(downX.roundToInt(), downY.roundToInt())
                }
                MotionEvent.ACTION_CANCEL -> downInside = false
            }
            return true
        }
    }
}
