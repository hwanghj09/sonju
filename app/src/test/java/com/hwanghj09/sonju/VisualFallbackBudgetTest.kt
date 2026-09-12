package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.*
import com.hwanghj09.sonju.execution.ExecutionFailureReason
import com.hwanghj09.sonju.logging.ProcessLogRepository
import com.hwanghj09.sonju.logging.TraceStep
import com.hwanghj09.sonju.skill.InMemorySkillRepository
import com.hwanghj09.sonju.verifier.VerificationResult
import org.junit.Assert.*
import org.junit.Test

class VisualFallbackBudgetTest {
    private val first = screen("메뉴")
    private val second = screen("다음 페이지")
    private val command = "테마를 바꿔줘"
    private val plan = AgentPlan(command, "선택", RiskLevel.LOW, .95,
        listOf(AgentAction(ActionType.CLICK, "선택", "없는 항목")), PlanSource.OPENAI_STRUCTURE)
    private val runtime = SonjuAgentRuntime.createForTest(InMemorySkillRepository(), object : ProcessLogRepository {
        override fun append(step: TraceStep) = Unit
        override fun exportRedacted(): List<String> = emptyList()
    })

    @Test fun staleSkillsAndInvalidCompletionDoNotSpendImageBudget() {
        val run = session()
        run.recordAccessibilityModelAttempt(first)
        repeat(4) { runtime.recordPlanningFailure(plan, run, "완료 근거 또는 저장된 경로 불일치") }
        assertTrue(run.aiRecoveryRequested)
        assertFalse(run.visualFallbackActive)
        assertFalse(run.reserveVisualFallback(first, first.screenFingerprint()))
    }

    @Test fun accessibilityModelMustTryThisScreenBeforeVision() {
        val run = session()
        repeat(2) { run.requestAiRecovery("missing", true, first) }
        assertFalse(run.reserveVisualFallback(first, first.screenFingerprint()))
        run.recordAccessibilityModelAttempt(first)
        assertTrue(run.reserveVisualFallback(first, first.screenFingerprint()))
    }

    @Test fun oneFailureFromEachScreenDoesNotCombineAndSuccessfulActionResetsVision() {
        val run = session()
        run.recordAccessibilityModelAttempt(first)
        run.requestAiRecovery("missing", true, first)
        run.observe(second)
        run.recordAccessibilityModelAttempt(second)
        run.requestAiRecovery("missing", true, second)
        assertFalse(run.reserveVisualFallback(second, second.screenFingerprint()))
        run.requestAiRecovery("missing again", true, second)
        assertTrue(run.visualFallbackActive)
        run.recordExecution(plan, second, ExecutionResult(true, "ok", 1, postconditionSatisfied = true))
        assertFalse(run.visualFallbackActive)
        assertFalse(run.reserveVisualFallback(second, second.screenFingerprint()))
    }

    @Test fun sameScreenReplansAndRevisitsNeverResendAnImageAndRequestHasTwoImageLimit() {
        val run = session()
        ready(run, first)
        assertTrue(run.reserveVisualFallback(first, first.screenFingerprint()))
        repeat(4) {
            run.requestAiRecovery("missing again", true, first.copy(epoch = 100 + it.toLong()))
            assertFalse(run.reserveVisualFallback(first, first.screenFingerprint()))
        }
        ready(run, second)
        assertTrue(run.reserveVisualFallback(second, second.screenFingerprint()))
        ready(run, first)
        assertFalse(run.reserveVisualFallback(first, first.screenFingerprint()))
        val third = screen("세 번째 화면")
        ready(run, third)
        assertFalse(run.reserveVisualFallback(third, third.screenFingerprint()))
        val independent = session()
        ready(independent, first)
        assertTrue(independent.reserveVisualFallback(first, first.screenFingerprint()))
    }

