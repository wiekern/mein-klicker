package com.meinklicker.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.meinklicker.R
import kotlin.math.max
import kotlin.math.min

/** 拖出矩形后再确认，所有触摸均由选框接住，不传给游戏。 */
class PanelRegionOverlay(
    context: Context,
    private val overlays: OverlayManager,
    private val onFinish: (Rect?) -> Unit,
) {
    private var selected: Rect? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selection = object : View(context) {
        private var startX = 0
        private var startY = 0

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0x33000000)
            selected?.let {
                paint.color = Color.CYAN
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = overlays.dp(2).toFloat()
                canvas.drawRect(it, paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x.toInt().coerceIn(0, width)
            val y = event.y.toInt().coerceIn(0, height)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = x; startY = y; selected = null; invalidate() }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    selected = Rect(min(startX, x), min(startY, y), max(startX, x), max(startY, y))
                    invalidate()
                    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                }
                MotionEvent.ACTION_CANCEL -> { selected = null; invalidate() }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
    private val root = FrameLayout(context).apply {
        addView(selection, FrameLayout.LayoutParams(-1, -1))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(BAR_BG, overlays.dp(10).toFloat())
            setPadding(overlays.dp(8), overlays.dp(8), overlays.dp(8), overlays.dp(8))
            addView(TextView(context).apply {
                setText(R.string.panel_region_hint)
                setTextColor(Color.WHITE)
                textSize = 15f
            })
            addView(LinearLayout(context).apply {
                addView(BarButton(context, "使用选区", overlays.dp(40)) {
                    selected?.takeIf { it.width() >= 16 && it.height() >= 16 }?.let { finish(Rect(it)) }
                }.apply { textSize = 14f })
                addView(BarButton(context, "取消", overlays.dp(40)) { finish(null) }.apply { textSize = 14f })
            })
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = overlays.dp(24)
        })
    }

    fun show() = overlays.add(root, overlays.newParams(width = overlays.screen.width, height = overlays.screen.height))
    fun dismiss() = overlays.remove(root)

    private fun finish(region: Rect?) {
        dismiss()
        onFinish(region)
    }
}
