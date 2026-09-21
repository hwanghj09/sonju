package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.*
import com.hwanghj09.sonju.logging.ProcessLogRepository
import com.hwanghj09.sonju.logging.TraceStep
import com.hwanghj09.sonju.skill.*
import com.hwanghj09.sonju.task.TaskParameter
import com.hwanghj09.sonju.verifier.VerificationResult
import com.hwanghj09.sonju.verifier.WebNavigationPolicy
import org.junit.Assert.*
import org.junit.Test

class GeneralSkillLifecycleTest {
    private val repository = InMemorySkillRepository()
    private val runtime = SonjuAgentRuntime.createForTest(repository, object : ProcessLogRepository {
        override fun append(step: TraceStep) = Unit
        override fun exportRedacted(): List<String> = emptyList()
    })
    private val start = screen("목록", "open")
    private val editor = screen("", "input", editable = true)
    private fun command(value: String) = "별빛에 ${value}라고 적어줘"

    @Test fun liveModelRecognizesAnUnseenParaphraseAndBindsOnlyItsCurrentValue() {
        org.junit.Assume.assumeTrue(System.getenv("SONJU_LIVE_MODEL") == "1")
        val stored = learn(command("여행 준비"), "여행 준비")
        val request = "별빛 앱의 입력 칸에 회의 자료라는 문구를 남겨줘"
        val run = run(request)
        assertNull(runtime.fastPathPlan(request, start, run))
        val context = runtime.skillPlannerContext(request, start, run)
        assertTrue(run.reserveModelCall(1))
        com.hwanghj09.sonju.ai.OpenAiPlanner(installedApps = { listOf(InstalledApp("별빛", "org.starlight")) }).use { planner ->
            assertTrue(planner.isConfigured)
            val latch = java.util.concurrent.CountDownLatch(1)
            var result: Result<AgentPlan>? = null
            planner.planAsync(request, start, null, autonomyContext = context, callback = {
                result = it
                latch.countDown()
            })
            assertTrue(latch.await(45, java.util.concurrent.TimeUnit.SECONDS))
            val candidate = requireNotNull(result).getOrThrow()
            assertEquals(stored.skillId, candidate.skillReuse?.skillId)
            assertEquals(mapOf("input1" to "회의 자료"), candidate.skillReuse?.parameters)
            assertNotNull(runtime.reuseSuggestedSkill(request, candidate, start, run))
        }
    }

    @Test fun unseenRequestLearnsThenChangedInputsAndParaphrasesReplayAcrossStorageReload() {
        val request = command("여행 준비")
        assertNull(runtime.fastPathPlan(request, start, run(request)))
        val skill = learn(request, "여행 준비")
        val encoded = SkillCodec.encode(skill)
        assertFalse(encoded.contains("여행 준비"))
        assertFalse(encoded.contains(request))
        assertEquals(skill, SkillCodec.decode(encoded))
        repository.save(requireNotNull(SkillCodec.decode(encoded)))

        for (value in listOf("오후 일정", "Test ABC", "가", "회의 자료와 우산", "a_b", "18시 할 일")) {
            val changed = command(value)
            val replay = run(changed)
            execute(replay, requireNotNull(runtime.fastPathPlan(changed, start, replay)) {
                "No entry for $value: binding=${skill.requestPatterns.map { it.bind(changed) }}; ${replay.plannerContext(start, null)}"
            }, start, editor)
            val input = requireNotNull(runtime.fastPathPlan(changed, editor, replay)) {
                "No input step for $value: ${replay.plannerContext(editor, null)}"
            }
            assertEquals(value, input.actions.first().value)
            assertTrue(runtime.verify(changed, input, editor) is VerificationResult.Allowed)
            val result = screen(value, "result")
            execute(replay, input, editor, result)
            val done = replay.acceptPlan(requireNotNull(runtime.fastPathPlan(changed, result, replay)), result)
            assertTrue(runtime.goalSatisfied(changed, done, result, replay))
            assertFalse(runtime.goalSatisfied(changed, done, screen("여행 준비", "result"), replay))
            assertEquals(0, replay.modelCallCount)
        }

        val paraphrase = "남길 내용은 오후 일정이야"
        val first = run(paraphrase)
        assertNull(runtime.fastPathPlan(paraphrase, start, first))
        assertTrue(runtime.skillPlannerContext(paraphrase, start, first).contains(skill.skillId))
        assertTrue(first.reserveModelCall(1))
        val suggestion = model(AgentAction(ActionType.CLICK, "입력 화면", "org.starlight:id/open"))
            .copy(skillReuse = SkillReuseSuggestion(skill.skillId, mapOf("input1" to "오후 일정")))
        execute(first, requireNotNull(runtime.reuseSuggestedSkill(paraphrase, suggestion, start, first)), start, editor)
        val result = screen("오후 일정", "result")
        execute(first, requireNotNull(runtime.fastPathPlan(paraphrase, editor, first)), editor, result)
        first.acceptPlan(requireNotNull(runtime.fastPathPlan(paraphrase, result, first)), result)
        runtime.rememberSuccessfulSkill(paraphrase, first, result)
        assertEquals(1, first.modelCallCount)
        val again = "남길 내용은 다른 준비물이야"
        assertNotNull(runtime.fastPathPlan(again, start, run(again)))
        assertNull(runtime.fastPathPlan("별빛에 오후 일정이라고 적지 말아줘", start, run("별빛에 오후 일정이라고 적지 말아줘")))
        assertNull(runtime.fastPathPlan(command("오후 일정"), start.copy(packageName = "org.impostor"), run(command("오후 일정"))))
    }

