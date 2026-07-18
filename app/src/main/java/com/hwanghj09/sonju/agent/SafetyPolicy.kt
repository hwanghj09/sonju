package com.hwanghj09.sonju.agent

import java.text.Normalizer

/**
 * Deterministic guardrail. Gemini's own risk label is informative only; this policy owns the final
 * decision and fails closed on ambiguity.
 */
object SafetyPolicy {
    private val blockedCommandTerms = setOf(
        "송금", "이체", "입금", "출금", "돈 보내", "결제", "구매", "주문 확정", "계좌", "카드 번호", "카드번호",
        "비밀번호", "비번", "pin", "otp", "인증번호", "보안코드", "캡차", "captcha",
        "공장 초기화", "초기화해", "앱 삭제", "계정 삭제", "영구 삭제", "휴지통 비우기", "보안 해제", "잠금 해제",
        "권한 허용", "설치해", "apk", "투자", "주식 매수", "코인 매수",
        "위치 공유", "파일 공유",
        "transfer", "send money", "deposit", "withdraw", "bank account", "account number",
        "pay", "payment", "purchase", "buy", "checkout", "place order", "confirm order",
        "credit card", "debit card", "invest", "stock", "crypto", "wallet",
        "password", "passcode", "verification code", "security code", "factory reset",
        "delete app", "uninstall", "delete account", "permanently delete", "empty trash",
        "delete", "erase", "reset", "clear data", "clear storage", "remove account",
        "disable security", "unlock", "allow permission", "install apk", "share location", "share file",
    )

    private val blockedTargetTerms = setOf(
        "송금", "이체", "결제", "구매", "주문", "비밀번호", "인증번호", "권한 허용",
        "설치", "초기화", "계정 삭제", "영구 삭제", "보안 해제", "공유",
        "transfer", "send money", "pay", "payment", "purchase", "buy", "checkout",
        "place order", "password", "passcode", "verification code", "security code",
        "allow", "install", "uninstall", "delete account", "permanently delete", "share",
        "delete", "erase", "reset", "clear data", "clear storage", "remove account", "forget network",
    )
    private val blockedScreenTerms = setOf(
        "송금", "이체", "입금", "출금", "잔액", "계좌", "카드", "결제", "구매", "주문",
        "비밀번호", "비번", "pin", "otp", "인증", "보안코드", "cvc", "cvv",
        "권한", "설치", "영구삭제", "휴지통비우기", "초기화", "위치공유", "파일공유",
        "보낼 금액", "받는 분", "예금주", "수취인", "출금 계좌", "입금 계좌",
        "transfer", "send money", "recipient", "amount to send", "bank account", "account number",
        "balance", "pay", "payment", "purchase", "buy", "checkout", "place order", "credit card",
        "debit card", "password", "passcode", "verification", "security code", "captcha",
        "allow", "permission", "install", "uninstall", "delete account", "permanently delete",
        "factory reset", "empty trash", "disable security", "share location", "share file",
        "delete", "erase", "reset", "clear data", "clear storage", "remove account",
        "device admin", "developer options", "usb debugging", "install unknown apps",
    )
    private val blockedPackageFragments = setOf(
        "bank", "banking", "finance", "securities", "wallet", "payment", "kakaopay",
        "tosspay", "authenticator", "packageinstaller", "permissioncontroller", "crypto",
    )

