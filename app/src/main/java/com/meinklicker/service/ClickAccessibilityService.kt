package com.meinklicker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.WindowManager
import com.meinklicker.TAG
import com.meinklicker.container
import com.meinklicker.data.Scheme
import com.meinklicker.data.PanelCheck
import com.meinklicker.data.triggerLabel
import com.meinklicker.engine.GestureDispatcher
import com.meinklicker.engine.RunState
import com.meinklicker.engine.RunAborted
import com.meinklicker.engine.PanelMatcher
import com.meinklicker.engine.PanelVisibilityGate
import com.meinklicker.overlay.ControlBarOverlay
import com.meinklicker.overlay.MarkerEditorOverlay
import com.meinklicker.overlay.MessageOverlay
import com.meinklicker.overlay.OverlayManager
import com.meinklicker.overlay.TapEffectOverlay
import com.meinklicker.overlay.TriggerZoneOverlay
import com.meinklicker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** 核心宿主：托管所有悬浮窗，执行手势，把用户动作转给 RunController。 */
class ClickAccessibilityService : AccessibilityService(), ControlBarOverlay.Actions {

    val gestures: GestureDispatcher by lazy {
        AccessibilityGestureDispatcher(this,
            onTap = { x, y -> if (::tapEffect.isInitialized) tapEffect.tapAt(x, y) },
            displayId = { overlays.displayId },
        )
    }

    private val panelScreenshot by lazy { if (Build.VERSION.SDK_INT >= 30) PanelScreenshot(this) else null }

    private var scope: CoroutineScope? = null
    private lateinit var overlays: OverlayManager
    private lateinit var controlBar: ControlBarOverlay
    private var editor: MarkerEditorOverlay? = null
    private lateinit var triggerZone: TriggerZoneOverlay
    private lateinit var messages: MessageOverlay
    private lateinit var tapEffect: TapEffectOverlay

    /** 补发的点击被触发区自己接住了（FLAG_NOT_TOUCHABLE 尚未生效），需要重发。 */
    private var replayIntercepted = false

    private val runController get() = container.runController
    private val repository get() = container.repository

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "accessibility service connected")
        initializeOverlays()
    }

    private fun initializeOverlays() {
        // 部分 ROM 会对同一实例重复回调，先清掉上一次的悬浮窗和协程
        teardown()
        // 多屏设备上，标记、截图和手势必须使用同一个 display。
        val activeDisplay = if (Build.VERSION.SDK_INT >= 30) rootInActiveWindow?.window?.displayId else null
        val display = activeDisplay?.let { getSystemService(DisplayManager::class.java).getDisplay(it) }
        val overlayContext = if (display != null && Build.VERSION.SDK_INT >= 30) {
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        } else this
        overlays = OverlayManager(overlayContext)
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
            combine(container.controlBarEnabled, container.mainUiVisible) { _, _ -> }
                .collect { refreshControlBarVisibility() }
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
                        val triggers = repository.get(state.schemeId)?.triggers.orEmpty()
                        if (triggers.isNotEmpty()) triggerZone.show(triggers) else runController.disarm()
                    }
                    is RunState.Running -> if (state.fromTrigger) triggerZone.suspendTouch() else triggerZone.hide()
                    RunState.Idle -> triggerZone.hide()
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** 运行中的每一次点击（含触发补发）都先检测，不让后台监测与点击并行。 */
    suspend fun tapForRun(x: Int, y: Int, durationMs: Long): Boolean {
        val state = runController.state.value as? RunState.Running ?: return false
        val check = state.panelCheck?.takeIf { it.enabled }
        if (check != null) {
            try {
                PanelVisibilityGate {
                    val screen = overlays.screen
                    check(screen.width == check.screenWidth && screen.height == check.screenHeight) {
                        "屏幕尺寸已改变（${screen.width}×${screen.height}，参考 ${check.screenWidth}×${check.screenHeight}），请重新框选检测区域"
                    }
                    controlBar.bounds()?.let { bar ->
                        check(!Rect.intersects(bar, Rect(check.left, check.top, check.right, check.bottom))) {
                            "悬浮栏挡住检测区域，请拖开后重新开始"
                        }
                    }
                    capturePanelFrame().use { frame ->
                        check(frame.packageName == check.packageName) { "当前应用已切换" }
                        frame.matches(check)
                    }
                }.awaitVisible()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = if (e is RunAborted) e.message!! else "检测失败，已停止：${e.message ?: "无法读取画面"}"
                toast(message)
                throw RunAborted(message)
            }
        }
        currentCoroutineContext().ensureActive()
        return gestures.tap(x, y, durationMs)
    }

    private suspend fun capturePanelFrame(): PanelScreenshot.Frame {
        check(Build.VERSION.SDK_INT >= 30) { "面板检测需要 Android 11 或以上" }
        return try {
            panelScreenshot!!.capture(overlays.displayId)
        } catch (_: TimeoutCancellationException) {
            throw IllegalStateException("截图超时")
        }
    }

    private suspend fun capturePanel(region: Rect): PanelCheck {
        return capturePanelFrame().use { frame ->
            val colors = frame.sample(region)
            check(PanelMatcher.hasDetail(colors)) { "选区缺少特征，请框选标题文字或图标" }
            val screen = overlays.screen
            PanelCheck(region.left, region.top, region.right, region.bottom,
                screen.width, screen.height, frame.packageName, colors)
        }
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
        editor?.dismiss()
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
        val triggers = scheme.triggers
        if (triggers.isEmpty()) {
            toast("该方案没有触发点，请先在编辑中添加")
            return
        }
        controlBar.bounds()?.let { bar ->
            val hit = triggers.indexOfFirst { t ->
                val r = overlays.dp(t.radiusDp)
                Rect.intersects(bar, Rect(t.x - r, t.y - r, t.x + r, t.y + r))
            }
            if (hit >= 0) {
                val which = if (triggers.size == 1) "触发区" else "触发点 ${triggerLabel(hit, triggers.size)}"
                toast("${which}与悬浮栏重叠，请先把悬浮栏拖开")
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
        ensureCurrentDisplay()
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
    private fun onTriggerTapped(index: Int, x: Int, y: Int) {
        val upAt = SystemClock.uptimeMillis()
        triggerZone.suspendTouch()
        replayIntercepted = false
        val started = runController.onTriggerTapped(
            index, x, y,
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
        // 没开始运行但仍在待命（触发点刚被改动）：状态不变不会刷新触发区，这里恢复成可触摸
        val state = runController.state.value
        if (!started && state is RunState.Armed) {
            repository.get(state.schemeId)?.triggers?.takeIf { it.isNotEmpty() }?.let(triggerZone::show)
        }
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
        ensureCurrentDisplay()
        val defaultName = "方案 ${repository.schemes.value.size + 1}"
        editor = MarkerEditorOverlay(this, overlays, scheme, defaultName, ::toast, gestures::tap,
            onCapturePanel = ::capturePanel,
        ) { result ->
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
        val visible = container.controlBarEnabled.value && editor == null && !container.mainUiVisible.value
        if (visible) controlBar.show() else controlBar.hide()
    }

    private fun ensureCurrentDisplay() {
        if (Build.VERSION.SDK_INT >= 30 && editor == null && runController.state.value == RunState.Idle) {
            val displayId = rootInActiveWindow?.window?.displayId ?: return
            if (displayId != overlays.displayId) initializeOverlays()
        }
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
