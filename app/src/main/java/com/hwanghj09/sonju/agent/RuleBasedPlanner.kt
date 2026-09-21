package com.hwanghj09.sonju.agent

import com.hwanghj09.sonju.task.DeterministicTaskParser
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
        TimerRequest.parse(command)?.let { return planFor(command, it.action()) }
        if (AppWorkflowRouter.route(command) != null) return null
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
                "^(?:화면(?:을)? )?왼쪽(?:으로)? (?:넘기기|넘겨|스크롤하기|스크롤해)$",
            ) -> AgentAction(ActionType.SCROLL_LEFT, "현재 화면을 왼쪽 내용으로 이동합니다.")

            body.matchesAny(
                "^(?:화면(?:을)? )?오른쪽(?:으로)? (?:넘기기|넘겨|스크롤하기|스크롤해)$",
            ) -> AgentAction(ActionType.SCROLL_RIGHT, "현재 화면을 오른쪽 내용으로 이동합니다.")

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
        targetApp = action.target.takeIf { action.type == ActionType.OPEN_APP }.orEmpty(),
        targetSurface = action.description,
        requiredTools = setOf(action.type),
        strategy = listOf(action.description),
        successCriteria = listOf("${action.description} 결과가 현재 화면에 나타남"),
        revisionReason = "사용자 명령에서 직접 결정 가능한 단일 도구 경로",
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
        // Korean order verbs such as "시켜" end with the app-launch verb "켜". They are not app
        // launch commands, so never let the suffix-only grammar turn the preceding sentence into
        // an application label (for example, "배민에서 피자 시").
        if (ORDER_VERBS.any(body::contains)) return null
        val target = Regex(
            "^(.{1,40}?)(?:\\s*앱)?(?:을|를)?\\s*(?:열기|열어|켜기|켜|실행하기|실행해|실행|들어가기|들어가)$",
        ).matchEntire(body)?.groupValues?.get(1)
            ?: Regex("^open\\s+(.{1,40})$", RegexOption.IGNORE_CASE)
                .matchEntire(body)?.groupValues?.get(1)
            ?: return null
        return target.trim().takeIf {
            it.isNotBlank() && compact(it) !in NON_APP_TARGETS &&
                !APP_INTERNAL_COMMAND_MARKER.containsMatchIn(it)
        }
    }

    private fun String.matchesAny(vararg patterns: String): Boolean =
        patterns.any { pattern -> Regex(pattern, RegexOption.IGNORE_CASE).matches(this) }

    private val NON_APP_TARGETS = setOf(
        "앱", "화면", "설정", "버튼", "링크", "메뉴", "파일", "문서", "사진", "영상",
    ).map(::compact).toSet()
    private val APP_INTERNAL_COMMAND_MARKER = Regex("(?:에서|으로|로)\\s+")
    private val ORDER_VERBS = setOf("시켜", "주문")
}

data class AppWorkflowRoute(
    val appLabel: String,
    val targetSurface: String,
    val launchQuery: String? = null,
)

/** Separates an installed app prefix from the menu or document requested inside that app. */
object AppWorkflowRouter {
    internal const val KAKAO_PROFILE_CHAT_ADAPTER_TARGET =
        "app-adapter:kakaotalk/profile-home/one-to-one-chat"
    internal const val KAKAO_PROFILE_CHAT_X_RATIO = 0.27

    internal fun kakaoProfileChatYRatio(snapshot: UiSnapshot): Double? {
        val window = snapshot.windowBounds ?: return null
        if (window.left != 0 || window.top != 0 || window.right <= 0 || window.bottom <= 0) {
            return null
        }
        val firstPhotoTop = snapshot.elements.asSequence()
            .filter { it.visible && !it.sensitive }
            .filter { element -> labels(element).any { compact(it) == "사진" } }
            .map { it.bounds.top }
            .minOrNull()
            ?: return null
        val photoTopRatio = firstPhotoTop.toDouble() / window.bottom
        if (photoTopRatio !in KAKAO_PROFILE_PHOTO_TOP_RANGE) return null
        return photoTopRatio - KAKAO_PROFILE_PHOTO_TO_CHAT_CENTER_OFFSET
    }

    fun route(
        command: String,
        installedAppLabels: Collection<String> = emptyList(),
    ): AppWorkflowRoute? {
        val intent = DeterministicTaskParser.parse(command,
            installedAppLabels.takeIf { it.isNotEmpty() })
        val normalizedCommand = normalize(command)
        val explicitApp = intent.targetApp ?: installedAppPrefix(command, installedAppLabels)
        if (explicitApp == null) {
            // Message text, a destination, or a food query may itself contain an installed app
            // name. Infer the requested domain before scanning those literal arguments.
            inferredDomainApp(intent, installedAppLabels)?.let { inferred ->
                return AppWorkflowRoute(
                    appLabel = inferred,
                    targetSurface = normalizedCommand,
                    launchQuery = intent.entities["destination"],
                )
            }
        }
        val appLabel = explicitApp ?: installedAppMention(command, installedAppLabels) ?: return null
        val normalizedApp = normalize(appLabel)
        if (normalize(appLabel).split(' ').any { compact(it) in NON_APP_TARGET_TERMS } &&
            compact(appLabel) !in BUILT_IN_WORKFLOW_APPS
        ) return null
        val commandWithoutPossessive = normalizedCommand.removePrefix("내 ")
        if (!commandWithoutPossessive.startsWith(normalizedApp)) return null
        val targetSurface = commandWithoutPossessive.removePrefix(normalizedApp)
            .replace(LEADING_APP_GRAMMAR, "")
            .replace(LEADING_WORKFLOW_GRAMMAR, "")
            .trim()
        if (targetSurface.isBlank() || SIMPLE_LAUNCH.matches(targetSurface)) return null
        return AppWorkflowRoute(
            appLabel = appLabel,
            targetSurface = targetSurface,
            launchQuery = intent.entities["destination"],
        )
    }

    fun entryPlan(
        command: String,
        route: AppWorkflowRoute,
        currentPackage: String,
        targetPackage: String?,
    ): AgentPlan? {
        if (!targetPackage.isNullOrBlank() && currentPackage == targetPackage) return null
        val action = AgentAction(
            type = ActionType.OPEN_APP,
            description = route.launchQuery?.let { destination ->
                "${route.appLabel} 앱에서 '$destination' 위치를 엽니다."
            } ?: "${route.appLabel} 앱을 먼저 엽니다.",
            target = route.appLabel,
            value = route.launchQuery,
        )
        return AgentPlan(
            goal = command.trim(),
            summary = action.description,
            modelRisk = RiskLevel.LOW,
            confidence = 1.0,
            actions = listOf(action, AgentAction(ActionType.FINISH, "앱 내부 화면을 다시 확인합니다.")),
            source = PlanSource.LOCAL_RULE,
            targetApp = route.appLabel,
            targetSurface = route.targetSurface,
            requiredTools = setOf(ActionType.OPEN_APP),
            strategy = listOf(action.description, "앱 내부 화면에서 요청한 항목을 찾습니다."),
            successCriteria = listOf("${route.appLabel} 앱이 열림"),
            continueAfterAction = true,
            revisionReason = "앱 이름과 앱 내부 대상을 분리한 로컬 진입 경로",
        )
    }

    fun inAppPlan(
        command: String,
        route: AppWorkflowRoute,
        snapshot: UiSnapshot,
        successfulActions: List<AgentAction> = emptyList(),
    ): AgentPlan? {
        val intent = DeterministicTaskParser.parse(command, listOf(route.appLabel))
        val compactTarget = compact(route.targetSurface)
        val appSpecificTarget = when {
            snapshot.packageName == "com.samsung.android.app.notes" &&
                compactTarget.contains("가장최근") &&
                listOf("파일", "노트", "문서").any(compactTarget::contains) ->
                snapshot.elements.asSequence()
                    .filter { element ->
                        element.visible && element.enabled && element.clickable &&
                            element.viewId.orEmpty().substringAfterLast('/') == "root_cardview"
                    }
                    .sortedWith(compareBy<UiElement> { it.bounds.top }.thenBy { it.bounds.left })
                    .firstOrNull()

            else -> null
        }
        val candidateDecision = interruptionDecision(command, snapshot)
            ?: profileDecision(command, route, snapshot)
            ?: messageDecision(intent, snapshot, successfulActions)
            ?: directionsDecision(intent, snapshot)
            ?: foodOrderDecision(intent, snapshot, successfulActions)
            ?: appSpecificTarget?.let { target ->
            WorkflowDecision(
                action = AgentAction(
                    type = ActionType.CLICK,
                    description = "${route.appLabel}에서 ${route.targetSurface} 항목을 엽니다.",
                    target = target.path,
                ),
                reason = "검증된 앱 adapter의 구조화된 빠른 경로",
            )
        } ?: genericSearchDecision(command, route, snapshot, successfulActions)
            ?: genericMenuDecision(command, route, snapshot)
            ?: return null
        val decision = if (
            ScreenContextHandoff.hasVisibleLoadingIndicator(snapshot) &&
            !candidateDecision.goalCompleted &&
            candidateDecision.action?.let { action ->
                action.type == ActionType.BACK ||
                    action.type == ActionType.CLICK && action.target.isNullOrBlank()
            } == true
        ) {
            WorkflowDecision(
                action = AgentAction(
                    ActionType.WAIT,
                    "화면의 목표 control이 로딩될 때까지 잠시 기다립니다.",
                    waitMillis = 800,
                ),
                reason = "로딩 중에는 target 없는 클릭이나 뒤로가기를 실행하지 않음",
            )
        } else {
            candidateDecision
        }
        val action = decision.action
        val actions = if (decision.goalCompleted) {
            listOf(AgentAction(ActionType.FINISH, "요청한 결과 화면을 확인했습니다."))
        } else {
            listOf(
                action ?: return null,
                AgentAction(ActionType.FINISH, "변경된 화면을 다시 확인합니다."),
            )
        }
        return AgentPlan(
            goal = command.trim(),
            summary = action?.description ?: "${route.appLabel}에서 요청한 결과를 확인했습니다.",
            modelRisk = RiskLevel.LOW,
            confidence = 1.0,
            actions = actions,
            source = decision.source,
            continueAfterAction = !decision.goalCompleted,
            goalCompleted = decision.goalCompleted,
            targetApp = route.appLabel,
            targetSurface = route.targetSurface,
            requiredTools = action?.let { setOf(it.type) }.orEmpty(),
            strategy = listOfNotNull(action?.description),
            successCriteria = listOf("요청한 메뉴 또는 검색 결과가 현재 화면에서 확인됨"),
            revisionReason = decision.reason,
            visualFallback = decision.visualFallback,
        )
    }