    private val confirmationTargetTerms = setOf(
        "보내기", "전송", "삭제", "등록", "저장", "예약", "신고", "공유", "게시",
        "send", "delete", "save", "reserve", "report", "share", "post",
    )
    private val trustedSettingsControlsBySurface = mapOf(
        "와이파이" to setOf("와이파이", "wifi", "와이파이사용", "wifi사용", "usewifi"),
        "wifi" to setOf("와이파이", "wifi", "와이파이사용", "wifi사용", "usewifi"),
        "인터넷" to setOf("와이파이", "wifi", "와이파이사용", "wifi사용", "usewifi"),
        "internet" to setOf("와이파이", "wifi", "와이파이사용", "wifi사용", "usewifi"),
        "네트워크및인터넷" to setOf(
            "와이파이", "wifi", "와이파이사용", "wifi사용", "usewifi",
        ),
        "networkinternet" to setOf(
            "와이파이", "wifi", "와이파이사용", "wifi사용", "usewifi",
        ),
        "소리및진동" to setOf(
            "전화벨이울릴때진동", "터치음", "화면잠금소리", "충전소리및진동",
            "vibrateforcalls", "touchsounds", "screenlockingsound", "chargingvibration",
        ),
        "soundvibration" to setOf(
            "전화벨이울릴때진동", "터치음", "화면잠금소리", "충전소리및진동",
            "vibrateforcalls", "touchsounds", "screenlockingsound", "chargingvibration",
        ),
        "소리" to setOf("터치음", "화면잠금소리", "touchsounds", "screenlockingsound"),
        "sound" to setOf("터치음", "화면잠금소리", "touchsounds", "screenlockingsound"),
        "디스플레이" to setOf(
            "어두운테마", "자동화면회전", "굵은텍스트", "고대비텍스트",
            "darktheme", "autorotatescreen", "boldtext", "highcontrasttext",
        ),
        "display" to setOf(
            "어두운테마", "자동화면회전", "굵은텍스트", "고대비텍스트",
            "darktheme", "autorotatescreen", "boldtext", "highcontrasttext",
        ),
        "displaytouch" to setOf(
            "어두운테마", "자동화면회전", "굵은텍스트", "고대비텍스트",
            "darktheme", "autorotatescreen", "boldtext", "highcontrasttext",
        ),
        "접근성" to setOf(
            "확대", "색상보정", "색상반전", "애니메이션삭제", "굵은텍스트", "고대비텍스트",
            "magnification", "colorcorrection", "colorinversion", "removeanimations",
            "boldtext", "highcontrasttext",
        ),
        "accessibility" to setOf(
            "확대", "색상보정", "색상반전", "애니메이션삭제", "굵은텍스트", "고대비텍스트",
            "magnification", "colorcorrection", "colorinversion", "removeanimations",
            "boldtext", "highcontrasttext",
        ),
        "확대" to setOf("확대단축키", "magnificationshortcut"),
        "magnification" to setOf("확대단축키", "magnificationshortcut"),
        "색상및모션" to setOf("색상보정", "색상반전", "애니메이션삭제"),
        "colorandmotion" to setOf("colorcorrection", "colorinversion", "removeanimations"),
        "글꼴크기와스타일" to setOf("굵은텍스트", "고대비텍스트"),
        "글꼴크기및스타일" to setOf("굵은텍스트", "고대비텍스트"),
        "디스플레이크기및텍스트" to setOf("굵은텍스트", "고대비텍스트"),
        "displaysizeandtext" to setOf("boldtext", "highcontrasttext"),
        "날짜및시간" to setOf(
            "자동날짜및시간", "자동시간대", "위치를사용하여시간대설정", "24시간형식사용",
        ),
        "datetime" to setOf(
            "automaticdateandtime", "automatictimezone", "uselocationfortimezone",
            "use24hourformat",
        ),
    )
    private val trustedSettingsStateResourceIds = setOf(
        "android:id/switch_widget",
        "com.android.settings:id/switchwidget",
        "com.android.settings:id/switch_widget",
    )
    private val trustedSettingsTitlesByRoute = mapOf(
        TrustedSettingsRoute.WIFI to setOf(
            "와이파이", "wifi", "인터넷", "internet", "네트워크및인터넷", "networkinternet",
        ),
        TrustedSettingsRoute.SOUND to setOf("소리", "sound", "소리및진동", "soundvibration"),
        TrustedSettingsRoute.ACCESSIBILITY to setOf("접근성", "accessibility"),
        TrustedSettingsRoute.DISPLAY to setOf("디스플레이", "display", "displaytouch"),
        TrustedSettingsRoute.DATE_TIME to setOf("날짜및시간", "datetime"),
    )
    private val numericRunPattern = Regex(
        "(?<!\\p{Nd})\\p{Nd}(?:[\\s\\p{M}\\p{P}\\p{S}_]*\\p{Nd})*",
    )
    private const val ENGLISH_MONTH =
        "(?:january|february|march|april|may|june|july|august|september|" +
            "october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)"
    private const val DATE_YEAR = "(?:(?:19|20|21)[0-9]{2})"
    private const val DATE_MONTH = "(?:0?[1-9]|1[0-2])"
    private const val DATE_DAY = "(?:0?[1-9]|[12][0-9]|3[01])"
    private const val CLOCK_TIME =
        "(?:[01]?[0-9]|2[0-3])\\s*:\\s*[0-5][0-9](?:\\s*:\\s*[0-5][0-9])?"
    private const val ENGLISH_DATE_SEPARATOR = "(?:\\s*,\\s*|\\s+)"
    private val englishDatePattern = Regex(
        "(?<![A-Za-z0-9])(?:" +
            "$ENGLISH_MONTH\\s+$DATE_DAY(?:st|nd|rd|th)?$ENGLISH_DATE_SEPARATOR$DATE_YEAR|" +
            "$DATE_DAY(?:st|nd|rd|th)?\\s+$ENGLISH_MONTH\\s+$DATE_YEAR|" +
            "$ENGLISH_MONTH\\s+$DATE_YEAR" +
            ")(?:\\s+$CLOCK_TIME(?:\\s*(?:a\\.?m\\.?|p\\.?m\\.?))?)?(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val numericDatePatterns = listOf(
        Regex(
            "(?<!\\p{Nd})$DATE_YEAR\\s*([-./])\\s*$DATE_MONTH\\s*\\1\\s*" +
                "$DATE_DAY(?:\\s*\\.)?(?:\\s+$CLOCK_TIME)?(?!\\p{Nd})",
        ),
        Regex(
            "(?<!\\p{Nd})$DATE_DAY\\s*([-./])\\s*$DATE_MONTH\\s*\\1\\s*" +
                "$DATE_YEAR(?:\\s+$CLOCK_TIME)?(?!\\p{Nd})",
        ),
        Regex(
            "(?<!\\p{Nd})$DATE_MONTH\\s*([-./])\\s*$DATE_DAY\\s*\\1\\s*" +
                "$DATE_YEAR(?:\\s+$CLOCK_TIME)?(?!\\p{Nd})",
        ),
    )
    private val explicitTimeContextPattern = Regex(
        "(?:알람|시간|시각|시계|오전|오후|\\b(?:alarm|time|clock)\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val strongClockPattern = Regex(
        "(?<![A-Za-z0-9])(?:" +
            "$CLOCK_TIME\\s*(?:a\\.?m\\.?|p\\.?m\\.?)|" +
            "(?:오전|오후)\\s*$CLOCK_TIME|" +
            "(?:[01]?[0-9]|2[0-3])\\s*:\\s*[0-5][0-9]\\s*:\\s*[0-5][0-9]" +
            ")(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val explicitNegationPattern = Regex(
        "(?:지\\s*(?:마(?:세요|라)?|말(?:아|고|라)?|않)|하지\\s*(?:마|말)|" +
            "(?:^|\\s)안\\s+(?:열어?|켜|가|내리|올리|눌러?|보여)\\s*(?=줘|주세요|$)|" +
            "(?:면|서는)\\s*(?:(?:절대|결코)\\s*)?안\\s*(?:돼|되|된|될|됨)|" +
            "(?:열어|켜|가|내려|올려|눌러|보여|해)선\\s*" +
            "(?:(?:절대|결코)\\s*)?안\\s*(?:돼|되|된|될|됨)|" +
            "(?:는|은)\\s*(?:건|것(?:은|이)?)\\s*(?:(?:절대|결코)\\s*)?" +
            "안\\s*(?:돼|되|된|될|됨)|말고|필요\\s*없|" +
            "\\b(?:do\\s+not|don't|dont|never)\\b)",
    )

