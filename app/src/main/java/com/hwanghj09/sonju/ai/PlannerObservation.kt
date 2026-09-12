package com.hwanghj09.sonju.ai

import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiNodeAction
import com.hwanghj09.sonju.agent.UiSnapshot
import java.text.Normalizer

/** A bounded observation that keeps task evidence as well as controls, with original node paths. */
object PlannerObservation {
    fun render(snapshot: UiSnapshot, command: String, maxElements: Int = 120): String {
        val limit = maxElements.coerceIn(1, 180)
        val terms = Regex("[\\p{L}\\p{Nd}]{2,}").findAll(normalize(command))
            .map { it.value }.filterNot { it in STOP_WORDS }.distinct().take(24).toList()
        val candidates = snapshot.elements.filter { node ->
            node.visible && !node.sensitive && (interactive(node) || labels(node).isNotBlank())
        }
        val nodeIds = snapshot.elements.withIndex().associate { it.value.path to "node=${it.index}" }
        fun relevance(node: UiElement): Int {
            val label = normalize(labels(node))
            return terms.sumOf { term ->
                when {
                    label.contains(term) -> 30
                    label.length >= 2 && term.contains(label) -> 20
                    else -> 0
                }
            }
        }
        val selected = linkedSetOf<UiElement>()
        // Do not let large lists of clickable wrappers erase headings and result values.
        selected += candidates.filter { !interactive(it) && labels(it).isNotBlank() }
            .sortedByDescending { relevance(it) + if (it.heading) 15 else 0 }
            .take(limit / 3)
        selected += candidates.filterNot { it in selected }.sortedByDescending {
            relevance(it) + when {
                it.focused || it.editable -> 18
                it.checkable -> 15
                it.heading -> 12
                it.clickable -> 8
                it.scrollable -> 6
                else -> 0
            }
        }.take(limit - selected.size)
        return buildString {
            appendLine("package=${snapshot.packageName.take(120)}")
            appendLine("window=${snapshot.windowTitle.orEmpty().take(120)} epoch=${snapshot.epoch}")
            appendLine("observed=${candidates.size} shown=${selected.size} omitted=${candidates.size - selected.size} tree_truncated=${snapshot.treeTruncated}")
            if (com.hwanghj09.sonju.agent.ScreenContextHandoff.hasUnobservedRenderedContent(snapshot)) {
                appendLine("rendered_content_unavailable=true: 화면을 그리는 영역의 본문이 접근성에 노출되지 않음. 도구 모음만으로 페이지 내용을 추측하거나 항목이 없다고 판단하지 않는다.")
            }
            if (candidates.size > selected.size) appendLine("일부 노드는 요약에서 생략됨. 항목이 없다고 단정하지 말고 검색/스크롤 후 재관찰한다.")
            candidates.filter { it in selected }.forEach { node ->
                appendLine(node.compactLine().replaceFirst("path=${node.path}", nodeIds.getValue(node.path)))
                if (interactive(node) && labels(node).isBlank()) {
                    val children = candidates.asSequence()
                        .filter { it.path.startsWith("${node.path}.") && labels(it).isNotBlank() }
                        .take(3).joinToString(" | ") { "${nodeIds.getValue(it.path)}: ${labels(it).take(100).replace('\n', ' ')}" }
                    if (children.isNotEmpty()) appendLine("  descendant_labels (관찰 데이터): $children")
                }
            }
        }
    }

    /** Bind compact model references to this exact observation, before any live-node verification. */
    fun resolveSelector(selector: String, snapshot: UiSnapshot?): String {
        val index = Regex("^node=([0-9]{1,5})$").matchEntire(selector)?.groupValues?.get(1)?.toIntOrNull()
            ?: return selector
        return snapshot?.elements?.getOrNull(index)?.takeIf { it.visible && !it.sensitive }?.path ?: selector
    }

    private fun labels(node: UiElement) = listOfNotNull(node.text, node.contentDescription,
        node.hintText, node.stateDescription, node.paneTitle).filter(String::isNotBlank).joinToString(" ")

    private fun interactive(node: UiElement) = node.clickable || node.editable || node.checkable ||
        node.scrollable || node.longClickable || node.dismissable || node.availableActions.any {
            it == UiNodeAction.CLICK || it == UiNodeAction.SET_TEXT || it.name.startsWith("SCROLL")
        }

    private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
    private val STOP_WORDS = setOf("열어줘", "알려줘", "찾아줘", "해줘", "주세요", "메뉴를", "화면을",
        "the", "please", "open", "show", "find", "화면", "메뉴")
}
