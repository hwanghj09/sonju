package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.*
import com.hwanghj09.sonju.logging.ProcessLogRepository
import com.hwanghj09.sonju.logging.TraceStep
import com.hwanghj09.sonju.skill.InMemorySkillRepository
import com.hwanghj09.sonju.verifier.VerificationResult
import org.junit.Assert.*
import org.junit.Test

class UserInterventionTest {
    private val command = "내역 알려줘"
    private val app = "example.client"

    @Test fun allAuthenticationKindsUseOneCheckpointAndExcludeHumanTimeFromBudget() {
        val prompts = mapOf(
            "회원 로그인" to UserIntervention.Kind.LOGIN,
            "비밀번호" to UserIntervention.Kind.LOGIN,
            "지문 센서에 손가락을 올려 주세요" to UserIntervention.Kind.BIOMETRIC,
            "생체 정보로 인증해주세요" to UserIntervention.Kind.BIOMETRIC,
            "지문을 입력하세요." to UserIntervention.Kind.BIOMETRIC,
            "로봇이 아닙니다" to UserIntervention.Kind.CAPTCHA,
            "본인인증" to UserIntervention.Kind.IDENTITY,
            "인증번호를 입력해 주세요" to UserIntervention.Kind.TWO_FACTOR,
            "인증번호" to UserIntervention.Kind.TWO_FACTOR,
        )
        for ((prompt, kind) in prompts) {
            val challenge = page(prompt)
            val run = AutonomySession(command, challenge, 0)
            assertEquals(kind, UserIntervention.required(challenge, command))
            assertTrue(run.pauseForUser(requireNotNull(UserIntervention.plan(command, challenge)), challenge, 1_000))
            assertFalse(run.reserveModelCall(600_000))
            assertFalse(run.resumeAfterUser(challenge, 600_000))
            assertTrue(run.resumeAfterUser(complete(), 600_000))
            assertTrue(run.canContinue(601_000))
            assertFalse(run.canContinue(781_000))
            assertEquals(command, run.finalGoal)
            assertEquals(0, run.modelCallCount)
            assertEquals(ActionType.WAIT_FOR_USER, run.history.single().action.type)
            assertFalse(run.history.single().message.contains(prompt))
        }
    }

    @Test fun captchaCheckboxIsAHandoffButLoginAndBiometricSettingsButtonsAreNot() {
        val checkbox = page("로봇이 아닙니다").let { it.copy(elements = it.elements.map { n -> n.copy(clickable = true, checkable = true) }) }
        assertEquals(UserIntervention.Kind.CAPTCHA, UserIntervention.required(checkbox, command))
        for (label in listOf("로그인", "생체 인증", "본인인증")) {
            assertNull(UserIntervention.required(page(label).let { it.copy(elements = it.elements.map { n -> n.copy(clickable = true) }) }, command))
        }
        assertNull(UserIntervention.required(page("로그인 기록", "생체인식 설정", "고객센터"), command))
    }

    @Test fun sequentialChallengesResetTheAutomaticResumeStabilityWindow() {
        val run = paused(page("로그인"))
        assertFalse(run.resumeAfterUser(complete(), 2_000, userConfirmed = false))
        assertFalse(run.resumeAfterUser(page("보안문자 입력"), 4_000, userConfirmed = false))
        assertFalse(run.resumeAfterUser(page("2단계 인증"), 5_000, userConfirmed = false))
        assertFalse(run.resumeAfterUser(complete(), 6_000, userConfirmed = false))
        assertFalse(run.resumeAfterUser(complete(), 7_000, userConfirmed = false))
        assertTrue(run.resumeAfterUser(complete(), 7_200, userConfirmed = false))
        assertEquals(1, run.history.size)
        assertFalse(run.resumeAfterUser(complete(), 9_000, userConfirmed = false))
    }

