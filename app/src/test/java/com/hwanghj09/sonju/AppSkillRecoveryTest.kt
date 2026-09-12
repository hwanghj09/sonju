package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.*
import com.hwanghj09.sonju.execution.ExecutionFailureReason
import com.hwanghj09.sonju.logging.ProcessLogRepository
import com.hwanghj09.sonju.logging.TraceStep
import com.hwanghj09.sonju.skill.*
import com.hwanghj09.sonju.task.*
import org.junit.Assert.*
import org.junit.Test

class AppSkillRecoveryTest {
    private val command = "테마를 바꿔줘"
    private val start = screen("설정")
    private val middle = screen("테마")
    private val finish = screen("어두운 테마 적용됨")
    private val repository = InMemorySkillRepository()
    private val runtime = SonjuAgentRuntime.createForTest(repository, object : ProcessLogRepository {
        override fun append(step: TraceStep) = Unit
        override fun exportRedacted(): List<String> = emptyList()
    })

    @Test fun learnedUnknownRequestReplaysEveryStepAndCompletesWithoutModel() {
        learn()
        val session = session()
        val first = requireNotNull(runtime.fastPathPlan(command, start, session))
        assertEquals(PlanSource.SKILL_FAST_PATH, first.source)
        assertFalse(first.goalCompleted)
        execute(session, first, start, middle)
        val second = requireNotNull(runtime.fastPathPlan(command, middle, session))
        execute(session, second, middle, finish)
        val done = requireNotNull(runtime.fastPathPlan(command, finish, session))
        assertTrue(done.goalCompleted)
        assertTrue(runtime.goalSatisfied(command, done, finish, session))
        assertNull(runtime.fastPathPlan("글꼴을 바꿔줘", start, session("글꼴을 바꿔줘")))
        assertNull(runtime.fastPathPlan(command, finish, AutonomySession(command, finish, 0)))
    }

    @Test fun settlingObservationsStayConnectedUntilTheNextAction() {
        val run = session()
        execute(run, plan("열기"), start, screen("로딩 중"))
        run.observe(middle)
        execute(run, plan("적용"), middle, finish)
        run.acceptPlan(completion(finish), finish)
        runtime.rememberSuccessfulSkill(command, run, finish)
        val replay = session()
        val first = requireNotNull(runtime.fastPathPlan(command, start, replay))
        assertEquals(middle.semanticTemplateFingerprint(), first.expectedScreenFingerprint)
    }

    @Test fun finalTransitionUsesVerifiedResultDespiteAnUnrelatedNewToolbar() {
        learn()
        val replay = session()
        execute(replay, requireNotNull(runtime.fastPathPlan(command, start, replay)), start, middle)
        val last = requireNotNull(runtime.fastPathPlan(command, middle, replay))
        assertNull(last.expectedScreenFingerprint)
        val changed = finish.copy(elements = finish.elements + finish.elements.single().copy(
            path = "0.8", viewId = "com.example:id/help", text = "도움말"))
        execute(replay, last, middle, changed)
        val done = requireNotNull(runtime.fastPathPlan(command, changed, replay))
        assertTrue(runtime.goalSatisfied(command, done, changed, replay))
        assertFalse(runtime.goalSatisfied(command, done, middle, replay))
    }

    @Test fun groundedDeepPathsAreStoredWithoutTreatingThemAsPhoneNumbers() {
        val path = "0.0.0.0.0.0.0.0.2.0.0.0.1.0.0.0.10"
        val action = AgentAction(ActionType.CLICK, "메뉴", path)
        val learned = requireNotNull(SkillLearner.learn(task(command), listOf(trace("a", "b", action)), "a", "b"))
        assertEquals(path, learned.steps.single().action.targetTemplate)
        assertNull(SkillLearner.learn(task(command),
            listOf(trace("a", "b", action.copy(target = "010-1234-5678"))), "a", "b"))
    }

