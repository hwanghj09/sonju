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
    )

    private val traces = mutableListOf<Trace>()
    private var pendingObservationIndex: Int? = null
    private var lastPlan: AgentPlan? = null
    private var revision = 0

    val toolCallCount: Int get() = traces.size
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
        )
        lastPlan = normalized
        return normalized
    }

    fun recordExecution(plan: AgentPlan, snapshot: UiSnapshot, result: ExecutionResult) {
        val action = plan.actions.firstOrNull { it.type != ActionType.FINISH } ?: return
        traces += Trace(
            action = action,
            beforePackage = snapshot.packageName,
            beforeFingerprint = snapshot.screenFingerprint(),
            succeeded = result.success,
            message = result.message.take(MAX_RESULT_LENGTH),
            beforeTemplateFingerprint = snapshot.semanticTemplateFingerprint(),
        )
        pendingObservationIndex = traces.lastIndex
    }

    /** Completes the most recent transition once the post-action screen is available. */
    fun observe(snapshot: UiSnapshot) {
        val index = pendingObservationIndex ?: return
        val pending = traces.getOrNull(index) ?: return
        val afterFingerprint = snapshot.screenFingerprint()
        traces[index] = pending.copy(
            afterPackage = snapshot.packageName,
            afterFingerprint = afterFingerprint,
            screenChanged = pending.beforePackage != snapshot.packageName ||
                pending.beforeFingerprint != afterFingerprint,
            afterTemplateFingerprint = snapshot.semanticTemplateFingerprint(),
        )
        pendingObservationIndex = null
    }

    fun canContinue(nowMillis: Long): Boolean =
        toolCallCount < maxToolCalls && nowMillis - startedAtMillis in 0..maxDurationMillis &&
            !hasDetectedLoop()

    fun hasDetectedLoop(): Boolean {
        if (traces.groupBy { trace ->
                "${trace.beforeTemplateFingerprint ?: trace.beforeFingerprint}:" +
                    actionSignature(trace.action)
            }.any { (_, matching) -> matching.size >= REPEATED_ACTION_LIMIT }
        ) return true
        val states = traces.takeLast(4).mapNotNull { it.afterTemplateFingerprint ?: it.afterFingerprint }
        if (states.size == 4 && states[0] == states[2] && states[1] == states[3] &&
            states[0] != states[1]
        ) return true
        return traces.takeLast(NO_CHANGE_LIMIT).size == NO_CHANGE_LIMIT &&
            traces.takeLast(NO_CHANGE_LIMIT).all { it.screenChanged == false }
    }

    /** Exact actions that failed twice on the same semantic screen must not be proposed again. */
    fun discouragedActionSignatures(): Set<String> = traces.asSequence()
        .filterNot(Trace::succeeded)
        .groupBy { trace -> "${trace.beforeFingerprint}:${actionSignature(trace.action)}" }
        .filterValues { it.size >= REPEATED_FAILURE_LIMIT }
        .values
        .mapTo(linkedSetOf()) { failures -> actionSignature(failures.last().action) }

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
            lastPlan?.let { plan ->
                appendLine("직전 실행 앱: ${plan.targetApp}")
                appendLine("직전 목표 화면/기능: ${plan.targetSurface}")
                if (plan.strategy.isNotEmpty()) {
                    appendLine("직전 전략: ${plan.strategy.joinToString(" -> ")}")
                }
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
        const val DEFAULT_MAX_TOOL_CALLS = 24
        const val DEFAULT_MAX_DURATION_MILLIS = 180_000L
        private const val REPEATED_FAILURE_LIMIT = 2
        private const val REPEATED_ACTION_LIMIT = 3
        private const val NO_CHANGE_LIMIT = 3
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
}
