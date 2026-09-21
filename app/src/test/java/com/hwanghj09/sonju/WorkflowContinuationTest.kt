package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.*
import com.hwanghj09.sonju.logging.ProcessLogRepository
import com.hwanghj09.sonju.logging.TraceStep
import com.hwanghj09.sonju.skill.InMemorySkillRepository
import org.junit.Assert.*
import org.junit.Test

class WorkflowContinuationTest {
    private val card = UiElement("0.1", null, "android.view.View", null, null,
        ScreenBounds(0, 300, 1000, 800), true, false, false, true, true, false)
    private val name = card.copy(path = "0.1.0", text = "피자 가게 A", clickable = false)
    private val snapshot = UiSnapshot("example.food", null, epoch = 1, elements = listOf(card, name))
    private fun plan(completed: Boolean = true) = AgentPlan("피자 주문해 줘", "검색 결과가 보입니다", RiskLevel.LOW, .99,
        listOf(AgentAction(ActionType.FINISH, "후보 선택에서 종료")), PlanSource.OPENAI_STRUCTURE,
        goalCompleted = completed, goalChecks = listOf(GoalCheck(name.path, name.text)))
    private fun runtime() = SonjuAgentRuntime.createForTest(InMemorySkillRepository(), object : ProcessLogRepository {
        override fun append(step: TraceStep) = Unit
        override fun exportRedacted(): List<String> = emptyList()
    })

    @Test fun matchingCandidateLabelsAreNotAnOrderOrAnObservationFailure() {
        val runtime = runtime()
        val proposal = plan()
        assertTrue(proposal.goalChecks.all { it.matches(snapshot) })
        assertFalse(runtime.goalSatisfied("피자 주문해 줘", proposal, snapshot))
        val reason = runtime.completionRejectionReason("피자 주문해 줘", proposal, snapshot)
        assertTrue(reason.contains("관찰과 일치"))
        assertTrue(reason.contains("고정 최종 목표 '피자 주문해 줘'"))
        assertTrue(reason.contains("다음 준비 단계"))
        assertFalse(reason.startsWith("제시한 완료 근거가"))
        assertTrue(runtime.completionRejectionReason("피자 주문해 줘",
            proposal.copy(goalChecks = listOf(GoalCheck("missing", "absent"))), snapshot).startsWith("제시한 완료 근거가"))
    }

    @Test fun repeatedFinishWithNoActionsIsRecordedAndBoundedWithoutClaimingCompletion() {
        val runtime = runtime()
        val session = AutonomySession("피자 주문해 줘", snapshot, 0)
        repeat(3) { index ->
            val rejected = plan(completed = index % 2 == 0)
            runtime.recordPlanningFailure(rejected, session,
                runtime.completionRejectionReason(session.finalGoal, rejected, snapshot), snapshot)
            if (index < 2) assertTrue(session.canContinue(100))
        }
        assertEquals(0, session.toolCallCount)
        assertTrue(session.history.isEmpty())
        assertTrue(session.plannerContext(snapshot, null).contains("FINISH"))
        assertFalse(session.canContinue(100))
        assertTrue(session.stopReason(100).orEmpty().contains("작업은 완료되지 않았습니다"))
        assertFalse(session.canAttemptVisualFallback(snapshot))
    }

    @Test fun actualNextActionsAndNewScreensAllowRecoveryFromPrematureCompletion() {
        val session = AutonomySession("피자 주문해 줘", snapshot, 0)
        repeat(2) { session.recordPlanningRejection(plan(), snapshot, "unfinished") }
        val click = plan(false).copy(actions = listOf(AgentAction(ActionType.CLICK, "가게 상세", card.path)))
        session.recordExecution(click, snapshot, ExecutionResult(true, "opened", 1, postconditionSatisfied = true), card.path)
        session.recordPlanningRejection(plan(), snapshot, "unfinished")
        assertTrue(session.canContinue(100))
        repeat(2) { session.recordPlanningRejection(plan(), snapshot, "unfinished") }
        assertFalse(session.canContinue(100))
        val changed = snapshot.copy(epoch = 2, elements = listOf(card, name.copy(text = "새 메뉴 선택")))
        session.observe(changed)
        assertTrue(session.canContinue(100))
    }

