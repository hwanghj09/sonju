package com.hwanghj09.sonju.agent

import com.hwanghj09.sonju.execution.ExecutionFailureReason
import com.hwanghj09.sonju.execution.ExecutionMethod
import com.hwanghj09.sonju.perception.AccessibilityScreenParser
import java.security.MessageDigest

enum class ActionType {
    OPEN_APP,
    OPEN_URL,
    /** Human checkpoint; never dispatched by the accessibility executor. */
    WAIT_FOR_USER,
    OPEN_WIFI_SETTINGS,
    OPEN_SOUND_SETTINGS,
    OPEN_ACCESSIBILITY_SETTINGS,
    OPEN_DISPLAY_SETTINGS,
    OPEN_DATE_SETTINGS,
    OPEN_CAMERA,
    OPEN_DIALER,
    OPEN_MESSAGES,
    START_TIMER,
    CLICK,
    CLICK_COORDINATE,
    SET_TEXT,
    SUBMIT_TEXT,
    SCROLL_DOWN,
    SCROLL_UP,
    SCROLL_LEFT,
    SCROLL_RIGHT,
    BACK,
    HOME,
    NOTIFICATIONS,
    QUICK_SETTINGS,
    WAIT,
    FINISH,
}

/**
 * Proof that the current Settings screen was opened by Sonju through a reviewed system intent.
 * A visible title alone is not sufficient because app-provided labels can appear inside Settings.
 */
enum class TrustedSettingsRoute {
    WIFI,
    SOUND,
    ACCESSIBILITY,
    DISPLAY,
    DATE_TIME,
}

enum class PlanSource {
    LOCAL_RULE,
    APP_ADAPTER,
    SKILL_FAST_PATH,
    OPENAI_STRUCTURE,
    OPENAI_SEMANTIC_MAP,
}

/** Accessibility actions exposed by a live node and safe to share with the planner. */
enum class UiNodeAction {
    CLICK,
    LONG_CLICK,
    SET_TEXT,
    IME_ENTER,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    SCROLL_UP,
    SCROLL_DOWN,
    SCROLL_LEFT,
    SCROLL_RIGHT,
    FOCUS,
    CLEAR_FOCUS,
    EXPAND,
    COLLAPSE,
    DISMISS,
}

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    BLOCKED,
}

data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class UiElement(
    val path: String,
    val viewId: String?,
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val bounds: ScreenBounds,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val sensitive: Boolean,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val stateDescription: String? = null,
    val hintText: String? = null,
    val paneTitle: String? = null,
    val tooltipText: String? = null,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val accessibilityFocused: Boolean = false,
    val longClickable: Boolean = false,
    val dismissable: Boolean = false,
    val heading: Boolean = false,
    val availableActions: Set<UiNodeAction> = emptySet(),
) {
    fun compactLine(): String {
        val safeText = if (sensitive) "[민감정보 가림]" else text.orEmpty().take(if (editable) 4_000 else 80)
        val safeDescription = if (sensitive) "" else contentDescription.orEmpty().take(80)
        return buildString {
            append("path=").append(path)
            append(" class=").append(className.substringAfterLast('.'))
            viewId?.takeIf { !sensitive && it.isNotBlank() }
                ?.let { append(" id=").append(it.takeLast(70)) }
            if (safeText.isNotBlank()) append(" text=").append(safeText.replace('\n', ' '))
            if (safeDescription.isNotBlank()) append(" desc=").append(safeDescription.replace('\n', ' '))
            append(" bounds=").append(bounds.left).append(',').append(bounds.top)
                .append(',').append(bounds.right).append(',').append(bounds.bottom)
            if (clickable) append(" clickable")
            if (editable) append(" editable")
            if (scrollable) append(" scrollable")
            if (checkable) append(" checkable checked=").append(checked)
            if (selected) append(" selected")
            if (!sensitive && !stateDescription.isNullOrBlank()) {
                append(" state=").append(stateDescription.take(60).replace('\n', ' '))
            }
            if (!sensitive && !hintText.isNullOrBlank()) {
                append(" hint=").append(hintText.take(60).replace('\n', ' '))
            }
            if (!sensitive && !paneTitle.isNullOrBlank()) {
                append(" pane=").append(paneTitle.take(60).replace('\n', ' '))
            }
            if (focusable) append(" focusable")
            if (focused) append(" focused")
            if (accessibilityFocused) append(" accessibilityFocused")
            if (longClickable) append(" longClickable")
            if (dismissable) append(" dismissable")
            if (heading) append(" heading")
            if (availableActions.isNotEmpty()) {
                append(" actions=").append(availableActions.joinToString(",") { it.name })
            }
            if (!enabled) append(" disabled")
        }
    }
}

