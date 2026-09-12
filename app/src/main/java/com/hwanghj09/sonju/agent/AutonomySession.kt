package com.hwanghj09.sonju.agent

import java.security.MessageDigest
import java.text.Normalizer

/**
 * Mutable state for one user-initiated autonomous run.
 *
 * The final goal is captured once and never taken from later model output. Everything else can be
 * revised after a new screen observation or a failed tool call. The class is Android-free so the
 * planning/execution policy can be unit tested without an emulator.
 */
class AutonomySession(
    val finalGoal: String,
    val initialSnapshot: UiSnapshot,
    val startedAtMillis: Long,
    private val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
    private val maxDurationMillis: Long = DEFAULT_MAX_DURATION_MILLIS,
) {
    data class Trace(
        val action: AgentAction,
        val beforePackage: String,
        val beforeFingerprint: String,
        val succeeded: Boolean,
        val message: String,
        val afterPackage: String? = null,
        val afterFingerprint: String? = null,
        val screenChanged: Boolean? = null,
        val beforeTemplateFingerprint: String? = null,
        val afterTemplateFingerprint: String? = null,
        val postconditionMismatch: Boolean = false,
        val visualFrameHash: String? = null,
        val afterVisualFrameHash: String? = null,
        val notDispatched: Boolean = false,
    )

    private val traces = mutableListOf<Trace>()
    private var pendingObservationIndex: Int? = null
    private var lastPlan: AgentPlan? = null
    private var revision = 0
    var aiRecoveryRequested: Boolean = false
        private set
    val visualFallbackActive: Boolean get() = accessibilityFailures >= 2 && accessibilityModelAttempted ||
        renderedContentUnavailable || visualProgressCredit
    private var renderedContentUnavailable = false
    private var accessibilityFailures = 0
    private var accessibilityModelAttempted = false
    private var recoveryScreen = initialSnapshot.semanticTemplateFingerprint()
    private val visualAttemptedFrames = mutableSetOf<Pair<String, String>>()
    private var visualProgressCredit = false
    private var nativeProgressCredit = false
    private var modelCalls = 0
    private var recoveryReason = ""
    private data class RejectedAction(val screen: String, val signature: String, val reason: String)
    private val rejectedActions = mutableListOf<RejectedAction>()
    data class UserHandoff(
        val origin: String?,
        val startedAtMillis: Long,
        val beforePackage: String,
        val beforeFingerprint: String,
        val beforeTemplateFingerprint: String,
        val skillId: String?,
        val kind: UserIntervention.Kind = UserIntervention.Kind.LOGIN,
        val resumePackage: String = beforePackage,
        val resumeBaselineTemplateFingerprint: String? = null,
        val automaticResume: Boolean = true,
    )
    var userHandoff: UserHandoff? = null
        private set
    private var pausedDurationMillis = 0L
    private var resumeCandidate: Pair<String, Long>? = null
    private var lastReadyPackage = initialSnapshot.packageName
    private var lastReadyOrigin = UserIntervention.browserOrigin(initialSnapshot)

    fun pauseForUser(plan: AgentPlan, snapshot: UiSnapshot, nowMillis: Long, observedReturnPackage: String? = null): Boolean {
        val local = UserIntervention.plan(finalGoal, snapshot) ?: return false
        if (plan.actions.filterNot { it.type == ActionType.FINISH }.singleOrNull()?.let {
                it.type == ActionType.WAIT_FOR_USER && it.target == local.actions.single().target } != true) return false
        if (userHandoff != null) return true
        observe(snapshot)
        pendingObservationIndex = null
        val owner = if (HospitalReservationWorkflow.matches(finalGoal)) snapshot.packageName else
            traces.lastOrNull()?.takeIf { it.succeeded && it.action.type == ActionType.OPEN_APP }?.action?.target
                ?.takeIf { PACKAGE_NAME.matches(it) }
                ?: latestPlan?.targetApp?.takeIf { PACKAGE_NAME.matches(it) }
                ?: observedReturnPackage?.takeIf { PACKAGE_NAME.matches(it) } ?: snapshot.packageName
        val origin = HospitalReservationWorkflow.siteFor(finalGoal)?.origin ?: when (owner) {
            lastReadyPackage -> lastReadyOrigin
            snapshot.packageName -> UserIntervention.browserOrigin(snapshot)
            else -> null
        }
        userHandoff = UserHandoff(origin,
            nowMillis, snapshot.packageName, snapshot.screenFingerprint(), snapshot.semanticTemplateFingerprint(), plan.skillId,
            requireNotNull(UserIntervention.required(snapshot, finalGoal)), owner,
            traces.lastOrNull { it.beforePackage == owner }?.beforeTemplateFingerprint)
        return true
    }

    fun restoreUserHandoff(handoff: UserHandoff, nowMillis: Long) {
        userHandoff = handoff.copy(startedAtMillis = nowMillis, skillId = null, automaticResume = false)
        resumeCandidate = null
    }

    /** Records a human transition separately; never records passwords, OTPs, or user input actions. */
    fun resumeAfterUser(snapshot: UiSnapshot, nowMillis: Long, userConfirmed: Boolean = true): Boolean {
        val handoff = userHandoff ?: return false
        if (nowMillis < handoff.startedAtMillis || !UserIntervention.canResume(handoff, snapshot, finalGoal)) {
            resumeCandidate = null
            return false
        }
        if (!userConfirmed) {
            val fingerprint = snapshot.semanticTemplateFingerprint()
            if (!handoff.automaticResume || fingerprint == handoff.resumeBaselineTemplateFingerprint) {
                resumeCandidate = null
                return false
            }
            val candidate = resumeCandidate
            if (candidate?.first != fingerprint) {
                resumeCandidate = fingerprint to nowMillis
                return false
            }
            if (nowMillis - candidate.second < USER_RESUME_SETTLE_MILLIS) return false
        }
        pausedDurationMillis += nowMillis - handoff.startedAtMillis
        traces += Trace(
            action = AgentAction(ActionType.WAIT_FOR_USER, "사용자 확인 후 원래 작업 화면 복귀를 확인했습니다.",
                HospitalReservationWorkflow.siteFor(finalGoal)?.origin ?: handoff.beforePackage),
            beforePackage = handoff.beforePackage, beforeFingerprint = handoff.beforeFingerprint,
            beforeTemplateFingerprint = handoff.beforeTemplateFingerprint,
            afterPackage = snapshot.packageName, afterFingerprint = snapshot.screenFingerprint(),
            afterTemplateFingerprint = snapshot.semanticTemplateFingerprint(),
            succeeded = true, screenChanged = true, message = "human checkpoint verified",
        )
        pendingObservationIndex = traces.lastIndex
        if (handoff.skillId != null && handoff.skillId == activeSkillId) nextSkillStep++
        userHandoff = null
        resumeCandidate = null
        return true
    }
    var activeSkillId: String? = null
        private set
    var nextSkillStep: Int = 0
        private set
    data class SkillRepair(val skillId: String, val version: Int, val stepIndex: Int, val traceIndex: Int)
    var skillRepair: SkillRepair? = null
        private set

    fun selectSkill(skillId: String, stepIndex: Int = 0) {
        activeSkillId = skillId
        nextSkillStep = stepIndex
    }

    fun requestAiRecovery(reason: String, accessibilityFailure: Boolean = false, snapshot: UiSnapshot? = null) {
        aiRecoveryRequested = true
        recoveryReason = reason.take(1000)
        if (accessibilityFailure && snapshot != null) {
            observeRecoveryScreen(snapshot)
            accessibilityFailures++
        }
    }

    fun recordPlanningRejection(plan: AgentPlan, snapshot: UiSnapshot, reason: String,
                                accessibilityFailure: Boolean = false) {
        requestAiRecovery(reason, accessibilityFailure, snapshot)
        val action = plan.actions.firstOrNull { it.type != ActionType.FINISH } ?: return
        rejectedActions += RejectedAction(snapshot.semanticTemplateFingerprint(),
            actionSignature(canonicalAction(action, snapshot)), reason.take(180))
        if (rejectedActions.size > MAX_CONTEXT_TRACES) rejectedActions.removeAt(0)
    }

    /** A model cannot spend the remaining session repeatedly dispatching a known failed action. */
    fun repeatedActionFailure(action: AgentAction, snapshot: UiSnapshot, resolvedNodeId: String? = null): String? {
        observeRecoveryScreen(snapshot)
        val candidate = canonicalAction(action.copy(target = resolvedNodeId ?: action.target), snapshot)
        return if (actionSignature(candidate) in discouragedActionSignatures()) {
            "현재 화면에서 반복 실패한 동작입니다. 다른 도구나 대상을 선택해야 합니다."
        } else null
    }

    private fun observeRecoveryScreen(snapshot: UiSnapshot) {
        val fingerprint = snapshot.semanticTemplateFingerprint()
        if (fingerprint != recoveryScreen) {
            recoveryScreen = fingerprint
            accessibilityFailures = 0
            accessibilityModelAttempted = false
            renderedContentUnavailable = false
        }
    }

    fun recordAccessibilityModelAttempt(snapshot: UiSnapshot) {
        observeRecoveryScreen(snapshot)
        accessibilityModelAttempted = true
    }

    /** Two live reads can establish missing renderer semantics without spending model calls. */
    fun recordUnobservedRenderedContent(before: UiSnapshot, after: UiSnapshot): Boolean {
        observeRecoveryScreen(after)
        renderedContentUnavailable = before.packageName == after.packageName && before.windowId == after.windowId &&
            !before.treeTruncated && !after.treeTruncated &&
            ScreenContextHandoff.hasUnobservedRenderedContent(before) &&
            ScreenContextHandoff.hasUnobservedRenderedContent(after)
        return renderedContentUnavailable
    }

    fun canAttemptVisualFallback(snapshot: UiSnapshot): Boolean {
        observeRecoveryScreen(snapshot)
        return visualFallbackActive && visualAttemptedFrames.size < MAX_VISUAL_CALLS &&
            (visualAttemptedFrames.size < 2 || visualProgressCredit || nativeProgressCredit)
    }

    /** Charge only distinct frames. Extra calls require a verified native or visual transition. */
    fun reserveVisualFallback(snapshot: UiSnapshot, frameHash: String): Boolean {
        if (frameHash.isBlank() || !canAttemptVisualFallback(snapshot) ||
            !visualAttemptedFrames.add(recoveryScreen to frameHash)) return false
        visualProgressCredit = false
        nativeProgressCredit = false
        return true
    }

    fun hasVisualAttemptFor(snapshot: UiSnapshot, frameHash: String?): Boolean =
        frameHash != null && (snapshot.semanticTemplateFingerprint() to frameHash) in visualAttemptedFrames

    fun beginSkillRepair(skillId: String, version: Int, stepIndex: Int, traceIndex: Int = traces.size) {
        if (skillRepair == null) skillRepair = SkillRepair(skillId, version, stepIndex, traceIndex)
    }

    fun reserveModelCall(nowMillis: Long): Boolean {
        if (!canContinue(nowMillis) || modelCalls >= MAX_MODEL_CALLS) return false
        // Local app-entry rules must not reopen a step after the model takes over.
        aiRecoveryRequested = true
        modelCalls++
        return true
    }

    val toolCallCount: Int get() = traces.size
    val modelCallCount: Int get() = modelCalls
    val needsFreshObservation: Boolean get() = aiRecoveryRequested || activeSkillId != null
    val latestPlan: AgentPlan? get() = lastPlan
    val history: List<Trace> get() = traces.toList()

    /**
     * Enforces the plan contract locally. A model cannot replace [finalGoal], forge a revision, or
     * batch speculative actions that depend on screens which have not been observed yet.
     */
    fun acceptPlan(candidate: AgentPlan, snapshot: UiSnapshot): AgentPlan {
        revision += 1
        val previous = lastPlan
        val executable = candidate.actions.filterNot { it.type == ActionType.FINISH }
        val oneObservedAction = when {
            candidate.goalCompleted -> emptyList()
            executable.size <= 1 -> executable
            else -> executable.take(1)
        }
        val actions = if (candidate.goalCompleted) {
            listOf(AgentAction(ActionType.FINISH, "최종 목표 달성을 확인했습니다."))
        } else {
            oneObservedAction + candidate.actions.firstOrNull { it.type == ActionType.FINISH }
                .let { finish -> listOf(finish ?: AgentAction(ActionType.FINISH, "화면을 다시 관찰합니다.")) }
        }
        val inferredTools = candidate.requiredTools.ifEmpty {
            candidate.actions.mapTo(linkedSetOf()) { it.type }
                .filterTo(linkedSetOf()) { it != ActionType.FINISH }
        }
        val normalized = candidate.copy(
            goal = finalGoal,
            targetApp = candidate.targetApp.ifBlank {
                previous?.targetApp?.takeIf(String::isNotBlank) ?: snapshot.packageName
            }.take(MAX_PLAN_FIELD_LENGTH),
            targetSurface = candidate.targetSurface.ifBlank {
                previous?.targetSurface?.takeIf(String::isNotBlank)
                    ?: snapshot.windowTitle.orEmpty()
            }.take(MAX_PLAN_FIELD_LENGTH),
            requiredTools = inferredTools,
            strategy = candidate.strategy.ifEmpty {
                candidate.actions.map(AgentAction::description).filter(String::isNotBlank)
            }.take(MAX_STRATEGY_STEPS).map { it.take(MAX_PLAN_FIELD_LENGTH) },
            successCriteria = candidate.successCriteria.ifEmpty {
                listOfNotNull(candidate.targetSurface.takeIf(String::isNotBlank))
            }.take(MAX_SUCCESS_CRITERIA).map { it.take(MAX_PLAN_FIELD_LENGTH) },
            revisionReason = candidate.revisionReason.take(MAX_PLAN_FIELD_LENGTH),
            revision = revision,
            actions = actions,
            continueAfterAction = candidate.continueAfterAction || (!candidate.goalCompleted &&
                candidate.source in setOf(PlanSource.OPENAI_STRUCTURE, PlanSource.OPENAI_SEMANTIC_MAP)),
        )
        lastPlan = normalized
        return normalized
    }

    fun recordExecution(plan: AgentPlan, snapshot: UiSnapshot, result: ExecutionResult, resolvedNodeId: String? = null) {
        val proposed = plan.actions.firstOrNull { it.type != ActionType.FINISH } ?: return
        observe(snapshot)
        val node = snapshot.elements.firstOrNull { it.path == resolvedNodeId && !it.sensitive }
        val stableId = node?.viewId?.takeIf { id -> snapshot.elements.count { it.viewId == id } == 1 }
        val uniqueLabel = if (node != null && proposed.type == ActionType.CLICK) snapshot.elements.asSequence()
            .filter { !it.sensitive && it.visible && (it.path == node.path || it.path.startsWith("${node.path}.")) }
            .mapNotNull { it.text?.takeIf(String::isNotBlank) ?: it.contentDescription?.takeIf(String::isNotBlank) }
            .firstOrNull { label -> snapshot.elements.count { it.text == label || it.contentDescription == label } == 1 &&
                com.hwanghj09.sonju.logging.RedactionPolicy.redact(label) == label } else null
        val action = if (node != null && proposed.type in setOf(ActionType.CLICK, ActionType.SET_TEXT,
                ActionType.SUBMIT_TEXT)) proposed.copy(target = stableId ?: uniqueLabel ?: node.path)
            else canonicalAction(proposed, snapshot)
        traces += Trace(
            action = action,
            beforePackage = snapshot.packageName,
            beforeFingerprint = snapshot.screenFingerprint(),
            succeeded = result.success && result.postconditionSatisfied != false,
            message = result.message.take(MAX_RESULT_LENGTH),
            beforeTemplateFingerprint = snapshot.semanticTemplateFingerprint(),
            postconditionMismatch = result.failureReason ==
                com.hwanghj09.sonju.execution.ExecutionFailureReason.POSTCONDITION_TIMEOUT,
            visualFrameHash = plan.visualFrameHash,
            afterVisualFrameHash = result.afterVisualFrameHash,
            notDispatched = result.failureReason ==
                com.hwanghj09.sonju.execution.ExecutionFailureReason.NODE_ACTION_FAILED,
        )
        visualProgressCredit = result.success && result.postconditionSatisfied == true &&
            plan.visualFrameHash != null && result.afterVisualFrameHash != null &&
            plan.visualFrameHash != result.afterVisualFrameHash
        nativeProgressCredit = nativeProgressCredit || (result.success && result.postconditionSatisfied == true &&
            action.type != ActionType.WAIT && result.beforeFingerprint != null && result.afterFingerprint != null &&
            result.beforeFingerprint != result.afterFingerprint)
        pendingObservationIndex = traces.lastIndex
        if (result.success && result.postconditionSatisfied != false) {
            if (action.type != ActionType.WAIT) {
                accessibilityFailures = 0
                accessibilityModelAttempted = false
            }
            if (plan.skillId == activeSkillId && plan.skillId != null) nextSkillStep++
        } else if (result.failureReason != com.hwanghj09.sonju.execution.ExecutionFailureReason.USER_CANCELLED) {
            plan.skillId?.let { beginSkillRepair(it, plan.skillVersion ?: 1, nextSkillStep, traces.lastIndex) }
            requestAiRecovery(result.message, action.type in ACCESSIBILITY_ACTIONS && result.failureReason in setOf(
                com.hwanghj09.sonju.execution.ExecutionFailureReason.GROUNDING_NOT_FOUND,
                com.hwanghj09.sonju.execution.ExecutionFailureReason.NODE_ACTION_FAILED,
            ), snapshot)
        }
    }

    /** Follow settling/loading changes until the next action fixes the transition boundary. */
    fun observe(snapshot: UiSnapshot) {
        observeRecoveryScreen(snapshot)
        if (snapshot.packageName != "unknown" && snapshot.elements.isNotEmpty() &&
            UserIntervention.required(snapshot, finalGoal) == null) {
            lastReadyPackage = snapshot.packageName
            lastReadyOrigin = UserIntervention.browserOrigin(snapshot)
        }
        val index = pendingObservationIndex ?: return
        val pending = traces.getOrNull(index) ?: return
        val afterFingerprint = snapshot.screenFingerprint()
        traces[index] = pending.copy(
            afterPackage = snapshot.packageName,
            afterFingerprint = afterFingerprint,
            screenChanged = pending.beforePackage != snapshot.packageName ||
                pending.beforeFingerprint != afterFingerprint ||
                pending.afterVisualFrameHash?.let { it != pending.visualFrameHash } == true,
            afterTemplateFingerprint = snapshot.semanticTemplateFingerprint(),
        )
    }

    fun canContinue(nowMillis: Long): Boolean = stopReason(nowMillis) == null

    fun stopReason(nowMillis: Long): String? = when {
        userHandoff != null -> "직접 확인이 끝날 때까지 요청을 보관하고 기다리고 있어요."
        toolCallCount >= maxToolCalls -> "도구 실행 횟수 한도에 도달해 멈췄어요."
        nowMillis - startedAtMillis - pausedDurationMillis !in 0..maxDurationMillis ->
            "사용자 확인 대기를 제외한 실행 시간이 3분을 넘어 멈췄어요."
        hasDetectedLoop() -> "같은 동작이 반복되어 멈췄어요. 현재 화면을 다시 확인해야 합니다."
        else -> null
    }

    fun hasDetectedLoop(): Boolean {
        if (traces.filter { !it.succeeded || it.screenChanged == false }.groupBy { trace ->
                "${trace.beforeFingerprint}:" +
                    actionSignature(trace.action)
            }.any { (_, matching) -> matching.size >= REPEATED_ACTION_LIMIT }
        ) return true
        // Template fingerprints erase numbers; repeated keypad clicks can be real progress.
        val states = traces.takeLast(4).mapNotNull { it.afterFingerprint }
        if (states.size == 4 && states[0] == states[2] && states[1] == states[3] &&
            states[0] != states[1]
        ) return true
        val unchanged = traces.takeLast(NO_CHANGE_LIMIT)
        return unchanged.size == NO_CHANGE_LIMIT && unchanged.all { it.screenChanged == false }
    }

    /** Exact actions that failed twice on the same semantic screen must not be proposed again. */
    fun discouragedActionSignatures(): Set<String> = buildList {
        traces.filter { (!it.succeeded || it.screenChanged == false) &&
            (it.beforeTemplateFingerprint ?: it.beforeFingerprint) == recoveryScreen }
            .forEach { add(actionSignature(it.action)) }
        rejectedActions.filter { it.screen == recoveryScreen }.forEach { add(it.signature) }
    }.groupingBy { it }.eachCount().filterValues { it >= REPEATED_FAILURE_LIMIT }.keys

    fun successfulActions(): List<AgentAction> = traces.asSequence()
        .filter(Trace::succeeded)
        .map(Trace::action)
        .filterNot { it.type in setOf(ActionType.WAIT, ActionType.FINISH) }
        .toList()

    fun plannerContext(snapshot: UiSnapshot, learnedRouteHint: String?): String {
        observe(snapshot)
        val discouraged = discouragedActionSignatures()
        return buildString {
            appendLine("고정 최종 목표: ${finalGoal.take(MAX_PLAN_FIELD_LENGTH)}")
            appendLine("로컬 계획 리비전: $revision")
            if (recoveryReason.isNotBlank()) appendLine("재계획 필수: $recoveryReason")
            appendLine("남은 도구: ${maxToolCalls - toolCallCount}, 남은 모델 호출: ${MAX_MODEL_CALLS - modelCalls}")
            skillRepair?.let { appendLine("AppSkill 복구: ${it.skillId}, 실패 단계 ${it.stepIndex + 1}. 현재 화면부터 목표까지 완료한다.") }
            lastPlan?.let { plan ->
                appendLine("직전 실행 앱: ${plan.targetApp}")
                appendLine("직전 목표 화면/기능: ${plan.targetSurface}")
                if (plan.strategy.isNotEmpty()) {
                    appendLine("직전 전략: ${plan.strategy.joinToString(" -> ")}")
                }
            }
            rejectedActions.filter { it.screen == recoveryScreen }.takeLast(3).forEach {
                appendLine("실행 전에 거절된 후보: ${it.signature} -> ${it.reason}")
            }
            if (traces.lastOrNull()?.let { !it.succeeded && it.action.type == ActionType.SET_TEXT } == true) {
                appendLine("입력 후 조건 검증이 일치하지 않았다. 현재 값을 먼저 확인한다. 이미 요청한 값이면 다음 단계로 진행하고, 값이 다르면 보이는 키패드/다른 입력 방식을 사용한다.")
            }
            if (!learnedRouteHint.isNullOrBlank()) {
                appendLine("과거의 더 짧은 성공 경로(현재 화면과 맞을 때만 응용):")
                appendLine(learnedRouteHint.take(MAX_ROUTE_HINT_LENGTH))
            }
            if (traces.isNotEmpty()) {
                appendLine("최근 도구 결과:")
                traces.takeLast(MAX_CONTEXT_TRACES).forEachIndexed { index, trace ->
                    val changed = when (trace.screenChanged) {
                        true -> "화면변화"
                        false -> "변화없음"
                        null -> "변화확인전"
                    }
                    appendLine(
                        "${index + 1}. ${actionSignature(trace.action)} -> " +
                            "${if (trace.succeeded) "성공" else "실패"}, $changed, " +
                            trace.message.take(120),
                    )
                }
            }
            if (discouraged.isNotEmpty()) {
                appendLine("같은 화면에서 반복 실패하여 다른 도구/대상을 써야 하는 동작:")
                discouraged.forEach { appendLine("- $it") }
            }
        }.take(MAX_CONTEXT_LENGTH)
    }

    companion object {
        const val USER_RESUME_SETTLE_MILLIS = 1_200L
        private val PACKAGE_NAME = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+")
        const val DEFAULT_MAX_TOOL_CALLS = 24
        const val DEFAULT_MAX_DURATION_MILLIS = 180_000L
        private const val MAX_MODEL_CALLS = 24
        private const val MAX_VISUAL_CALLS = 6
        private val ACCESSIBILITY_ACTIONS = setOf(ActionType.CLICK, ActionType.SET_TEXT,
            ActionType.SUBMIT_TEXT, ActionType.SCROLL_UP, ActionType.SCROLL_DOWN,
            ActionType.SCROLL_LEFT, ActionType.SCROLL_RIGHT)
        private const val REPEATED_FAILURE_LIMIT = 2
        private const val REPEATED_ACTION_LIMIT = 3
        // Leave room for a different recovery strategy after an initial failed action and retry.
        private const val NO_CHANGE_LIMIT = 6
        private const val MAX_CONTEXT_TRACES = 8
        private const val MAX_CONTEXT_LENGTH = 6_000
        private const val MAX_ROUTE_HINT_LENGTH = 2_000
        private const val MAX_PLAN_FIELD_LENGTH = 300
        private const val MAX_STRATEGY_STEPS = 12
        private const val MAX_SUCCESS_CRITERIA = 8
        private const val MAX_RESULT_LENGTH = 500

        fun actionSignature(action: AgentAction): String = buildString {
            append(action.type.name)
            action.target?.takeIf(String::isNotBlank)?.let {
                append(':').append(normalizeSelector(it).take(120))
            }
            if (action.type in setOf(ActionType.SET_TEXT, ActionType.SUBMIT_TEXT)) {
                // Distinguish corrected input without copying its value into recovery diagnostics.
                val hash = MessageDigest.getInstance("SHA-256").digest(action.value.orEmpty().toByteArray(Charsets.UTF_8))
                append(":input=").append(hash.take(6).joinToString("") { "%02x".format(it) })
            }
            if (action.type == ActionType.CLICK_COORDINATE) {
                append(':').append(action.xRatio?.let { "%.3f".format(it) } ?: "?")
                append(',').append(action.yRatio?.let { "%.3f".format(it) } ?: "?")
            }
        }

        fun stableGoalKey(goal: String): String {
            val normalized = Normalizer.normalize(goal, Normalizer.Form.NFKC)
                .lowercase()
                .replace(Regex("[0-9]{2,}"), "#")
                .replace(Regex("[^\\p{L}\\p{Nd}#]+"), " ")
                .trim()
            return MessageDigest.getInstance("SHA-256")
                .digest(normalized.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }

        private fun normalizeSelector(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFKC).trim().lowercase()
    }

    private fun canonicalAction(action: AgentAction, snapshot: UiSnapshot): AgentAction {
        if (action.type !in setOf(ActionType.CLICK, ActionType.SET_TEXT, ActionType.SUBMIT_TEXT)) return action
        val selector = action.target ?: return action
        val value = selector.substringAfter('=', selector)
        val node = snapshot.elements.filter { !it.sensitive && it.visible &&
            value in listOf(it.path, it.viewId, it.text, it.contentDescription, it.hintText) }.singleOrNull() ?: return action
        val id = node.viewId?.takeIf { id -> snapshot.elements.count { it.viewId == id } == 1 }
        val label = sequenceOf(node.text, node.contentDescription).filterNotNull().firstOrNull { label ->
            label.isNotBlank() && snapshot.elements.count { it.text == label || it.contentDescription == label } == 1
        }
        return action.copy(target = id ?: label ?: node.path)
    }
}
