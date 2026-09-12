package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.*
import com.hwanghj09.sonju.execution.ExecutionFailureReason
import com.hwanghj09.sonju.logging.ProcessLogRepository
import com.hwanghj09.sonju.logging.TraceStep
import com.hwanghj09.sonju.skill.InMemorySkillRepository
import com.hwanghj09.sonju.verifier.VerificationResult
import org.junit.Assert.*
import org.junit.Test

class GeneralCommandRecoveryTest {
    @Test fun routeEntitiesKeepOriginAndDestinationSeparateFromTheAppName() {
        val parser = com.hwanghj09.sonju.task.DeterministicTaskParser
        val named = parser.parse("구글 지도에서 서울역에서 시청역까지 대중교통으로 가는 길을 알려줘")
        val unnamed = parser.parse("서울역에서 시청역까지 대중교통으로 가는 길을 알려줘")
        for (intent in listOf(named, unnamed)) {
            assertEquals("서울역", intent.entities["origin"])
            assertEquals("시청역", intent.entities["destination"])
        }
        val route = AppWorkflowRouter.route(named.rawText, listOf("지도", "네이버지도", "구글 지도"))
        assertEquals("구글 지도", route?.appLabel)
        assertEquals("시청역", route?.launchQuery)
    }

    @Test fun completionTextToleratesInvisibleFormattingButKeepsEveryNumberAndSymbol() {
        val snapshot = screen("\u200b1호선\u200b\n4분")
        val check = GoalCheck("0.input", "1호선 4분")
        assertTrue(check.matches(snapshot))
        assertFalse(check.matches(screen("1호선 14분")))
        assertFalse(GoalCheck("0.input", "1,000원").matches(screen("-1,000원")))
        val stored = requireNotNull(com.hwanghj09.sonju.skill.StoredGoalCheck.capture(check, snapshot))
        assertNotNull(stored.resolve(screen("1호선 4분")))
        assertNull(stored.resolve(screen("1호선 14분")))
    }

    @Test fun aModelWaitAlwaysRequiresAnotherObservation() {
        val session = AutonomySession("결과를 확인해줘", screen(), 0)
        val accepted = session.acceptPlan(plan(AgentAction(ActionType.WAIT, "기다림")), screen())
        assertTrue(accepted.continueAfterAction)
        assertFalse(accepted.goalCompleted)
    }

    @Test fun repeatedSuccessfulKeypadActionsWithChangingValuesAreNotALoop() {
        val session = AutonomySession("반복 입력", screen("1"), 0)
        repeat(5) { index ->
            val before = screen("1".repeat(index + 1))
            val after = screen("1".repeat(index + 2))
            session.recordExecution(plan(AgentAction(ActionType.CLICK, "1", "key")), before,
                ExecutionResult(true, "ok", 1, postconditionSatisfied = true), "0.key")
            session.observe(after)
        }
        assertFalse(session.hasDetectedLoop())
        assertTrue(session.canContinue(100))
    }

    @Test fun rejectedCandidatesAreReturnedToTheModelAndBoundToTheCurrentScreen() {
        val snapshot = screen()
        val session = AutonomySession("새로운 메뉴 열기", snapshot, 0)
        val proposal = plan(AgentAction(ActionType.CLICK, "메뉴", "missing"))
        repeat(2) { session.recordPlanningRejection(proposal, snapshot, "target missing", true) }
        assertTrue(session.plannerContext(snapshot, null).contains("CLICK:missing -> target missing"))
        assertNotNull(session.repeatedActionFailure(proposal.actions.first(), snapshot))
        session.observe(screen("different page"))
        assertTrue(session.discouragedActionSignatures().isEmpty())
        assertNull(session.repeatedActionFailure(proposal.actions.first(), screen("different page")))
    }

