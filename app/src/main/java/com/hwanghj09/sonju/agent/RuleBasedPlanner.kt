package com.hwanghj09.sonju.agent

import java.text.Normalizer

/**
 * Common, low-risk requests never leave the device. Every rule is a full-sentence grammar: an
 * action phrase embedded in a question, explanation, quotation, or writing request cannot run.
 */
object RuleBasedPlanner {
    private val requestEnding = Regex("\\s*(?:해\\s*)?(?:줘|주세요)$")

    private data class ToggleRule(
        val route: TrustedSettingsRoute,
        val commandNames: Set<String>,
        val visibleLabels: Set<String>,
    )

    private val toggleRules = listOf(
        ToggleRule(
            TrustedSettingsRoute.WIFI,
            setOf("wifi", "와이파이"),
            setOf("wifi", "와이파이", "usewifi", "wifi사용", "와이파이사용"),
        ),
        ToggleRule(
            TrustedSettingsRoute.SOUND,
            setOf("터치음", "touchsounds"),
            setOf("터치음", "touchsounds"),
        ),
        ToggleRule(
            TrustedSettingsRoute.SOUND,
            setOf("화면잠금소리", "screenlockingsound"),
            setOf("화면잠금소리", "screenlockingsound"),
        ),
        ToggleRule(
            TrustedSettingsRoute.DISPLAY,
            setOf("어두운테마", "darktheme"),
            setOf("어두운테마", "darktheme"),
        ),
        ToggleRule(
            TrustedSettingsRoute.DISPLAY,
            setOf("자동화면회전", "autorotatescreen"),
            setOf("자동화면회전", "autorotatescreen"),
        ),
        ToggleRule(
            TrustedSettingsRoute.ACCESSIBILITY,
            setOf("색상반전", "colorinversion"),
            setOf("색상반전", "colorinversion"),
        ),
        ToggleRule(
            TrustedSettingsRoute.ACCESSIBILITY,
            setOf("애니메이션삭제", "removeanimations"),
            setOf("애니메이션삭제", "removeanimations"),
        ),
        ToggleRule(
            TrustedSettingsRoute.DATE_TIME,
            setOf("자동시간대", "automatictimezone"),
            setOf("자동시간대", "automatictimezone"),
        ),
        ToggleRule(
            TrustedSettingsRoute.DATE_TIME,
            setOf("24시간형식", "use24hourformat"),
            setOf("24시간형식사용", "use24hourformat"),
        ),
    )

