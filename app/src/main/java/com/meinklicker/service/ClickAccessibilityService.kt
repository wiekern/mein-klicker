package com.meinklicker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.meinklicker.TAG
import com.meinklicker.container
import com.meinklicker.data.Scheme
import com.meinklicker.engine.GestureDispatcher
import com.meinklicker.engine.RunState
import com.meinklicker.overlay.ControlBarOverlay
import com.meinklicker.overlay.MarkerEditorOverlay
import com.meinklicker.overlay.MessageOverlay
import com.meinklicker.overlay.OverlayManager
import com.meinklicker.overlay.TapEffectOverlay
import com.meinklicker.overlay.TriggerZoneOverlay
import com.meinklicker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/** 核心宿主：托管所有悬浮窗，执行手势，把用户动作转给 RunController。 */
class ClickAccessibilityService : AccessibilityService(), ControlBarOverlay.Actions {

    val gestures: GestureDispatcher by lazy {
        AccessibilityGestureDispatcher(this) { x, y -> if (::tapEffect.isInitialized) tapEffect.tapAt(x, y) }
    }

    private var scope: CoroutineScope? = null
    private lateinit var overlays: OverlayManager
    private lateinit var controlBar: ControlBarOverlay
    private var editor: MarkerEditorOverlay? = null
    private lateinit var triggerZone: TriggerZoneOverlay
    private lateinit var messages: MessageOverlay
    private lateinit var tapEffect: TapEffectOverlay

    /** 补发的点击被触发区自己接住了（FLAG_NOT_TOUCHABLE 尚未生效），需要重发。 */
    private var replayIntercepted = false

    /** 急停时吞掉的音量下键，对应的 UP 也要吞掉。 */
    private var swallowVolumeUp = false

