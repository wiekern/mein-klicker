package com.meinklicker.service

import android.app.UiAutomation
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meinklicker.container
import com.meinklicker.data.LoopConfig
import com.meinklicker.data.PanelCheck
import com.meinklicker.data.Scheme
import com.meinklicker.data.Step
import com.meinklicker.engine.RunState
import com.meinklicker.engine.RunController
import com.meinklicker.engine.ClickEngine
import com.meinklicker.engine.GestureDispatcher
import com.meinklicker.engine.PanelVisibilityGate
import com.meinklicker.engine.PanelMatcher
import com.meinklicker.engine.RunAborted
import com.meinklicker.ui.MainActivity
import com.meinklicker.util.ScreenInfo
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PanelStopTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val context = instrumentation.targetContext

    private fun shell(command: String): String = automation.executeShellCommand(command).let {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }

    @Test fun realScreenshotsStopLoopWhenPanelDisappears() = runPanelTest(usePlatformGestures = false)

    @Test fun realGesturesStopWithoutClickingBackground() = runPanelTest(usePlatformGestures = true)

    private fun runPanelTest(usePlatformGestures: Boolean) {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val previous = shell("settings get secure enabled_accessibility_services")
        val previousEnabled = shell("settings get secure accessibility_enabled")
        val component = "${context.packageName}/${ClickAccessibilityService::class.java.name}"
        val otherServices = previous.takeUnless { it == "null" || it.isEmpty() }?.split(":").orEmpty().filter { it != component }
        shell("settings put secure enabled_accessibility_services '${otherServices.joinToString(":")}'")
        val hits = AtomicInteger()
        val backgroundHits = AtomicInteger()
        val id = "panel-stop-test-${java.util.UUID.randomUUID()}"
        val previousSelection = context.container.selectedSchemeId.value
        var controller: RunController? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var panel: FrameLayout
                lateinit var title: TextView
                lateinit var button: Button
                scenario.onActivity { activity ->
                    panel = FrameLayout(activity).apply {
                        setBackgroundColor(Color.WHITE)
                        isClickable = true
                        elevation = 24f
                    }
                    title = TextView(activity).apply {
                        text = "治疗伤兵 / PANEL"
                        textSize = 28f
                        setTextColor(Color.rgb(35, 75, 120))
                        gravity = Gravity.CENTER
                    }
                    panel.addView(title, FrameLayout.LayoutParams(-1, 120, Gravity.TOP))
                    button = Button(activity).apply {
                        text = "治疗"
                        setOnClickListener {
                            val count = hits.incrementAndGet()
                            text = if (count % 2 == 1) "联盟帮助" else "治疗"
                            if (count == 4) panel.visibility = View.GONE
                        }
                    }
                    panel.addView(button, FrameLayout.LayoutParams(400, 160, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
                    activity.setContentView(FrameLayout(activity).apply {
                        setBackgroundColor(Color.rgb(30, 80, 140))
                        addView(Button(activity).apply {
                            text = "背景（不应被点击）"
                            setOnClickListener { backgroundHits.incrementAndGet() }
                        }, FrameLayout.LayoutParams(-1, -1))
                        addView(panel, FrameLayout.LayoutParams(700, 600, Gravity.CENTER))
                    })
                }
                instrumentation.waitForIdleSync()
                // 模拟器可能在启动后把应用迁移到独立显示屏，等迁移和布局完成。
                SystemClock.sleep(1_500)
                // 先显示测试画面，再绑定服务，使副屏模拟器的点击与截图也选择该屏。
                val services = (otherServices + component).distinct()
                shell("settings put secure enabled_accessibility_services ${services.joinToString(":")}")
                shell("settings put secure accessibility_enabled 1")
                await("无障碍服务未连接") { context.container.serviceFlow.value != null }
                val service = context.container.serviceFlow.value!!
                await("当前应用窗口未就绪") { service.rootInActiveWindow?.packageName?.toString() == context.packageName }
                await("当前应用交互窗口未就绪") {
                    val displays = service.windowsOnAllDisplays
                    (0 until displays.size()).flatMap { displays.valueAt(it) }.any {
                        it.isActive && it.isFocused && it.root?.packageName?.toString() == context.packageName
                    }
                }
                val region = Rect()
                val location = IntArray(2)
                var displayId = 0
                var screenWidth = 0
                var screenHeight = 0
                scenario.onActivity {
                    displayId = it.display!!.displayId
                    val size = ScreenInfo.realSize(it)
                    screenWidth = size.width
                    screenHeight = size.height
                    title.getGlobalVisibleRect(region)
                    button.getLocationOnScreen(location)
                    location[0] += button.width / 2
                    location[1] += button.height / 2
                }
                runBlocking {
                    withContext(Dispatchers.Main) {
                        val capture = PanelScreenshot(service)
                        val check = capture.capture(displayId).use { frame ->
                            PanelCheck(region.left, region.top, region.right, region.bottom,
                                screenWidth, screenHeight, frame.packageName, frame.sample(region))
                        }
                        assertTrue("参考选区必须包含面板特征", PanelMatcher.hasDetail(check.colors))
                        // 参考截图使用独立实例，仍需遵守同一服务的系统截图频率限制。
                        delay(400)
                        val scheme = Scheme(id, "panel test", screenWidth, screenHeight,
                            steps = listOf(Step(location[0], location[1], 100)), loop = LoopConfig(0),
                            panelCheck = check, updatedAt = 0)
                        if (usePlatformGestures) {
                            context.container.repository.save(scheme)
                            context.container.selectScheme(id)
                            controller = context.container.runController
                            service.onRun()
                        } else {
                            // 使用真实无障碍截图，只替换手势输出，隔离模拟器的副屏输入限制。
                            val gate = PanelVisibilityGate {
                                try {
                                    capture.capture(displayId).use { it.matches(check) }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    throw RunAborted(e.message ?: "截图失败")
                                }
                            }
                            val dispatcher = object : GestureDispatcher {
                                override suspend fun tap(x: Int, y: Int, durationMs: Long): Boolean {
                                    gate.awaitVisible()
                                    if (panel.visibility == View.VISIBLE) button.performClick()
                                    else backgroundHits.incrementAndGet()
                                    return true
                                }
                            }
                            controller = RunController(ClickEngine(dispatcher), context.container.scope) { scheme }
                            controller!!.run(id)
                        }
                        assertTrue(controller!!.state.value is RunState.Running)
                    }
                }
                try {
                    await("面板未被点击四次并停止") {
                        hits.get() == 4 && controller!!.state.value == RunState.Idle
                    }
                } catch (e: AssertionError) {
                    throw AssertionError("hits=${hits.get()}, background=${backgroundHits.get()}, running=${controller?.state?.value is RunState.Running}", e)
                }
                SystemClock.sleep(800)
                assertEquals(4, hits.get())
                assertEquals("面板关闭后误点了背景", 0, backgroundHits.get())
            }
        } finally {
            runBlocking {
                withContext(Dispatchers.Main) {
                    context.container.runController.reset()
                    controller?.reset()
                    context.container.repository.delete(id)
                    context.container.selectScheme(previousSelection)
                }
            }
            if (previous == "null") shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services '$previous'")
            if (previousEnabled == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $previousEnabled")
        }
    }
}
