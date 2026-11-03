package com.meinklicker.engine

import com.meinklicker.data.Scheme
import com.meinklicker.data.TriggerConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 执行循环体和触发补发。不依赖 Android，停止靠取消协程：
 * 取消在下一个挂起点（delay 或等待手势回调）生效，所以能在 1 个步骤内停下。
 */
class ClickEngine(private val gestures: GestureDispatcher) {

    /** 按循环设置执行步骤列表；onProgress 的 round、step 都从 1 开始。 */
    suspend fun runLoop(scheme: Scheme, onProgress: (round: Int, step: Int) -> Unit = { _, _ -> }) {
        if (scheme.steps.isEmpty()) return
        val loop = scheme.loop
        var round = 1
        while (loop.infinite || round <= loop.count) {
            if (round > 1 && loop.intervalMs > 0) delay(loop.intervalMs)
            scheme.steps.forEachIndexed { index, step ->
                currentCoroutineContext().ensureActive()
                onProgress(round, index + 1)
                // 等待时长从上一次手势完成时开始计时
                delay(step.delayBeforeMs)
                // 手势被取消时继续下一步，不中断整个方案
                gestures.tap(step.x, step.y)
            }
            round++
        }
    }

    /**
     * 触发模式：在用户实际点击的坐标补发一次单击，然后等待 delayAfterMs。
     * 补发后 [retryIf] 返回 true（例如补发被触发区自己截获）时重发，最多 [MAX_REPLAY_RETRIES] 次。
     */
    suspend fun replayTrigger(
        x: Int,
        y: Int,
        trigger: TriggerConfig,
        retryIf: suspend () -> Boolean = { false },
        onReplayed: () -> Unit = {},
    ) {
        gestures.tap(x, y)
        var retries = 0
        while (retries < MAX_REPLAY_RETRIES && retryIf()) {
            gestures.tap(x, y)
            retries++
        }
        onReplayed()
        delay(trigger.delayAfterMs)
    }

    companion object {
        const val MAX_REPLAY_RETRIES = 2
    }
}
