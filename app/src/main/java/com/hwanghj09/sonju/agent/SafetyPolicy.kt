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
    private val blockedPackageFragments = setOf(
        "bank", "banking", "finance", "securities", "wallet", "payment", "kakaopay",
        "tosspay", "authenticator", "packageinstaller", "permissioncontroller", "crypto",
        "systemui", "keyguard", "biometric", "credential", "inputmethod", "keyboard",
    )

    private val blockedScreenTerms = setOf(
        "송금", "이체", "입금", "출금", "결제", "구매", "주문 확정", "계좌", "카드번호",
        "비밀번호", "비번", "pin", "otp", "인증번호", "보안코드", "생체인증", "지문인증",
        "권한 허용", "앱 권한", "제한된 설정 허용", "앱 설치", "앱 삭제", "계정 삭제",
        "공장 초기화", "보안 해제", "잠금 해제",
        "transfer", "send money", "payment", "pay now", "purchase", "checkout",
        "place order", "confirm order", "bank account", "card number", "password", "passcode",
        "verification code", "security code", "biometric", "fingerprint authentication",
        "allow permission", "app permission", "install app", "install apk", "uninstall",
        "delete account", "factory reset", "disable security", "unlock",
    )

    private val confirmationTargetTerms = setOf(
        "보내기", "전송", "삭제", "등록", "저장", "예약", "신고", "공유", "게시",
        "send", "delete", "save", "reserve", "report", "share", "post",
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
        if (plan.actions.any { it.type == ActionType.CLICK }) {
            return SafetyAssessment(
                SafetyDecision.ALLOW,
                RiskLevel.LOW,
                "클릭 안전검사 없이 실행합니다.",
            )
        }
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
            highRiskScreenReason(snapshot)?.let { reason ->
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
                if (snapshot.treeTruncated) {
                    return SafetyAssessment(
                        SafetyDecision.BLOCK,
                        RiskLevel.BLOCKED,
                        "스크롤 영역 전체를 확인하지 못해 화면을 움직이지 않습니다.",
                    )
                }
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


        }

        val needsConfirmation = plan.source != PlanSource.LOCAL_RULE || plan.actions.any { action ->
            action.type in setOf(
                ActionType.SET_TEXT,
                ActionType.OPEN_MESSAGES,
                ActionType.OPEN_DIALER,
            ) || confirmationTargetTerms.any { term ->
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
            .firstOrNull { it.clickable }

    fun highRiskScreenReason(snapshot: UiSnapshot): String? {
        if (snapshot.treeTruncated) {
            return "화면 전체를 안전하게 검사할 수 없어 버튼·입력·스크롤을 실행하거나 모델에 보내지 않습니다."
        }
        val normalizedPackage = Normalizer.normalize(snapshot.packageName, Normalizer.Form.NFKC)
            .lowercase()
        blockedPackageFragments.firstOrNull { normalizedPackage.contains(it) }?.let {
            return "금융·인증·권한 관련 앱에서는 화면 동작을 대신 실행하지 않습니다."
        }

        if (snapshot.windowTitle == "[민감 화면]" || snapshot.elements.any { element ->
                element.visible && element.sensitive && element.editable
            }
        ) {
            return "비밀번호·인증 정보가 있는 화면은 모델에 보내거나 대신 조작하지 않습니다."
        }

        val screenContext = buildString {
            append(snapshot.windowTitle.orEmpty()).append('\n')
            snapshot.elements.asSequence()
                .filter { it.visible && !it.sensitive }
                .forEach { element ->
                    append(element.text.orEmpty()).append(' ')
                    append(element.contentDescription.orEmpty()).append(' ')
                    append(element.stateDescription.orEmpty()).append(' ')
                    append(element.viewId.orEmpty()).append('\n')
                }
        }
        if (blockedScreenTerms.any { containsPolicyTerm(screenContext, it) }) {
            return "금융·결제·인증·권한·설치·보안 화면에서는 모델 입력과 자동 조작을 중단합니다."
        }

        return null
    }

    private fun ActionType.requiresStableScreen(): Boolean = this in setOf(
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

    /** Resolves the first matching clickable node without applying click safety policy. */
    fun resolveClick(action: AgentAction, snapshot: UiSnapshot): ResolvedClick? {
        if (action.type != ActionType.CLICK) return null
        val target = action.target.orEmpty()
        if (target.isBlank()) return null
        val clickable = snapshot.elements.asSequence()
            .filter { matches(it, target) }
            .mapNotNull { effectiveClickableElement(snapshot, it) }
            .firstOrNull() ?: return null
        return ResolvedClick(clickablePath = clickable.path)
    }
}
