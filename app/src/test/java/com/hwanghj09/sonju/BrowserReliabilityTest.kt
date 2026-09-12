package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.*
import com.hwanghj09.sonju.task.DeterministicTaskParser
import com.hwanghj09.sonju.verifier.SearchSubmissionPolicy
import org.junit.Assert.*
import org.junit.Test

class BrowserReliabilityTest {
    @Test fun browserReadingUsesBodyEvidenceAndKeepsLocalOcrOutOfExecutableNodes() {
        val command = "현재 웹페이지 읽어줘"
        val missing = rendererScreen().copy(packageName = "com.sec.android.app.sbrowser")
        assertTrue(ScreenExplainer.needsLocalPageRead(command, missing))
        val read = missing.copy(localReadOnlyText = listOf("Example Domain", "This domain is for documentation examples.",
            "새로고침 검색 메뉴", "OTP 987654"))
        val explanation = requireNotNull(ScreenExplainer.pageReadingExplanation(command, read))
        assertTrue(explanation.contains("documentation examples"))
        assertFalse(explanation.contains("새로고침"))
        assertFalse(explanation.contains("987654"))
        assertEquals(missing.elements, read.elements)
        assertFalse(read.compactText().contains("documentation examples"))
        assertNull(ScreenExplainer.pageReadingExplanation(command, missing))
        assertFalse(ScreenExplainer.needsLocalPageRead("이 페이지에서 어떻게 검색해?", missing))
        assertFalse(ScreenExplainer.needsLocalPageRead(command,
            missing.copy(elements = missing.elements + editor("private").copy(sensitive = true))))

        val paragraph = "This is a complete paragraph containing more than forty characters. ".repeat(5)
        val native = screen(label("0.web", "Page title", ScreenBounds(0, 0, 1000, 1700))
            .copy(className = "android.webkit.WebView"),
            label("0.web.body", paragraph, ScreenBounds(0, 100, 1000, 600)),
            editor("private draft"), label("0.toolbar", "Toolbar", ScreenBounds(0, 1800, 1000, 1950)))
        assertTrue(requireNotNull(ScreenExplainer.pageReadingExplanation(command, native)).contains(paragraph))
        assertFalse(requireNotNull(ScreenExplainer.pageReadingExplanation(command, native)).contains("private draft"))
        assertNull(InstalledApps.preferredWebBrowser(emptyList()))
        val chrome = InstalledApp("Chrome", "com.android.chrome")
        assertEquals(chrome, InstalledApps.preferredWebBrowser(listOf(
            InstalledApp("인터넷", "com.sec.android.app.sbrowser"), chrome)))
    }

    @Test fun appParticlesRequireCatalogEvidenceAndPreservePlaces() {
        val apps = listOf("설정", "새 도서관", "인터넷")
        for (origin in listOf("여기", "부산역", "집", "회사")) {
            val command = "${origin}에서 서울역까지 가는 기차표 예매해줘"
            val intent = DeterministicTaskParser.parse(command, apps)
            assertNull(intent.targetApp)
            assertNull(AppWorkflowRouter.route(command, apps))
            assertEquals(origin, intent.entities["origin"])
            assertEquals("서울역", intent.entities["destination"])
        }
        assertNull(DeterministicTaskParser.parse("여기에서 서울역까지 가는 기차표 예매해줘").targetApp)
        val command = "새 도서관에서 부산에서 출발하는 열차 검색해줘"
        assertEquals("새 도서관", DeterministicTaskParser.parse(command, apps).targetApp)
        assertEquals("부산에서 출발하는 열차", DeterministicTaskParser.parse(command, apps).entities["query"])
        assertNull(DeterministicTaskParser.parse(command, emptyList()).targetApp)
    }

    @Test fun searchAndUrlsUseTheObservedWholeValueWithoutRequiringAnExposedImeAction() {
        for (value in listOf("서울역 열차 예매", "https://example.com/?q=rail", "https://example.com/" + "x".repeat(250))) {
            val input = editor(value)
            val action = AgentAction(ActionType.SUBMIT_TEXT, "이동", input.path, value)
            assertTrue(SearchSubmissionPolicy.allows("열차편 알아봐줘", action, screen(input), input.path))
            assertFalse(SearchSubmissionPolicy.allows("열차편 알아봐줘", action.copy(value = "$value 다른 값"),
                screen(input), input.path))
            assertTrue(input.compactLine().contains(value))
        }
        for (hint in listOf("메시지 검색", "댓글", "password", "인증번호")) {
            val input = editor("테스트").copy(viewId = "app:id/input", hintText = hint)
            assertFalse(SearchSubmissionPolicy.allows("검색해줘", AgentAction(ActionType.SUBMIT_TEXT, "검색", input.path, "테스트"),
                screen(input), input.path))
        }
        for (value in listOf("javascript:alert(1)", "\u200e javascript:alert(1)", "123456")) {
            val input = editor(value)
            assertFalse(SearchSubmissionPolicy.allows("검색해줘", AgentAction(ActionType.SUBMIT_TEXT, "이동", input.path, value),
                screen(input), input.path))
        }
    }

