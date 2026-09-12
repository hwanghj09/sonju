package com.hwanghj09.sonju.skill

import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.AutonomySession
import com.hwanghj09.sonju.agent.GoalCheck
import com.hwanghj09.sonju.agent.normalizeGoalText
import com.hwanghj09.sonju.agent.UiSnapshot
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
    val xRatio: Double? = null,
    val yRatio: Double? = null,
    val visualFrameHash: String? = null,
    val descriptionTemplate: String = "",
)

data class SkillStep(
    val stepId: String,
    val entryFingerprint: String,
    val action: StoredSkillAction,
    val expectedAfterFingerprint: String?,
    val retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 1),
    val fallbackPolicy: StepFallbackPolicy = StepFallbackPolicy.REPLAN,
)

/** Preserve completion evidence without persisting the observed result or personal text. */
data class StoredGoalCheck(val selector: String, val textHash: String?, val checked: Boolean?) {
    fun resolve(snapshot: UiSnapshot): GoalCheck? {
        val check = GoalCheck(selector, checked = checked)
        val node = check.resolveNode(snapshot) ?: return null
        if (!check.matches(snapshot)) return null
        val text = if (textHash == null) null else
            listOfNotNull(node.text, node.contentDescription, node.stateDescription)
                .firstOrNull { digest(it) == textHash || digest(normalizeGoalText(it)) == textHash } ?: return null
        return check.copy(text = text)
    }

    companion object {
        fun capture(check: GoalCheck, snapshot: UiSnapshot): StoredGoalCheck? {
            if (!check.matches(snapshot)) return null
            val node = check.resolveNode(snapshot) ?: return null
            val id = node.viewId?.takeIf { id ->
                RESOURCE_ID.matches(id) && snapshot.elements.count { it.visible && it.viewId == id } == 1
            }
            val text = check.text ?: listOfNotNull(node.text, node.contentDescription, node.stateDescription)
                .firstOrNull(String::isNotBlank)
            if (text == null && check.checked == null) return null
            return StoredGoalCheck(id?.let { "id=$it" } ?: "path=${node.path}",
                text?.let { digest(normalizeGoalText(it)) }, check.checked)
        }

        private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private val RESOURCE_ID = Regex("[A-Za-z0-9_.]+:id/[A-Za-z0-9_]+")
    }
}

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
    val requestKey: String = "",
    val exitVisualFrameHash: String? = null,
    val goalChecks: List<StoredGoalCheck> = emptyList(),
    val exitPackage: String? = null,
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
    val requestKey: String = "",
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
        skill.status != SkillStatus.DISABLED && skill.requestKey == query.requestKey &&
            skill.canonicalTaskKey == query.canonicalTaskKey &&
            skill.taskType == query.taskType &&
            (query.appId == null || skill.appId == query.appId)
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
            requestKey = task.requestKey,
        ),
    )
}

object ParameterFiller {
    fun fill(template: String?, parameters: Map<String, TaskParameter>): String? {
        var output = template ?: return null
        PLACEHOLDER.findAll(output).toList().forEach { match ->
            val name = match.groupValues[1]
            val span = REQUEST_SPAN.matchEntire(name)
            val value = if (span != null) {
                val request = parameters["request"]?.value ?: return null
                val start = span.groupValues[1].toIntOrNull() ?: return null
                val end = span.groupValues[2].toIntOrNull() ?: return null
                if (start !in 0..request.length || end !in start..request.length) return null
                request.substring(start, end)
            } else parameters[name]?.value ?: return null
            output = output.replace(match.value, value)
        }
        return output
    }

    private val PLACEHOLDER = Regex("\\$\\{([A-Za-z][A-Za-z0-9_]*)\\}")
    private val REQUEST_SPAN = Regex("request_([0-9]+)_([0-9]+)")
}

class FastPathPlanner : Planner {
    override fun plan(task: CanonicalTask, screen: ScreenState, skills: List<AppSkill>): Plan? {
        val skill = skills.firstOrNull { it.status == SkillStatus.ACTIVE } ?: return null
        val index = skill.steps.indexOfFirst { it.entryFingerprint == screen.fingerprint }
        return planStep(task, screen, skill, if (index < 0) skill.steps.size else index)
    }