    @Test fun crossAppBiometricsReturnOnlyToTheOriginalAppAndCancelDoesNotAutoResume() {
        val initial = complete()
        val run = AutonomySession(command, initial, 0)
        val plan = run.acceptPlan(AgentPlan(command, "내역 열기", RiskLevel.LOW, 1.0,
            listOf(AgentAction(ActionType.CLICK, "내역", "내역")), source = PlanSource.LOCAL_RULE, targetApp = app), initial)
        run.recordExecution(plan, initial, ExecutionResult(true, "ok", 1))
        val biometric = page("지문 센서에 손가락을 올려 주세요").copy(packageName = "example.system.auth")
        assertTrue(run.pauseForUser(requireNotNull(UserIntervention.plan(command, biometric)), biometric, 1_000))
        assertEquals(app, run.userHandoff?.resumePackage)
        assertFalse(run.resumeAfterUser(complete().copy(packageName = "example.other"), 4_000))
        assertFalse(run.resumeAfterUser(initial, 4_000, userConfirmed = false))
        assertFalse(run.resumeAfterUser(initial, 8_000, userConfirmed = false))
        assertFalse(run.resumeAfterUser(initial.copy(windowId = 10), 8_500, userConfirmed = false))
        val result = complete("조회 결과")
        assertFalse(run.resumeAfterUser(result, 9_000, userConfirmed = false))
        assertTrue(run.resumeAfterUser(result, 10_200, userConfirmed = false))
    }

    @Test fun openingAnAppBindsItsAuthenticationReturnToTheLaunchedPackage() {
        val initial = complete().copy(packageName = "com.hwanghj09.sonju")
        val run = AutonomySession(command, initial, 0)
        val open = run.acceptPlan(AgentPlan(command, "앱 열기", RiskLevel.LOW, 1.0,
            listOf(AgentAction(ActionType.OPEN_APP, "앱 열기", app)), source = PlanSource.LOCAL_RULE), initial)
        run.recordExecution(open, initial, ExecutionResult(true, "ok", 1))
        val biometric = page("생체 인증").copy(packageName = "example.system.auth")
        assertTrue(run.pauseForUser(requireNotNull(UserIntervention.plan(command, biometric)), biometric, 1_000))
        assertEquals(app, run.userHandoff?.resumePackage)
        assertFalse(run.resumeAfterUser(complete(), 3_000, userConfirmed = false))
        assertTrue(run.resumeAfterUser(complete(), 4_200, userConfirmed = false))
    }

    @Test fun aRequestStartedOnABiometricDialogUsesItsObservedUnderlyingApplication() {
        val biometric = page("생체 정보로 인증해주세요", "지문을 입력하세요.").copy(packageName = "example.system.auth")
        val run = AutonomySession(command, biometric, 0)
        assertTrue(run.pauseForUser(requireNotNull(UserIntervention.plan(command, biometric)), biometric, 1_000, app))
        assertEquals(app, run.userHandoff?.resumePackage)
        assertFalse(run.resumeAfterUser(complete(), 2_000, userConfirmed = false))
        assertTrue(run.resumeAfterUser(complete(), 3_200, userConfirmed = false))
    }

    @Test fun loadingMissingTruncatedSensitiveAndFailedScreensNeverResume() {
        val run = paused(page("로그인"))
        val badScreens = listOf(UiSnapshot.empty(), page("잠시만 기다려 주세요", "로딩 중"),
            complete().copy(treeTruncated = true), complete("인증 실패"),
            complete().copy(elements = complete().elements + node("", "4").copy(editable = true, sensitive = true)))
        for (snapshot in badScreens) assertFalse(run.resumeAfterUser(snapshot, 5_000))
        assertFalse(run.resumeAfterUser(complete(), 999))
    }

    @Test fun browserReturnRequiresTheSameVerifiedOriginIncludingAfterRedirects() {
        fun browser(url: String) = complete().copy(packageName = "com.android.chrome",
            elements = complete().elements + node(url, "9").copy(viewId = "com.android.chrome:id/url_bar"))
        val challenge = browser("https://example.test/login").copy(userIntervention = UserIntervention.Kind.LOGIN)
        val run = paused(challenge)
        assertFalse(run.resumeAfterUser(browser("https://evil.test"), 5_000))
        assertFalse(run.resumeAfterUser(browser("https://example.test@evil.test"), 5_000))
        assertFalse(run.resumeAfterUser(browser("http://example.test"), 5_000))
        assertFalse(run.resumeAfterUser(browser("https://example.test").let { it.copy(elements = it.elements.map { n -> n.copy(viewId = null) }) }, 5_000))
        assertTrue(run.resumeAfterUser(browser("https://example.test/records"), 5_000))
    }

