package com.hwanghj09.sonju.shopping

import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import java.text.Normalizer

data class BaeminOrderRequest(val query: String)

object BaeminOrderRequestParser {
    private val appTerms = listOf("배달의민족", "배민")
    private val orderEnding = Regex(
        "\\s*(?:시켜\\s*(?:줘|주세요)|주문(?:해)?\\s*(?:줘|주세요)|주문해|주문)[.!?]?$",
    )
    private val removableTerms = listOf(
        "배달의민족", "배민", "들어가서", "들어가", "열어서", "열어", "에서",
        "배달로", "배달", "시켜줘", "시켜 줘", "주문해줘", "주문해 줘", "주문",
        "해줘", "해 줘", "줘", "주세요",
    )

    fun parse(command: String): BaeminOrderRequest? {
        val normalized = Normalizer.normalize(command, Normalizer.Form.NFKC).trim()
        if (appTerms.none(normalized::contains) || !orderEnding.containsMatchIn(normalized)) return null
        var query = normalized
        removableTerms.sortedByDescending(String::length).forEach { query = query.replace(it, " ") }
        query = query.replace(Regex("[^\\p{L}\\p{Nd}]+"), " ").trim()
        return query.takeIf { it.length in 1..40 }?.let(::BaeminOrderRequest)
    }
}

sealed interface BaeminScreenAction {
    data class Click(val path: String, val label: String, val finalCommit: Boolean = false) :
        BaeminScreenAction

    data class SetSearchText(val path: String, val value: String) : BaeminScreenAction
    data class Scroll(val path: String) : BaeminScreenAction
    data class Stop(val reason: String) : BaeminScreenAction
    data object Complete : BaeminScreenAction
    data object Wait : BaeminScreenAction
}

object BaeminNavigator {
    const val PACKAGE_NAME = "com.sampleapp"

    private val finalActionTerms = listOf(
        "결제하기", "주문하기", "결제 및 주문", "주문 및 결제", "주문 확정", "결제",
    )
    private val cartTerms = listOf("장바구니 보기", "장바구니", "카트 보기")
    private val addTerms = listOf("장바구니 담기", "메뉴 담기", "담기")
    private val confirmOptionTerms = listOf("선택 완료", "옵션 선택 완료", "메뉴 담기")
    private val searchTerms = listOf("검색", "검색하기")
    private val completionTerms = listOf("주문이 접수", "주문 접수", "주문 완료", "주문이 완료")

    fun next(
        snapshot: UiSnapshot,
        query: String,
        stepCount: Int,
        itemAdded: Boolean = false,
        completionBaseline: List<String>? = null,
    ): BaeminScreenAction {
        if (snapshot.packageName != PACKAGE_NAME) return BaeminScreenAction.Wait
        val currentCompletionState = completionState(snapshot)
        if (completionBaseline != null) {
            return if (currentCompletionState.isNotEmpty() && currentCompletionState != completionBaseline) {
                BaeminScreenAction.Complete
            } else {
                BaeminScreenAction.Wait
            }
        }
        if (currentCompletionState.isNotEmpty()) {
            return BaeminScreenAction.Stop("현재 화면의 과거 주문 완료 문구를 새 주문 성공으로 판단하지 않습니다.")
        }

        findClickable(snapshot, finalActionTerms)?.let { (path, label) ->
            return BaeminScreenAction.Click(path, label, finalCommit = true)
        }
        if (itemAdded) {
            findClickable(snapshot, cartTerms)?.let { (path, label) ->
                return BaeminScreenAction.Click(path, label)
            }
        } else {
            findClickable(snapshot, confirmOptionTerms)?.let { (path, label) ->
                return BaeminScreenAction.Click(path, label)
            }
            findClickable(snapshot, addTerms)?.let { (path, label) ->
                return BaeminScreenAction.Click(path, label)
            }
        }

        val editable = snapshot.elements.filter {
            it.visible && it.enabled && it.editable && !it.sensitive
        }.singleOrNull()
        if (editable != null && compact(editable.text.orEmpty()) != compact(query)) {
            return BaeminScreenAction.SetSearchText(editable.path, query)
        }
        if (editable != null) {
            findQueryResult(snapshot, query)?.let { (path, label) ->
                return BaeminScreenAction.Click(path, label)
            }
        }
        findClickable(snapshot, searchTerms, allowAffixes = false)?.let { (path, label) ->
            return BaeminScreenAction.Click(path, label)
        }
        findQueryResult(snapshot, query)?.let { (path, label) ->
            return BaeminScreenAction.Click(path, label)
        }

        val scrollable = effectiveScrollable(snapshot).singleOrNull()
        if (scrollable != null && stepCount < 10) return BaeminScreenAction.Scroll(scrollable.path)
        return BaeminScreenAction.Stop(
            "배민 화면에서 다음 버튼을 하나로 확실히 찾지 못했어요. 원하는 메뉴를 직접 선택한 뒤 ‘손주야’와 명령을 함께 다시 말해 주세요.",
        )
    }

