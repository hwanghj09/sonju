package com.hwanghj09.sonju.execution

enum class ExecutionMethod {
    ACCESSIBILITY_NODE_ACTION,
    ACCESSIBILITY_ANCESTOR_ACTION,
    GLOBAL_ACTION,
    GESTURE,
    VLM_GESTURE,
}

enum class ExecutionFailureReason {
    INTENT_PARSE_FAILED,
    APP_NOT_INSTALLED,
    ACCESSIBILITY_ROOT_UNAVAILABLE,
    SCREEN_UNKNOWN,
    SKILL_NOT_FOUND,
    SKILL_STALE,
    GROUNDING_NOT_FOUND,
    GROUNDING_AMBIGUOUS,
    ACTION_REJECTED_BY_VERIFIER,
    NODE_ACTION_FAILED,
    GESTURE_FAILED,
    POSTCONDITION_TIMEOUT,
    LOOP_DETECTED,
    VLM_FAILED,
    MODEL_FAILED,
    USER_CANCELLED,
    UNSUPPORTED_FLOW,
    INTERNAL_ERROR,
}

data class RetryPolicy(
    val maxAttempts: Int = 1,
    val retryOn: Set<ExecutionFailureReason> = setOf(
        ExecutionFailureReason.GROUNDING_NOT_FOUND,
        ExecutionFailureReason.NODE_ACTION_FAILED,
        ExecutionFailureReason.POSTCONDITION_TIMEOUT,
    ),
    val backoffMs: Long = 200,
) {
    init {
        require(maxAttempts in 0..5) { "retry budget must be finite" }
        require(backoffMs in 0..10_000) { "retry backoff is out of range" }
    }
}

enum class StepFallbackPolicy { REFRESH, REGROUND, REPLAN, VISUAL, ABORT }

data class PostconditionResult(
    val satisfied: Boolean,
    val observedFingerprint: String?,
    val reason: String?,
)

interface PostconditionMonitor {
    fun waitUntil(timeoutMs: Long, callback: (PostconditionResult) -> Unit)
}

enum class LoopStatus { CLEAR, REPEATED_STATE_ACTION, TWO_STATE_CYCLE, NO_STATE_CHANGE }

data class LoopObservation(
    val beforeFingerprint: String,
    val actionSignature: String,
    val afterFingerprint: String?,
)

interface LoopDetector {
    fun observe(observation: LoopObservation): LoopStatus
    fun reset()
}

/** Bounded in-memory detector used by a single foreground session. */
class BoundedLoopDetector(private val historyLimit: Int = 12) : LoopDetector {
    private val history = ArrayDeque<LoopObservation>()

    override fun observe(observation: LoopObservation): LoopStatus {
        history += observation
        while (history.size > historyLimit) history.removeFirst()
        val repeated = history.count {
            it.beforeFingerprint == observation.beforeFingerprint &&
                it.actionSignature == observation.actionSignature
        }
        if (repeated >= 3) return LoopStatus.REPEATED_STATE_ACTION
        if (history.takeLast(4).mapNotNull(LoopObservation::afterFingerprint).let { states ->
                states.size == 4 && states[0] == states[2] && states[1] == states[3] &&
                    states[0] != states[1]
            }
        ) return LoopStatus.TWO_STATE_CYCLE
        if (history.takeLast(3).all {
                it.afterFingerprint != null && it.beforeFingerprint == it.afterFingerprint
            }
        ) return LoopStatus.NO_STATE_CHANGE
        return LoopStatus.CLEAR
    }

    override fun reset() = history.clear()
}

