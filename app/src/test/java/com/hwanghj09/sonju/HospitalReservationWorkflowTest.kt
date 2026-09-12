package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.*
import com.hwanghj09.sonju.logging.ProcessLogRepository
import com.hwanghj09.sonju.logging.TraceStep
import com.hwanghj09.sonju.skill.InMemorySkillRepository
import com.hwanghj09.sonju.skill.SkillCodec
import com.hwanghj09.sonju.verifier.VerificationResult
import org.junit.Assert.*
import org.junit.Test

class HospitalReservationWorkflowTest {
    @Test fun hospitalEntityAndLookupIntentAreIndependentAndCannotCrossOrigins() {
        val asan = "아산병원 예약기록 알려줘"
        val variant = "서울 아산 병원 예약 내역 좀 보여줄래?"
        assertTrue(HospitalReservationWorkflow.matches(asan))
        assertEquals("asan", HospitalReservationWorkflow.siteFor(asan)?.id)
        assertEquals(HospitalReservationWorkflow.siteFor(asan), HospitalReservationWorkflow.siteFor(variant))
        assertTrue(HospitalReservationWorkflow.matches("강릉아산병원 예약기록 알려줘"))
        assertNull(HospitalReservationWorkflow.siteFor("강릉아산병원 예약기록 알려줘"))
        assertNull(HospitalReservationWorkflow.siteFor("강릉 아산병원 예약기록 알려줘"))
        val target = HospitalReservationWorkflow.entryPlan(asan).actions.single().target
        assertTrue(HospitalReservationWorkflow.isAllowedUrl(asan, target))
        assertFalse(HospitalReservationWorkflow.isAllowedUrl(command, target))
        assertNull(HospitalReservationWorkflow.resultText(records(), asan))
        assertFalse(HospitalReservationWorkflow.canResume(records(), asan))
    }

    private val command = "분당서울대병원 예약 기록 알려줘."
    private val workflow = HospitalReservationWorkflow
    private val repository = InMemorySkillRepository()
    private val runtime = SonjuAgentRuntime.createForTest(repository, object : ProcessLogRepository {
        override fun append(step: TraceStep) = Unit
        override fun exportRedacted(): List<String> = emptyList()
    })

    @Test fun asanLoginResumeCompletionAndSavedReplayStayBoundToAsan() {
        val request = "아산병원 예약기록 알려줘"
        val initial = start()
        val asanLogin = page("https://www.amc.seoul.kr/asan/member/preLogin.do", "로그인")
        val asanRecords = page(requireNotNull(workflow.siteFor(request)).reservationsUrl,
            "로그아웃", "진료예약내역", "진료일시", "진료과", "2030-12-24 10:00", "테스트진료과")
        val run = AutonomySession(request, initial, 0)
        val open = workflow.entryPlan(request)
        assertTrue(runtime.verify(request, open, initial) is VerificationResult.Allowed)
        assertTrue(runtime.verify(command, open, initial) is VerificationResult.Blocked)
        execute(run, open, initial, asanLogin)
        assertTrue(run.pauseForUser(workflow.loginPlan(request), asanLogin, 1_000))
        assertFalse(run.resumeAfterUser(login(), 600_000))
        assertFalse(run.resumeAfterUser(records(), 600_000))
        assertFalse(run.resumeAfterUser(asanLogin, 600_000))
        assertTrue(run.resumeAfterUser(asanRecords, 600_000))
        val done = requireNotNull(workflow.completionPlan(request, asanRecords))
        assertTrue(runtime.goalSatisfied(request, done, asanRecords))
        assertFalse(runtime.goalSatisfied(request, done, records()))
        run.acceptPlan(done, asanRecords)
        runtime.rememberSuccessfulSkill(request, run, asanRecords)
        assertNotNull(runtime.fastPathPlan(request, initial, AutonomySession(request, initial, 0)))
        assertNull(runtime.fastPathPlan(command, initial, AutonomySession(command, initial, 0)))
        assertNull(workflow.resultText(page(requireNotNull(workflow.siteFor(request)).reservationsUrl,
            "진료예약내역", "조회하기"), request))
    }