    @Test fun restoredCheckpointsRequireExplicitContinueAndRecheckTheScreen() {
        val run = paused(page("본인인증"))
        val restored = AutonomySession(command, complete(), 100_000)
        restored.restoreUserHandoff(requireNotNull(run.userHandoff), 100_000)
        assertFalse(restored.resumeAfterUser(complete(), 101_000, userConfirmed = false))
        assertFalse(restored.resumeAfterUser(complete(), 110_000, userConfirmed = false))
        assertFalse(restored.resumeAfterUser(page("2단계 인증"), 110_000))
        assertTrue(restored.resumeAfterUser(complete(), 110_000))
        assertTrue(restored.canContinue(111_000))
    }

    @Test fun externalAuthenticationReturnsToTheAppOrOriginalSiteRatherThanTheIdentityProvider() {
        fun browser(url: String, challenge: Boolean = false) = complete().copy(packageName = "com.android.chrome",
            elements = complete().elements + node(url, "9").copy(viewId = "com.android.chrome:id/url_bar"),
            userIntervention = if (challenge) UserIntervention.Kind.LOGIN else null)
        val provider = browser("https://identity.test/login", challenge = true)
        for (initial in listOf(complete(), browser("https://example.test/records"))) {
            val run = AutonomySession(command, initial, 0)
            val click = run.acceptPlan(AgentPlan(command, "조회", RiskLevel.LOW, 1.0,
                listOf(AgentAction(ActionType.CLICK, "조회", "조회")), source = PlanSource.LOCAL_RULE), initial)
            run.recordExecution(click, initial, ExecutionResult(true, "ok", 1))
            assertTrue(run.pauseForUser(requireNotNull(UserIntervention.plan(command, provider)), provider, 1_000))
            assertFalse(run.resumeAfterUser(browser("https://identity.test/done"), 5_000))
            assertTrue(run.resumeAfterUser(initial, 5_000))
        }
    }

    @Test fun modelCannotInventAHandoffExecuteCaptchaOrDeclareGoalDuringAuthentication() {
        val runtime = SonjuAgentRuntime.createForTest(InMemorySkillRepository(), object : ProcessLogRepository {
            override fun append(step: TraceStep) = Unit
            override fun exportRedacted(): List<String> = emptyList()
        })
        val challenge = page("보안문자 확인")
        val run = AutonomySession(command, challenge, 0)
        val handoff = requireNotNull(UserIntervention.plan(command, challenge))
        assertFalse(run.pauseForUser(handoff.copy(actions = listOf(handoff.actions.single().copy(target = "other.app"))), challenge, 1_000))
        assertFalse(run.pauseForUser(handoff, complete(), 1_000))
        assertTrue(runtime.verify(command, handoff, challenge) is VerificationResult.Blocked)
        val click = handoff.copy(actions = listOf(AgentAction(ActionType.CLICK, "확인", "확인")))
        assertTrue(runtime.verify(command, click, challenge, userConfirmed = true) is VerificationResult.Blocked)
        assertFalse(runtime.goalSatisfied(command, handoff.copy(goalCompleted = true), challenge))
    }

    private fun paused(snapshot: UiSnapshot) = AutonomySession(command, snapshot, 0).also {
        assertTrue(it.pauseForUser(requireNotNull(UserIntervention.plan(command, snapshot)), snapshot, 1_000))
    }
    private fun page(vararg labels: String) = UiSnapshot(app, "앱", epoch = 1,
        elements = labels.mapIndexed { index, label -> node(label, index.toString()) })
    private fun complete(title: String = "내역") = page(title, "조회할 내역이 없습니다", "홈").let {
        it.copy(elements = it.elements.mapIndexed { index, n -> n.copy(clickable = index == 2) })
    }
    private fun node(label: String, path: String) = UiElement(path, null, "android.widget.TextView", label, null,
        ScreenBounds(0, 0, 100, 100), clickable = false, editable = false, scrollable = false,
        enabled = true, visible = true, sensitive = false)
}
