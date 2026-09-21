package com.hwanghj09.sonju.verifier

import com.hwanghj09.sonju.agent.AgentAction
import com.hwanghj09.sonju.agent.UiNodeAction
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import com.hwanghj09.sonju.agent.UiTargetResolver
import java.text.Normalizer

/** Only submit the exact current value of an observed search/address field, regardless of planner. */
object SearchSubmissionPolicy {
    fun isSearchInputClick(action: AgentAction, snapshot: UiSnapshot, resolvedNodeId: String? = null): Boolean {
        if (action.type != com.hwanghj09.sonju.agent.ActionType.CLICK) return false
        val path = resolvedNodeId ?: UiTargetResolver.resolveClickable(action, snapshot)?.clickablePath ?: return false
        return snapshot.elements.any { it.visible && it.editable && isSearchOrAddressField(it, snapshot) &&
            (path == it.path || path.startsWith("${it.path}.") || it.path.startsWith("$path.")) }
    }

    /** Exact query suggestions and explicit search controls submit, rather than refine, a query. */
    fun isSubmitClick(action: AgentAction, snapshot: UiSnapshot, value: String? = null): Boolean {
        if (action.type != com.hwanghj09.sonju.agent.ActionType.CLICK) return false
        val path = UiTargetResolver.resolveClickable(action, snapshot)?.clickablePath ?: return false
        val inputs = snapshot.elements.filter { it.visible && it.enabled && it.editable &&
            isSearchOrAddressField(it, snapshot) && !it.text.isNullOrBlank() &&
            (value == null || normalize(it.text.orEmpty()) == normalize(value)) }
        if (inputs.isEmpty() || snapshot.elements.any { it.editable &&
                (it.path == path || it.path.startsWith("$path.")) }) return false
        return snapshot.elements.any { node -> node.visible && !node.sensitive &&
            (node.path == path || node.path.startsWith("$path.")) &&
            listOfNotNull(node.text, node.contentDescription).any { label ->
                normalize(label).trim().lowercase() in setOf("검색", "검색하기", "search", "go", "이동") ||
                    inputs.any { normalize(label) == normalize(it.text.orEmpty()) }
            } }
    }

    fun allows(command: String, action: AgentAction, snapshot: UiSnapshot, resolvedNodeId: String?): Boolean {
        val input = snapshot.elements.singleOrNull { it.path == resolvedNodeId } ?: return false
        val value = action.value?.takeIf { it.isNotBlank() && it.length <= 4_000 } ?: return false
        if (!input.visible || !input.enabled || input.sensitive ||
            !(input.editable || UiNodeAction.SET_TEXT in input.availableActions)) return false
        if (command.isBlank() || normalize(input.text.orEmpty()) != normalize(value)) return false
        return isSearchOrAddressField(input, snapshot) &&
            !value.contains(Regex("\\p{Nd}{6,}")) &&
            !normalize(value).trimStart().contains(Regex("^(?:javascript|data|file|intent):", RegexOption.IGNORE_CASE))
    }

    fun isSearchOrAddressField(input: UiElement, snapshot: UiSnapshot? = null): Boolean {
        // Do not rely on the model's action description, or a search title elsewhere on the page.
        val identity = listOfNotNull(input.viewId?.substringAfterLast('/'), input.hintText,
            input.contentDescription, input.paneTitle).joinToString(" ").lowercase()
        if (NON_SEARCH.containsMatchIn(identity) || input.sensitive || input.imeAction in setOf(4, 5, 7)) return false
        if (SEARCH.containsMatchIn(identity) || ADDRESS.containsMatchIn(identity) || input.imeAction == 3) return true
        // Compose/WebView can remove the placeholder after typing and expose the search label
        // on a child (for example, "검색어 지우기"). Only this editor's own subtree is evidence.
        return snapshot?.elements?.any { child ->
            child.visible && !child.sensitive && !child.editable && child.path.startsWith("${input.path}.") &&
                snapshot.elements.none { other -> other.editable && other.path != input.path &&
                    child.path.startsWith("${other.path}.") && other.path.startsWith("${input.path}.") } &&
                listOfNotNull(child.text, child.contentDescription, child.hintText).any {
                    SEARCH.containsMatchIn(it) && !NON_SEARCH.containsMatchIn(it)
                }
        } == true
    }

