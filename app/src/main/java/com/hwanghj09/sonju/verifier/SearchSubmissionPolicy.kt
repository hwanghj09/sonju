package com.hwanghj09.sonju.verifier

import com.hwanghj09.sonju.agent.AgentAction
import com.hwanghj09.sonju.agent.UiNodeAction
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import java.text.Normalizer

/** Only submit the exact current value of an observed search/address field, regardless of planner. */
object SearchSubmissionPolicy {
    fun allows(command: String, action: AgentAction, snapshot: UiSnapshot, resolvedNodeId: String?): Boolean {
        val input = snapshot.elements.singleOrNull { it.path == resolvedNodeId } ?: return false
        val value = action.value?.takeIf { it.isNotBlank() && it.length <= 4_000 } ?: return false
        if (!input.visible || !input.enabled || input.sensitive ||
            !(input.editable || UiNodeAction.SET_TEXT in input.availableActions)) return false
        if (command.isBlank() || normalize(input.text.orEmpty()) != normalize(value)) return false
        return isSearchOrAddressField(input) &&
            !value.contains(Regex("\\p{Nd}{6,}")) &&
            !normalize(value).trimStart().contains(Regex("^(?:javascript|data|file|intent):", RegexOption.IGNORE_CASE))
    }

    fun isSearchOrAddressField(input: UiElement): Boolean {
        // Do not rely on the model's action description, or a search title elsewhere on the page.
        val identity = listOfNotNull(input.viewId?.substringAfterLast('/'), input.hintText,
            input.contentDescription, input.paneTitle).joinToString(" ").lowercase()
        return (SEARCH.containsMatchIn(identity) || ADDRESS.containsMatchIn(identity)) &&
            !NON_SEARCH.containsMatchIn(identity)
    }

    private val SEARCH = Regex("검색|(?:^|[^a-z])(?:search|query)(?:[^a-z]|$)", RegexOption.IGNORE_CASE)
    private val ADDRESS = Regex("주소창|웹\\s*주소|URL|omnibox|(?:location|address|url)[_\\s-]*bar", RegexOption.IGNORE_CASE)
    private val NON_SEARCH = Regex("메시지|메세지|답장|댓글|채팅|전송|비밀번호|인증|message|reply|comment|compose|password|(?:^|[^a-z])(?:otp|pin)(?:[^a-z]|$)", RegexOption.IGNORE_CASE)
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("\\p{Cf}"), "")
}
