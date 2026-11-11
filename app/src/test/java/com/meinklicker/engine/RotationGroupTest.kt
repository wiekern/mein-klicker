package com.meinklicker.engine

import com.meinklicker.data.LoopConfig
import com.meinklicker.data.RotationGroup
import com.meinklicker.data.Scheme
import com.meinklicker.data.Step
import com.meinklicker.data.TriggerConfig
import com.meinklicker.data.reconcileRotation
import com.meinklicker.data.rotationError
import com.meinklicker.data.rotationMembers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RotationGroupTest {
    private fun scheme() = Scheme(
        id = "rotation", name = "rotation", screenWidth = 100, screenHeight = 100,
        triggers = listOf(TriggerConfig(90, 90, delayAfterMs = 0), TriggerConfig(80, 80, delayAfterMs = 0)),
        steps = listOf(Step(1, 1, 10, "before"), Step(2, 2, 20, "a"),
            Step(3, 3, 30, "b"), Step(4, 4, 40, "c"), Step(5, 5, 50, "send"),
            Step(6, 6, 60, "after")),
        loop = LoopConfig(0, 9999),
        rotationGroup = RotationGroup(listOf("a", "b", "c"), "send", "a"), updatedAt = 0,
    )

    @Test fun groupRunsOnceInOrderAndPreservesOriginalStepNumbersAndDelays() = runTest {
        val fake = FakeGestureDispatcher(testScheduler)
        val progress = mutableListOf<Int>()
        var commits = 0
        ClickEngine(fake).runLoop(scheme().copy(rotationGroup = scheme().rotationGroup!!.copy(nextStepId = "b")),
            onRotationCommitted = { commits++; assertEquals(5, fake.taps.last().x) },
            onProgress = { round, step -> assertEquals(1, round); progress += step })
        assertEquals(listOf(1, 3, 5, 6), fake.taps.map { it.x })
        assertEquals(listOf(10L, 40L, 90L, 150L), fake.taps.map { it.atMs })
        assertEquals(listOf(1, 3, 5, 6), progress)
        assertEquals(1, commits)
    }

    @Test fun directAndDifferentTriggersShareProgressAndWrap() = runTest {
        var current = scheme()
        val fake = FakeGestureDispatcher(testScheduler)
        val rc = RunController(ClickEngine(fake), backgroundScope, advanceRotation = { snapshot ->
            val members = snapshot.rotationMembers()
            val group = snapshot.rotationGroup!!
            val next = (members.indexOfFirst { it.id == group.nextStepId } + 1) % members.size
            current = current.copy(rotationGroup = group.copy(nextStepId = members[next].id))
        }) { current }
        assertTrue(rc.run(current.id))
        advanceTimeBy(1000); runCurrent()
        assertTrue(rc.arm(current.id))
        assertTrue(rc.onTriggerTapped(0, 90, 90))
        advanceTimeBy(1000); runCurrent()
        assertTrue(rc.onTriggerTapped(1, 80, 80))
        advanceTimeBy(1000); runCurrent()
        rc.disarm()
        assertTrue(rc.run(current.id))
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf(2, 3, 4, 2), fake.taps.map { it.x }.filter { it in 2..4 })
        assertEquals("b", current.rotationGroup!!.nextStepId)
    }

    @Test fun stopBeforeCommitKeepsCurrentButStopAfterCommitKeepsNext() = runTest {
        var current = scheme()
        val rc = RunController(ClickEngine(FakeGestureDispatcher(testScheduler)), backgroundScope,
            advanceRotation = { current = current.copy(rotationGroup = current.rotationGroup!!.copy(nextStepId = "b")) },
        ) { current }
        rc.run(current.id)
        advanceTimeBy(40); runCurrent() // selected a, waiting for send
        rc.stop()
        assertEquals("a", current.rotationGroup!!.nextStepId)
        rc.run(current.id)
        advanceTimeBy(81); runCurrent() // send completed, waiting for after
        rc.stop()
        assertEquals("b", current.rotationGroup!!.nextStepId)
        rc.reset()
        assertEquals("b", current.rotationGroup!!.nextStepId)
    }

    @Test fun stopDuringPersistenceFinishesCommitAndBlocksNewRun() = runTest {
        val release = CompletableDeferred<Unit>()
        var persisted = false
        val rc = RunController(ClickEngine(FakeGestureDispatcher(testScheduler)), backgroundScope,
            advanceRotation = { release.await(); persisted = true },
        ) { scheme() }
        rc.run("rotation")
        advanceTimeBy(100); runCurrent()
        assertTrue(rc.isCommitting)
        rc.stop()
        assertFalse(rc.run("rotation"))
        assertFalse(rc.arm("rotation"))
        release.complete(Unit)
        runCurrent()
        assertTrue(persisted)
        assertFalse(rc.isCommitting)
        assertEquals(RunState.Idle, rc.state.value)
    }

    @Test fun failedSelectionOrConfirmationStopsWithoutAdvancing() = runTest {
        for (failure in listOf(2, 5)) {
            val taps = mutableListOf<Int>()
            var commits = 0
            val errors = mutableListOf<String>()
            val gestures = object : GestureDispatcher {
                override suspend fun tap(x: Int, y: Int, durationMs: Long): Boolean {
                    taps += x
                    return x != failure
                }
            }
            val rc = RunController(ClickEngine(gestures), backgroundScope,
                advanceRotation = { commits++ }, onError = errors::add) { scheme() }
            rc.arm("rotation"); rc.onTriggerTapped(0, 90, 90)
            advanceTimeBy(1000); runCurrent()
            assertEquals(0, commits)
            assertEquals(failure, taps.last())
            assertEquals(RunState.Idle, rc.state.value)
            assertTrue(errors.isNotEmpty())
        }
    }

    @Test fun failedReplayAndGuardAbortDoNotReachConfirmation() = runTest {
        for (aborted in listOf(false, true)) {
            var commits = 0
            val gestures = object : GestureDispatcher {
                override suspend fun tap(x: Int, y: Int, durationMs: Long): Boolean {
                    if (aborted) throw RunAborted("panel gone")
                    return false
                }
            }
            val rc = RunController(ClickEngine(gestures), backgroundScope, advanceRotation = { commits++ }) { scheme() }
            rc.arm("rotation"); rc.onTriggerTapped(0, 90, 90)
            advanceTimeBy(1000); runCurrent()
            assertEquals(0, commits)
            assertEquals(RunState.Idle, rc.state.value)
        }
    }

    @Test fun persistenceFailureExitsArmedModeAndReportsCalibration() = runTest {
        val errors = mutableListOf<String>()
        val rc = RunController(ClickEngine(FakeGestureDispatcher(testScheduler)), backgroundScope,
            advanceRotation = { throw IllegalStateException("disk failure") }, onError = errors::add) { scheme() }
        rc.arm("rotation"); rc.onTriggerTapped(0, 90, 90)
        advanceTimeBy(1000); runCurrent()
        assertEquals(RunState.Idle, rc.state.value)
        assertTrue(errors.single().contains("校准"))
        assertFalse(rc.isCommitting)
    }

    @Test fun invalidConfigurationIsRejectedAndDeletionNeverReassignsByIndex() = runTest {
        val original = scheme()
        val remaining = original.copy(steps = original.steps.filter { it.id !in listOf("before", "a") }).reconcileRotation()
        assertEquals(listOf("b", "c"), remaining.rotationGroup!!.stepIds)
        assertEquals("b", remaining.rotationGroup!!.nextStepId)
        assertNull(remaining.rotationError())
        val invalid = remaining.copy(steps = remaining.steps.filter { it.id != "send" }).reconcileRotation()
        assertNotNull(invalid.rotationError())
        val rc = RunController(ClickEngine(FakeGestureDispatcher(testScheduler)), backgroundScope) { invalid }
        assertFalse(rc.run("rotation"))
        assertFalse(rc.arm("rotation"))
        assertNull(original.copy(steps = emptyList()).reconcileRotation().rotationGroup)
    }

    @Test fun pendingSettingsSaveBlocksTriggersAndRestoresArmedModeAfterFailure() = runTest {
        val rc = RunController(ClickEngine(FakeGestureDispatcher(testScheduler)), backgroundScope) { scheme() }
        val release = CompletableDeferred<Unit>()
        rc.arm("rotation")
        val save = backgroundScope.launch {
            try {
                rc.editSettings { release.await(); throw IllegalStateException("disk error") }
            } catch (_: IllegalStateException) { }
        }
        runCurrent()
        assertFalse(rc.onTriggerTapped(0, 90, 90))
        assertEquals(RunState.Armed("rotation"), rc.state.value)
        release.complete(Unit)
        runCurrent()
        assertTrue(save.isCompleted)
        assertTrue(rc.onTriggerTapped(1, 80, 80))
        rc.stop()
    }

    @Test fun editingCannotChangeAnActiveRun() = runTest {
        val rc = RunController(ClickEngine(FakeGestureDispatcher(testScheduler)), backgroundScope) { scheme() }
        rc.run("rotation")
        var edited = false
        try {
            rc.editSettings { edited = true }
            fail("must reject edits during a run")
        } catch (_: IllegalStateException) { }
        assertFalse(edited)
        rc.stop()
    }
}