    @Test fun rejectedInputCanRecoverThroughAVisibleKeypadBeforeTheSessionStops() {
        val snapshot = screen()
        val session = AutonomySession("키패드 입력", snapshot, 0)
        val input = plan(AgentAction(ActionType.SET_TEXT, "입력", "0.input", "123"))
        repeat(2) {
            session.recordExecution(input, snapshot, ExecutionResult(false, "not reflected", 0,
                failureReason = ExecutionFailureReason.POSTCONDITION_TIMEOUT))
            session.observe(snapshot)
        }
        assertTrue(session.canContinue(100))
        assertTrue(session.plannerContext(snapshot, null).contains("키패드"))
        assertNotNull(session.repeatedActionFailure(input.actions.first(), snapshot, "0.input"))
        assertNull(session.repeatedActionFailure(input.actions.first().copy(value = "124"), snapshot, "0.input"))
        assertNull(session.repeatedActionFailure(AgentAction(ActionType.CLICK, "1", "0.key"), snapshot, "0.key"))
        repeat(4) {
            session.recordExecution(plan(AgentAction(ActionType.WAIT, "대기")), snapshot, ExecutionResult(true, "wait", 1))
            session.observe(snapshot)
        }
        assertFalse(session.canContinue(100))
    }

    @Test fun modelCanSubmitAnObservedSearchButCannotSubmitMessagesOrChangeTheCurrentValue() {
        val runtime = runtime()
        val command = "도서관에서 우주 여행 검색해줘"
        val search = screen("우주 여행").copy(elements = listOf(input("우주 여행", "catalog_search", "도서 검색")))
        val submit = plan(AgentAction(ActionType.SUBMIT_TEXT, "검색", "0.input", "우주 여행"))
        assertTrue(runtime.verify(command, submit, search) is VerificationResult.Allowed)
        assertTrue(runtime.verify(command, submit.copy(actions = listOf(submit.actions.first().copy(value = "다른 검색어"))),
            search) is VerificationResult.NeedsReplan)
        assertTrue(runtime.verify(command, submit, search.copy(elements = listOf(input("우주 여행", "composer", "메시지"))))
            is VerificationResult.NeedsReplan)
        assertTrue(runtime.verify(command, submit, search.copy(elements = listOf(input("우주 여행", "catalog_search", "검색")
            .copy(availableActions = setOf(UiNodeAction.SET_TEXT))))) is VerificationResult.Allowed)
        assertTrue(runtime.verify("검색해줘", submit, search) is VerificationResult.Allowed)
    }

    @Test fun aCompleteScreenStillRequiresObservedEvidenceWithNoSavedSkill() {
        val runtime = runtime()
        val snapshot = screen("상세 정보: 우주 여행, 출판사 별빛")
        val command = "우주 여행의 출판사를 알려줘"
        val complete = plan(AgentAction(ActionType.FINISH, "완료")).copy(goalCompleted = true,
            goalChecks = listOf(GoalCheck("0.input", "상세 정보: 우주 여행, 출판사 별빛")))
        assertNull(runtime.fastPathPlan(command, snapshot))
        assertFalse(runtime.goalSatisfied(command, complete.copy(goalChecks = emptyList()), snapshot))
        assertFalse(runtime.goalSatisfied(command, complete, screen("검색 중")))
        assertTrue(runtime.goalSatisfied(command, complete, snapshot))
    }

    private fun runtime() = SonjuAgentRuntime.createForTest(InMemorySkillRepository(), object : ProcessLogRepository {
        override fun append(step: TraceStep) = Unit
        override fun exportRedacted(): List<String> = emptyList()
    })
    private fun input(text: String, id: String = "input", hint: String = "입력") = UiElement("0.input", "org.example.app:id/$id",
        "android.widget.EditText", text, null, ScreenBounds(0, 0, 400, 100), clickable = true,
        editable = true, scrollable = false, enabled = true, visible = true, sensitive = false,
        hintText = hint, availableActions = setOf(UiNodeAction.SET_TEXT, UiNodeAction.IME_ENTER))
    private fun screen(text: String = "") = UiSnapshot("org.example.app", "App", epoch = 1,
        elements = listOf(input(text), UiElement("0.key", "org.example.app:id/key", "android.widget.Button", "1", null,
            ScreenBounds(0, 100, 200, 200), true, false, false, true, true, false)))
    private fun plan(action: AgentAction) = AgentPlan("일반 요청", "다음 행동", RiskLevel.LOW, .95,
        listOf(action), PlanSource.OPENAI_STRUCTURE)
}