    private fun interruptionDecision(command: String, snapshot: UiSnapshot): WorkflowDecision? {
        if (snapshot.treeTruncated || snapshot.elements.any { it.visible && it.sensitive }) return null
        val compactCommand = compact(command)
        if (INTERRUPTION_OFFER_TERMS.any(compactCommand::contains)) return null
        val visibleLabels = snapshot.elements.asSequence()
            .filter { it.visible && !it.sensitive }
            .flatMap { labels(it).asSequence() }
            .map(::compact)
            .toList()
        if (visibleLabels.any { label -> RECOVERABLE_ALERT_TERMS.any(label::contains) }) {
            semanticSurfaces(snapshot).singleMatching(ALERT_DISMISS_TERMS)?.let { dismiss ->
                return WorkflowDecision(
                    action = AgentAction(
                        ActionType.CLICK,
                        "복구 가능한 오류 안내의 닫기 control을 누르고 현재 화면을 다시 관찰합니다.",
                        target = dismiss.path,
                    ),
                    reason = "진행 불가 안내와 유일한 확인·닫기 control을 semantic 정보로 식별",
                )
            }
        }
        if (visibleLabels.any { label -> CART_REPLACEMENT_PROMPT_TERMS.any(label::contains) }) {
            semanticSurfaces(snapshot).filter { surface ->
                surface.labels.any { label ->
                    CART_REPLACEMENT_CONFIRM_TERMS.any { term ->
                        label == term || (term != ADD_TO_CART_EXACT_TERM && label.endsWith(term))
                    }
                }
            }.distinctBy(SemanticSurface::path).singleOrNull()?.let { confirm ->
                return WorkflowDecision(
                    action = AgentAction(
                        ActionType.CLICK,
                        "새 요청의 메뉴를 담기 위해 기존 장바구니 교체를 진행합니다.",
                        target = confirm.path,
                    ),
                    reason = "다른 가게 장바구니 교체 안내와 유일한 담기 확인 control을 semantic 정보로 식별",
                )
            }
        }
        if (visibleLabels.none { label -> INTERRUPTION_OFFER_TERMS.any(label::contains) }) return null
        if (visibleLabels.any { label -> CROSS_SELL_TERMS.any(label::contains) }) {
            semanticSurfaces(snapshot).singleMatching(ORDER_REVIEW_TERMS)?.let { review ->
                return WorkflowDecision(
                    action = AgentAction(
                        ActionType.CLICK,
                        "추천 상품은 추가하지 않고 주문서 화면으로 이동합니다.",
                        target = review.path,
                    ),
                    reason = "교차판매 제안과 유일한 주문서 계속 control을 semantic 정보로 식별",
                )
            }
        }
        val dismiss = semanticSurfaces(snapshot).filter { surface ->
            surface.labels.any { label ->
                INTERRUPTION_DISMISS_TERMS.any { term -> label == term || label.endsWith(term) }
            }
        }.distinctBy(SemanticSurface::path).singleOrNull() ?: return null
        return WorkflowDecision(
            action = AgentAction(
                ActionType.CLICK,
                "현재 요청과 무관한 가입·구독 제안 화면을 닫습니다.",
                target = dismiss.path,
            ),
            reason = "현재 명령에 없는 제안과 유일한 닫기 control을 semantic 정보로 식별",
        )
    }

    private fun profileDecision(
        command: String,
        route: AppWorkflowRoute,
        snapshot: UiSnapshot,
    ): WorkflowDecision? {
        if (!compact(route.targetSurface).contains("프로필")) return null
        val wantsEdit = PROFILE_EDIT_TERMS.any(compact(command)::contains)
        val editElements = snapshot.elements.filter { element ->
            element.visible && element.enabled && !element.sensitive && labels(element).any { label ->
                PROFILE_EDIT_LABELS.any { term -> compact(label).contains(term) }
            }
        }
        val editPaths = editElements.mapNotNull { clickablePath(snapshot, it) }.distinct()
        if (wantsEdit && editPaths.size == 1) {
            return WorkflowDecision(
                action = AgentAction(
                    ActionType.CLICK,
                    "내 프로필의 편집 화면을 엽니다.",
                    target = editPaths.single(),
                ),
                reason = "현재 프로필 화면의 유일한 편집 control을 semantic 정보로 식별",
            )
        }
        if (editElements.isNotEmpty() && (wantsEdit && editPaths.isEmpty() || !wantsEdit)) {
            return WorkflowDecision(
                goalCompleted = true,
                reason = if (wantsEdit) {
                    "프로필 편집 화면 제목이 현재 화면에서 확인됨"
                } else {
                    "내 프로필 화면의 편집 control이 확인됨"
                },
            )
        }
        val ownProfilePaths = snapshot.elements.asSequence()
            .filter { it.visible && it.enabled && !it.sensitive }
            .filter { element ->
                labels(element).any { label -> OWN_PROFILE_TERMS.any(compact(label)::contains) }
            }
            .mapNotNull { clickablePath(snapshot, it) }
            .distinct()
            .toList()
        val ownProfileDecision = ownProfilePaths.singleOrNull()?.let { path ->
            WorkflowDecision(
                action = AgentAction(
                    ActionType.CLICK,
                    "내 프로필을 엽니다.",
                    target = path,
                ),
                reason = "친구 프로필과 구분되는 유일한 내 프로필 control을 semantic 정보로 식별",
            )
        }
        ownProfileDecision?.let { return it }
        val profileEntryElements = snapshot.elements.asSequence()
            .filter { it.visible && it.enabled && !it.sensitive }
            .filter { element ->
                labels(element).any { label ->
                    compact(label).let { it.contains("친구탭") || it.contains("friendstab") }
                }
            }
            .toList()
        val profileEntryPaths = profileEntryElements.asSequence()
            .mapNotNull { clickablePath(snapshot, it) }
            .distinct()
            .toList()
        profileEntryPaths.singleOrNull()?.let { path ->
            return WorkflowDecision(
                action = AgentAction(
                    ActionType.CLICK,
                    "내 프로필이 있는 탭을 엽니다.",
                    target = path,
                ),
                reason = "현재 앱의 프로필 진입 탭을 semantic 정보로 식별",
            )
        }
        if (profileEntryElements.isNotEmpty()) {
            return WorkflowDecision(
                action = AgentAction(
                    ActionType.WAIT,
                    "프로필 진입 탭이 조작 가능한 상태가 될 때까지 잠시 기다립니다.",
                    waitMillis = 800,
                ),
                reason = "앱 홈 탭은 보이지만 프로필 진입 control이 아직 로딩 중",
            )
        }
        messageBackDecision(snapshot)?.let { back ->
            return back.copy(
                action = back.action?.copy(
                    description = "내 프로필 진입 화면이 있는 이전 화면으로 돌아갑니다.",
                ),
                reason = "현재 중첩 화면의 유일한 이전 control로 프로필 탐색을 계속함",
            )
        }
        return null
    }