    @Test fun clickedContainersPreferUniqueDescendantLabelsOverFragilePaths() {
        val node = start.elements.single().copy(path = "0.4", viewId = null, text = null)
        val labelled = start.copy(elements = listOf(node,
            node.copy(path = "0.4.0.0", text = "Galaxy AI", clickable = false)))
        val run = AutonomySession(command, labelled, 0)
        run.recordExecution(plan("0.4"), labelled, ExecutionResult(true, "ok", 1), "0.4")
        assertEquals("Galaxy AI", run.history.single().action.target)
    }

    @Test fun rejectedClickFocusDriftDoesNotDisconnectTheSameSemanticScreen() {
        val failed = trace("b", "b-focused").copy(succeeded = false,
            beforeTemplateFingerprint = "b", afterTemplateFingerprint = "b")
        val next = trace("b-focused", "c").copy(beforeTemplateFingerprint = "b")
        val learned = requireNotNull(SkillLearner.learn(task(command),
            listOf(trace("a", "b"), failed, next), "a", "c"))
        assertEquals(2, learned.steps.size)
        assertNull(SkillLearner.learn(task(command), listOf(trace("a", "b"),
            failed.copy(afterTemplateFingerprint = "different"), next), "a", "c"))
        val loaded = requireNotNull(SkillLearner.learn(task(command), listOf(trace("a", "b"),
            failed.copy(notDispatched = true, afterTemplateFingerprint = "loaded"),
            next.copy(beforeTemplateFingerprint = "loaded")), "a", "c"))
        assertEquals("loaded", loaded.steps.first().expectedAfterFingerprint)
    }

    @Test fun failedSkillRepairsSuffixUnderSameIdAndIncrementsVersionOnlyAfterSuccess() {
        learn()
        val replay = session()
        val first = requireNotNull(runtime.fastPathPlan(command, start, replay))
        execute(replay, first, start, middle)
        val broken = requireNotNull(runtime.fastPathPlan(command, middle, replay))
        val before = requireNotNull(repository.get(broken.skillId!!))
        runtime.recordPlanningFailure(broken, replay, "old selector missing")
        assertTrue(replay.aiRecoveryRequested)
        assertNull(runtime.fastPathPlan(command, middle, replay))
        assertEquals(SkillStatus.SUSPECT, repository.get(before.skillId)!!.status)
        assertEquals(before.version, repository.get(before.skillId)!!.version)
        val detour = screen("테마 선택")
        execute(replay, plan("새 테마 메뉴"), middle, detour)
        execute(replay, plan("어둡게"), detour, finish)
        replay.acceptPlan(completion(finish), finish)
        assertTrue(runtime.goalSatisfied(command, completion(finish), finish, replay))
        runtime.rememberSuccessfulSkill(command, replay, finish)
        val repaired = requireNotNull(repository.get(before.skillId))
        assertEquals(before.version + 1, repaired.version)
        assertEquals(before.steps.first(), repaired.steps.first())
        assertEquals(3, repaired.steps.size)
        assertEquals("새 테마 메뉴", repaired.steps[1].action.targetTemplate)
        assertEquals(SkillStatus.ACTIVE, repaired.status)
    }

    @Test fun failedExecutionRetainsRepairPointAndPostconditionDriftCanBeLearned() {
        learn()
        val replay = session()
        val first = requireNotNull(runtime.fastPathPlan(command, start, replay))
        execute(replay, first, start, middle)
        val broken = requireNotNull(runtime.fastPathPlan(command, middle, replay))
        replay.recordExecution(broken, middle, ExecutionResult(false, "layout changed", 0,
            failureReason = ExecutionFailureReason.POSTCONDITION_TIMEOUT, postconditionSatisfied = false))
        val newPage = screen("새 테마 페이지")
        replay.observe(newPage)
        assertEquals(1, replay.skillRepair!!.stepIndex)
        assertEquals(1, replay.skillRepair!!.traceIndex)
        execute(replay, plan("적용"), newPage, finish)
        replay.acceptPlan(completion(finish), finish)
        runtime.rememberSuccessfulSkill(command, replay, finish)
        val repaired = repository.get(broken.skillId!!)!!
        assertEquals(2, repaired.version)
        assertEquals(newPage.semanticTemplateFingerprint(), repaired.steps[1].expectedAfterFingerprint)
    }

