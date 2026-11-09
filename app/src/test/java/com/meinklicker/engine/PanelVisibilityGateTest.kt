package com.meinklicker.engine

import com.meinklicker.data.LoopConfig
import com.meinklicker.data.Scheme
import com.meinklicker.data.Step
import com.meinklicker.data.TriggerConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PanelVisibilityGateTest {
    @Test fun transientMismatchRechecksBeforeAllowingTap() = runTest {
        var reads = 0
        var taps = 0
        PanelVisibilityGate { ++reads == 2 }.awaitVisible()
        taps++
        assertEquals(2, reads)
        assertEquals(1, taps)
    }

    @Test fun disappearingPanelAbortsTriggerRunWithoutRearmingOrFurtherTaps() = runTest {
        var reads = 0
        var taps = 0
        val gate = PanelVisibilityGate { ++reads == 1 }
        val dispatcher = object : GestureDispatcher {
            override suspend fun tap(x: Int, y: Int, durationMs: Long): Boolean {
                gate.awaitVisible()
                taps++
                return true
            }
        }
        val scheme = Scheme("guarded", "guarded", 1080, 1920,
            triggers = listOf(TriggerConfig(10, 10, delayAfterMs = 0)),
            steps = listOf(Step(10, 10)), loop = LoopConfig(0), updatedAt = 0)
        val controller = RunController(ClickEngine(dispatcher), backgroundScope) { scheme }
        controller.arm(scheme.id)
        controller.onTriggerTapped(0, 10, 10)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, taps)
        assertEquals(3, reads)
        assertEquals(RunState.Idle, controller.state.value)
        advanceTimeBy(5_000)
        assertEquals(1, taps)
    }

    @Test fun stopWhileScreenshotIsPendingNeverDispatchesTap() = runTest {
        var taps = 0
        val gate = PanelVisibilityGate { awaitCancellation() }
        val task = launch {
            gate.awaitVisible()
            taps++
        }
        runCurrent()
        task.cancel()
        runCurrent()
        assertEquals(0, taps)
        assertTrue(task.isCancelled)
    }

    @Test fun failedCaptureCannotAllowATap() = runTest {
        var taps = 0
        try {
            PanelVisibilityGate { throw RunAborted("截图失败") }.awaitVisible()
            taps++
            fail("must abort")
        } catch (_: RunAborted) {
            assertEquals(0, taps)
        }
    }

    @Test fun matcherToleratesSmallColorNoiseAndRejectsBackground() {
        val reference = List(PanelMatcher.SAMPLE_COUNT) { if (it % 4 == 0) 0xFF306080.toInt() else 0xFFF0F8FF.toInt() }
        val noisy = reference.map { it - 0x00020202 }
        assertTrue(PanelMatcher.hasDetail(reference))
        assertTrue(PanelMatcher.matches(reference, noisy))
        assertFalse(PanelMatcher.matches(reference, List(reference.size) { 0xFF5080A0.toInt() }))
        assertFalse(PanelMatcher.matches(reference, List(reference.size) { reference[1] }))
        assertFalse(PanelMatcher.matches(reference, emptyList()))
        assertFalse(PanelMatcher.hasDetail(List(reference.size) { 0xFFFFFFFF.toInt() }))
    }
}