    @Test fun readOnlyScrollRecoveryUsesDirectionRatherThanTheWholeOrdersRisk() {
        val horizontal = card.copy(path = "0.photos", clickable = false, scrollable = true,
            availableActions = setOf(UiNodeAction.SCROLL_LEFT, UiNodeAction.SCROLL_RIGHT, UiNodeAction.SCROLL_FORWARD))
        val vertical = card.copy(path = "0.body", clickable = false, scrollable = true,
            availableActions = setOf(UiNodeAction.SCROLL_DOWN, UiNodeAction.SCROLL_FORWARD))
        val runtime = runtime()
        fun verify(nodes: List<UiElement>, target: String? = null) = runtime.verify("피자 주문해 줘",
            plan(false).copy(actions = listOf(AgentAction(ActionType.SCROLL_DOWN, "아래 내용 보기", target))),
            snapshot.copy(elements = nodes))
        assertTrue(verify(listOf(horizontal)) is com.hwanghj09.sonju.verifier.VerificationResult.NeedsReplan)
        assertTrue(verify(listOf(horizontal), horizontal.path) is com.hwanghj09.sonju.verifier.VerificationResult.NeedsReplan)
        assertTrue(verify(listOf(horizontal, vertical), vertical.path) is com.hwanghj09.sonju.verifier.VerificationResult.Allowed)
        assertTrue(verify(listOf(vertical, vertical.copy(path = "0.second"))) is com.hwanghj09.sonju.verifier.VerificationResult.NeedsReplan)
        assertTrue(verify(listOf(vertical.copy(availableActions = setOf(UiNodeAction.SCROLL_FORWARD))), vertical.path)
            is com.hwanghj09.sonju.verifier.VerificationResult.Allowed)
    }

    @Test fun submitTheTypedQueryBeforeChoosingAnUnrequestedAutocompleteBrand() {
        val field = card.copy(path = "0.search", className = "android.widget.EditText", text = "피자",
            editable = true, hintText = "검색", focused = true)
        val brand = card.copy(text = "피자헛")
        val current = snapshot.copy(elements = listOf(field, brand, name.copy(text = "피자")))
        fun session(goal: String = "피자 주문해 줘") = AutonomySession(goal, current, 0).also {
            it.recordExecution(plan(false).copy(actions = listOf(AgentAction(ActionType.SET_TEXT, "입력", field.path, "피자"))),
                current, ExecutionResult(true, "typed", 1, postconditionSatisfied = true), field.path)
        }
        val click = AgentAction(ActionType.CLICK, "추천", brand.path)
        val run = session()
        assertNotNull(run.repeatedActionFailure(click, current, brand.path))
        assertNull(session("피자 입력하고 피자헛을 눌러줘").repeatedActionFailure(click, current, brand.path))
        val searchButton = current.copy(elements = listOf(field, brand.copy(text = "검색")))
        assertNull(run.repeatedActionFailure(click, searchButton, brand.path))
        val exactSuggestion = current.copy(elements = listOf(field, brand.copy(text = "피자")))
        assertNull(run.repeatedActionFailure(click, exactSuggestion, brand.path))
        assertTrue(com.hwanghj09.sonju.verifier.SearchSubmissionPolicy.isSubmitClick(click, exactSuggestion, "피자"))
        run.recordExecution(plan(false).copy(actions = listOf(AgentAction(ActionType.SUBMIT_TEXT, "검색", field.path, "피자"))),
            current, ExecutionResult(false, "No effect; inspect live results", 0, postconditionSatisfied = false), field.path)
        assertNull(run.repeatedActionFailure(click, current, brand.path))
    }
}