    @Test fun onlyContinuousAccessibilityFailuresEnableTaskLocalVision() {
        val session = session()
        session.recordAccessibilityModelAttempt(start)
        session.requestAiRecovery("missing target", true, start)
        assertFalse(session.visualFallbackActive)
        execute(session, plan("테마"), start, middle)
        session.recordAccessibilityModelAttempt(middle)
        session.requestAiRecovery("another target missing", true, middle)
        assertFalse(session.visualFallbackActive)
        session.requestAiRecovery("still missing", true, middle)
        assertTrue(session.visualFallbackActive)
        assertFalse(session().visualFallbackActive)
        assertFalse(session().aiRecoveryRequested)
        val budget = session()
        assertFalse(budget.needsFreshObservation)
        repeat(24) { assertTrue(budget.reserveModelCall(1)) }
        assertTrue(budget.aiRecoveryRequested)
        assertTrue(budget.needsFreshObservation)
        assertFalse(budget.reserveModelCall(1))
    }

    @Test fun genericCompletionRequiresUniqueVisibleEvidenceAndKeepsHighRiskBoundary() {
        assertFalse(runtime.goalSatisfied(command, completion(finish).copy(goalChecks = emptyList()), finish))
        assertFalse(runtime.goalSatisfied(command, completion(start), finish))
        assertFalse(runtime.goalSatisfied(command, completion(finish), finish.copy(treeTruncated = true)))
        assertFalse(runtime.goalSatisfied(command, completion(finish), finish.copy(elements = finish.elements + finish.elements)))
        assertTrue(runtime.goalSatisfied(command, completion(finish), finish))
        assertTrue(GoalCheck("id=com.example:id/label", finish.elements.single().text).matches(finish))
        assertFalse(GoalCheck("id=0").matches(finish))
        assertFalse(runtime.goalSatisfied("계정을 삭제해", completion(finish), finish))
    }

    @Test fun modelOnlyInputIsStoredAsCurrentRequestSpanWithoutPersistingValue() {
        val request = "메모에 여행 준비라고 적어줘"
        val task = task(request)
        val trace = trace("A", "B", AgentAction(ActionType.SET_TEXT, "입력", "메모", "여행 준비"))
        val skill = requireNotNull(SkillLearner.learn(task, listOf(trace), "A", "B"))
        val template = skill.steps.single().action.valueTemplate!!
        assertFalse(template.contains("여행 준비"))
        assertEquals("여행 준비", ParameterFiller.fill(template, mapOf("request" to TaskParameter("request", request))))
        assertNull(ParameterFiller.fill(template, emptyMap()))
        assertNull(SkillLearner.learn(task, listOf(trace.copy(action = trace.action.copy(value = "모델이 지어낸 내용"))), "A", "B"))
    }

    @Test fun loopErasureKeepsConnectedRouteAndDoesNotReplaceShorterHealthySkill() {
        val task = task(command)
        val traces = listOf(trace("A", "B"), trace("B", "C"), trace("C", "B"), trace("B", "D"))
        val skill = requireNotNull(SkillLearner.learn(task, traces, "A", "D"))
        assertEquals(listOf("A", "B"), skill.steps.map { it.entryFingerprint })
        assertNull(SkillLearner.learn(task, listOf(trace("A", "B"), trace("X", "D")), "A", "D"))
        learn()
        val first = runtime.fastPathPlan(command, start, session())!!
        val old = repository.get(first.skillId!!)!!
        val longer = session()
        val detour = screen("추가 메뉴")
        execute(longer, plan("다른 메뉴"), start, detour)
        execute(longer, plan("테마"), detour, middle)
        execute(longer, plan("어둡게"), middle, finish)
        longer.acceptPlan(completion(finish), finish)
        runtime.rememberSuccessfulSkill(command, longer, finish)
        assertEquals(old, repository.get(old.skillId))
    }