    private fun messageDecision(
        intent: com.hwanghj09.sonju.task.UserIntent,
        snapshot: UiSnapshot,
        successfulActions: List<AgentAction>,
    ): WorkflowDecision? {
        val recipient = intent.entities["recipient"] ?: return null
        val message = intent.entities["message"] ?: return null
        if (snapshot.treeTruncated) return null
        val safeBackDecision = messageBackDecision(snapshot)
        val messagingHomeVisible = snapshot.elements.asSequence()
            .filter { it.visible && !it.sensitive }
            .flatMap { labels(it).asSequence() }
            .map(::compact)
            .any { label ->
                listOf("친구탭", "채팅탭", "대화탭", "friendstab", "chatstab", "messagestab")
                    .any(label::contains)
            }
        val fallbackBackDecision = when {
            safeBackDecision != null -> safeBackDecision
            messagingHomeVisible -> WorkflowDecision(
                action = AgentAction(
                    ActionType.WAIT,
                    "메시지 앱의 홈 화면이 안정될 때까지 잠시 기다립니다.",
                    waitMillis = 800,
                ),
                reason = "메시지 앱 홈 탭은 확인됐지만 검색 control이 아직 로딩 중",
            )
            else -> WorkflowDecision(
                action = AgentAction(
                    ActionType.BACK,
                    "메시지 검색 진입점이 있는 이전 화면으로 돌아갑니다.",
                ),
                reason = "현재 중첩 화면에 semantic 이전 control이 없어 표준 Android 뒤로가기를 사용",
            )
        }
        val editables = editableSurfaces(snapshot)
        val messageInputs = editables.filter { element ->
            labels(element).any { label -> MESSAGE_INPUT_TERMS.any(compact(label)::contains) }
        }.ifEmpty {
            editables.filterNot { element ->
                isSearchElement(element) || isLikelyTopSearchInput(snapshot, element)
            }.takeIf { it.size == 1 }.orEmpty()
        }
        messageInputs.singleOrNull()?.let { input ->
            if (compact(input.text.orEmpty()) == compact(message)) {
                val sendPaths = snapshot.elements.asSequence()
                    .filter { it.visible && it.enabled && !it.sensitive && !it.editable }
                    .filter { element ->
                        labels(element).any { label ->
                            val compactLabel = compact(label)
                            SEND_CONTROL_TERMS.any { term ->
                                compactLabel == term || compactLabel.endsWith(term)
                            }
                        }
                    }
                    .mapNotNull { clickablePath(snapshot, it) }
                    .distinct()
                    .toList()
                if (sendPaths.size == 1) {
                    return WorkflowDecision(
                        goalCompleted = true,
                        reason = "받는 사람과 메시지 초안 및 전송 직전 control을 화면에서 확인함",
                    )
                }
                return null
            }
            return WorkflowDecision(
                action = AgentAction(
                    ActionType.SET_TEXT,
                    "$recipient 대화방에 사용자가 말한 메시지 초안을 입력합니다.",
                    target = input.path,
                    value = message,
                ),
                reason = "현재 대화 화면의 유일한 비민감 메시지 입력란을 식별",
            )
        }

        val searchInput = editables.filter(::isSearchElement).singleOrNull()
            ?: editables.filter { isLikelyTopSearchInput(snapshot, it) }.singleOrNull()
        val recipientPaths = snapshot.elements.asSequence()
            .filter { it.visible && it.enabled && !it.sensitive && !it.editable }
            .filter { element -> labels(element).any { compact(it) == compact(recipient) } }
            .mapNotNull { clickablePath(snapshot, it) }
            .distinct()
            .toList()
        if (searchInput != null) {
            if (compact(searchInput.text.orEmpty()) != compact(recipient)) {
                return WorkflowDecision(
                    action = AgentAction(
                        ActionType.SET_TEXT,
                        "받는 사람을 찾기 위해 연락처 이름을 입력합니다.",
                        target = searchInput.path,
                        value = recipient,
                    ),
                    reason = "메시지 앱의 유일한 연락처 검색 입력란을 식별",
                )
            }
            recipientPaths.singleOrNull()?.let { path ->
                return WorkflowDecision(
                    action = AgentAction(
                        ActionType.CLICK,
                        "$recipient 대화방을 엽니다.",
                        target = path,
                    ),
                    reason = "입력한 이름과 정확히 일치하는 유일한 연락처를 식별",
                )
            }
            val recipientFilterPaths = snapshot.elements.asSequence()
                .filter {
                    it.visible && it.enabled && !it.sensitive && !it.editable && !it.selected
                }
                .filter { element ->
                    labels(element).any { compact(it) in RECIPIENT_FILTER_TERMS }
                }
                .mapNotNull { clickablePath(snapshot, it) }
                .distinct()
                .toList()
            recipientFilterPaths.singleOrNull()?.let { path ->
                return WorkflowDecision(
                    action = AgentAction(
                        ActionType.CLICK,
                        "검색 결과를 친구나 연락처로 좁힙니다.",
                        target = path,
                    ),
                    reason = "전체 검색에서 선택되지 않은 유일한 수신자 분류 control을 식별",
                )
            }
            return null
        }
        kakaoProfileChatAdapterDecision(
            recipient = recipient,
            snapshot = snapshot,
            successfulActions = successfulActions,
        )?.let { return it }
        recipientPaths.singleOrNull()?.let { path ->
            val broadContainerTarget = snapshot.elements.singleOrNull { it.path == path }
                ?.let { occupiesBroadScreenArea(snapshot, it) } == true
            if (broadContainerTarget || (!messagingHomeVisible && safeBackDecision != null)) {
                return WorkflowDecision(
                    action = AgentAction(
                        ActionType.CLICK,
                        "$recipient 프로필에서 1:1 채팅을 엽니다.",
                        target = "1:1 채팅",
                    ),
                    reason = "수신자 프로필은 확인했지만 채팅 control이 접근성 구조에 없어 시각 grounding이 필요",
                )
            }
            return WorkflowDecision(
                action = AgentAction(ActionType.CLICK, "$recipient 대화방을 엽니다.", target = path),
                reason = "현재 목록에서 정확히 일치하는 유일한 연락처를 식별",
            )
        }
        val searchDecision = searchControls(snapshot).singleOrNull()?.let { path ->
            WorkflowDecision(
                action = AgentAction(ActionType.CLICK, "받는 사람 검색을 엽니다.", target = path),
                reason = "현재 메시지 앱의 유일한 검색 control을 식별",
            )
        }
        searchDecision?.let { return it }
        return fallbackBackDecision
    }

    private fun kakaoProfileChatAdapterDecision(
        recipient: String,
        snapshot: UiSnapshot,
        successfulActions: List<AgentAction>,
    ): WorkflowDecision? {
        if (snapshot.packageName != KAKAO_TALK_PACKAGE || snapshot.treeTruncated) return null
        val window = snapshot.windowBounds ?: return null
        if (window.right <= window.left || window.bottom <= window.top ||
            window.bottom - window.top <= window.right - window.left
        ) return null
        if (snapshot.elements.none { element ->
                element.visible && element.viewId == KAKAO_PROFILE_HOME_VIEW_ID
            }
        ) return null
        val chatYRatio = kakaoProfileChatYRatio(snapshot) ?: return null
        val lastSuccessfulClick = successfulActions.lastOrNull { it.type == ActionType.CLICK }
            ?: return null
        val recipientTransitionVerified = lastSuccessfulClick.let { action ->
            compact(action.description).contains(compact(recipient)) &&
                MESSAGE_RECIPIENT_TRANSITION_TERMS.any {
                    compact(action.description).contains(it)
                }
        }
        if (!recipientTransitionVerified) return null

        // ponytail: This portrait Kakao profile ratio is an app-version ceiling; remove it when
        // profile_home exposes the 1:1 chat child semantically or an on-device OCR target replaces it.
        return WorkflowDecision(
            action = AgentAction(
                type = ActionType.CLICK_COORDINATE,
                description = "$recipient 프로필의 1:1 채팅을 엽니다.",
                target = KAKAO_PROFILE_CHAT_ADAPTER_TARGET,
                xRatio = KAKAO_PROFILE_CHAT_X_RATIO,
                yRatio = chatYRatio,
            ),
            reason = "정확한 수신자 전환 뒤 Kakao profile_home이 숨긴 1:1 채팅 위치를 앱 adapter로 식별",
            source = PlanSource.APP_ADAPTER,
            visualFallback = true,
        )
    }

    private fun messageBackDecision(snapshot: UiSnapshot): WorkflowDecision? {
        val backPaths = snapshot.elements.asSequence()
            .filter { it.visible && it.enabled && !it.sensitive }
            .filter { element ->
                labels(element).any { label ->
                    compact(label) in setOf("이전", "뒤로", "back", "navigateup")
                }
            }
            .mapNotNull { clickablePath(snapshot, it) }
            .distinct()
            .toList()
        return backPaths.singleOrNull()?.let { path ->
            val target = snapshot.elements.singleOrNull { it.path == path }
            if (target != null && occupiesBroadScreenArea(snapshot, target)) {
                return@let WorkflowDecision(
                    action = AgentAction(
                        ActionType.CLICK,
                        "접근성 구조에 없는 메시지 진입점을 현재 화면에서 찾습니다.",
                        target = null,
                    ),
                    reason = "이전 아이콘의 클릭 조상이 화면 전체라 직접 실행하지 않고 시각 grounding 요청",
                )
            }
            WorkflowDecision(
                action = AgentAction(ActionType.CLICK, "메시지 검색 화면으로 돌아갑니다.", target = path),
                reason = "완료 control과 구분되는 유일한 이전 탐색 control을 semantic 정보로 식별",
            )
        }
    }

