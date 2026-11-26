package com.meinklicker.engine

import com.meinklicker.data.DEFAULT_PRESS_MS
import com.meinklicker.data.PanelAction
import com.meinklicker.data.PanelCheck
import com.meinklicker.data.branchClickStepIds
import com.meinklicker.data.detectionSteps
import com.meinklicker.data.Scheme
import com.meinklicker.data.panelBranchError
import com.meinklicker.data.TriggerConfig
import com.meinklicker.data.rotationError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 执行循环体和触发补发。不依赖 Android，停止靠取消协程：
 * 取消在下一个挂起点（delay 或等待手势回调）生效，所以能在 1 个步骤内停下。
 */
class ClickEngine(
    private val gestures: GestureDispatcher,
    private val detectPanel: suspend (PanelCheck) -> Boolean = { throw RunAborted("面板检测不可用，已停止") },
) {

    /** 按循环设置执行步骤列表；onProgress 的 round、step 都从 1 开始。 */
    suspend fun runLoop(
        scheme: Scheme,
        onRotationCommitted: suspend () -> Unit = {},
        onPanelCheck: (round: Int) -> Unit = {},
        onRoundWait: (nextRound: Int, intervalMs: Long) -> Unit = { _, _ -> },
        onProgress: (round: Int, step: Int) -> Unit = { _, _ -> },
    ) {
        (scheme.rotationError() ?: scheme.panelBranchError())?.let { throw RunAborted(it) }
        if (scheme.steps.isEmpty()) return
        val group = scheme.rotationGroup
        val detections = scheme.detectionSteps()
        val stepIndices = scheme.steps.withIndex().associate { (index, step) -> step.id to index }
        val branchTargets = detections.branchClickStepIds()
        // 相邻的「第 N 步后」和「第 N+1 步前」属于同一位置，按配置列表顺序执行。
        val detectionsByPosition = detections.groupBy { detection ->
            val branch = detection.branch
            when {
                branch.beforeStepId != null -> stepIndices.getValue(branch.beforeStepId)
                branch.afterStepId != null -> stepIndices.getValue(branch.afterStepId) + 1
                else -> scheme.steps.size
            }
        }

        suspend fun tapStep(round: Int, index: Int) {
            val step = scheme.steps[index]
            currentCoroutineContext().ensureActive()
            onProgress(round, index + 1)
            delay(step.delayBeforeMs)
            val completed = gestures.tap(step.x, step.y, scheme.pressMs)
            if (group != null) {
                if (!completed) throw RunAborted("点击未完成，已停止；请检查循环组下次执行项")
                if (step.id == group.commitStepId) onRotationCommitted()
            }
        }

        val loop = scheme.loop
        var round = 1
        while (group != null || loop.infinite || round <= loop.count) {
            if (round > 1 && loop.intervalMs > 0) {
                onRoundWait(round, loop.intervalMs)
                delay(loop.intervalMs)
            }
            roundSteps@ for (index in 0..scheme.steps.size) {
                for (detection in detectionsByPosition[index].orEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val branch = detection.branch
                    onPanelCheck(round)
                    delay(branch.delayBeforeMs)
                    val visible = detectPanel(checkNotNull(detection.reference))
                    val action = if (visible) branch.presentAction else branch.absentAction
                    when (action) {
                        PanelAction.CLICK -> {
                            val target = if (visible) branch.presentStepId else branch.absentStepId
                            tapStep(round, stepIndices.getValue(target))
                        }
                        PanelAction.STOP -> throw RunAborted("面板检测命中「停止执行」，已结束本次运行")
                        PanelAction.CONTINUE -> Unit
                        PanelAction.END_ROUND -> break@roundSteps
                    }
                }
                if (index == scheme.steps.size) break
                val step = scheme.steps[index]
                if (step.id in branchTargets) continue
                if (group == null || step.id !in group.stepIds || step.id == group.nextStepId) tapStep(round, index)
            }
            if (group != null) break
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
        pressMs: Long = DEFAULT_PRESS_MS,
        retryIf: suspend () -> Boolean = { false },
        onReplayed: () -> Unit = {},
        requireSuccess: Boolean = false,
    ) {
        var completed = gestures.tap(x, y, pressMs)
        var retries = 0
        while (retries < MAX_REPLAY_RETRIES && retryIf()) {
            completed = gestures.tap(x, y, pressMs)
            retries++
        }
        if (requireSuccess && (!completed || retryIf())) throw RunAborted("触发点击未完成，已停止，循环组进度未推进")
        onReplayed()
        delay(trigger.delayAfterMs)
    }

    companion object {
        const val MAX_REPLAY_RETRIES = 2
    }
}