    @Test fun visualSkillRequiresImageDigestAndUnverifiedCoordinatesStayUnlearnable() {
        val coordinate = AgentAction(ActionType.CLICK_COORDINATE, "테마", xRatio = .4, yRatio = .6)
        val raw = trace("A", "B", coordinate)
        assertNull(SkillLearner.learn(task(command), listOf(raw), "A", "B"))
        val learned = requireNotNull(SkillLearner.learn(task(command),
            listOf(raw.copy(visualFrameHash = visualFrameHash("pixels"))), "A", "B"))
        assertEquals(visualFrameHash("pixels"), learned.steps.single().action.visualFrameHash)
        val encoded = SkillCodec.encode(learned.copy(exitVisualFrameHash = visualFrameHash("after")))
        assertEquals(learned.copy(exitVisualFrameHash = visualFrameHash("after")), SkillCodec.decode(encoded))
        assertNull(SkillCodec.decode("broken storage"))
    }

    @Test fun failedAndIncompleteAttemptsNeverOverwriteSkillAndChangedEntryTriggersRepair() {
        learn()
        val session = session()
        val first = runtime.fastPathPlan(command, start, session)!!
        val old = repository.get(first.skillId!!)!!
        runtime.recordPlanningFailure(first, session, "target missing")
        runtime.rememberSuccessfulSkill(command, session, finish)
        assertEquals(old.steps, repository.get(old.skillId)!!.steps)
        assertEquals(old.version, repository.get(old.skillId)!!.version)
        // A different app layout after a verified prefix must not silently select a different skill.
        val independentRepository = InMemorySkillRepository().apply { save(old) }
        val otherRuntime = SonjuAgentRuntime.createForTest(independentRepository, object : ProcessLogRepository {
            override fun append(step: TraceStep) = Unit
            override fun exportRedacted(): List<String> = emptyList()
        })
        val next = session()
        val entry = otherRuntime.fastPathPlan(command, start, next)!!
        execute(next, entry, start, middle)
        assertNull(otherRuntime.fastPathPlan(command, screen("다른 메뉴"), next))
        assertTrue(next.aiRecoveryRequested)
        assertEquals(1, next.skillRepair!!.stepIndex)
    }

    @Test fun shortestObservedPathCanReuseAnEarlierEdgeErasedByChronologicalLoops() {
        val history = listOf(trace("A", "B"), trace("B", "C"), trace("C", "B"),
            trace("B", "D"), trace("D", "C"), trace("C", "E"))
        val skill = requireNotNull(SkillLearner.learn(task(command), history, "A", "E"))
        assertEquals(listOf("A", "B", "C"), skill.steps.map { it.entryFingerprint })
    }

    @Test fun cachedClickUsesCurrentLabelForCriticalActionPolicy() {
        val payment = screen("결제하기")
        val cached = plan("com.example:id/label").copy(source = PlanSource.SKILL_FAST_PATH,
            skillId = "cached", skillVersion = 1)
        assertTrue(runtime.verify(command, cached, payment) is com.hwanghj09.sonju.verifier.VerificationResult.Blocked)
    }

    @Test fun cachedCompletionRejectsWrongNumbersEvenWhenTemplateFingerprintMatches() {
        val result = screen("오전 7:00 알람 켜짐")
        val wrong = screen("오전 8:00 알람 켜짐")
        val learning = session()
        execute(learning, plan("알람"), start, result)
        learning.acceptPlan(completion(result), result)
        runtime.rememberSuccessfulSkill(command, learning, result)
        val replay = session()
        execute(replay, requireNotNull(runtime.fastPathPlan(command, start, replay)), start, wrong)
        assertEquals(result.semanticTemplateFingerprint(), wrong.semanticTemplateFingerprint())
        val done = requireNotNull(runtime.fastPathPlan(command, wrong, replay))
        assertFalse(runtime.goalSatisfied(command, done, wrong, replay))
        assertTrue(runtime.goalSatisfied(command, done, result, replay))
    }