    @Test fun repeatedFailureHandsOffAtTheFailedStepAndReplacesOnlyAfterVerifiedCompletion() {
        val request = command("여행 준비")
        val stored = learn(request, "여행 준비")
        val replay = run(request)
        execute(replay, requireNotNull(runtime.fastPathPlan(request, start, replay)), start, editor)
        val failed = requireNotNull(runtime.fastPathPlan(request, editor, replay))
        runtime.recordPlanningFailure(failed, replay, "target temporarily unavailable", editor, true)
        assertFalse(replay.aiRecoveryRequested)
        assertNotNull(runtime.fastPathPlan(request, editor, replay))
        runtime.recordPlanningFailure(failed, replay, "target still unavailable", editor, true)
        assertTrue(replay.aiRecoveryRequested)
        assertEquals(1, replay.skillRepair!!.stepIndex)
        assertEquals(1, replay.skillRepair!!.traceIndex)
        assertEquals(1L, repository.get(stored.skillId)!!.failureCount)
        runtime.recordPlanningFailure(failed, replay, "same failure")
        assertEquals(1L, repository.get(stored.skillId)!!.failureCount)
        assertTrue(replay.plannerContext(editor, null).contains("현재 화면부터"))
        assertNull(runtime.fastPathPlan(request, editor, replay))

        val alternative = screen("", "new_input", editable = true)
        execute(replay, model(AgentAction(ActionType.CLICK, "다른 입력", "org.starlight:id/new")), editor, alternative)
        runtime.rememberSuccessfulSkill(request, replay, alternative)
        assertEquals(stored.version, repository.get(stored.skillId)!!.version)
        val result = screen("여행 준비", "result")
        execute(replay, model(AgentAction(ActionType.SET_TEXT, "입력", "org.starlight:id/new_input", "여행 준비")), alternative, result)
        replay.acceptPlan(completion(result), result)
        runtime.rememberSuccessfulSkill(request, replay, result)
        val repaired = requireNotNull(repository.get(stored.skillId))
        assertEquals(stored.version + 1, repaired.version)
        assertEquals(SkillStatus.ACTIVE, repaired.status)
        assertEquals(stored.steps.first(), repaired.steps.first())
        assertEquals("org.starlight:id/new_input", repaired.steps.last().action.targetTemplate)
    }

    @Test fun unrelatedPageChangesPreserveVerifiedTargetButChangedMeaningOrAppDoesNot() {
        val request = command("여행 준비")
        learn(request, "여행 준비")
        val replay = run(request)
        execute(replay, requireNotNull(runtime.fastPathPlan(request, start, replay)), start, editor)
        val changed = editor.copy(elements = editor.elements + start.elements.single().copy(
            path = "0.8", viewId = "org.starlight:id/history", text = "새 검색 이력"))
        assertNotEquals(editor.semanticTemplateFingerprint(), changed.semanticTemplateFingerprint())
        val next = requireNotNull(runtime.fastPathPlan(request, changed, replay))
        assertEquals(PlanSource.SKILL_FAST_PATH, next.source)
        assertEquals("여행 준비", next.actions.first().value)
        assertTrue(runtime.verify(request, next, changed) is VerificationResult.Allowed)
        assertNull(runtime.fastPathPlan(request, changed, run(request)))
        val wrongInput = changed.copy(elements = changed.elements.map {
            if (it.editable) it.copy(hintText = "다른 기능 입력란") else it })
        assertNull(runtime.fastPathPlan(request, wrongInput, replay))
        assertTrue(replay.aiRecoveryRequested)
    }

    @Test fun repairUpdatesTheObservedPrefixWhenAppEntryWasSkipped() {
        val request = command("여행 준비")
        val launcher = start.copy(packageName = "org.launcher")
        val learning = AutonomySession(request, launcher, 0)
        execute(learning, model(AgentAction(ActionType.OPEN_APP, "앱 열기", "org.starlight")), launcher, start)
        execute(learning, model(AgentAction(ActionType.CLICK, "편집", "org.starlight:id/open")), start, editor)
        val result = screen("여행 준비", "result")
        execute(learning, model(AgentAction(ActionType.SET_TEXT, "입력", "org.starlight:id/input", "여행 준비")), editor, result)
        learning.acceptPlan(completion(result), result)
        runtime.rememberSuccessfulSkill(request, learning, result)
        val original = repository.all().single()
        val replay = run(request)
        val first = requireNotNull(runtime.fastPathPlan(request, start, replay))
        assertEquals(1, replay.nextSkillStep)
        val changedEditor = editor.copy(elements = editor.elements.map { it.copy(viewId = "org.starlight:id/new_input") })
        execute(replay, first, start, changedEditor)
        assertNull(runtime.fastPathPlan(request, changedEditor, replay))
        assertEquals(2, replay.skillRepair!!.stepIndex)
        execute(replay, model(AgentAction(ActionType.SET_TEXT, "입력", "org.starlight:id/new_input", "여행 준비")), changedEditor, result)
        replay.acceptPlan(completion(result), result)
        runtime.rememberSuccessfulSkill(request, replay, result)
        val repaired = repository.all().single()
        assertEquals(original.skillId, repaired.skillId)
        assertEquals(original.version + 1, repaired.version)
        assertEquals(SkillStatus.ACTIVE, repaired.status)
        assertEquals(original.steps.first(), repaired.steps.first())
        assertEquals("org.starlight:id/new_input", repaired.steps.last().action.targetTemplate)
    }

