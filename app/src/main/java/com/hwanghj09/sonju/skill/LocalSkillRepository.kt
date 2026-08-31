package com.hwanghj09.sonju.skill

import android.content.Context
import androidx.core.content.edit
import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.execution.RetryPolicy
import com.hwanghj09.sonju.execution.StepFallbackPolicy
import com.hwanghj09.sonju.task.TaskRisk
import org.json.JSONArray
import org.json.JSONObject

/** Local-only MVP store. The repository interface keeps Room/cloud migration outside the engine. */
class LocalSkillRepository(context: Context) : SkillRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun find(query: SkillQuery): List<AppSkill> = all().filter { skill ->
        skill.status != SkillStatus.DISABLED && skill.canonicalTaskKey == query.canonicalTaskKey &&
            skill.taskType == query.taskType &&
            (query.appId == null || skill.appId == query.appId) &&
            (skill.exitFingerprint == query.screenFingerprint ||
                skill.steps.any { it.entryFingerprint == query.screenFingerprint })
    }.sortedWith(compareByDescending<AppSkill> { it.confidence }.thenByDescending { it.version })

    override fun get(skillId: String): AppSkill? = preferences.getString(key(skillId), null)?.let(::decode)

    override fun save(skill: AppSkill) {
        preferences.edit { putString(key(skill.skillId), encode(skill)) }
        trim()
    }

    override fun markSuccess(skillId: String) {
        val skill = get(skillId) ?: return
        save(
            skill.copy(
                confidence = (skill.confidence + .05).coerceAtMost(1.0),
                successCount = skill.successCount + 1,
                lastValidatedAt = System.currentTimeMillis(),
                status = SkillStatus.ACTIVE,
            ),
        )
    }

    override fun markFailure(skillId: String) {
        val skill = get(skillId) ?: return
        val failures = skill.failureCount + 1
        save(
            skill.copy(
                confidence = (skill.confidence - .15).coerceAtLeast(0.0),
                failureCount = failures,
                status = if (failures >= 3) SkillStatus.NEEDS_REPAIR else SkillStatus.SUSPECT,
            ),
        )
    }

    private fun all(): List<AppSkill> = preferences.all.asSequence()
        .filter { (name, _) -> name.startsWith(KEY_PREFIX) }
        .mapNotNull { (_, raw) -> (raw as? String)?.let(::decode) }
        .toList()

    private fun trim() {
        val sorted = all().sortedByDescending { it.lastValidatedAt ?: 0L }
        if (sorted.size <= MAX_SKILLS) return
        preferences.edit {
            sorted.drop(MAX_SKILLS).forEach { remove(key(it.skillId)) }
        }
    }

    private fun encode(skill: AppSkill): String = JSONObject()
        .put("schema", SCHEMA_VERSION)
        .put("skill_id", skill.skillId)
        .put("app_id", skill.appId)
        .put("task_type", skill.taskType)
        .put("name", skill.name)
        .put("description", skill.description)
        .put("entry", skill.entryFingerprint)
        .put("exit", skill.exitFingerprint)
        .put("risk", skill.risk.name)
        .put("version", skill.version)
        .put("confidence", skill.confidence)
        .put("success_count", skill.successCount)
        .put("failure_count", skill.failureCount)
        .put("last_validated", skill.lastValidatedAt ?: JSONObject.NULL)
        .put("status", skill.status.name)
        .put(
            "parameters",
            JSONArray().apply {
                skill.parameters.forEach { parameter ->
                    put(
                        JSONObject()
                            .put("name", parameter.name)
                            .put("type", parameter.type.name)
                            .put("required", parameter.required)
                            .put("description", parameter.description),
                    )
                }
            },
        )
        .put(
            "steps",
            JSONArray().apply {
                skill.steps.forEach { step ->
                    put(
                        JSONObject()
                            .put("step_id", step.stepId)
                            .put("entry", step.entryFingerprint)
                            .put("after", step.expectedAfterFingerprint ?: JSONObject.NULL)
                            .put("type", step.action.type.name)
                            .put("target", step.action.targetTemplate ?: JSONObject.NULL)
                            .put("value", step.action.valueTemplate ?: JSONObject.NULL)
                            .put("retry", step.retryPolicy.maxAttempts)
                            .put("fallback", step.fallbackPolicy.name),
                    )
                }
            },
        )
        .toString()

    private fun decode(raw: String): AppSkill? = runCatching {
        val json = JSONObject(raw)
        if (json.getInt("schema") != SCHEMA_VERSION) return null
        val parametersJson = json.getJSONArray("parameters")
        val parameters = buildList {
            for (index in 0 until parametersJson.length()) {
                val item = parametersJson.getJSONObject(index)
                add(
                    SkillParameter(
                        name = item.getString("name"),
                        type = ParameterType.valueOf(item.getString("type")),
                        required = item.getBoolean("required"),
                        description = item.getString("description"),
                    ),
                )
            }
        }
        val stepsJson = json.getJSONArray("steps")
        val steps = buildList {
            for (index in 0 until stepsJson.length()) {
                val item = stepsJson.getJSONObject(index)
                add(
                    SkillStep(
                        stepId = item.getString("step_id"),
                        entryFingerprint = item.getString("entry"),
                        action = StoredSkillAction(
                            type = ActionType.valueOf(item.getString("type")),
                            targetTemplate = item.nullableString("target"),
                            valueTemplate = item.nullableString("value"),
                        ),
                        expectedAfterFingerprint = item.nullableString("after"),
                        retryPolicy = RetryPolicy(item.getInt("retry")),
                        fallbackPolicy = StepFallbackPolicy.valueOf(item.getString("fallback")),
                    ),
                )
            }
        }
        AppSkill(
            skillId = json.getString("skill_id"),
            appId = json.getString("app_id"),
            taskType = json.getString("task_type"),
            name = json.getString("name"),
            description = json.getString("description"),
            parameters = parameters,
            entryFingerprint = json.getString("entry"),
            exitFingerprint = json.getString("exit"),
            steps = steps,
            risk = TaskRisk.valueOf(json.getString("risk")),
            version = json.getInt("version"),
            confidence = json.getDouble("confidence").coerceIn(0.0, 1.0),
            successCount = json.getLong("success_count"),
            failureCount = json.getLong("failure_count"),
            lastValidatedAt = json.nullableLong("last_validated"),
            status = SkillStatus.valueOf(json.getString("status")),
        )
    }.getOrNull()

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else getString(name).takeIf(String::isNotBlank)

    private fun JSONObject.nullableLong(name: String): Long? =
        if (isNull(name)) null else getLong(name)

    private fun key(skillId: String): String = KEY_PREFIX + skillId

    companion object {
        private const val PREFERENCES = "sonju_app_skills_v1"
        private const val KEY_PREFIX = "skill_"
        private const val SCHEMA_VERSION = 1
        private const val MAX_SKILLS = 100
    }
}
