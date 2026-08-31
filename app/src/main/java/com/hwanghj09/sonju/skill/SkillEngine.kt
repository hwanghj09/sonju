package com.hwanghj09.sonju.skill

import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.AutonomySession
import com.hwanghj09.sonju.execution.RetryPolicy
import com.hwanghj09.sonju.execution.StepFallbackPolicy
import com.hwanghj09.sonju.grounding.GroundingQuery
import com.hwanghj09.sonju.perception.ScreenState
import com.hwanghj09.sonju.planner.Plan
import com.hwanghj09.sonju.planner.PlanStep
import com.hwanghj09.sonju.planner.PlannedAction
import com.hwanghj09.sonju.planner.Planner
import com.hwanghj09.sonju.planner.PlannerSource
import com.hwanghj09.sonju.planner.ScrollDirection
import com.hwanghj09.sonju.task.CanonicalTask
import com.hwanghj09.sonju.task.TaskParameter
import com.hwanghj09.sonju.task.TaskRisk
import com.hwanghj09.sonju.verifier.Predicate
import java.security.MessageDigest

enum class ParameterType { STRING, NUMBER, BOOLEAN, DATE, TIME }
enum class SkillStatus { ACTIVE, SUSPECT, NEEDS_REPAIR, DISABLED }

data class SkillParameter(
    val name: String,
    val type: ParameterType = ParameterType.STRING,
    val required: Boolean = true,
    val description: String = name,
)

data class StoredSkillAction(
    val type: ActionType,
    val targetTemplate: String?,
    val valueTemplate: String?,
)

data class SkillStep(
    val stepId: String,
    val entryFingerprint: String,
    val action: StoredSkillAction,
    val expectedAfterFingerprint: String?,
    val retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 1),
    val fallbackPolicy: StepFallbackPolicy = StepFallbackPolicy.REPLAN,
)

data class AppSkill(
    val skillId: String,
    val appId: String,
    val taskType: String,
    val name: String,
    val description: String,
    val parameters: List<SkillParameter>,
    val entryFingerprint: String,
    val exitFingerprint: String,
    val steps: List<SkillStep>,
    val risk: TaskRisk,
    val version: Int = 1,
    val confidence: Double = .60,
    val successCount: Long = 0,
    val failureCount: Long = 0,
    val lastValidatedAt: Long? = null,
    val status: SkillStatus = SkillStatus.ACTIVE,
) {
    val canonicalTaskKey: String
        get() = listOf(appId, taskType, parameters.map(SkillParameter::name).sorted().joinToString(","))
            .joinToString(":")
}

data class SkillQuery(
    val canonicalTaskKey: String,
    val appId: String?,
    val taskType: String,
    val screenFingerprint: String,
)

interface SkillRepository {
    fun find(query: SkillQuery): List<AppSkill>
    fun get(skillId: String): AppSkill?
    fun save(skill: AppSkill)
    fun markSuccess(skillId: String)
    fun markFailure(skillId: String)
}

class InMemorySkillRepository : SkillRepository {
    private val skills = linkedMapOf<String, AppSkill>()

    @Synchronized
    override fun find(query: SkillQuery): List<AppSkill> = skills.values.filter { skill ->
        skill.status != SkillStatus.DISABLED && skill.canonicalTaskKey == query.canonicalTaskKey &&
            skill.taskType == query.taskType &&
            (query.appId == null || skill.appId == query.appId) &&
            (skill.exitFingerprint == query.screenFingerprint ||
                skill.steps.any { it.entryFingerprint == query.screenFingerprint })
    }.sortedWith(compareByDescending<AppSkill> { it.confidence }.thenByDescending { it.version })

    @Synchronized
    override fun get(skillId: String): AppSkill? = skills[skillId]

    @Synchronized
    override fun save(skill: AppSkill) {
        skills[skill.skillId] = skill
    }

    @Synchronized
    override fun markSuccess(skillId: String) {
        val skill = skills[skillId] ?: return
        skills[skillId] = skill.copy(
            confidence = (skill.confidence + .05).coerceAtMost(1.0),
            successCount = skill.successCount + 1,
            lastValidatedAt = System.currentTimeMillis(),
            status = SkillStatus.ACTIVE,
        )
    }

    @Synchronized
    override fun markFailure(skillId: String) {
        val skill = skills[skillId] ?: return
        val failures = skill.failureCount + 1
        skills[skillId] = skill.copy(
            confidence = (skill.confidence - .15).coerceAtLeast(0.0),
            failureCount = failures,
            status = when {
                failures >= 3 -> SkillStatus.NEEDS_REPAIR
                else -> SkillStatus.SUSPECT
            },
        )
    }
}

class SkillRetriever(private val repository: SkillRepository) {
    fun retrieve(task: CanonicalTask, screen: ScreenState): List<AppSkill> = repository.find(
        SkillQuery(
            canonicalTaskKey = task.key,
            appId = task.appId,
            taskType = task.taskType,
            screenFingerprint = screen.fingerprint,
        ),
    )
}

object ParameterFiller {
    fun fill(template: String?, parameters: Map<String, TaskParameter>): String? {
        var output = template ?: return null
        PLACEHOLDER.findAll(output).toList().forEach { match ->
            val name = match.groupValues[1]
            val value = parameters[name]?.value ?: return null
            output = output.replace(match.value, value)
        }
        return output
    }

    private val PLACEHOLDER = Regex("\\$\\{([A-Za-z][A-Za-z0-9_]*)\\}")
}

