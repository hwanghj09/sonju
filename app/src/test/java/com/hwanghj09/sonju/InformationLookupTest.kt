package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.*
import com.hwanghj09.sonju.logging.ProcessLogRepository
import com.hwanghj09.sonju.logging.TraceStep
import com.hwanghj09.sonju.skill.InMemorySkillRepository
import com.hwanghj09.sonju.verifier.VerificationResult
import org.junit.Assert.*
import org.junit.Test

class InformationLookupTest {
    private val runtime = SonjuAgentRuntime.createForTest(InMemorySkillRepository(), object : ProcessLogRepository {
        override fun append(step: TraceStep) = Unit
        override fun exportRedacted(): List<String> = emptyList()
    })

    @Test fun historyNavigationAndObservedResultsDoNotExecuteTheActionNamedInTheHistory() {
        for (subject in listOf("송금", "전송", "삭제")) {
            val command = "$subject 내역 알려줘"
            val menu = screen("$subject 내역", clickable = true)
            val click = plan(command, AgentAction(ActionType.CLICK, "내역을 엽니다", "0"))
            assertTrue("$subject history must be navigation", runtime.verify(command, click, menu) is VerificationResult.Allowed)
            val result = screen("해당 기간의 내역이 없습니다", clickable = false)
            val done = plan(command, AgentAction(ActionType.FINISH, "결과 확인")).copy(
                goalCompleted = true, goalChecks = listOf(GoalCheck("0", result.elements.single().text)))
            assertTrue("$subject lookup must complete on actual evidence", runtime.goalSatisfied(command, done, result))
            assertFalse(runtime.goalSatisfied(command, done, menu))
            assertFalse(runtime.goalSatisfied(command, done.copy(goalChecks = emptyList()), result))
        }
    }

    @Test fun lookupWordingCannotAuthorizeACommitOrInventCompletionOfAMixedRequest() {
        for (label in listOf("송금하기", "송금 확인", "내역 삭제", "삭제 확인")) {
            val snapshot = screen(label, clickable = true)
            val disguised = plan("송금 내역 알려줘", AgentAction(ActionType.CLICK, "내역 조회", "0"))
            assertFalse(label, runtime.verify(disguised.goal, disguised, snapshot) is VerificationResult.Allowed)
        }
        val result = screen("해당 기간의 내역이 없습니다", clickable = false)
        for (command in listOf("송금해주고 내역 알려줘", "송금하고 결과 알려줘", "송금하시고 내역 알려줘",
                "삭제한 다음 기록 알려줘")) {
            val done = plan(command, AgentAction(ActionType.FINISH, "완료")).copy(
                goalCompleted = true, goalChecks = listOf(GoalCheck("0", result.elements.single().text)))
            assertFalse(command, runtime.goalSatisfied(command, done, result))
        }
    }

    private fun plan(command: String, action: AgentAction) = AgentPlan(command, "조회", RiskLevel.LOW, 1.0,
        listOf(action), PlanSource.OPENAI_STRUCTURE)

    private fun screen(label: String, clickable: Boolean) = UiSnapshot("org.example.app", "Example", epoch = 1,
        elements = listOf(UiElement("0", null, "android.widget.TextView", label, null,
            ScreenBounds(0, 0, 300, 100), clickable, false, false, true, true, false)))
}
