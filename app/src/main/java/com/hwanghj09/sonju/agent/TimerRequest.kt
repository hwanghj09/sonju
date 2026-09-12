package com.hwanghj09.sonju.agent

import java.text.Normalizer

/** A full spoken request, bound again by the verifier before Android's timer intent is sent. */
data class TimerRequest(val seconds: Int, val label: String?) {
    fun action() = AgentAction(ActionType.START_TIMER, buildString {
        label?.let { append('‘').append(it).append("’ ") }
        if (seconds % 60 == 0) append("${seconds / 60}분") else append("${seconds}초")
        append(" 타이머를 시작합니다.")
    }, target = label, value = seconds.toString())

    fun matches(action: AgentAction): Boolean = action.type == ActionType.START_TIMER &&
        action.value == seconds.toString() && action.target == label

    /** An editor showing the right duration is insufficient: the running control must be visible. */
    fun runningSeconds(snapshot: UiSnapshot, elapsedSeconds: Long): Int? {
        val visible = snapshot.elements.filter { it.visible && !it.sensitive }
        fun labels(node: UiElement) = listOfNotNull(node.text, node.contentDescription)
        if (label != null && visible.none { labels(it).any { text -> text.trim() == label } }) return null
        if (visible.none { node -> node.enabled && (node.clickable || UiNodeAction.CLICK in node.availableActions) &&
                labels(node).any { it.replace(" ", "").lowercase() in setOf("일시정지", "일시중지", "pause") } }) return null
        val remaining = visible.filterNot { it.editable }.flatMap { node -> labels(node).mapNotNull { text ->
            CLOCK.matchEntire(text.trim())?.let { match ->
                val hours = match.groupValues[1].takeIf(String::isNotEmpty)?.toIntOrNull() ?: 0
                hours * 3600 + match.groupValues[2].toInt() * 60 + match.groupValues[3].toInt()
            } ?: SPOKEN_CLOCK.matchEntire(text.trim())?.takeIf { text.any(Char::isDigit) }?.let { match ->
                (match.groupValues[1].toIntOrNull() ?: 0) * 3600 +
                    (match.groupValues[2].toIntOrNull() ?: 0) * 60 + (match.groupValues[3].toIntOrNull() ?: 0)
            }
        } }.distinct().filter { it in (seconds - elapsedSeconds - 4).coerceAtLeast(1)..seconds.toLong() }
        return remaining.singleOrNull()
    }

    companion object {
        private val REQUEST = Regex("^(?:시계(?:\\s*앱)?에서\\s*)?(?:([\\p{L}\\p{Nd} _-]{1,60}?)(?:이?라는 이름으로)\\s*)?" +
            "((?:[0-9]+\\s*(?:시간|분|초)\\s*)+)타이머(?:를)?\\s*(?:시작해|맞춰|설정해)\\s*(?:줘|주세요)$")
        private val PART = Regex("([0-9]+)\\s*(시간|분|초)")
        private val CLOCK = Regex("^(?:([0-9]{1,2}):)?([0-5]?[0-9]):([0-5][0-9])$")
        private val SPOKEN_CLOCK = Regex("^(?:([0-9]{1,2})\\s*시간\\s*)?(?:([0-5]?[0-9])\\s*분\\s*)?(?:([0-5]?[0-9])\\s*초)?$")

        fun parse(command: String): TimerRequest? {
            val text = Normalizer.normalize(command, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ")
            if (SafetyPolicy.isExplicitNegation(text)) return null
            val match = REQUEST.matchEntire(text) ?: return null
            val parts = PART.findAll(match.groupValues[2]).toList()
            if (parts.map { it.groupValues[2] }.distinct().size != parts.size) return null
            val seconds = parts.sumOf {
                val value = it.groupValues[1].toLongOrNull()?.takeIf { n -> n <= 86400 } ?: return null
                value * when (it.groupValues[2]) { "시간" -> 3600; "분" -> 60; else -> 1 }
            }
            if (seconds !in 1..86400) return null
            return TimerRequest(seconds.toInt(), match.groupValues[1].trim().takeIf(String::isNotEmpty))
        }
    }
}
