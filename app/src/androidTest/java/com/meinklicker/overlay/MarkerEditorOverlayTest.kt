package com.meinklicker.overlay

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meinklicker.R
import com.meinklicker.container
import com.meinklicker.data.PanelCheck
import com.meinklicker.data.Scheme
import com.meinklicker.data.Step
import com.meinklicker.data.TriggerConfig
import com.meinklicker.service.ClickAccessibilityService
import com.meinklicker.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MarkerEditorOverlayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun shell(command: String): String = automation.executeShellCommand(command)
        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() } }

    private fun await(message: String = "等待界面或服务超时", timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue(message + ": " + automation.windows.joinToString { "${it.type} ${it.root}" }, condition())
    }

    private fun tap(x: Int, y: Int) {
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(50)
        }
    }

    private fun button(text: String): AccessibilityNodeInfo? = automation.windows.firstNotNullOfOrNull { window ->
        window.root?.findAccessibilityNodeInfosByText(text)?.firstOrNull()
    }

    private fun described(description: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.contentDescription?.toString() == description) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::find) }
        }
        return automation.windows.firstNotNullOfOrNull { it.root?.let(::find) }
    }

    @Test fun docksAtSafeTopAndFoldsWithoutBlockingTheUnderlyingAppOrLosingEdits() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val previous = shell("settings get secure enabled_accessibility_services")
        val previousEnabled = shell("settings get secure accessibility_enabled")
        val context = instrumentation.targetContext
        val collapse = context.getString(R.string.editor_bar_collapse)
        val expand = context.getString(R.string.editor_bar_expand)
        val saved = AtomicReference<Scheme>()
        val hits = AtomicInteger()
        var editor: MarkerEditorOverlay? = null
        try {
            val component = "${context.packageName}/${ClickAccessibilityService::class.java.name}"
            val services = (previous.takeUnless { it == "null" || it.isEmpty() }?.split(":").orEmpty() + component).distinct()
            shell("settings put secure enabled_accessibility_services ${services.joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            await("无障碍服务未连接", timeoutMs = 20_000) { context.container.serviceFlow.value != null }
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val service = context.container.serviceFlow.value!!
                val manager = OverlayManager(service)
                val screen = manager.screen
                val insets = service.getSystemService(WindowManager::class.java).maximumWindowMetrics.windowInsets
                val safe = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                val first = Step(screen.width / 4, screen.height / 2, id = "fold-existing")
                val scheme = Scheme("fold-test", "fold", screen.width, screen.height,
                    steps = listOf(first), updatedAt = 0)
                fun open() {
                    scenario.onActivity {
                        editor = MarkerEditorOverlay(service, manager, scheme, "fold", {},
                            onPreviewTap = { _, _, _ -> true }, onFinish = { saved.set(it) },
                        ).also { it.show() }
                    }
                    await { described(collapse) != null }
                }
                fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
                fun tapNode(node: AccessibilityNodeInfo) = bounds(node).let { tap(it.centerX(), it.centerY()) }
                fun settleInput() = runBlocking {
                    withContext(Dispatchers.Main) { manager.awaitTouchReleased() }
                }
                scenario.onActivity { activity ->
                    activity.setContentView(FrameLayout(activity).apply {
                        setOnClickListener { hits.incrementAndGet() }
                    })
                }

                open()
                val initial = bounds(described(collapse)!!)
                assertTrue(initial.top >= safe.top)
                assertTrue(initial.top <= safe.top + manager.dp(24))
                settleInput()
                tapNode(button("加步骤")!!)
                settleInput()
                await { button("⠿") != null }
                val drag = bounds(button("⠿")!!)
                shell("input swipe ${drag.centerX()} ${drag.centerY()} ${drag.centerX()} ${drag.centerY() + manager.dp(145)} 350")
                await { described(collapse)?.let { bounds(it).top > initial.top + manager.dp(80) } == true }
                val dragged = bounds(described(collapse)!!)
                val uncovered = bounds(button("取消")!!)
                val hitsBeforeFold = hits.get()
                tapNode(described(collapse)!!)
                val leftGestureInset = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemGestures()).left
                val expectedLeft = maxOf(safe.left, leftGestureInset) + manager.dp(8)
                await("展开按钮未停靠到左侧安全区") {
                    val handle = described(expand)?.let(::bounds)
                    handle?.left == expectedLeft && handle.width() == manager.dp(48) && button("保存") == null
                }
                settleInput()
                tap(uncovered.centerX(), uncovered.centerY())
                await { hits.get() == hitsBeforeFold + 1 }

                tapNode(described(expand)!!)
                await { described(collapse)?.let { bounds(it) == dragged } == true && button("保存") != null }
                settleInput()
                tapNode(button("保存")!!)
                await { saved.get() != null }
                assertEquals(2, saved.get().steps.size)
                assertEquals(first, saved.get().steps.first())

                open()
                await { described(collapse)?.let { bounds(it) == initial } == true }
            }
        } finally {
            instrumentation.runOnMainSync { editor?.dismiss() }
            if (previous == "null") shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services '$previous'")
            if (previousEnabled == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $previousEnabled")
        }
    }

    @Test fun insertsClicksBeforeAndAfterSelectionAndCapturesTwoIndependentDetections() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val previous = shell("settings get secure enabled_accessibility_services")
        val previousEnabled = shell("settings get secure accessibility_enabled")
        val context = instrumentation.targetContext
        val result = AtomicReference<Scheme>()
        val captures = AtomicInteger()
        var editor: MarkerEditorOverlay? = null
        try {
            val component = "${context.packageName}/${ClickAccessibilityService::class.java.name}"
            val services = (previous.takeUnless { it == "null" || it.isEmpty() }?.split(":").orEmpty() + component).distinct()
            shell("settings put secure enabled_accessibility_services ${services.joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            await { context.container.serviceFlow.value != null }
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val service = context.container.serviceFlow.value!!
                val manager = OverlayManager(service)
                val screen = manager.screen
                val first = Step(screen.width / 4, screen.height / 3, id = "first")
                val second = Step(screen.width * 3 / 4, screen.height / 3, id = "second")
                val scheme = Scheme("insert-test", "insert", screen.width, screen.height,
                    steps = listOf(first, second), updatedAt = 0)
                scenario.onActivity {
                    editor = MarkerEditorOverlay(service, manager, scheme, "insert", {},
                        onPreviewTap = { _, _, _ -> true }, onCapturePanel = { region ->
                            val value = captures.incrementAndGet()
                            PanelCheck(region.left, region.top, region.right, region.bottom,
                                screen.width, screen.height, context.packageName, List(768) { it + value })
                        }, onFinish = { result.set(it) }).also { it.show() }
                }
                fun click(text: String) {
                    await("找不到 $text") { button(text) != null }
                    var node = button(text)!!
                    while (!node.isClickable && node.parent != null) node = node.parent
                    assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    instrumentation.waitForIdleSync()
                }
                fun configureRegion(top: Int) {
                    await { button("使用选区") != null }
                    shell("input swipe 100 $top 320 ${top + 100} 250")
                    click("使用选区")
                    await { button("加步骤") != null }
                }
                await { button("加步骤") != null }
                runBlocking { withContext(Dispatchers.Main) { manager.awaitTouchReleased() } }
                tap(first.x, first.y)
                await { button("第 1 步") != null }
                click("加步骤")
                click("在第 1 步前添加步骤")
                tap(first.x, first.y)
                await { button("第 2 步") != null }
                click("加步骤")
                click("在第 2 步后添加步骤")
                tap(first.x, first.y)
                await { button("第 2 步") != null }
                click("面板检测")
                click("在第 2 步前添加检测")
                configureRegion(screen.height / 6)
                tap(second.x, second.y)
                await { button("第 4 步") != null }
                click("面板检测")
                click("在第 4 步后添加检测")
                configureRegion(screen.height / 4)
                click("保存")
                await { result.get() != null }
                val saved = result.get()!!
                assertEquals(4, saved.steps.size)
                assertEquals(first, saved.steps[1])
                assertEquals(second, saved.steps[3])
                assertEquals(first.id, saved.panelDetections[0].branch.beforeStepId)
                assertEquals(second.id, saved.panelDetections[1].branch.afterStepId)
                assertEquals(listOf(1, 2), saved.panelDetections.map { it.reference!!.colors.first() })
                assertEquals(2, saved.panelDetections.map { it.reference!!.top }.distinct().size)
            }
        } finally {
            instrumentation.runOnMainSync { editor?.dismiss() }
            if (previous == "null") shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services '$previous'")
            if (previousEnabled == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $previousEnabled")
        }
    }

    @Test fun resizesTriggersIndependentlyWithoutMovingCentersAndCancelDiscardsChanges() {
        val previous = shell("settings get secure enabled_accessibility_services")
        val previousEnabled = shell("settings get secure accessibility_enabled")
        val context = instrumentation.targetContext
        var editor: MarkerEditorOverlay? = null
        var result: Scheme? = null
        var finished = false
        try {
            val component = "${context.packageName}/${ClickAccessibilityService::class.java.name}"
            val services = (previous.takeUnless { it == "null" || it.isEmpty() }?.split(":").orEmpty() + component).distinct()
            shell("settings put secure enabled_accessibility_services ${services.joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            await { context.container.serviceFlow.value != null }
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val service = context.container.serviceFlow.value!!
                val manager = OverlayManager(service)
                val screen = manager.screen
                val first = TriggerConfig(screen.width / 2, screen.height / 4, radiusDp = 40)
                val second = TriggerConfig(screen.width * 3 / 4, screen.height / 4, radiusDp = 150)
                val scheme = Scheme("resize-test", "resize", screen.width, screen.height,
                    triggers = listOf(first, second), steps = listOf(Step(100, 100)), updatedAt = 0)
                fun open() {
                    scenario.onActivity {
                        editor = MarkerEditorOverlay(service, manager, scheme, "resize", {},
                            onPreviewTap = { _, _, _ -> true },
                            onFinish = { result = it; finished = true }).also { it.show() }
                    }
                    await("编辑栏未显示") { button("加步骤") != null }
                    runBlocking { withContext(Dispatchers.Main) { manager.awaitTouchReleased() } }
                }
                fun click(text: String) {
                    await("找不到 $text") { button(text) != null }
                    var node = button(text)!!
                    while (!node.isClickable && node.parent != null) node = node.parent
                    assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    instrumentation.waitForIdleSync()
                }
                fun resizeToMinimum() {
                    tap(first.x, first.y)
                    await { described("触发区直径") != null }
                    val args = android.os.Bundle().apply {
                        putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 0f)
                    }
                    assertTrue(described("触发区直径")!!.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args))
                    await { button("触发区直径 20 dp") != null }
                    assertTrue(described("缩小触发区")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    assertTrue(described("放大触发区")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    await { button("触发区直径 22 dp") != null }
                }
                open()
                resizeToMinimum()
                click("关闭")
                tap(second.x, second.y)
                await { button("触发区直径 300 dp") != null }
                click("关闭")
                click("保存")
                await { finished }
                assertEquals(listOf(first.copy(radiusDp = 11), second), result!!.triggers)

                // 保存结果不回写原对象，取消第二轮编辑仍保持输入方案。
                finished = false
                result = null
                open()
                resizeToMinimum()
                click("关闭")
                click("取消")
                await { finished }
                assertNull(result)
                assertEquals(40, scheme.triggers.first().radiusDp)

                // 新建点采用直径 40dp，不继承旧触发点的半径缺省值。
                finished = false
                open()
                click("触发点")
                tap(screen.width / 2, screen.height / 3)
                await { button("触发区直径 40 dp") != null }
                click("关闭")
                click("保存")
                await { finished }
                assertEquals(20, result!!.triggers.last().radiusDp)
                scenario.onActivity { editor = null }
            }
        } finally {
            instrumentation.runOnMainSync { editor?.dismiss() }
            if (previous == "null") shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services '$previous'")
            if (previousEnabled == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $previousEnabled")
        }
    }

    @Test fun previewsExactlyOneTapThroughOverlappingMarkersAndRestoresEditor() {
        val previous = shell("settings get secure enabled_accessibility_services")
        val previousEnabled = shell("settings get secure accessibility_enabled")
        var editor: MarkerEditorOverlay? = null
        val hits = AtomicInteger()
        val previews = AtomicInteger()
        val messages = mutableListOf<String>()
        val context = instrumentation.targetContext
        try {
            val component = "${context.packageName}/${ClickAccessibilityService::class.java.name}"
            val services = (previous.takeUnless { it == "null" || it.isEmpty() }?.split(":").orEmpty() + component).distinct()
            shell("settings put secure enabled_accessibility_services ${services.joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            await { context.container.serviceFlow.value != null }
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var target: Button
                scenario.onActivity { activity ->
                    target = Button(activity).apply {
                        text = "下层目标"
                        setOnClickListener { hits.incrementAndGet() }
                    }
                    activity.setContentView(FrameLayout(activity).apply {
                        addView(target, FrameLayout.LayoutParams(400, 180, Gravity.CENTER))
                    })
                }
                instrumentation.waitForIdleSync()
                val location = IntArray(2)
                var x = 0
                var y = 0
                scenario.onActivity {
                    target.getLocationOnScreen(location)
                    x = location[0] + target.width / 2
                    y = location[1] + target.height / 2
                    val service = context.container.serviceFlow.value!!
                    val scheme = Scheme(
                        id = "preview-test", name = "preview", screenWidth = 1344, screenHeight = 2992,
                        triggers = listOf(TriggerConfig(x, y)),
                        steps = listOf(Step(x, y), Step(x, y)), pressMs = 80, updatedAt = 0,
                    )
                    editor = MarkerEditorOverlay(service, OverlayManager(service), scheme, "preview", messages::add,
                        onPreviewTap = { tapX, tapY, pressMs ->
                            assertEquals(80L, pressMs)
                            previews.incrementAndGet()
                            service.gestures.tap(tapX, tapY, pressMs)
                        }, onFinish = {})
                    editor!!.show()
                }

                await("编辑栏未显示") { button("加步骤") != null }
                // 两次点击验证补发既能穿过重叠标记，也能在恢复编辑后再次使用。
                repeat(2) { index ->
                    tap(x, y)
                    await("第 ${index + 1} 次打开参数面板失败；下层点击 ${hits.get()} 次") { button("点击一次") != null }
                    assertTrue(button("点击一次")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    await { hits.get() == index + 1 }
                    await { button("加步骤") != null }
                    instrumentation.waitForIdleSync()
                }
                assertEquals(2, hits.get())
                assertEquals(2, previews.get())
                assertTrue(messages.isEmpty())
                scenario.onActivity { editor!!.dismiss(); editor = null }
            }
        } finally {
            instrumentation.runOnMainSync { editor?.dismiss() }
            if (previous == "null") shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services '$previous'")
            if (previousEnabled == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $previousEnabled")
        }
    }
}
