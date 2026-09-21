package com.hwanghj09.sonju.ai

import com.hwanghj09.sonju.agent.*
import com.hwanghj09.sonju.logging.ProcessLogRepository
import com.hwanghj09.sonju.logging.TraceStep
import com.hwanghj09.sonju.skill.InMemorySkillRepository
import com.hwanghj09.sonju.verifier.VerificationResult
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in real planner checks on synthetic public native/web lists; no order is placed. */
class WorkflowContinuationLiveTest {
    @Test fun pizzaOrderContinuesFromMultipleObservedRestaurants() = checkNextStep(
        "피자 주문해 줘", "example.delivery", "배달 앱", "피자 검색 결과", "피자",
        listOf("민들레피자 · 평점 4.8 · 최소주문 15,000원", "정원피자 · 평점 4.7 · 최소주문 16,000원"))

    @Test fun webShoppingContinuesFromMultipleProductsAfterAPrematureFinish() = checkNextStep(
        "운동화 장바구니에 담아줘", "com.android.chrome", "Chrome", "운동화 검색 결과", "운동화",
        listOf("운동화 A · 59,000원 · 상세 보기", "운동화 B · 69,000원 · 상세 보기"), rejectedCompletion = true)

    @Test fun anUnavailableRestaurantLeadsToAnObservedAvailableAlternative() = checkNextStep(
        "피자 주문해 줘", "example.delivery", "배달 앱", "현재 가게는 영업 종료 · 오픈알림. 지금 배달 가능한 다른 가게", "피자",
        listOf("민들레피자 · 현재 주문 가능 · 최소주문 15,000원", "정원피자 · 현재 주문 가능 · 최소주문 16,000원"))

    @Test fun pizzaOrderSubmitsTheOriginalQueryInsteadOfAnAutocompleteFranchise() {
        assumeTrue(System.getenv("SONJU_LIVE_MODEL") == "1")
        val field = UiElement("0.search", null, "android.widget.EditText", "피자", null,
            ScreenBounds(100, 100, 950, 250), true, true, false, true, true, false,
            hintText = "검색", focused = true, availableActions = setOf(UiNodeAction.SET_TEXT, UiNodeAction.IME_ENTER), imeAction = 3)
        val snapshot = UiSnapshot("example.delivery", "배달 앱", epoch = 2, elements = listOf(field,
            field.copy(path = "0.suggestion", className = "android.view.View", text = "피자헛", editable = false,
                focused = false, hintText = null, imeAction = null, availableActions = setOf(UiNodeAction.CLICK))))
        val session = AutonomySession("피자 주문해 줘", snapshot, 0)
        session.recordExecution(AgentPlan(session.finalGoal, "검색어 입력", RiskLevel.LOW, .99,
            listOf(AgentAction(ActionType.SET_TEXT, "입력", field.path, "피자")), PlanSource.OPENAI_STRUCTURE),
            snapshot, ExecutionResult(true, "입력됨, 아직 검색 전", 1, postconditionSatisfied = true), field.path)
        OpenAiPlanner(installedApps = { listOf(InstalledApp("배달 앱", snapshot.packageName)) }).use { planner ->
            val done = CountDownLatch(1); var response: Result<AgentPlan>? = null
            planner.planAsync(session.finalGoal, snapshot, null, autonomyContext = session.plannerContext(snapshot, null),
                callback = { response = it; done.countDown() })
            assertTrue(done.await(45, TimeUnit.SECONDS))
            val action = requireNotNull(response).getOrThrow().actions.first()
            assertEquals(ActionType.SUBMIT_TEXT, action.type)
            assertEquals("피자", action.value)
            assertEquals(field.path, action.target)
        }
    }

    private fun checkNextStep(command: String, app: String, appLabel: String, title: String, query: String,
        labels: List<String>, rejectedCompletion: Boolean = false) {
        assumeTrue(System.getenv("SONJU_LIVE_MODEL") == "1")
        fun node(path: String, label: String?, clickable: Boolean, top: Int) = UiElement(path, null,
            "android.view.View", label, null, ScreenBounds(20, top, 1000, top + 180), clickable,
            false, false, true, true, false)
        val cards = labels.mapIndexed { index, label ->
            listOf(node("0.card$index", null, true, 400 + index * 200),
                node("0.card$index.0", label, false, 400 + index * 200))
        }.flatten()
        val input = node("0.search", query, true, 100).copy(className = "android.widget.EditText",
            editable = true, hintText = "검색", availableActions = setOf(UiNodeAction.SET_TEXT, UiNodeAction.IME_ENTER))
        val snapshot = UiSnapshot(app, appLabel, epoch = 2, windowId = 1,
            elements = listOf(node("0.heading", title, false, 280), input) + cards,
            windowBounds = ScreenBounds(0, 0, 1080, 2000))
        val runtime = SonjuAgentRuntime.createForTest(InMemorySkillRepository(), object : ProcessLogRepository {
            override fun append(step: TraceStep) = Unit
            override fun exportRedacted(): List<String> = emptyList()
        })
        val session = AutonomySession(command, snapshot, 0)
        val submission = AgentPlan(command, "검색 제출", RiskLevel.LOW, .99,
            listOf(AgentAction(ActionType.SUBMIT_TEXT, "검색", input.path, query)), PlanSource.OPENAI_STRUCTURE)
        session.recordExecution(submission, snapshot.copy(epoch = 1),
            ExecutionResult(true, "search results observed", 1, postconditionSatisfied = true), input.path)
        session.observe(snapshot)
        if (rejectedCompletion) {
            val finish = submission.copy(actions = listOf(AgentAction(ActionType.FINISH, "후보에서 종료")),
                goalCompleted = true, goalChecks = listOf(GoalCheck(cards[1].path, labels[0])))
            runtime.recordPlanningFailure(finish, session, runtime.completionRejectionReason(command, finish, snapshot), snapshot)
        }
        OpenAiPlanner(installedApps = { listOf(InstalledApp(appLabel, app)) }).use { planner ->
            val latch = CountDownLatch(1)
            var response: Result<AgentPlan>? = null
            planner.planAsync(command, snapshot, null, autonomyContext = session.plannerContext(snapshot, null),
                callback = { response = it; latch.countDown() })
            assertTrue("Planner timeout", latch.await(45, TimeUnit.SECONDS))
            val plan = session.acceptPlan(requireNotNull(response).getOrThrow(), snapshot)
            assertFalse("A candidate list is not the final workflow goal: ${plan.summary}", plan.goalCompleted)
            assertEquals(ActionType.CLICK, plan.actions.first().type)
            val verified = runtime.verify(command, plan, snapshot)
            assertTrue("Must pass the real verifier: $verified", verified is VerificationResult.Allowed)
            val action = (verified as VerificationResult.Allowed).verifiedPlan.actions.values.single()
            assertTrue("Must inspect one real candidate, not reopen search", action.resolvedNodeId in setOf("0.card0", "0.card1"))
            assertNull(session.repeatedActionFailure(action.action, snapshot, action.resolvedNodeId))
        }
    }
}
