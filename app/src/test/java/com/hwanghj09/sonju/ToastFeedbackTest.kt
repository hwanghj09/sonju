package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.*
import com.hwanghj09.sonju.ai.PlannerObservation
import com.hwanghj09.sonju.skill.SkillLearner
import org.junit.Assert.*
import org.junit.Test

class ToastFeedbackTest {
    @Test fun feedbackIsBoundedFreshAppScopedAndRedactedBeforeRetention() {
        val feedback = ToastFeedback()
        assertFalse(feedback.record("", "안내", 0))
        assertFalse(feedback.record("app", " ", 0))
        assertTrue(feedback.record("app", "연결 실패", 100))
        assertFalse(feedback.record("app", "연결 실패", 200))
        val first = feedback.recent("app", 200).single()
        assertTrue(feedback.record("app", "연결 실패", 500))
        assertNotEquals(first.id, feedback.recent("app", 500).last().id)
        assertTrue(feedback.recent("other", 500).isEmpty())
        feedback.record("app", "인증번호 123456", 600)
        assertEquals("<REDACTED_SENSITIVE>", feedback.recent("app", 600).last().text)
        repeat(8) { feedback.record("app", "안내 $it", 1000L + it * 300) }
        assertEquals(4, feedback.recent("app", 4000).size)
        assertTrue(feedback.recent("app", 40_000).isEmpty())
        feedback.record("app", "최근 안내", 50_000)
        assertTrue(feedback.recent("app", 1).isEmpty())
        feedback.record("app", "최근 안내", 60_000)
        feedback.clear()
        assertTrue(feedback.recent("app", 60_000).isEmpty())
    }

    @Test fun toastIsReadOnlyContextWithoutChangingTheExecutableScreenOrCompletionEvidence() {
        val before = screen()
        val after = before.copy(recentToasts = listOf(ToastMessage(1, "app", "사용할 수 없습니다. 대체 항목을 선택하세요.", 10),
            ToastMessage(2, "other", "other-app-private", 11), ToastMessage(3, "app", "인증번호 123456", 12)))
        assertTrue(after.hasNewToastSince(before))
        assertFalse(after.hasNewToastSince(after))
        assertEquals(before.screenFingerprint(), after.screenFingerprint())
        assertEquals(before.semanticTemplateFingerprint(), after.semanticTemplateFingerprint())
        assertTrue(before.hasSameRevisionAs(after))
        val prompt = PlannerObservation.render(after, "기본 항목 실행")
        assertTrue(prompt.contains("recent_toasts_read_only"))
        assertTrue(prompt.contains("대체 항목을 선택하세요"))
        assertFalse(prompt.contains("other-app-private"))
        assertFalse(prompt.contains("123456"))
        assertFalse(PlannerObservation.render(after.copy(elements = after.elements.map { it.copy(sensitive = true) }),
            "기본 항목 실행").contains("recent_toasts_read_only"))
        assertFalse(PlannerObservation.render(after.copy(userIntervention = UserIntervention.Kind.DEVICE_UNLOCK),
            "기본 항목 실행").contains("recent_toasts_read_only"))
        assertFalse(GoalCheck("toast_event=1", after.recentToasts.first().text).matches(after))
        assertEquals(before.elements, after.elements)
    }

    @Test fun anUnverifiedToastActionCannotBeErasedToLearnAFabricatedSuccessfulRoute() {
        val before = screen()
        val after = before.copy(recentToasts = listOf(ToastMessage(1, "app", "처리 결과를 확인하세요", 10)))
        val trace = AutonomySession.Trace(AgentAction(ActionType.CLICK, "기본 항목", "0"), "app", "A", false,
            "토스트 응답", "app", "A", false, "A", "A", postconditionMismatch = true,
            beforeObservation = before, afterObservation = after)
        assertTrue(SkillLearner.shortestTrace(listOf(trace, trace.copy(succeeded = true, beforeFingerprint = "A",
            afterFingerprint = "B", beforeTemplateFingerprint = "A", afterTemplateFingerprint = "B"))).isEmpty())
    }

    private fun screen() = UiSnapshot("app", "앱", epoch = 1, elements = listOf(UiElement("0", null,
        "android.widget.Button", "기본 항목", null, ScreenBounds(0, 0, 100, 100), true, false, false, true, true, false)))
}
