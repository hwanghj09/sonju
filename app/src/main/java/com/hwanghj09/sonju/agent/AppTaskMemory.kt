package com.hwanghj09.sonju.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Small, local-only cache of successful single-screen actions.
 *
 * It never stores screenshots or the raw voice command. A cached action is only proposed when the
 * normalized command hash, package and semantic screen hash all match, then the normal safety
 * policy and live-node verification still run before execution.
 */
class AppTaskMemory(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun recall(command: String, snapshot: UiSnapshot): AgentPlan? {
        val key = AppTaskMemoryPolicy.cacheKey(command, snapshot)
        val raw = preferences.getString(key, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val savedAt = json.getLong("saved_at")
            if (System.currentTimeMillis() - savedAt !in 0..MAX_AGE_MILLIS) return null
            val actionsJson = json.getJSONArray("actions")
            val actions = buildList {
                for (index in 0 until actionsJson.length()) {
                    val item = actionsJson.getJSONObject(index)
                    add(
                        AgentAction(
                            type = ActionType.valueOf(item.getString("type")),
                            description = "전에 성공한 안전한 화면 동작을 다시 확인합니다.",
                            target = item.optNullableString("target")?.take(160),
                            value = item.optNullableString("value")?.take(500),
                            waitMillis = item.optLong("wait_millis", 0).coerceIn(0, 2_000),
                        ),
                    )
                }
            }
            AgentPlan(
                goal = "전에 성공한 작업 재사용",
                summary = "같은 화면에서 전에 성공한 동작을 다시 실행합니다.",
                modelRisk = RiskLevel.valueOf(json.getString("risk")),
                confidence = 1.0,
                actions = actions,
                source = PlanSource.GEMINI_STRUCTURE,
            ).takeIf { AppTaskMemoryPolicy.isReusable(snapshot, it) }
        }.getOrNull()
    }

    fun remember(command: String, snapshot: UiSnapshot, plan: AgentPlan) {
        if (plan.source != PlanSource.GEMINI_STRUCTURE ||
            !AppTaskMemoryPolicy.isReusable(snapshot, plan)
        ) return
        val actions = JSONArray().apply {
            plan.actions.forEach { action ->
                put(
                    JSONObject()
                        .put("type", action.type.name)
                        .put("target", action.target ?: JSONObject.NULL)
                        .put("value", action.value ?: JSONObject.NULL)
                        .put("wait_millis", action.waitMillis),
                )
            }
        }
        val value = JSONObject()
            .put("saved_at", System.currentTimeMillis())
            .put("risk", plan.modelRisk.name)
            .put("actions", actions)
            .toString()
        preferences.edit()
            .putString(AppTaskMemoryPolicy.cacheKey(command, snapshot), value)
            .apply()
        trimOldEntries()
    }

    fun forget(command: String, snapshot: UiSnapshot) {
        preferences.edit()
            .remove(AppTaskMemoryPolicy.cacheKey(command, snapshot))
            .apply()
    }

    private fun trimOldEntries() {
        val entries = preferences.all.mapNotNull { (key, value) ->
            val savedAt = runCatching { JSONObject(value as String).getLong("saved_at") }.getOrNull()
            savedAt?.let { key to it }
        }.sortedByDescending { it.second }
        if (entries.size <= MAX_ENTRIES) return
        preferences.edit().apply {
            entries.drop(MAX_ENTRIES).forEach { remove(it.first) }
        }.apply()
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    companion object {
        private const val PREFERENCES = "sonju_app_task_memory_v1"
        private const val MAX_ENTRIES = 40
        private const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1_000
    }
}

internal object AppTaskMemoryPolicy {
    private val reusableActions = setOf(
        ActionType.CLICK,
        ActionType.SCROLL_DOWN,
        ActionType.SCROLL_UP,
        ActionType.BACK,
        ActionType.WAIT,
        ActionType.FINISH,
    )

    fun isReusable(snapshot: UiSnapshot, plan: AgentPlan): Boolean {
        val effectiveActions = plan.actions.filterNot { it.type == ActionType.FINISH }
        return snapshot.packageName != "unknown" &&
            !snapshot.treeTruncated &&
            snapshot.elements.none { it.sensitive } &&
            plan.modelRisk in setOf(RiskLevel.LOW, RiskLevel.MEDIUM) &&
            !plan.continueAfterAction &&
            effectiveActions.size == 1 &&
            plan.actions.all { it.type in reusableActions } &&
            plan.actions.none { it.type == ActionType.SET_TEXT }
    }

    fun cacheKey(command: String, snapshot: UiSnapshot): String {
        val normalizedCommand = Normalizer.normalize(command, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        val semanticScreen = buildString {
            append(snapshot.packageName).append('|').append(snapshot.windowTitle.orEmpty()).append('\n')
            snapshot.elements.asSequence()
                .filter { it.visible && !it.sensitive }
                .map { element ->
                    listOf(
                        element.viewId.orEmpty(),
                        element.className.substringAfterLast('.'),
                        element.text.orEmpty(),
                        element.contentDescription.orEmpty(),
                        element.stateDescription.orEmpty(),
                        element.clickable,
                        element.editable,
                        element.scrollable,
                        element.checkable,
                    ).joinToString("|")
                }
                .sorted()
                .forEach { append(it).append('\n') }
        }
        return "entry_" + sha256("$normalizedCommand\n$semanticScreen")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