    fun preflightCommand(command: String): SafetyAssessment? {
        if (isExplicitNegation(command)) {
            return SafetyAssessment(
                SafetyDecision.BLOCK,
                RiskLevel.BLOCKED,
                "‘하지 마’라는 뜻으로 이해해 아무 동작도 실행하지 않았습니다.",
            )
        }
        blockedCommandTerms.firstOrNull { containsPolicyTerm(command, it) }
            ?.let { term ->
            return SafetyAssessment(
                SafetyDecision.BLOCK,
                RiskLevel.BLOCKED,
                "‘$term’이 포함된 금융·인증·보안·삭제 작업은 손주가 대신 실행하지 않습니다.",
            )
        }
        if (containsSensitiveValue(command) || containsUnsafeShortNumericValue(command)) {
            return SafetyAssessment(
                SafetyDecision.BLOCK,
                RiskLevel.BLOCKED,
                "인증번호나 카드·신분 정보로 보이는 숫자는 모델에 보내거나 대신 입력하지 않습니다.",
            )
        }
        return null
    }

    fun isExplicitNegation(command: String): Boolean {
        val normalized = Normalizer.normalize(command, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("[\\p{Cf}\\p{Cc}\\p{M}]"), "")
        return explicitNegationPattern.containsMatchIn(normalized)
    }

