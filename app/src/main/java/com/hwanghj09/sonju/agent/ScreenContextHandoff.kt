package com.hwanghj09.sonju.agent

import java.text.Normalizer
import kotlin.math.abs

/**
 * Rules for handing an immutable accessibility snapshot across Sonju's own activity transition.
 * The handoff never carries live AccessibilityNodeInfo objects.
 */
object ScreenContextHandoff {
    /** Single-character keypad and symbol labels are meaningful exact matches too. */
    fun labelsCompatible(expected: List<String>, live: List<String>): Boolean {
        if (expected.isEmpty()) return true
        fun normalized(values: List<String>) = values.map {
            Normalizer.normalize(it, Normalizer.Form.NFKC).trim().lowercase()
        }.filter(String::isNotEmpty).toSet()
        val expectedLabels = normalized(expected)
        val liveLabels = normalized(live)
        if (expectedLabels.intersect(liveLabels).isNotEmpty()) return true
        fun tokens(labels: Set<String>) = labels.flatMap {
            it.split(Regex("[^\\p{L}\\p{Nd}]+"))
        }.filter { it.length >= 2 }.toSet()
        return tokens(expectedLabels).intersect(tokens(liveLabels)).isNotEmpty()
    }

    fun shouldRetryCapture(
        snapshot: UiSnapshot?,
        captureEpoch: Long,
        currentEpoch: Long,
        requireSemanticSignal: Boolean,
        attempt: Int,
        maxRetries: Int,
    ): Boolean = attempt < maxRetries && (
        snapshot == null || captureEpoch != currentEpoch ||
            requireSemanticSignal && !snapshot.hasSemanticSignal()
        )

    /**
     * A rendered shell can briefly look stable before WebView or Compose semantics arrive.
     * Retry a no-plan result only within the caller's small loading budget.
     */
    fun shouldWaitForStableReplan(
        attempt: Int,
        maxRetries: Int,
    ): Boolean = attempt < maxRetries

    /** Extend the bounded grace only while the accessibility tree exposes active loading UI. */
    fun hasVisibleLoadingIndicator(snapshot: UiSnapshot): Boolean = snapshot.elements.any { element ->
        if (!element.visible || element.sensitive) return@any false
        val simpleClass = element.className.substringAfterLast('.').lowercase()
        val compactId = compact(element.viewId.orEmpty())
        val compactLabels = sequenceOf(
            element.text,
            element.contentDescription,
            element.stateDescription,
            element.hintText,
            element.paneTitle,
            element.tooltipText,
        ).filterNotNull().map(::compact)
        simpleClass.contains("progressbar") || simpleClass.contains("spinner") ||
            !simpleClass.endsWith("layout") && !simpleClass.endsWith("group") &&
                LOADING_ID_TERMS.any(compactId::contains) ||
            compactLabels.any { label -> LOADING_LABEL_TERMS.any(label::contains) }
    }

    /** A renderer can expose only native toolbars, without exposing the content it draws. */
    fun hasUnobservedRenderedContent(snapshot: UiSnapshot): Boolean = unobservedRenderedSurfaces(snapshot).isNotEmpty()

    fun unobservedRenderedSurfaces(snapshot: UiSnapshot): List<UiElement> {
        val window = snapshot.windowBounds ?: return emptyList()
        val windowArea = (window.right - window.left).toLong() * (window.bottom - window.top)
        if (windowArea <= 0) return emptyList()
        return snapshot.elements.filter { surface ->
            if (!surface.visible || !surface.enabled || surface.sensitive ||
                listOf("SurfaceView", "TextureView", "WebView").none(surface.className::endsWith)) return@filter false
            val bounds = surface.bounds
            val height = bounds.bottom - bounds.top
            if (bounds.left < window.left || bounds.right > window.right || bounds.top < window.top ||
                bounds.bottom > window.bottom ||
                (bounds.right - bounds.left).toLong() * height < windowArea / 4) return@filter false
            // Browser chrome around the edges is not evidence that the page body was read.
            val top = bounds.top + height * 15 / 100
            val bottom = bounds.bottom - height * 15 / 100
            snapshot.elements.none { node ->
                node !== surface && node.visible && !node.sensitive &&
                    node.bounds.centerX in bounds.left..bounds.right && node.bounds.centerY in top..bottom &&
                    listOfNotNull(node.text, node.contentDescription, node.hintText).any(String::isNotBlank)
            }
        }
    }