    /**
     * Safe deterministic prefix of an order workflow. It opens search, enters the explicit query,
     * and selects only one uniquely identifiable query suggestion. Restaurant/menu/options and
     * final order controls stay in the verified adaptive planner where ambiguity can stop or ask.
     */
    fun nextSearchAction(snapshot: UiSnapshot, query: String): BaeminScreenAction {
        if (snapshot.packageName != PACKAGE_NAME) return BaeminScreenAction.Wait
        if (snapshot.treeTruncated || snapshot.windowTitle == "[민감 화면]" ||
            snapshot.elements.any { it.visible && it.sensitive }
        ) {
            return BaeminScreenAction.Stop("민감하거나 불완전한 배민 화면에서는 검색을 자동화하지 않습니다.")
        }
        val editable = snapshot.elements.filter {
            it.visible && it.enabled && it.editable && !it.sensitive
        }.singleOrNull()
        if (editable != null) {
            if (compact(editable.text.orEmpty()) != compact(query)) {
                return BaeminScreenAction.SetSearchText(editable.path, query)
            }
            val queryResults = findQueryResults(snapshot, query)
            queryResults.singleOrNull()?.let { (path, label) ->
                return BaeminScreenAction.Click(path, label)
            }
            if (queryResults.size > 1) {
                return BaeminScreenAction.Stop("검색 결과가 여러 개라 하나를 임의로 선택하지 않습니다.")
            }
            return BaeminScreenAction.Wait
        }
        findClickable(snapshot, searchTerms, allowAffixes = false)?.let { (path, label) ->
            return BaeminScreenAction.Click(path, label)
        }
        val queryResults = findQueryResults(snapshot, query)
        queryResults.singleOrNull()?.let { (path, label) ->
            return BaeminScreenAction.Click(path, label)
        }
        if (queryResults.size > 1) {
            return BaeminScreenAction.Stop("검색 결과가 여러 개라 하나를 임의로 선택하지 않습니다.")
        }
        return BaeminScreenAction.Wait
    }

    /** The add step is complete only when cart-related semantic state actually changed. */
    fun itemAddedPostcondition(before: UiSnapshot, after: UiSnapshot): Boolean {
        if (before.packageName != PACKAGE_NAME || after.packageName != PACKAGE_NAME) return false
        val beforeState = cartState(before)
        val afterState = cartState(after)
        return afterState.isNotEmpty() && beforeState != afterState
    }

    fun completionState(snapshot: UiSnapshot): List<String> = snapshot.elements.asSequence()
        .filter { it.visible && !it.sensitive }
        .mapNotNull { element ->
            val value = listOfNotNull(element.text, element.contentDescription)
                .joinToString(" ")
                .trim()
            value.takeIf { text -> completionTerms.any(text.lowercase()::contains) }
                ?.let { text -> "${element.path}|$text" }
        }
        .sorted()
        .toList()

    private fun cartState(snapshot: UiSnapshot): List<String> = snapshot.elements.asSequence()
        .filter { it.visible && !it.sensitive }
        .mapNotNull { element ->
            val text = listOfNotNull(
                element.text,
                element.contentDescription,
                element.stateDescription,
            ).joinToString(" ").trim()
            text.takeIf { value -> cartTerms.any { term -> compact(value).contains(compact(term)) } }
                ?.let { value ->
                    listOf(
                        element.path,
                        value,
                        element.checked,
                        element.selected,
                    ).joinToString("|")
                }
        }
        .sorted()
        .toList()

    private fun findClickable(
        snapshot: UiSnapshot,
        terms: List<String>,
        allowAffixes: Boolean = true,
    ): Pair<String, String>? {
        val matches = snapshot.elements.asSequence()
            .mapNotNull { element ->
                val label = sequenceOf(element.text, element.contentDescription)
                    .filterNotNull()
                    .map(String::trim)
                    .firstOrNull { value ->
                        val compactValue = compact(value)
                        terms.any { term ->
                            val compactTerm = compact(term)
                            compactValue == compactTerm || allowAffixes &&
                                (compactValue.startsWith(compactTerm) ||
                                    compactValue.endsWith(compactTerm))
                        }
                    }
                    ?: return@mapNotNull null
                clickablePath(snapshot, element)?.let { it to label }
            }
            .distinctBy { it.first }
            .sortedWith(compareBy({ pathElement(snapshot, it.first)?.bounds?.top ?: Int.MAX_VALUE },
                { pathElement(snapshot, it.first)?.bounds?.left ?: Int.MAX_VALUE }))
            .toList()
        return matches.singleOrNull()
    }

    private fun findQueryResult(snapshot: UiSnapshot, query: String): Pair<String, String>? =
        findQueryResults(snapshot, query).singleOrNull()

    private fun findQueryResults(snapshot: UiSnapshot, query: String): List<Pair<String, String>> {
        val compactQuery = compact(query)
        if (compactQuery.isBlank()) return emptyList()
        return snapshot.elements.asSequence()
            .filter { !it.editable }
            .mapNotNull { element ->
                val label = sequenceOf(element.text, element.contentDescription)
                    .filterNotNull()
                    .map(String::trim)
                    .firstOrNull { compact(it).contains(compactQuery) }
                    ?: return@mapNotNull null
                clickablePath(snapshot, element)?.let { it to label }
            }
            .distinctBy { it.first }
            .sortedWith(compareBy({ pathElement(snapshot, it.first)?.bounds?.top ?: Int.MAX_VALUE },
                { pathElement(snapshot, it.first)?.bounds?.left ?: Int.MAX_VALUE }))
            .toList()
    }

    private fun clickablePath(snapshot: UiSnapshot, element: UiElement): String? {
        if (element.clickable) return element.path
        return generateSequence(element.path) { path ->
            path.substringBeforeLast('.', missingDelimiterValue = "").takeIf(String::isNotBlank)
        }.drop(1).take(6)
            .firstOrNull { path -> pathElement(snapshot, path)?.clickable == true }
    }

    private fun pathElement(snapshot: UiSnapshot, path: String): UiElement? =
        snapshot.elements.firstOrNull { it.path == path }

    private fun effectiveScrollable(snapshot: UiSnapshot): List<UiElement> {
        val scrollable = snapshot.elements.filter { it.visible && it.enabled && it.scrollable }
        return scrollable.filter { candidate ->
            scrollable.none { other ->
                other.path != candidate.path && other.path.startsWith("${candidate.path}.")
            }
        }
    }

    private fun compact(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
}
