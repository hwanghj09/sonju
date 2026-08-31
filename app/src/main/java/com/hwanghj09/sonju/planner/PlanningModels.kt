package com.hwanghj09.sonju.planner

import com.hwanghj09.sonju.execution.RetryPolicy
import com.hwanghj09.sonju.execution.StepFallbackPolicy
import com.hwanghj09.sonju.grounding.GroundingQuery
import com.hwanghj09.sonju.perception.ScreenState
import com.hwanghj09.sonju.skill.AppSkill
import com.hwanghj09.sonju.task.CanonicalTask
import com.hwanghj09.sonju.verifier.Predicate

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }
enum class ScrollAmount { SMALL, MEDIUM, LARGE }

sealed interface PlannedAction {
    data class Click(val target: GroundingQuery) : PlannedAction
    data class SetText(
        val target: GroundingQuery,
        val value: String,
        val sensitive: Boolean = false,
    ) : PlannedAction
    data class Scroll(
        val target: GroundingQuery?,
        val direction: ScrollDirection,
        val amount: ScrollAmount = ScrollAmount.MEDIUM,
    ) : PlannedAction
    data class OpenApp(val packageOrLabel: String) : PlannedAction
    data object Back : PlannedAction
    data object Home : PlannedAction
    data class WaitFor(val condition: Predicate, val timeoutMs: Long) : PlannedAction
    data class VisualClick(
        val xRatio: Double,
        val yRatio: Double,
        val description: String,
    ) : PlannedAction
}

data class PlanStep(
    val stepId: String,
    val precondition: Predicate? = null,
    val action: PlannedAction,
    val postcondition: Predicate? = null,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val fallbackPolicy: StepFallbackPolicy = StepFallbackPolicy.REPLAN,
)

enum class PlannerSource { SKILL_FAST_PATH, LOCAL_RULE, EXPLORATORY_MODEL, VISUAL_FALLBACK }

data class Plan(
    val goal: String,
    val steps: List<PlanStep>,
    val source: PlannerSource,
    val confidence: Double,
    val complete: Boolean = false,
)

interface Planner {
    fun plan(task: CanonicalTask, screen: ScreenState, skills: List<AppSkill>): Plan?
}

