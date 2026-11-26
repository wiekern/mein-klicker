package com.meinklicker.engine

import com.meinklicker.data.LoopConfig
import com.meinklicker.data.PanelAction
import com.meinklicker.data.PanelBranch
import com.meinklicker.data.PanelCheck
import com.meinklicker.data.PanelDetection
import com.meinklicker.data.RotationGroup
import com.meinklicker.data.Scheme
import com.meinklicker.data.Step
import com.meinklicker.data.TriggerConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PanelBranchTest {
    private val reference = PanelCheck(0, 0, 100, 80, 1080, 2400, "example.app", List(768) { it })
    private val branch = PanelBranch("after", "a", "b", delayBeforeMs = 50)
    private val scheme = Scheme("branch", "branch", 1080, 2400,
        triggers = listOf(TriggerConfig(90, 90, delayAfterMs = 0)),
        steps = listOf(Step(1, 1, 10, "a"), Step(2, 2, 20, "before"), Step(3, 3, 30, "after"), Step(4, 4, 40, "b")),
        loop = LoopConfig(2, 100), panelCheck = reference, panelBranch = branch, updatedAt = 0)

    @Test fun eachRoundRunsOnlyItsSelectedBranchAndThenResumesOrdinarySteps() = runTest {
        val fake = FakeGestureDispatcher(testScheduler)
        val checks = mutableListOf<Long>()
        val results = listOf(true, false).iterator()
        ClickEngine(fake) {
            assertEquals(reference, it)
            checks += testScheduler.currentTime
            results.next()
        }.runLoop(scheme)
        assertEquals(listOf(70L, 280L), checks)
        assertEquals(listOf(2, 1, 3, 2, 4, 3), fake.taps.map { it.x })
        assertEquals(listOf(20L, 80L, 110L, 230L, 320L, 350L), fake.taps.map { it.atMs })
    }

    @Test fun eachDetectionUsesItsOwnReferenceAndRunsAtItsConfiguredPosition() = runTest {
        val fake = FakeGestureDispatcher(testScheduler)
        val second = reference.copy(left = 25)
        val third = reference.copy(left = 35)
        val references = mutableListOf<Int>()
        val multiple = scheme.copy(loop = LoopConfig(), panelBranch = null, panelDetections = listOf(
            PanelDetection("after", second, branch.copy(beforeStepId = null, afterStepId = "after")),
            PanelDetection("before", reference, branch.copy(beforeStepId = "before")),
            PanelDetection("same-gap", third, branch.copy(beforeStepId = null, afterStepId = "after")),
        ))
        ClickEngine(fake) { check ->
            references += check.left
            check.left != second.left
        }.runLoop(multiple)
        assertEquals(listOf(0, 25, 35), references)
        assertEquals(listOf(1, 2, 3, 4, 1), fake.taps.map { it.x })
    }

    @Test fun missingPanelAfterTriggerReplaysTriggerAndRunsBThenRearms() = runTest {
        val fake = FakeGestureDispatcher(testScheduler)
        var reads = 0
        val configured = scheme.copy(loop = LoopConfig(), panelBranch = branch.copy(beforeStepId = null))
        val controller = RunController(ClickEngine(fake) { PanelVisibilityGate { reads++; false }.isVisible() }, this) { configured }
        assertTrue(controller.arm(configured.id))
        assertTrue(controller.onTriggerTapped(0, 90, 90))
        assertNull((controller.state.value as RunState.Running).panelCheck)
        advanceUntilIdle()
        assertEquals(2, reads)
        assertEquals(listOf(90, 2, 3, 4), fake.taps.map { it.x })
        assertEquals(RunState.Armed(configured.id), controller.state.value)
    }

    @Test fun matchingStopBranchEndsTheRunWithoutFinishingTheRoundOrRearming() = runTest {
        val fake = FakeGestureDispatcher(testScheduler)
        val messages = mutableListOf<String>()
        val results = listOf(false, true).iterator()
        val configured = scheme.copy(panelCheck = null, panelBranch = null, loop = LoopConfig(5, 100),
            panelDetections = listOf(PanelDetection("stop", reference, PanelBranch(beforeStepId = "after",
                presentAction = PanelAction.STOP, absentAction = PanelAction.CONTINUE))))
        val controller = RunController(ClickEngine(fake) { results.next() }, this,
            onError = { messages += it }) { configured }
        assertTrue(controller.arm(configured.id))
        assertTrue(controller.onTriggerTapped(0, 90, 90))
        advanceUntilIdle()

        // 第一轮未命中，继续执行全部普通点位。第二轮命中，跳过余下步骤和轮次。
        assertEquals(listOf(90, 1, 2, 3, 4, 1, 2), fake.taps.map { it.x })
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("停止执行"))
        assertEquals(RunState.Idle, controller.state.value)
    }

    @Test fun endRoundSkipsRemainingActionsAndWaitsOnlyWhenAnotherRoundExists() = runTest {
        val fake = FakeGestureDispatcher(testScheduler)
        val checks = mutableListOf<Pair<Int, Long>>()
        val waits = mutableListOf<Pair<Int, Long>>()
        val followup = reference.copy(left = 10)
        val outcomes = listOf(false, true, false).iterator()
        val configured = scheme.copy(panelCheck = null, panelBranch = null, loop = LoopConfig(3, 100),
            panelDetections = listOf(
                PanelDetection("end", reference, PanelBranch(afterStepId = "a", delayBeforeMs = 50,
                    presentAction = PanelAction.CONTINUE, absentAction = PanelAction.END_ROUND)),
                PanelDetection("same-position", followup, PanelBranch(beforeStepId = "before", delayBeforeMs = 5,
                    presentAction = PanelAction.CONTINUE, absentAction = PanelAction.STOP)),
            ))
        ClickEngine(fake) { check ->
            checks += check.left to testScheduler.currentTime
            check == followup || outcomes.next()
        }.runLoop(configured, onRoundWait = { nextRound, interval ->
            assertEquals(100L, interval)
            waits += nextRound to testScheduler.currentTime
        })

        assertEquals(listOf(1, 1, 2, 3, 4, 1), fake.taps.map { it.x })
        assertEquals(listOf(10L, 170L, 245L, 275L, 315L, 425L), fake.taps.map { it.atMs })
        assertEquals(listOf(0 to 60L, 0 to 220L, 10 to 225L, 0 to 475L), checks)
        assertEquals(listOf(2 to 60L, 3 to 315L), waits)
        assertEquals(475L, testScheduler.currentTime)
    }

    @Test fun endRoundBeforeRotationConfirmationRearmsWithoutAdvancingTheGroup() = runTest {
        val fake = FakeGestureDispatcher(testScheduler)
        var commits = 0
        var checks = 0
        val configured = scheme.copy(panelCheck = null, panelBranch = null, loop = LoopConfig(0, 100),
            rotationGroup = RotationGroup(listOf("a", "before"), "after", "a"),
            panelDetections = listOf(PanelDetection("end", reference, PanelBranch(beforeStepId = "after",
                presentAction = PanelAction.END_ROUND, absentAction = PanelAction.CONTINUE))))
        val controller = RunController(ClickEngine(fake) { checks++; true }, this,
            advanceRotation = { commits++ }) { configured }
        assertTrue(controller.arm(configured.id))
        assertTrue(controller.onTriggerTapped(0, 90, 90))
        advanceUntilIdle()

        assertEquals(listOf(90, 1), fake.taps.map { it.x })
        assertEquals(1, checks)
        assertEquals(0, commits)
        assertEquals(RunState.Armed(configured.id), controller.state.value)
    }

    @Test fun missingPanelCanStopBeforeTheFirstTapAndSkipLaterDetections() = runTest {
        val fake = FakeGestureDispatcher(testScheduler)
        val stop = PanelBranch(beforeStepId = "a", presentAction = PanelAction.CONTINUE, absentAction = PanelAction.STOP)
        val configured = scheme.copy(panelCheck = null, panelBranch = null, panelDetections = listOf(
            PanelDetection("stop", reference, stop),
            PanelDetection("later", reference, stop.copy(beforeStepId = "after")),
        ))
        var reads = 0
        val controller = RunController(ClickEngine(fake) { reads++; false }, this) { configured }
        assertTrue(controller.run(configured.id))
        advanceUntilIdle()

        assertEquals(1, reads)
        assertTrue(fake.taps.isEmpty())
        assertEquals(RunState.Idle, controller.state.value)
    }

    @Test fun screenshotFailureNeverRunsEitherBranchAndExitsStandby() = runTest {
        val fake = FakeGestureDispatcher(testScheduler)
        val errors = mutableListOf<String>()
        val controller = RunController(ClickEngine(fake) { throw RunAborted("截图失败") }, this,
            onError = { errors += it }) { scheme.copy(panelBranch = branch.copy(beforeStepId = "a")) }
        assertTrue(controller.arm(scheme.id))
        assertTrue(controller.onTriggerTapped(0, 90, 90))
        advanceUntilIdle()
        assertEquals(listOf(90), fake.taps.map { it.x })
        assertEquals(listOf("截图失败"), errors)
        assertEquals(RunState.Idle, controller.state.value)
    }

    @Test fun stoppingWhileDetectingDoesNotRunEitherBranch() = runTest {
        val fake = FakeGestureDispatcher(testScheduler)
        val configured = scheme.copy(panelBranch = branch.copy(beforeStepId = "a", delayBeforeMs = 0))
        val controller = RunController(ClickEngine(fake) { awaitCancellation() }, this) { configured }
        assertTrue(controller.run(configured.id))
        runCurrent()
        assertTrue((controller.state.value as RunState.Running).checkingPanel)
        controller.stop()
        advanceUntilIdle()
        assertTrue(fake.taps.isEmpty())
        assertEquals(RunState.Idle, controller.state.value)
    }

    @Test fun invalidReferencesBlockRunningAndArmingBeforeAnyTap() = runTest {
        val fake = FakeGestureDispatcher(testScheduler)
        val errors = mutableListOf<String>()
        val invalidSchemes = listOf(
            scheme.copy(panelCheck = null),
            scheme.copy(panelBranch = branch.copy(presentStepId = "unknown")),
            scheme.copy(panelBranch = branch.copy(beforeStepId = "unknown")),
            scheme.copy(panelBranch = branch.copy(presentStepId = "b")),
            scheme.copy(rotationGroup = RotationGroup(listOf("a", "before"), "after", "a")),
        )
        for (invalid in invalidSchemes) {
            val controller = RunController(ClickEngine(fake), this, onError = { errors += it }) { invalid }
            assertFalse(controller.run(scheme.id))
            assertFalse(controller.arm(scheme.id))
        }
        assertEquals(invalidSchemes.size * 2, errors.size)
        assertTrue(fake.taps.isEmpty())
    }
}
