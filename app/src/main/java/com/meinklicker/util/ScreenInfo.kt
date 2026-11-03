package com.meinklicker.util

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.util.Size
import android.view.WindowManager

object ScreenInfo {
    /** 真实屏幕像素（含状态栏、导航栏、刘海），与手势坐标系一致。 */
    fun realSize(context: Context): Size {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            Size(bounds.width(), bounds.height())
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            Size(point.x, point.y)
        }
    }
}