    fun plan(command: String, snapshot: UiSnapshot? = null): AgentPlan? {
        val normalized = command.trim().lowercase().replace(Regex("\\s+"), " ")
        if (normalized.isBlank() || SafetyPolicy.isExplicitNegation(normalized)) return null
        planTrustedToggle(normalized, snapshot)?.let { return planFor(command, it) }
        val body = commandBody(normalized) ?: return null

        val action = when {
            body.matchesAny(
                "^(?:와이파이|wi-?fi)(?:를|의)? 설정(?:을)?(?: 열기| 열어| 보기| 보여)?$",
                "^open (?:wi-?fi|wifi) settings$",
            ) -> AgentAction(ActionType.OPEN_WIFI_SETTINGS, "와이파이 설정 화면을 엽니다.")

            body.matchesAny(
                "^(?:소리|음량|벨소리)(?:를|의)? 설정(?:을)?(?: 열기| 열어| 보기| 보여)?$",
                "^open sound settings$",
            ) -> AgentAction(ActionType.OPEN_SOUND_SETTINGS, "소리 설정 화면을 엽니다.")

            body.matchesAny(
                "^접근성(?:을|의)? 설정(?:을)?(?: 열기| 열어| 보기| 보여)?$",
                "^화면 도우미(?:를)? 연결(?:하기|해)?$",
                "^open accessibility settings$",
            ) -> AgentAction(ActionType.OPEN_ACCESSIBILITY_SETTINGS, "접근성 설정 화면을 엽니다.")

            body.matchesAny(
                "^(?:디스플레이|화면)(?:를|의)? 설정(?:을)?(?: 열기| 열어| 보기| 보여)?$",
                "^open (?:display|screen) settings$",
            ) -> AgentAction(ActionType.OPEN_DISPLAY_SETTINGS, "디스플레이 설정 화면을 엽니다.")

            body.matchesAny(
                "^(?:날짜 및 시간|날짜|시간)(?:을|의)? 설정(?:을)?(?: 열기| 열어| 보기| 보여)?$",
                "^open (?:date|date and time) settings$",
            ) -> AgentAction(ActionType.OPEN_DATE_SETTINGS, "날짜 및 시간 설정 화면을 엽니다.")

            body.matchesAny(
                "^(?:카메라|사진기)(?:를)? (?:열기|켜기|열어|켜)$",
            ) -> AgentAction(ActionType.OPEN_CAMERA, "카메라를 엽니다.")

            body.matchesAny(
                "^(?:전화 화면|전화기|전화 앱|다이얼러)(?:를)? (?:열기|켜기|열어|켜)$",
                "^전화(?:를)? 걸기$",
            ) -> AgentAction(
                ActionType.OPEN_DIALER,
                "전화 번호 입력 화면을 엽니다. 실제 전화는 사용자가 눌러야 합니다.",
            )

            body.matchesAny(
                "^(?:문자 화면|메시지 앱|문자 앱)(?:를)? (?:열기|켜기|열어|켜)$",
            ) -> AgentAction(
                ActionType.OPEN_MESSAGES,
                "문자 작성 화면을 엽니다. 전송은 사용자가 확인해야 합니다.",
            )

            body.matchesAny(
                "^(?:화면(?:을)? )?(?:아래|밑)(?:로)? (?:내리기|내려|스크롤하기|스크롤해)$",
                "^다음 내용(?:을)? (?:보기|보여)$",
            ) -> AgentAction(ActionType.SCROLL_DOWN, "현재 화면을 아래쪽 내용으로 이동합니다.")

            body.matchesAny(
                "^(?:화면(?:을)? )?위(?:로)? (?:올리기|올려|스크롤하기|스크롤해)$",
            ) -> AgentAction(ActionType.SCROLL_UP, "현재 화면을 위쪽 내용으로 이동합니다.")

            body.matchesAny(
                "^(?:뒤|뒤로|이전 화면|전 화면)(?:으로)? (?:가기|가|돌아가기|돌아가)$",
            ) -> AgentAction(ActionType.BACK, "이전 화면으로 이동합니다.")

            body.matchesAny(
                "^(?:홈 화면|처음 화면)(?:으로)? (?:가기|가|열기|열어|보여)$",
            ) -> AgentAction(ActionType.HOME, "홈 화면으로 이동합니다.")

            body.matchesAny(
                "^알림 ?창(?:을)? (?:열기|열어|보여)$",
                "^알림(?:을)? 보여$",
            ) -> AgentAction(ActionType.NOTIFICATIONS, "알림창을 엽니다.")

            body.matchesAny(
                "^(?:빠른|퀵) 설정(?:창)?(?:을)? (?:열기|열어|보여)$",
            ) -> AgentAction(ActionType.QUICK_SETTINGS, "빠른 설정창을 엽니다.")

            else -> parseOpenAppTarget(body)?.let { appName ->
                AgentAction(
                    ActionType.OPEN_APP,
                    "$appName 앱을 엽니다.",
                    target = appName,
                )
            } ?: return null
        }

        return planFor(command, action)
    }

    private fun planFor(command: String, action: AgentAction) = AgentPlan(
        goal = command.trim(),
        summary = action.description,
        modelRisk = RiskLevel.LOW,
        confidence = 1.0,
        actions = listOf(action, AgentAction(ActionType.FINISH, "요청을 마칩니다.")),
        source = PlanSource.LOCAL_RULE,
    )