data class UiSnapshot(
    val packageName: String,
    val windowTitle: String?,
    val windowId: Int = -1,
    val epoch: Long,
    val elements: List<UiElement>,
    val treeTruncated: Boolean = false,
    val trustedSettingsRoute: TrustedSettingsRoute? = null,
    val windowBounds: ScreenBounds? = null,
    /** On-device OCR for read-only page evidence. Never exposed as executable nodes or persisted. */
    val localReadOnlyText: List<String> = emptyList(),
    /** Local category only, detected before credential text is redacted. */
    val userIntervention: UserIntervention.Kind? = null,
) {
    fun hasSemanticSignal(): Boolean =
        elements.count { element ->
            element.visible && !element.sensitive &&
                (element.clickable || element.editable || element.scrollable ||
                    element.checkable ||
                    element.longClickable || element.dismissable ||
                    element.availableActions.isNotEmpty() ||
                    !element.text.isNullOrBlank() ||
                    !element.contentDescription.isNullOrBlank() ||
                    !element.hintText.isNullOrBlank() || !element.paneTitle.isNullOrBlank())
        } >= 4

    fun compactText(maxElements: Int = 90): String = buildString {
        appendLine("package=${packageName.take(120)}")
        windowTitle?.takeIf { it.isNotBlank() }?.let { appendLine("window=${it.take(120)}") }
        appendLine("epoch=$epoch")
        elements
            .asSequence()
            .filter { element ->
                element.visible && !element.sensitive &&
                    (element.clickable || element.editable || element.scrollable || element.checkable ||
                        element.longClickable || element.dismissable ||
                        element.availableActions.isNotEmpty() ||
                        !element.text.isNullOrBlank() ||
                        !element.contentDescription.isNullOrBlank() ||
                        !element.stateDescription.isNullOrBlank() ||
                        !element.hintText.isNullOrBlank() || !element.paneTitle.isNullOrBlank())
            }
            .sortedByDescending { element ->
                when {
                    element.checkable -> 6
                    element.editable || UiNodeAction.SET_TEXT in element.availableActions -> 5
                    element.clickable || UiNodeAction.CLICK in element.availableActions -> 4
                    element.scrollable || element.availableActions.any { it.name.startsWith("SCROLL") } -> 3
                    !element.contentDescription.isNullOrBlank() || !element.hintText.isNullOrBlank() -> 2
                    else -> 1
                }
            }
            .take(maxElements)
            .forEach { appendLine(it.compactLine()) }
    }

    fun screenFingerprint(): String {
        val canonical = buildString {
            append(packageName).append('|').append(windowTitle.orEmpty()).append('|')
                .append(windowId).append('|')
                .append(windowBounds?.let { "${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty())
                .append('|')
                .append(treeTruncated).append('|').append(trustedSettingsRoute?.name.orEmpty())
                .append('\n')
            elements.asSequence()
                .filter { it.visible }
                .sortedBy { it.path }
                .forEach { element ->
                    append(element.path).append('|')
                    append(element.viewId.orEmpty()).append('|')
                    append(element.className).append('|')
                    append(if (element.sensitive) "[redacted]" else element.text.orEmpty()).append('|')
                    append(
                        if (element.sensitive) "[redacted]" else element.contentDescription.orEmpty(),
                    ).append('|')
                    append(
                        if (element.sensitive) "[redacted]" else element.stateDescription.orEmpty(),
                    ).append('|')
                    append(element.bounds.left).append(',').append(element.bounds.top).append(',')
                    append(element.bounds.right).append(',').append(element.bounds.bottom).append('|')
                    append(element.clickable).append('|').append(element.editable).append('|')
                    append(element.scrollable).append('|').append(element.enabled).append('|')
                    append(element.sensitive).append('|').append(element.checkable).append('|')
                    append(element.checked).append('|').append(element.selected).append('|')
                    append(element.hintText.orEmpty()).append('|')
                    append(element.paneTitle.orEmpty()).append('|')
                    append(element.tooltipText.orEmpty()).append('|')
                    append(element.focusable).append('|').append(element.focused).append('|')
                    append(element.accessibilityFocused).append('|')
                    append(element.longClickable).append('|').append(element.dismissable).append('|')
                    append(element.heading).append('|')
                    append(element.availableActions.sortedBy { it.name }.joinToString(",") { it.name })
                        .append('\n')
                }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    fun hasSameContentAs(other: UiSnapshot): Boolean =
        packageName != "unknown" && other.packageName != "unknown" &&
            screenFingerprint() == other.screenFingerprint()

    /** Content comparison for postconditions; capability provenance is not a visible UI change. */
    fun hasSameObservableContentAs(other: UiSnapshot): Boolean =
        packageName != "unknown" && other.packageName != "unknown" &&
            copy(trustedSettingsRoute = null).screenFingerprint() ==
            other.copy(trustedSettingsRoute = null).screenFingerprint()

    /** Layout animations and focus do not change any displayed value or control identity. */
    fun hasSameContentIgnoringLayoutAs(other: UiSnapshot): Boolean {
        fun content(snapshot: UiSnapshot) = snapshot.copy(
            windowBounds = null,
            elements = snapshot.elements.map { it.copy(bounds = ScreenBounds(0, 0, 0, 0),
                focused = false, accessibilityFocused = false) },
        )
        return content(this).hasSameObservableContentAs(content(other))
    }

    /**
     * Exact executable revision used at the final action sink. [epoch] is a monotonic revision of
     * non-Sonju application accessibility events; the service deliberately excludes its own
     * confirmation overlay events. Requiring both values prevents an A -> B -> A ABA transition
     * from becoming executable merely because the semantic content returned to the old shape.
     */
    fun hasSameRevisionAs(other: UiSnapshot): Boolean =
        epoch == other.epoch && hasSameContentAs(other)

    /** Screenshot authorization tolerates animation geometry drift, never content or epoch drift. */
    fun hasSameScreenshotSecurityContextAs(other: UiSnapshot): Boolean {
        if (epoch != other.epoch || packageName == "unknown" || other.packageName == "unknown") {
            return false
        }
        fun UiSnapshot.withoutGeometry() = copy(
            trustedSettingsRoute = null,
            windowBounds = null,
            elements = elements.map { element ->
                element.copy(bounds = ScreenBounds(0, 0, 0, 0))
            },
        )
        return withoutGeometry().screenFingerprint() == other.withoutGeometry().screenFingerprint()
    }

    /** Allows text entry after unrelated dynamic content changes, but only into the same target. */
    fun hasSameEditableTargetAs(other: UiSnapshot, path: String): Boolean {
        if (packageName == "unknown" || packageName != other.packageName ||
            windowId != other.windowId || treeTruncated || other.treeTruncated
        ) return false
        val expected = elements.singleOrNull {
            it.path == path && it.editable && it.enabled && it.visible && !it.sensitive
        } ?: return false
        val live = other.elements.singleOrNull {
            it.path == path && it.editable && it.enabled && it.visible && !it.sensitive
        } ?: return false
        val stableLabels = listOf(
            expected.contentDescription,
            expected.hintText,
            expected.paneTitle,
            expected.tooltipText,
        )
        val liveLabels = listOf(
            live.contentDescription,
            live.hintText,
            live.paneTitle,
            live.tooltipText,
        )
        val strongIdentity =
            (!expected.viewId.isNullOrBlank() && expected.viewId == live.viewId) ||
                (stableLabels.any { !it.isNullOrBlank() } && stableLabels == liveLabels)
        val uniqueFallback = elements.count {
            it.editable && it.enabled && it.visible && !it.sensitive
        } == 1 && other.elements.count {
            it.editable && it.enabled && it.visible && !it.sensitive
        } == 1
        return expected.className == live.className &&
            (expected.bounds == live.bounds || hasSameContentIgnoringLayoutAs(other)) &&
            expected.text == live.text && (strongIdentity || uniqueFallback)
    }

    /** Stable semantic template used by skill matching; it does not persist live node objects. */
    fun semanticTemplateFingerprint(): String = AccessibilityScreenParser.parse(this).fingerprint

    companion object {
        fun empty(epoch: Long = 0L) = UiSnapshot(
            packageName = "unknown",
            windowTitle = null,
            epoch = epoch,
            elements = emptyList(),
        )
    }
}

data class ResolvedClick(val clickablePath: String)

data class AgentAction(
    val type: ActionType,
    val description: String,
    val target: String? = null,
    val value: String? = null,
    val waitMillis: Long = 0,
    /** Normalized display coordinate used only by [ActionType.CLICK_COORDINATE]. */
    val xRatio: Double? = null,
    /** Normalized display coordinate used only by [ActionType.CLICK_COORDINATE]. */
    val yRatio: Double? = null,
)

data class AgentPlan(
    val goal: String,
    val summary: String,
    val modelRisk: RiskLevel,
    val confidence: Double,
    val actions: List<AgentAction>,
    val source: PlanSource,
    val continueAfterAction: Boolean = false,
    val goalCompleted: Boolean = false,
    /** App that should be running to satisfy [goal]. May be revised after observations. */
    val targetApp: String = "",
    /** Page or feature that should be reached or operated. May be revised after observations. */
    val targetSurface: String = "",
    /** Full tool set expected for the route, not just the next executable action. */
    val requiredTools: Set<ActionType> = emptySet(),
    /** High-level route. Execution still happens one observed action at a time. */
    val strategy: List<String> = emptyList(),
    /** Observable conditions that prove the immutable final goal is complete. */
    val successCriteria: List<String> = emptyList(),
    /** Why mutable plan fields changed after the latest observation or failure. */
    val revisionReason: String = "",
    /** Assigned locally. Model output cannot roll the revision backwards. */
    val revision: Int = 0,
    /** Set locally only when screenshot/visual grounding was actually used. */
    val visualFallback: Boolean = false,
    /** Present only for a locally retrieved reusable skill. */
    val skillId: String? = null,
    val skillVersion: Int? = null,
    /** Locally learned semantic postcondition; never supplied by a remote planner. */
    val expectedScreenFingerprint: String? = null,
    /** Current-screen evidence proposed by the model, checked locally before accepting completion. */
    val goalChecks: List<GoalCheck> = emptyList(),
    /** Exact image digest, assigned by local capture code; pixels are never stored in skills. */
    val visualFrameHash: String? = null,
    val visualFrameVerified: Boolean = false,
    /** Untrusted suggestion; the runtime validates it against the offered local catalog. */
    val skillReuse: com.hwanghj09.sonju.skill.SkillReuseSuggestion? = null,
)

data class GoalCheck(val selector: String, val text: String? = null, val checked: Boolean? = null) {
    fun matches(snapshot: UiSnapshot): Boolean {
        val node = resolveNode(snapshot) ?: return false
        return (text == null || listOfNotNull(node.text, node.contentDescription, node.stateDescription)
            .any { normalizeGoalText(it) == normalizeGoalText(text) }) &&
            (checked == null || node.checkable && node.checked == checked)
    }

    fun resolveNode(snapshot: UiSnapshot): UiElement? {
        if (selector.isBlank()) return null
        val value = selector.substringAfter('=', selector)
        val field = selector.substringBefore('=', "")
        return snapshot.elements.filter { it.visible && !it.sensitive &&
            when (field) {
                "path" -> value == it.path
                "id" -> value == it.viewId
                "text" -> it.text?.let(::normalizeGoalText) == normalizeGoalText(value)
                "desc" -> it.contentDescription?.let(::normalizeGoalText) == normalizeGoalText(value)
                "hint" -> it.hintText?.let(::normalizeGoalText) == normalizeGoalText(value)
                else -> selector in listOf(it.path, it.viewId) ||
                    listOfNotNull(it.text, it.contentDescription, it.hintText)
                        .any { label -> normalizeGoalText(label) == normalizeGoalText(selector) }
            }
        }.singleOrNull()
    }
}

/** Ignore invisible formatting and collapsed whitespace, while preserving every value and symbol. */
fun normalizeGoalText(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
    .replace(Regex("\\p{Cf}"), "").replace(Regex("\\s+"), " ").trim()

enum class SafetyDecision {
    ALLOW,
    REQUIRE_CONFIRMATION,
    BLOCK,
}

data class SafetyAssessment(
    val decision: SafetyDecision,
    val level: RiskLevel,
    val reason: String,
)

data class ExecutionResult(
    val success: Boolean,
    val message: String,
    val completedSteps: Int,
    val failureReason: ExecutionFailureReason? = null,
    val method: ExecutionMethod? = null,
    val beforeFingerprint: String? = null,
    val afterFingerprint: String? = null,
    val postconditionSatisfied: Boolean? = null,
    val goalVerified: Boolean = false,
    val afterVisualFrameHash: String? = null,
)

fun visualFrameHash(jpegBase64: String): String = MessageDigest.getInstance("SHA-256")
    .digest(jpegBase64.toByteArray(Charsets.US_ASCII))
    .joinToString("") { "%02x".format(it) }

fun ActionType.displayName(): String = when (this) {
    ActionType.OPEN_URL -> "공식 웹페이지 열기"
    ActionType.WAIT_FOR_USER -> "사용자 확인 기다리기"
    ActionType.OPEN_APP -> "앱 열기"
    ActionType.OPEN_WIFI_SETTINGS -> "와이파이 설정 열기"
    ActionType.OPEN_SOUND_SETTINGS -> "소리 설정 열기"
    ActionType.OPEN_ACCESSIBILITY_SETTINGS -> "접근성 설정 열기"
    ActionType.OPEN_DISPLAY_SETTINGS -> "디스플레이 설정 열기"
    ActionType.OPEN_DATE_SETTINGS -> "날짜 및 시간 설정 열기"
    ActionType.OPEN_CAMERA -> "카메라 열기"
    ActionType.OPEN_DIALER -> "전화 화면 열기"
    ActionType.OPEN_MESSAGES -> "문자 화면 열기"
    ActionType.START_TIMER -> "타이머 시작"
    ActionType.CLICK -> "버튼 누르기"
    ActionType.CLICK_COORDINATE -> "화면 좌표 누르기"
    ActionType.SET_TEXT -> "글자 입력하기"
    ActionType.SUBMIT_TEXT -> "입력한 검색어 제출하기"
    ActionType.SCROLL_DOWN -> "화면 아래로 내리기"
    ActionType.SCROLL_UP -> "화면 위로 올리기"
    ActionType.SCROLL_LEFT -> "화면 왼쪽으로 넘기기"
    ActionType.SCROLL_RIGHT -> "화면 오른쪽으로 넘기기"
    ActionType.BACK -> "이전 화면으로 가기"
    ActionType.HOME -> "홈 화면으로 가기"
    ActionType.NOTIFICATIONS -> "알림창 열기"
    ActionType.QUICK_SETTINGS -> "빠른 설정 열기"
    ActionType.WAIT -> "화면 기다리기"
    ActionType.FINISH -> "마치기"
}
