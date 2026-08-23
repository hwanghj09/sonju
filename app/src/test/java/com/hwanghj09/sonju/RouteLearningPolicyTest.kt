package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.AgentAction
import com.hwanghj09.sonju.agent.AutonomySession
import com.hwanghj09.sonju.agent.LearnedRoute
import com.hwanghj09.sonju.agent.LearnedRouteStep
import com.hwanghj09.sonju.agent.RouteLearningPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLearningPolicyTest {
    @Test
    fun loopErasureKeepsTheShortestObservedSuccessPath() {
        val history = listOf(
            trace("A", "B", AgentAction(ActionType.CLICK, "열기", "메뉴")),
            trace("B", "A", AgentAction(ActionType.BACK, "뒤로")),
            trace("A", "C", AgentAction(ActionType.CLICK, "목표", "목표")),
        )

        val steps = RouteLearningPolicy.shortestReusableSteps(history)

        assertEquals(1, steps.size)
        assertEquals(ActionType.CLICK, steps.single().type)
        assertEquals("목표", steps.single().target)
    }

    @Test
    fun textValueIsNeverPersistedInRouteStep() {
        val action = AgentAction(ActionType.SET_TEXT, "검색", "검색어", "010-1234-5678")
        val step = RouteLearningPolicy.shortestReusableSteps(listOf(trace("A", "B", action))).single()

        assertEquals(ActionType.SET_TEXT, step.type)
        assertEquals("검색어", step.target)
        assertFalse(step.toString().contains("010-1234-5678"))
    }

    @Test
    fun lowerCostRouteReplacesExistingRoute() {
        val existing = route(listOf(LearnedRouteStep(ActionType.CLICK_COORDINATE, null, .5, .5)))
        val candidate = route(listOf(LearnedRouteStep(ActionType.CLICK, "확인", null, null)))

        assertTrue(RouteLearningPolicy.shouldReplace(existing, candidate))
    }

    @Test
    fun goalPatternRedactsPersonalValues() {
        val pattern = RouteLearningPolicy.normalizedGoalPattern(
            "010-1234-5678 번호와 user@example.com, 839201을 입력해줘",
        )

        assertTrue(pattern.contains("<phone>"))
        assertTrue(pattern.contains("<email>"))
        assertTrue(pattern.contains("<number>"))
        assertFalse(pattern.contains("839201"))
    }

    private fun trace(before: String, after: String, action: AgentAction) = AutonomySession.Trace(
        action = action,
        beforePackage = "com.example",
        beforeFingerprint = before,
        succeeded = true,
        message = "성공",
        afterPackage = "com.example",
        afterFingerprint = after,
        screenChanged = before != after,
    )

    private fun route(steps: List<LearnedRouteStep>) = LearnedRoute(
        key = "key",
        goalPattern = "목표",
        startPackage = "com.example",
        targetApp = "예시",
        startFingerprint = "A",
        steps = steps,
        cost = RouteLearningPolicy.routeCost(steps),
        savedAtMillis = 1,
    )
}