    /** Some virtual editors report an unknown offset (-1), even for their complete value. */
    fun matchesConnectionValue(value: String, surrounding: String?, offset: Int): Boolean =
        value.isNotBlank() && value.length <= 4_000 && offset in -1..0 && surrounding != null &&
            normalize(value) == normalize(surrounding)

    fun editorAction(imeOptions: Int, actionId: Int, actionLabel: String?): Int? {
        if ((imeOptions and 0xff) in setOf(4, 5, 7)) return null
        if (!actionLabel.isNullOrBlank()) {
            val label = actionLabel.trim()
            return actionId.takeIf { !NON_SEARCH.containsMatchIn(label) &&
                !Regex("보내|다음|이전|\\b(?:send|submit|publish|next|previous)\\b", RegexOption.IGNORE_CASE).containsMatchIn(label) &&
                (SEARCH.containsMatchIn(label) || Regex("find|go|done|이동|완료", RegexOption.IGNORE_CASE).matches(label)) }
        }
        return (imeOptions and 0xff).takeIf { it in setOf(2, 3, 6) } // GO, SEARCH, DONE
    }

    fun allowsEnterKey(imeOptions: Int, inputType: Int): Boolean =
        (imeOptions and 0xff) in setOf(0, 1, 2, 3, 6) &&
            imeOptions and 0x40000000 == 0 && inputType and 0x20000 == 0

    /** Focus, caret, keyboard resizing, and node reparenting do not prove a search happened. */
    fun hasObservedEffect(before: UiSnapshot, after: UiSnapshot, inputPath: String?): Boolean {
        if (after.packageName == "unknown" || before.packageName != after.packageName ||
            after.treeTruncated || after.epoch <= before.epoch ||
            com.hwanghj09.sonju.agent.ScreenContextHandoff.hasVisibleLoadingIndicator(after)) return false
        val currentInput = UiTargetResolver.rebindEditable(before, after, inputPath)
        val previousInput = before.elements.singleOrNull { it.path == inputPath } ?: return false
        if (currentInput != null && ADDRESS.containsMatchIn(listOfNotNull(previousInput.viewId,
                previousInput.hintText, previousInput.contentDescription).joinToString(" ")) &&
            normalize(previousInput.text.orEmpty()) != normalize(currentInput.text.orEmpty()) &&
            currentInput.text.orEmpty().isNotBlank()) return true
        fun content(snapshot: UiSnapshot, editorPath: String?) = snapshot.elements.asSequence()
            .filter { it.visible && !it.sensitive && !it.editable &&
                (editorPath == null || it.path != editorPath && !it.path.startsWith("$editorPath.")) }
            .map { listOf(it.text.orEmpty(), it.contentDescription.orEmpty(), it.stateDescription.orEmpty())
                .map(::normalize).joinToString("|").trim('|') }
            .filter(String::isNotBlank).toSet()
        val previous = content(before, inputPath)
        val current = content(after, currentInput?.path)
        val added = current - previous
        // Keyboard clipping can merely reveal/hide more of the same autocomplete list.
        return added.isNotEmpty() && ((previous - current).isNotEmpty() || added.any {
            Regex("검색\\s*결과|search\\s*results?|results?\\s*for|no\\s*results?", RegexOption.IGNORE_CASE).containsMatchIn(it)
        })
    }

    private val SEARCH = Regex("검색|(?:^|[^a-z])(?:search|query)(?:[^a-z]|$)", RegexOption.IGNORE_CASE)
    private val ADDRESS = Regex("주소창|웹\\s*주소|URL|omnibox|(?:location|address|url)[_\\s-]*bar", RegexOption.IGNORE_CASE)
    private val NON_SEARCH = Regex("메시지|메세지|답장|댓글|채팅|전송|비밀번호|인증|message|reply|comment|compose|password|(?:^|[^a-z])(?:otp|pin)(?:[^a-z]|$)", RegexOption.IGNORE_CASE)
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("\\p{Cf}"), "")
}