    fun evaluate(command: String, plan: AgentPlan, snapshot: UiSnapshot): SafetyAssessment {
        preflightCommand(command)?.let { return it }

        if (plan.confidence < 0.60) {
            return SafetyAssessment(
                SafetyDecision.BLOCK,
                RiskLevel.BLOCKED,
                "요청을 충분히 확실하게 이해하지 못해 안전하게 멈췄습니다.",
            )
        }

        if (plan.modelRisk == RiskLevel.HIGH || plan.modelRisk == RiskLevel.BLOCKED) {
            return SafetyAssessment(
                SafetyDecision.BLOCK,
                RiskLevel.BLOCKED,
                "위험도가 높은 요청으로 판단되어 자동 실행하지 않습니다.",
            )
        }

        if (plan.actions.isEmpty() || plan.actions.size > 8) {
            return SafetyAssessment(
                SafetyDecision.BLOCK,
                RiskLevel.BLOCKED,
                "행동 계획이 비어 있거나 너무 길어 안전하게 실행할 수 없습니다.",
            )
        }

        val nonFinishActions = plan.actions.filterNot { it.type == ActionType.FINISH }
        if (nonFinishActions.isEmpty()) {
            return SafetyAssessment(
                SafetyDecision.BLOCK,
                RiskLevel.BLOCKED,
                "실행할 수 있는 안전한 행동을 찾지 못했습니다.",
            )
        }
        if (nonFinishActions.size > 1) {
            return SafetyAssessment(
                SafetyDecision.BLOCK,
                RiskLevel.BLOCKED,
                "화면이 바뀐 뒤의 행동을 미리 추측하지 않도록 한 번에 한 단계만 실행합니다.",
            )
        }

        if (nonFinishActions.any { it.type.requiresStableScreen() }) {
            if (snapshot.packageName == "unknown" ||
                snapshot.elements.none { it.visible && !it.sensitive }
            ) {
                return SafetyAssessment(
                    SafetyDecision.BLOCK,
                    RiskLevel.BLOCKED,
                    "화면 구조를 확실히 읽을 수 없어 버튼·입력·스크롤을 실행하지 않습니다.",
                )
            }
            highRiskScreenReason(
                snapshot,
                allowTruncated = plan.source == PlanSource.GEMINI_RAW_SCREEN,
            )?.let { reason ->
                return SafetyAssessment(
                    SafetyDecision.BLOCK,
                    RiskLevel.BLOCKED,
                    reason,
                )
            }
        }

        if (nonFinishActions.any { it.type == ActionType.SET_TEXT }) {
            return SafetyAssessment(
                SafetyDecision.BLOCK,
                RiskLevel.BLOCKED,
                "이 프로토타입에서는 외부 화면에 글자를 대신 입력하지 않습니다.",
            )
        }

        if (nonFinishActions.any { it.type == ActionType.CLICK } &&
            !isTrustedInteractiveSurface(snapshot)
        ) {
            return SafetyAssessment(
                SafetyDecision.BLOCK,
                RiskLevel.BLOCKED,
                "안전성이 검토된 저위험 설정 화면이 아니어서 버튼 누르기와 글자 입력을 실행하지 않습니다.",
            )
        }

        if (nonFinishActions.any { it.type == ActionType.VISUAL_CLICK }) {
            if (plan.source != PlanSource.GEMINI_RAW_SCREEN ||
                snapshot.visualFingerprint == null ||
                VisualTargetResolver.resolveClickablePath(
                    snapshot,
                    nonFinishActions.single().x,
                    nonFinishActions.single().y,
                ) == null
            ) {
                return SafetyAssessment(
                    SafetyDecision.BLOCK,
                    RiskLevel.BLOCKED,
                    "AI가 가리킨 위치에서 안전하게 누를 버튼 하나를 확인하지 못했습니다.",
                )
            }
        }

        plan.actions.forEach { action ->
            if (action.waitMillis !in 0..2_000) {
                return SafetyAssessment(
                    SafetyDecision.BLOCK,
                    RiskLevel.BLOCKED,
                    "기다림 시간이 안전 한도를 넘었습니다.",
                )
            }

            val rawActionContext = listOfNotNull(action.target, action.description, action.value)
                .joinToString(" ")

            if (containsSensitiveValue(rawActionContext) ||
                containsUnsafeShortNumericValue(rawActionContext)
            ) {
                return SafetyAssessment(
                    SafetyDecision.BLOCK,
                    RiskLevel.BLOCKED,
                    "인증번호나 카드·신분 정보로 보이는 숫자는 모델 계획에도 포함할 수 없습니다.",
                )
            }

            blockedTargetTerms.firstOrNull { containsPolicyTerm(rawActionContext, it) }
                ?.let { term ->
                return SafetyAssessment(
                    SafetyDecision.BLOCK,
                    RiskLevel.BLOCKED,
                    "‘$term’ 버튼이나 입력은 자동으로 실행하지 않습니다.",
                )
            }

            if (action.type == ActionType.SET_TEXT) {
                val target = action.target.orEmpty()
                if (containsSensitiveValue(action.value.orEmpty()) ||
                    containsShortNumericValue(action.value.orEmpty())
                ) {
                    return SafetyAssessment(
                        SafetyDecision.BLOCK,
                        RiskLevel.BLOCKED,
                        "인증번호나 카드·신분 정보로 보이는 숫자는 대신 입력하지 않습니다.",
                    )
                }
                val matching = snapshot.elements.filter { element ->
                    element.editable && element.visible && element.enabled && matches(element, target)
                }
                if (matching.size != 1) {
                    return SafetyAssessment(
                        SafetyDecision.BLOCK,
                        RiskLevel.BLOCKED,
                        "입력할 곳을 하나로 확실히 찾지 못해 안전하게 멈췄습니다.",
                    )
                }
                if (matching.any { it.sensitive }) {
                    return SafetyAssessment(
                        SafetyDecision.BLOCK,
                        RiskLevel.BLOCKED,
                        "비밀번호나 인증 정보 입력란에는 글자를 대신 입력하지 않습니다.",
                    )
                }
            }

            if (action.type in setOf(ActionType.SCROLL_DOWN, ActionType.SCROLL_UP)) {
                val scrollableElements = snapshot.elements.filter { element ->
                    element.scrollable && element.visible && element.enabled
                }
                val effectiveScrollableElements = scrollableElements.filter { candidate ->
                    scrollableElements.none { other ->
                        other.path != candidate.path &&
                            other.path.startsWith("${candidate.path}.")
                    }
                }
                if (effectiveScrollableElements.size != 1) {
                    return SafetyAssessment(
                        SafetyDecision.BLOCK,
                        RiskLevel.BLOCKED,
                        "스크롤할 영역을 하나로 확실히 찾지 못해 화면을 움직이지 않습니다.",
                    )
                }
            }


            if (action.type == ActionType.CLICK) {
                if (validateClick(action, snapshot) == null) {
                    return SafetyAssessment(
                        SafetyDecision.BLOCK,
                        RiskLevel.BLOCKED,
                        "공식 설정 경로·화면·대상·현재 상태를 하나로 검증하지 못해 누르지 않습니다.",
                    )
                }
            }
        }

        val needsConfirmation = plan.modelRisk == RiskLevel.MEDIUM || plan.actions.any { action ->
            action.type in setOf(
                ActionType.SET_TEXT,
                ActionType.OPEN_MESSAGES,
                ActionType.OPEN_DIALER,
                ActionType.VISUAL_CLICK,
            ) ||
                confirmationTargetTerms.any { term ->
                    containsPolicyTerm(
                        listOfNotNull(action.target, action.description).joinToString(" "),
                        term,
                    )
                }
        }

        return if (needsConfirmation) {
            SafetyAssessment(
                SafetyDecision.REQUIRE_CONFIRMATION,
                RiskLevel.MEDIUM,
                "다른 사람에게 영향을 주거나 되돌리기 어려울 수 있어 실행 전에 확인이 필요합니다.",
            )
        } else {
            SafetyAssessment(
                SafetyDecision.ALLOW,
                RiskLevel.LOW,
                "사용자가 시작한 요청이며, 허용된 탐색·설정 동작만 포함합니다.",
            )
        }
    }