    private fun directionsDecision(
        intent: com.hwanghj09.sonju.task.UserIntent,
        snapshot: UiSnapshot,
    ): WorkflowDecision? {
        val destination = intent.entities["destination"] ?: return null
        val destinationVisible = snapshot.elements.any { element ->
            element.visible && !element.sensitive && labels(element).any { label ->
                compact(label).contains(compact(destination))
            }
        }
        val routeState = snapshot.elements.asSequence()
            .filter { it.visible && !it.sensitive }
            .flatMap { labels(it).asSequence() }
            .map(::compact)
            .toSet()
        if (destinationVisible && (
                routeState.any { label -> ROUTE_READY_TERMS.any(label::contains) } ||
                    ROUTE_ENDPOINT_TERMS.all { endpoint ->
                        routeState.any { label -> label.contains(endpoint) }
                    }
                )
        ) {
            return WorkflowDecision(
                goalCompleted = true,
                reason = "목적지와 길찾기 경로 화면이 함께 확인됨",
            )
        }
        if (destinationVisible) {
            val directionSurfaces = semanticSurfaces(snapshot).filter { surface ->
                surface.labels.any { label ->
                    DIRECTIONS_CONTROL_TERMS.any { term ->
                        label == term || label.endsWith(term)
                    }
                }
            }
            directionSurfaces.singleOrNull()?.let { surface ->
                return WorkflowDecision(
                    action = AgentAction(
                        ActionType.CLICK,
                        "목적지 길찾기 화면을 엽니다.",
                        target = surface.path,
                    ),
                    reason = "목적지 화면의 유일한 길찾기 control을 식별",
                )
            }
            val destinationCandidates = snapshot.elements.filter { element ->
                element.visible && element.enabled && !element.sensitive &&
                    element.bounds.bottom > element.bounds.top && labels(element).any { label ->
                        compact(label).startsWith(compact(destination))
                    }
            }
            directionSurfaces.asSequence()
                .mapNotNull { direction ->
                    val nearestDestination = destinationCandidates.asSequence()
                        .filter { candidate ->
                            candidate.bounds.bottom <= direction.bounds.top &&
                                direction.bounds.top - candidate.bounds.bottom <=
                                MAX_DESTINATION_ROUTE_GAP_PIXELS
                        }
                        .maxByOrNull { it.bounds.bottom }
                        ?: return@mapNotNull null
                    direction to nearestDestination
                }
                .sortedBy { (direction, _) -> direction.bounds.top }
                .firstOrNull()
                ?.let { (direction, _) ->
                    return WorkflowDecision(
                        action = AgentAction(
                            ActionType.CLICK,
                            "검색 결과의 첫 번째 정확한 '$destination' 목적지에서 길찾기를 엽니다.",
                            target = direction.path,
                        ),
                        reason = "반복된 지명 결과에서 각 card의 지명과 길찾기 control을 공간적으로 연결",
                    )
                }
        }
        val editables = snapshot.elements.filter {
            it.visible && it.enabled && it.editable && !it.sensitive
        }
        val searchInput = editables.filter(::isSearchElement).singleOrNull()
            ?: editables.singleOrNull()
        if (searchInput != null) {
            if (compact(searchInput.text.orEmpty()) != compact(destination)) {
                return WorkflowDecision(
                    action = AgentAction(
                        ActionType.SET_TEXT,
                        "지도 검색창에 목적지를 입력합니다.",
                        target = searchInput.path,
                        value = destination,
                    ),
                    reason = "지도 화면의 유일한 검색 입력란을 식별",
                )
            }
            return WorkflowDecision(
                action = AgentAction(
                    ActionType.SUBMIT_TEXT,
                    "입력한 목적지를 제안 선택 없이 그대로 검색합니다.",
                    target = searchInput.path,
                    value = destination,
                ),
                reason = "지도 검색 입력란의 원문 목적지를 IME 동작으로 제출",
            )
        }
        return searchControls(snapshot).singleOrNull()?.let { path ->
            WorkflowDecision(
                action = AgentAction(ActionType.CLICK, "지도 검색을 엽니다.", target = path),
                reason = "지도 화면의 유일한 검색 control을 식별",
            )
        }
    }

    private fun foodOrderDecision(
        intent: com.hwanghj09.sonju.task.UserIntent,
        snapshot: UiSnapshot,
        successfulActions: List<AgentAction>,
    ): WorkflowDecision? {
        val restaurantQuery = intent.entities["restaurant_query"] ?: return null
        val menuQuery = intent.entities["menu_query"] ?: return null
        if (snapshot.treeTruncated) return null

        val visibleLabels = snapshot.elements.asSequence()
            .filter { it.visible && !it.sensitive }
            .flatMap { labels(it).asSequence() }
            .map(::compact)
            .filter(String::isNotBlank)
            .toList()
        val surfaces = semanticSurfaces(snapshot)
        val menuVisible = visibleLabels.any { it.contains(compact(menuQuery)) }
        val verifiedMenuSelection = successfulActions.any { action ->
            val description = compact(action.description)
            description.contains(compact(menuQuery)) &&
                VERIFIED_MENU_SELECTION_TERMS.any(description::contains)
        }
        val verifiedMenuAdded = successfulActions.any { action ->
            val description = compact(action.description)
            description.contains(compact(menuQuery)) &&
                VERIFIED_MENU_SELECTION_TERMS.any(description::contains) &&
                VERIFIED_ADD_TO_CART_TERMS.any(description::contains)
        }
        val verifiedOrderReviewTransition = successfulActions.any { action ->
            val description = compact(action.description)
            VERIFIED_ORDER_REVIEW_TRANSITION_TERMS.any(description::contains)
        }
        val checkoutVisible = visibleLabels.any { label ->
            CHECKOUT_SCREEN_TERMS.any(label::contains)
        }
        val finalCommit = surfaces.filter { surface ->
            surface.labels.any { label ->
                FINAL_ORDER_COMMIT_TERMS.any(label::contains) ||
                    checkoutVisible && GENERIC_ORDER_COMMIT_TERMS.any(label::contains)
            }
        }
        if ((checkoutVisible || verifiedOrderReviewTransition) &&
            (menuVisible || verifiedMenuSelection) && finalCommit.size == 1
        ) {
            return WorkflowDecision(
                goalCompleted = true,
                reason = "현재 주문서와 누르지 않은 최종 결제·주문 control, 성공한 메뉴 선택을 함께 확인함",
            )
        }

        val merchantDetailVisible = visibleLabels.any { label ->
            MERCHANT_DETAIL_TERMS.any(label::contains)
        }
        if (merchantDetailVisible && visibleLabels.any(::isUnavailableMerchantLabel)) {
            return WorkflowDecision(
                action = AgentAction(
                    ActionType.BACK,
                    "현재 주문할 수 없는 식당 상세에서 이전 결과로 돌아갑니다.",
                ),
                reason = "식당 상세의 영업 불가 상태를 화면 의미 정보로 확인",
            )
        }

        val awaitingMerchantDetail = successfulActions.lastOrNull()?.let { action ->
            action.type == ActionType.CLICK &&
                compact(action.description).contains("식당을선택")
        } == true
        val merchantContentVisible = merchantDetailVisible || menuVisible ||
            visibleLabels.any { label ->
                POPULAR_MENU_SECTION_TERMS.any(label::contains) ||
                    MENU_SEARCH_SECTION_TERMS.any(label::contains) ||
                    CART_VIEW_TERMS.any(label::contains) ||
                    ADD_TO_CART_TERMS.any(label::contains)
            }
        if (awaitingMerchantDetail && !merchantContentVisible) {
            return WorkflowDecision(
                action = AgentAction(
                    ActionType.WAIT,
                    "선택한 식당의 상세 내용이 로딩될 때까지 잠시 기다립니다.",
                    waitMillis = 800,
                ),
                reason = "식당 선택 전환은 확인됐지만 메뉴나 영업 정보가 아직 나타나지 않음",
            )
        }

        val cartVisible = visibleLabels.any { label -> CART_SCREEN_TERMS.any(label::contains) }
        if (verifiedMenuAdded) {
            surfaces.singleMatching(CART_VIEW_TERMS)?.let { cart ->
                return WorkflowDecision(
                    action = AgentAction(
                        ActionType.CLICK,
                        "담은 메뉴를 확인하기 위해 장바구니 보기를 엽니다.",
                        target = cart.path,
                    ),
                    reason = "현재 실행에서 요청 메뉴를 담은 뒤 유일한 장바구니 보기 control을 식별",
                )
            }
            val staleMenuVisible = menuVisible || visibleLabels.any { label ->
                POPULAR_MENU_SECTION_TERMS.any(label::contains) ||
                    MENU_SEARCH_SECTION_TERMS.any(label::contains) ||
                    ADD_TO_CART_TERMS.any(label::contains)
            }
            if (!cartVisible && staleMenuVisible) {
                return WorkflowDecision(
                    action = AgentAction(
                        ActionType.WAIT,
                        "담은 메뉴가 반영되고 장바구니 화면으로 이동할 수 있을 때까지 잠시 기다립니다.",
                        waitMillis = 800,
                    ),
                    reason = "현재 실행에서 메뉴 담기를 이미 확인했으므로 다른 메뉴를 다시 선택하지 않음",
                )
            }
        }

        val emptyCartVisible = visibleLabels.any { label -> EMPTY_CART_TERMS.any(label::contains) }
        if (cartVisible && emptyCartVisible) {
            return WorkflowDecision(
                action = AgentAction(
                    ActionType.BACK,
                    "빈 장바구니에서 이전 선택 화면으로 돌아갑니다.",
                ),
                reason = "장바구니 화면과 비어 있는 상태를 함께 확인",
            )
        }
        if (cartVisible) {
            surfaces.singleMatching(ORDER_REVIEW_TERMS)?.let { review ->
                return WorkflowDecision(
                    action = AgentAction(
                        ActionType.CLICK,
                        "최종 확정은 누르지 않고 주문서 화면으로 이동합니다.",
                        target = review.path,
                    ),
                    reason = "장바구니 화면의 유일한 주문서 이동 control을 식별",
                )
            }
        }

        val unresolvedRequiredChoice = visibleLabels.any { label ->
            REQUIRED_OPTION_PROMPTS.any(label::contains)
        }
        if (menuVisible && !unresolvedRequiredChoice) {
            surfaces.singleMatching(ADD_TO_CART_TERMS)?.let { add ->
                return WorkflowDecision(
                    action = AgentAction(
                        ActionType.CLICK,
                        "요청한 인기 메뉴 '$menuQuery'를 장바구니에 담습니다.",
                        target = add.path,
                    ),
                    reason = "메뉴 상세 화면의 유일한 장바구니 담기 control을 식별",
                )
            }
        }

        val menuSectionHeadingBottom = snapshot.elements.asSequence()
            .filter { it.visible && !it.sensitive }
            .filter { element ->
                labels(element).any { label ->
                    val compactLabel = compact(label)
                    POPULAR_MENU_SECTION_TERMS.any(compactLabel::contains) ||
                        compactLabel.contains(compact(menuQuery)) &&
                        MENU_SEARCH_SECTION_TERMS.any(compactLabel::contains)
                }
            }
            .map { it.bounds.bottom }
            .minOrNull()
        if (menuSectionHeadingBottom != null) {
            val menuCandidates = surfaces.filter { surface ->
                surface.bounds.top >= menuSectionHeadingBottom &&
                    surface.labels.any { it.contains(compact(menuQuery)) } &&
                    surface.labels.none { label -> FOOD_CONTROL_EXCLUSIONS.any(label::contains) }
            }.sortedWith(compareBy<SemanticSurface> { it.bounds.top }.thenBy { it.bounds.left })
            menuCandidates.firstOrNull()?.let { menu ->
                return WorkflowDecision(
                    action = AgentAction(
                        ActionType.CLICK,
                        "요청한 인기 메뉴 첫 번째 '$menuQuery' 항목을 선택합니다.",
                        target = menu.path,
                    ),
                    reason = "인기 메뉴 구역에서 요청한 종류와 일치하는 첫 비광고 메뉴를 식별",
                )
            }
            snapshot.elements.asSequence()
                .filter { it.visible && it.enabled && it.scrollable && !it.sensitive }
                .maxByOrNull { element ->
                    (element.bounds.right - element.bounds.left).toLong() *
                        (element.bounds.bottom - element.bounds.top)
                }
                ?.let { scrollable ->
                    return WorkflowDecision(
                        action = AgentAction(
                            ActionType.SCROLL_DOWN,
                            "요청한 메뉴 카드가 나타나도록 메뉴 목록을 아래로 이동합니다.",
                            target = scrollable.path,
                        ),
                        reason = "요청 메뉴 섹션 제목은 보이지만 실제 메뉴 card가 아직 화면 밖에 있음",
                    )
                }
        }

        val menuListContextVisible = visibleLabels.any { label ->
            POPULAR_MENU_SECTION_TERMS.any(label::contains) ||
                MENU_SEARCH_SECTION_TERMS.any(label::contains)
        }
        if (menuListContextVisible) {
            val menuCandidates = surfaces.filter { surface ->
                val combined = surface.labels.joinToString("")
                combined.contains(compact(menuQuery)) &&
                    MENU_PRICE_PATTERN.containsMatchIn(combined) &&
                    surface.labels.none { label ->
                        FOOD_CONTROL_EXCLUSIONS.any(label::contains) ||
                            RESTAURANT_ONLY_CARD_EVIDENCE.any(label::contains)
                    }
            }.sortedWith(compareBy<SemanticSurface> { it.bounds.top }.thenBy { it.bounds.left })
            menuCandidates.firstOrNull()?.let { menu ->
                return WorkflowDecision(
                    action = AgentAction(
                        ActionType.CLICK,
                        "현재 메뉴 목록의 첫 번째 인기 '$menuQuery' 항목을 선택합니다.",
                        target = menu.path,
                    ),
                    reason = "메뉴 탭 문맥과 가격은 있고 식당 배달 정보는 없는 첫 메뉴 card를 식별",
                )
            }
        }

        val sortSurfaces = surfaces.filter { surface ->
            val combined = surface.labels.joinToString(" ")
            val resultCardEvidence = combined.contains(compact(restaurantQuery)) ||
                RESTAURANT_CARD_EVIDENCE.count(combined::contains) >= 2
            !resultCardEvidence &&
                surface.labels.any { label -> isSortControlLabel(label, ALL_SORT_CONTROL_TERMS) }
        }.let { matches ->
            val leafPaths = leafInteractivePaths(matches.map(SemanticSurface::path)).toSet()
            matches.filter { it.path in leafPaths }
        }
        val popularSortSurfaces = sortSurfaces.filter { surface ->
            surface.labels.any { label -> isSortControlLabel(label, POPULAR_SORT_TERMS) }
        }
        val popularSortApplied = popularSortSurfaces.size == 1 && sortSurfaces.size == 1 ||
            snapshot.elements.any { element ->
                element.visible && (element.selected || element.checked) && labels(element).any { label ->
                    POPULAR_SORT_TERMS.any(compact(label)::contains)
                }
            }
        val unavailableRestaurantPaths = successfulActions.windowed(2).mapNotNull { actions ->
            val selection = actions[0]
            val recovery = actions[1]
            selection.target?.takeIf {
                selection.type == ActionType.CLICK &&
                    compact(selection.description).contains("식당을선택") &&
                    recovery.type == ActionType.BACK &&
                    compact(recovery.description).contains("주문할수없는식당")
            }
        }.toSet()
        val restaurantCandidates = surfaces.filter { surface ->
            val combined = surface.labels.joinToString(" ")
            val metadataCount = RESTAURANT_CARD_EVIDENCE.count(combined::contains)
            val matchesRequestedKind = combined.contains(compact(restaurantQuery))
            surface.path !in unavailableRestaurantPaths &&
                (matchesRequestedKind || metadataCount >= 2) &&
                surface.labels.none { label ->
                    FOOD_CONTROL_EXCLUSIONS.any(label::contains) ||
                        isAdvertisementLabel(label) || isUnavailableMerchantLabel(label)
                }
        }
        val rankedRestaurant = if (popularSortApplied) {
            restaurantCandidates.sortedWith(
                compareBy<SemanticSurface> { it.bounds.top }.thenBy { it.bounds.left },
            ).firstOrNull()
        } else if (sortSurfaces.size <= 1) {
            restaurantCandidates.asSequence()
                .map { it to popularityScore(it) }
                .filter { it.second > 0 }
                .sortedWith(
                    compareByDescending<Pair<SemanticSurface, Int>> { it.second }
                        .thenBy { it.first.bounds.top },
                )
                .firstOrNull()?.first
        } else {
            null
        }
        rankedRestaurant?.let { restaurant ->
            return WorkflowDecision(
                action = AgentAction(
                    ActionType.CLICK,
                    "요청한 인기순 결과의 첫 번째 비광고 '$restaurantQuery' 식당을 선택합니다.",
                    target = restaurant.path,
                ),
                reason = "화면에 노출된 주문 순위 또는 리뷰 수로 인기 식당 card를 식별",
            )
        }
        return null
    }

