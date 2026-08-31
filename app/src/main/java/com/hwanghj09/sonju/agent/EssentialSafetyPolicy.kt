package com.hwanghj09.sonju.agent

import java.text.Normalizer

/**
 * Small user-facing safety boundary for the autonomous engine.
 *
 * Reliability checks (fresh screen, valid coordinates, unique nodes, bounded retries) stay in the
 * executor/session. This policy only adds friction around payment commits and personal or secret
 * data entry; ordinary navigation, app opening, scrolling and text search remain autonomous.
 */
object EssentialSafetyPolicy {
    fun evaluate(command: String, plan: AgentPlan, snapshot: UiSnapshot): SafetyAssessment {
        val actions = plan.actions.filterNot { it.type == ActionType.FINISH }
        if (plan.goalCompleted && actions.isEmpty()) {
            return SafetyAssessment(SafetyDecision.ALLOW, RiskLevel.LOW, "최종 목표가 화면에서 확인됐습니다.")
        }
        if (actions.size != 1) {
            return SafetyAssessment(
                SafetyDecision.BLOCK,
                RiskLevel.BLOCKED,
                "화면을 관찰한 뒤 한 번에 하나의 도구만 실행할 수 있습니다.",
            )
        }
        val action = actions.single()
        validateToolCall(action, snapshot)?.let { return it }

        val context = listOfNotNull(
            command,
            plan.targetSurface,
            action.description,
            action.target,
        ).joinToString(" ")
        val paymentCommit = action.type in setOf(
            ActionType.CLICK,
            ActionType.CLICK_COORDINATE,
            ActionType.SUBMIT_TEXT,
        ) &&
            isPaymentCommit(command, plan, action)
        val personalInput = action.type in setOf(ActionType.SET_TEXT, ActionType.SUBMIT_TEXT) && (
            containsAny(context, personalDataTerms) ||
                looksLikePersonalValue(action.value.orEmpty()) ||
                matchingEditableIsSensitive(action, snapshot)
            )
        val opaqueTapOnSensitiveScreen = action.type == ActionType.CLICK_COORDINATE &&
            snapshot.elements.any { it.visible && it.sensitive }

        return if (paymentCommit || personalInput || opaqueTapOnSensitiveScreen) {
            val reason = when {
                paymentCommit -> "결제·송금·구매를 최종 확정할 수 있는 동작이라 실행 직전에 확인이 필요합니다."
                personalInput -> "개인정보나 인증정보를 입력할 수 있는 동작이라 입력 내용과 대상을 확인해 주세요."
                else -> "개인정보가 포함된 화면의 좌표를 직접 누르기 전에 위치를 확인해 주세요."
            }
            SafetyAssessment(SafetyDecision.REQUIRE_CONFIRMATION, RiskLevel.MEDIUM, reason)
        } else {
            SafetyAssessment(
                SafetyDecision.ALLOW,
                RiskLevel.LOW,
                "사용자가 시작한 작업의 일반 화면 조작입니다.",
            )
        }
    }

    /** Raw screenshots must not leave the device when accessibility has marked the screen private. */
    fun allowsRemoteScreenshot(snapshot: UiSnapshot): Boolean =
        snapshot.packageName != "unknown" && snapshot.elements.none { it.visible && it.sensitive }

    /**
     * Screenshot approval is bound to the same accessibility epoch and security-relevant content.
     * Geometry-only animation drift is allowed; content or sensitivity drift remains fail-closed.
     */
    fun allowsRemoteScreenshot(expected: UiSnapshot, live: UiSnapshot): Boolean =
        expected.hasSameScreenshotSecurityContextAs(live) &&
            allowsRemoteScreenshot(expected) &&
            allowsRemoteScreenshot(live)