    @Test fun pastReservationsAreQueriesButNewReservationsAndMutationsAreNot() {
        for (request in listOf("아산병원 예약해 둔 내역 알려줘", "아산병원 예약해 놓은 날짜 알려줘",
            "어느 날 아산병원 진료 예약이 있는지 알려줘")) assertTrue(request, workflow.matches(request))
        for (request in listOf("아산병원 예약해 줘", "아산병원 예약 기록 확인하고 취소해 줘",
            "아산병원 예약 변경해 줘")) assertFalse(request, workflow.matches(request))
        assertNotNull(workflow.unsupportedReason("삼성서울병원 예약 내역 알려줘"))
        assertNotNull(workflow.unsupportedReason("병원 예약 내역 알려줘"))
    }

    @Test fun genericInformationLookupNeedsCurrentEvidenceEvenAtALearnedDestination() {
        val request = "내일 날씨 알려줘"
        val snapshot = page("https://example.test", "날씨", "내일 예보 보기")
        val fingerprint = com.hwanghj09.sonju.perception.AccessibilityScreenParser.parse(snapshot).fingerprint
        val arrival = AgentPlan(request, "예보 화면 도착", RiskLevel.LOW, 1.0,
            listOf(AgentAction(ActionType.FINISH, "완료")), source = PlanSource.SKILL_FAST_PATH,
            goalCompleted = true, expectedScreenFingerprint = fingerprint)
        assertFalse(runtime.goalSatisfied(request, arrival, snapshot))
    }

    @Test fun requestedRecordsStartAnActionButExplanationsAndCancellationDoNotUseTheReadOnlyRoute() {
        assertEquals(ScreenExplainer.RequestKind.COMMAND, ScreenExplainer.classifyRequest(command))
        assertEquals(ScreenExplainer.RequestKind.QUESTION, ScreenExplainer.classifyRequest("이 화면 내용 알려줘"))
        assertFalse(workflow.matches("분당서울대병원 예약 기록 확인하고 취소해 줘"))
        assertFalse(workflow.matches("분당서울대병원 위치 알려줘"))
        assertFalse(workflow.matches("분당서울대병원 예약 기록 조회 방법 알려줘"))
    }

    @Test fun onlyTheBrowserAddressControlEstablishesOfficialOrigin() {
        assertTrue(workflow.loginRequired(login()))
        assertFalse(workflow.loginRequired(login().copy(elements = login().elements.map {
            if (it.viewId?.endsWith("url_bar") == true) it.copy(viewId = "web:id/url_bar") else it
        })))
        for (url in listOf("https://www.snubh.org.evil.test/member/login.do", "https://www.snubh.org@evil.test/member/login.do",
            "http://www.snubh.org/member/login.do", "https://www.snubh.org:444/member/login.do")) {
            assertNull(workflow.location(page(url, "로그인")))
        }
    }

    @Test fun samsungCollapsedAddressWithDirectionMarkIsRecognizedWithoutRewritingHostContent() {
        val samsung = opaqueSamsung()
        assertEquals("snubh.org", workflow.location(samsung)?.host)
        assertTrue(workflow.needsLocalPageRead(samsung))
        val malicious = samsung.copy(elements = samsung.elements.map { it.copy(text = "snubh.\u200eorg") })
        assertNull(workflow.location(malicious))
        assertNull(workflow.location(samsung.copy(elements = samsung.elements.map { it.copy(text = "\u200esnubh.org.evil.test") })))
    }

    @Test fun opaqueWebContentUsesLocalReadOnlyEvidenceAndNeverCreatesActionTargets() {
        val opaque = opaqueSamsung()
        assertFalse(workflow.loginRequired(opaque))
        val read = opaque.copy(localReadOnlyText = listOf("로그인", "아이디를 입력해 주세요.", "비밀번호를 입력해 주세요."))
        assertTrue(workflow.loginRequired(read))
        assertFalse(workflow.canResume(read))
        assertEquals(opaque.elements, read.elements)
        assertEquals(opaque.compactText(), read.compactText())
        val result = opaque.copy(localReadOnlyText = listOf("로그아웃", "예약현황조회", "예약 내역이 없습니다."))
        assertNotNull(workflow.resultText(result))
        assertTrue(workflow.canResume(result))
    }

