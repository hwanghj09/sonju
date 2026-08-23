package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.AgentAction
import com.hwanghj09.sonju.agent.AgentPlan
import com.hwanghj09.sonju.agent.AutonomySession
import com.hwanghj09.sonju.agent.ExecutionResult
import com.hwanghj09.sonju.agent.PlanSource
import com.hwanghj09.sonju.agent.RiskLevel
import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomySessionTest {
    @Test
    fun finalGoalIsImmutableAndSpeculativeActionsAreTrimmed() {
        val snapshot = snapshot("시작")
        val session = AutonomySession("지도에서 서울역 열기", snapshot, startedAtMillis = 10)
        val candidate = plan(
            goal = "모델이 바꾼 목표",
            actions = listOf(
                AgentAction(ActionType.CLICK, "검색", "검색"),
                AgentAction(ActionType.SET_TEXT, "서울역 입력", "검색어", "서울역"),
                AgentAction(ActionType.FINISH, "끝"),
            ),
        )

        val accepted = session.acceptPlan(candidate, snapshot)

        assertEquals("지도에서 서울역 열기", accepted.goal)
        assertEquals(1, accepted.revision)
        assertEquals(1, accepted.actions.count { it.type != ActionType.FINISH })
        assertEquals(ActionType.CLICK, accepted.actions.first().type)
        assertTrue(accepted.targetApp.isNotBlank())
        assertTrue(accepted.targetSurface.isNotBlank())
        assertTrue(accepted.requiredTools.isNotEmpty())
    }

    @Test
    fun repeatedFailureOnSameScreenIsFedBackToPlanner() {
        val snapshot = snapshot("목록")
        val session = AutonomySession("설정 찾기", snapshot, startedAtMillis = 0)
        val plan = session.acceptPlan(
            plan(actions = listOf(AgentAction(ActionType.CLICK, "설정", "설정"))),
            snapshot,
        )
        repeat(2) {
            session.recordExecution(plan, snapshot, ExecutionResult(false, "대상을 찾지 못함", 0))
            session.observe(snapshot)
        }

        assertEquals(setOf("CLICK:설정"), session.discouragedActionSignatures())
        assertTrue(session.plannerContext(snapshot, null).contains("반복 실패"))
    }

    @Test
    fun observedTransitionRecordsWhetherScreenChanged() {
        val before = snapshot("A")
        val after = snapshot("B").copy(epoch = 2)
        val session = AutonomySession("다음 화면", before, startedAtMillis = 0)
        val plan = session.acceptPlan(
            plan(actions = listOf(AgentAction(ActionType.CLICK, "다음", "다음"))),
            before,
        )
        session.recordExecution(plan, before, ExecutionResult(true, "성공", 1))
        session.observe(after)

        assertEquals(true, session.history.single().screenChanged)
        assertEquals(listOf(ActionType.CLICK), session.successfulActions().map { it.type })
    }

    @Test
    fun toolAndTimeBudgetsAreBounded() {
        val snapshot = snapshot("A")
        val session = AutonomySession(
            "작업",
            snapshot,
            startedAtMillis = 100,
            maxToolCalls = 1,
            maxDurationMillis = 1_000,
        )
        val plan = session.acceptPlan(
            plan(actions = listOf(AgentAction(ActionType.BACK, "뒤로"))),
            snapshot,
        )
        assertTrue(session.canContinue(500))
        session.recordExecution(plan, snapshot, ExecutionResult(true, "성공", 1))
        assertFalse(session.canContinue(500))
        assertFalse(session.canContinue(1_101))
    }

    private fun plan(
        goal: String = "작업",
        actions: List<AgentAction>,
    ) = AgentPlan(
        goal = goal,
        summary = "계획",
        modelRisk = RiskLevel.LOW,
        confidence = 1.0,
        actions = actions,
        source = PlanSource.GEMINI_STRUCTURE,
    )

    private fun snapshot(text: String) = UiSnapshot(
        packageName = "com.example",
        windowTitle = "예시 화면",
        epoch = 1,
        elements = listOf(
            UiElement(
                path = "0.0",
                viewId = "com.example:id/item",
                className = "android.widget.TextView",
                text = text,
                contentDescription = null,
                bounds = ScreenBounds(0, 0, 500, 200),
                clickable = true,
                editable = false,
                scrollable = false,
                enabled = true,
                visible = true,
                sensitive = false,
            ),
        ),
    )
}