    private fun matches(element: UiElement, target: String): Boolean {
        if (target.isBlank()) return element.editable
        val normalized = Normalizer.normalize(target, Normalizer.Form.NFKC).trim().lowercase()
        val compactTarget = compactForPolicy(target)
        val normalizedViewId = element.viewId?.let {
            Normalizer.normalize(it, Normalizer.Form.NFKC).trim().lowercase()
        }
        return element.path.equals(target, ignoreCase = true) ||
            normalizedViewId == normalized ||
            normalizedViewId?.substringAfterLast('/') == normalized ||
            compactTarget.isNotBlank() && compactForPolicy(element.text.orEmpty()) == compactTarget ||
            compactTarget.isNotBlank() &&
            compactForPolicy(element.contentDescription.orEmpty()) == compactTarget
    }

    private fun effectiveClickableElement(snapshot: UiSnapshot, element: UiElement): UiElement? =
        generateSequence(element.path) { path ->
            path.substringBeforeLast('.', missingDelimiterValue = "")
                .takeIf(String::isNotBlank)
        }
            .take(5)
            .mapNotNull { path -> snapshot.elements.firstOrNull { it.path == path } }
            .firstOrNull { it.visible && it.enabled && it.clickable }

    private fun associatedStateElements(
        snapshot: UiSnapshot,
        clickable: UiElement,
    ): List<UiElement> = snapshot.elements.filter { element ->
        element.visible && element.enabled &&
            (element.path == clickable.path || element.path.startsWith("${clickable.path}.")) &&
            (element.checkable || element.selected || !element.stateDescription.isNullOrBlank())
    }

