package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.*
import com.hwanghj09.sonju.verifier.SearchSubmissionPolicy as Policy
import com.hwanghj09.sonju.verifier.VerificationResult
import org.junit.Assert.*
import org.junit.Test

class SearchSubmissionTest {
    private val editor = UiElement("0.1", null, "android.widget.EditText", "치킨", null,
        ScreenBounds(100, 100, 900, 240), true, true, false, true, true, false,
        focused = true, availableActions = setOf(UiNodeAction.SET_TEXT, UiNodeAction.IME_ENTER))
    private fun label(path: String, text: String) = editor.copy(path = path, text = text, editable = false,
        focused = false, availableActions = emptySet())
    private fun screen(vararg nodes: UiElement) = UiSnapshot("some.app", null, epoch = 1, elements = nodes.toList(), windowId = 1)
    private val action = AgentAction(ActionType.SUBMIT_TEXT, "검색", editor.path, "치킨")
    private fun allowed(input: UiElement, vararg others: UiElement) =
        Policy.allows("치킨 검색해줘", action, screen(input, *others), input.path)

    @Test fun observedBaeminShapeUsesOnlyTheEditorsOwnSearchSemantics() {
        val clear = label("0.1.1", "").copy(contentDescription = "검색어 지우기")
        assertTrue(allowed(editor, clear))
        assertFalse(allowed(editor)) // IME_ENTER alone can also mean send, next or done.
        assertFalse(allowed(editor, clear.copy(path = "0.2")))
        assertFalse(allowed(editor, clear.copy(visible = false)))
        assertFalse(allowed(editor, clear.copy(sensitive = true)))
        assertFalse(allowed(editor, editor.copy(path = "0.1.2"), clear.copy(path = "0.1.2.1")))
        assertFalse(allowed(editor.copy(hintText = "메시지"), clear))
        assertFalse(allowed(editor.copy(text = "치킨피자"), clear))
    }

    @Test fun aValueMatchedImeSearchIdentifiesAnUnlabelledEditorInAnyLocale() {
        assertTrue(allowed(editor.copy(imeAction = 3)))
        for (actionId in listOf(0, 1, 2, 4, 5, 6, 7)) assertFalse(allowed(editor.copy(imeAction = actionId)))
        for (hint in listOf("댓글", "메시지", "password", "OTP")) {
            assertFalse(allowed(editor.copy(imeAction = 3, hintText = hint)))
        }
        for (actionId in listOf(4, 5, 7)) assertFalse(allowed(editor.copy(hintText = "검색", imeAction = actionId)))
        val input = editor.copy(imeAction = 3)
        assertTrue(input.compactLine().contains("imeAction=3"))
        assertFalse(input.copy(sensitive = true).compactLine().contains("imeAction="))
        assertNotEquals(screen(editor).screenFingerprint(), screen(input).screenFingerprint())
        assertTrue(screen(editor).hasSameObservableContentAs(screen(input)))
    }

    @Test fun connectionMatchingAcceptsUnknownOffsetsButNeverPartialOrDifferentValues() {
        for (offset in listOf(-1, 0)) {
            assertTrue(Policy.matchesConnectionValue("치킨", "치킨", offset))
            assertTrue(Policy.matchesConnectionValue(" 치킨 ", " 치킨 ", offset))
            assertFalse(Policy.matchesConnectionValue(" 치킨 ", "치킨", offset))
            assertFalse(Policy.matchesConnectionValue("치킨", "치킨피자", offset))
            assertFalse(Policy.matchesConnectionValue("치킨", null, offset))
        }
        assertFalse(Policy.matchesConnectionValue("치킨", "치킨", 1))
        assertFalse(Policy.matchesConnectionValue("", "", 0))
    }