    @Test fun keyboardWrapperChangesRetainOnlyAUniquelyIdentifiedEditor() {
        val input = editor("")
        val before = screen(input)
        val moved = input.copy(path = "0.keyboard.editor", text = "기차", bounds = ScreenBounds(100, 700, 900, 800))
        val after = screen(moved)
        assertEquals(moved, UiTargetResolver.rebindEditable(before, after, input.path))
        for (invalid in listOf(after.copy(packageName = "another.app"), after.copy(windowId = 2),
            screen(moved, moved.copy(path = "0.duplicate")), screen(moved.copy(sensitive = true)),
            screen(moved.copy(editable = false)))) {
            assertNull(UiTargetResolver.rebindEditable(before, invalid, input.path))
        }
    }

    @Test fun genericSearchUsesTheAddressEditorBeforeAnAdjacentSearchEngineButton() {
        val command = "인터넷에서 기차 시간표 검색해줘"
        val route = requireNotNull(AppWorkflowRouter.route(command, listOf("인터넷")))
        val input = editor("example.com").copy(hintText = null)
        val button = label("0.engine", "검색", ScreenBounds(0, 1700, 100, 1800)).copy(clickable = true)
        val type = AppWorkflowRouter.inAppPlan(command, route, screen(input, button))
        assertEquals(ActionType.SET_TEXT, type?.actions?.first()?.type)
        assertEquals(input.path, type?.actions?.first()?.target)
        val submit = AppWorkflowRouter.inAppPlan(command, route, screen(input.copy(text = "기차 시간표"), button))
        assertEquals(ActionType.SUBMIT_TEXT, submit?.actions?.first()?.type)
        assertEquals(input.path, submit?.actions?.first()?.target)
        assertNull(AppWorkflowRouter.inAppPlan(command, route, screen(input.copy(text = "search.example.com")),
            successfulActions = listOf(requireNotNull(submit).actions.first())))
    }

    @Test fun anEmptyProgressContainerIsNotAnActiveLoadingIndicator() {
        val container = label("0.progress", "", ScreenBounds(0, 0, 1000, 30))
            .copy(viewId = "app:id/toolbar_progress_container", className = "android.widget.FrameLayout")
        assertFalse(ScreenContextHandoff.hasVisibleLoadingIndicator(screen(container)))
        assertTrue(ScreenContextHandoff.hasVisibleLoadingIndicator(screen(container.copy(className = "android.widget.ProgressBar"))))
        assertTrue(ScreenContextHandoff.hasVisibleLoadingIndicator(screen(container.copy(text = "로딩 중"))))
    }

    @Test fun toolbarSemanticsDoNotStandInForUnexposedRendererContent() {
        val missing = rendererScreen()
        assertTrue(ScreenContextHandoff.hasUnobservedRenderedContent(missing))
        val fallback = ScreenExplainer.explain("현재 페이지 읽어줘", "앱", missing)
        assertTrue(fallback.contains("본문"))
        assertFalse(fallback.contains("새로고침"))
        val body = label("0.body", "출발역 선택", ScreenBounds(0, 400, 1000, 600))
        assertFalse(ScreenContextHandoff.hasUnobservedRenderedContent(missing.copy(elements = missing.elements + body)))
        assertFalse(ScreenContextHandoff.hasUnobservedRenderedContent(screen(body)))
        assertFalse(ScreenContextHandoff.hasUnobservedRenderedContent(screen(missing.elements.first()
            .copy(bounds = ScreenBounds(0, 0, 200, 200)))))
        for (command in listOf("현재 웹페이지의 제목과 본문 내용을 읽어줘", "이 페이지를 설명해줘", "현재 문서 요약해줘")) {
            assertTrue(ScreenExplainer.isExplanationRequest(command))
            assertTrue(ScreenExplainer.needsScreenshotFallback(command, missing))
        }
        assertFalse(ScreenExplainer.isExplanationRequest("현재 웹페이지에서 예약 내역 조회해줘"))
    }