    @Test fun observedSamsungEmptyTreatmentResultCompletesOnlyOnVerifiedResultsPage() {
        val labels = listOf("로그아웃", "예약조회 및 취소", "예약현황조회", "진료예약현황", "검사예약현황",
            "예약취소현황", "진료예약현황", "추가진료예약", "진료비결제", "진료예약현황이 존재하지 않습니다.")
        val snapshot = opaqueSamsung().copy(localReadOnlyText = labels)
        assertTrue(workflow.resultText(snapshot).orEmpty().contains("진료예약현황이 존재하지 않습니다."))
        assertTrue(runtime.goalSatisfied(command, requireNotNull(workflow.completionPlan(command, snapshot)), snapshot))
        assertNull(workflow.resultText(snapshot.copy(localReadOnlyText = labels.filterNot { it == "로그아웃" })))
        assertNull(workflow.resultText(snapshot.copy(localReadOnlyText = listOf(labels.last()))))
        assertNull(workflow.resultText(snapshot.copy(localReadOnlyText = labels.dropLast(1) + "진료예약현황을 불러오지 못했습니다.")))
        assertNull(workflow.resultText(snapshot.copy(localReadOnlyText = labels.dropLast(1) + "진료예약현황을 조회할 수 없습니다.")))
        assertNull(workflow.resultText(snapshot.copy(localReadOnlyText = labels.dropLast(1) + "검색결과가 존재하지 않습니다.")))
    }

    @Test fun failedUrlLaunchIsNotRepeatedAndLoopTerminationIsNotReportedAsThreeMinutes() {
        val initial = start()
        val run = AutonomySession(command, initial, 0)
        assertTrue(workflow.shouldOpenPage(run, initial))
        val plan = run.acceptPlan(workflow.entryPlan(command), initial)
        repeat(3) {
            run.recordExecution(plan, initial, ExecutionResult(false, "timeout", 0,
                failureReason = com.hwanghj09.sonju.execution.ExecutionFailureReason.POSTCONDITION_TIMEOUT))
            run.observe(initial)
        }
        assertFalse(workflow.shouldOpenPage(run, initial))
        assertFalse(run.canContinue(15_000))
        assertTrue(run.stopReason(15_000).orEmpty().contains("반복"))
        assertFalse(run.stopReason(15_000).orEmpty().contains("3분"))
        assertTrue(AutonomySession(command, initial, 0).stopReason(181_000).orEmpty().contains("3분"))
    }

    @Test fun loginWaitKeepsGoalAndBudgetAndCannotResumeFromLoginOrAnotherSite() {
        val run = AutonomySession(command, start(), 0)
        assertTrue(run.pauseForUser(workflow.loginPlan(command), login(), 1_000))
        assertFalse(run.canContinue(1_001))
        assertFalse(run.reserveModelCall(1_001))
        assertFalse(run.resumeAfterUser(login(), 900_000))
        assertFalse(run.resumeAfterUser(page("https://evil.test", "로그아웃"), 900_000))
        assertEquals(command, run.finalGoal)
        assertTrue(run.resumeAfterUser(records(), 900_000))
        assertTrue(run.canContinue(901_000))
        assertFalse(run.canContinue(1_081_000))
        assertEquals(ActionType.WAIT_FOR_USER, run.history.single().action.type)
        assertEquals(0, run.modelCallCount)
    }

    @Test fun aTitleLoginPageOrEmptyLoadingStateNeverCompletesTheTask() {
        val done = AgentPlan(command, "완료", RiskLevel.LOW, 1.0, listOf(AgentAction(ActionType.FINISH, "끝")),
            source = PlanSource.OPENAI_STRUCTURE, goalCompleted = true)
        for (snapshot in listOf(login(), page(workflow.RESERVATIONS_URL, "예약현황조회"),
            page(workflow.RESERVATIONS_URL, "예약현황조회", "로딩 중"),
            records().copy(treeTruncated = true))) {
            assertFalse(runtime.goalSatisfied(command, done, snapshot))
        }
        assertTrue(runtime.goalSatisfied(command, done, records()))
        assertTrue(workflow.resultText(page(workflow.RESERVATIONS_URL, "예약현황조회", "예약 내역이 없습니다."))
            .orEmpty().contains("없습니다"))
    }