class FastPathPlanner : Planner {
    override fun plan(task: CanonicalTask, screen: ScreenState, skills: List<AppSkill>): Plan? {
        val skill = skills.firstOrNull { it.status == SkillStatus.ACTIVE } ?: return null
        if (screen.fingerprint == skill.exitFingerprint) {
            return Plan(
                goal = task.taskType,
                steps = emptyList(),
                source = PlannerSource.SKILL_FAST_PATH,
                confidence = skill.confidence,
                complete = true,
            )
        }
        val storedStep = skill.steps.firstOrNull { it.entryFingerprint == screen.fingerprint }
            ?: return null
        val action = storedStep.action.toPlannedAction(task.parameters) ?: return null
        return Plan(
            goal = task.taskType,
            steps = listOf(
                PlanStep(
                    stepId = storedStep.stepId,
                    precondition = Predicate.AppIs(screen.packageName),
                    action = action,
                    postcondition = storedStep.expectedAfterFingerprint?.let {
                        Predicate.ScreenChanged(storedStep.entryFingerprint)
                    },
                    retryPolicy = storedStep.retryPolicy,
                    fallbackPolicy = storedStep.fallbackPolicy,
                ),
            ),
            source = PlannerSource.SKILL_FAST_PATH,
            confidence = skill.confidence,
        )
    }

    private fun StoredSkillAction.toPlannedAction(
        parameters: Map<String, TaskParameter>,
    ): PlannedAction? {
        val target = ParameterFiller.fill(targetTemplate, parameters)
        val value = ParameterFiller.fill(valueTemplate, parameters)
        return when (type) {
            ActionType.CLICK -> PlannedAction.Click(GroundingQuery(selector = target))
            ActionType.SET_TEXT -> PlannedAction.SetText(
                target = GroundingQuery(selector = target),
                value = value ?: return null,
            )
            ActionType.SCROLL_UP -> PlannedAction.Scroll(null, ScrollDirection.UP)
            ActionType.SCROLL_DOWN -> PlannedAction.Scroll(null, ScrollDirection.DOWN)
            ActionType.SCROLL_LEFT -> PlannedAction.Scroll(null, ScrollDirection.LEFT)
            ActionType.SCROLL_RIGHT -> PlannedAction.Scroll(null, ScrollDirection.RIGHT)
            ActionType.OPEN_APP -> PlannedAction.OpenApp(target ?: return null)
            ActionType.BACK -> PlannedAction.Back
            ActionType.HOME -> PlannedAction.Home
            else -> null
        }
    }
}

object SkillLearner {
    fun learn(
        task: CanonicalTask,
        history: List<AutonomySession.Trace>,
        fallbackEntryFingerprint: String,
        fallbackExitFingerprint: String,
    ): AppSkill? {
        val successful = history.filter { it.succeeded }
        val reusable = successful.filterNot { it.action.type in setOf(ActionType.WAIT, ActionType.FINISH) }
        if (reusable.isEmpty() || reusable.any { it.action.type !in REUSABLE_TYPES }) return null
        val steps = reusable.mapIndexedNotNull { index, trace ->
            val action = sanitize(trace.action, task.parameters) ?: return@mapIndexedNotNull null
            SkillStep(
                stepId = "step-${index + 1}",
                entryFingerprint = trace.beforeTemplateFingerprint ?: fallbackEntryFingerprint,
                action = action,
                expectedAfterFingerprint = trace.afterTemplateFingerprint,
            )
        }
        if (steps.size != reusable.size) {
            return null
        }
        val entry = steps.first().entryFingerprint
        val exit = steps.last().expectedAfterFingerprint ?: fallbackExitFingerprint
        val appId = task.appId ?: successful.first().beforePackage
        val id = "${appId}.${task.taskType}." + sha256(task.key).take(12)
        return AppSkill(
            skillId = id,
            appId = appId,
            taskType = task.taskType,
            name = task.taskType,
            description = "검증된 성공 경로",
            parameters = task.parameters.values.map { parameter ->
                SkillParameter(name = parameter.name, required = parameter.required)
            },
            entryFingerprint = entry,
            exitFingerprint = exit,
            steps = steps,
            risk = task.risk,
        )
    }

    private fun sanitize(
        action: com.hwanghj09.sonju.agent.AgentAction,
        parameters: Map<String, TaskParameter>,
    ): StoredSkillAction? {
        if (action.type !in REUSABLE_TYPES) return null
        val target = parameterize(action.target, parameters)
        val value = when (action.type) {
            ActionType.SET_TEXT -> parameterizeRequired(action.value, parameters) ?: return null
            else -> null
        }
        return StoredSkillAction(action.type, target, value)
    }

    private fun parameterize(
        raw: String?,
        parameters: Map<String, TaskParameter>,
    ): String? {
        var output = raw ?: return null
        parameters.values.forEach { parameter ->
            val value = parameter.value?.takeIf(String::isNotBlank) ?: return@forEach
            output = output.replace(value, "${'$'}{${parameter.name}}", ignoreCase = true)
        }
        return output.take(160)
    }

    private fun parameterizeRequired(
        raw: String?,
        parameters: Map<String, TaskParameter>,
    ): String? {
        val output = parameterize(raw, parameters) ?: return null
        return output.takeIf { PLACEHOLDER.containsMatchIn(it) }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private val PLACEHOLDER = Regex("\\$\\{[A-Za-z][A-Za-z0-9_]*\\}")
    private val REUSABLE_TYPES = setOf(
        ActionType.CLICK,
        ActionType.SET_TEXT,
        ActionType.SCROLL_UP,
        ActionType.SCROLL_DOWN,
        ActionType.SCROLL_LEFT,
        ActionType.SCROLL_RIGHT,
        ActionType.OPEN_APP,
        ActionType.BACK,
        ActionType.HOME,
    )
}