    @Test fun overlappingInputsFromARepairRemainBoundToTheExactCurrentRequest() {
        val request = command("여행 준비")
        val learning = run(request)
        execute(learning, model(AgentAction(ActionType.SET_TEXT, "입력", "org.starlight:id/input", "여행")), editor, editor.copy(epoch = 2))
        val result = screen("여행 준비", "result")
        execute(learning, model(AgentAction(ActionType.SET_TEXT, "입력", "org.starlight:id/input", "여행 준비")), editor.copy(epoch = 2), result)
        learning.acceptPlan(completion(result), result)
        runtime.rememberSuccessfulSkill(request, learning, result)
        val stored = repository.all().single()
        assertEquals(2, stored.parameters.size)
        assertTrue(stored.parameters.all { it.requestStart != null && it.requestEnd != null })
        assertFalse(SkillCodec.encode(stored).contains("여행"))
        val replay = AutonomySession(request, editor, 0)
        val first = requireNotNull(runtime.fastPathPlan(request, editor, replay))
        assertEquals("여행", first.actions.first().value)
        execute(replay, first, editor, editor.copy(epoch = 2))
        assertEquals("여행 준비", requireNotNull(runtime.fastPathPlan(request, editor.copy(epoch = 2), replay)).actions.first().value)
        val changed = command("다른 준비")
        assertNull(runtime.fastPathPlan(changed, editor, AutonomySession(changed, editor, 0)))
        repository.save(stored.copy(parameters = stored.parameters.map { it.copy(requestStart = null, requestEnd = null) }))
        val migration = AutonomySession(request, editor, 0)
        runtime.skillPlannerContext(request, editor, migration)
        val suggested = model(AgentAction(ActionType.SET_TEXT, "입력", "org.starlight:id/input", "여행"))
            .copy(skillReuse = SkillReuseSuggestion(stored.skillId, mapOf("input1" to "여행", "input2" to "여행 준비")))
        execute(migration, requireNotNull(runtime.reuseSuggestedSkill(request, suggested, editor, migration)), editor, editor.copy(epoch = 2))
        execute(migration, requireNotNull(runtime.fastPathPlan(request, editor.copy(epoch = 2), migration)), editor.copy(epoch = 2), result)
        migration.acceptPlan(requireNotNull(runtime.fastPathPlan(request, result, migration)), result)
        runtime.rememberSuccessfulSkill(request, migration, result)
        assertTrue(repository.all().single().parameters.all { it.requestStart != null && it.requestEnd != null })
    }

    @Test fun anAlreadyVisibleReadoutCanBeLearnedWithoutInventingAToolCall() {
        val request = "별빛 공지 내용을 알려줘"
        val result = screen("오늘의 공지: 도서관이 오후 6시에 닫습니다", "notice")
        val learning = AutonomySession(request, result, 0)
        learning.reserveModelCall(1)
        learning.acceptPlan(completion(result), result)
        runtime.rememberSuccessfulSkill(request, learning, result)
        assertTrue(repository.all().single().steps.isEmpty())
        val replay = AutonomySession(request, result, 0)
        val done = requireNotNull(runtime.fastPathPlan(request, result, replay))
        assertTrue(runtime.goalSatisfied(request, done, result, replay))
        assertEquals(0, replay.toolCallCount)
        assertEquals(0, replay.modelCallCount)
        assertFalse(runtime.goalSatisfied(request, done, screen("휴관 안내", "notice"), replay))
    }

    @Test fun nativeDeviceLockPausesWithoutModelCallsAndResumesTheOriginalGoal() {
        val request = "별빛 열어줘"
        val locked = start.copy(userIntervention = UserIntervention.Kind.DEVICE_UNLOCK)
        val run = AutonomySession(request, locked, 0)
        assertTrue(run.pauseForUser(requireNotNull(UserIntervention.plan(request, locked)), locked, 1))
        assertFalse(run.reserveModelCall(2))
        assertFalse(EssentialSafetyPolicy.allowsRemoteScreenshot(locked))
        assertTrue(runtime.verify(request, model(AgentAction(ActionType.OPEN_APP, "열기", "org.starlight")), locked) is VerificationResult.Blocked)
        assertFalse(run.resumeAfterUser(locked, 600_000))
        assertTrue(run.resumeAfterUser(start, 600_000))
        assertEquals(request, run.finalGoal)
        assertEquals(0, run.modelCallCount)
        assertTrue(run.canContinue(601_000))
    }