    private fun validateToolCall(
        action: AgentAction,
        snapshot: UiSnapshot,
    ): SafetyAssessment? = when (action.type) {
        ActionType.CLICK_COORDINATE -> {
            if (action.xRatio?.let { it.isFinite() && it in 0.0..1.0 } != true ||
                action.yRatio?.let { it.isFinite() && it in 0.0..1.0 } != true
            ) {
                blocked("좌표 클릭에는 0부터 1 사이의 x_ratio와 y_ratio가 필요합니다.")
            } else null
        }

        ActionType.SET_TEXT,
        ActionType.SUBMIT_TEXT,
        -> {
            val path = UiTargetResolver.resolveEditablePath(action, snapshot)
            when {
                action.value == null -> blocked("텍스트 입력 도구에 입력할 값이 없습니다.")
                action.value.length > MAX_INPUT_LENGTH -> blocked("한 번에 입력할 텍스트가 너무 깁니다.")
                path == null -> blocked("입력할 필드를 현재 화면에서 하나로 식별하지 못했습니다.")
                action.type == ActionType.SUBMIT_TEXT &&
                    compact(snapshot.elements.single { it.path == path }.text.orEmpty()) !=
                    compact(action.value) -> blocked("현재 입력란의 검색어가 요청한 검색어와 달라 제출하지 않습니다.")
                else -> null
            }
        }

        ActionType.CLICK -> if (action.target.isNullOrBlank()) {
            blocked("요소 클릭에는 접근성 텍스트, 설명, 뷰 ID 또는 노드 경로가 필요합니다.")
        } else null

        else -> null
    }

    private fun matchingEditableIsSensitive(action: AgentAction, snapshot: UiSnapshot): Boolean {
        val path = UiTargetResolver.resolveEditablePath(action, snapshot) ?: return false
        return snapshot.elements.firstOrNull { it.path == path }?.sensitive == true
    }

    private fun looksLikePersonalValue(value: String): Boolean {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        return emailPattern.containsMatchIn(normalized) ||
            phonePattern.containsMatchIn(normalized) ||
            longSecretNumberPattern.containsMatchIn(normalized)
    }

    private fun isPaymentCommit(
        command: String,
        plan: AgentPlan,
        action: AgentAction,
    ): Boolean {
        val wholeContext = listOf(
            command,
            plan.targetSurface,
            action.description,
            action.target.orEmpty(),
        ).joinToString(" ")
        if (containsAny(wholeContext, paymentCommitTerms)) return true
        val paymentDomain = containsAny(wholeContext, paymentDomainTerms)
        val actionContext = listOf(action.description, action.target.orEmpty()).joinToString(" ")
        if (containsAny(actionContext, paymentBrowsingTerms)) return false
        val directCommitLabel = compact(action.target.orEmpty()) in directPaymentLabels
        return paymentDomain && (containsAny(actionContext, commitSignalTerms) || directCommitLabel)
    }

    private fun containsAny(value: String, terms: Set<String>): Boolean {
        val compact = compact(value)
        return terms.any { compact.contains(compact(it)) }
    }

    private fun compact(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{Nd}]"), "")

    private fun blocked(reason: String) = SafetyAssessment(
        SafetyDecision.BLOCK,
        RiskLevel.BLOCKED,
        reason,
    )

    private val paymentCommitTerms = setOf(
        "결제하기", "결제 완료", "구매하기", "주문하기", "주문 확정", "송금하기", "이체하기",
        "pay now", "place order", "confirm purchase", "confirm payment", "transfer now",
    )
    private val paymentDomainTerms = setOf(
        "결제", "송금", "구매", "주문", "이체", "pay", "payment", "purchase", "order", "transfer",
    )
    private val commitSignalTerms = setOf(
        "확인", "확정", "완료", "제출", "진행하기", "보내기",
        "confirm", "complete", "submit", "pay now", "place order", "buy now", "transfer now",
    )
    private val paymentBrowsingTerms = setOf(
        "내역", "상세", "목록", "방법", "수단", "정보", "history", "details", "list", "method",
    )
    private val directPaymentLabels = setOf(
        "결제", "구매", "주문", "송금", "이체", "pay", "buy", "purchase", "transfer",
    )
    private val personalDataTerms = setOf(
        "비밀번호", "암호", "pin", "otp", "인증번호", "보안코드", "카드번호", "cvc", "cvv",
        "주민등록번호", "여권번호", "계좌번호", "전화번호", "휴대폰번호", "주소", "이메일",
        "password", "passcode", "verification code", "card number", "account number", "address",
    )
    private val emailPattern = Regex("[\\p{L}\\p{Nd}._%+-]+@[\\p{L}\\p{Nd}.-]+")
    private val phonePattern = Regex("(?<!\\d)(?:\\+?\\d[\\d .-]{7,}\\d)(?!\\d)")
    private val longSecretNumberPattern = Regex("(?<!\\d)\\d{6,}(?!\\d)")
    private const val MAX_INPUT_LENGTH = 4_000
}
