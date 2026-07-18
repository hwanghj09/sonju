package com.hwanghj09.sonju.agent

import java.security.MessageDigest

enum class ActionType {
    OPEN_APP,
    OPEN_WIFI_SETTINGS,
    OPEN_SOUND_SETTINGS,
    OPEN_ACCESSIBILITY_SETTINGS,
    OPEN_DISPLAY_SETTINGS,
    OPEN_DATE_SETTINGS,
    OPEN_CAMERA,
    OPEN_DIALER,
    OPEN_MESSAGES,
    CLICK,
    SET_TEXT,
    SCROLL_DOWN,
    SCROLL_UP,
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
    GEMINI_STRUCTURE,
    GEMINI_VISION,
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
) {
    fun compactLine(): String {
        val safeText = if (sensitive) "[민감정보 가림]" else text.orEmpty().take(80)
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
            if (!enabled) append(" disabled")
        }
    }
}

data class UiSnapshot(
    val packageName: String,
    val windowTitle: String?,
    val epoch: Long,
    val elements: List<UiElement>,
    val treeTruncated: Boolean = false,
    val trustedSettingsRoute: TrustedSettingsRoute? = null,
) {
    fun hasSemanticSignal(): Boolean =
        elements.count { element ->
            element.visible && !element.sensitive &&
                (element.clickable || element.editable || element.scrollable ||
                    element.checkable ||
                    !element.text.isNullOrBlank() || !element.contentDescription.isNullOrBlank())
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
                        !element.text.isNullOrBlank() ||
                        !element.contentDescription.isNullOrBlank() ||
                        !element.stateDescription.isNullOrBlank())
            }
            .sortedByDescending { element ->
                when {
                    element.checkable -> 6
                    element.editable -> 5
                    element.clickable -> 4
                    element.scrollable -> 3
                    !element.contentDescription.isNullOrBlank() -> 2
                    else -> 1
                }
            }
            .take(maxElements)
            .forEach { appendLine(it.compactLine()) }
    }

    fun screenFingerprint(): String {
        val canonical = buildString {
            append(packageName).append('|').append(windowTitle.orEmpty()).append('|')
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
                    append(element.checked).append('|').append(element.selected).append('\n')
                }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        fun empty(epoch: Long = System.currentTimeMillis()) = UiSnapshot(
            packageName = "unknown",
            windowTitle = null,
            epoch = epoch,
            elements = emptyList(),
        )
    }
}

/** Exact, deterministic click identity produced by [SafetyPolicy] and rechecked at the sink. */
data class ValidatedClick(
    val clickablePath: String,
    val statePath: String,
    val stateViewId: String,
    val currentState: Boolean,
    val desiredState: Boolean,
)

data class AgentAction(
    val type: ActionType,
    val description: String,
    val target: String? = null,
    val value: String? = null,
    val waitMillis: Long = 0,
)

data class AgentPlan(
    val goal: String,
    val summary: String,
    val modelRisk: RiskLevel,
    val confidence: Double,
    val actions: List<AgentAction>,
    val source: PlanSource,
)

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
)

fun ActionType.displayName(): String = when (this) {
    ActionType.OPEN_APP -> "앱 열기"
    ActionType.OPEN_WIFI_SETTINGS -> "와이파이 설정 열기"
    ActionType.OPEN_SOUND_SETTINGS -> "소리 설정 열기"
    ActionType.OPEN_ACCESSIBILITY_SETTINGS -> "접근성 설정 열기"
    ActionType.OPEN_DISPLAY_SETTINGS -> "디스플레이 설정 열기"
    ActionType.OPEN_DATE_SETTINGS -> "날짜 및 시간 설정 열기"
    ActionType.OPEN_CAMERA -> "카메라 열기"
    ActionType.OPEN_DIALER -> "전화 화면 열기"
    ActionType.OPEN_MESSAGES -> "문자 화면 열기"
    ActionType.CLICK -> "버튼 누르기"
    ActionType.SET_TEXT -> "글자 입력하기"
    ActionType.SCROLL_DOWN -> "화면 아래로 내리기"
    ActionType.SCROLL_UP -> "화면 위로 올리기"
    ActionType.BACK -> "이전 화면으로 가기"
    ActionType.HOME -> "홈 화면으로 가기"
    ActionType.NOTIFICATIONS -> "알림창 열기"
    ActionType.QUICK_SETTINGS -> "빠른 설정 열기"
    ActionType.WAIT -> "화면 기다리기"
    ActionType.FINISH -> "마치기"
}