    @Test fun informationSkillReturnsFreshVerifiedTextWithoutStoringReadout() {
        val request = "배터리 잔량 알려줘"
        val result = screen("배터리 82%")
        val learning = session(request)
        execute(learning, plan("배터리"), start, result)
        learning.acceptPlan(completion(result), result)
        runtime.rememberSuccessfulSkill(request, learning, result)
        val replay = session(request)
        val first = requireNotNull(runtime.fastPathPlan(request, start, replay))
        val stored = requireNotNull(repository.get(first.skillId!!))
        val encoded = SkillCodec.encode(stored)
        assertFalse(encoded.contains("배터리 82%"))
        assertEquals(stored, SkillCodec.decode(encoded))
        execute(replay, first, start, result)
        val done = requireNotNull(runtime.fastPathPlan(request, result, replay))
        assertTrue(runtime.goalSatisfied(request, done, result, replay))
        assertTrue(done.summary.contains("배터리 82%"))
        assertFalse(runtime.goalSatisfied(request, done, screen("배터리 12%"), replay))
    }

    @Test fun skillCanJoinAfterAppLaunchFromADifferentStartingApp() {
        val initial = start.copy(packageName = "com.launcher")
        val request = "글씨 크게 하는 메뉴를 열어줘"
        val learning = AutonomySession(request, initial, 0)
        execute(learning, plan("com.example.app").copy(actions = listOf(
            AgentAction(ActionType.OPEN_APP, "설정 열기", "com.example.app"))), initial, start)
        execute(learning, plan("테마"), start, middle)
        learning.acceptPlan(completion(middle), middle)
        runtime.rememberSuccessfulSkill(request, learning, middle)
        val replay = AutonomySession(request, initial.copy(packageName = "com.camera"), 0)
        val next = requireNotNull(runtime.fastPathPlan(request, start, replay))
        assertEquals(1, replay.nextSkillStep)
        assertEquals(ActionType.CLICK, next.actions.first().type)
        execute(replay, next, start, middle)
        assertTrue(runtime.goalSatisfied(request, requireNotNull(runtime.fastPathPlan(request, middle, replay)), middle, replay))
    }

    @Test fun replayNeverSkipsEarlierEffectsOnCoincidingIntermediateScreens() {
        learn()
        assertNull(runtime.fastPathPlan(command, middle, AutonomySession(command, middle, 0)))
    }

    @Test fun oldSkillCompletionCanBeRevalidatedWithoutInventingAnotherAction() {
        learn()
        val replay = session()
        val first = requireNotNull(runtime.fastPathPlan(command, start, replay))
        val stored = requireNotNull(repository.get(first.skillId!!))
        repository.save(stored.copy(goalChecks = emptyList()))
        execute(replay, first, start, middle)
        execute(replay, requireNotNull(runtime.fastPathPlan(command, middle, replay)), middle, finish)
        val oldDone = requireNotNull(runtime.fastPathPlan(command, finish, replay))
        assertFalse(runtime.goalSatisfied(command, oldDone, finish, replay))
        runtime.recordPlanningFailure(oldDone, replay, "fresh completion evidence required")
        replay.acceptPlan(completion(finish), finish)
        runtime.rememberSuccessfulSkill(command, replay, finish)
        val repaired = requireNotNull(repository.get(stored.skillId))
        assertEquals(stored.version + 1, repaired.version)
        assertEquals(stored.steps, repaired.steps)
        assertEquals(SkillStatus.ACTIVE, repaired.status)
        assertTrue(repaired.goalChecks.isNotEmpty())
    }

    @Test fun alreadyOpenGoalUsesConcreteEvidenceDespiteUnrelatedLayoutChanges() {
        val request = "설정에서 글자 크기 메뉴를 열어줘"
        val result = screen("글자 크기와 스타일")
        val learning = session(request)
        execute(learning, plan("글자 크기"), start, result)
        learning.acceptPlan(completion(result), result)
        runtime.rememberSuccessfulSkill(request, learning, result)
        val changed = result.copy(elements = result.elements + result.elements.single().copy(
            path = "0.8", viewId = "com.example:id/extra", text = "새 도움말"))
        assertNotEquals(result.semanticTemplateFingerprint(), changed.semanticTemplateFingerprint())
        val replay = AutonomySession(request, changed, 0)
        val done = requireNotNull(runtime.fastPathPlan(request, changed, replay))
        assertTrue(done.goalCompleted)
        assertTrue(runtime.goalSatisfied(request, done, changed, replay))
        assertEquals(0, replay.toolCallCount)
        val otherApp = changed.copy(packageName = "com.impostor")
        assertNull(runtime.fastPathPlan(request, otherApp, AutonomySession(request, otherApp, 0)))
    }

