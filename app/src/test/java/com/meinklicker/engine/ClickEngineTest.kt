package com.meinklicker.engine

import com.meinklicker.data.LoopConfig
import com.meinklicker.data.Scheme
import com.meinklicker.data.Step
import com.meinklicker.data.TriggerConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClickEngineTest {

    private fun scheme(loop: LoopConfig, vararg steps: Step) = Scheme(
        id = "s", name = "s", screenWidth = 1080, screenHeight = 2400,
        steps = steps.toList(), loop = loop, updatedAt = 0,
    )

    @Test
    fun stepsRunInOrderWithDelays() = runTest {
        val fake = FakeGestureDispatcher(testScheduler)
        val s = scheme(LoopConfig(count = 1), Step(1, 1, 100), Step(2, 2, 250), Step(3, 3, 10))

        ClickEngine(fake).runLoop(s)

        assertEquals(
            listOf(
                FakeGestureDispatcher.Tap(1, 1, 100),
                FakeGestureDispatcher.Tap(2, 2, 350),
                FakeGestureDispatcher.Tap(3, 3, 360),
            ),
            fake.taps,
        )
    }

    @Test
    fun loopsNTimesWithInterval() = runTest {
        val fake = FakeGestureDispatcher(testScheduler)
        val s = scheme(LoopConfig(count = 3, intervalMs = 1000), Step(1, 1, 100), Step(2, 2, 100))
        val progress = mutableListOf<Pair<Int, Int>>()

        ClickEngine(fake).runLoop(s) { r, st -> progress += r to st }

        assertEquals(6, fake.taps.size)
        assertEquals(listOf(100L, 200L, 1300L, 1400L, 2500L, 2600L), fake.taps.map { it.atMs })
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1, 2 to 2, 3 to 1, 3 to 2), progress)
    }

    @Test
    fun infiniteLoopStopsWithinOneStep() = runTest {
        val fake = FakeGestureDispatcher(testScheduler)
        val s = scheme(LoopConfig(count = 0), Step(1, 1, 100), Step(2, 2, 100))
        val job = launch { ClickEngine(fake).runLoop(s) }

        advanceTimeBy(10_050)
        runCurrent()
        val before = fake.taps.size
        assertEquals(100, before)
        job.cancel()
        advanceTimeBy(10_000)

        assertTrue(job.isCancelled)
        assertEquals(before, fake.taps.size)
    }

    @Test
    fun emptyStepsReturnImmediately() = runTest {
        val fake = FakeGestureDispatcher(testScheduler)
        ClickEngine(fake).runLoop(scheme(LoopConfig(count = 0)))
        assertTrue(fake.taps.isEmpty())
    }

    @Test
    fun replayTriggerRetriesWhenAskedAtMostTwice() = runTest {
        val fake = FakeGestureDispatcher(testScheduler)
        var asks = 0
        // 第一次补发被"截获"，第二次成功
        ClickEngine(fake).replayTrigger(500, 600, TriggerConfig(1, 1, delayAfterMs = 0), retryIf = { ++asks == 1 })
        assertEquals(2, fake.taps.size)
        assertEquals(2, asks)

        fake.taps.clear()
        ClickEngine(fake).replayTrigger(500, 600, TriggerConfig(1, 1, delayAfterMs = 0), retryIf = { true })
        assertEquals(1 + ClickEngine.MAX_REPLAY_RETRIES, fake.taps.size)
    }

    @Test
    fun replayTriggerTapsOnceThenWaits() = runTest {
        val fake = FakeGestureDispatcher(testScheduler)
        ClickEngine(fake).replayTrigger(500, 600, TriggerConfig(1, 1, delayAfterMs = 150))
        assertEquals(listOf(FakeGestureDispatcher.Tap(500, 600, 0)), fake.taps)
        assertEquals(150, testScheduler.currentTime)
    }
}
