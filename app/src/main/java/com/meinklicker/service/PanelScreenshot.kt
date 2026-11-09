package com.meinklicker.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.meinklicker.data.PanelCheck
import com.meinklicker.engine.PanelMatcher
import java.io.Closeable
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** 所有截图串行且不缓存；Android 的截图频率限制也计入等待时间。 */
class PanelScreenshot(private val service: AccessibilityService) {
    private val mutex = Mutex()
    private var lastRequestAt = -1_000L

    @RequiresApi(30)
    private fun foregroundWindow(displayId: Int): AccessibilityWindowInfo {
        val activeId = service.rootInActiveWindow?.windowId
        val all = service.windowsOnAllDisplays.let { displays ->
            (0 until displays.size()).flatMap { displays.valueAt(it) }
        }
        val target = all.firstOrNull { it.id == activeId && it.isFocused && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?: all.singleOrNull { it.isActive && it.isFocused && it.displayId == displayId &&
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?: throw IllegalStateException("无法读取当前应用窗口")
        check(target.displayId == displayId) { "当前应用不在点击所在屏幕，请重新开启无障碍服务" }
        return target
    }

    suspend fun capture(displayId: Int): Frame = mutex.withLock {
        if (Build.VERSION.SDK_INT < 30) throw IllegalStateException("面板检测需要 Android 11 或以上")
        withTimeout(3_000) {
            delay((350 - (SystemClock.uptimeMillis() - lastRequestAt)).coerceAtLeast(0))
            val window = foregroundWindow(displayId)
            val packageName = window.root?.packageName?.toString()
                ?: throw IllegalStateException("无法读取当前应用")
            val bounds = Rect().also(window::getBoundsInScreen)
            lastRequestAt = SystemClock.uptimeMillis()
            val bitmap = screenshot(window.id, displayId)
            try {
                val current = foregroundWindow(displayId)
                check(current.id == window.id && current.root?.packageName?.toString() == packageName) {
                    "当前应用已切换"
                }
                // 窗口截图以窗口左上角为原点；全屏截图以屏幕左上角为原点。
                val origin = if (Build.VERSION.SDK_INT >= 34) bounds else Rect(0, 0, bitmap.width, bitmap.height)
                check(bitmap.width == origin.width() && bitmap.height == origin.height()) { "截图尺寸发生变化" }
                Frame(bitmap, packageName, origin)
            } catch (e: Exception) {
                bitmap.recycle()
                throw e
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @RequiresApi(30)
    private suspend fun screenshot(windowId: Int, displayId: Int): Bitmap = suspendCancellableCoroutine { cont ->
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                try {
                    if (!cont.isActive) return
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?: throw IllegalStateException("无法读取截图")
                    val bitmap = try {
                        hardware.copy(Bitmap.Config.ARGB_8888, false)
                            ?: throw IllegalStateException("无法转换截图")
                    } finally {
                        hardware.recycle()
                    }
                    cont.resume(bitmap, onCancellation = { bitmap.recycle() })
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                } finally {
                    buffer.close()
                }
            }

            override fun onFailure(errorCode: Int) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("截图失败（$errorCode）"))
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) service.takeScreenshotOfWindow(windowId, service.mainExecutor, callback)
            else service.takeScreenshot(displayId, service.mainExecutor, callback)
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    class Frame(
        private val bitmap: Bitmap,
        val packageName: String,
        private val bounds: Rect,
    ) : Closeable {
        fun sample(region: Rect): List<Int> {
            require(region.width() >= 16 && region.height() >= 16 && bounds.contains(region)) {
                "检测区域不在当前应用画面内，请重新框选"
            }
            return List(PanelMatcher.SAMPLE_COUNT) { i ->
                val x = region.left - bounds.left + ((i % PanelMatcher.COLUMNS + 0.5) * region.width() / PanelMatcher.COLUMNS).toInt()
                val y = region.top - bounds.top + ((i / PanelMatcher.COLUMNS + 0.5) * region.height() / PanelMatcher.ROWS).toInt()
                bitmap.getPixel(x, y)
            }
        }

        fun matches(check: PanelCheck): Boolean = packageName == check.packageName &&
            PanelMatcher.matches(check.colors, sample(Rect(check.left, check.top, check.right, check.bottom)))

        override fun close() = bitmap.recycle()
    }
}
