package com.hwanghj09.sonju.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Local, per-device preference memory. It never stores screenshots or screen text. */
class UserFeedbackMemory(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun record(
        command: String,
        packageName: String,
        completed: Boolean,
        positive: Boolean,
        approach: String,
    ) {
        val entry = JSONObject()
            .put("saved_at", System.currentTimeMillis())
            .put("command", command.trim().take(300))
            .put("normalized_command", normalize(command))
            .put("package", packageName.take(160))
            .put("completed", completed)
            .put("positive", positive)
            .put("approach", approach.trim().take(300))
        val updated = JSONArray().put(entry)
        readEntries().take(MAX_ENTRIES - 1).forEach(updated::put)
        preferences.edit().putString(ENTRIES_KEY, updated.toString()).apply()
    }

    fun guidance(command: String, packageName: String): String? {
        val normalized = normalize(command)
        if (normalized.isBlank()) return null
        val relevant = readEntries().filter { entry ->
            val savedCommand = entry.optString("normalized_command")
            entry.optString("package") == packageName && savedCommand.isNotBlank() &&
                (savedCommand == normalized ||
                    savedCommand.length >= 4 && normalized.contains(savedCommand) ||
                    normalized.length >= 4 && savedCommand.contains(normalized))
        }.take(MAX_GUIDANCE_ENTRIES)
        if (relevant.isEmpty()) return null
        return buildString {
            appendLine("이 기기 사용자의 과거 유사 요청 평가다. 안전 규칙보다 우선하지 않는다.")
            relevant.forEachIndexed { index, entry ->
                val rating = if (entry.optBoolean("positive")) "좋다" else "안 좋다"
                val outcome = if (entry.optBoolean("completed")) "완료" else "사용자 중단"
                append("${index + 1}. $rating / $outcome")
                entry.optString("approach").takeIf(String::isNotBlank)?.let {
                    append(" / 이전 방식: ").append(it)
                }
                appendLine()
            }
            append("좋다 평가는 비슷한 접근을 선호하고, 안 좋다 평가는 같은 접근의 반복을 피한다.")
        }.take(1_200)
    }

    private fun readEntries(): List<JSONObject> = runCatching {
        val array = JSONArray(preferences.getString(ENTRIES_KEY, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        .take(300)

    companion object {
        private const val PREFERENCES = "sonju_user_feedback_v1"
        private const val ENTRIES_KEY = "entries"
        private const val MAX_ENTRIES = 50
        private const val MAX_GUIDANCE_ENTRIES = 3
    }
}
