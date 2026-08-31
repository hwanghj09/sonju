package com.hwanghj09.sonju.agent

import android.content.Context
import com.hwanghj09.sonju.execution.ExecutionFailureReason
import com.hwanghj09.sonju.logging.ExecutionStatus
import com.hwanghj09.sonju.logging.FallbackType
import com.hwanghj09.sonju.logging.LocalProcessLogRepository
import com.hwanghj09.sonju.logging.ProcessLogRepository
import com.hwanghj09.sonju.logging.RedactionPolicy
import com.hwanghj09.sonju.logging.TraceStep
import com.hwanghj09.sonju.perception.AccessibilityScreenParser
import com.hwanghj09.sonju.perception.ScreenState
import com.hwanghj09.sonju.planner.PlannedAction
import com.hwanghj09.sonju.planner.ScrollDirection
import com.hwanghj09.sonju.skill.FastPathPlanner
import com.hwanghj09.sonju.skill.LocalSkillRepository
import com.hwanghj09.sonju.skill.SkillLearner
import com.hwanghj09.sonju.skill.SkillRepository
import com.hwanghj09.sonju.skill.SkillRetriever
import com.hwanghj09.sonju.skill.SkillStatus
import com.hwanghj09.sonju.task.CanonicalTask
import com.hwanghj09.sonju.task.DeterministicTaskCanonicalizer
import com.hwanghj09.sonju.task.DeterministicTaskParser
import com.hwanghj09.sonju.task.TaskCanonicalizationCache
import com.hwanghj09.sonju.task.TaskRisk
import com.hwanghj09.sonju.verifier.ActionVerifier
import com.hwanghj09.sonju.verifier.DeterministicActionVerifier
import com.hwanghj09.sonju.verifier.DeterministicGoalEvaluator
import com.hwanghj09.sonju.verifier.StateValue
import com.hwanghj09.sonju.verifier.VerificationResult
import com.hwanghj09.sonju.verifier.VerifiedPlan
import com.hwanghj09.sonju.vision.VisualFallbackPolicy
import java.util.UUID

/**
 * Thin composition root for the deterministic architecture.
 *
 * Android UI/model callbacks remain outside; every executable plan enters through [verify].
 */