    @Test fun exactObservedPathsSurviveDuplicateLabelsAndFailedClicksLeaveModelChoices() {
        val wrapper = start.elements.single().copy(viewId = null, text = null)
        val duplicate = wrapper.copy(path = "0.2")
        val page = start.copy(elements = listOf(wrapper, wrapper.copy(path = "0.1", text = "동일 메뉴", clickable = false),
            duplicate, duplicate.copy(path = "0.2.1", text = "동일 메뉴", clickable = false)))
        assertEquals("0", ScreenContextHandoff.relocateClickTarget(page, page, "0"))
        val action = StoredSkillAction(ActionType.CLICK, "0", null)
        val identity = requireNotNull(action.targetIdentity(page, emptyMap()))
        assertEquals(identity, action.targetIdentity(page.copy(elements = page.elements +
            wrapper.copy(path = "1", text = "부수 안내")), emptyMap()))
        assertNotEquals(identity, action.targetIdentity(page.copy(elements = page.elements.map {
            if (it.path == "0.1") it.copy(text = "다른 기능") else it }), emptyMap()))
        assertNull(ScreenContextHandoff.relocateClickTarget(page, page.copy(epoch = page.epoch + 1), "0"))
        assertNull(ScreenContextHandoff.relocateClickTarget(page,
            page.copy(elements = page.elements.map { if (it.path == "0.1") it.copy(text = "변경된 메뉴") else it }), "0"))
        val session = AutonomySession("동일 메뉴 열기", page, 0)
        repeat(2) { session.recordPlanningRejection(model(AgentAction(ActionType.CLICK, "메뉴", "0")), page, "repeated failure") }
        val excluded = session.failedClickPaths(page)
        assertTrue("0" in excluded && "0.1" in excluded)
        com.hwanghj09.sonju.ai.OpenAiPlanner(apiKey = "test").use { planner ->
            val schema = planner.buildPlanRequest("메뉴 열기", page, excludedClickPaths = excluded)
                .getJSONObject("text").getJSONObject("format").getJSONObject("schema")
            val choices = schema.getJSONObject("properties").getJSONObject("actions").getJSONObject("items").getJSONArray("anyOf")
            val clicks = choices.getJSONObject(1).getJSONObject("properties").getJSONObject("target").getJSONArray("enum")
            assertFalse((0 until clicks.length()).map { clicks.getString(it) }.any { it in setOf("node=0", "node=1") })
        }
    }

