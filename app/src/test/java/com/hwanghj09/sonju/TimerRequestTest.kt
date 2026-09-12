package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.*
import com.hwanghj09.sonju.verifier.VerificationResult
import org.junit.Assert.*
import org.junit.Test

class TimerRequestTest {
    @Test fun exactRequestsBindNameAndDurationWithoutAcceptingQuestionsOrNegation() {
        assertEquals(TimerRequest(600, "손주 테스트"),
            TimerRequest.parse("시계에서 손주 테스트라는 이름으로 10분 타이머를 시작해줘"))
        assertEquals(TimerRequest(3725, null), TimerRequest.parse("1시간 2분 5초 타이머 맞춰주세요"))
        listOf("10분 타이머 시작하지 마", "10분 타이머 맞춰줘라고 메모해줘",
            "10분 타이머를 시작하는 방법 알려줘", "0초 타이머 맞춰줘", "99999999999999999시간 타이머 맞춰줘",
            "1분 2분 타이머 맞춰줘", "25시간 타이머 시작해줘").forEach {
            assertNull(it, TimerRequest.parse(it))
        }
    }

    @Test fun verifierRejectsInventedDurationLabelOrUnrelatedRequest() {
        val runtime = SonjuAgentRuntime.createForTest(com.hwanghj09.sonju.skill.InMemorySkillRepository(),
            object : com.hwanghj09.sonju.logging.ProcessLogRepository {
                override fun append(step: com.hwanghj09.sonju.logging.TraceStep) = Unit
                override fun exportRedacted(): List<String> = emptyList()
            })
        val command = "손주 테스트라는 이름으로 10분 타이머를 시작해줘"
        val plan = RuleBasedPlanner.plan(command)!!
        val screen = UiSnapshot.empty(1).copy(packageName = "launcher")
        assertTrue(runtime.verify(command, plan, screen) is VerificationResult.Allowed)
        for (action in listOf(plan.actions.first().copy(value = "3600"), plan.actions.first().copy(target = "다른 이름"))) {
            assertTrue(runtime.verify(command, plan.copy(actions = listOf(action)), screen) is VerificationResult.Blocked)
        }
        assertTrue(runtime.verify("시계 열어줘", plan, screen) is VerificationResult.Blocked)
    }

    @Test fun countdownRequiresRequestedNameRunningControlAndPlausibleRemainingTime() {
        val request = TimerRequest(600, "손주 테스트")
        val screen = UiSnapshot.empty(1).copy(packageName = "clock", elements = listOf(
            node("0.0", "손주 테스트"), node("0.1", "09:58"), node("0.2", "일시 정지", true)))
        assertEquals(598, request.runningSeconds(screen, 2))
        assertEquals(598, request.runningSeconds(screen.copy(elements = screen.elements.map {
            if (it.path == "0.1") it.copy(text = null, contentDescription = "9 분 58 초") else it }), 2))
        assertNull(request.runningSeconds(screen.copy(elements = screen.elements.map {
            if (it.path == "0.1") it.copy(text = "19:58") else it }), 2))
        assertNull(request.runningSeconds(screen.copy(elements = screen.elements.map {
            if (it.path == "0.2") it.copy(text = "다시 시작") else it }), 2))
        assertNull(TimerRequest(600, "다른 이름").runningSeconds(screen, 2))
        assertNull(request.runningSeconds(screen.copy(elements = screen.elements.map {
            if (it.path == "0.1") it.copy(editable = true) else it }), 2))
    }

    private fun node(path: String, text: String, clickable: Boolean = false) = UiElement(
        path, null, "android.widget.TextView", text, null, ScreenBounds(0, 0, 100, 50),
        clickable, false, false, true, true, false)
}