class SonjuAgentRuntime private constructor(
    private val skillRepository: SkillRepository,
    private val processLog: ProcessLogRepository,
    private val verifier: ActionVerifier = DeterministicActionVerifier(),
) {
    private val taskCache = TaskCanonicalizationCache()
    private val skillRetriever = SkillRetriever(skillRepository)
    private val fastPathPlanner = FastPathPlanner()
    private val goalEvaluator = DeterministicGoalEvaluator()
    private val visualFallbackPolicy = VisualFallbackPolicy()
    private val sessionIds = linkedMapOf<String, String>()

    fun fastPathPlan(command: String, snapshot: UiSnapshot): AgentPlan? {
        val screen = AccessibilityScreenParser.parse(snapshot)
        val task = canonicalTask(command, screen)
        val skills = skillRetriever.retrieve(task, screen)
        val skill = skills.firstOrNull { it.status == SkillStatus.ACTIVE } ?: return null
        val plan = fastPathPlanner.plan(task, screen, listOf(skill)) ?: return null
        val storedStep = skill.steps.firstOrNull { it.entryFingerprint == screen.fingerprint }
        return AgentPlan(
            goal = command,
            summary = if (plan.complete) {
                "저장된 skill의 최종 화면을 확인했습니다."
            } else {
                "검증된 로컬 skill을 모델 호출 없이 재사용합니다."
            },
            modelRisk = task.risk.toLegacyRisk(),
            confidence = skill.confidence,
            actions = if (plan.complete) {
                listOf(AgentAction(ActionType.FINISH, "저장된 최종 상태를 확인했습니다."))
            } else {
                val action = plan.steps.single().action.toLegacyAction() ?: return null
                listOf(action, AgentAction(ActionType.FINISH, "화면 상태를 다시 확인합니다."))
            },
            source = PlanSource.SKILL_FAST_PATH,
            continueAfterAction = !plan.complete,
            goalCompleted = plan.complete,
            targetApp = skill.appId,
            targetSurface = plan.steps.firstOrNull()?.stepId ?: "learned goal state",
            requiredTools = plan.steps.firstOrNull()?.action?.toLegacyAction()?.let { setOf(it.type) }
                ?: emptySet(),
            strategy = skill.steps.map { it.stepId },
            successCriteria = listOf("semantic fingerprint=${skill.exitFingerprint}"),
            revisionReason = "known skill exact task/screen match",
            skillId = skill.skillId,
            skillVersion = skill.version,
            expectedScreenFingerprint = storedStep?.expectedAfterFingerprint ?: skill.exitFingerprint,
        )
    }

    fun verify(
        command: String,
        plan: AgentPlan,
        snapshot: UiSnapshot,
        userConfirmed: Boolean = false,
    ): VerificationResult {
        val screen = AccessibilityScreenParser.parse(snapshot)
        val intent = DeterministicTaskParser.parse(command)
        val task = canonicalTask(command, screen)
        return verifier.verify(intent, task, plan, snapshot, screen, userConfirmed)
    }

    fun goalSatisfied(
        command: String,
        plan: AgentPlan,
        snapshot: UiSnapshot,
        session: AutonomySession? = null,
    ): Boolean {
        val screen = AccessibilityScreenParser.parse(snapshot)
        val task = canonicalTask(command, screen)
        val extracted = buildMap<String, StateValue> {
            plan.expectedScreenFingerprint?.let {
                put("expected_fingerprint", StateValue.Text(it))
            }
            if (task.taskType == "order_food" && session?.history?.any { trace ->
                    trace.succeeded && trace.screenChanged == true && isOrderProgress(trace.action)
                } == true
            ) {
                put("order_transition_verified", StateValue.Flag(true))
            }
            val requestedMenu = task.parameters["menu_query"]?.value
            if (task.taskType == "order_food" && session?.history?.any { trace ->
                    trace.succeeded && trace.screenChanged == true &&
                        isRequestedMenuProgress(trace.action, requestedMenu)
                } == true
            ) {
                put("requested_menu_selected", StateValue.Flag(true))
            }
        }
        return goalEvaluator.evaluate(task, screen, extracted).satisfied
    }

    fun rememberSuccessfulSkill(
        command: String,
        session: AutonomySession,
        finalSnapshot: UiSnapshot,
    ) {
        val initialScreen = AccessibilityScreenParser.parse(session.initialSnapshot)
        val finalScreen = AccessibilityScreenParser.parse(finalSnapshot)
        val task = canonicalTask(command, initialScreen)
        val skill = SkillLearner.learn(
            task = task,
            history = session.history,
            fallbackEntryFingerprint = initialScreen.fingerprint,
            fallbackExitFingerprint = finalScreen.fingerprint,
        ) ?: return
        skillRepository.save(skill)
    }

    fun recordExecution(
        command: String,
        verifiedPlan: VerifiedPlan,
        result: ExecutionResult,
    ) {
        val action = verifiedPlan.actions.values.singleOrNull() ?: return
        val plan = verifiedPlan.plan
        val status = when {
            result.success -> ExecutionStatus.SUCCEEDED
            result.failureReason == ExecutionFailureReason.USER_CANCELLED -> ExecutionStatus.CANCELLED
            else -> ExecutionStatus.FAILED
        }
        plan.skillId?.let { skillId ->
            if (result.success && result.postconditionSatisfied != false) {
                skillRepository.markSuccess(skillId)
            } else {
                skillRepository.markFailure(skillId)
            }
        }
        processLog.append(
            TraceStep(
                sessionId = sessionId(command),
                requestHash = RedactionPolicy.requestHash(command),
                canonicalTaskKey = canonicalTask(
                    command,
                    AccessibilityScreenParser.parse(
                        UiSnapshot.empty(verifiedPlan.sourceEpoch).copy(
                            packageName = plan.targetApp.ifBlank { "unknown" },
                        ),
                    ),
                ).key,
                index = action.actionIndex,
                screenFingerprintBefore = verifiedPlan.semanticFingerprint,
                screenTypeBefore = null,
                plannerSource = plan.source,
                skillId = plan.skillId,
                skillVersion = plan.skillVersion,
                actionType = action.action.type,
                semanticSelectorRedacted = action.action.target,
                groundingConfidence = action.groundingConfidence,
                verificationEvidence = action.evidence,
                executionMethod = result.method,
                failureReason = result.failureReason,
                screenFingerprintAfter = result.afterFingerprint,
                postconditionSatisfied = result.postconditionSatisfied,
                fallbackUsed = if (action.visualFallback) FallbackType.VISUAL else FallbackType.NONE,
                latencyMs = 0,
                status = status,
            ),
        )
    }

    fun shouldUseVisualFallback(snapshot: UiSnapshot, groundingFailed: Boolean): Boolean {
        val screen = AccessibilityScreenParser.parse(snapshot)
        return snapshot.packageName != "unknown" && visualFallbackPolicy.shouldUse(
            screen = screen,
            groundingFailed = groundingFailed,
            containsSensitiveNode = snapshot.elements.any { it.visible && it.sensitive },
        )
    }

    private fun canonicalTask(command: String, screen: ScreenState): CanonicalTask =
        taskCache.getOrPut("${screen.packageName}\n$command") {
            DeterministicTaskCanonicalizer.canonicalize(
                DeterministicTaskParser.parse(command),
                screen,
            )
        }

    @Synchronized
    private fun sessionId(command: String): String = sessionIds.getOrPut(
        RedactionPolicy.requestHash(command),
    ) { UUID.randomUUID().toString() }

    private fun PlannedAction.toLegacyAction(): AgentAction? = when (this) {
        is PlannedAction.Click -> AgentAction(
            ActionType.CLICK,
            "저장된 semantic target을 누릅니다.",
            target = target.legacySelector(),
        )
        is PlannedAction.SetText -> AgentAction(
            ActionType.SET_TEXT,
            "현재 요청의 parameter를 입력합니다.",
            target = target.legacySelector(),
            value = value,
        )
        is PlannedAction.Scroll -> AgentAction(
            type = when (direction) {
                ScrollDirection.UP -> ActionType.SCROLL_UP
                ScrollDirection.DOWN -> ActionType.SCROLL_DOWN
                ScrollDirection.LEFT -> ActionType.SCROLL_LEFT
                ScrollDirection.RIGHT -> ActionType.SCROLL_RIGHT
            },
            description = "저장된 방향으로 화면을 이동합니다.",
        )
        is PlannedAction.OpenApp -> AgentAction(ActionType.OPEN_APP, "앱을 엽니다.", packageOrLabel)
        PlannedAction.Back -> AgentAction(ActionType.BACK, "이전 화면으로 이동합니다.")
        PlannedAction.Home -> AgentAction(ActionType.HOME, "홈 화면으로 이동합니다.")
        is PlannedAction.WaitFor -> AgentAction(
            ActionType.WAIT,
            "조건을 기다립니다.",
            waitMillis = timeoutMs.coerceIn(100, 2_000),
        )
        is PlannedAction.VisualClick -> null
    }

    private fun com.hwanghj09.sonju.grounding.GroundingQuery.legacySelector(): String? =
        selector ?: nodeIdHint ?: resourceIdHint ?: textEquals ?: textContains ?: contentDescriptionHint

    private fun TaskRisk.toLegacyRisk(): RiskLevel = when (this) {
        TaskRisk.LOW -> RiskLevel.LOW
        TaskRisk.MEDIUM -> RiskLevel.MEDIUM
        TaskRisk.HIGH, TaskRisk.CRITICAL -> RiskLevel.HIGH
    }

    private fun isOrderProgress(action: AgentAction): Boolean {
        if (action.type !in setOf(ActionType.CLICK, ActionType.CLICK_COORDINATE)) return false
        val compact = listOf(action.description, action.target)
            .joinToString(" ")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        return ORDER_PROGRESS_TERMS.any(compact::contains)
    }

    private fun isRequestedMenuProgress(action: AgentAction, requestedMenu: String?): Boolean {
        if (requestedMenu.isNullOrBlank() || action.type !in setOf(
                ActionType.CLICK,
                ActionType.CLICK_COORDINATE,
            )
        ) return false
        val compact = listOf(action.description, action.target)
            .joinToString(" ")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        val compactMenu = requestedMenu.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        return compact.contains(compactMenu) && REQUESTED_MENU_PROGRESS_TERMS.any(compact::contains)
    }

    companion object {
        private val ORDER_PROGRESS_TERMS = setOf(
            "인기순", "인기메뉴", "장바구니담기", "장바구니보기", "주문서화면으로이동",
            "주문하기", "placeorder", "revieworder",
        )
        private val REQUESTED_MENU_PROGRESS_TERMS = setOf(
            "요청한인기메뉴", "requestedpopularmenu", "장바구니에담", "addtocart",
        )
        @Volatile
        private var instance: SonjuAgentRuntime? = null

        fun get(context: Context): SonjuAgentRuntime = instance ?: synchronized(this) {
            instance ?: SonjuAgentRuntime(
                skillRepository = LocalSkillRepository(context.applicationContext),
                processLog = LocalProcessLogRepository(context.applicationContext),
            ).also { instance = it }
        }

        internal fun createForTest(
            repository: SkillRepository,
            processLog: ProcessLogRepository,
        ): SonjuAgentRuntime = SonjuAgentRuntime(repository, processLog)
    }
}
