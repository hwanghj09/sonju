package com.hwanghj09.sonju.grounding

import com.hwanghj09.sonju.perception.ScreenState
import com.hwanghj09.sonju.perception.SemanticNode
import com.hwanghj09.sonju.perception.SemanticRole
import com.hwanghj09.sonju.agent.UiNodeAction
import java.text.Normalizer
import kotlin.math.abs

enum class SpatialHint { TOP, BOTTOM, LEFT, RIGHT, CENTER }

enum class InteractionRequirement { ANY, CLICK, EDIT, SCROLL }

data class GroundingQuery(
    /** Flexible legacy selector; new planners should prefer the typed fields below. */
    val selector: String? = null,
    val nodeIdHint: String? = null,
    val role: SemanticRole? = null,
    val textEquals: String? = null,
    val textContains: String? = null,
    val resourceIdHint: String? = null,
    val contentDescriptionHint: String? = null,
    val spatialHint: SpatialHint? = null,
    val stateRequirements: Map<String, Boolean> = emptyMap(),
    val ancestorHint: String? = null,
    val descendantHint: String? = null,
    val interaction: InteractionRequirement = InteractionRequirement.ANY,
    val scrollAction: UiNodeAction? = null,
)

data class GroundingCandidate(
    val nodeId: String,
    val label: String?,
    val score: Int,
    val confidence: Double,
)

sealed class GroundingResult {
    data class Success(
        val nodeId: String,
        val confidence: Double,
        val evidence: List<String>,
    ) : GroundingResult()

    data class Ambiguous(val candidates: List<GroundingCandidate>) : GroundingResult()

    data class NotFound(val reason: String) : GroundingResult()
}

interface SemanticGrounder {
    fun ground(query: GroundingQuery, screen: ScreenState): GroundingResult
}

