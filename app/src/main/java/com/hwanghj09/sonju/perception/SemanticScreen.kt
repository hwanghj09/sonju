package com.hwanghj09.sonju.perception

import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiNodeAction
import com.hwanghj09.sonju.agent.UiSnapshot
import java.security.MessageDigest
import java.text.Normalizer

enum class SemanticRole {
    BUTTON,
    TEXT,
    INPUT,
    CHECKBOX,
    RADIO,
    SWITCH,
    TAB,
    LIST,
    LIST_ITEM,
    IMAGE,
    IMAGE_BUTTON,
    MENU,
    DIALOG,
    TOOLBAR,
    SCROLL_CONTAINER,
    WEB_CONTENT,
    UNKNOWN,
}

data class SemanticNode(
    val nodeId: String,
    val parentId: String?,
    val childIds: List<String>,
    val packageName: String?,
    val className: String?,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: ScreenBounds,
    val visible: Boolean,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val selected: Boolean,
    val checked: Boolean?,
    val password: Boolean,
    val actions: Set<UiNodeAction>,
    val role: SemanticRole,
    val normalizedLabel: String?,
    val depth: Int,
) {
    val interactive: Boolean
        get() = clickable || longClickable || editable || scrollable || checked != null ||
            actions.any { it in INTERACTIVE_ACTIONS }

    companion object {
        private val INTERACTIVE_ACTIONS = setOf(
            UiNodeAction.CLICK,
            UiNodeAction.LONG_CLICK,
            UiNodeAction.SET_TEXT,
            UiNodeAction.SCROLL_FORWARD,
            UiNodeAction.SCROLL_BACKWARD,
            UiNodeAction.SCROLL_UP,
            UiNodeAction.SCROLL_DOWN,
            UiNodeAction.SCROLL_LEFT,
            UiNodeAction.SCROLL_RIGHT,
        )
    }
}

data class ObservationQuality(
    val score: Double,
    val labeledInteractiveRatio: Double,
    val reasons: List<String>,
)

data class ScreenState(
    val packageName: String,
    val activityHint: String?,
    val timestamp: Long,
    val fingerprint: String,
    val screenType: String?,
    val nodes: List<SemanticNode>,
    val interactiveNodes: List<SemanticNode>,
    val visibleTexts: List<String>,
    val dialogs: List<SemanticNode>,
    val metadata: Map<String, String>,
    val quality: ObservationQuality,
    val sourceEpoch: Long,
    val sourceFingerprint: String,
    val treeTruncated: Boolean,
) {
    fun compactRepresentation(maxNodes: Int = 90): String = buildString {
        appendLine("APP: $packageName")
        appendLine("SCREEN: ${screenType ?: "unknown"}")
        interactiveNodes.take(maxNodes).forEachIndexed { index, node ->
            append('[').append(index).append("] ").append(node.role)
            node.resourceId?.substringAfterLast('/')?.let { append(" id=").append(it) }
            node.normalizedLabel?.let { append(" label=\"").append(it.take(80)).append('"') }
            append(" bounds=(").append(node.bounds.left).append(',').append(node.bounds.top)
                .append(',').append(node.bounds.right).append(',').append(node.bounds.bottom)
                .append(')')
            if (node.selected) append(" selected=true")
            node.checked?.let { append(" checked=").append(it) }
            appendLine()
        }
    }
}

interface ScreenParser {
    fun parse(snapshot: UiSnapshot): ScreenState
}