    @Test fun twoMissingBodyObservationsEnableOnlyTheExistingBoundedVisualRecovery() {
        val missing = rendererScreen()
        val run = AutonomySession("현재 페이지를 읽어줘", missing, 0)
        assertFalse(run.canAttemptVisualFallback(missing))
        assertFalse(run.recordUnobservedRenderedContent(missing, missing.copy(treeTruncated = true)))
        assertFalse(run.recordUnobservedRenderedContent(missing, missing.copy(packageName = "other.app")))
        assertTrue(run.recordUnobservedRenderedContent(missing, missing.copy(epoch = 2)))
        assertTrue(run.reserveVisualFallback(missing, "frame-1"))
        assertFalse(run.reserveVisualFallback(missing, "frame-1"))
        assertTrue(run.reserveVisualFallback(missing, "frame-2"))
        assertFalse(run.reserveVisualFallback(missing, "frame-3"))
        val submit = AgentPlan("검색", "검색", RiskLevel.LOW, .95,
            listOf(AgentAction(ActionType.SUBMIT_TEXT, "검색", "0.editor", "열차")), PlanSource.LOCAL_RULE)
        for (confirmed in listOf(false, null)) {
            run.recordExecution(submit, missing, ExecutionResult(true, "unverified", 1,
                beforeFingerprint = "before", afterFingerprint = "after", postconditionSatisfied = confirmed))
            assertFalse(run.reserveVisualFallback(missing, "frame-3"))
        }
        run.recordExecution(submit, missing, ExecutionResult(true, "observed navigation", 1,
            beforeFingerprint = "before", afterFingerprint = "after", postconditionSatisfied = true))
        assertTrue(run.recordUnobservedRenderedContent(missing, missing.copy(epoch = 3)))
        assertTrue(run.reserveVisualFallback(missing, "frame-3"))
        assertFalse(run.reserveVisualFallback(missing, "frame-4"))
    }

    @Test fun toolbarTextCannotVerifyCompletionWhenTheRequestedPageBodyIsUnobserved() {
        val runtime = runtime()
        val snapshot = rendererScreen()
        val plan = AgentPlan("열차 검색", "결과 확인", RiskLevel.LOW, .99,
            listOf(AgentAction(ActionType.FINISH, "완료")), PlanSource.OPENAI_STRUCTURE,
            goalCompleted = true, goalChecks = listOf(GoalCheck("0.toolbar", "새로고침 검색 메뉴")))
        assertTrue(plan.goalChecks.single().matches(snapshot))
        assertFalse(runtime.goalSatisfied("열차 검색해줘", plan, snapshot))
    }

    @Test fun missingScrollNodesRequireAFreshVerifiedImageAndOneObservedRenderer() {
        val runtime = runtime()
        val snapshot = rendererScreen()
        val plan = AgentPlan("본문 확인", "아래 본문 확인", RiskLevel.LOW, .99,
            listOf(AgentAction(ActionType.SCROLL_DOWN, "본문 아래 보기")), PlanSource.OPENAI_SEMANTIC_MAP,
            visualFallback = true, visualFrameHash = "captured-frame", visualFrameVerified = true)
        val allowed = runtime.verify("아래 내용을 확인해줘", plan, snapshot)
            as com.hwanghj09.sonju.verifier.VerificationResult.Allowed
        assertEquals("0.surface", allowed.verifiedPlan.actions.values.single().resolvedNodeId)
        assertTrue(allowed.verifiedPlan.actions.values.single().visualFallback)
        for (unverified in listOf(plan.copy(visualFrameVerified = false), plan.copy(visualFrameHash = null),
            plan.copy(source = PlanSource.OPENAI_STRUCTURE), plan.copy(visualFallback = false))) {
            assertFalse(runtime.verify("아래 내용을 확인해줘", unverified, snapshot)
                is com.hwanghj09.sonju.verifier.VerificationResult.Allowed)
        }
        for (unsafe in listOf(snapshot.copy(treeTruncated = true),
            snapshot.copy(elements = snapshot.elements + snapshot.elements.first().copy(path = "0.second")),
            snapshot.copy(elements = snapshot.elements + editor("private").copy(sensitive = true)))) {
            assertFalse(runtime.verify("아래 내용을 확인해줘", plan, unsafe)
                is com.hwanghj09.sonju.verifier.VerificationResult.Allowed)
        }
    }

    private fun runtime() = SonjuAgentRuntime.createForTest(com.hwanghj09.sonju.skill.InMemorySkillRepository(),
        object : com.hwanghj09.sonju.logging.ProcessLogRepository {
            override fun append(step: com.hwanghj09.sonju.logging.TraceStep) = Unit
            override fun exportRedacted(): List<String> = emptyList()
        })

    private fun editor(value: String) = label("0.editor", value, ScreenBounds(100, 1700, 900, 1800))
        .copy(viewId = "app:id/location_bar_edit_text", className = "android.widget.EditText", editable = true,
            focused = true, hintText = "웹 주소 입력", availableActions = setOf(UiNodeAction.SET_TEXT))
    private fun rendererScreen() = screen(
        label("0.surface", "", ScreenBounds(0, 0, 1000, 2000)).copy(className = "android.view.SurfaceView"),
        label("0.toolbar", "새로고침 검색 메뉴", ScreenBounds(0, 1800, 1000, 1950)),
    )
    private fun screen(vararg elements: UiElement) = UiSnapshot("app", "Browser", epoch = 1, windowId = 1,
        elements = elements.toList(), windowBounds = ScreenBounds(0, 0, 1000, 2000))
    private fun label(path: String, text: String, bounds: ScreenBounds) = UiElement(path, null,
        "android.widget.TextView", text, null, bounds, false, false, false, true, true, false)
}