    private fun popularityScore(surface: SemanticSurface): Int {
        val combined = surface.labels.joinToString(" ")
        val explicitRank = ORDER_RANK.find(combined)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (explicitRank != null) return 1_000_000 - explicitRank
        return surface.labels.maxOfOrNull { label ->
            REVIEW_COUNT.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        } ?: 0
    }

    private fun genericSearchDecision(
        command: String,
        route: AppWorkflowRoute,
        snapshot: UiSnapshot,
        successfulActions: List<AgentAction>,
    ): WorkflowDecision? {
        val intent = DeterministicTaskParser.parse(command, listOf(route.appLabel))
        val query = intent.entities["query"]
            ?.let { stripLeadingAppLabel(it, route.appLabel) }
            ?.takeIf(String::isNotBlank)
            ?: return null
        val compactQuery = compact(query)
        if (compactQuery.length < 2) return null

        if (snapshot.treeTruncated) {
            // The verifier permits only an exact, currently visible local node on an incomplete
            // tree. Keep that narrow exception useful for the reversible entry into app search;
            // result, ranking, menu, and checkout choices still require a complete observation.
            return searchControls(snapshot).singleOrNull()?.let { path ->
                WorkflowDecision(
                    action = AgentAction(
                        type = ActionType.CLICK,
                        description = "${route.appLabel}의 검색 기능을 엽니다.",
                        target = path,
                    ),
                    reason = "잘린 트리에서도 현재 노드로 확인된 유일한 검색 control을 식별",
                )
            }
        }

        val editables = snapshot.elements.filter { element ->
            element.visible && element.enabled && element.editable && !element.sensitive
        }
        val searchEditables = editables.filter(::isSearchElement)
        val editable = when {
            searchEditables.size == 1 -> searchEditables.single()
            searchEditables.isEmpty() && editables.size == 1 &&
                isLikelyTopSearchInput(snapshot, editables.single()) -> editables.single()
            else -> null
        }
        val queryEvidence = snapshot.elements.any { element ->
            element.visible && !element.editable && !element.sensitive &&
                (clickablePath(snapshot, element) == null || isSearchElement(element)) &&
                labels(element).any { compact(it).contains(compactQuery) }
        }
        val resultPaths = snapshot.elements.asSequence()
            .filter { element ->
                element.visible && element.enabled && !element.sensitive &&
                    !isSearchElement(element) && labels(element).any(::isResultLabel)
            }
            .mapNotNull { clickablePath(snapshot, it) }
            .distinct()
            .toList()
        val allSortPaths = snapshot.elements.asSequence()
            .filter { it.visible && it.enabled && !it.sensitive }
            .filter { element ->
                labels(element).any { label ->
                    isSortControlLabel(compact(label), ALL_SORT_CONTROL_TERMS)
                }
            }
            .mapNotNull { clickablePath(snapshot, it) }
            .distinct()
            .toList()
            .let(::leafInteractivePaths)
        val exactEditableQuery = editable != null &&
            compact(editable.text.orEmpty()) == compactQuery
        val resultScreen = resultPaths.size >= MIN_RESULT_TARGETS && (
            editable == null && queryEvidence ||
                exactEditableQuery && !editable.focused && (
                    allSortPaths.isNotEmpty() || snapshot.elements.any { element ->
                        element.visible && !element.sensitive && !isSearchElement(element) &&
                            labels(element).any { label ->
                                isResultLabel(label) && !compact(label).contains(compactQuery)
                            }
                    }
                )
        )
        if (resultScreen) {
            val orderWorkflow = intent.entities.containsKey("restaurant_query") &&
                intent.entities.containsKey("menu_query")
            val sortConstraint = intent.constraints.firstOrNull {
                it.field == "sort" || it.field == "restaurant_sort"
            }
            if (sortConstraint == null) {
                if (orderWorkflow) return null
                return WorkflowDecision(
                    goalCompleted = true,
                    reason = "검색어와 구조화된 결과 항목이 현재 화면에서 함께 확인됨",
                )
            }
            val sortTerms = when (sortConstraint.value) {
                "popular" -> POPULAR_SORT_TERMS
                "rating" -> RATING_SORT_TERMS
                else -> emptySet()
            }
            val matches = snapshot.elements.asSequence()
                .filter { it.visible && it.enabled && !it.sensitive }
                .filter { element ->
                    labels(element).any { label ->
                        isSortControlLabel(compact(label), sortTerms)
                    }
                }
                .toList()
            if (matches.any {
                    it.selected || it.checked || compact(it.stateDescription.orEmpty()).contains("선택")
                }
            ) {
                if (orderWorkflow) return null
                return WorkflowDecision(
                    goalCompleted = true,
                    reason = "검색어와 요청한 정렬 상태가 현재 화면에서 함께 확인됨",
                )
            }
            val matchPaths = matches.mapNotNull { clickablePath(snapshot, it) }.distinct()
            if (matchPaths.size == 1 && allSortPaths == matchPaths) {
                if (orderWorkflow) return null
                return WorkflowDecision(
                    goalCompleted = true,
                    reason = "결과 화면의 현재 정렬 control이 요청한 기준과 일치함",
                )
            }
            matchPaths.singleOrNull()?.let { path ->
                return WorkflowDecision(
                    action = AgentAction(
                        type = ActionType.CLICK,
                        description = "검색 결과를 요청한 기준으로 정렬합니다.",
                        target = path,
                    ),
                    reason = "정렬 선택 화면의 유일한 요청 기준을 semantic 정보로 식별",
                )
            }
            val explicitSortOpenPaths = snapshot.elements.asSequence()
                .filter { it.visible && it.enabled && !it.sensitive }
                .filter { element ->
                    labels(element).any { label ->
                        val compactLabel = compact(label)
                        SORT_OPEN_TERMS.any { term ->
                            compactLabel == term || compactLabel.endsWith(term)
                        }
                    }
                }
                .mapNotNull { clickablePath(snapshot, it) }
                .distinct()
                .toList()
            explicitSortOpenPaths.singleOrNull()?.let { path ->
                return WorkflowDecision(
                    action = AgentAction(
                        type = ActionType.CLICK,
                        description = "검색 결과의 정렬 기준을 엽니다.",
                        target = path,
                    ),
                    reason = "결과 화면의 유일한 정렬 control을 semantic 정보로 식별",
                )
            }
            if (allSortPaths.size == 1) {
                if (orderWorkflow) return null
                return WorkflowDecision(
                    goalCompleted = true,
                    reason = "앱이 대체 정렬 선택지를 노출하지 않아 구조화된 기본 랭킹 결과를 확인함",
                )
            }
            return null
        }
        // After submission, an address editor shows the destination URL instead of the query.
        // Read the result before choosing another action; never restart the same search locally.
        if (successfulActions.any { it.type == ActionType.SUBMIT_TEXT && compact(it.value.orEmpty()) == compactQuery }) {
            return null
        }
        if (editable != null) {
            if (compact(editable.text.orEmpty()) != compactQuery) {
                return WorkflowDecision(
                    action = AgentAction(
                        type = ActionType.SET_TEXT,
                        description = "${route.appLabel} 검색창에 '$query'을 입력합니다.",
                        target = editable.path,
                        value = query,
                    ),
                    reason = "현재 화면의 유일한 검색 입력란을 로컬 semantic 정보로 식별",
                )
            }
            if (com.hwanghj09.sonju.verifier.SearchSubmissionPolicy.isSearchOrAddressField(editable, snapshot)) {
                return WorkflowDecision(
                    action = AgentAction(
                        type = ActionType.SUBMIT_TEXT,
                        description = "입력한 '$query'을 제안 선택 없이 그대로 검색합니다.",
                        target = editable.path,
                        value = query,
                    ),
                    reason = "관찰한 검색 또는 주소 입력란의 실제 편집기 동작으로 검색어를 확정",
                )
            }
            val submitTargets = searchControls(snapshot)
                .filterNot { path -> path == editable.path || editable.path.startsWith("$path.") }
            submitTargets.singleOrNull()?.let { path ->
                return WorkflowDecision(
                    action = AgentAction(
                        type = ActionType.CLICK,
                        description = "'$query' 검색을 실행합니다.",
                        target = path,
                    ),
                    reason = "현재 화면의 유일한 검색 실행 control을 선택",
                )
            }
            return WorkflowDecision(
                action = AgentAction(
                    type = ActionType.SUBMIT_TEXT,
                    description = "입력한 '$query'을 제안 선택 없이 그대로 검색합니다.",
                    target = editable.path,
                    value = query,
                ),
                reason = "자동완성 후보를 추측하지 않고 검증된 입력란에 IME 제출을 요청",
            )
        }

        searchControls(snapshot).singleOrNull()?.let { path ->
            return WorkflowDecision(
                action = AgentAction(
                    type = ActionType.CLICK,
                    description = "${route.appLabel}의 검색 기능을 엽니다.",
                    target = path,
                ),
                reason = "앱 종류와 무관하게 유일한 검색 control을 semantic 정보로 식별",
            )
        }
        return null
    }