    @Test fun liveModelUsesAnotherToolWhenClicksAreExcluded() {
        org.junit.Assume.assumeTrue(System.getenv("SONJU_LIVE_MODEL") == "1")
        val search = editor.copy(elements = listOf(start.elements.single().copy(text = "배터리 정보"),
            editor.elements.single().copy(path = "0.1", viewId = "org.starlight:id/search", hintText = "검색")))
        com.hwanghj09.sonju.ai.OpenAiPlanner().use { planner ->
            val latch = java.util.concurrent.CountDownLatch(1)
            var response: Result<AgentPlan>? = null
            planner.planAsync("검색창에 배터리 정보를 입력해줘", search, null,
                autonomyContext = "결과 버튼 클릭이 반복 실패했다. 검색 입력란으로 다른 경로를 사용한다.",
                excludedClickPaths = setOf("0", "0.1"), callback = { response = it; latch.countDown() })
            assertTrue(latch.await(45, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(ActionType.SET_TEXT, requireNotNull(response).getOrThrow().actions.first().type)
        }
    }

    @Test fun changedInputsCannotReuseStaticOldResultAndModelCannotInjectUnrequestedValues() {
        val request = command("오전 일정")
        val stored = learn(request, "오전 일정", "저장 결과 A")
        val changed = command("오후 일정")
        val replay = run(changed)
        execute(replay, requireNotNull(runtime.fastPathPlan(changed, start, replay)), start, editor)
        val oldResult = screen("저장 결과 A", "result")
        execute(replay, requireNotNull(runtime.fastPathPlan(changed, editor, replay)), editor, oldResult)
        val done = runtime.fastPathPlan(changed, oldResult, replay)
        assertTrue(done == null || !runtime.goalSatisfied(changed, done, oldResult, replay))
        val unknown = run("다른 표현")
        runtime.skillPlannerContext("다른 표현", start, unknown)
        assertNull(runtime.reuseSuggestedSkill("다른 표현", model(AgentAction(ActionType.BACK, "뒤로"))
            .copy(skillReuse = SkillReuseSuggestion(stored.skillId, mapOf("input1" to "모델이 만든 값"))), start, unknown))
    }

    @Test fun routeOptimizationPreservesEffectsAndCannotInventARepairBridge() {
        fun trace(a: String, b: String, type: ActionType) = AutonomySession.Trace(
            AgentAction(type, "action", "org.starlight:id/item", if (type == ActionType.SET_TEXT) "x" else null),
            "org.starlight", a, true, "ok", "org.starlight", b, a != b, a, b)
        val history = listOf(trace("A", "B", ActionType.CLICK), trace("B", "B", ActionType.SET_TEXT),
            trace("B", "C", ActionType.CLICK), trace("C", "B", ActionType.BACK), trace("B", "D", ActionType.CLICK))
        val optimized = SkillLearner.shortestTrace(history)
        assertEquals(listOf(ActionType.CLICK, ActionType.SET_TEXT, ActionType.CLICK), optimized.map { it.action.type })
        assertEquals(listOf("A", "B", "B"), optimized.map { it.beforeFingerprint })
        val stored = learn(command("x"), "x")
        assertNull(SkillLearner.repair(stored, 1, stored.copy(entryFingerprint = "unobserved")))
        assertEquals("${'$'}{second}", ParameterFiller.fill("${'$'}{first}", mapOf(
            "first" to TaskParameter("first", "${'$'}{second}"), "second" to TaskParameter("second", "wrong"))))
    }

    @Test fun webNavigationAllowsGeneralSitesButNeverNonWebSchemesOrCredentials() {
        for (url in listOf("https://example.org/topics/abc?q=tree", "https://www.wikipedia.org", "http://example.org/")) {
            assertTrue(WebNavigationPolicy.allows(url))
            val action = model(AgentAction(ActionType.OPEN_URL, "웹 탐색", url))
            assertTrue(runtime.verify("웹에서 자료 찾아줘", action, start) is VerificationResult.Allowed)
        }
        for (url in listOf("javascript:alert(1)", "file:///sdcard/private", "intent://x", "https://user:password@example.org/",
            "https://example.org\\@evil.test", "https://example.org\n.evil.test", "https://")) {
            assertFalse(WebNavigationPolicy.allows(url))
        }
        val task = com.hwanghj09.sonju.task.CanonicalTask("any", "navigate", emptyMap(), emptyList(),
            com.hwanghj09.sonju.task.TaskRisk.LOW)
        val history = listOf(AutonomySession.Trace(AgentAction(ActionType.OPEN_URL, "웹", "https://example.org/", "com.android.chrome"),
            "org.starlight", "A", true, "ok", "com.android.chrome", "B", true, "A", "B"))
        val stored = requireNotNull(SkillLearner.learn(task, history, "A", "B"))
        val parameterized = SkillLearner.bindInputs(task.copy(requestText = "https://example.org/ 열어줘"), history)
        val website = requireNotNull(SkillLearner.learn(parameterized, history, "A", "B"))
        assertEquals("${'$'}{input1}", website.steps.single().action.targetTemplate)
        assertEquals("https://another.test/", website.requestPatterns.single().bind("https://another.test/ 열어줘")?.get("input1")?.value)
        val replay = FastPathPlanner().planStep(task,
            com.hwanghj09.sonju.perception.AccessibilityScreenParser.parse(start).copy(fingerprint = "A"), stored, 0)
        assertEquals("com.android.chrome", (replay!!.steps.single().action as com.hwanghj09.sonju.planner.PlannedAction.OpenUrl).browserPackage)
        assertFalse(WebNavigationPolicy.arrived("https://example.org", start.copy(
            packageName = "com.android.chrome", elements = start.elements.map { it.copy(text = "https://example.org") })))
        val browser = start.copy(packageName = "com.android.chrome", elements = start.elements.map {
            it.copy(viewId = "com.android.chrome:id/url_bar", text = "https://example.org") })
        assertTrue(WebNavigationPolicy.arrived("https://example.org:443", browser))
        assertTrue(WebNavigationPolicy.arrived("http://example.org:80", browser))
        assertFalse(WebNavigationPolicy.arrived("https://example.org:8443", browser))
    }

    @Test fun compositeResultEvidenceReplaysNewInputsWithoutStoringTheResult() {
        val stored = learn(command("여행 준비"), "여행 준비", "작성 완료: 여행 준비 (저장됨)")
        val encoded = SkillCodec.encode(stored)
        assertFalse(encoded.contains("여행 준비"))
        assertFalse(encoded.contains("작성 완료"))
        repository.save(requireNotNull(SkillCodec.decode(encoded)))
        assertNotNull(stored.goalChecks.single().textPattern)
        val request = command("회의 자료")
        val replay = run(request)
        execute(replay, requireNotNull(runtime.fastPathPlan(request, start, replay)), start, editor)
        val result = screen("작성 완료: 회의 자료 (저장됨)", "result")
        execute(replay, requireNotNull(runtime.fastPathPlan(request, editor, replay)), editor, result)
        val done = replay.acceptPlan(requireNotNull(runtime.fastPathPlan(request, result, replay)), result)
        assertTrue(runtime.goalSatisfied(request, done, result, replay))
        assertFalse(runtime.goalSatisfied(request, done, screen("작성 완료: 여행 준비 (저장됨)", "result"), replay))
        assertFalse(runtime.goalSatisfied(request, done, screen("작성 실패: 회의 자료 (저장됨)", "result"), replay))
        assertEquals(0, replay.modelCallCount)
        runtime.rememberSuccessfulSkill(request, replay, result)
        assertEquals(1, repository.all().size)
    }

    @Test fun resultPatternsKeepLiteralNumbersNegationAndPoliteEndingsExact() {
        val parameters = mapOf("input1" to TaskParameter("input1", "1"))
        assertNull(SkillRequestPattern.captureText("가격 1000원", parameters))
        assertNull(SkillRequestPattern.captureText("피자헛", mapOf("input1" to TaskParameter("input1", "피자"))))
        val pattern = requireNotNull(SkillRequestPattern.captureText("결과: 1 / 1 확인해 주세요", parameters))
        val changed = mapOf("input1" to TaskParameter("input1", "2"))
        assertTrue(pattern.matchesText("결과: 2 / 2 확인해 주세요", changed))
        assertFalse(pattern.matchesText("결과: 2 / 1 확인해 주세요", changed))
        assertFalse(pattern.matchesText("결과: 2 / 2 확인해줘", changed))
        assertFalse(pattern.matchesText("결과: 2 / 2 확인하지 마세요", changed))
        assertFalse(pattern.matchesText("결과: 2 / 2 확인해 주세요", emptyMap()))
    }

    @Test fun concreteResultEvidenceDoesNotRequireTheOldScreenshotButVisualOnlyEvidenceDoes() {
        val request = command("여행 준비")
        val stored = learn(request, "여행 준비", "저장된 기록: 여행 준비")
        repository.save(stored.copy(exitVisualFrameHash = "old-image"))
        val replay = run(request)
        execute(replay, requireNotNull(runtime.fastPathPlan(request, start, replay)), start, editor)
        val result = screen("저장된 기록: 여행 준비", "result")
        execute(replay, requireNotNull(runtime.fastPathPlan(request, editor, replay)), editor, result)
        val done = requireNotNull(runtime.fastPathPlan(request, result, replay))
        assertNull(done.visualFrameHash)
        assertTrue(runtime.goalSatisfied(request, done, result, replay))
        repository.save(stored.copy(exitVisualFrameHash = "old-image", goalChecks = emptyList()))
        val visualOnly = requireNotNull(runtime.fastPathPlan(request, result, replay))
        assertEquals("old-image", visualOnly.visualFrameHash)
        assertFalse(runtime.goalSatisfied(request, visualOnly, result, replay))
    }

    @Test fun changedKoreanParticlesNeverBecomePartOfTheInputAndLearnAfterModelBinding() {
        val stored = learn(command("여름 준비"), "여름 준비", "저장된 기록: 여름 준비")
        val request = "별빛에 가을 일정이라고 적어줘"
        val run = run(request)
        assertNull(runtime.fastPathPlan(request, start, run))
        assertTrue(runtime.skillPlannerContext(request, start, run).contains(stored.skillId))
        run.reserveModelCall(1)
        val suggestion = model(AgentAction(ActionType.CLICK, "기록 편집", "org.starlight:id/open"))
            .copy(skillReuse = SkillReuseSuggestion(stored.skillId, mapOf("input1" to "가을 일정")))
        execute(run, requireNotNull(runtime.reuseSuggestedSkill(request, suggestion, start, run)), start, editor)
        val input = requireNotNull(runtime.fastPathPlan(request, editor, run))
        assertEquals("가을 일정", input.actions.first().value)
        val result = screen("저장된 기록: 가을 일정", "result")
        execute(run, input, editor, result)
        run.acceptPlan(requireNotNull(runtime.fastPathPlan(request, result, run)), result)
        runtime.rememberSuccessfulSkill(request, run, result)
        val nextRequest = "별빛에 겨울 일정이라고 적어줘"
        val next = run(nextRequest)
        execute(next, requireNotNull(runtime.fastPathPlan(nextRequest, start, next)), start, editor)
        assertEquals("겨울 일정", requireNotNull(runtime.fastPathPlan(nextRequest, editor, next)).actions.first().value)
        assertEquals(0, next.modelCallCount)
        val direction = requireNotNull(SkillRequestPattern.capture("경로를 서울로 설정해줘",
            mapOf("place" to TaskParameter("place", "서울"))))
        assertNull(direction.bind("경로를 서울역으로 설정해줘"))
    }

    @Test fun modelSuggestionForTheWrongScreenDoesNotDamageAHealthySkill() {
        val stored = learn(command("여행 준비"), "여행 준비")
        val request = "기록할 내용은 회의 자료야"
        val unrelated = screen("다른 작업", "another_workflow")
        val run = AutonomySession(request, unrelated, 0)
        runtime.skillPlannerContext(request, unrelated, run)
        run.reserveModelCall(1)
        val suggestion = model(AgentAction(ActionType.BACK, "편집 화면 찾기"))
            .copy(skillReuse = SkillReuseSuggestion(stored.skillId, mapOf("input1" to "회의 자료")))
        assertNull(runtime.reuseSuggestedSkill(request, suggestion, unrelated, run))
        assertNull(run.activeSkillId)
        assertNull(run.skillRepair)
        assertNull(run.consumeSkillFailure())
        assertEquals(stored, repository.get(stored.skillId))
    }

    @Test fun observedSearchInputKeepsAnUnusedParserPrefixFixedAndRepairKeepsOriginalSpans() {
        val request = "검색 연습에서 우주 여행을 검색해줘"
        val task = com.hwanghj09.sonju.task.CanonicalTask("any", "search",
            mapOf("query" to TaskParameter("query", "검색 연습에서 우주 여행")), emptyList(),
            com.hwanghj09.sonju.task.TaskRisk.LOW, requestKey = SkillRequestPattern.digest(request), requestText = request)
        val history = listOf(AutonomySession.Trace(AgentAction(ActionType.SET_TEXT, "검색어", "0.1", "우주 여행"),
            "org.starlight", "A", true, "ok", "org.starlight", "B", true, "A", "B"))
        val bound = SkillLearner.bindInputs(task, history)
        assertEquals(setOf("input1"), bound.parameters.keys)
        val stored = requireNotNull(SkillLearner.learn(bound, history, "A", "B"))
        assertEquals("더 긴 바다 여행", stored.requestPatterns.single()
            .bind("검색 연습에서 더 긴 바다 여행을 검색해줘")?.get("input1")?.value)
        assertNull(stored.requestPatterns.single().bind("다른 앱에서 우주 여행을 검색해줘"))
        val longer = bound.copy(requestText = "검색 연습에서 더 긴 바다 여행을 검색해줘",
            parameters = mapOf("input1" to TaskParameter("input1", "더 긴 바다 여행")))
        val replacement = requireNotNull(SkillLearner.learn(longer,
            history.map { it.copy(action = it.action.copy(value = "더 긴 바다 여행")) }, "A", "B"))
        assertNull(replacement.parameters.single().requestStart)
        val repaired = requireNotNull(SkillLearner.repair(stored, 0, replacement))
        assertEquals(stored.parameters.single().requestStart, repaired.parameters.single().requestStart)
        assertEquals(stored.parameters.single().requestEnd, repaired.parameters.single().requestEnd)
    }

    @Test fun completionRepairRemovesAnUnusedOverlappingParameterFromALegacyRoute() {
        val request = command("여행 준비")
        val stored = learn(request, "여행 준비")
        val broad = "별빛에 여행 준비"
        repository.save(stored.copy(parameters = stored.parameters + SkillParameter("query", requestStart = 0,
            requestEnd = broad.length), requestPatterns = emptyList()))
        val repair = run(request)
        execute(repair, requireNotNull(runtime.fastPathPlan(request, start, repair)), start, editor)
        val result = screen("저장된 기록: 여행 준비", "result")
        execute(repair, requireNotNull(runtime.fastPathPlan(request, editor, repair)), editor, result)
        assertNull(runtime.fastPathPlan(request, result, repair))
        assertEquals(2, repair.skillRepair!!.stepIndex)
        repair.acceptPlan(completion(result), result)
        runtime.rememberSuccessfulSkill(request, repair, result)
        val updated = repository.all().single()
        assertEquals(stored.skillId, updated.skillId)
        assertEquals(setOf("input1"), updated.parameters.map { it.name }.toSet())
        val changed = command("더 긴 회의 자료")
        val replay = run(changed)
        execute(replay, requireNotNull(runtime.fastPathPlan(changed, start, replay)), start, editor)
        assertEquals("더 긴 회의 자료", requireNotNull(runtime.fastPathPlan(changed, editor, replay)).actions.first().value)
        assertEquals(0, replay.modelCallCount)
    }

    @Test fun scrollReplayKeepsTheVerifiedContainerInAllFourDirections() {
        val directions = listOf(ActionType.SCROLL_UP to UiNodeAction.SCROLL_UP,
            ActionType.SCROLL_DOWN to UiNodeAction.SCROLL_DOWN,
            ActionType.SCROLL_LEFT to UiNodeAction.SCROLL_LEFT,
            ActionType.SCROLL_RIGHT to UiNodeAction.SCROLL_RIGHT)
        for ((type, supported) in directions) {
            val request = "별빛 내용을 $type 방향으로 더 보여줘"
            val body = start.elements.single().copy(path = "0.1", viewId = "org.starlight:id/body",
                className = "android.widget.ScrollView", clickable = false, scrollable = true,
                availableActions = setOf(supported))
            val page = start.copy(elements = listOf(body, body.copy(path = "0.2", viewId = "org.starlight:id/sidebar")))
            val result = screen("마지막 항목", "result")
            val learning = AutonomySession(request, page, 0)
            val action = model(AgentAction(type, "본문 이동", body.path))
            assertTrue(runtime.verify(request, action, page) is VerificationResult.Allowed)
            execute(learning, action, page, result, body.path)
            learning.acceptPlan(completion(result), result)
            runtime.rememberSuccessfulSkill(request, learning, result)
            val replay = AutonomySession(request, page, 0)
            val next = requireNotNull(runtime.fastPathPlan(request, page, replay))
            val stored = requireNotNull(repository.get(next.skillId!!))
            repository.save(requireNotNull(SkillCodec.decode(SkillCodec.encode(stored))))
            assertEquals(type, next.actions.first().type)
            assertEquals(body.viewId, next.actions.first().target)
            assertTrue(runtime.verify(request, next, page) is VerificationResult.Allowed)
            execute(replay, next, page, result, body.path)
            val done = requireNotNull(runtime.fastPathPlan(request, result, replay))
            assertTrue(runtime.goalSatisfied(request, done, result, replay))
            assertEquals(0, replay.modelCallCount)
        }
    }

    @Test fun repairFromAnotherAppReplacesTheActuallyObservedLaunchAndRecoveryRoute() {
        val request = command("여행 준비")
        val launcher = start.copy(packageName = "org.launcher")
        val learning = AutonomySession(request, launcher, 0)
        val launch = model(AgentAction(ActionType.OPEN_APP, "앱 열기", "org.starlight"))
        execute(learning, launch, launcher, start)
        execute(learning, model(AgentAction(ActionType.CLICK, "입력 화면", "org.starlight:id/open")), start, editor)
        val result = screen("여행 준비", "result")
        execute(learning, model(AgentAction(ActionType.SET_TEXT, "입력", "org.starlight:id/input", "여행 준비")), editor, result)
        learning.acceptPlan(completion(result), result)
        runtime.rememberSuccessfulSkill(request, learning, result)
        val original = repository.all().single()

        val anotherApp = start.copy(packageName = "org.other")
        val recovery = AutonomySession(request, anotherApp, 0)
        execute(recovery, requireNotNull(runtime.fastPathPlan(request, anotherApp, recovery)), anotherApp, start)
        val changedEditor = screen("", "new_input", editable = true)
        execute(recovery, requireNotNull(runtime.fastPathPlan(request, start, recovery)), start, changedEditor)
        assertNull(runtime.fastPathPlan(request, changedEditor, recovery))
        assertTrue(recovery.aiRecoveryRequested)
        assertEquals(2, recovery.skillRepair!!.stepIndex)
        execute(recovery, model(AgentAction(ActionType.SET_TEXT, "복구 입력", "org.starlight:id/new_input", "여행 준비")), changedEditor, result)
        recovery.acceptPlan(completion(result), result)
        runtime.rememberSuccessfulSkill(request, recovery, result)
        val repaired = repository.all().single()
        assertEquals(original.skillId, repaired.skillId)
        assertEquals(original.version + 1, repaired.version)
        assertEquals(SkillStatus.ACTIVE, repaired.status)
        assertEquals(anotherApp.skillFingerprint(emptyMap()), repaired.entryFingerprint)
        val replay = AutonomySession(request, launcher, 0)
        execute(replay, requireNotNull(runtime.fastPathPlan(request, launcher, replay)), launcher, start)
        execute(replay, requireNotNull(runtime.fastPathPlan(request, start, replay)), start, changedEditor)
        execute(replay, requireNotNull(runtime.fastPathPlan(request, changedEditor, replay)), changedEditor, result)
        assertTrue(runtime.goalSatisfied(request, requireNotNull(runtime.fastPathPlan(request, result, replay)), result, replay))
        assertEquals(0, replay.modelCallCount)
    }

    @Test fun freshToastFeedbackReturnsAnActiveSkillToTheModelAtItsCurrentStep() {
        val request = command("여행 준비")
        val stored = learn(request, "여행 준비")
        val replay = AutonomySession(request, start, 100)
        val oldFeedback = start.copy(recentToasts = listOf(ToastMessage(1, start.packageName, "이전 작업 안내", 50)))
        execute(replay, requireNotNull(runtime.fastPathPlan(request, oldFeedback, replay)), start, editor)
        val feedback = editor.copy(recentToasts = listOf(ToastMessage(2, editor.packageName, "입력 조건을 확인하세요", 120)))
        assertNull(runtime.fastPathPlan(request, feedback, replay))
        assertTrue(replay.aiRecoveryRequested)
        assertEquals(stored.skillId, replay.skillRepair!!.skillId)
        assertEquals(1, replay.skillRepair!!.stepIndex)
        assertEquals(stored.version, repository.get(stored.skillId)!!.version)
        assertFalse(runtime.goalSatisfied(request, model(AgentAction(ActionType.FINISH, "끝")).copy(
            goalCompleted = true, goalChecks = listOf(GoalCheck("toast_event=2", "입력 조건을 확인하세요"))), feedback, replay))
    }

    private fun learn(request: String, value: String, resultText: String = value): AppSkill {
        val learning = run(request)
        assertTrue(learning.reserveModelCall(1))
        execute(learning, model(AgentAction(ActionType.CLICK, "입력 화면", "org.starlight:id/open")), start, editor)
        val result = screen(resultText, "result")
        execute(learning, model(AgentAction(ActionType.SET_TEXT, "입력", "org.starlight:id/input", value)), editor, result)
        learning.acceptPlan(completion(result), result)
        runtime.rememberSuccessfulSkill(request, learning, result)
        return repository.all().single()
    }

    private fun run(request: String) = AutonomySession(request, start, 0)
    private fun execute(run: AutonomySession, plan: AgentPlan, before: UiSnapshot, after: UiSnapshot,
                        resolvedNodeId: String? = null) {
        val accepted = run.acceptPlan(plan, before)
        run.recordExecution(accepted, before, ExecutionResult(true, "ok", 1, postconditionSatisfied = true), resolvedNodeId)
        run.observe(after)
    }
    private fun model(action: AgentAction) = AgentPlan("immutable user request", "next", RiskLevel.LOW, .98,
        listOf(action), PlanSource.OPENAI_STRUCTURE, continueAfterAction = true)
    private fun completion(result: UiSnapshot) = model(AgentAction(ActionType.FINISH, "끝")).copy(
        goalCompleted = true, goalChecks = listOf(GoalCheck("0", result.elements.single().text)))
    private fun screen(text: String, id: String, editable: Boolean = false) = UiSnapshot("org.starlight", "Starlight", epoch = 1,
        elements = listOf(UiElement("0", "org.starlight:id/$id", if (editable) "android.widget.EditText" else "android.widget.Button",
            text, null, ScreenBounds(0, 0, 300, 100), !editable, editable, false, true, true, false)))
}