    private val runController get() = container.runController
    private val repository get() = container.repository

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "accessibility service connected")
        // 部分 ROM 会对同一实例重复回调，先清掉上一次的悬浮窗和协程
        teardown()
        overlays = OverlayManager(this)
        controlBar = ControlBarOverlay(this, overlays, this)
        triggerZone = TriggerZoneOverlay(this, overlays, ::onTriggerTapped) {
            Log.w(TAG, "replay intercepted")
            appendLatencyLog("${System.currentTimeMillis()} INTERCEPTED by trigger zone")
            replayIntercepted = true
        }
        messages = MessageOverlay(this, overlays)
        tapEffect = TapEffectOverlay(this, overlays)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        container.attachService(this)

        scope.launch {
            container.controlBarEnabled.collect { refreshControlBarVisibility() }
        }
        scope.launch {
            combine(runController.state, repository.schemes, container.selectedSchemeId) { state, list, id ->
                Triple(state, list.find { it.id == id }, list)
            }.collect { (state, scheme, list) -> controlBar.render(state, scheme, list) }
        }
        // 点击特效只在运行期间显示（全屏、不可触摸）
        scope.launch {
            combine(runController.state, container.tapEffectEnabled) { state, enabled ->
                enabled && state is RunState.Running
            }.collect { if (it) tapEffect.show() else tapEffect.hide() }
        }
        // 触发区跟随状态：待命显示，触发运行中不拦截触摸，其余隐藏
        scope.launch {
            // 同时观察方案：待命中在主界面改了触发点半径也能立即刷新
            combine(runController.state, repository.schemes) { state, _ -> state }.collect { state ->
                when (state) {
                    is RunState.Armed -> {
                        val trigger = repository.get(state.schemeId)?.trigger
                        if (trigger != null) triggerZone.show(trigger) else runController.disarm()
                    }
                    is RunState.Running -> if (state.fromTrigger) triggerZone.suspendTouch() else triggerZone.hide()
                    RunState.Idle -> triggerZone.hide()
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (event.action == KeyEvent.ACTION_DOWN && runController.state.value is RunState.Running) {
            Log.i(TAG, "emergency stop by volume key")
            runController.stop()
            swallowVolumeUp = true
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && swallowVolumeUp) {
            swallowVolumeUp = false
            return true
        }
        return false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "accessibility service destroyed")
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        val scope = scope ?: return
        this.scope = null
        container.detachService(this)
        editor = null
        overlays.removeAll()
        scope.cancel()
    }

    // ---- 悬浮栏动作 ----

    override fun onRun() {
        val scheme = checkedSelectedScheme() ?: return
        Log.i(TAG, "run ${scheme.name}: ${runController.run(scheme.id)}")
    }

    override fun onArm() {
        val scheme = checkedSelectedScheme() ?: return
        if (scheme.trigger == null) {
            toast("该方案没有触发点，请先在编辑中添加")
            return
        }
        controlBar.bounds()?.let { bar ->
            val r = overlays.dp(scheme.trigger.radiusDp)
            val t = scheme.trigger
            if (Rect.intersects(bar, Rect(t.x - r, t.y - r, t.x + r, t.y + r))) {
                toast("触发区与悬浮栏重叠，请先把悬浮栏拖开")
                return
            }
        }
        if (runController.arm(scheme.id)) toast("已待命：点红圈里的目标开始，再点 👆 退出")
    }

    override fun onDisarm() {
        runController.disarm()
        toast("已退出待命")
    }

    override fun onStop() = runController.stop()

    /** 运行前检查：方案有步骤、步骤不落在悬浮栏上；分辨率不一致只提示。 */
    private fun checkedSelectedScheme(): Scheme? {
        val scheme = container.selectedSchemeId.value?.let(repository::get)
        if (scheme == null || scheme.steps.isEmpty()) {
            toast("请先选择一个有步骤的方案")
            return null
        }
        controlBar.bounds()?.let { bar ->
            val hit = scheme.steps.indexOfFirst { bar.contains(it.x, it.y) }
            if (hit >= 0) {
                toast("第 ${hit + 1} 步落在悬浮栏上，请先把悬浮栏拖开")
                return null
            }
        }
        val screen = overlays.screen
        if (scheme.screenWidth != screen.width || scheme.screenHeight != screen.height) {
            toast("方案分辨率 ${scheme.screenWidth}×${scheme.screenHeight} 与当前屏幕不一致，位置可能偏移")
        }
        return scheme
    }

    /**
     * 拦截 → 补发：先让触发区不再拦截触摸，等几帧，再在按下的位置补发点击。
     * 偶尔 flag 仍未在输入系统生效，补发会被触发区接住；这时再等一轮后重发。
     */
    private fun onTriggerTapped(x: Int, y: Int) {
        val upAt = SystemClock.uptimeMillis()
        triggerZone.suspendTouch()
        replayIntercepted = false
        runController.onTriggerTapped(
            x, y,
            beforeReplay = { triggerZone.awaitTouchReleased() },
            retryIf = {
                if (replayIntercepted) {
                    replayIntercepted = false
                    appendLatencyLog("${System.currentTimeMillis()} retry replay")
                    triggerZone.awaitTouchReleased()
                    true
                } else {
                    false
                }
            },
            onReplayed = { recordReplayLatency(x, y, SystemClock.uptimeMillis() - upAt) },
        )
    }

    override fun onOpenMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onEdit() {
        startEditor(container.selectedSchemeId.value?.let(repository::get))
    }

    override fun onNewScheme() = startEditor(null)

    override fun onSelectScheme(id: String) = container.selectScheme(id)

    /** 编辑期间隐藏悬浮栏，只显示标记和编辑栏。 */
    private fun startEditor(scheme: Scheme?) {
        if (editor != null || runController.state.value != RunState.Idle) return
        val defaultName = "方案 ${repository.schemes.value.size + 1}"
        editor = MarkerEditorOverlay(this, overlays, scheme, defaultName, ::toast) { result ->
            editor = null
            if (result != null) {
                // 用进程级作用域：服务恰好断开时也要把文件和内存都更新完
                container.scope.launch {
                    repository.save(result)
                    container.selectScheme(result.id)
                    toast("已保存「${result.name}」")
                }
            }
            refreshControlBarVisibility()
        }.also { it.show() }
        refreshControlBarVisibility()
    }

    private fun refreshControlBarVisibility() {
        if (container.controlBarEnabled.value && editor == null) controlBar.show() else controlBar.hide()
    }

    private val isDebuggable: Boolean
        get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * 补发延迟打点。部分 ROM（vivo）会屏蔽应用日志，所以 debug 包同时追加到 files/latency.log
     * （`adb shell run-as com.meinklicker cat files/latency.log`）并在屏幕上提示。
     */
    private fun recordReplayLatency(x: Int, y: Int, ms: Long) {
        val line = "${System.currentTimeMillis()} replay ($x, $y) ${ms}ms"
        Log.i(TAG, line)
        appendLatencyLog(line)
        if (isDebuggable) messages.show("补发延迟 $ms ms", 1200)
    }

    /** 只在 debug 包里写文件，release 包不留诊断文件。 */
    private fun appendLatencyLog(line: String) {
        if (!isDebuggable) return
        runCatching { File(filesDir, "latency.log").appendText(line + "\n") }
    }

    /** vivo 等 ROM 在通知权限关闭时会屏蔽 Toast，所以用悬浮窗提示。 */
    private fun toast(text: String) {
        Log.i(TAG, "message: $text")
        messages.show(text)
    }
}
