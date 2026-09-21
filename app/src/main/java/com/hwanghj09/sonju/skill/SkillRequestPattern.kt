package com.hwanghj09.sonju.skill

import com.hwanghj09.sonju.agent.UiSnapshot
import com.hwanghj09.sonju.agent.normalizeGoalText
import com.hwanghj09.sonju.task.TaskParameter
import java.security.MessageDigest
import java.text.Normalizer

/** Literal request fragments are hashed; only values from the *current* request are bound. */
data class RequestPart(val hash: String? = null, val length: Int = 0, val parameter: String? = null)

data class SkillRequestPattern(val parts: List<RequestPart>) {
    /** Result evidence has known values, so match every literal and slot without guessing. */
    fun matchesText(text: String, parameters: Map<String, TaskParameter>): Boolean {
        val normalized = normalizeGoalText(text)
        var offset = 0
        for (part in parts) {
            val value = part.parameter?.let { name ->
                parameters[name]?.value?.let(::normalizeGoalText) ?: return false
            }
            val end = offset + (value?.length ?: part.length)
            if (end <= offset || end > normalized.length) return false
            val actual = normalized.substring(offset, end)
            if (value != null && actual != value || value == null && digest(actual) != part.hash) return false
            offset = end
        }
        return parts.isNotEmpty() && offset == normalized.length
    }

    // ponytail: bounded literal/slot matching; unseen or ambiguous wording uses the existing model.
    fun bind(request: String): Map<String, TaskParameter>? {
        val text = normalizeRequest(request)
        if (text.length !in 1..MAX_REQUEST || parts.isEmpty() || parts.size > 33) return null
        val matches = mutableListOf<Map<String, TaskParameter>>()
        var attempts = 0
        fun visit(index: Int, offset: Int, values: Map<String, TaskParameter>) {
            if (++attempts > 4096 || matches.size > 1) return
            if (index == parts.size) {
                if (offset == text.length) matches += values
                return
            }
            val part = parts[index]
            val name = part.parameter
            if (name == null) {
                val end = offset + part.length
                if (part.length > 0 && end <= text.length && digest(text.substring(offset, end)) == part.hash) {
                    val previousValue = parts.getOrNull(index - 1)?.parameter?.let { values[it]?.value }
                    val literal = text.substring(offset, end)
                    // "일정이라고" must not bind "일정이" to a learned "...라고" slot.
                    // Unseen particle boundaries need model binding, then get their own pattern.
                    if (previousValue?.endsWith("이") == true && literal.startsWith("라고") ||
                        previousValue?.endsWith("으") == true && literal.startsWith("로")) return
                    visit(index + 1, end, values)
                }
            } else {
                val known = values[name]?.value
                if (known != null) {
                    if (text.startsWith(known, offset)) visit(index + 1, offset + known.length, values)
                } else {
                    for (end in offset + 1..text.length) {
                        val value = text.substring(offset, end)
                        if (value == value.trim() && !value.contains("${'$'}{")) {
                            visit(index + 1, end, values + (name to TaskParameter(name, value)))
                        }
                        if (attempts > 4096 || matches.size > 1) break
                    }
                }
            }
        }
        visit(0, 0, emptyMap())
        // Ambiguous segmentation or a search-budget overflow belongs to the planner.
        return matches.singleOrNull()?.takeIf { attempts <= 4096 }
    }

