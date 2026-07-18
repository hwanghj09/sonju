package com.hwanghj09.sonju.shopping

import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import java.text.Normalizer

data class BaeminOrderRequest(val query: String)

object BaeminOrderRequestParser {
    private val appTerms = listOf("배달의민족", "배민")
    private val orderTerms = listOf("시켜줘", "시켜 줘", "주문해줘", "주문해 줘", "주문")
    private val removableTerms = listOf(
        "배달의민족", "배민", "들어가서", "들어가", "열어서", "열어", "에서",
        "배달로", "배달", "시켜줘", "시켜 줘", "주문해줘", "주문해 줘", "주문",
        "해줘", "해 줘", "줘", "주세요",
    )

    fun parse(command: String): BaeminOrderRequest? {
        val normalized = Normalizer.normalize(command, Normalizer.Form.NFKC).trim()
        if (appTerms.none(normalized::contains) || orderTerms.none(normalized::contains)) return null
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

    private val credentialTerms = setOf(
        "비밀번호", "비번", "pin", "otp", "인증번호", "보안코드", "cvc", "cvv",
        "카드번호", "카드 번호", "생체인증", "지문인증",
    )
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
    ): BaeminScreenAction {
        if (snapshot.packageName != PACKAGE_NAME) return BaeminScreenAction.Wait
        if (snapshot.treeTruncated) {
            return BaeminScreenAction.Stop("화면 구조가 너무 커서 주문 보조를 안전하게 계속할 수 없어요.")
        }
        val visibleText = snapshot.elements.asSequence()
            .filter { it.visible && !it.sensitive }
            .flatMap { sequenceOf(it.text.orEmpty(), it.contentDescription.orEmpty()) }
            .joinToString(" ")
            .lowercase()
        credentialTerms.firstOrNull { visibleText.contains(it) }?.let {
            return BaeminScreenAction.Stop("비밀번호·인증·카드 정보 화면에서는 자동 조작을 중단합니다.")
        }
        if (completionTerms.any(visibleText::contains)) return BaeminScreenAction.Complete

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

    fun reviewSummary(snapshot: UiSnapshot, actionLabel: String): String {
        val importantTerms = listOf(
            "총", "결제", "주문금액", "배달팁", "할인", "주소", "배달주소", "원",
        )
        val lines = snapshot.elements.asSequence()
            .filter { it.visible && !it.sensitive }
            .flatMap { sequenceOf(it.text.orEmpty(), it.contentDescription.orEmpty()) }
            .map(String::trim)
            .filter { value -> value.isNotBlank() && importantTerms.any(value::contains) }
            .distinct()
            .take(10)
            .toList()
        return buildString {
            appendLine("누르려는 버튼: $actionLabel")
            if (lines.isNotEmpty()) {
                appendLine()
                appendLine("현재 주문 화면에서 확인된 내용:")
                lines.forEach { appendLine("• ${it.take(100)}") }
            }
            appendLine()
            append("주소·메뉴·수량·총액·결제수단이 맞을 때만 최종 확정해 주세요.")
        }
    }

    private fun findClickable(
        snapshot: UiSnapshot,
        terms: List<String>,
        allowAffixes: Boolean = true,
    ): Pair<String, String>? {
        val matches = snapshot.elements.asSequence()
            .filter { it.visible && it.enabled && !it.sensitive }
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
        return matches.firstOrNull()
    }

    private fun findQueryResult(snapshot: UiSnapshot, query: String): Pair<String, String>? {
        val compactQuery = compact(query)
        if (compactQuery.isBlank()) return null
        return snapshot.elements.asSequence()
            .filter { it.visible && it.enabled && !it.sensitive && !it.editable }
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
            .firstOrNull()
    }

    private fun clickablePath(snapshot: UiSnapshot, element: UiElement): String? {
        if (element.clickable) return element.path
        return generateSequence(element.path) { path ->
            path.substringBeforeLast('.', missingDelimiterValue = "").takeIf(String::isNotBlank)
        }.drop(1).take(6)
            .firstOrNull { path -> pathElement(snapshot, path)?.let { it.visible && it.enabled && it.clickable } == true }
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