    @Test fun normalizedRequestSpacingKeepsSavedInputSpansCorrect() {
        val request = "메모에 여행 준비라고 적어줘"
        val result = screen("여행 준비")
        val learning = session(request)
        execute(learning, plan("메모").copy(actions = listOf(
            AgentAction(ActionType.SET_TEXT, "입력", "메모", "여행 준비"))), start, result)
        learning.acceptPlan(completion(result), result)
        runtime.rememberSuccessfulSkill(request, learning, result)
        val variation = "  메모에   여행 준비라고 적어줘  "
        val next = requireNotNull(runtime.fastPathPlan(variation, start, session(variation)))
        assertEquals("여행 준비", next.actions.first().value)
        val polite = "메모에 여행 준비라고 적어 주세요"
        assertEquals("여행 준비", requireNotNull(runtime.fastPathPlan(polite, start, session(polite))).actions.first().value)
        assertNull(runtime.fastPathPlan("메모에 병원 준비라고 적어줘", start, session()))
        assertNull(runtime.fastPathPlan("메모에 여행 준비라고 적지 말아 주세요", start, session()))
    }

    @Test fun spokenArithmeticLearnsTheDerivedExpressionWithoutPersistingOperands() {
        val request = "계산기에서 157 곱하기 24 계산해줘"
        val result = screen("3,768")
        val learning = session(request)
        execute(learning, plan("계산기").copy(actions = listOf(
            AgentAction(ActionType.SET_TEXT, "식 입력", "calculator:id/formula", "157×24"))), start, result)
        learning.acceptPlan(completion(result), result)
        runtime.rememberSuccessfulSkill(request, learning, result)
        val replay = session(request)
        val next = requireNotNull(runtime.fastPathPlan(request, start, replay))
        assertEquals("157×24", next.actions.first().value)
        val skill = requireNotNull(repository.get(next.skillId!!))
        assertEquals("${'$'}{expression}", skill.steps.single().action.valueTemplate)
        assertFalse(SkillCodec.encode(skill).contains("157×24"))
        assertNull(runtime.fastPathPlan("계산기에서 157 곱하기 25 계산해줘", start, session()))
    }

    private fun learn() {
        val learning = session()
        execute(learning, plan("테마"), start, middle)
        execute(learning, plan("어둡게"), middle, finish)
        learning.acceptPlan(completion(finish), finish)
        runtime.rememberSuccessfulSkill(command, learning, finish)
    }
    private fun session(goal: String = command) = AutonomySession(goal, start, 0)
    private fun execute(session: AutonomySession, plan: AgentPlan, before: UiSnapshot, after: UiSnapshot) {
        session.recordExecution(plan, before, ExecutionResult(true, "ok", 1, postconditionSatisfied = true))
        session.observe(after)
    }
    private fun plan(target: String) = AgentPlan(command, "next", RiskLevel.LOW, .95,
        listOf(AgentAction(ActionType.CLICK, "선택", target)), PlanSource.OPENAI_STRUCTURE, continueAfterAction = true)
    private fun completion(snapshot: UiSnapshot) = plan("unused").copy(actions = emptyList(), goalCompleted = true,
        goalChecks = listOf(GoalCheck("0", snapshot.elements.single().text)))
    private fun task(request: String) = CanonicalTask("com.example.app", "navigate", emptyMap(), emptyList(), TaskRisk.LOW,
        requestText = request)
    private fun trace(before: String, after: String, action: AgentAction = AgentAction(ActionType.CLICK, "next", "메뉴")) =
        AutonomySession.Trace(action, "com.example.app", before, true, "ok", "com.example.app", after, true, before, after)
    private fun screen(label: String) = UiSnapshot("com.example.app", "Example", epoch = 1,
        elements = listOf(UiElement("0", "com.example:id/label", "android.widget.TextView", label, null,
            ScreenBounds(0, 0, 300, 100), clickable = true, editable = false, scrollable = false,
            enabled = true, visible = true, sensitive = false)))
}
