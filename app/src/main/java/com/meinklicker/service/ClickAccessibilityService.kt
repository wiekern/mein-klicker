package com.meinklicker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.meinklicker.TAG
import com.meinklicker.container
import com.meinklicker.engine.GestureDispatcher
import com.meinklicker.engine.RunState
import com.meinklicker.overlay.ControlBarOverlay
import com.meinklicker.overlay.OverlayManager
import com.meinklicker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 核心宿主：托管所有悬浮窗，执行手势，把用户动作转给 RunController。 */
class ClickAccessibilityService : AccessibilityService(), ControlBarOverlay.Actions {

    val gestures: GestureDispatcher by lazy { AccessibilityGestureDispatcher(this) }

    private var scope: CoroutineScope? = null
    private lateinit var overlays: OverlayManager
    private lateinit var controlBar: ControlBarOverlay

    /** 急停时吞掉的音量下键，对应的 UP 也要吞掉。 */
    private var swallowVolumeUp = false

    private val runController get() = container.runController
    private val repository get() = container.repository

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "accessibility service connected")
        overlays = OverlayManager(this)
        controlBar = ControlBarOverlay(this, overlays, this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        container.attachService(this)

        scope.launch {
            container.controlBarEnabled.collect { if (it) controlBar.show() else controlBar.hide() }
        }
        scope.launch {
            combine(runController.state, repository.schemes, container.selectedSchemeId) { state, _, id ->
                state to id?.let(repository::get)
            }.collect { (state, scheme) -> controlBar.render(state, scheme) }
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
        overlays.removeAll()
        scope.cancel()
    }

    // ---- 悬浮栏动作 ----

    override fun onRun() {
        val scheme = container.selectedSchemeId.value?.let(repository::get)
        if (scheme == null || scheme.steps.isEmpty()) {
            toast("请先选择一个有步骤的方案")
            return
        }
        controlBar.bounds()?.let { bar ->
            val hit = scheme.steps.indexOfFirst { bar.contains(it.x, it.y) }
            if (hit >= 0) {
                toast("第 ${hit + 1} 步落在悬浮栏上，请先把悬浮栏拖开")
                return
            }
        }
        val screen = overlays.screen
        if (scheme.screenWidth != screen.width || scheme.screenHeight != screen.height) {
            toast("方案分辨率 ${scheme.screenWidth}×${scheme.screenHeight} 与当前屏幕不一致，位置可能偏移")
        }
        runController.run(scheme.id)
    }

    override fun onStop() = runController.stop()

    override fun onOpenMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