    private fun genericMenuDecision(
        command: String,
        route: AppWorkflowRoute,
        snapshot: UiSnapshot,
    ): WorkflowDecision? {
        if (snapshot.treeTruncated ||
            DeterministicTaskParser.parse(command).entities.containsKey("query")
        ) return null
        val surface = route.targetSurface.replace(TARGET_ACTION_SUFFIX, "").trim()
        val compactSurface = compact(surface)
        if (compactSurface.length < 2) return null
        val tokens = normalize(surface).split(' ')
            .map { it.trim().trimEnd('을', '를', '은', '는', '이', '가') }
            .filter { compact(it).length >= 2 && compact(it) !in TARGET_STOP_WORDS }
        val candidates = snapshot.elements.asSequence()
            .filter { it.visible && it.enabled && !it.sensitive }
            .mapNotNull { element ->
                val path = clickablePath(snapshot, element) ?: return@mapNotNull null
                val score = labels(element).maxOfOrNull { label ->
                    val compactLabel = compact(label)
                    when {
                        compactLabel == compactSurface -> 120
                        compactSurface.length >= 2 && compactLabel.contains(compactSurface) -> 105
                        tokens.isNotEmpty() && tokens.all { compactLabel.contains(compact(it)) } ->
                            85 + tokens.size.coerceAtMost(5) * 3
                        tokens.size == 1 && compactLabel.contains(compact(tokens.single())) -> 85
                        else -> 0
                    }
                } ?: 0
                if (score < MIN_GENERIC_TARGET_SCORE) null else path to score
            }
            .groupBy({ it.first }, { it.second })
            .map { (path, scores) -> path to scores.max() }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        val best = candidates.firstOrNull() ?: return null
        val runnerUp = candidates.getOrNull(1)
        if (runnerUp != null && best.second - runnerUp.second < GENERIC_TARGET_MARGIN) return null
        return WorkflowDecision(
            action = AgentAction(
                type = ActionType.CLICK,
                description = "${route.appLabel}에서 ${route.targetSurface} 항목을 엽니다.",
                target = best.first,
            ),
            reason = "설치 앱과 무관하게 유일한 semantic menu target을 식별",
        )
    }

    private fun searchControls(snapshot: UiSnapshot): List<String> = snapshot.elements.asSequence()
        .filter { it.visible && it.enabled && !it.sensitive && !it.editable && isSearchControl(it) }
        .mapNotNull { clickablePath(snapshot, it) }
        .toList()
        .let(::leafInteractivePaths)

    /** WebViews sometimes expose both their full container and the real field as editable. */
    private fun editableSurfaces(snapshot: UiSnapshot): List<UiElement> {
        val candidates = snapshot.elements.filter {
            it.visible && it.enabled && it.editable && !it.sensitive &&
                it.bounds.right > it.bounds.left && it.bounds.bottom > it.bounds.top &&
                (it.bounds.right - it.bounds.left) * 2 >=
                it.bounds.bottom - it.bounds.top
        }.groupBy(UiElement::bounds).values.map { sameBounds ->
            sameBounds.minWithOrNull(
                compareBy<UiElement> {
                    if (it.className.substringAfterLast('.').equals("EditText", true)) 0 else 1
                }.thenByDescending { it.path.count { character -> character == '.' } },
            ) ?: sameBounds.first()
        }
        return candidates.filter { outer ->
            candidates.none { inner ->
                inner !== outer && strictlyContains(outer.bounds, inner.bounds)
            }
        }
    }

    private fun strictlyContains(outer: ScreenBounds, inner: ScreenBounds): Boolean {
        val outerArea = (outer.right - outer.left).toLong() * (outer.bottom - outer.top)
        val innerArea = (inner.right - inner.left).toLong() * (inner.bottom - inner.top)
        return outerArea > innerArea && outer.left <= inner.left && outer.top <= inner.top &&
            outer.right >= inner.right && outer.bottom >= inner.bottom
    }

    /** Unlabelled full-width fields near the top are common in embedded app search screens. */
    private fun isLikelyTopSearchInput(snapshot: UiSnapshot, element: UiElement): Boolean {
        val screenRight = snapshot.elements.maxOfOrNull { it.bounds.right } ?: return false
        val screenBottom = snapshot.elements.maxOfOrNull { it.bounds.bottom } ?: return false
        val width = element.bounds.right - element.bounds.left
        val height = element.bounds.bottom - element.bounds.top
        return screenRight > 0 && screenBottom > 0 && width * 2 >= screenRight &&
            height > 0 && height * 5 <= screenBottom && element.bounds.top <= screenBottom / 4
    }