/** Converts the short-lived Android node tree snapshot into an immutable semantic screen. */
object AccessibilityScreenParser : ScreenParser {
    override fun parse(snapshot: UiSnapshot): ScreenState {
        val visible = snapshot.elements.filter { element ->
            element.visible && (
                element.sensitive || hasArea(element.bounds) || element.path == "0"
                )
        }
        val childIds = visible.groupBy { parentPath(it.path) }.mapValues { (_, children) ->
            children.map(UiElement::path)
        }
        val meaningfulParents = visible.asSequence()
            .filter { isMeaningful(it) }
            .flatMap { ancestors(it.path) }
            .toSet()
        val semanticNodes = visible.asSequence()
            .filter { isMeaningful(it) || it.path in meaningfulParents }
            .map { element -> element.toSemanticNode(snapshot.packageName, childIds[element.path].orEmpty()) }
            .toList()
        val interactive = semanticNodes.filter(SemanticNode::interactive)
        val labels = semanticNodes.mapNotNull(SemanticNode::normalizedLabel).distinct()
        val fingerprint = fingerprint(snapshot.packageName, snapshot.windowTitle, semanticNodes)
        val quality = quality(semanticNodes, interactive, snapshot.treeTruncated)
        val screenType = classify(semanticNodes, interactive)
        return ScreenState(
            packageName = snapshot.packageName,
            activityHint = snapshot.windowTitle,
            timestamp = System.currentTimeMillis(),
            fingerprint = fingerprint,
            screenType = screenType,
            nodes = semanticNodes,
            interactiveNodes = interactive,
            visibleTexts = labels,
            dialogs = semanticNodes.filter { it.role == SemanticRole.DIALOG },
            metadata = mapOf(
                "agentOwned" to (snapshot.packageName == SONJU_PACKAGE).toString(),
                "observationQuality" to "%.3f".format(quality.score),
            ),
            quality = quality,
            sourceEpoch = snapshot.epoch,
            sourceFingerprint = snapshot.screenFingerprint(),
            treeTruncated = snapshot.treeTruncated,
        )
    }

    private fun UiElement.toSemanticNode(
        packageName: String,
        childIds: List<String>,
    ): SemanticNode {
        val label = if (sensitive) null else normalizeLabel(
            listOfNotNull(text, contentDescription, hintText, paneTitle).firstOrNull(),
        )
        return SemanticNode(
            nodeId = path,
            parentId = parentPath(path),
            childIds = childIds,
            packageName = packageName,
            className = className,
            resourceId = viewId,
            text = text.takeUnless { sensitive },
            contentDescription = contentDescription.takeUnless { sensitive },
            bounds = bounds,
            visible = visible,
            clickable = clickable || UiNodeAction.CLICK in availableActions,
            longClickable = longClickable || UiNodeAction.LONG_CLICK in availableActions,
            editable = editable || UiNodeAction.SET_TEXT in availableActions,
            scrollable = scrollable || availableActions.any { it.name.startsWith("SCROLL") },
            enabled = enabled,
            selected = selected,
            checked = checked.takeIf { checkable },
            password = sensitive,
            actions = availableActions,
            role = inferRole(this),
            normalizedLabel = label,
            depth = path.count { it == '.' },
        )
    }

    private fun inferRole(element: UiElement): SemanticRole {
        val className = element.className.lowercase()
        return when {
            element.editable || UiNodeAction.SET_TEXT in element.availableActions -> SemanticRole.INPUT
            element.checkable && className.contains("switch") -> SemanticRole.SWITCH
            element.checkable && className.contains("radio") -> SemanticRole.RADIO
            element.checkable -> SemanticRole.CHECKBOX
            className.contains("dialog") -> SemanticRole.DIALOG
            className.contains("toolbar") -> SemanticRole.TOOLBAR
            className.contains("webview") -> SemanticRole.WEB_CONTENT
            className.contains("recyclerview") || className.contains("listview") -> SemanticRole.LIST
            className.contains("tab") -> SemanticRole.TAB
            className.contains("imagebutton") -> SemanticRole.IMAGE_BUTTON
            className.contains("image") -> SemanticRole.IMAGE
            element.scrollable -> SemanticRole.SCROLL_CONTAINER
            className.contains("button") || element.clickable -> SemanticRole.BUTTON
            className.contains("text") -> SemanticRole.TEXT
            else -> SemanticRole.UNKNOWN
        }
    }