    fun planStep(task: CanonicalTask, screen: ScreenState, skill: AppSkill, index: Int,
                 completionEvidenceMatches: Boolean = false): Plan? {
        if (skill.status == SkillStatus.DISABLED || skill.requestKey != task.requestKey) return null
        if (index == skill.steps.size && (screen.fingerprint == skill.exitFingerprint || completionEvidenceMatches)) {
            return Plan(
                goal = task.taskType,
                steps = emptyList(),
                source = PlannerSource.SKILL_FAST_PATH,
                confidence = skill.confidence,
                complete = true,
            )
        }
        if (skill.status != SkillStatus.ACTIVE) return null
        val storedStep = skill.steps.getOrNull(index)?.takeIf { it.entryFingerprint == screen.fingerprint }
            ?: return null
        val parameters = task.parameters + listOfNotNull(task.requestText?.let {
            "request" to TaskParameter("request", it)
        }).toMap()
        val action = storedStep.action.toPlannedAction(parameters) ?: return null
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
        if (targetTemplate != null && target == null || valueTemplate != null && value == null) return null
        return when (type) {
            ActionType.CLICK -> PlannedAction.Click(GroundingQuery(selector = target))
            ActionType.SET_TEXT -> PlannedAction.SetText(
                target = GroundingQuery(selector = target),
                value = value ?: return null,
            )
            ActionType.SUBMIT_TEXT -> PlannedAction.SubmitText(GroundingQuery(selector = target), value ?: return null)
            ActionType.SCROLL_UP -> PlannedAction.Scroll(null, ScrollDirection.UP)
            ActionType.SCROLL_DOWN -> PlannedAction.Scroll(null, ScrollDirection.DOWN)
            ActionType.SCROLL_LEFT -> PlannedAction.Scroll(null, ScrollDirection.LEFT)
            ActionType.SCROLL_RIGHT -> PlannedAction.Scroll(null, ScrollDirection.RIGHT)
            ActionType.OPEN_APP -> PlannedAction.OpenApp(target ?: return null)
            ActionType.OPEN_URL -> PlannedAction.OpenUrl(target ?: return null)
            ActionType.WAIT_FOR_USER -> PlannedAction.UserCheckpoint(target ?: return null)
            ActionType.BACK -> PlannedAction.Back
            ActionType.HOME -> PlannedAction.Home
            ActionType.CLICK_COORDINATE -> {
                if (visualFrameHash == null || xRatio?.let { it.isFinite() && it in 0.0..1.0 } != true ||
                    yRatio?.let { it.isFinite() && it in 0.0..1.0 } != true) return null
                val description = ParameterFiller.fill(descriptionTemplate, parameters)?.takeIf(String::isNotBlank)
                    ?: return null
                PlannedAction.VisualClick(xRatio, yRatio, description)
            }
            ActionType.WAIT, ActionType.FINISH -> null
            else -> PlannedAction.SystemAction(type)
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
        val successful = shortestTrace(history)
        val reusable = successful.filterNot { it.action.type in setOf(ActionType.WAIT, ActionType.FINISH) }
        if (reusable.isEmpty() || reusable.any { it.action.type !in REUSABLE_TYPES }) return null
        val steps = reusable.mapIndexedNotNull { index, trace ->
            val action = sanitize(trace.action, task.parameters, task.requestText,
                trace.visualFrameHash) ?: return@mapIndexedNotNull null
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
        // Never splice across an unobserved/failed transition or silently omit an essential input.
        if (steps.zipWithNext().any { (a, b) -> a.expectedAfterFingerprint != b.entryFingerprint } ||
            steps.last().expectedAfterFingerprint != fallbackExitFingerprint) return null
        val entry = steps.first().entryFingerprint
        val exit = steps.last().expectedAfterFingerprint ?: fallbackExitFingerprint
        val appId = task.appId ?: successful.first().beforePackage
        val id = "${appId}.${task.taskType}." + sha256("${task.key}:${task.requestKey}:$entry").take(20)
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
            requestKey = task.requestKey,
            successCount = 1,
            lastValidatedAt = System.currentTimeMillis(),
        )
    }

    /** Shortest path through observed transitions; raw fingerprints keep different input values apart. */
    fun shortestTrace(history: List<AutonomySession.Trace>): List<AutonomySession.Trace> {
        val route = mutableListOf<AutonomySession.Trace>()
        history.forEach { trace ->
            // A click rejected before dispatch is an observation delay, including ongoing loading.
            // Otherwise fold only focus drift on the same semantic screen; never failed text input.
            if (!trace.succeeded && trace.action.type == ActionType.CLICK &&
                trace.beforeTemplateFingerprint != null &&
                (trace.notDispatched || trace.beforeTemplateFingerprint == trace.afterTemplateFingerprint) &&
                route.lastOrNull()?.afterFingerprint == trace.beforeFingerprint) {
                route[route.lastIndex] = route.last().copy(afterFingerprint = trace.afterFingerprint,
                    afterPackage = trace.afterPackage, afterTemplateFingerprint = trace.afterTemplateFingerprint)
                return@forEach
            }
            if ((!trace.succeeded && !(trace.postconditionMismatch && trace.screenChanged == true)) ||
                trace.afterTemplateFingerprint == null) return@forEach
            if (trace.action.type in setOf(ActionType.WAIT, ActionType.FINISH)) {
                if (route.lastOrNull()?.afterFingerprint == trace.beforeFingerprint) {
                    route[route.lastIndex] = route.last().copy(afterFingerprint = trace.afterFingerprint,
                        afterPackage = trace.afterPackage, afterTemplateFingerprint = trace.afterTemplateFingerprint)
                }
                return@forEach
            }
            route += trace
        }
        // A human authentication boundary cannot be shortened away by equal surrounding screens.
        if (route.isEmpty() || route.any { it.visualFrameHash != null || it.action.type == ActionType.WAIT_FOR_USER }) return route
        fun before(trace: AutonomySession.Trace) = "${trace.beforePackage}:${trace.beforeFingerprint}"
        fun after(trace: AutonomySession.Trace) = "${trace.afterPackage}:${trace.afterFingerprint}"
        val destination = after(route.last())
        val queue = ArrayDeque<Pair<String, List<AutonomySession.Trace>>>()
        queue.add(before(route.first()) to emptyList())
        val visited = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val (state, path) = queue.removeFirst()
            if (state == destination) return path
            if (!visited.add(state)) continue
            route.filter { before(it) == state }.forEach { queue.add(after(it) to path + it) }
        }
        return emptyList()
    }

