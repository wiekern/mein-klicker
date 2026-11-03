package com.meinklicker.overlay

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 代替 Toast 的提示条。部分 ROM（如 vivo）在通知权限关闭时会屏蔽 Toast，
 * 而无障碍悬浮窗不受影响。不可触摸，几秒后自动消失。
 */
class MessageOverlay(context: Context, private val overlays: OverlayManager) {
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { overlays.remove(root) }

    private val text = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        background = roundedBg(BAR_BG, overlays.dp(16).toFloat())
        setPadding(overlays.dp(16), overlays.dp(10), overlays.dp(16), overlays.dp(10))
    }
    private val root = FrameLayout(context).apply {
        addView(text, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL,
        ))
    }
    private val params = overlays.newParams(width = LayoutParams.MATCH_PARENT).apply {
        flags = flags or LayoutParams.FLAG_NOT_TOUCHABLE
    }

    fun show(message: String, durationMs: Long = 2500) {
        text.text = message
        val screen = overlays.screen
        params.width = screen.width
        params.y = screen.height / 8
        if (overlays.isShown(root)) {
            overlays.remove(root)
        }
        // 每次重新添加，保证在所有悬浮窗最上层
        overlays.add(root, params)
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, durationMs)
    }

    fun dismiss() {
        handler.removeCallbacks(hideRunnable)
        overlays.remove(root)
    }
}
