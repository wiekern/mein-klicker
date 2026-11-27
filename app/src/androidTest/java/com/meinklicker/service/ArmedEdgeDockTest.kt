package com.meinklicker.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meinklicker.container
import com.meinklicker.data.PanelCheck
import com.meinklicker.data.Scheme
import com.meinklicker.data.Step
import com.meinklicker.data.TriggerConfig
import com.meinklicker.engine.RunState
import com.meinklicker.ui.MainActivity
import com.meinklicker.util.ScreenInfo
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmedEdgeDockTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val context = instrumentation.targetContext
    private val container = context.container

    @Test fun armReplacesObstructingBarRestoresItAndHandlesBlockedEdges() {
        val previousServices = shell("settings get secure enabled_accessibility_services")
        val previousEnabled = shell("settings get secure accessibility_enabled")
        val selection = container.selectedSchemeId.value
        val barEnabled = container.controlBarEnabled.value
        val component = "${context.packageName}/${ClickAccessibilityService::class.java.name}"
        val services = previousServices.takeUnless { it == "null" || it.isEmpty() }?.split(":").orEmpty()
        val id = "edge-dock-test-${UUID.randomUUID()}"
        val hits = AtomicInteger()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var width = 0
                var height = 0
                var displayId = 0
                var radius = 0
                scenario.onActivity { activity ->
                    activity.setContentView(FrameLayout(activity).apply {
                        setOnTouchListener { _, event ->
                            if (event.actionMasked == MotionEvent.ACTION_UP) hits.incrementAndGet()
                            true
                        }
                    })
                    val screen = ScreenInfo.realSize(activity)
                    width = screen.width
                    height = screen.height
                    displayId = activity.display!!.displayId
                    radius = (20 * activity.resources.displayMetrics.density).roundToInt()
                }
                shell("settings put secure enabled_accessibility_services ${(services + component).distinct().joinToString(":")}")
                shell("settings put secure accessibility_enabled 1")
                await("无障碍服务未连接") { container.serviceFlow.value != null }
                automation.serviceInfo = automation.serviceInfo.apply {
                    flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
                instrumentation.runOnMainSync {
                    container.setControlBarEnabled(true)
                    container.setMainUiVisible(false)
                }
                await("悬浮栏未显示") { findNode("👆") != null && findNode("⠿") != null }
                val originalArm = bounds("👆")
                val originalHandle = bounds("⠿")
                val trigger = TriggerConfig(originalArm.centerX(), originalArm.centerY(), radiusDp = 20)
                val triggerArea = Rect(trigger.x - radius, trigger.y - radius, trigger.x + radius, trigger.y + radius)
                val scheme = Scheme(id, "edge dock test", width, height, triggers = listOf(trigger),
                    steps = listOf(Step(originalHandle.centerX(), originalHandle.centerY(), delayBeforeMs = 10_000)), updatedAt = 0)
                saveAndSelect(scheme)
                await("待命按钮尚未启用") { findNode("👆")?.isEnabled == true }
                click("👆")
                await("冲突时应自动贴边并进入待命") {
                    container.runController.state.value == RunState.Armed(id) && findNode("退出待命") != null
                }
                val dock = bounds("退出待命")
                assertFalse(Rect.intersects(dock, triggerArea))
                assertFalse(dock.contains(scheme.steps.single().x, scheme.steps.single().y))
                assertFalse(findNode("⠿") != null)

                shell("input -d $displayId tap ${trigger.x} ${trigger.y}")
                await("触发点击未补发，或停止按钮未出现") { hits.get() == 1 && findNode("停止运行") != null }
                assertEquals(dock, bounds("停止运行"))
                click("停止运行")
                await("停止后应保留原贴边位置并回到待命") {
                    container.runController.state.value == RunState.Armed(id) && findNode("退出待命") != null
                }
                assertEquals(dock, bounds("退出待命"))

                val quick = scheme.copy(steps = listOf(scheme.steps.single().copy(delayBeforeMs = 100)))
                saveAndSelect(quick)
                shell("input -d $displayId tap ${trigger.x} ${trigger.y}")
                await("执行完一轮应回到原贴边位置") {
                    hits.get() == 3 && container.runController.state.value == RunState.Armed(id) && findNode("退出待命") != null
                }
                assertEquals(dock, bounds("退出待命"))
                click("退出待命")
                await("退出待命后应恢复悬浮栏") {
                    container.runController.state.value == RunState.Idle && findNode("⠿") != null && findNode("退出待命") == null
                }
                assertEquals(originalHandle, bounds("⠿"))

                // 触发区和点击点都在屏幕中间，检测区单独挡住左侧整条边。
                val unobstructed = scheme.copy(triggers = listOf(trigger.copy(x = width / 2, y = height / 2)),
                    steps = listOf(scheme.steps.single().copy(x = width / 2, y = height / 2)))
                val reference = PanelCheck(0, 0, width / 3, height, width, height, context.packageName, emptyList())
                saveAndSelect(unobstructed.copy(panelCheck = reference))
                click("👆")
                await("检测区域挡住左侧时应切换到右侧") { findNode("退出待命") != null }
                assertTrue(bounds("退出待命").left > width / 2)
                click("退出待命")
                await("悬浮栏未恢复") { findNode("⠿") != null && findNode("👆")?.isEnabled == true }

                saveAndSelect(unobstructed.copy(panelCheck = reference.copy(right = width)))
                click("👆")
                instrumentation.waitForIdleSync()
                assertEquals("两侧都被挡住时应保持空闲", RunState.Idle, container.runController.state.value)
                assertFalse(findNode("退出待命") != null)
                assertEquals(originalHandle, bounds("⠿"))

                saveAndSelect(unobstructed.copy(panelCheck = reference.copy(right = width, enabled = false)))
                click("👆")
                await("无冲突时应原地待命") { container.runController.state.value == RunState.Armed(id) }
                assertEquals(originalHandle, bounds("⠿"))
            }
        } finally {
            runBlocking {
                withContext(Dispatchers.Main) {
                    container.runController.reset()
                    container.repository.delete(id)
                    container.selectScheme(selection)
                    container.setControlBarEnabled(barEnabled)
                }
            }
            if (previousServices == "null") shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services '$previousServices'")
            if (previousEnabled == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $previousEnabled")
        }
    }

    private fun saveAndSelect(scheme: Scheme) = runBlocking {
        withContext(Dispatchers.Main) {
            container.repository.save(scheme)
            container.selectScheme(scheme.id)
        }
    }

    private fun click(label: String) {
        val node = checkNotNull(findNode(label)) { "找不到 $label" }
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun bounds(label: String) = Rect().also(checkNotNull(findNode(label))::getBoundsInScreen)

    private fun findNode(label: String): AccessibilityNodeInfo? = automation.windows.firstNotNullOfOrNull { window ->
        window.root?.findAccessibilityNodeInfosByText(label)?.firstOrNull {
            it.text?.toString() == label || it.contentDescription?.toString() == label
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        do {
            if (condition()) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue(message, condition())
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }
}