    private fun classify(nodes: List<SemanticNode>, interactive: List<SemanticNode>): String? = when {
        nodes.any { it.role == SemanticRole.DIALOG } -> "DIALOG"
        interactive.any { it.role == SemanticRole.INPUT } &&
            nodes.any { it.normalizedLabel?.contains("검색", ignoreCase = true) == true } -> "SEARCH"
        interactive.any { it.role == SemanticRole.LIST || it.role == SemanticRole.LIST_ITEM } -> "LIST"
        interactive.isNotEmpty() -> "APP_SCREEN"
        nodes.isNotEmpty() -> "READ_ONLY"
        else -> null
    }

    private fun quality(
        nodes: List<SemanticNode>,
        interactive: List<SemanticNode>,
        truncated: Boolean,
    ): ObservationQuality {
        val labeled = interactive.count { !it.normalizedLabel.isNullOrBlank() || !it.resourceId.isNullOrBlank() }
        val labeledRatio = if (interactive.isEmpty()) 0.0 else labeled.toDouble() / interactive.size
        val resourceRatio = if (interactive.isEmpty()) 0.0 else
            interactive.count { !it.resourceId.isNullOrBlank() }.toDouble() / interactive.size
        val roleRatio = if (interactive.isEmpty()) 0.0 else
            interactive.count { it.role != SemanticRole.UNKNOWN }.toDouble() / interactive.size
        val countScore = (interactive.size / 8.0).coerceIn(0.0, 1.0)
        var score = countScore * .25 + labeledRatio * .35 + resourceRatio * .15 + roleRatio * .25
        val reasons = mutableListOf<String>()
        if (interactive.isEmpty()) reasons += "no interactive accessibility nodes"
        if (labeledRatio < .5) reasons += "most interactive nodes are unlabeled"
        if (truncated) {
            score *= .65
            reasons += "accessibility tree truncated"
        }
        return ObservationQuality(score.coerceIn(0.0, 1.0), labeledRatio, reasons)
    }

    private fun fingerprint(
        packageName: String,
        windowTitle: String?,
        nodes: List<SemanticNode>,
    ): String {
        val canonical = buildString {
            append(packageName).append('|').append(stableToken(windowTitle.orEmpty())).append('\n')
            nodes.forEach { node ->
                append(node.depth).append('|').append(node.role).append('|')
                append(node.resourceId?.substringAfterLast('/').orEmpty()).append('|')
                append(stableToken(node.normalizedLabel.orEmpty())).append('|')
                append(node.clickable).append('|').append(node.editable).append('|')
                append(node.scrollable).append('|').append(node.selected).append('|')
                append(node.checked).append('\n')
            }
        }
        return sha256(canonical)
    }

    private fun normalizeLabel(value: String?): String? = value?.let {
        Normalizer.normalize(it, Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)
            .takeIf(String::isNotBlank)
    }

    private fun stableToken(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace(EMAIL, "<email>")
        .replace(NUMBER, "#")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(120)

    private fun isMeaningful(element: UiElement): Boolean = element.sensitive ||
        element.clickable || element.longClickable || element.editable || element.scrollable ||
        element.checkable || element.availableActions.isNotEmpty() ||
        !element.text.isNullOrBlank() || !element.contentDescription.isNullOrBlank() ||
        !element.hintText.isNullOrBlank() || !element.paneTitle.isNullOrBlank()

    private fun hasArea(bounds: ScreenBounds): Boolean =
        bounds.right > bounds.left && bounds.bottom > bounds.top

    private fun parentPath(path: String): String? =
        path.substringBeforeLast('.', missingDelimiterValue = "").takeIf(String::isNotBlank)

    private fun ancestors(path: String): Sequence<String> = generateSequence(parentPath(path), ::parentPath)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private val EMAIL = Regex("[\\p{L}\\p{Nd}._%+-]+@[\\p{L}\\p{Nd}.-]+")
    private val NUMBER = Regex("\\p{Nd}+")
    private const val SONJU_PACKAGE = "com.hwanghj09.sonju"
}