    private fun currentBooleanState(element: UiElement): Boolean? {
        if (element.checkable) return element.checked
        val describedState = when (compactForPolicy(element.stateDescription.orEmpty())) {
            "켜짐", "사용", "사용중", "on", "checked", "enabled", "true" -> true
            "꺼짐", "사용안함", "off", "unchecked", "disabled", "false" -> false
            else -> null
        }
        return describedState ?: true.takeIf { element.selected }
    }

    fun highRiskScreenReason(snapshot: UiSnapshot, allowTruncated: Boolean = false): String? {
        if (snapshot.treeTruncated && !allowTruncated) {
            return "화면 전체를 안전하게 검사할 수 없어 버튼·입력·스크롤을 실행하거나 모델에 보내지 않습니다."
        }
        if (snapshot.elements.any { it.sensitive }) {
            return "민감 정보 입력란이 있는 화면에서는 버튼·입력·스크롤을 대신 조작하지 않습니다."
        }

        val normalizedPackage = Normalizer.normalize(snapshot.packageName, Normalizer.Form.NFKC)
            .lowercase()
        blockedPackageFragments.firstOrNull { normalizedPackage.contains(it) }?.let {
            return "금융·인증·권한 관련 앱에서는 화면 동작을 대신 실행하지 않습니다."
        }

        val screenValues = buildList {
            add(snapshot.windowTitle.orEmpty())
            snapshot.elements.asSequence()
                .filter { it.visible && !it.sensitive }
                .forEach { element ->
                    add(element.text.orEmpty())
                    add(element.contentDescription.orEmpty())
                }
        }

        blockedScreenTerms.firstOrNull { term ->
            screenValues.any { value -> containsPolicyTerm(value, term) }
        }?.let { term ->
            return "‘$term’ 관련 화면에서는 일반적인 확인 버튼도 대신 누르지 않습니다."
        }
        return null
    }

    private fun ActionType.requiresStableScreen(): Boolean = this in setOf(
        ActionType.CLICK,
        ActionType.VISUAL_CLICK,
        ActionType.SET_TEXT,
        ActionType.SCROLL_DOWN,
        ActionType.SCROLL_UP,
    )