    fun isReusable(
        snapshot: UiSnapshot,
        ownPackageName: String,
        nowElapsedRealtime: Long,
        capturedAtElapsedRealtime: Long,
        ttlMillis: Long,
    ): Boolean =
        snapshot.packageName != "unknown" &&
            snapshot.packageName != ownPackageName &&
            ContextLifetime.isFresh(
                nowElapsedRealtime,
                capturedAtElapsedRealtime,
                ttlMillis,
            )

    /**
     * Planning may reuse a very recent observation even if an animation emitted another event.
     * Execution still performs a fresh semantic rebind, so a stale target cannot be acted on.
     */
    fun isRecentPlanningSnapshot(
        snapshot: UiSnapshot,
        activePackageName: String,
        activeWindowId: Int,
        nowElapsedRealtime: Long,
        capturedAtElapsedRealtime: Long,
        ttlMillis: Long,
    ): Boolean =
        !snapshot.treeTruncated &&
            snapshot.packageName == activePackageName &&
            (snapshot.windowId == -1 || activeWindowId == -1 ||
                snapshot.windowId == activeWindowId) &&
            ContextLifetime.isFresh(
                nowElapsedRealtime,
                capturedAtElapsedRealtime,
                ttlMillis,
            )

    /**
     * Returning from Sonju creates a new accessibility epoch. Rebind only when every observable
     * screen detail is unchanged; any content or window change keeps the verified action blocked.
     */
    fun resumeOnUnchangedScreen(expected: UiSnapshot, live: UiSnapshot): UiSnapshot? =
        live.takeIf { expected.hasSameContentAs(it) }

    /**
     * Search suggestions and other live content may refresh while Sonju's activity closes. Text
     * input remains safe only when the previously verified editable node itself is unchanged.
     */
    fun resumeForTextInput(
        expected: UiSnapshot,
        live: UiSnapshot,
        editablePath: String?,
    ): UiSnapshot? = editablePath?.let { path ->
        live.takeIf { expected.hasSameEditableTargetAs(it, path) }
    }

    /** Dynamic maps/lists may refresh unrelated content while the verified button stays put. */
    fun resumeForClick(
        expected: UiSnapshot,
        live: UiSnapshot,
        clickPath: String?,
    ): UiSnapshot? = clickPath?.let { path ->
        live.takeIf { relocateClickTarget(expected, it, path) == path }
    }

    /** A changing list may still scroll when its verified container itself is unchanged. */
    fun resumeForScroll(
        expected: UiSnapshot,
        live: UiSnapshot,
        scrollPath: String?,
    ): UiSnapshot? {
        if (scrollPath.isNullOrBlank() || expected.packageName != live.packageName ||
            expected.windowId != live.windowId || expected.treeTruncated || live.treeTruncated
        ) return null
        val expectedTarget = expected.elements.singleOrNull { element ->
            element.path == scrollPath && element.visible && element.enabled &&
                element.scrollable && !element.sensitive
        } ?: return null
        val liveTarget = live.elements.singleOrNull { element ->
            element.path == scrollPath && element.visible && element.enabled &&
                element.scrollable && !element.sensitive
        } ?: return null
        return live.takeIf {
            expectedTarget.className == liveTarget.className &&
                boundsRemainCompatible(expectedTarget, liveTarget)
        }
    }

    /** Ignore unrelated clocks and counters that change while a scroll action has no effect. */
    fun hasObservedScrollEffect(
        before: UiSnapshot,
        after: UiSnapshot,
        scrollPath: String,
    ): Boolean {
        if (before.packageName != after.packageName || before.windowId != after.windowId) return false
        fun content(snapshot: UiSnapshot) = snapshot.elements.asSequence()
            .filter { element ->
                element.visible && !element.sensitive && element.path != scrollPath &&
                    element.path.startsWith("$scrollPath.")
            }
            .associateBy(UiElement::path)
        val beforeContent = content(before)
        val afterContent = content(after)
        val commonPaths = beforeContent.keys.intersect(afterContent.keys)
        if (commonPaths.any { path ->
                val oldBounds = beforeContent.getValue(path).bounds
                val newBounds = afterContent.getValue(path).bounds
                abs(oldBounds.top - newBounds.top) >= MIN_SCROLL_MOVEMENT_PIXELS ||
                    abs(oldBounds.bottom - newBounds.bottom) >= MIN_SCROLL_MOVEMENT_PIXELS
            }
        ) return true
        val changedLabels = commonPaths.count { path ->
            semanticLabel(beforeContent.getValue(path)) != semanticLabel(afterContent.getValue(path))
        }
        val replacedPaths = (beforeContent.keys union afterContent.keys).size - commonPaths.size
        return changedLabels >= 2 || replacedPaths >= 2
    }

