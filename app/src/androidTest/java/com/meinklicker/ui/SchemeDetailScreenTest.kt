package com.meinklicker.ui

import android.graphics.Rect
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.meinklicker.container
import com.meinklicker.data.LoopConfig
import com.meinklicker.data.Scheme
import com.meinklicker.data.SchemeRepository
import com.meinklicker.data.Step
import com.meinklicker.data.TriggerConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class SchemeDetailScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val scheme = Scheme(
        id = "save-regression", name = "保存回归测试", screenWidth = 1080, screenHeight = 2400,
        steps = listOf(Step(100, 200, 100), Step(300, 400, 150)),
        triggers = listOf(TriggerConfig(50, 60)),
        loop = LoopConfig(2, 100), updatedAt = 0,
    )
    private val repository get() = compose.activity.container.repository

    @Before fun openScheme() {
        runBlocking { repository.save(scheme) }
        compose.onNodeWithText(scheme.name).performScrollTo().performClick()
    }

    @After fun cleanUp() {
        runBlocking { repository.delete(scheme.id) }
    }

    private fun replace(label: String, value: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        compose.onNode(hasSetTextAction() and hasText(label))
            .performTextReplacement(value)
    }

    @Test fun savesLatestNumericInputsAndReloadsThemFromDisk() {
        replace("循环次数", "7")
        replace("轮间隔 (ms)", "1234")
        replace("按压时长 (ms)", "80")
        replace("触发后延迟 (ms)", "450")
        replace("触发区半径 (dp)", "55")
        replace("第 1 步 (100, 200) 等待 ms", "600")
        replace("第 2 步 (300, 400) 等待 ms", "900")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { repository.get(scheme.id)?.updatedAt != 0L }

        val reloaded = SchemeRepository(File(compose.activity.filesDir, "schemes.json")).get(scheme.id)!!
        assertEquals(LoopConfig(7, 1234), reloaded.loop)
        assertEquals(80L, reloaded.pressMs)
        assertEquals(listOf(600L, 900L), reloaded.steps.map { it.delayBeforeMs })
        assertEquals(TriggerConfig(50, 60, 55, 450), reloaded.triggers.single())

        // 返回列表后重新进入，界面也应显示保存后的值。
        compose.onNodeWithText(scheme.name).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("循环次数") and hasText("7")).assertExists()
    }

    @Test fun laterStepsCanScrollAboveKeyboardWithoutHidingIt() {
        runBlocking {
            repository.save(scheme.copy(steps = List(12) { Step(100, 200, 100) }))
        }
        val list = compose.onNode(hasScrollToIndexAction())
        val firstLabel = "第 1 步 (100, 200) 等待 ms"
        list.performScrollToNode(hasText(firstLabel))
        compose.onNode(hasSetTextAction() and hasText(firstLabel)).performClick()
        val decor = compose.activity.window.decorView
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }

        for (step in 8..12) {
            val label = "第 $step 步 (100, 200) 等待 ms"
            list.performScrollToNode(hasText(label))
            val field = compose.onNode(hasSetTextAction() and hasText(label))
            // 在点选新输入框之前检查：靠滚动就应能完整露出，不依赖重新弹键盘。
            val bounds = field.fetchSemanticsNode().boundsInWindow
            compose.runOnIdle {
                val visible = Rect()
                val origin = IntArray(2)
                decor.getWindowVisibleDisplayFrame(visible)
                decor.getLocationOnScreen(origin)
                assertTrue("第 $step 步被输入法遮挡", bounds.bottom <= visible.bottom - origin[1])
                assertTrue(ViewCompat.getRootWindowInsets(decor)!!.isVisible(WindowInsetsCompat.Type.ime()))
            }
            field.performClick().performTextReplacement("$step" + "00")
        }
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { repository.get(scheme.id)?.updatedAt != 0L }
        assertEquals(listOf(800L, 900L, 1000L, 1100L, 1200L), repository.get(scheme.id)!!.steps.drop(7).map { it.delayBeforeMs })
    }
}