class DeterministicSemanticGrounder(
    private val ambiguityMargin: Int = DEFAULT_AMBIGUITY_MARGIN,
) : SemanticGrounder {
    override fun ground(query: GroundingQuery, screen: ScreenState): GroundingResult {
        val byId = screen.nodes.associateBy(SemanticNode::nodeId)
        val scored = screen.nodes.asSequence()
            .filter { node -> node.visible && node.enabled && satisfiesInteraction(node, query.interaction) }
            .filter { node -> supportsScrollAction(node, query.scrollAction) }
            .mapNotNull { node ->
                val score = score(node, query, byId)
                if (score <= 0) null else node to score
            }
            .map { (node, score) ->
                val effective = if (query.interaction == InteractionRequirement.CLICK) {
                    clickableAncestor(node, byId) ?: return@map null
                } else {
                    node
                }
                effective to score
            }
            .filterNotNull()
            .groupBy({ it.first.nodeId }, { it.first to it.second })
            .map { (_, values) -> values.maxBy { it.second } }
            .sortedWith(compareByDescending<Pair<SemanticNode, Int>> { it.second }.thenBy { it.first.nodeId })

        if (scored.isEmpty()) return GroundingResult.NotFound(if (query.scrollAction != null)
            "요청한 대상과 스크롤 방향에 맞는 접근성 노드를 찾지 못했습니다. 실제 지원 방향과 target을 다시 확인하세요."
            else "semantic target not found")
        val best = scored.first()
        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null && best.second - runnerUp.second < ambiguityMargin) {
            return GroundingResult.Ambiguous(
                scored.take(MAX_AMBIGUOUS_CANDIDATES).map { (node, score) ->
                    GroundingCandidate(node.nodeId, node.normalizedLabel, score, confidence(score))
                },
            )
        }
        return GroundingResult.Success(
            nodeId = best.first.nodeId,
            confidence = confidence(best.second),
            evidence = evidence(best.first, query),
        )
    }

    private fun score(
        node: SemanticNode,
        query: GroundingQuery,
        byId: Map<String, SemanticNode>,
    ): Int {
        if (!matchesState(node, query.stateRequirements)) return 0
        if (query.role != null && node.role != query.role) return 0
        if (!relationMatches(node, query.ancestorHint, byId, ancestors = true)) return 0
        if (!relationMatches(node, query.descendantHint, byId, ancestors = false)) return 0

        var score = 0
        val labels = listOfNotNull(node.text, node.normalizedLabel)
        val descriptions = listOfNotNull(node.contentDescription)
        val resourceId = node.resourceId.orEmpty()
        query.selector?.let { selector ->
            val normalizedSelector = normalize(selector)
            val compactSelector = compact(selector)
            val allLabels = labels + descriptions
            score += when {
                node.nodeId.equals(selector, ignoreCase = true) -> 130
                resourceId.equals(selector, ignoreCase = true) -> 120
                resourceId.substringAfterLast('/').equals(selector, ignoreCase = true) -> 115
                allLabels.any { normalize(it) == normalizedSelector } -> 110
                compactSelector.length >= 2 && allLabels.any { compact(it) == compactSelector } -> 100
                compactSelector.length >= 4 && allLabels.any { compact(it).contains(compactSelector) } -> 70
                else -> return 0
            }
        }
        query.nodeIdHint?.let { hint ->
            score += if (node.nodeId == hint) 130 else return 0
        }
        query.resourceIdHint?.let { hint ->
            score += when {
                resourceId.equals(hint, ignoreCase = true) -> 120
                resourceId.substringAfterLast('/').equals(hint, ignoreCase = true) -> 115
                compact(resourceId).contains(compact(hint)) && compact(hint).length >= 3 -> 75
                else -> return 0
            }
        }
        query.textEquals?.let { target ->
            score += when {
                labels.any { normalize(it) == normalize(target) } -> 110
                labels.any { compact(it) == compact(target) } && compact(target).length >= 2 -> 100
                else -> return 0
            }
        }
        query.contentDescriptionHint?.let { target ->
            score += if (descriptions.any { normalize(it) == normalize(target) }) 105 else return 0
        }
        query.textContains?.let { target ->
            val compactTarget = compact(target)
            score += if (compactTarget.length >= 2 && labels.any { compact(it).contains(compactTarget) }) {
                70
            } else {
                return 0
            }
        }
        if (query.role != null) score += 30
        if (node.clickable && query.interaction == InteractionRequirement.CLICK) score += 20
        if (node.editable && query.interaction == InteractionRequirement.EDIT) score += 20
        if (node.scrollable && query.interaction == InteractionRequirement.SCROLL) score += 20
        if (node.enabled) score += 10
        query.spatialHint?.let { score += spatialScore(node, it, byId.values) }

        val hasSelector = listOf(
            query.selector,
            query.nodeIdHint,
            query.textEquals,
            query.textContains,
            query.resourceIdHint,
            query.contentDescriptionHint,
        ).any { !it.isNullOrBlank() } || query.role != null
        if (!hasSelector) score += 1
        return score
    }

    private fun relationMatches(
        node: SemanticNode,
        hint: String?,
        byId: Map<String, SemanticNode>,
        ancestors: Boolean,
    ): Boolean {
        if (hint.isNullOrBlank()) return true
        val related = if (ancestors) {
            generateSequence(node.parentId) { byId[it]?.parentId }.mapNotNull(byId::get)
        } else {
            byId.values.asSequence().filter { it.nodeId.startsWith("${node.nodeId}.") }
        }
        return related.any { candidate ->
            listOfNotNull(candidate.normalizedLabel, candidate.resourceId)
                .any { compact(it).contains(compact(hint)) }
        }
    }

    private fun clickableAncestor(
        node: SemanticNode,
        byId: Map<String, SemanticNode>,
    ): SemanticNode? {
        if (node.clickable) return node
        var parentId = node.parentId
        repeat(MAX_ANCESTOR_DEPTH) {
            val path = parentId ?: return null
            val parent = byId[path]
            if (parent != null && parent.visible && parent.enabled && parent.clickable) return parent
            // UiTreeReader omits inert layout containers; their path still identifies the ancestry.
            parentId = parent?.parentId ?: path.substringBeforeLast('.', "").takeIf(String::isNotBlank)
        }
        return null
    }

    private fun satisfiesInteraction(node: SemanticNode, requirement: InteractionRequirement): Boolean =
        when (requirement) {
            InteractionRequirement.ANY -> true
            InteractionRequirement.CLICK -> node.clickable || node.parentId != null
            InteractionRequirement.EDIT -> node.editable
            InteractionRequirement.SCROLL -> node.scrollable
        }

    private fun matchesState(node: SemanticNode, requirements: Map<String, Boolean>): Boolean =
        requirements.all { (key, value) ->
            when (key.lowercase()) {
                "selected" -> node.selected == value
                "checked" -> node.checked == value
                "enabled" -> node.enabled == value
                "clickable" -> node.clickable == value
                "editable" -> node.editable == value
                else -> false
            }
        }

    private fun supportsScrollAction(node: SemanticNode, requested: UiNodeAction?): Boolean {
        if (requested == null || requested in node.actions) return true
        val directions = setOf(UiNodeAction.SCROLL_UP, UiNodeAction.SCROLL_DOWN,
            UiNodeAction.SCROLL_LEFT, UiNodeAction.SCROLL_RIGHT)
        if (node.actions.any { it in directions }) return false
        val generic = if (requested in setOf(UiNodeAction.SCROLL_DOWN, UiNodeAction.SCROLL_RIGHT))
            UiNodeAction.SCROLL_FORWARD else UiNodeAction.SCROLL_BACKWARD
        return generic in node.actions || node.actions.none {
            it == UiNodeAction.SCROLL_FORWARD || it == UiNodeAction.SCROLL_BACKWARD }
    }

    private fun spatialScore(
        node: SemanticNode,
        hint: SpatialHint,
        allNodes: Collection<SemanticNode>,
    ): Int {
        val maxRight = allNodes.maxOfOrNull { it.bounds.right }?.coerceAtLeast(1) ?: 1
        val maxBottom = allNodes.maxOfOrNull { it.bounds.bottom }?.coerceAtLeast(1) ?: 1
        val x = node.bounds.centerX.toDouble() / maxRight
        val y = node.bounds.centerY.toDouble() / maxBottom
        val match = when (hint) {
            SpatialHint.TOP -> y < .35
            SpatialHint.BOTTOM -> y > .65
            SpatialHint.LEFT -> x < .35
            SpatialHint.RIGHT -> x > .65
            SpatialHint.CENTER -> abs(x - .5) < .2 && abs(y - .5) < .2
        }
        return if (match) 10 else 0
    }

    private fun evidence(node: SemanticNode, query: GroundingQuery): List<String> = buildList {
        add("node=${node.nodeId}")
        add("role=${node.role}")
        query.selector?.let { add("selector=$it") }
        query.nodeIdHint?.let { add("nodeId=$it") }
        query.resourceIdHint?.let { add("resourceId=$it") }
        query.textEquals?.let { add("text=$it") }
        query.textContains?.let { add("textContains=$it") }
    }

    private fun confidence(score: Int): Double = (score / 110.0).coerceIn(0.0, 1.0)

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim().lowercase()

    private fun compact(value: String): String = normalize(value)
        .replace(Regex("[^\\p{L}\\p{Nd}]"), "")

    companion object {
        private const val DEFAULT_AMBIGUITY_MARGIN = 10
        private const val MAX_AMBIGUOUS_CANDIDATES = 5
        private const val MAX_ANCESTOR_DEPTH = 8
    }
}
