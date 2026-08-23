package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.AgentAction
import com.hwanghj09.sonju.agent.AgentPlan
import com.hwanghj09.sonju.agent.EssentialSafetyPolicy
import com.hwanghj09.sonju.agent.PlanSource
import com.hwanghj09.sonju.agent.RiskLevel
import com.hwanghj09.sonju.agent.SafetyDecision
import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EssentialSafetyPolicyTest {
    @Test
    fun ordinaryNavigationAndSearchInputAreAutonomous() {
        val snapshot = snapshot(editable = true, hint = "검색어")
        val action = AgentAction(ActionType.SET_TEXT, "검색어 입력", "검색어", "서울역")

        assertEquals(
            SafetyDecision.ALLOW,
            EssentialSafetyPolicy.evaluate("서울역 찾아줘", plan(action), snapshot).decision,
        )
    }

    @Test
    fun paymentCommitRequiresConfirmation() {
        val action = AgentAction(ActionType.CLICK, "결제하기", "결제하기")

        assertEquals(
            SafetyDecision.REQUIRE_CONFIRMATION,
            EssentialSafetyPolicy.evaluate("주문 결제해줘", plan(action), snapshot()).decision,
        )
    }

    @Test
    fun genericConfirmButtonStillRequiresConfirmationInsideOrderGoal() {
        val action = AgentAction(ActionType.CLICK, "확인 버튼 누르기", "확인")

        assertEquals(
            SafetyDecision.REQUIRE_CONFIRMATION,
            EssentialSafetyPolicy.evaluate("치킨을 주문해줘", plan(action), snapshot()).decision,
        )
    }

    @Test
    fun browsingOrderHistoryDoesNotRequirePaymentConfirmation() {
        val action = AgentAction(ActionType.CLICK, "주문 내역 열기", "주문 내역")

        assertEquals(
            SafetyDecision.ALLOW,
            EssentialSafetyPolicy.evaluate("주문 내역 보여줘", plan(action), snapshot()).decision,
        )
    }

    @Test
    fun personalDataInputRequiresConfirmation() {
        val snapshot = snapshot(editable = true, hint = "전화번호")
        val action = AgentAction(ActionType.SET_TEXT, "전화번호 입력", "전화번호", "010-1234-5678")

        assertEquals(
            SafetyDecision.REQUIRE_CONFIRMATION,
            EssentialSafetyPolicy.evaluate("전화번호 넣어줘", plan(action), snapshot).decision,
        )
    }

    @Test
    fun invalidCoordinateIsBlocked() {
        val action = AgentAction(
            ActionType.CLICK_COORDINATE,
            "좌표 클릭",
            xRatio = 1.2,
            yRatio = .5,
        )

        assertEquals(
            SafetyDecision.BLOCK,
            EssentialSafetyPolicy.evaluate("눌러줘", plan(action), snapshot()).decision,
        )
    }

    @Test
    fun sensitiveAccessibilityScreenCannotBeUploadedAsScreenshot() {
        assertTrue(EssentialSafetyPolicy.allowsRemoteScreenshot(snapshot()))
        assertFalse(
            EssentialSafetyPolicy.allowsRemoteScreenshot(
                snapshot().copy(elements = snapshot().elements.map { it.copy(sensitive = true) }),
            ),
        )
    }

    @Test
    fun screenshotApprovalIsBoundToTheObservedRevision() {
        val expected = snapshot()

        assertTrue(EssentialSafetyPolicy.allowsRemoteScreenshot(expected, expected))
        assertFalse(
            EssentialSafetyPolicy.allowsRemoteScreenshot(
                expected,
                expected.copy(epoch = expected.epoch + 1),
            ),
        )
    }

    private fun plan(action: AgentAction) = AgentPlan(
        goal = "테스트",
        summary = "테스트",
        modelRisk = RiskLevel.LOW,
        confidence = 1.0,
        actions = listOf(action, AgentAction(ActionType.FINISH, "끝")),
        source = PlanSource.GEMINI_STRUCTURE,
        targetSurface = action.description,
    )

    private fun snapshot(
        editable: Boolean = false,
        hint: String? = null,
    ) = UiSnapshot(
        packageName = "com.example",
        windowTitle = "예시",
        epoch = 1,
        elements = listOf(
            UiElement(
                path = "0.0",
                viewId = "com.example:id/field",
                className = if (editable) "android.widget.EditText" else "android.widget.Button",
                text = if (editable) null else "확인",
                contentDescription = null,
                bounds = ScreenBounds(0, 0, 500, 200),
                clickable = !editable,
                editable = editable,
                scrollable = false,
                enabled = true,
                visible = true,
                sensitive = false,
                hintText = hint,
            ),
        ),
    )
}