    private fun semanticSurfaces(snapshot: UiSnapshot): List<SemanticSurface> {
        val labelsByPath = linkedMapOf<String, MutableSet<String>>()
        snapshot.elements.asSequence()
            .filter { it.visible && it.enabled && !it.sensitive }
            .forEach { element ->
                val path = clickablePath(snapshot, element) ?: return@forEach
                val surfaceLabels = labelsByPath.getOrPut(path) { linkedSetOf() }
                labels(element).map(::compact).filter(String::isNotBlank).forEach(surfaceLabels::add)
            }
        return labelsByPath.mapNotNull { (path, surfaceLabels) ->
            val target = snapshot.elements.firstOrNull { it.path == path } ?: return@mapNotNull null
            SemanticSurface(path, surfaceLabels.toList(), target.bounds)
        }
    }

    private fun List<SemanticSurface>.singleMatching(terms: Set<String>): SemanticSurface? =
        filter { surface ->
            surface.labels.any { label ->
                terms.any { term -> label == term || label.endsWith(term) || label.contains(term) }
            }
        }.distinctBy(SemanticSurface::path).singleOrNull()

    /** Compose often exposes one control as both a clickable wrapper and clickable semantic child. */
    private fun leafInteractivePaths(paths: List<String>): List<String> {
        val unique = paths.distinct()
        return unique.filter { path ->
            unique.none { other -> other != path && other.startsWith("$path.") }
        }
    }

    private fun isSearchElement(element: UiElement): Boolean =
        element.editable && com.hwanghj09.sonju.verifier.SearchSubmissionPolicy.isSearchOrAddressField(element) ||
        labels(element).any { value ->
        val compactValue = compact(value)
        compactValue in SEARCH_CONTROL_TERMS ||
            SEARCH_CONTROL_TERMS.any { term ->
                compactValue.startsWith(term) || compactValue.endsWith(term)
            }
    }

    private fun isSearchControl(element: UiElement): Boolean = isSearchElement(element) &&
        labels(element).none { value ->
            val compactValue = compact(value)
            CLEAR_SEARCH_TERMS.any(compactValue::contains)
        }

    /** Weak metadata such as `star_rating` is not a sort control unless sort semantics are explicit. */
    private fun isSortControlLabel(label: String, terms: Set<String>): Boolean = terms.any { rawTerm ->
        val term = compact(rawTerm)
        when {
            term.endsWith("순") -> label.contains(term)
            term == "sort" -> label == term || label.startsWith(term) || label.endsWith(term)
            else -> label == term || label.contains("sort") && label.contains(term)
        }
    }

    /** `ad` is a marker only as a whole label; words such as `badge` are not advertisements. */
    private fun isAdvertisementLabel(label: String): Boolean = AD_LABEL_TERMS.any { term ->
        if (term == "ad") label == term else label.contains(term)
    }

    private fun isUnavailableMerchantLabel(label: String): Boolean =
        UNAVAILABLE_MERCHANT_TERMS.any(label::contains) ||
            SCHEDULED_OPENING_PATTERN.containsMatchIn(label)

    private fun labels(element: UiElement): List<String> = listOfNotNull(
        element.text,
        element.contentDescription,
        element.hintText,
        element.paneTitle,
        element.tooltipText,
        element.viewId?.substringAfterLast('/'),
    ).filter(String::isNotBlank)

    private fun stripLeadingAppLabel(value: String, appLabel: String): String {
        val prefix = Regex(
            "^\\s*${Regex.escape(appLabel)}(?:\\s*앱)?(?:을|를|에서|으로|로)?\\s*",
            RegexOption.IGNORE_CASE,
        )
        return value.replace(prefix, "").trim().ifBlank { value.trim() }
    }

    private fun isResultLabel(value: String): Boolean {
        val compactValue = compact(value)
        return compactValue.length >= 2 &&
            RESULT_NAVIGATION_TERMS.none(compactValue::contains)
    }

    private fun occupiesBroadScreenArea(snapshot: UiSnapshot, element: UiElement): Boolean {
        val width = element.bounds.right - element.bounds.left
        val height = element.bounds.bottom - element.bounds.top
        if (width <= 0 || height <= 0) return false
        snapshot.windowBounds?.let { window ->
            val windowWidth = window.right - window.left
            val windowHeight = window.bottom - window.top
            if (windowWidth > 0 && windowHeight > 0) {
                return width.toLong() * height * 2 >= windowWidth.toLong() * windowHeight
            }
        }
        return false
    }

    private fun clickablePath(snapshot: UiSnapshot, element: UiElement): String? {
        if (element.clickable || UiNodeAction.CLICK in element.availableActions) return element.path
        return generateSequence(element.path) { path ->
            path.substringBeforeLast('.', missingDelimiterValue = "").takeIf(String::isNotBlank)
        }.drop(1).take(MAX_CLICKABLE_ANCESTOR_DEPTH)
            .firstOrNull { path ->
                snapshot.elements.firstOrNull { it.path == path }?.let { ancestor ->
                    ancestor.visible && ancestor.enabled && !ancestor.sensitive &&
                        (ancestor.clickable || UiNodeAction.CLICK in ancestor.availableActions)
                } == true
            }
    }