    private fun compactForPolicy(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("[\\p{Cf}\\p{Cc}\\p{M}\\s\\p{P}\\p{S}_]+"), "")

    private fun containsPolicyTerm(value: String, term: String): Boolean {
        val normalizedValue = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("[\\p{Cf}\\p{Cc}\\p{M}]"), "")
        val compactTerm = compactForPolicy(term)
        if (compactTerm.matches(Regex("[a-z0-9]+"))) {
            val separatedTerm = compactTerm.toCharArray()
                .joinToString("[\\s\\p{M}\\p{P}\\p{S}_]*") {
                    Regex.escape(it.toString())
                }
            return Regex(
                "(?<![a-z0-9])$separatedTerm(?![a-z0-9])",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(normalizedValue)
        }
        return compactForPolicy(normalizedValue).contains(compactTerm)
    }

    private fun containsSensitiveValue(value: String): Boolean {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("[\\p{Cf}\\p{Cc}\\p{M}]"), "")
        return numericRunPattern.findAll(normalized).any { match ->
            val digitCount = match.value.count(Char::isDigit)
            digitCount >= 6 && !isClearlyNonSensitiveDate(normalized, match)
        }
    }

    private fun containsShortNumericValue(value: String): Boolean {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("[\\p{Cf}\\p{Cc}\\p{M}]"), "")
        return numericRunPattern.findAll(normalized).any { match ->
            match.value.count(Char::isDigit) in 4..5
        }
    }

    private fun containsUnsafeShortNumericValue(value: String): Boolean {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("[\\p{Cf}\\p{Cc}\\p{M}]"), "")
        return numericRunPattern.findAll(normalized).any { match ->
            match.value.count(Char::isDigit) in 4..5 &&
                !isClearlyNonSensitiveShortNumber(normalized, match) &&
                !isClearlyNonSensitiveDate(normalized, match)
        }
    }

    private fun isClearlyNonSensitiveShortNumber(
        normalized: String,
        match: MatchResult,
    ): Boolean {
        val matched = match.value.trim()
        if (Regex(CLOCK_TIME).matches(matched) && hasExplicitTimeContext(normalized, match)) {
            return true
        }

        val suffix = normalized.substring(match.range.last + 1).trimStart()
        return suffix.startsWith("년") || isExplicitWonAmount(normalized, match)
    }

    private fun isClearlyNonSensitiveDate(
        normalized: String,
        match: MatchResult,
    ): Boolean {
        if (isExplicitWonAmount(normalized, match)) return true
        if (strongClockPattern.findAll(normalized).any { clock ->
                dateExactlyCoversNumericRun(clock, match)
            }
        ) return true
        if (englishDatePattern.findAll(normalized).any { date ->
                dateExactlyCoversNumericRun(date, match)
            }
        ) {
            return true
        }
        return numericDatePatterns.any { pattern ->
            pattern.findAll(normalized).any { date ->
                dateExactlyCoversNumericRun(date, match)
            }
        }
    }

    private fun isExplicitWonAmount(normalized: String, match: MatchResult): Boolean {
        if (match.value.count(Char::isDigit) !in 4..12) return false
        val suffix = normalized.substring(match.range.last + 1).trimStart()
        if (!suffix.startsWith("원")) return false
        val afterWon = suffix.drop(1)
        if (afterWon.firstOrNull()?.isLetterOrDigit() != true) return true
        return listOf("짜리", "어치", "입니다").any { allowedSuffix ->
            afterWon.startsWith(allowedSuffix) &&
                afterWon.drop(allowedSuffix.length).firstOrNull()?.isLetterOrDigit() != true
        }
    }

    private fun hasExplicitTimeContext(normalized: String, match: MatchResult): Boolean {
        val contextStart = maxOf(0, match.range.first - 24)
        val contextEnd = minOf(normalized.length, match.range.last + 1 + 24)
        return explicitTimeContextPattern.containsMatchIn(
            normalized.substring(contextStart, contextEnd),
        )
    }

    private fun dateExactlyCoversNumericRun(
        date: MatchResult,
        numericRun: MatchResult,
    ): Boolean = numericRunPattern.findAll(date.value).any { dateRun ->
        date.range.first + dateRun.range.first == numericRun.range.first &&
            date.range.first + dateRun.range.last == numericRun.range.last &&
            dateRun.value.count(Char::isDigit) == numericRun.value.count(Char::isDigit)
    }

    /**
     * Resolves a click to exact semantic paths only after every policy predicate succeeds. The
     * accessibility sink independently rebuilds this value from one live root before clicking.
     */
    fun validateClick(action: AgentAction, snapshot: UiSnapshot): ValidatedClick? {
        if (action.type != ActionType.CLICK || !isTrustedInteractiveSurface(snapshot)) return null
        val target = action.target.orEmpty()
        if (target.isBlank()) return null
        val matching = snapshot.elements.filter { element ->
            element.visible && element.enabled && matches(element, target)
        }
        val desiredState = desiredCheckState(action.value) ?: return null
        val directStateTargets = matching.filter { element ->
            val normalizedViewId = Normalizer.normalize(
                element.viewId.orEmpty(),
                Normalizer.Form.NFKC,
            ).lowercase()
            element.clickable && normalizedViewId in trustedSettingsStateResourceIds &&
                currentBooleanState(element) != null
        }.distinctBy(UiElement::path)
        val directStateTarget = directStateTargets.singleOrNull()
        val clickable: UiElement
        val stateElement: UiElement
        if (directStateTarget != null) {
            // Some OEM Settings layouts expose the label container and its sibling switch as two
            // clickable matches. An exact named, allowlisted checkable control is less ambiguous.
            clickable = directStateTarget
            stateElement = directStateTarget
        } else {
            val clickableCandidates = matching.mapNotNull { element ->
                effectiveClickableElement(snapshot, element)
            }.distinctBy(UiElement::path)
            val clickableTargets = clickableCandidates.filter { candidate ->
                clickableCandidates.none { other ->
                    other.path != candidate.path && other.path.startsWith("${candidate.path}.")
                }
            }
            clickable = clickableTargets.singleOrNull() ?: return null
            stateElement = associatedStateElements(snapshot, clickable).singleOrNull() ?: return null
        }
        if (!isTrustedSettingsControl(snapshot, action, stateElement)) return null
        val currentState = currentBooleanState(stateElement) ?: return null
        if (currentState == desiredState) return null
        val stateViewId = Normalizer.normalize(
            stateElement.viewId.orEmpty(),
            Normalizer.Form.NFKC,
        ).lowercase()
        return ValidatedClick(
            clickablePath = clickable.path,
            statePath = stateElement.path,
            stateViewId = stateViewId,
            currentState = currentState,
            desiredState = desiredState,
        )
    }

    private fun isTrustedInteractiveSurface(snapshot: UiSnapshot): Boolean {
        val normalizedPackage = Normalizer.normalize(snapshot.packageName, Normalizer.Form.NFKC)
            .lowercase()
        if (normalizedPackage != "com.android.settings") return false
        val route = snapshot.trustedSettingsRoute ?: return false
        val normalizedTitle = compactForPolicy(snapshot.windowTitle.orEmpty())
        return normalizedTitle in trustedSettingsTitlesByRoute[route].orEmpty() &&
            normalizedTitle in trustedSettingsControlsBySurface
    }

    private fun isTrustedSettingsControl(
        snapshot: UiSnapshot,
        action: AgentAction,
        stateElement: UiElement,
    ): Boolean {
        val route = snapshot.trustedSettingsRoute ?: return false
        val normalizedTitle = compactForPolicy(snapshot.windowTitle.orEmpty())
        if (normalizedTitle !in trustedSettingsTitlesByRoute[route].orEmpty()) return false
        val allowedControls = trustedSettingsControlsBySurface[normalizedTitle] ?: return false
        val normalizedTarget = compactForPolicy(action.target.orEmpty())
        val normalizedViewId = Normalizer.normalize(
            stateElement.viewId.orEmpty(),
            Normalizer.Form.NFKC,
        ).lowercase()
        return normalizedTarget in allowedControls &&
            normalizedViewId in trustedSettingsStateResourceIds
    }

    private fun desiredCheckState(value: String?): Boolean? = when (
        Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC).trim().lowercase()
    ) {
        "checked", "true" -> true
        "unchecked", "false" -> false
        else -> null
    }
}
