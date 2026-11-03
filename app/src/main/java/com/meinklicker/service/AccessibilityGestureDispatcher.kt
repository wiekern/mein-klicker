package com.meinklicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import com.meinklicker.TAG
import com.meinklicker.engine.GestureDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 用 dispatchGesture 实现单击。挂起直到 onCompleted/onCancelled，
 * 保证串行：新的 dispatchGesture 会取消仍在执行的旧手势。
 */
class AccessibilityGestureDispatcher(private val service: AccessibilityService) : GestureDispatcher {

    override suspend fun tap(x: Int, y: Int, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "gesture cancelled at ($x, $y)")
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (!service.dispatchGesture(gesture, callback, null)) {
                Log.w(TAG, "dispatchGesture rejected at ($x, $y)")
                cont.resume(false)
            }
        }
}