    @Test fun editorAndKeyFallbacksRespectRealActionIdsAndMultilineEditors() {
        for (id in listOf(2, 3, 6)) assertEquals(id, Policy.editorAction(0x2000000 or id, 0, null))
        assertEquals(73, Policy.editorAction(3, 73, "검색"))
        assertEquals(73, Policy.editorAction(3, 73, "검색하기"))
        assertEquals(0, Policy.editorAction(3, 0, "Search"))
        assertEquals(42, Policy.editorAction(3, 42, "Search"))
        assertNull(Policy.editorAction(3, 42, "전송"))
        assertNull(Policy.editorAction(3, 42, "Search and send"))
        for (id in listOf(0, 1, 4, 5, 7)) assertNull(Policy.editorAction(id, 0, null))
        assertTrue(Policy.allowsEnterKey(3, 1))
        assertTrue(Policy.allowsEnterKey(0, 1))
        for (id in listOf(4, 5, 7)) assertFalse(Policy.allowsEnterKey(id, 1))
        assertFalse(Policy.allowsEnterKey(3 or 0x40000000, 1))
        assertFalse(Policy.allowsEnterKey(3, 1 or 0x20000))
    }

    @Test fun submissionRequiresResultContentInsteadOfFocusLayoutOrCapabilityChanges() {
        val before = screen(editor, label("0.2", "치킨피자"))
        fun effect(after: UiSnapshot) = Policy.hasObservedEffect(before, after.copy(epoch = 2), editor.path)
        assertFalse(effect(before))
        assertFalse(effect(before.copy(elements = before.elements.map { it.copy(focused = false,
            bounds = ScreenBounds(0, 0, 200, 60), imeAction = 3, availableActions = emptySet()) })))
        assertFalse(effect(screen(editor, label("0.3", "치킨피자"))))
        assertFalse(effect(screen(editor.copy(focused = false), label("0.2", "치킨피자"), label("0.3", "치킨마요"))))
        assertFalse(effect(screen(editor)))
        assertTrue(effect(screen(editor.copy(focused = false), label("0.2", "검색 결과 12개"))))
        assertTrue(effect(screen(editor, label("0.2", "검색 결과가 없습니다"))))
        assertFalse(effect(screen(editor, label("0.2", "로딩 중"))))
        assertFalse(effect(before.copy(packageName = "other.app")))
    }

    @Test fun disappearingPlaceholdersRebindWithoutAcceptingAnAmbiguousOrConflictingField() {
        val before = screen(editor.copy(hintText = "검색"))
        assertEquals(editor, UiTargetResolver.rebindEditable(before, screen(editor), editor.path))
        assertNull(UiTargetResolver.rebindEditable(before, screen(editor.copy(hintText = "댓글")), editor.path))
        assertNull(UiTargetResolver.rebindEditable(before, screen(editor).copy(windowId = 2), editor.path))
        val web = editor.copy(path = "0.2", hintText = "두 번 탭하여 Google에서 검색합니다.")
        assertEquals(web, UiTargetResolver.rebindEditable(before, screen(web), editor.path))
        assertNull(UiTargetResolver.rebindEditable(before, screen(web, web.copy(path = "0.3")), editor.path))
        val identified = editor.copy(viewId = "app:id/search")
        assertNull(UiTargetResolver.rebindEditable(screen(identified),
            screen(identified, identified.copy(path = "0.2")), identified.path))
    }

