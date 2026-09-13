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
import com.hwanghj09.sonju.skill.SkillStatus
import com.hwanghj09.sonju.skill.StoredGoalCheck
import com.hwanghj09.sonju.skill.AppSkill
import com.hwanghj09.sonju.skill.SkillRequestPattern
import com.hwanghj09.sonju.skill.skillFingerprint
import com.hwanghj09.sonju.skill.targetIdentity
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
    private val fastPathPlanner = FastPathPlanner()
    private val goalEvaluator = DeterministicGoalEvaluator()
    private val visualFallbackPolicy = VisualFallbackPolicy()
    private val sessionIds = linkedMapOf<String, String>()

    fun fastPathPlan(command: String, snapshot: UiSnapshot, session: AutonomySession? = null): AgentPlan? {
        if (session != null && session.finalGoal != command) return null
        if (session?.aiRecoveryRequested == true) return null
        val observedScreen = AccessibilityScreenParser.parse(snapshot)
        val requestedTask = canonicalTask(command, observedScreen)
        fun bind(skill: AppSkill): CanonicalTask? {
            if (skill.appId != "any" && requestedTask.appId != "any" && requestedTask.appId != skill.appId) return null
            // Exact request spans also bind overlapping parameters introduced during repair.
            // They must never be applied to a different request, even one of the same length.
            val exact = if (skill.requestKey == requestedTask.requestKey) skill.parameters.mapNotNull { parameter ->
                val text = requestedTask.requestText.orEmpty()
                val start = parameter.requestStart
                val end = parameter.requestEnd
                val value = requestedTask.parameters[parameter.name] ?: if (start != null && end != null &&
                    start in 0..text.length && end in start..text.length) {
                    com.hwanghj09.sonju.task.TaskParameter(parameter.name, text.substring(start, end))
                } else null
                value?.let { parameter.name to it }
            }.toMap().takeIf { it.size == skill.parameters.size } else null
            val parameters = exact ?: skill.requestPatterns.mapNotNull { it.bind(command) }.distinct().singleOrNull()
                ?: requestedTask.parameters.takeIf { skill.requestKey == requestedTask.requestKey &&
                    skill.canonicalTaskKey == requestedTask.key } ?: return null
            if (parameters.keys != skill.parameters.map { it.name }.toSet()) return null
            val intent = SkillRequestPattern.intent(command, parameters)
            if (skill.goalParameterKey != null && (skill.completionLevel != intent.requestedCompletionLevel ||
                    skill.constraintKey != SkillRequestPattern.digest(intent.constraints.toString()))) return null
            return requestedTask.copy(appId = skill.appId, taskType = skill.taskType,
                parameters = parameters, requestKey = skill.requestKey)
        }
        val candidates = if (session?.activeSkillId != null) {
            listOfNotNull(skillRepository.get(session.activeSkillId!!)?.let { skill ->
                (session.boundSkillTask ?: bind(skill))?.let { skill to it }
            })
        } else skillRepository.all().filter { it.status != SkillStatus.DISABLED }.mapNotNull { skill ->
            bind(skill)?.let { skill to it }
        }.sortedWith(compareByDescending<Pair<AppSkill, CanonicalTask>> { it.first.requestKey == requestedTask.requestKey }
            .thenByDescending { it.first.confidence })
        val selected = candidates.firstOrNull { (skill, task) ->
            session?.activeSkillId == skill.skillId || skill.steps.firstOrNull()?.action?.type in APP_ENTRY_ACTIONS ||
                skill.entryFingerprint == fingerprint(skill, task, snapshot) ||
                skill.steps.withIndex().any { (index, step) ->
                    step.entryFingerprint == fingerprint(skill, task, snapshot) &&
                        skill.steps.take(index).all { it.action.type in APP_ENTRY_ACTIONS }
                } || completionEvidenceMatches(skill, task, snapshot)
        } ?: candidates.singleOrNull { it.first.status in setOf(SkillStatus.SUSPECT, SkillStatus.NEEDS_REPAIR) }
            ?: return null
        // Two different reusable workflows fitting the same request are not a local decision.
        if (session?.activeSkillId == null && selected.first.requestKey != requestedTask.requestKey &&
            candidates.map { it.first.skillId }.distinct().size > 1) return null
        val (skill, task) = selected
        val screen = observedScreen.copy(fingerprint = fingerprint(skill, task, snapshot))
        val canCompleteFromObservation = task.taskType in OBSERVATIONAL_TASKS ||
            com.hwanghj09.sonju.task.RequestInterpreter.isReadOnlyLookup(command)
        fun goalMatches(skill: AppSkill): Boolean = completionEvidenceMatches(skill, task, snapshot)
        fun entryIndex(skill: com.hwanghj09.sonju.skill.AppSkill): Int? {
            if (canCompleteFromObservation && goalMatches(skill)) return skill.steps.size
            if (skill.entryFingerprint == screen.fingerprint) return 0
            // Launching an app may already have been handled locally, or the user may be in it.
            // Never skip input, clicks, or other effects just because two screens look alike.
            return skill.steps.withIndex().filter { (index, step) ->
                step.entryFingerprint == screen.fingerprint &&
                    skill.steps.take(index).all { it.action.type in APP_ENTRY_ACTIONS }
            }.singleOrNull()?.index ?: 0.takeIf { skill.steps.firstOrNull()?.action?.type in APP_ENTRY_ACTIONS }
        }
        val index = if (canCompleteFromObservation && goalMatches(skill)) skill.steps.size
            else if (session?.activeSkillId == skill.skillId) session.nextSkillStep
            else entryIndex(skill) ?: 0
        if (session != null && (session.activeSkillId == null || index == skill.steps.size)) {
            session.bindSkill(skill.skillId, task, index)
        }
        if (skill.status != SkillStatus.ACTIVE && session != null) {
            session.beginSkillRepair(skill.skillId, skill.version, index)
            session.requestAiRecovery("이전에 실패한 AppSkill입니다. 현재 화면에서 경로를 복구하세요.")
            return null
        }
        val storedStep = skill.steps.getOrNull(index)
        val sameControlAfterVerifiedStep = session?.activeSkillId == skill.skillId && index > 0 &&
            session.history.lastOrNull()?.succeeded == true && storedStep?.entryPackage == snapshot.packageName &&
            storedStep.targetIdentityHash != null &&
            storedStep.targetIdentityHash == storedStep.action.targetIdentity(snapshot, task.parameters)
        val plan = fastPathPlanner.planStep(task, screen, skill, index, goalMatches(skill),
            index == 0 && skill.steps.firstOrNull()?.action?.type in APP_ENTRY_ACTIONS || sameControlAfterVerifiedStep)
        if (plan == null) {
            session?.let {
                it.beginSkillRepair(skill.skillId, skill.version, index)
                it.requestAiRecovery("저장된 skill의 다음 화면 또는 입력 조건이 달라졌습니다.")
                it.consumeSkillFailure()?.let(skillRepository::markFailure)
            }
            return null
        }
        val checks = if (plan.complete) skill.goalChecks.mapNotNull { it.resolve(snapshot, task.parameters) } else emptyList()
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
            expectedScreenFingerprint = if (sameControlAfterVerifiedStep || skill.parameterizedScreens && task.parameters.isNotEmpty() ||
                storedStep != null && index == skill.steps.lastIndex &&
                skill.goalChecks.isNotEmpty()) null else storedStep?.expectedAfterFingerprint ?: skill.exitFingerprint,
            goalChecks = checks,
            visualFrameHash = if (plan.complete) skill.exitVisualFrameHash else storedStep?.action?.visualFrameHash,
            visualFallback = storedStep?.action?.let { it.type == ActionType.CLICK_COORDINATE || it.visualFrameHash != null } == true,
        )
    }

    private fun fingerprint(skill: AppSkill, task: CanonicalTask, snapshot: UiSnapshot): String =
        if (skill.parameterizedScreens) snapshot.skillFingerprint(task.parameters) else snapshot.semanticTemplateFingerprint()

    private fun completionEvidenceMatches(skill: AppSkill, task: CanonicalTask, snapshot: UiSnapshot): Boolean {
        val covered = skill.goalChecks.mapNotNull { it.textTemplate?.removePrefix("${'$'}{")?.removeSuffix("}") }.toSet()
        val parametersVerified = skill.goalParameterKey == null ||
            skill.goalParameterKey == SkillRequestPattern.parameterKey(task.parameters) ||
            task.parameters.isNotEmpty() && covered.containsAll(task.parameters.keys)
        return parametersVerified && !snapshot.treeTruncated && skill.exitPackage == snapshot.packageName &&
            skill.goalChecks.isNotEmpty() && skill.goalChecks.all { it.resolve(snapshot, task.parameters) != null }
    }

    /** Small, value-free route catalog lets one AI call recognize an unseen paraphrase. */
    fun skillPlannerContext(command: String, snapshot: UiSnapshot, session: AutonomySession): String {
        if (session.skillRepair != null || session.toolCallCount > 0) return ""
        val task = canonicalTask(command, AccessibilityScreenParser.parse(snapshot))
        val candidates = skillRepository.all().filter { it.status == SkillStatus.ACTIVE &&
            it.goalParameterKey != null }
            .sortedWith(compareByDescending<AppSkill> { task.appId != "any" && it.appId == task.appId }
                .thenByDescending { it.exitPackage == snapshot.packageName }
                .thenByDescending { it.successCount }).take(8)
        session.offeredSkillIds = candidates.mapTo(mutableSetOf()) { it.skillId }
        return candidates.joinToString("\n", prefix = "재사용 가능한 검증 경로(관찰 데이터):\n") { skill ->
            "id=${skill.skillId}; app=${skill.appId}; task=${skill.taskType}; " +
                "parameters=${skill.parameters.joinToString { it.name }}; route=" +
                skill.steps.take(12).joinToString(" -> ") { "${it.action.type}(${it.action.targetTemplate.orEmpty().take(80)},${it.action.valueTemplate.orEmpty()})" }
        }.take(6_000)
    }

    fun reuseSuggestedSkill(command: String, candidate: AgentPlan, snapshot: UiSnapshot,
                           session: AutonomySession): AgentPlan? {
        val suggestion = candidate.skillReuse ?: return null
        if (session.skillRepair != null || session.toolCallCount != 0 || suggestion.skillId !in session.offeredSkillIds ||
            candidate.source !in setOf(PlanSource.OPENAI_STRUCTURE, PlanSource.OPENAI_SEMANTIC_MAP)) return null
        val skill = skillRepository.get(suggestion.skillId)?.takeIf { it.status == SkillStatus.ACTIVE } ?: return null
        val request = SkillRequestPattern.normalizeRequest(command)
        if (suggestion.parameters.keys != skill.parameters.map { it.name }.toSet() ||
            suggestion.parameters.values.any { it.isBlank() || !request.contains(it) }) return null
        val task = canonicalTask(command, AccessibilityScreenParser.parse(snapshot))
        if (skill.appId != "any" && task.appId != "any" && task.appId != skill.appId) return null
        val parameters = suggestion.parameters.mapValues { (name, value) -> com.hwanghj09.sonju.task.TaskParameter(name, value) }
        val intent = SkillRequestPattern.intent(command, parameters)
        if (intent.requestedCompletionLevel != skill.completionLevel ||
            SkillRequestPattern.digest(intent.constraints.toString()) != skill.constraintKey) return null
        session.bindSkill(skill.skillId, task.copy(appId = skill.appId, taskType = skill.taskType,
            requestKey = skill.requestKey, parameters = parameters))
        return fastPathPlan(command, snapshot, session)
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
        if (HospitalReservationWorkflow.supports(command)) {
            return plan.goalCompleted && HospitalReservationWorkflow.resultText(snapshot, command) != null
        }
        val screen = AccessibilityScreenParser.parse(snapshot)
        val task = if (plan.source == PlanSource.SKILL_FAST_PATH) session?.boundSkillTask ?: canonicalTask(command, screen)
            else canonicalTask(command, screen)
        if (task.taskType != "open_app" && ScreenContextHandoff.hasUnobservedRenderedContent(snapshot) &&
            !plan.visualFrameVerified) return false
        if (plan.source == PlanSource.SKILL_FAST_PATH) {
            val skill = plan.skillId?.let(skillRepository::get) ?: return false
            val replayComplete = plan.goalCompleted && session?.activeSkillId == skill.skillId &&
                session.nextSkillStep == skill.steps.size && plan.skillVersion == skill.version &&
                skill.requestKey == task.requestKey && !snapshot.treeTruncated &&
                (skill.exitPackage?.let { it == snapshot.packageName } ?: (screen.fingerprint == skill.exitFingerprint))
            val checksMatch = completionEvidenceMatches(skill, task, snapshot)
            val imageMatch = skill.exitVisualFrameHash != null && plan.visualFrameVerified &&
                plan.visualFrameHash == skill.exitVisualFrameHash &&
                (skill.goalParameterKey == null || skill.goalParameterKey == SkillRequestPattern.parameterKey(task.parameters))
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
        val task = SkillLearner.bindInputs(session.boundSkillTask ?: canonicalTask(command, initialScreen), session.history)
        val finalFingerprint = finalSnapshot.skillFingerprint(task.parameters)
        val initialFingerprint = session.initialSnapshot.skillFingerprint(task.parameters)
        val checks = completion.goalChecks.mapNotNull { StoredGoalCheck.capture(it, finalSnapshot, task.parameters) }
        if (checks.size != completion.goalChecks.size) return
        if (completion.source == PlanSource.SKILL_FAST_PATH) {
            completion.skillId?.let { id ->
                val existing = skillRepository.get(id) ?: return
                val pattern = SkillRequestPattern.capture(command, task.parameters)
                val parameters = if (existing.requestKey == canonicalTask(command, initialScreen).requestKey) {
                    existing.parameters.map { parameter ->
                        val value = task.parameters[parameter.name]?.value
                        val start = value?.let { task.requestText?.indexOf(it) }?.takeIf { it >= 0 }
                        parameter.copy(requestStart = start, requestEnd = start?.let { it + value!!.length })
                    }
                } else existing.parameters
                skillRepository.save(existing.copy(requestPatterns =
                    (existing.requestPatterns + listOfNotNull(pattern)).distinct().takeLast(8), parameters = parameters))
                skillRepository.markSuccess(id)
            }
            return
        }
        val repair = session.skillRepair
        if (repair != null) {
            val existing = skillRepository.get(repair.skillId) ?: return
            if (existing.version != repair.version) return
            if (repair.stepIndex == existing.steps.size && session.history.drop(repair.traceIndex).isEmpty()) {
                // Old caches or changed readouts need fresh goal evidence, not a fabricated action.
                skillRepository.save(existing.copy(goalChecks = checks,
                    exitFingerprint = finalFingerprint, exitPackage = finalSnapshot.packageName,
                    exitVisualFrameHash = completion.visualFrameHash,
                    goalParameterKey = SkillRequestPattern.parameterKey(task.parameters),
                    requestPatterns = (existing.requestPatterns + listOfNotNull(
                        SkillRequestPattern.capture(command, task.parameters))).distinct().takeLast(8),
                    version = existing.version + 1, status = SkillStatus.ACTIVE,
                    confidence = maxOf(.6, existing.confidence), successCount = existing.successCount + 1,
                    lastValidatedAt = System.currentTimeMillis()))
                return
            }
            // Optimize the complete observed route whenever its start was replayed in this run.
            // This also removes recovery detours which cross the old prefix/suffix boundary.
            val full = SkillLearner.learn(task, session.history, initialFingerprint, finalFingerprint)
            if (full != null) {
                val observed = full.copy(goalChecks = checks, exitPackage = finalSnapshot.packageName,
                    exitVisualFrameHash = completion.visualFrameHash)
                // App entry may have been skipped because the user was already in the app.
                // Replace the *observed* prefix as well, so a changed transition is not fabricated
                // from an old fingerprint, nor discarded merely because the launch was skipped.
                val startIndex = existing.steps.withIndex().filter { (index, step) ->
                    index <= repair.stepIndex && step.entryFingerprint == observed.entryFingerprint &&
                        existing.steps.take(index).all { it.action.type in APP_ENTRY_ACTIONS }
                }.singleOrNull()?.index
                val repaired = when {
                    observed.entryFingerprint == existing.entryFingerprint -> SkillLearner.repair(existing, 0, observed)
                    startIndex != null -> SkillLearner.repair(existing, startIndex, observed)
                    else -> null
                }
                if (repaired != null) {
                    skillRepository.save(repaired)
                    return
                }
            }
            val suffix = SkillLearner.learn(task, session.history.drop(repair.traceIndex),
                initialFingerprint, finalFingerprint)?.copy(
                    exitVisualFrameHash = completion.visualFrameHash, goalChecks = checks,
                    exitPackage = finalSnapshot.packageName) ?: return
            SkillLearner.repair(existing, repair.stepIndex, suffix)?.let(skillRepository::save)
            return
        }
        val skill = SkillLearner.learn(
            task = task,
            history = session.history,
            fallbackEntryFingerprint = initialFingerprint,
            fallbackExitFingerprint = finalFingerprint,
        ) ?: if (session.history.isEmpty() && checks.isNotEmpty() &&
            (task.taskType in OBSERVATIONAL_TASKS || com.hwanghj09.sonju.task.RequestInterpreter.isReadOnlyLookup(command))) {
            AppSkill(skillId = "observed." + SkillRequestPattern.digest("${task.key}:${task.requestKey}:$initialFingerprint").take(20),
                appId = task.appId ?: "any", taskType = task.taskType, name = task.taskType,
                description = "현재 화면에서 검증된 조회 결과", parameters = task.parameters.keys.map { com.hwanghj09.sonju.skill.SkillParameter(it) },
                entryFingerprint = initialFingerprint, exitFingerprint = finalFingerprint,
                steps = emptyList(), risk = task.risk, requestKey = task.requestKey,
                requestPatterns = listOfNotNull(SkillRequestPattern.capture(command, task.parameters)),
                goalParameterKey = SkillRequestPattern.parameterKey(task.parameters), parameterizedScreens = true,
                completionLevel = SkillRequestPattern.intent(command, task.parameters).requestedCompletionLevel,
                constraintKey = SkillRequestPattern.digest(SkillRequestPattern.intent(command, task.parameters).constraints.toString()),
                successCount = 1, lastValidatedAt = System.currentTimeMillis())
        } else return
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
            val retryable = snapshot != null && !plan.goalCompleted && plan.visualFrameHash == null
            if (!session.failSkill(it, plan.skillVersion ?: 1, reason, retryable)) return
            session.consumeSkillFailure()?.let(skillRepository::markFailure)
        }
        if (snapshot != null) session.recordPlanningRejection(plan, snapshot, reason, accessibilityFailure)
        else session.requestAiRecovery(reason, accessibilityFailure)
    }

    fun recordExecution(
        command: String,
        verifiedPlan: VerifiedPlan,
        result: ExecutionResult,
        session: AutonomySession? = null,
    ) {
        val action = verifiedPlan.actions.values.singleOrNull() ?: return
        val plan = verifiedPlan.plan
        val status = when {
            result.success -> ExecutionStatus.SUCCEEDED
            result.failureReason == ExecutionFailureReason.USER_CANCELLED -> ExecutionStatus.CANCELLED
            else -> ExecutionStatus.FAILED
        }
        session?.consumeSkillFailure()?.let(skillRepository::markFailure)
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
        val request = SkillRequestPattern.normalizeRequest(command)
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
        is PlannedAction.OpenApp -> AgentAction(ActionType.OPEN_APP, "앱을 엽니다.", packageOrLabel, initialQuery)
        is PlannedAction.OpenUrl -> AgentAction(ActionType.OPEN_URL, "저장된 웹페이지를 엽니다.", url, browserPackage)
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
        private val OBSERVATIONAL_TASKS = setOf("open_app", "search", "calculate", "directions")
        private val APP_ENTRY_ACTIONS = setOf(ActionType.OPEN_APP, ActionType.OPEN_URL, ActionType.OPEN_WIFI_SETTINGS,
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
