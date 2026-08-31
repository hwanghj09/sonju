package com.hwanghj09.sonju.agent

data class AgentConfig(
    val maxTaskSteps: Int = 30,
    val maxStepRetries: Int = 2,
    val maxScrollSearchSteps: Int = 8,
    val eventDebounceMs: Long = 200,
    val defaultPostconditionTimeoutMs: Long = 7_000,
    val minDirectGroundingConfidence: Double = .90,
    val minStructuredGroundingConfidence: Double = .70,
    val enableVlmFallback: Boolean = true,
    val enableSkillLearning: Boolean = true,
    val enableAutomaticExploration: Boolean = false,
    val failClosedForHighRisk: Boolean = true,
)

enum class FallbackDecision { REFRESH, REGROUND, SCROLL_SEARCH, REPLAN, VISUAL, ASK_USER, ABORT }

interface FallbackRouter {
    fun route(
        failure: com.hwanghj09.sonju.execution.ExecutionFailureReason,
        observationQuality: Double,
        retryCount: Int,
    ): FallbackDecision
}

class DeterministicFallbackRouter(
    private val config: AgentConfig = AgentConfig(),
) : FallbackRouter {
    override fun route(
        failure: com.hwanghj09.sonju.execution.ExecutionFailureReason,
        observationQuality: Double,
        retryCount: Int,
    ): FallbackDecision {
        if (retryCount >= config.maxStepRetries) {
            return if (config.enableVlmFallback && observationQuality < .55) {
                FallbackDecision.VISUAL
            } else {
                FallbackDecision.ABORT
            }
        }
        return when (failure) {
            com.hwanghj09.sonju.execution.ExecutionFailureReason.GROUNDING_NOT_FOUND ->
                FallbackDecision.REFRESH
            com.hwanghj09.sonju.execution.ExecutionFailureReason.GROUNDING_AMBIGUOUS ->
                FallbackDecision.REGROUND
            com.hwanghj09.sonju.execution.ExecutionFailureReason.POSTCONDITION_TIMEOUT ->
                FallbackDecision.REPLAN
            com.hwanghj09.sonju.execution.ExecutionFailureReason.LOOP_DETECTED ->
                FallbackDecision.ABORT
            com.hwanghj09.sonju.execution.ExecutionFailureReason.ACTION_REJECTED_BY_VERIFIER ->
                FallbackDecision.ASK_USER
            else -> if (observationQuality < .55 && config.enableVlmFallback) {
                FallbackDecision.VISUAL
            } else {
                FallbackDecision.REPLAN
            }
        }
    }
}

