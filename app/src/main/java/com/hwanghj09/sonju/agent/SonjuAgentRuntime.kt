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
import com.hwanghj09.sonju.skill.StoredGoalCheck
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
    private val installedAppLabels: () -> Collection<String>? = { null },
) {
    private val taskCache = TaskCanonicalizationCache()
    private val skillRetriever = SkillRetriever(skillRepository)
    private val fastPathPlanner = FastPathPlanner()
    private val goalEvaluator = DeterministicGoalEvaluator()
    private val visualFallbackPolicy = VisualFallbackPolicy()
    private val sessionIds = linkedMapOf<String, String>()

    fun fastPathPlan(command: String, snapshot: UiSnapshot, session: AutonomySession? = null): AgentPlan? {
        if (session?.aiRecoveryRequested == true) return null
        val screen = AccessibilityScreenParser.parse(snapshot)
        val task = canonicalTask(command, session?.initialSnapshot?.let(AccessibilityScreenParser::parse) ?: screen)
        val skills = skillRetriever.retrieve(task, screen)
        val canCompleteFromObservation = task.taskType in OBSERVATIONAL_TASKS ||
            com.hwanghj09.sonju.task.RequestInterpreter.isReadOnlyLookup(command)
        fun goalMatches(skill: com.hwanghj09.sonju.skill.AppSkill): Boolean =
            !snapshot.treeTruncated && skill.exitPackage == snapshot.packageName &&
                skill.goalChecks.isNotEmpty() && skill.goalChecks.all { it.resolve(snapshot) != null }
        fun entryIndex(skill: com.hwanghj09.sonju.skill.AppSkill): Int? {
            if (canCompleteFromObservation && goalMatches(skill)) return skill.steps.size
            if (skill.entryFingerprint == screen.fingerprint) return 0
            // Launching an app may already have been handled locally, or the user may be in it.
            // Never skip input, clicks, or other effects just because two screens look alike.
            return skill.steps.withIndex().filter { (index, step) ->
                step.entryFingerprint == screen.fingerprint &&
                    skill.steps.take(index).all { it.action.type in APP_ENTRY_ACTIONS }
            }.singleOrNull()?.index
        }
        val skill = session?.activeSkillId?.let(skillRepository::get) ?: skills.firstOrNull {
            (it.status == SkillStatus.ACTIVE && entryIndex(it) != null) ||
                (it.status != SkillStatus.DISABLED && canCompleteFromObservation && goalMatches(it))
        } ?: return null
        val index = if (canCompleteFromObservation && goalMatches(skill)) skill.steps.size
            else if (session?.activeSkillId == skill.skillId) session.nextSkillStep
            else entryIndex(skill) ?: return null
        if (session != null && (session.activeSkillId == null || index == skill.steps.size)) {
            session.selectSkill(skill.skillId, index)
        }
        val plan = fastPathPlanner.planStep(task, screen, skill, index, goalMatches(skill))
        if (plan == null) {
            session?.let {
                it.beginSkillRepair(skill.skillId, skill.version, index)
                it.requestAiRecovery("저장된 skill의 다음 화면 또는 입력 조건이 달라졌습니다.")
                skillRepository.markFailure(skill.skillId)
            }
            return null
        }
        val storedStep = skill.steps.getOrNull(index)
        val checks = if (plan.complete) skill.goalChecks.mapNotNull { it.resolve(snapshot) } else emptyList()
        return AgentPlan(
            goal = command,
            summary = if (plan.complete) {
                checks.mapNotNull { it.text }.distinct().joinToString(". ")
                    .ifBlank { "현재 화면에서 요청한 결과를 확인했습니다." }
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
            // The final transition is followed by concrete goal checks. Focus/toolbar changes
            // must not reject a correct result before those checks can inspect it.
            expectedScreenFingerprint = if (storedStep != null && index == skill.steps.lastIndex &&
                skill.goalChecks.isNotEmpty()) null else storedStep?.expectedAfterFingerprint ?: skill.exitFingerprint,
            goalChecks = checks,
            visualFrameHash = if (plan.complete) skill.exitVisualFrameHash else storedStep?.action?.visualFrameHash,
            visualFallback = storedStep?.action?.let { it.type == ActionType.CLICK_COORDINATE || it.visualFrameHash != null } == true,
        )
    }

    fun verify(
        command: String,
        plan: AgentPlan,
        snapshot: UiSnapshot,
        userConfirmed: Boolean = false,
    ): VerificationResult {
        val screen = AccessibilityScreenParser.parse(snapshot)
        val intent = DeterministicTaskParser.parse(command, installedAppLabels())
        val task = canonicalTask(command, screen)
        return verifier.verify(intent, task, plan, snapshot, screen, userConfirmed)
    }

    fun goalSatisfied(
        command: String,
        plan: AgentPlan,
        snapshot: UiSnapshot,
        session: AutonomySession? = null,
    ): Boolean {
        if (session?.userHandoff != null || UserIntervention.required(snapshot, command) != null) return false
        if (HospitalReservationWorkflow.matches(command)) {
            return plan.goalCompleted && HospitalReservationWorkflow.resultText(snapshot, command) != null
        }
        val screen = AccessibilityScreenParser.parse(snapshot)
        val task = canonicalTask(command, screen)
        if (task.taskType != "open_app" && ScreenContextHandoff.hasUnobservedRenderedContent(snapshot) &&
            !plan.visualFrameVerified) return false
        if (plan.source == PlanSource.SKILL_FAST_PATH) {
            val skill = plan.skillId?.let(skillRepository::get) ?: return false
            val replayComplete = plan.goalCompleted && session?.activeSkillId == skill.skillId &&
                session.nextSkillStep == skill.steps.size && plan.skillVersion == skill.version &&
                skill.requestKey == task.requestKey && !snapshot.treeTruncated &&
                (skill.exitPackage?.let { it == snapshot.packageName } ?: (screen.fingerprint == skill.exitFingerprint))
            val checksMatch = skill.goalChecks.isNotEmpty() && skill.goalChecks.all { it.resolve(snapshot) != null }
            val imageMatch = skill.exitVisualFrameHash != null && plan.visualFrameVerified &&
                plan.visualFrameHash == skill.exitVisualFrameHash
            return replayComplete && (checksMatch || imageMatch)
        }
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
        val needsInformationEvidence = com.hwanghj09.sonju.task.RequestInterpreter.understand(command).purpose ==
            com.hwanghj09.sonju.task.RequestPurpose.INFORMATION_LOOKUP && task.taskType != "directions"
        if (!needsInformationEvidence && goalEvaluator.evaluate(task, screen, extracted).satisfied &&
            (plan.source !in setOf(PlanSource.OPENAI_STRUCTURE, PlanSource.OPENAI_SEMANTIC_MAP) ||
                task.taskType == "order_food")) return true
        // The model must point to concrete, unique, non-sensitive evidence on this observation.
        // Known high-risk completion contracts remain owned by the deterministic evaluator.
        val visualEvidence = plan.visualFrameHash != null && plan.visualFrameVerified &&
            session?.hasVisualAttemptFor(snapshot, plan.visualFrameHash) == true && plan.successCriteria.isNotEmpty() &&
            session.history.any { it.succeeded && it.screenChanged == true }
        val readOnlyLookup = com.hwanghj09.sonju.task.RequestInterpreter.isReadOnlyLookup(command)
        return (task.risk < TaskRisk.HIGH || readOnlyLookup) && plan.goalCompleted && plan.confidence >= .85 &&
            plan.source in setOf(PlanSource.OPENAI_STRUCTURE, PlanSource.OPENAI_SEMANTIC_MAP) &&
            !snapshot.treeTruncated && screen.packageName !in setOf("unknown", "com.hwanghj09.sonju") &&
            (plan.goalChecks.isNotEmpty() && plan.goalChecks.all { it.matches(snapshot) } || visualEvidence)
    }

    fun rememberSuccessfulSkill(
        command: String,
        session: AutonomySession,
        finalSnapshot: UiSnapshot,
    ) {
        val completion = session.latestPlan?.takeIf { it.goalCompleted } ?: return
        if (!goalSatisfied(command, completion, finalSnapshot, session)) return
        val initialScreen = AccessibilityScreenParser.parse(session.initialSnapshot)
        val finalScreen = AccessibilityScreenParser.parse(finalSnapshot)
        val task = canonicalTask(command, initialScreen)
        val checks = completion.goalChecks.mapNotNull { StoredGoalCheck.capture(it, finalSnapshot) }
        if (checks.size != completion.goalChecks.size) return
        if (completion.source == PlanSource.SKILL_FAST_PATH) {
            completion.skillId?.let(skillRepository::markSuccess)
            return
        }
        val repair = session.skillRepair
        if (repair != null) {
            val existing = skillRepository.get(repair.skillId) ?: return
            if (existing.version != repair.version) return
            if (repair.stepIndex == existing.steps.size && session.history.drop(repair.traceIndex).isEmpty()) {
                // Old caches or changed readouts need fresh goal evidence, not a fabricated action.
                skillRepository.save(existing.copy(goalChecks = checks,
                    exitFingerprint = finalScreen.fingerprint, exitPackage = finalSnapshot.packageName,
                    exitVisualFrameHash = completion.visualFrameHash,
                    version = existing.version + 1, status = SkillStatus.ACTIVE,
                    confidence = maxOf(.6, existing.confidence), successCount = existing.successCount + 1,
                    lastValidatedAt = System.currentTimeMillis()))
                return
            }
            val suffix = SkillLearner.learn(task, session.history.drop(repair.traceIndex),
                initialScreen.fingerprint, finalScreen.fingerprint)?.copy(
                    exitVisualFrameHash = completion.visualFrameHash, goalChecks = checks,
                    exitPackage = finalSnapshot.packageName) ?: return
            SkillLearner.repair(existing, repair.stepIndex, suffix)?.let(skillRepository::save)
            return
        }
        val skill = SkillLearner.learn(
            task = task,
            history = session.history,
            fallbackEntryFingerprint = initialScreen.fingerprint,
            fallbackExitFingerprint = finalScreen.fingerprint,
        ) ?: return
        val existing = skillRepository.get(skill.skillId)
        if (existing != null && existing.status == SkillStatus.ACTIVE &&
            existing.steps.size <= skill.steps.size) return
        skillRepository.save(skill.copy(version = (existing?.version ?: 0) + 1,
            exitVisualFrameHash = completion.visualFrameHash, goalChecks = checks,
            exitPackage = finalSnapshot.packageName))
    }

    fun recordPlanningFailure(plan: AgentPlan, session: AutonomySession, reason: String,
                              snapshot: UiSnapshot? = null, accessibilityFailure: Boolean = false) {
        plan.skillId?.let {
            session.beginSkillRepair(it, plan.skillVersion ?: 1, session.nextSkillStep)
            skillRepository.markFailure(it)
        }
        if (snapshot != null) session.recordPlanningRejection(plan, snapshot, reason, accessibilityFailure)
        else session.requestAiRecovery(reason, accessibilityFailure)
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
        if (!result.success || result.postconditionSatisfied == false) plan.skillId?.let(skillRepository::markFailure)
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

    private fun canonicalTask(command: String, screen: ScreenState): CanonicalTask {
        val request = java.text.Normalizer.normalize(command, java.text.Normalizer.Form.NFKC)
            .replace(REQUEST_WHITESPACE, " ").trim()
            .replace(POLITE_REQUEST_ENDING, "줘").replace(SPACED_REQUEST_ENDING, "줘")
        return taskCache.getOrPut(request) {
            DeterministicTaskCanonicalizer.canonicalize(
                DeterministicTaskParser.parse(request, installedAppLabels()),
                screen.copy(packageName = "unknown"),
            ).let { it.copy(appId = it.appId ?: "any",
                requestKey = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(request.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) },
                requestText = request) }
        }
    }

    @Synchronized
    private fun sessionId(command: String): String = sessionIds.getOrPut(
        RedactionPolicy.requestHash(command),
    ) { UUID.randomUUID().toString() }

    private fun PlannedAction.toLegacyAction(): AgentAction? = when (this) {
        is PlannedAction.SystemAction -> AgentAction(type, "저장된 시스템 동작을 실행합니다.")
        is PlannedAction.SubmitText -> AgentAction(ActionType.SUBMIT_TEXT, "현재 요청의 검색어를 제출합니다.",
            target = target.legacySelector(), value = value)
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
        is PlannedAction.OpenUrl -> AgentAction(ActionType.OPEN_URL, "저장된 공식 웹페이지를 엽니다.", url)
        is PlannedAction.UserCheckpoint -> AgentAction(ActionType.WAIT_FOR_USER, "사용자의 직접 확인을 기다립니다.", origin)
        PlannedAction.Back -> AgentAction(ActionType.BACK, "이전 화면으로 이동합니다.")
        PlannedAction.Home -> AgentAction(ActionType.HOME, "홈 화면으로 이동합니다.")
        is PlannedAction.WaitFor -> AgentAction(
            ActionType.WAIT,
            "조건을 기다립니다.",
            waitMillis = timeoutMs.coerceIn(100, 2_000),
        )
        is PlannedAction.VisualClick -> AgentAction(ActionType.CLICK_COORDINATE, description,
            xRatio = xRatio, yRatio = yRatio)
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
        private val REQUEST_WHITESPACE = Regex("\\s+")
        private val POLITE_REQUEST_ENDING = Regex("\\s*주(?:세요|실래요|시겠어요)[.!?]*$")
        private val SPACED_REQUEST_ENDING = Regex("\\s+줘[.!?]*$")
        private val OBSERVATIONAL_TASKS = setOf("open_app", "search", "calculate", "directions")
        private val APP_ENTRY_ACTIONS = setOf(ActionType.OPEN_APP, ActionType.OPEN_WIFI_SETTINGS,
            ActionType.OPEN_SOUND_SETTINGS, ActionType.OPEN_ACCESSIBILITY_SETTINGS,
            ActionType.OPEN_DISPLAY_SETTINGS, ActionType.OPEN_DATE_SETTINGS,
            ActionType.OPEN_CAMERA, ActionType.OPEN_DIALER, ActionType.OPEN_MESSAGES)
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
                installedAppLabels = { InstalledApps.query(context.applicationContext.packageManager).map { it.label } },
            ).also { instance = it }
        }

        internal fun createForTest(
            repository: SkillRepository,
            processLog: ProcessLogRepository,
        ): SonjuAgentRuntime = SonjuAgentRuntime(repository, processLog)
    }
}
