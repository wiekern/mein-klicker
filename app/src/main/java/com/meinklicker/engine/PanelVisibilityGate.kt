package com.meinklicker.engine

import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 由点击代理抛出，表示本轮必须结束且不能恢复待命。 */
class RunAborted(message: String) : Exception(message)

/** 复查期间始终阻塞下一次点击；截图失败由调用方转换成 RunAborted。 */
class PanelVisibilityGate(private val matches: suspend () -> Boolean) {
    suspend fun awaitVisible() {
        currentCoroutineContext().ensureActive()
        if (!matches() && !matches()) throw RunAborted("检测区域已消失，已停止并退出待命")
        currentCoroutineContext().ensureActive()
    }
}

/** 固定区域的颜色网格比较，不搜索整屏，不识别文字。 */
object PanelMatcher {
    const val COLUMNS = 48
    const val ROWS = 16
    const val SAMPLE_COUNT = COLUMNS * ROWS

    fun hasDetail(colors: List<Int>): Boolean = colors.size == SAMPLE_COUNT &&
        (0..2).any { channel ->
            val values = colors.map { (it shr (channel * 8)) and 255 }
            values.max() - values.min() >= 50
        }

    fun matches(reference: List<Int>, actual: List<Int>): Boolean {
        if (reference.size != SAMPLE_COUNT || actual.size != reference.size) return false
        var close = 0
        var totalError = 0L
        reference.indices.forEach { i ->
            var largest = 0
            repeat(3) { channel ->
                val shift = channel * 8
                val difference = abs(((reference[i] shr shift) and 255) - ((actual[i] shr shift) and 255))
                totalError += difference
                largest = maxOf(largest, difference)
            }
            if (largest <= 40) close++
        }
        return close.toDouble() / reference.size >= 0.88 && totalError.toDouble() / (reference.size * 3) <= 18
    }
}