    private fun planTrustedToggle(command: String, snapshot: UiSnapshot?): AgentAction? {
        val currentSnapshot = snapshot ?: return null
        val (rawControl, desiredState) = parseToggleRequest(command) ?: return null
        val control = compact(rawControl.removeSuffix("을").removeSuffix("를").trim())
        val matchingRule = toggleRules.singleOrNull { rule ->
            rule.route == currentSnapshot.trustedSettingsRoute && control in rule.commandNames
        } ?: return null
        val visibleLabels = currentSnapshot.elements.asSequence()
            .filter { it.visible && it.enabled && !it.sensitive && !it.text.isNullOrBlank() }
            .filter { compact(it.text.orEmpty()) in matchingRule.visibleLabels }
            .mapNotNull(UiElement::text)
            // OEM Settings screens can expose the same label with different Unicode hyphens.
            // Treat those as one semantic candidate while preserving the exact on-screen text.
            .distinctBy(::compact)
            .toList()
        val exactVisibleLabel = visibleLabels.singleOrNull() ?: return null
        val description = if (desiredState) {
            "$exactVisibleLabel 설정을 켭니다."
        } else {
            "$exactVisibleLabel 설정을 끕니다."
        }
        return AgentAction(
            type = ActionType.CLICK,
            description = description,
            target = exactVisibleLabel,
            value = if (desiredState) "checked" else "unchecked",
        )
    }

    private fun parseToggleRequest(command: String): Pair<String, Boolean>? {
        val body = command.replace(requestEnding, "").trim()
        Regex("^(?:turn|switch)\\s+(on|off)\\s+(?:the\\s+)?(.+)$")
            .matchEntire(body)?.let { match ->
                return match.groupValues[2] to (match.groupValues[1] == "on")
            }
        Regex("^(enable|disable)\\s+(?:the\\s+)?(.+)$")
            .matchEntire(body)?.let { match ->
                return match.groupValues[2] to (match.groupValues[1] == "enable")
            }
        Regex("^(.+?)\\s+(on|off)$").matchEntire(body)?.let { match ->
            return match.groupValues[1] to (match.groupValues[2] == "on")
        }
        Regex("^(.+?)(?:을|를)?\\s*(켜|켜기|꺼|끄기|활성화|비활성화)$")
            .matchEntire(body)?.let { match ->
                val desired = match.groupValues[2] in setOf("켜", "켜기", "활성화")
                return match.groupValues[1] to desired
            }
        return null
    }

    private fun compact(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{Nd}]"), "")

    private fun commandBody(value: String): String? {
        if ('?' in value || '？' in value) return null
        val explicitRequest = value.endsWith("기") || value.endsWith("설정") ||
            value.startsWith("open ") || requestEnding.containsMatchIn(value)
        if (!explicitRequest) return null
        return value.replace(requestEnding, "").trim()
    }

    private fun parseOpenAppTarget(body: String): String? {
        val target = Regex(
            "^(.{1,40}?)(?:\\s*앱)?(?:을|를)?\\s*(?:열기|열어|켜기|켜|실행하기|실행해|실행|들어가기|들어가)$",
        ).matchEntire(body)?.groupValues?.get(1)
            ?: Regex("^open\\s+(.{1,40})$", RegexOption.IGNORE_CASE)
                .matchEntire(body)?.groupValues?.get(1)
            ?: return null
        return target.trim()
            .takeIf { it.isNotBlank() && compact(it) !in NON_APP_TARGETS }
    }

    private fun String.matchesAny(vararg patterns: String): Boolean =
        patterns.any { pattern -> Regex(pattern, RegexOption.IGNORE_CASE).matches(this) }

    private val NON_APP_TARGETS = setOf(
        "앱", "화면", "설정", "버튼", "링크", "메뉴", "파일", "문서", "사진", "영상",
    ).map(::compact).toSet()
}
