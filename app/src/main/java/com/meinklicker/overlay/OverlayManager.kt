package com.meinklicker.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import com.meinklicker.util.ScreenInfo
import kotlin.math.roundToInt

/**
 * 统一管理无障碍服务托管的悬浮窗。所有窗口都用 TOP|START + LAYOUT_NO_LIMITS + SHORT_EDGES，
 * 因此 params.x/y 就是屏幕绝对像素，与手势坐标一致。
 */
class OverlayManager(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val views = mutableSetOf<View>()
    private val density = context.resources.displayMetrics.density

    val screen: Size get() = ScreenInfo.realSize(context)

    fun dp(value: Int): Int = (value * density).roundToInt()

    fun newParams(
        x: Int = 0,
        y: Int = 0,
        width: Int = LayoutParams.WRAP_CONTENT,
        height: Int = LayoutParams.WRAP_CONTENT,
        focusable: Boolean = false,
    ) = LayoutParams(
        width,
        height,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        (if (focusable) 0 else LayoutParams.FLAG_NOT_FOCUSABLE) or
            LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    fun isShown(view: View) = view in views

    fun add(view: View, params: LayoutParams) {
        if (views.add(view)) wm.addView(view, params)
    }

    fun update(view: View, params: LayoutParams) {
        if (view in views) wm.updateViewLayout(view, params)
    }

    fun setTouchable(view: View, params: LayoutParams, touchable: Boolean) {
        params.flags = if (touchable) {
            params.flags and LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or LayoutParams.FLAG_NOT_TOUCHABLE
        }
        update(view, params)
    }

    fun remove(view: View) {
        if (views.remove(view)) wm.removeViewImmediate(view)
    }

    fun removeAll() {
        views.toList().forEach(::remove)
    }
}
