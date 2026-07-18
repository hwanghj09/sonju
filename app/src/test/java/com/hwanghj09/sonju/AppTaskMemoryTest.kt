package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.AgentAction
import com.hwanghj09.sonju.agent.AgentPlan
import com.hwanghj09.sonju.agent.AppTaskMemoryPolicy
import com.hwanghj09.sonju.agent.PlanSource
import com.hwanghj09.sonju.agent.RiskLevel
import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTaskMemoryTest {
    @Test
    fun keyDoesNotChangeForSpacingOrCaseInCommand() {
        val snapshot = snapshot()
        assertEquals(
            AppTaskMemoryPolicy.cacheKey("WiFi  켜 줘", snapshot),
            AppTaskMemoryPolicy.cacheKey("wifi켜줘", snapshot),
        )
    }

    @Test
    fun keyChangesWhenSemanticScreenChanges() {
        assertNotEquals(
            AppTaskMemoryPolicy.cacheKey("아래로", snapshot("목록 A")),
            AppTaskMemoryPolicy.cacheKey("아래로", snapshot("목록 B")),
        )
    }

    @Test
    fun onlySingleLowRiskStructureActionIsReusable() {
        val safePlan = plan(ActionType.SCROLL_DOWN)
        assertTrue(AppTaskMemoryPolicy.isReusable(snapshot(), safePlan))
        assertFalse(AppTaskMemoryPolicy.isReusable(snapshot(), plan(ActionType.SET_TEXT)))
        assertFalse(
            AppTaskMemoryPolicy.isReusable(
                snapshot(),
                safePlan.copy(modelRisk = RiskLevel.HIGH),
            ),
        )
        assertFalse(
            AppTaskMemoryPolicy.isReusable(
                snapshot().copy(treeTruncated = true),
                safePlan,
            ),
        )
        assertFalse(
            AppTaskMemoryPolicy.isReusable(
                snapshot(),
                safePlan.copy(continueAfterAction = true),
            ),
        )
    }

    private fun snapshot(text: String = "목록") = UiSnapshot(
        packageName = "com.example.app",
        windowTitle = "예시",
        epoch = 1,
        elements = listOf(
            UiElement(
                path = "0.0",
                viewId = "com.example.app:id/list",
                className = "android.widget.TextView",
                text = text,
                contentDescription = null,
                bounds = ScreenBounds(0, 100, 500, 500),
                clickable = false,
                editable = false,
                scrollable = true,
                enabled = true,
                visible = true,
                sensitive = false,
            ),
        ),
    )

    private fun plan(type: ActionType) = AgentPlan(
        goal = "테스트",
        summary = "테스트",
        modelRisk = RiskLevel.LOW,
        confidence = 1.0,
        actions = listOf(
            AgentAction(type = type, description = "테스트"),
            AgentAction(type = ActionType.FINISH, description = "끝"),
        ),
        source = PlanSource.GEMINI_STRUCTURE,
    )
}