    /**
     * Dynamic lists may insert nodes without changing the visible control. Rebind a click only
     * when one live clickable surface keeps the same class and descendant semantics. A layout
     * animation may move it farther only if every observed content/state value stays unchanged.
     */
    fun relocateClickTarget(
        expected: UiSnapshot,
        live: UiSnapshot,
        clickPath: String?,
    ): String? {
        if (clickPath.isNullOrBlank() || expected.packageName != live.packageName ||
            expected.windowId != live.windowId || expected.treeTruncated || live.treeTruncated
        ) return null
        val target = expected.elements.singleOrNull { element ->
            element.path == clickPath && element.visible && element.enabled && !element.sensitive &&
                (element.clickable || UiNodeAction.CLICK in element.availableActions)
        } ?: return null
        // An explicit verifier-bound path is still exact on the same complete revision, even
        // when two blank clickable wrappers have identical descendant labels.
        if (expected.epoch == live.epoch && expected.hasSameContentIgnoringLayoutAs(live) &&
            live.elements.singleOrNull { it.path == clickPath }?.bounds == target.bounds) return clickPath
        val expectedLabels = descendantLabels(expected, target.path)
        val expectedViewId = target.viewId?.takeIf(String::isNotBlank)
        if (expectedLabels.isEmpty() && expectedViewId == null) return null
        val onlyLayoutChanged by lazy { expected.hasSameContentIgnoringLayoutAs(live) }

        return live.elements.asSequence()
            .filter { element ->
                element.visible && element.enabled && !element.sensitive &&
                    (element.clickable || UiNodeAction.CLICK in element.availableActions) &&
                    element.className == target.className && element.checkable == target.checkable &&
                    (!target.checkable || element.checked == target.checked)
            }
            .filter { candidate ->
                val sameResource = expectedViewId != null && candidate.viewId == expectedViewId
                val liveLabels = descendantLabels(live, candidate.path)
                val sameMeaning = if (expectedLabels.isEmpty()) sameResource
                    else expectedLabels.intersect(liveLabels).isNotEmpty()
                sameMeaning && (boundsRemainCompatible(target, candidate) ||
                    expectedLabels == liveLabels && (expectedLabels.isNotEmpty() || sameResource) &&
                        onlyLayoutChanged)
            }
            .map(UiElement::path)
            .distinct()
            .toList()
            .singleOrNull()
    }

    private fun descendantLabels(snapshot: UiSnapshot, path: String): Set<String> =
        snapshot.elements.asSequence()
            .filter { element ->
                !element.sensitive && (element.path == path || element.path.startsWith("$path."))
            }
            .flatMap { element ->
                sequenceOf(
                    element.text,
                    element.contentDescription,
                    element.hintText,
                    element.paneTitle,
                    element.tooltipText,
                ).filterNotNull()
            }
            .map(::compact)
            .filter { it.length >= 2 }
            .toSet()

    private fun semanticLabel(element: UiElement): String = listOfNotNull(
        element.text,
        element.contentDescription,
        element.hintText,
        element.paneTitle,
        element.tooltipText,
    ).joinToString(" ").let(::compact)

    private fun boundsRemainCompatible(expected: UiElement, live: UiElement): Boolean {
        val leftShift = abs(expected.bounds.left - live.bounds.left)
        val topShift = abs(expected.bounds.top - live.bounds.top)
        val widthShift = abs(
            (expected.bounds.right - expected.bounds.left) -
                (live.bounds.right - live.bounds.left),
        )
        val heightShift = abs(
            (expected.bounds.bottom - expected.bounds.top) -
                (live.bounds.bottom - live.bounds.top),
        )
        return leftShift <= MAX_RELOCATION_PIXELS && topShift <= MAX_RELOCATION_PIXELS &&
            widthShift <= MAX_RELOCATION_PIXELS && heightShift <= MAX_RELOCATION_PIXELS
    }

    private fun compact(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")

    private const val MAX_RELOCATION_PIXELS = 24
    private const val MIN_SCROLL_MOVEMENT_PIXELS = 16
    private val LOADING_ID_TERMS = setOf("loading", "progress", "spinner")
    private val LOADING_LABEL_TERMS = setOf(
        "로딩", "불러오는중", "처리중", "잠시만", "loading", "pleasewait",
    )
}