    @Test fun postconditionTimeoutAndGestureFailureDoNotMeanAccessibilityTargetIsMissing() {
        for (failure in listOf(ExecutionFailureReason.POSTCONDITION_TIMEOUT, ExecutionFailureReason.GESTURE_FAILED)) {
            val run = session()
            run.recordAccessibilityModelAttempt(first)
            repeat(2) { run.recordExecution(plan, first, ExecutionResult(false, "failed", 0, failureReason = failure)) }
            assertFalse("Failure $failure must stay in structured recovery", run.visualFallbackActive)
        }
        val run = session()
        run.recordAccessibilityModelAttempt(first)
        repeat(2) { run.recordExecution(plan, first,
            ExecutionResult(false, "node rejected", 0, failureReason = ExecutionFailureReason.NODE_ACTION_FAILED)) }
        assertTrue(run.reserveVisualFallback(first, first.screenFingerprint()))
    }

    @Test fun onlyMissingGroundedTargetIsClassifiedForImageRecovery() {
        val missing = runtime.verify(command, plan, first) as VerificationResult.NeedsReplan
        assertTrue(missing.accessibilityTargetMissing)
        val lowConfidence = runtime.verify(command, plan.copy(confidence = .1), first) as VerificationResult.NeedsReplan
        assertFalse(lowConfidence.accessibilityTargetMissing)
        val truncated = runtime.verify(command, plan, first.copy(treeTruncated = true)) as VerificationResult.NeedsReplan
        assertFalse(truncated.accessibilityTargetMissing)
    }

    @Test fun changedCanvasFramesCanContinueOnlyWithVerifiedProgressAndStayBounded() {
        val run = session()
        ready(run, first)
        assertTrue(run.reserveVisualFallback(first, "frame-0"))
        assertFalse(run.reserveVisualFallback(first, "frame-0"))
        repeat(5) { index ->
            val visualPlan = plan.copy(visualFrameHash = "frame-$index", visualFallback = true,
                source = PlanSource.OPENAI_SEMANTIC_MAP,
                actions = listOf(AgentAction(ActionType.CLICK_COORDINATE, "현재 화면에서 다음 단계", xRatio = .5, yRatio = .4)))
            run.recordExecution(visualPlan, first, ExecutionResult(true, "visual transition verified", 1,
                postconditionSatisfied = true, afterVisualFrameHash = "frame-${index + 1}"))
            run.observe(first)
            assertTrue(run.canAttemptVisualFallback(first))
            assertTrue(run.reserveVisualFallback(first, "frame-${index + 1}"))
            assertTrue(run.hasVisualAttemptFor(first, "frame-${index + 1}"))
            assertFalse(run.hasVisualAttemptFor(first, "unseen-frame"))
        }
        assertFalse(run.canAttemptVisualFallback(first))
        assertFalse(run.reserveVisualFallback(first, "frame-7"))
    }

    @Test fun failedCaptureDoesNotSpendBudgetAndUnverifiedPixelsDoNotEarnMoreCalls() {
        val run = session()
        ready(run, first)
        repeat(5) { assertTrue(run.canAttemptVisualFallback(first)) }
        assertTrue(run.reserveVisualFallback(first, "frame-0"))
        assertTrue(run.reserveVisualFallback(first, "frame-1"))
        val visual = plan.copy(visualFrameHash = "frame-1")
        for (postcondition in listOf(false, null)) {
            run.recordExecution(visual, first, ExecutionResult(true, "unverified", 1,
                postconditionSatisfied = postcondition, afterVisualFrameHash = "new-pixels"))
            assertFalse(run.reserveVisualFallback(first, "new-pixels"))
        }
    }

    private fun session() = AutonomySession(command, first, 0)
    private fun ready(run: AutonomySession, snapshot: UiSnapshot) {
        run.observe(snapshot)
        run.recordAccessibilityModelAttempt(snapshot)
        repeat(2) { run.requestAiRecovery("missing", true, snapshot) }
    }
    private fun screen(label: String) = UiSnapshot("com.example.app", "Example", epoch = 1,
        elements = listOf(UiElement("0", "com.example:id/label", "android.widget.TextView", label, null,
            ScreenBounds(0, 0, 300, 100), clickable = true, editable = false, scrollable = false,
            enabled = true, visible = true, sensitive = false)))
}