    fun repair(existing: AppSkill, stepIndex: Int, suffix: AppSkill): AppSkill? {
        if (stepIndex !in 0..existing.steps.size || existing.requestKey != suffix.requestKey) return null
        val prefix = existing.steps.take(stepIndex).toMutableList()
        if (prefix.isNotEmpty()) prefix[prefix.lastIndex] = prefix.last().copy(
            expectedAfterFingerprint = suffix.entryFingerprint)
        val steps = prefix + suffix.steps.mapIndexed { index, step ->
            step.copy(stepId = "step-${stepIndex + index + 1}")
        }
        return existing.copy(steps = steps, entryFingerprint = steps.first().entryFingerprint,
            exitFingerprint = suffix.exitFingerprint,
            exitVisualFrameHash = suffix.exitVisualFrameHash,
            goalChecks = suffix.goalChecks,
            exitPackage = suffix.exitPackage,
            version = existing.version + 1, status = SkillStatus.ACTIVE,
            confidence = maxOf(.6, existing.confidence), successCount = existing.successCount + 1,
            lastValidatedAt = System.currentTimeMillis())
    }

    private fun sanitize(
        action: com.hwanghj09.sonju.agent.AgentAction,
        parameters: Map<String, TaskParameter>,
        requestText: String?,
        visualFrameHash: String?,
    ): StoredSkillAction? {
        if (action.type !in REUSABLE_TYPES) return null
        val target = parameterize(action.target, parameters)
        // Grounded child-index paths are structural selectors, not dotted phone numbers.
        if (target != null && !NODE_PATH.matches(target) &&
            com.hwanghj09.sonju.logging.RedactionPolicy.redact(target) != target) return null
        val value = when (action.type) {
            ActionType.SET_TEXT, ActionType.SUBMIT_TEXT ->
                parameterizeRequired(action.value, parameters, requestText) ?: return null
            else -> null
        }
        if (action.type == ActionType.CLICK_COORDINATE && visualFrameHash == null) return null
        val description = parameterize(action.description, parameters).orEmpty()
        if (action.type == ActionType.CLICK_COORDINATE &&
            com.hwanghj09.sonju.logging.RedactionPolicy.redact(description) != description) return null
        return StoredSkillAction(action.type, target, value, action.xRatio, action.yRatio,
            visualFrameHash.takeIf { action.type == ActionType.CLICK_COORDINATE },
            description.takeIf { action.type == ActionType.CLICK_COORDINATE }.orEmpty())
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
        requestText: String?,
    ): String? {
        if (raw.isNullOrBlank()) return null
        parameters.values.firstOrNull { it.value == raw }?.let { return "${'$'}{${it.name}}" }
        val start = requestText?.indexOf(raw)?.takeIf { it >= 0 } ?: return null
        return "${'$'}{request_${start}_${start + raw.length}}"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private val PLACEHOLDER = Regex("\\$\\{[A-Za-z][A-Za-z0-9_]*\\}")
    private val NODE_PATH = Regex("0(?:\\.[0-9]{1,3}){1,64}")
    // Timer requests already use an exact on-device rule, never a replayable side-effect trace.
    private val REUSABLE_TYPES = ActionType.entries.toSet() -
        setOf(ActionType.WAIT, ActionType.FINISH, ActionType.START_TIMER)
}