    @Test fun humanCheckpointAndPublicUrlAreSavedAndReplayedWithoutCredentialsOrRecords() {
        val initial = start()
        val run = AutonomySession(command, initial, 0)
        execute(run, workflow.entryPlan(command), initial, login())
        assertTrue(run.pauseForUser(workflow.loginPlan(command), login(), 1_000))
        assertNull(runtime.fastPathPlan(command, initial, AutonomySession(command, initial, 0)))
        assertTrue(run.resumeAfterUser(records(), 600_000))
        run.acceptPlan(requireNotNull(workflow.completionPlan(command, records())), records())
        runtime.rememberSuccessfulSkill(command, run, records())
        val replay = AutonomySession(command, initial, 0)
        val open = requireNotNull(runtime.fastPathPlan(command, initial, replay))
        assertEquals(PlanSource.SKILL_FAST_PATH, open.source)
        execute(replay, open, initial, login())
        val handoff = requireNotNull(runtime.fastPathPlan(command, login(), replay))
        assertEquals(ActionType.WAIT_FOR_USER, handoff.actions.first().type)
        assertTrue(replay.pauseForUser(handoff.copy(actions = handoff.actions.filterNot { it.type == ActionType.FINISH }), login(), 1_000))
        assertTrue(replay.resumeAfterUser(records(), 600_000))
        val done = requireNotNull(runtime.fastPathPlan(command, records(), replay))
        assertTrue(done.goalCompleted)
        assertTrue(runtime.goalSatisfied(command, done, records(), replay))
        assertEquals(0, replay.modelCallCount)
        val stored = requireNotNull(repository.get(requireNotNull(open.skillId)))
        assertEquals(listOf(ActionType.OPEN_URL, ActionType.WAIT_FOR_USER), stored.steps.map { it.action.type })
        val json = SkillCodec.encode(stored)
        assertFalse(json.contains("private-password"))
        assertFalse(json.contains("테스트진료과"))
        assertFalse(json.contains("2030-12-24"))
        assertEquals(stored, SkillCodec.decode(json))
    }

    @Test fun verifierAllowsOnlyReviewedReadOnlyEntryAndNeverExecutesTheHumanCheckpoint() {
        val open = workflow.entryPlan(command)
        assertTrue(runtime.verify(command, open, start()) is VerificationResult.Allowed)
        assertTrue(runtime.verify(command, open.copy(actions = listOf(open.actions.single().copy(
            target = "https://www.snubh.org/reserve/cancel.do"))), start()) is VerificationResult.Blocked)
        assertTrue(runtime.verify("다른 요청", open, start()) is VerificationResult.Blocked)
        assertTrue(runtime.verify(command, workflow.loginPlan(command), login()) is VerificationResult.Blocked)
        val cancel = open.copy(actions = listOf(AgentAction(ActionType.CLICK, "예약 취소", "예약 취소")))
        assertTrue(runtime.verify(command, cancel, records()) is VerificationResult.Blocked)
    }

    private fun execute(run: AutonomySession, plan: AgentPlan, before: UiSnapshot, after: UiSnapshot) {
        val accepted = run.acceptPlan(plan, before)
        run.recordExecution(accepted, before, ExecutionResult(true, "ok", 1, postconditionSatisfied = true))
        run.observe(after)
    }
    private fun start() = page("https://www.snubh.org/index.do", "분당서울대학교병원")
    private fun opaqueSamsung() = UiSnapshot("com.sec.android.app.sbrowser", "Samsung Internet", epoch = 1,
        elements = listOf(node("\u200esnubh.org", "0.1").copy(
            viewId = "com.sec.android.app.sbrowser:id/location_bar_edit_text", editable = true)))
    private fun login() = page("https://www.snubh.org/member/login.do?prevURI=/personal/resvrStatusList.do", "회원 로그인")
        .let { it.copy(elements = it.elements + node("private-password", "0.8").copy(editable = true, sensitive = true)) }
    private fun records() = page(workflow.RESERVATIONS_URL, "로그아웃", "예약현황조회", "진료일시", "진료과",
        "2030-12-24 10:00", "테스트진료과")
    private fun page(url: String, vararg labels: String) = UiSnapshot(
        packageName = "com.android.chrome", windowTitle = "Chrome", epoch = 1,
        elements = listOf(node(url, "0.0").copy(viewId = "com.android.chrome:id/url_bar")) +
            labels.mapIndexed { index, label -> node(label, "0.${index + 1}") },
    )
    private fun node(label: String, path: String) = UiElement(path, null, "android.widget.TextView", label, null,
        ScreenBounds(0, 0, 100, 100), clickable = false, editable = false, scrollable = false,
        enabled = true, visible = true, sensitive = false)
}
