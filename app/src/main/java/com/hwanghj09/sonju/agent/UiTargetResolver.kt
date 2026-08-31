package com.hwanghj09.sonju.agent

import java.text.Normalizer

/** Deterministic, uniqueness-preserving selector resolution for live accessibility nodes. */
object UiTargetResolver {
    fun resolveClickable(action: AgentAction, snapshot: UiSnapshot): ResolvedClick? {
        if (action.type != ActionType.CLICK) return null
        val target = action.target.orEmpty()
        val candidates = snapshot.elements.asSequence()
            .filter { it.visible && it.enabled }
            .mapNotNull { element ->
                val score = selectorScore(element, target)
                if (score <= 0) return@mapNotNull null
                val clickable = effectiveClickable(snapshot, element) ?: return@mapNotNull null
                clickable.path to score
            }
            .groupBy({ it.first }, { it.second })
            .map { (path, scores) -> path to scores.max() }
        val selected = uniqueBest(candidates) ?: return null
        return ResolvedClick(selected.first)
    }

    fun resolveEditablePath(action: AgentAction, snapshot: UiSnapshot): String? {
        if (action.type !in setOf(ActionType.SET_TEXT, ActionType.SUBMIT_TEXT)) return null
        val target = action.target.orEmpty()
        val editable = snapshot.elements.asSequence()
            .filter { element -> element.visible && element.enabled && element.editable }
            .mapNotNull { element ->
                val score = selectorScore(element, target)
                if (score <= 0) null else element.path to score
            }
            .toList()
        return uniqueBest(editable)?.first
    }

    internal fun selectorScore(element: UiElement, target: String): Int {
        if (target.isBlank()) {
            return if (element.editable || element.clickable) BLANK_TARGET_SCORE else 0
        }
        val normalized = normalize(target)
        val compactTarget = compact(target)
        val viewId = element.viewId.orEmpty()
        val values = listOfNotNull(
            element.text,
            element.contentDescription,
            element.hintText,
            element.paneTitle,
            element.tooltipText,
            element.stateDescription,
        )
        return when {
            element.path.equals(target, ignoreCase = true) -> 120
            viewId.equals(target, ignoreCase = true) -> 115
            viewId.substringAfterLast('/').equals(target, ignoreCase = true) -> 110
            values.any { normalize(it) == normalized } -> 100
            compactTarget.length >= 3 && values.any { compact(it) == compactTarget } -> 95
            normalized.length >= 4 && values.any { normalize(it).contains(normalized) } -> 70
            compactTarget.length >= 4 && values.any { compact(it).contains(compactTarget) } -> 65
            else -> 0
        }
    }

    private fun effectiveClickable(snapshot: UiSnapshot, element: UiElement): UiElement? {
        if (element.clickable || UiNodeAction.CLICK in element.availableActions) return element
        return generateSequence(element.path) { path ->
            path.substringBeforeLast('.', missingDelimiterValue = "").takeIf(String::isNotBlank)
        }.drop(1).take(MAX_ANCESTOR_DEPTH).mapNotNull { path ->
            snapshot.elements.firstOrNull { it.path == path }
        }.firstOrNull { it.clickable || UiNodeAction.CLICK in it.availableActions }
    }

    private fun uniqueBest(candidates: List<Pair<String, Int>>): Pair<String, Int>? {
        if (candidates.isEmpty()) return null
        val sorted = candidates.sortedWith(compareByDescending<Pair<String, Int>> { it.second }
            .thenBy { it.first.length })
        val best = sorted.first()
        if (best.second == BLANK_TARGET_SCORE && sorted.size != 1) return null
        if (sorted.drop(1).any { it.second == best.second && it.first != best.first }) return null
        return best
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).trim().lowercase()

    private fun compact(value: String): String = normalize(value)
        .replace(Regex("[^\\p{L}\\p{Nd}]"), "")

    private const val MAX_ANCESTOR_DEPTH = 8
    private const val BLANK_TARGET_SCORE = 1
}