    private fun installedAppPrefix(command: String, labels: Collection<String>): String? {
        val normalizedCommand = normalize(command)
        return labels.asSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && compact(it).length >= 2 }
            .distinctBy(::compact)
            .sortedByDescending { normalize(it).length }
            .firstOrNull { label ->
                val normalizedLabel = normalize(label)
                normalizedCommand.startsWith(normalizedLabel) &&
                    normalizedCommand.removePrefix(normalizedLabel).matches(APP_PREFIX_BOUNDARY)
            }
    }

    private fun installedAppMention(command: String, labels: Collection<String>): String? {
        val compactCommand = compact(command)
        return labels.asSequence()
            .map(String::trim)
            .filter { compact(it).length >= 2 && compactCommand.contains(compact(it)) }
            .distinctBy(::compact)
            .sortedByDescending { compact(it).length }
            .firstOrNull()
    }

    private fun inferredDomainApp(
        intent: com.hwanghj09.sonju.task.UserIntent,
        labels: Collection<String>,
    ): String? {
        val preferences = when {
            intent.entities.containsKey("restaurant_query") -> DELIVERY_APP_PREFERENCES
            intent.entities.containsKey("recipient") && intent.entities.containsKey("message") ->
                MESSAGING_APP_PREFERENCES
            intent.entities.containsKey("destination") -> MAP_APP_PREFERENCES
            else -> return null
        }
        val candidates = labels.map(String::trim).filter(String::isNotBlank)
        preferences.forEach { preference ->
            candidates.firstOrNull { label ->
                val compactLabel = compact(label)
                compactLabel == compact(preference) || compactLabel.contains(compact(preference))
            }?.let { return it }
        }
        return null
    }

    private data class WorkflowDecision(
        val action: AgentAction? = null,
        val goalCompleted: Boolean = false,
        val reason: String,
        val source: PlanSource = PlanSource.LOCAL_RULE,
        val visualFallback: Boolean = false,
    )

    private data class SemanticSurface(
        val path: String,
        val labels: List<String>,
        val bounds: ScreenBounds,
    )

    private fun normalize(value: String): String = Normalizer.normalize(
        value.trim().lowercase(),
        Normalizer.Form.NFKC,
    ).replace(Regex("\\s+"), " ")

    private fun compact(value: String): String = normalize(value)
        .replace(Regex("[^\\p{L}\\p{Nd}]"), "")

    private val LEADING_APP_GRAMMAR = Regex("^(?:앱\\s*)?(?:을|를)?\\s*(?:에서|으로|로)?\\s*")
    private val LEADING_WORKFLOW_GRAMMAR = Regex("^(?:들어가서|열어서)\\s*")
    private val APP_PREFIX_BOUNDARY = Regex(
        "^(?:\\s+|앱(?:을|를)?\\s*|(?:을|를|에서|으로|로)\\s*|(?:들어가서|열어서)\\s*).+",
    )
    private val SIMPLE_LAUNCH = Regex(
        "^(?:앱\\s*)?(?:을|를)?\\s*(?:열기|열어|켜기|켜|실행하기|실행해|실행|들어가기|들어가)" +
            "(?:\\s*(?:해)?\\s*(?:줘|주세요))?[.!?]?$",
    )
    private val NON_APP_TARGET_TERMS = setOf(
        "화면", "설정", "버튼", "링크", "메뉴", "파일", "문서", "사진", "영상", "내용",
    ).map(::compact).toSet()
    private val BUILT_IN_WORKFLOW_APPS = setOf("설정").map(::compact).toSet()
    private val TARGET_ACTION_SUFFIX = Regex(
        "(?:\\s*(?:열기|열어|들어가기|들어가|찾기|찾아|검색하기|검색해|" +
            "선택하기|선택해|눌러|보여)(?:\\s*(?:줘|주세요))?)+[.!?]?$",
        RegexOption.IGNORE_CASE,
    )
    private val TARGET_STOP_WORDS = setOf("가장", "제일", "최근", "있는", "항목", "메뉴")
        .map(::compact).toSet()
    private val PROFILE_EDIT_TERMS = setOf("바꾸", "변경", "수정", "편집").map(::compact)
    private val PROFILE_EDIT_LABELS = setOf("프로필 편집", "프로필 수정", "edit profile").map(::compact)
    private val OWN_PROFILE_TERMS = setOf("내 프로필", "나의 프로필", "my profile").map(::compact)
    private val MESSAGE_INPUT_TERMS = setOf(
        "메시지 입력", "채팅 입력", "대화 입력", "message input", "type a message", "chat input",
    ).map(::compact)
    private val RECIPIENT_FILTER_TERMS = setOf(
        "친구", "연락처", "사람", "contacts", "people",
    ).map(::compact).toSet()
    private val MESSAGE_RECIPIENT_TRANSITION_TERMS = setOf(
        "대화방", "연락처", "프로필", "chat", "contact", "profile",
    ).map(::compact).toSet()
    private val SEND_CONTROL_TERMS = setOf("전송", "보내기", "send").map(::compact)
    private val DIRECTIONS_CONTROL_TERMS = setOf("길찾기", "경로", "directions", "route").map(::compact)
    private val ROUTE_READY_TERMS = setOf(
        "안내 시작", "경로 옵션", "추천 경로", "소요 시간", "경로 미리보기", "start navigation",
    ).map(::compact)
    private val ROUTE_ENDPOINT_TERMS = setOf("출발", "도착").map(::compact)
    private val CROSS_SELL_TERMS = setOf(
        "함께 먹으면", "함께 구매", "추가 메뉴", "추천 상품", "곁들임",
        "you may also like", "frequently bought", "add on",
    ).map(::compact).toSet()
    private val INTERRUPTION_OFFER_TERMS = setOf(
        "가입", "구독", "무료 체험", "멤버십", "회원 혜택", "upgrade", "subscribe",
        "subscription", "membership", "free trial", "더 할인", "추가 할인", "할인 없이",
        "혜택 적용", "extra discount", "without discount",
    ).map(::compact).toSet() + CROSS_SELL_TERMS
    private val INTERRUPTION_DISMISS_TERMS = setOf(
        "닫기", "나중에", "건너뛰기", "아니요", "close", "not now", "skip",
    ).map(::compact).toSet()
    private val RECOVERABLE_ALERT_TERMS = setOf(
        "할 수 없습니다", "사용할 수 없", "이용할 수 없", "진행할 수 없", "다시 시도",
        "try again", "unavailable", "could not", "couldn't", "cannot",
    ).map(::compact).toSet()
    private val ALERT_DISMISS_TERMS = setOf(
        "확인", "알겠어요", "알겠습니다", "닫기", "ok", "got it", "close",
    ).map(::compact).toSet()
    private val CART_REPLACEMENT_PROMPT_TERMS = setOf(
        "같은 가게의 메뉴만", "이전에 담은 메뉴가 삭제", "장바구니를 교체",
        "items from another store", "replace your cart", "clear your cart",
    ).map(::compact).toSet()
    private val ADD_TO_CART_EXACT_TERM = compact("담기")
    private val CART_REPLACEMENT_CONFIRM_TERMS = setOf(
        "담기", "계속 담기", "교체하고 담기", "replace cart", "add anyway",
    ).map(::compact).toSet()
    private val CHECKOUT_SCREEN_TERMS = setOf(
        "담은 메뉴", "결제수단", "주문자", "배달주소", "주문금액", "할인쿠폰",
        "order summary", "your order", "payment method",
    ).map(::compact).toSet()
    private val VERIFIED_MENU_SELECTION_TERMS = setOf(
        "요청한 인기 메뉴", "requested popular menu",
    ).map(::compact).toSet()
    private val VERIFIED_ADD_TO_CART_TERMS = setOf(
        "장바구니에 담", "add to cart",
    ).map(::compact).toSet()
    private val VERIFIED_ORDER_REVIEW_TRANSITION_TERMS = setOf(
        "주문서 화면으로 이동", "review order",
    ).map(::compact).toSet()
    private val FINAL_ORDER_COMMIT_TERMS = setOf(
        "결제하기", "결제 및 주문", "결제 후 주문", "pay now", "place order and pay",
    ).map(::compact).toSet()
    private val GENERIC_ORDER_COMMIT_TERMS = setOf(
        "주문하기", "주문 확정", "place order",
    ).map(::compact).toSet()
    private val CART_VIEW_TERMS = setOf(
        "장바구니 보기", "카트 보기", "view cart",
    ).map(::compact).toSet()
    private val CART_SCREEN_TERMS = setOf("장바구니", "카트", "cart").map(::compact).toSet()
    private val EMPTY_CART_TERMS = setOf(
        "담은 메뉴가 없", "장바구니가 비어", "카트가 비어", "empty cart", "cart is empty",
        "no items", "nothing in your cart",
    ).map(::compact).toSet()
    private val ORDER_REVIEW_TERMS = setOf(
        "배달 주문하기", "포장 주문하기", "주문서로 이동", "review order",
    ).map(::compact).toSet()
    private val ADD_TO_CART_TERMS = setOf(
        "장바구니 담기", "메뉴 담기", "담기", "add to cart",
    ).map(::compact).toSet()
    private val REQUIRED_OPTION_PROMPTS = setOf(
        "필수 옵션을 선택", "필수 선택", "선택해 주세요", "선택해주세요", "required option",
    ).map(::compact).toSet()
    private val POPULAR_MENU_SECTION_TERMS = setOf(
        "인기 메뉴", "인기메뉴", "많이 주문", "popular menu", "most ordered",
    ).map(::compact).toSet()
    private val MENU_SEARCH_SECTION_TERMS = setOf(
        "검색한 메뉴", "메뉴 검색 결과", "matching menus", "menu results",
    ).map(::compact).toSet()
    private val RESTAURANT_CARD_EVIDENCE = setOf(
        "별점", "리뷰", "배달비", "최소주문", "도착예정", "rating", "review", "deliveryfee",
    ).map(::compact).toSet()
    private val RESTAURANT_ONLY_CARD_EVIDENCE = setOf(
        "최소주문", "배달비", "배달팁", "도착예정", "km", "minimumorder", "deliveryfee", "eta",
    ).map(::compact).toSet()
    private val MENU_PRICE_PATTERN = Regex("\\d{4,9}원")
    private val FOOD_CONTROL_EXCLUSIONS = setOf(
        "검색", "정렬", "인기순", "주문많은순", "별점순", "장바구니", "담기", "결제",
        "뒤로", "닫기", "search", "sort", "cart", "checkout", "back", "close",
    ).map(::compact).toSet()
    private val AD_LABEL_TERMS = setOf(
        "광고", "sponsored", "advertisement", "adbadge",
    ).map(::compact).toSet()
    private val MERCHANT_DETAIL_TERMS = setOf(
        "운영안내", "영업시간", "business hours", "opening hours",
    ).map(::compact).toSet()
    private val UNAVAILABLE_MERCHANT_TERMS = setOf(
        "영업 준비중", "영업 종료", "주문 불가", "현재 주문할 수 없", "currently closed",
        "closed for orders", "temporarily unavailable",
    ).map(::compact).toSet()
    private val SCHEDULED_OPENING_PATTERN = Regex("(?:오늘)?(?:오전|오후)\\d{3,4}오픈")
    private val ORDER_RANK = Regex("주문(\\d{1,3})위")
    private val REVIEW_COUNT = Regex("(\\d{1,7})(?:개|리뷰|reviews?)")
    private val DELIVERY_APP_PREFERENCES = listOf(
        "배달의민족", "배민", "쿠팡이츠", "요기요", "땡겨요", "delivery",
    )
    private val MESSAGING_APP_PREFERENCES = listOf(
        "카카오톡", "kakaotalk", "메시지", "messages", "문자",
    )
    private val MAP_APP_PREFERENCES = listOf(
        "네이버 지도", "네이버지도", "카카오맵", "구글 지도", "지도", "maps", "tmap",
    )
    private val SEARCH_CONTROL_TERMS = setOf(
        "검색", "검색하기", "search", "searchbutton", "searchview", "searchicon",
    ).map(::compact).toSet()
    private val CLEAR_SEARCH_TERMS = setOf(
        "검색어 삭제", "검색어 지우기", "검색 지우기", "clear search", "clear query", "취소", "cancel",
    ).map(::compact).toSet()
    private val POPULAR_SORT_TERMS = setOf("인기순", "인기", "주문많은순", "popular")
    private val RATING_SORT_TERMS = setOf("평점순", "평점", "별점순", "rating")
    private val ALL_SORT_CONTROL_TERMS = POPULAR_SORT_TERMS + RATING_SORT_TERMS + setOf(
        "기본순", "추천순", "가까운순", "거리순", "배달빠른순", "정렬", "sort",
    )
    private val SORT_OPEN_TERMS = setOf("정렬", "정렬하기", "sort", "sortby").map(::compact)
    private val RESULT_NAVIGATION_TERMS = setOf(
        "뒤로", "back", "닫기", "close", "메뉴", "menu", "홈", "home", "검색", "search",
    ).map(::compact).toSet()
    private const val MIN_GENERIC_TARGET_SCORE = 85
    private const val GENERIC_TARGET_MARGIN = 15
    private const val MAX_CLICKABLE_ANCESTOR_DEPTH = 6
    private const val MAX_DESTINATION_ROUTE_GAP_PIXELS = 480
    private const val MIN_RESULT_TARGETS = 2
    private const val KAKAO_TALK_PACKAGE = "com.kakao.talk"
    private const val KAKAO_PROFILE_HOME_VIEW_ID = "com.kakao.talk:id/profile_home"
    private val KAKAO_PROFILE_PHOTO_TOP_RANGE = 0.56..0.65
    private const val KAKAO_PROFILE_PHOTO_TO_CHAT_CENTER_OFFSET = 0.055
}