    companion object {
        fun capture(request: String, parameters: Map<String, TaskParameter>): SkillRequestPattern? =
            captureNormalized(normalizeRequest(request), parameters)?.takeIf {
                it.bind(request)?.mapValues { (_, value) -> value.value } == parameters.mapValues { (_, value) -> value.value }
            }

        fun captureText(text: String, parameters: Map<String, TaskParameter>): SkillRequestPattern? {
            val normalized = normalizeGoalText(text)
            val values = parameters.mapValues { (_, parameter) -> parameter.copy(
                value = parameter.value?.let(::normalizeGoalText)) }
                .filterValues { parameter ->
                    val value = parameter.value?.takeIf(String::isNotBlank) ?: return@filterValues false
                    val occurrences = Regex(Regex.escape(value)).findAll(normalized).toList()
                    // A query "1" must not turn a price "1000" into reusable evidence.
                    occurrences.isNotEmpty() && occurrences.all { match ->
                        listOf(match.range.first - 1, match.range.last + 1).all { index ->
                            normalized.getOrNull(index)?.let { it.isLetterOrDigit() || it == '_' } != true
                        }
                    }
                }
            if (values.isEmpty()) return null
            return captureNormalized(normalized, values)?.takeIf { it.matchesText(normalized, values) }
        }

        private fun captureNormalized(text: String, parameters: Map<String, TaskParameter>): SkillRequestPattern? {
            if (text.length !in 1..MAX_REQUEST) return null
            val values = parameters.values.filter { !it.value.isNullOrBlank() }
                .sortedByDescending { it.value!!.length }
            // Derived values (dates, arithmetic, rewritten queries) require model binding.
            if (values.any { !text.contains(it.value!!) } || values.map { it.value }.distinct().size != values.size) return null
            val parts = mutableListOf<RequestPart>()
            var offset = 0
            while (offset < text.length) {
                val next = values.mapNotNull { parameter ->
                    text.indexOf(parameter.value!!, offset).takeIf { it >= 0 }?.let { it to parameter }
                }.minByOrNull { it.first }
                val end = next?.first ?: text.length
                if (end > offset) parts += RequestPart(digest(text.substring(offset, end)), end - offset)
                if (next == null) break
                parts += RequestPart(parameter = next.second.name)
                offset = end + next.second.value!!.length
            }
            if (parts.none { it.hash != null } || parts.zipWithNext().any { (a, b) ->
                    a.parameter != null && b.parameter != null }) return null
            return SkillRequestPattern(parts).takeIf { parts.size <= 33 }
        }

        fun normalizeRequest(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ").trim()
            .replace(Regex("\\s*주(?:세요|실래요|시겠어요)[.!?]*$"), "줘")
            .replace(Regex("\\s+줘[.!?]*$"), "줘")

        fun parameterKey(parameters: Map<String, TaskParameter>): String = digest(parameters.toSortedMap()
            .entries.joinToString("|") { "${it.key.length}:${it.key}:${it.value.value?.length}:${it.value.value}" })

        /** Input contents such as '일정' or '삭제' are data, not the surrounding task's intent. */
        fun intent(request: String, parameters: Map<String, TaskParameter>): com.hwanghj09.sonju.task.UserIntent {
            var shape = normalizeRequest(request)
            parameters.values.sortedByDescending { it.value?.length ?: 0 }.forEach { parameter ->
                parameter.value?.takeIf(String::isNotBlank)?.let { shape = shape.replace(it, "값") }
            }
            return com.hwanghj09.sonju.task.DeterministicTaskParser.parse(shape)
        }

        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private const val MAX_REQUEST = 1_000
    }
}

/** These fields are observation-only, and are never serialized with a learned skill. */
fun UiSnapshot.skillFingerprint(parameters: Map<String, TaskParameter>): String {
    fun mask(value: String?): String? {
        var result = value ?: return null
        parameters.values.sortedByDescending { it.value?.length ?: 0 }.forEach { parameter ->
            parameter.value?.takeIf(String::isNotBlank)?.let {
                result = result.replace(it, "<${parameter.name}>")
            }
        }
        return result
    }
    return copy(windowTitle = mask(windowTitle), elements = elements.map { node ->
        node.copy(text = if (node.editable) null else mask(node.text),
            contentDescription = mask(node.contentDescription), stateDescription = mask(node.stateDescription))
    }).semanticTemplateFingerprint()
}

data class SkillReuseSuggestion(val skillId: String, val parameters: Map<String, String>)
