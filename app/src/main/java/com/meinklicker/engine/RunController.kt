package com.meinklicker.engine

import com.meinklicker.data.Scheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface RunState {
    data object Idle : RunState
    data class Armed(val schemeId: String) : RunState
    data class Running(
        val schemeId: String,
        val round: Int,
        val step: Int,
        val fromTrigger: Boolean,
    ) : RunState
}

/**
 * 运行状态机，只负责状态迁移和 Job 管理，不碰任何 View。
 * 方案每次启动时通过 [lookup] 取最新版本，所以主界面改了参数后下一次运行立即生效。
 * 所有方法应在同一个线程（Service 的主线程）调用。
 */
class RunController(
    private val engine: ClickEngine,
    private val scope: CoroutineScope,
    private val lookup: (id: String) -> Scheme?,
) {
    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    val state: StateFlow<RunState> = _state.asStateFlow()

    private var job: Job? = null

    /** 直接运行（不经过触发点）。只在 Idle 时有效。 */
    fun run(schemeId: String): Boolean {
        if (_state.value != RunState.Idle) return false
        val scheme = lookup(schemeId)?.takeIf { it.steps.isNotEmpty() } ?: return false
        start(scheme, fromTrigger = false) { engine.runLoop(scheme, progress(scheme, false)) }
        return true
    }

    /** 进入待命。方案必须有触发点。 */
    fun arm(schemeId: String): Boolean {
        if (_state.value != RunState.Idle) return false
        if (lookup(schemeId)?.trigger == null) return false
        _state.value = RunState.Armed(schemeId)
        return true
    }

    /**
     * 用户点了触发区。[beforeReplay] 由调用方提供，用来确保触发区已不再拦截触摸（例如等待一帧），
     * 之后才补发点击；[retryIf] 判断补发是否需要重发；补发完成时回调 [onReplayed]。
     */
    fun onTriggerTapped(
        x: Int,
        y: Int,
        beforeReplay: suspend () -> Unit = {},
        retryIf: suspend () -> Boolean = { false },
        onReplayed: () -> Unit = {},
    ): Boolean {
        val armed = _state.value as? RunState.Armed ?: return false
        val scheme = lookup(armed.schemeId)
        val trigger = scheme?.trigger
        if (trigger == null) {
            _state.value = RunState.Idle
            return false
        }
        start(scheme, fromTrigger = true) {
            beforeReplay()
            engine.replayTrigger(x, y, trigger, scheme.pressMs, retryIf = retryIf, onReplayed = onReplayed)
            engine.runLoop(scheme, progress(scheme, true))
        }
        return true
    }

    /** 停止当前运行：触发模式回到待命，直接运行回到空闲。 */
    fun stop() {
        val running = _state.value as? RunState.Running ?: return
        cancelJob()
        _state.value = if (running.fromTrigger) RunState.Armed(running.schemeId) else RunState.Idle
    }

    /** 退出待命（运行中也一并停止）。 */
    fun disarm() {
        if (_state.value == RunState.Idle) return
        reset()
    }

    /** 服务断开等情况：无条件回到空闲。 */
    fun reset() {
        cancelJob()
        _state.value = RunState.Idle
    }

    private fun start(scheme: Scheme, fromTrigger: Boolean, block: suspend () -> Unit) {
        cancelJob()
        lateinit var self: Job
        self = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                // 被 stop()/disarm() 取消时状态已由它们设置；只有自然结束的当前 Job 才在这里迁移
                if (job === self) {
                    job = null
                    _state.value = if (fromTrigger) RunState.Armed(scheme.id) else RunState.Idle
                }
            }
        }
        job = self
        _state.value = RunState.Running(scheme.id, round = 0, step = 0, fromTrigger = fromTrigger)
        self.start()
    }

    private fun progress(scheme: Scheme, fromTrigger: Boolean): (Int, Int) -> Unit = { round, step ->
        _state.value = RunState.Running(scheme.id, round, step, fromTrigger)
    }

    private fun cancelJob() {
        job?.cancel()
        job = null
    }
}
