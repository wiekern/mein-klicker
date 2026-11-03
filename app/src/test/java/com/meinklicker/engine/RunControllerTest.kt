package com.meinklicker.engine

import com.meinklicker.data.LoopConfig
import com.meinklicker.data.Scheme
import com.meinklicker.data.Step
import com.meinklicker.data.TriggerConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunControllerTest {

    private val trigger = TriggerConfig(x = 540, y = 1800, delayAfterMs = 100)
    private val trigger2 = TriggerConfig(x = 100, y = 300, delayAfterMs = 500)
    private val schemes = mutableMapOf(
        "direct" to Scheme(
            id = "direct", name = "d", screenWidth = 1080, screenHeight = 2400,
            steps = listOf(Step(1, 1, 100), Step(2, 2, 100)), loop = LoopConfig(count = 2),
            updatedAt = 0,
        ),
        "trig" to Scheme(
            id = "trig", name = "t", screenWidth = 1080, screenHeight = 2400, triggers = listOf(trigger, trigger2),
            steps = listOf(Step(1, 1, 100), Step(2, 2, 100)), loop = LoopConfig(count = 3),
            updatedAt = 0,
        ),
    )

    private fun TestScope.setup(): Pair<RunController, FakeGestureDispatcher> {
        val fake = FakeGestureDispatcher(testScheduler)
        return RunController(ClickEngine(fake), backgroundScope) { schemes[it] } to fake
    }

    /** backgroundScope 里的任务不会被 advanceUntilIdle 推进，这里显式推进足够长的虚拟时间。 */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    @Test
    fun directRunReturnsToIdle() = runTest {
        val (rc, fake) = setup()
        assertTrue(rc.run("direct"))
        runCurrent()
        assertTrue(rc.state.value is RunState.Running)
        assertFalse(rc.run("direct"))

        settle()
        assertEquals(RunState.Idle, rc.state.value)
        assertEquals(4, fake.taps.size)
    }

    @Test
    fun progressIsReported() = runTest {
        val (rc, _) = setup()
        rc.run("direct")
        advanceTimeBy(150)
        runCurrent()
        assertEquals(RunState.Running("direct", round = 1, step = 2, fromTrigger = false), rc.state.value)
    }

    @Test
    fun triggerTapsOnceLoopsNTimesThenReArms() = runTest {
        val (rc, fake) = setup()
        assertTrue(rc.arm("trig"))
        assertEquals(RunState.Armed("trig"), rc.state.value)

        var prepared = false
        assertTrue(rc.onTriggerTapped(0, 530, 1810) { prepared = true })
        settle()

        assertTrue(prepared)
        val triggerTaps = fake.taps.filter { it.x == 530 && it.y == 1810 }
        assertEquals(1, triggerTaps.size)
        assertEquals(FakeGestureDispatcher.Tap(530, 1810, 0), fake.taps.first())
        // 循环体 2 步 × 3 轮
        assertEquals(1 + 6, fake.taps.size)
        // 触发补发后等待 delayAfterMs，再等第一步的 delayBeforeMs
        assertEquals(200L, fake.taps[1].atMs)
        assertEquals(RunState.Armed("trig"), rc.state.value)

        // 回到待命后可以再次触发
        assertTrue(rc.onTriggerTapped(0, 540, 1800))
        settle()
        assertEquals(14, fake.taps.size)
        assertEquals(RunState.Armed("trig"), rc.state.value)
    }

    @Test
    fun anyTriggerStartsRunWithItsOwnDelay() = runTest {
        val (rc, fake) = setup()
        rc.arm("trig")
        assertTrue(rc.onTriggerTapped(1, 110, 290))
        settle()

        assertEquals(FakeGestureDispatcher.Tap(110, 290, 0), fake.taps.first())
        // 第 2 个触发点的 delayAfterMs（500）+ 第一步的 delayBeforeMs（100）
        assertEquals(600L, fake.taps[1].atMs)
        assertEquals(1 + 6, fake.taps.size)
        assertEquals(RunState.Armed("trig"), rc.state.value)
    }

    @Test
    fun triggerIndexOutOfRangeIsIgnoredAndStaysArmed() = runTest {
        val (rc, fake) = setup()
        rc.arm("trig")
        assertFalse(rc.onTriggerTapped(2, 0, 0))
        settle()
        assertTrue(fake.taps.isEmpty())
        assertEquals(RunState.Armed("trig"), rc.state.value)
    }

    @Test
    fun triggerTapWhenSchemeLostTriggersGoesIdle() = runTest {
        val (rc, _) = setup()
        rc.arm("trig")
        schemes["trig"] = schemes.getValue("trig").copy(triggers = emptyList())
        assertFalse(rc.onTriggerTapped(0, 540, 1800))
        assertEquals(RunState.Idle, rc.state.value)
    }

    @Test
    fun armRequiresTriggerAndIdle() = runTest {
        val (rc, _) = setup()
        assertFalse(rc.arm("direct"))
        assertFalse(rc.arm("missing"))
        assertTrue(rc.arm("trig"))
        assertFalse(rc.run("direct"))
        assertFalse(rc.arm("trig"))
    }

    @Test
    fun triggerTapIgnoredWhenNotArmed() = runTest {
        val (rc, fake) = setup()
        assertFalse(rc.onTriggerTapped(0, 0, 0))
        settle()
        assertTrue(fake.taps.isEmpty())
    }

    @Test
    fun stopDuringTriggerRunReturnsToArmed() = runTest {
        val (rc, fake) = setup()
        schemes["trig"] = schemes.getValue("trig").copy(loop = LoopConfig(count = 0))
        rc.arm("trig")
        rc.onTriggerTapped(0, 540, 1800)
        advanceTimeBy(5_000)
        runCurrent()
        rc.stop()
        val tapsAtStop = fake.taps.size

        assertEquals(RunState.Armed("trig"), rc.state.value)
        advanceTimeBy(5_000)
        assertEquals(tapsAtStop, fake.taps.size)
        assertEquals(RunState.Armed("trig"), rc.state.value)
    }

    @Test
    fun stopDuringDirectInfiniteRunReturnsToIdle() = runTest {
        val (rc, fake) = setup()
        schemes["direct"] = schemes.getValue("direct").copy(loop = LoopConfig(count = 0))
        rc.run("direct")
        advanceTimeBy(5_050)
        runCurrent()
        rc.stop()
        val tapsAtStop = fake.taps.size

        advanceTimeBy(5_000)
        assertEquals(RunState.Idle, rc.state.value)
        assertEquals(tapsAtStop, fake.taps.size)
    }

    @Test
    fun disarmFromRunningGoesIdle() = runTest {
        val (rc, fake) = setup()
        rc.arm("trig")
        rc.onTriggerTapped(0, 540, 1800)
        advanceTimeBy(150)
        rc.disarm()
        settle()
        assertEquals(RunState.Idle, rc.state.value)
        assertEquals(1, fake.taps.size)
    }

    @Test
    fun runUsesLatestSchemeVersion() = runTest {
        val (rc, fake) = setup()
        schemes["direct"] = schemes.getValue("direct").copy(loop = LoopConfig(count = 1))
        rc.run("direct")
        settle()
        assertEquals(2, fake.taps.size)
    }

    @Test
    fun runRejectsSchemeWithoutSteps() = runTest {
        val (rc, _) = setup()
        schemes["direct"] = schemes.getValue("direct").copy(steps = emptyList())
        assertFalse(rc.run("direct"))
        assertEquals(RunState.Idle, rc.state.value)
    }
}
