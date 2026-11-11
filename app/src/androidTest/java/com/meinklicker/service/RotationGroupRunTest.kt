package com.meinklicker.service

import android.app.UiAutomation
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meinklicker.container
import com.meinklicker.data.LoopConfig
import com.meinklicker.data.RotationGroup
import com.meinklicker.data.Scheme
import com.meinklicker.data.SchemeRepository
import com.meinklicker.data.Step
import com.meinklicker.data.TriggerConfig
import com.meinklicker.engine.RunState
import com.meinklicker.ui.MainActivity
import com.meinklicker.util.ScreenInfo
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class RotationGroupRunTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val context = instrumentation.targetContext

    private fun shell(command: String) = automation.executeShellCommand(command).let {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }

    private fun tap(x: Int, y: Int) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(50)
        }
    }

    @Test fun realTriggersAndDirectRunRotateOneMemberAndPersistAfterEachConfirmation() {
        val previous = shell("settings get secure enabled_accessibility_services")
        val previousEnabled = shell("settings get secure accessibility_enabled")
        val selection = context.container.selectedSchemeId.value
        val component = "${context.packageName}/${ClickAccessibilityService::class.java.name}"
        val services = previous.takeUnless { it == "null" || it.isEmpty() }?.split(":").orEmpty()
        val id = "rotation-gesture-test-${UUID.randomUUID()}"
        val events = Collections.synchronizedList(mutableListOf<String>())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val buttons = mutableMapOf<String, Button>()
                scenario.onActivity { activity ->
                    val column = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                    for (key in listOf("join1", "join2", "before", "a", "b", "c", "send", "after")) {
                        val button = Button(activity).apply {
                            text = key
                            setOnClickListener { events += key }
                        }
                        buttons[key] = button
                        column.addView(button, LinearLayout.LayoutParams(500, 120))
                    }
                    activity.setContentView(FrameLayout(activity).apply {
                        addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                    })
                }
                instrumentation.waitForIdleSync()
                shell("settings put secure enabled_accessibility_services ${(services + component).distinct().joinToString(":")}")
                shell("settings put secure accessibility_enabled 1")
                await("无障碍服务未连接") { context.container.serviceFlow.value != null }
                val service = context.container.serviceFlow.value!!
                // 本测试无需截图；由 Activity 验证焦点，避免依赖 UiAutomation 下服务的节点缓存。
                scenario.onActivity { assertTrue("目标窗口未激活", it.hasWindowFocus()) }
                val points = mutableMapOf<String, Pair<Int, Int>>()
                lateinit var scheme: Scheme
                scenario.onActivity { activity ->
                    buttons.forEach { (key, button) ->
                        val position = IntArray(2)
                        button.getLocationOnScreen(position)
                        points[key] = position[0] + button.width / 2 to position[1] + button.height / 2
                    }
                    val screen = ScreenInfo.realSize(activity)
                    scheme = Scheme(id, "rotation gesture test", screen.width, screen.height,
                        triggers = listOf("join1", "join2").map { key ->
                            val point = points.getValue(key)
                            TriggerConfig(point.first, point.second, radiusDp = 16, delayAfterMs = 50)
                        },
                        steps = listOf("before", "a", "b", "c", "send", "after").map { key ->
                            val point = points.getValue(key)
                            Step(point.first, point.second, 100, key)
                        },
                        loop = LoopConfig(0, 9999), rotationGroup = RotationGroup(listOf("a", "b", "c"), "send", "a"),
                        updatedAt = 0)
                }
                runBlocking { context.container.repository.save(scheme) }
                instrumentation.runOnMainSync { context.container.selectScheme(id); service.onRun() }
                await("直接运行未完成：$events") { events.size == 4 && context.container.runController.state.value == RunState.Idle }
                assertEquals(listOf("before", "a", "send", "after"), events.toList())
                fun nextFromDisk() = SchemeRepository(File(context.filesDir, "schemes.json")).get(id)!!.rotationGroup!!.nextStepId
                assertEquals("b", nextFromDisk())

                instrumentation.runOnMainSync { service.onArm() }
                await("未进入待命") { context.container.runController.state.value == RunState.Armed(id) }
                SystemClock.sleep(300)
                for ((index, trigger) in listOf("join1", "join2").withIndex()) {
                    val point = points.getValue(trigger)
                    tap(point.first, point.second)
                    val count = 4 + (index + 1) * 5
                    await("触发 $trigger 未完成：$events") {
                        events.size == count && context.container.runController.state.value == RunState.Armed(id)
                    }
                    assertEquals(if (index == 0) "c" else "a", nextFromDisk())
                    SystemClock.sleep(300)
                }
                assertEquals(listOf("before", "a", "send", "after",
                    "join1", "before", "b", "send", "after",
                    "join2", "before", "c", "send", "after"), events.toList())
                instrumentation.runOnMainSync { context.container.runController.disarm(); service.onRun() }
                await("回到首项后直接运行未完成") { events.size == 18 && context.container.runController.state.value == RunState.Idle }
                assertEquals(listOf("before", "a", "send", "after"), events.toList().takeLast(4))
                assertEquals("b", nextFromDisk())
            }
        } finally {
            runBlocking {
                withContext(Dispatchers.Main) {
                    context.container.runController.reset()
                    context.container.repository.delete(id)
                    context.container.selectScheme(selection)
                }
            }
            if (previous == "null") shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services '$previous'")
            if (previousEnabled == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $previousEnabled")
        }
    }
}
