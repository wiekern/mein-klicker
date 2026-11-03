package com.meinklicker.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.View
import android.view.WindowManager.LayoutParams
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 编辑时在相邻步骤之间画连线，线中间带箭头指向下一步；每个触发点到第 1 步用红色虚线。
 * 全屏但不可触摸，不影响操作下层应用和拖动标记。应在标记之前添加，让标记盖在线上。
 */
internal class OrderLinesOverlay(context: Context, private val overlays: OverlayManager) {
    private val view = LinesView(context)
    private val params = overlays.newParams(width = LayoutParams.MATCH_PARENT, height = LayoutParams.MATCH_PARENT)
        .apply { flags = flags or LayoutParams.FLAG_NOT_TOUCHABLE }

    private var triggers: List<Point> = emptyList()
    private var steps: List<Point> = emptyList()

    fun show() {
        val screen = overlays.screen
        params.width = screen.width
        params.height = screen.height
        overlays.add(view, params)
    }

    fun dismiss() = overlays.remove(view)

    fun update(triggers: List<Point>, steps: List<Point>) {
        this.triggers = triggers
        this.steps = steps
        view.invalidate()
    }

    private inner class LinesView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val stepLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB31E88E5.toInt()
            strokeWidth = 3 * density
            style = Paint.Style.STROKE
        }
        private val triggerLine = Paint(stepLine).apply {
            color = 0xB3E53935.toInt()
            pathEffect = DashPathEffect(floatArrayOf(10 * density, 6 * density), 0f)
        }
        private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val arrow = Path()

        override fun onDraw(canvas: Canvas) {
            steps.firstOrNull()?.let { first -> triggers.forEach { drawLink(canvas, it, first, triggerLine) } }
            steps.zipWithNext().forEach { (a, b) -> drawLink(canvas, a, b, stepLine) }
        }

        private fun drawLink(canvas: Canvas, a: Point, b: Point, paint: Paint) {
            // 坐标是屏幕绝对像素，窗口从 (0,0) 开始铺满屏幕，可直接使用
            canvas.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), paint)
            if (hypot((b.x - a.x).toFloat(), (b.y - a.y).toFloat()) < 60 * density) return
            // 箭头：尖端在中点前方，两个底角在中点后方、垂直于连线两侧
            val angle = atan2((b.y - a.y).toFloat(), (b.x - a.x).toFloat())
            val dx = cos(angle)
            val dy = sin(angle)
            val mx = (a.x + b.x) / 2f
            val my = (a.y + b.y) / 2f
            val half = 7 * density
            arrow.reset()
            arrow.moveTo(mx + dx * half, my + dy * half)
            arrow.lineTo(mx - dx * half - dy * half, my - dy * half + dx * half)
            arrow.lineTo(mx - dx * half + dy * half, my - dy * half - dx * half)
            arrow.close()
            arrowFill.color = paint.color or 0xFF000000.toInt()
            canvas.drawPath(arrow, arrowFill)
        }
    }
}
