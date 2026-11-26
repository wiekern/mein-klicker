package com.meinklicker.overlay

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 拖动 / 单击 区分：移动超过 touch slop 算拖动，否则抬起时算单击。
 * requireLongPress 开启时，按住达到系统长按时长后才能拖动。
 * dx/dy 是相对按下点的位移（用 rawX/rawY，屏幕坐标）。
 */
class DragTouchListener(
    private val onDragStart: () -> Unit = {},
    private val onDrag: (dx: Int, dy: Int) -> Unit,
    private val onDragEnd: () -> Unit = {},
    private val requireLongPress: Boolean = false,
    private val onClick: () -> Unit = {},
) : View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var movedBeforeLongPress = false

    private fun isLongPress(event: MotionEvent) =
        requireLongPress && event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                dragging = false
                movedBeforeLongPress = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && !movedBeforeLongPress &&
                    hypot(dx, dy) > ViewConfiguration.get(v.context).scaledTouchSlop
                ) {
                    if (requireLongPress && !isLongPress(e)) {
                        movedBeforeLongPress = true
                    } else {
                        dragging = true
                        onDragStart()
                        if (requireLongPress) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                }
                if (dragging) onDrag(dx.roundToInt(), dy.roundToInt())
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) onDragEnd()
                else if (!movedBeforeLongPress && !isLongPress(e)) onClick()
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) onDragEnd()
        }
        return true
    }
}