    @Test fun inputValueAloneCannotCompleteASearchEvenWhenTheModelClaimsSuccess() {
        val runtime = SonjuAgentRuntime.createForTest(com.hwanghj09.sonju.skill.InMemorySkillRepository(),
            object : com.hwanghj09.sonju.logging.ProcessLogRepository {
                override fun append(step: com.hwanghj09.sonju.logging.TraceStep) = Unit
                override fun exportRedacted(): List<String> = emptyList()
            })
        val snapshot = screen(editor.copy(imeAction = 3), label("0.1.1", "검색어 지우기"))
        val submit = AgentPlan("치킨 검색해줘", "검색", RiskLevel.LOW, .99, listOf(action), PlanSource.OPENAI_STRUCTURE)
        assertTrue(runtime.verify("치킨 검색해줘", submit, snapshot) is VerificationResult.Allowed)
        val finish = submit.copy(actions = listOf(AgentAction(ActionType.FINISH, "완료")),
            goalCompleted = true, goalChecks = listOf(GoalCheck(editor.path, "치킨")))
        assertFalse(runtime.goalSatisfied("치킨 검색해줘", finish, snapshot))
        val suggestions = snapshot.copy(elements = snapshot.elements + label("0.2", "치킨피자"))
        assertFalse(runtime.goalSatisfied("치킨 검색해줘", finish.copy(goalChecks = listOf(GoalCheck("0.2", "치킨피자"))), suggestions))
        val staleResults = snapshot.copy(elements = snapshot.elements + label("0.2", "피자 검색 결과"))
        val session = AutonomySession("치킨 검색해줘", staleResults, 0)
        session.recordExecution(submit.copy(actions = listOf(action.copy(type = ActionType.SET_TEXT))), staleResults,
            ExecutionResult(true, "typed", 1, postconditionSatisfied = true), editor.path)
        assertFalse(runtime.goalSatisfied("치킨 검색해줘", finish.copy(goalChecks = listOf(GoalCheck("0.2", "피자 검색 결과"))),
            staleResults, session))
    }

    @Test fun aRedactedBrowserUrlIsNotAnAuthenticationChallenge() {
        val address = editor.copy(viewId = "com.android.chrome:id/url_bar", sensitive = true, text = null)
        val browser = screen(address).copy(packageName = "com.android.chrome")
        assertNull(UserIntervention.required(browser, "검색해줘"))
        assertFalse(EssentialSafetyPolicy.allowsRemoteScreenshot(browser))
        assertTrue(address.sensitive)
        assertEquals(UserIntervention.Kind.LOGIN, UserIntervention.required(
            browser.copy(userIntervention = UserIntervention.Kind.LOGIN), "검색해줘"))
        assertEquals(UserIntervention.Kind.OTHER, UserIntervention.required(
            browser.copy(elements = listOf(address.copy(viewId = "page:input"))), "검색해줘"))
        assertEquals(UserIntervention.Kind.OTHER, UserIntervention.required(
            browser.copy(packageName = "some.app"), "검색해줘"))
    }

    @Test fun reopeningASubmittedSearchCannotTurnAutocompleteIntoResults() {
        val snapshot = screen(editor.copy(hintText = "검색"), label("0.2", "이전 검색결과"))
        val session = AutonomySession("치킨 검색해줘", snapshot, 0)
        val plan = AgentPlan("치킨 검색해줘", "검색", RiskLevel.LOW, .99, listOf(action), PlanSource.OPENAI_STRUCTURE)
        session.recordExecution(plan, snapshot, ExecutionResult(true, "submitted", 1, postconditionSatisfied = true), editor.path)
        val click = AgentAction(ActionType.CLICK, "입력창", editor.path)
        assertNotNull(session.repeatedActionFailure(click, snapshot, editor.path))
        assertNull(session.repeatedActionFailure(AgentAction(ActionType.SET_TEXT, "새 검색어", editor.path, "피자"), snapshot, editor.path))
        val runtime = SonjuAgentRuntime.createForTest(com.hwanghj09.sonju.skill.InMemorySkillRepository(),
            object : com.hwanghj09.sonju.logging.ProcessLogRepository {
                override fun append(step: com.hwanghj09.sonju.logging.TraceStep) = Unit
                override fun exportRedacted(): List<String> = emptyList()
            })
        session.observe(snapshot.copy(epoch = 2, elements = snapshot.elements + label("0.3", "치킨피자")))
        // Even a historical/replayed click that bypassed the proposal guard must invalidate completion.
        session.recordExecution(plan.copy(actions = listOf(click)), snapshot,
            ExecutionResult(true, "opened editor", 1, postconditionSatisfied = true), editor.path)
        val suggestions = snapshot.copy(elements = snapshot.elements + label("0.3", "치킨피자"))
        assertFalse(runtime.goalSatisfied("치킨 검색해줘", plan.copy(goalCompleted = true,
            actions = listOf(AgentAction(ActionType.FINISH, "완료")), goalChecks = listOf(GoalCheck("0.3", "치킨피자"))), suggestions, session))
    }
}
