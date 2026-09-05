package com.hwanghj09.sonju.verifier

import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.AgentAction
import com.hwanghj09.sonju.agent.AgentPlan
import com.hwanghj09.sonju.agent.AppWorkflowRouter
import com.hwanghj09.sonju.agent.PlanSource
import com.hwanghj09.sonju.agent.UiSnapshot
import com.hwanghj09.sonju.grounding.DeterministicSemanticGrounder
import com.hwanghj09.sonju.grounding.GroundingQuery
import com.hwanghj09.sonju.grounding.GroundingResult
import com.hwanghj09.sonju.grounding.InteractionRequirement
import com.hwanghj09.sonju.grounding.SemanticGrounder
import com.hwanghj09.sonju.perception.ScreenState
import com.hwanghj09.sonju.task.CanonicalTask
import com.hwanghj09.sonju.task.CompletionLevel
import com.hwanghj09.sonju.task.Constraint
import com.hwanghj09.sonju.task.TaskRisk
import com.hwanghj09.sonju.task.UserIntent
import java.text.Normalizer

sealed interface StateValue {
    data class Text(val value: String) : StateValue
    data class Number(val value: Double) : StateValue
    data class Flag(val value: Boolean) : StateValue
}

sealed interface Predicate {
    data object True : Predicate
    data class AppIs(val appId: String) : Predicate
    data class ScreenIs(val screenType: String) : Predicate
    data class TextExists(val text: String) : Predicate
    data class NodeExists(val query: GroundingQuery) : Predicate
    data class FieldEquals(val key: String, val value: String) : Predicate
    data class FieldMatches(val key: String, val pattern: String) : Predicate
    data class NumericLessThan(val key: String, val value: Double) : Predicate
    data class NumericAtMost(val key: String, val value: Double) : Predicate
    data class ScreenChanged(val previousFingerprint: String) : Predicate
    data class Not(val predicate: Predicate) : Predicate
    data class All(val predicates: List<Predicate>) : Predicate
    data class Any(val predicates: List<Predicate>) : Predicate
}

interface PredicateEvaluator {
    fun evaluate(
        predicate: Predicate,
        screen: ScreenState,
        extractedState: Map<String, StateValue> = emptyMap(),
    ): Boolean
}

class DeterministicPredicateEvaluator(
    private val grounder: SemanticGrounder = DeterministicSemanticGrounder(),
) : PredicateEvaluator {
    override fun evaluate(
        predicate: Predicate,
        screen: ScreenState,
        extractedState: Map<String, StateValue>,
    ): Boolean = when (predicate) {
        Predicate.True -> true
        is Predicate.AppIs -> normalize(screen.packageName) == normalize(predicate.appId) ||
            normalize(screen.packageName).contains(normalize(predicate.appId))
        is Predicate.ScreenIs -> screen.screenType.equals(predicate.screenType, ignoreCase = true)
        is Predicate.TextExists -> screen.visibleTexts.any {
            normalize(it).contains(normalize(predicate.text))
        }
        is Predicate.NodeExists -> grounder.ground(predicate.query, screen) is GroundingResult.Success
        is Predicate.FieldEquals -> (extractedState[predicate.key] as? StateValue.Text)?.value == predicate.value
        is Predicate.FieldMatches -> runCatching {
            Regex(predicate.pattern).matches(
                (extractedState[predicate.key] as? StateValue.Text)?.value.orEmpty(),
            )
        }.getOrDefault(false)
        is Predicate.NumericLessThan -> extractedState.number(predicate.key)?.let { it < predicate.value } == true
        is Predicate.NumericAtMost -> extractedState.number(predicate.key)?.let { it <= predicate.value } == true
        is Predicate.ScreenChanged -> screen.fingerprint != predicate.previousFingerprint
        is Predicate.Not -> !evaluate(predicate.predicate, screen, extractedState)
        is Predicate.All -> predicate.predicates.all { evaluate(it, screen, extractedState) }
        is Predicate.Any -> predicate.predicates.any { evaluate(it, screen, extractedState) }
    }

    private fun Map<String, StateValue>.number(key: String): Double? = when (val value = get(key)) {
        is StateValue.Number -> value.value
        is StateValue.Text -> value.value.toDoubleOrNull()
        else -> null
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase().replace(Regex("\\s+"), " ").trim()
}

class VerifiedAction internal constructor(
    val actionIndex: Int,
    val action: AgentAction,
    val resolvedNodeId: String?,
    val groundingConfidence: Double?,
    val visualFallback: Boolean,
    /** A no-effect platform click may be retried once as a verified bounds gesture. */
    val canRetryAfterNoEffect: Boolean,
    val evidence: List<String>,
)

class VerifiedPlan internal constructor(
    val plan: AgentPlan,
    val sourceEpoch: Long,
    val sourceFingerprint: String,
    val semanticFingerprint: String,
    val actions: Map<Int, VerifiedAction>,
    val evidence: List<String>,
) {
    fun actionAt(index: Int): VerifiedAction? = actions[index]
}

sealed class VerificationResult {
    data class Allowed(
        val verifiedPlan: VerifiedPlan,
        val evidence: List<String>,
    ) : VerificationResult()
    data class Blocked(
        val reason: String,
        val violatedConstraints: List<Constraint> = emptyList(),
    ) : VerificationResult()
    data class NeedsConfirmation(val reason: String, val summary: String) : VerificationResult()
    data class NeedsReplan(val reason: String) : VerificationResult()
}

interface ActionVerifier {
    fun verify(
        intent: UserIntent,
        task: CanonicalTask,
        plan: AgentPlan,
        snapshot: UiSnapshot,
        screen: ScreenState,
        userConfirmed: Boolean = false,
    ): VerificationResult
}

/** The only constructor of executable plans in the app's command path. */
class DeterministicActionVerifier(
    private val grounder: SemanticGrounder = DeterministicSemanticGrounder(),
) : ActionVerifier {
    override fun verify(
        intent: UserIntent,
        task: CanonicalTask,
        plan: AgentPlan,
        snapshot: UiSnapshot,
        screen: ScreenState,
        userConfirmed: Boolean,
    ): VerificationResult {
        val executable = plan.actions.withIndex().filter { it.value.type != ActionType.FINISH }
        if (executable.size != 1) {
            return VerificationResult.Blocked("관찰한 화면에서는 한 번에 하나의 동작만 실행할 수 있습니다.")
        }
        if (plan.confidence < MIN_PLANNER_CONFIDENCE && plan.source != PlanSource.LOCAL_RULE &&
            plan.source != PlanSource.SKILL_FAST_PATH
        ) {
            return VerificationResult.NeedsReplan("계획 신뢰도가 낮아 현재 화면을 다시 분석해야 합니다.")
        }
        val indexed = executable.single()
        val action = indexed.value
        if (action.type == ActionType.CLICK && action.target.isNullOrBlank()) {
            return VerificationResult.NeedsReplan("클릭에는 화면에서 확인 가능한 semantic target이 필요합니다.")
        }
        if ((screen.packageName == "unknown" || screen.packageName == SONJU_PACKAGE) &&
            action.type !in SCREEN_INDEPENDENT_ACTIONS
        ) {
            return VerificationResult.Blocked("실행할 외부 앱 화면을 확인하지 못했습니다.")
        }
        val exactLocalNodeTarget = plan.source == PlanSource.LOCAL_RULE &&
            action.type in setOf(ActionType.CLICK, ActionType.SET_TEXT, ActionType.SUBMIT_TEXT) &&
            !action.target.isNullOrBlank() &&
            screen.nodes.count { node ->
                node.nodeId == action.target && node.visible && node.enabled
            } == 1
        if (screen.treeTruncated && action.type in NODE_BOUND_ACTIONS && !exactLocalNodeTarget) {
            return if (task.risk >= TaskRisk.HIGH) {
                VerificationResult.Blocked("고위험 동작은 잘린 접근성 트리에서 실행하지 않습니다.")
            } else {
                VerificationResult.NeedsReplan("접근성 트리가 잘려 대상을 다시 확인해야 합니다.")
            }
        }

        val grounding = ground(action, screen)
        val grounded = when (grounding) {
            is GroundingResult.Success -> grounding
            is GroundingResult.Ambiguous -> return if (task.risk >= TaskRisk.HIGH) {
                VerificationResult.Blocked("고위험 동작의 대상이 여러 개라 안전하게 멈췄습니다.")
            } else {
                VerificationResult.NeedsReplan("화면 대상이 여러 개라 하나로 식별해야 합니다.")
            }
            is GroundingResult.NotFound -> return VerificationResult.NeedsReplan(grounding.reason)
            null -> null
        }
        if (grounded != null && grounded.confidence < MIN_STRUCTURED_CONFIDENCE) {
            return VerificationResult.NeedsReplan("대상 연결 신뢰도가 낮아 다시 찾아야 합니다.")
        }
        if (task.risk >= TaskRisk.HIGH && grounded != null &&
            grounded.confidence < MIN_HIGH_RISK_CONFIDENCE
        ) {
            return VerificationResult.Blocked("고위험 동작의 대상을 충분히 확신할 수 없습니다.")
        }
        val groundedNode = grounded?.nodeId?.let { id -> screen.nodes.firstOrNull { it.nodeId == id } }
        val submittedParameter = task.parameters["query"]?.value
            ?: task.parameters["destination"]?.value
            ?: task.parameters["recipient"]?.value
        if (action.type == ActionType.SUBMIT_TEXT && (
                plan.source != PlanSource.LOCAL_RULE ||
                    compact(submittedParameter.orEmpty()) != compact(action.value.orEmpty())
                )
        ) {
            return VerificationResult.Blocked(
                "검색어 제출은 현재 요청에서 직접 추출한 검색어의 로컬 경로에서만 실행합니다.",
            )
        }
        if (action.type in setOf(ActionType.SET_TEXT, ActionType.SUBMIT_TEXT) &&
            (groundedNode?.password == true || isSensitiveInput(action))
        ) {
            return VerificationResult.Blocked("비밀번호·OTP·인증정보 입력은 자동화하지 않습니다.")
        }

        val visual = action.type == ActionType.CLICK_COORDINATE
        val verifiedAppAdapterCoordinate = visual && isVerifiedAppAdapterCoordinate(
            task = task,
            plan = plan,
            action = action,
            snapshot = snapshot,
            screen = screen,
        )
        if (visual && (!plan.visualFallback ||
                plan.source != PlanSource.OPENAI_SEMANTIC_MAP && !verifiedAppAdapterCoordinate)
        ) {
            return VerificationResult.Blocked("일반 계획 경로에서는 좌표 동작을 실행할 수 없습니다.")
        }
        if (visual && (snapshot.elements.any { it.visible && it.sensitive } ||
                action.xRatio?.let { it.isFinite() && it in 0.0..1.0 } != true ||
                action.yRatio?.let { it.isFinite() && it in 0.0..1.0 } != true)
        ) {
            return VerificationResult.Blocked("시각 폴백 좌표 또는 화면의 개인정보 경계가 안전하지 않습니다.")
        }

        if (isUnderSpecifiedFoodChoice(action, task, groundedNode, plan.source) && !userConfirmed) {
            return VerificationResult.NeedsConfirmation(
                reason = "요청에서 식당·메뉴·옵션이 하나로 지정되지 않아 이 항목을 선택하기 전에 확인이 필요합니다.",
                summary = confirmationSummary(intent, plan, action, screen),
            )
        }

        val irreversible = isIrreversible(action, intent, task, screen)
        if (irreversible && intent.requestedCompletionLevel == CompletionLevel.BEFORE_IRREVERSIBLE_ACTION) {
            return VerificationResult.Blocked("요청한 완료 경계가 최종 확정 직전이므로 해당 동작은 실행하지 않습니다.")
        }
        if ((task.risk == TaskRisk.CRITICAL || isCriticalAction(action)) && irreversible) {
            return VerificationResult.Blocked("결제·금융·계정 보안의 최종 동작은 현재 제품 정책상 실행하지 않습니다.")
        }
        if (irreversible && !userConfirmed) {
            return VerificationResult.NeedsConfirmation(
                reason = "되돌리기 어려운 동작은 실행 직전에 확인이 필요합니다.",
                summary = confirmationSummary(intent, plan, action, screen),
            )
        }

        val verifiedAction = VerifiedAction(
            actionIndex = indexed.index,
            action = action,
            resolvedNodeId = grounded?.nodeId,
            groundingConfidence = grounded?.confidence,
            visualFallback = visual,
            canRetryAfterNoEffect = action.type == ActionType.CLICK && !irreversible,
            evidence = buildList {
                add("screenEpoch=${screen.sourceEpoch}")
                add("screenFingerprint=${screen.fingerprint}")
                grounded?.evidence?.let(::addAll)
                if (userConfirmed) add("userConfirmed=true")
            },
        )
        val verifiedPlan = VerifiedPlan(
            plan = plan,
            sourceEpoch = screen.sourceEpoch,
            sourceFingerprint = screen.sourceFingerprint,
            semanticFingerprint = screen.fingerprint,
            actions = mapOf(indexed.index to verifiedAction),
            evidence = verifiedAction.evidence,
        )
        return VerificationResult.Allowed(verifiedPlan, verifiedAction.evidence)
    }

    private fun ground(action: AgentAction, screen: ScreenState): GroundingResult? = when (action.type) {
        ActionType.CLICK -> grounder.ground(
            GroundingQuery(
                selector = action.target,
                interaction = InteractionRequirement.CLICK,
            ),
            screen,
        )
        ActionType.SET_TEXT,
        ActionType.SUBMIT_TEXT,
        -> grounder.ground(
            GroundingQuery(
                selector = action.target,
                interaction = InteractionRequirement.EDIT,
            ),
            screen,
        )
        ActionType.SCROLL_DOWN,
        ActionType.SCROLL_UP,
        ActionType.SCROLL_LEFT,
        ActionType.SCROLL_RIGHT,
        -> grounder.ground(
            GroundingQuery(
                selector = action.target,
                interaction = InteractionRequirement.SCROLL,
            ),
            screen,
        )
        else -> null
    }

    private fun isSensitiveInput(action: AgentAction): Boolean {
        if (action.type !in setOf(ActionType.SET_TEXT, ActionType.SUBMIT_TEXT)) return false
        val context = listOf(action.description, action.target, action.value).joinToString(" ")
        val compactContext = compact(context)
        return SENSITIVE_TERMS.any(compactContext::contains) ||
            action.value?.let { SECRET_NUMBER.matches(it.replace(Regex("\\s+"), "")) } == true
    }

    private fun isVerifiedAppAdapterCoordinate(
        task: CanonicalTask,
        plan: AgentPlan,
        action: AgentAction,
        snapshot: UiSnapshot,
        screen: ScreenState,
    ): Boolean {
        if (plan.source != PlanSource.APP_ADAPTER || !plan.visualFallback ||
            task.taskType != "send_message" ||
            task.completionLevel != CompletionLevel.BEFORE_IRREVERSIBLE_ACTION ||
            screen.packageName != KAKAO_TALK_PACKAGE ||
            snapshot.packageName != KAKAO_TALK_PACKAGE || snapshot.treeTruncated ||
            action.target != AppWorkflowRouter.KAKAO_PROFILE_CHAT_ADAPTER_TARGET
        ) return false
        val window = snapshot.windowBounds ?: return false
        val width = window.right - window.left
        val height = window.bottom - window.top
        if (width <= 0 || height <= width || height.toDouble() / width !in 1.6..2.8) return false
        val profileHome = screen.nodes.singleOrNull { node ->
            node.visible && node.resourceId == KAKAO_PROFILE_HOME_VIEW_ID
        } ?: return false
        if (profileHome.bounds.right - profileHome.bounds.left < width * 0.8 ||
            profileHome.bounds.bottom - profileHome.bounds.top < height * 0.6
        ) return false
        val recipient = task.parameters["recipient"]?.value?.takeIf(String::isNotBlank) ?: return false
        if (!compact(action.description).contains(compact(recipient))) return false
        if (screen.nodes.any { node ->
                node.visible && node.enabled && node.clickable &&
                    compact(node.normalizedLabel.orEmpty()).contains("11채팅")
            }
        ) return false
        val expectedY = AppWorkflowRouter.kakaoProfileChatYRatio(snapshot) ?: return false
        return action.xRatio?.let { it in KAKAO_PROFILE_CHAT_X_RANGE } == true &&
            action.yRatio?.let { it in (expectedY - 0.008)..(expectedY + 0.008) } == true
    }

    private fun isIrreversible(
        action: AgentAction,
        intent: UserIntent,
        task: CanonicalTask,
        screen: ScreenState,
    ): Boolean {
        if (action.type !in setOf(
                ActionType.CLICK,
                ActionType.CLICK_COORDINATE,
                ActionType.SET_TEXT,
                ActionType.SUBMIT_TEXT,
            )
        ) {
            return false
        }
        val context = listOf(
            action.description,
            action.target,
        ).joinToString(" ").let(::compact)
        if (task.taskType == "order_food" &&
            ORDER_REVIEW_NAVIGATION_TERMS.any(context::contains) &&
            screen.visibleTexts.any { label ->
                ORDER_REVIEW_CONTEXT_TERMS.any(compact(label)::contains)
            } &&
            screen.visibleTexts.none { label -> PAYMENT_SCREEN_TERMS.any(compact(label)::contains) }
        ) {
            return false
        }
        return IRREVERSIBLE_TERMS.any(context::contains) ||
            task.risk >= TaskRisk.HIGH && COMMIT_SIGNALS.any(context::contains)
    }

    private fun isCriticalAction(action: AgentAction): Boolean {
        val context = listOf(action.description, action.target).joinToString(" ").let(::compact)
        return CRITICAL_ACTION_TERMS.any(context::contains)
    }

    private fun isUnderSpecifiedFoodChoice(
        action: AgentAction,
        task: CanonicalTask,
        groundedNode: com.hwanghj09.sonju.perception.SemanticNode?,
        planSource: PlanSource,
    ): Boolean {
        if (task.taskType != "order_food" ||
            action.type !in setOf(ActionType.CLICK, ActionType.CLICK_COORDINATE)
        ) return false
        val label = groundedNode?.normalizedLabel.orEmpty().let(::compact)
        val query = task.parameters["query"]?.value.orEmpty().let(::compact)
        if (label.isNotBlank() && label == query) return false
        if (label in FOOD_ORDER_NAVIGATION_LABELS) return false
        val actionContext = listOf(action.description, action.target).joinToString(" ").let(::compact)
        val requestedRankedWorkflowStep = planSource == PlanSource.LOCAL_RULE &&
            task.constraints.any { constraint ->
                constraint.field in setOf("restaurant_sort", "menu_sort") &&
                    constraint.value == "popular"
            } && (RANKED_SELECTION_TERMS + REVERSIBLE_FOOD_PROGRESS_TERMS)
                .any(actionContext::contains)
        if (requestedRankedWorkflowStep) return false
        if (COMMIT_SIGNALS.any(actionContext::contains)) return false
        return FOOD_ORDER_NAVIGATION_LABELS.none(actionContext::contains)
    }

    private fun confirmationSummary(
        intent: UserIntent,
        plan: AgentPlan,
        action: AgentAction,
        screen: ScreenState,
    ): String = buildString {
        appendLine("요청: ${intent.goal.take(200)}")
        appendLine("앱: ${screen.packageName}")
        appendLine("동작: ${action.description.take(200)}")
        action.target?.let { appendLine("대상: ${it.take(120)}") }
        if (plan.targetSurface.isNotBlank()) append("확인된 화면: ${plan.targetSurface.take(160)}")
    }.trim()

    private fun compact(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")

    companion object {
        private val NODE_BOUND_ACTIONS = setOf(
            ActionType.CLICK,
            ActionType.SET_TEXT,
            ActionType.SUBMIT_TEXT,
            ActionType.SCROLL_DOWN,
            ActionType.SCROLL_UP,
            ActionType.SCROLL_LEFT,
            ActionType.SCROLL_RIGHT,
        )
        private val SCREEN_INDEPENDENT_ACTIONS = setOf(
            ActionType.OPEN_APP,
            ActionType.OPEN_WIFI_SETTINGS,
            ActionType.OPEN_SOUND_SETTINGS,
            ActionType.OPEN_ACCESSIBILITY_SETTINGS,
            ActionType.OPEN_DISPLAY_SETTINGS,
            ActionType.OPEN_DATE_SETTINGS,
            ActionType.OPEN_CAMERA,
            ActionType.OPEN_DIALER,
            ActionType.OPEN_MESSAGES,
            ActionType.HOME,
            ActionType.NOTIFICATIONS,
            ActionType.QUICK_SETTINGS,
        )
        private val SENSITIVE_TERMS = setOf(
            "비밀번호", "비번", "password", "passcode", "pin", "otp", "인증번호",
            "보안코드", "카드번호", "cvc", "cvv", "주민등록번호", "계좌번호",
        ).map { Normalizer.normalize(it, Normalizer.Form.NFKC).lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "") }
        private val IRREVERSIBLE_TERMS = setOf(
            "결제", "송금", "이체", "구매", "주문확정", "주문하기", "택시호출",
            "호출하기", "예약확정", "전송", "삭제", "탈퇴", "paynow", "placeorder",
            "confirm", "transfernow",
        )
        private val COMMIT_SIGNALS = setOf(
            "확정", "완료", "결제", "송금", "이체", "주문", "호출", "예약", "전송", "삭제", "탈퇴",
            "confirm", "submit", "pay", "transfer", "order", "call", "book", "send", "delete",
        )
        private val CRITICAL_ACTION_TERMS = setOf(
            "결제", "송금", "이체", "비밀번호변경", "계정탈퇴", "신원인증", "paynow", "transfernow",
        )
        private val FOOD_ORDER_NAVIGATION_LABELS = setOf(
            "검색", "검색하기", "search", "뒤로", "back", "닫기", "닫", "close",
            "건너뛰", "나중에", "skip", "notnow",
        )
        private val RANKED_SELECTION_TERMS = setOf(
            "요청한인기순", "인기순첫번째", "인기메뉴첫번째", "requestedpopularrank",
        )
        private val REVERSIBLE_FOOD_PROGRESS_TERMS = setOf(
            "선택한인기메뉴", "장바구니에담", "장바구니보기", "장바구니교체",
            "viewcart", "addtocart", "replacecart",
        )
        private val ORDER_REVIEW_NAVIGATION_TERMS = setOf(
            "주문서화면으로이동", "배달주문하기", "포장주문하기", "revieworder",
        )
        private val CART_SCREEN_TERMS = setOf("장바구니", "카트", "cart")
        private val ORDER_REVIEW_CONTEXT_TERMS = CART_SCREEN_TERMS + setOf(
            "함께먹으면", "함께구매", "추천상품", "추가메뉴", "youmayalsolike",
            "frequentlybought", "addon",
        )
        private val PAYMENT_SCREEN_TERMS = setOf(
            "결제수단", "결제하기", "결제및주문", "paymentmethod", "paynow",
        )
        private val SECRET_NUMBER = Regex("\\d{4,}")
        private const val MIN_PLANNER_CONFIDENCE = .55
        private const val MIN_STRUCTURED_CONFIDENCE = .70
        private const val MIN_HIGH_RISK_CONFIDENCE = .90
        private const val SONJU_PACKAGE = "com.hwanghj09.sonju"
        private const val KAKAO_TALK_PACKAGE = "com.kakao.talk"
        private const val KAKAO_PROFILE_HOME_VIEW_ID = "com.kakao.talk:id/profile_home"
        private val KAKAO_PROFILE_CHAT_X_RANGE = 0.24..0.30
    }
}

data class GoalEvaluation(val satisfied: Boolean, val evidence: List<String>)

interface GoalEvaluator {
    fun evaluate(
        task: CanonicalTask,
        state: ScreenState,
        extractedState: Map<String, StateValue> = emptyMap(),
    ): GoalEvaluation
}

class DeterministicGoalEvaluator : GoalEvaluator {
    override fun evaluate(
        task: CanonicalTask,
        state: ScreenState,
        extractedState: Map<String, StateValue>,
    ): GoalEvaluation {
        val expectedFingerprint = (extractedState["expected_fingerprint"] as? StateValue.Text)?.value
        if (expectedFingerprint != null && expectedFingerprint == state.fingerprint) {
            return GoalEvaluation(true, listOf("matched learned skill exit fingerprint"))
        }
        if ((extractedState["goal_verified"] as? StateValue.Flag)?.value == true) {
            return GoalEvaluation(true, listOf("executor verified final goal predicate"))
        }
        val values = task.parameters.values.mapNotNull { it.value?.takeIf(String::isNotBlank) }
        if (task.taskType == "order_food") {
            if (task.completionLevel == CompletionLevel.BEFORE_IRREVERSIBLE_ACTION) {
                val progressVerified =
                    (extractedState["order_transition_verified"] as? StateValue.Flag)?.value == true
                val checkoutContext = state.visibleTexts.any { actual ->
                    CHECKOUT_CONTEXT_TERMS.any { term -> compact(actual).contains(compact(term)) }
                }
                val finalCommitVisible = state.interactiveNodes.any { node ->
                    val label = compact(node.normalizedLabel.orEmpty())
                    FINAL_ORDER_COMMIT_TERMS.any(label::contains) ||
                        checkoutContext && GENERIC_ORDER_COMMIT_TERMS.any(label::contains)
                }
                val requestedMenu = task.parameters["menu_query"]?.value
                val menuVisible = requestedMenu.isNullOrBlank() || state.visibleTexts.any { actual ->
                    compact(actual).contains(compact(requestedMenu))
                }
                val menuSelectionVerified =
                    (extractedState["requested_menu_selected"] as? StateValue.Flag)?.value == true
                return GoalEvaluation(
                    satisfied = progressVerified && finalCommitVisible &&
                        (menuVisible || menuSelectionVerified),
                    evidence = if (progressVerified && finalCommitVisible &&
                        (menuVisible || menuSelectionVerified)
                    ) {
                        listOf("verified menu selection and the untouched final order control are present")
                    } else {
                        emptyList()
                    },
                )
            }
            val transitionVerified =
                (extractedState["order_transition_verified"] as? StateValue.Flag)?.value == true
            val orderCompleted = state.visibleTexts.any { actual ->
                ORDER_COMPLETION_TERMS.any { term -> compact(actual).contains(compact(term)) }
            }
            return GoalEvaluation(
                satisfied = transitionVerified && orderCompleted,
                evidence = if (transitionVerified && orderCompleted) {
                    listOf("this session's order transition and a new receipt state are verified")
                } else {
                    emptyList()
                },
            )
        }
        val matched = values.isNotEmpty() && values.all { expected ->
            state.visibleTexts.any { actual -> compact(actual).contains(compact(expected)) }
        }
        return GoalEvaluation(
            satisfied = matched,
            evidence = if (matched) listOf("task parameters are visible in the observed goal state") else emptyList(),
        )
    }

    private fun compact(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")

    private companion object {
        val ORDER_COMPLETION_TERMS = setOf("주문이 접수", "주문 접수", "주문 완료", "주문이 완료")
        val CHECKOUT_CONTEXT_TERMS = setOf(
            "결제수단", "주문자", "배달주소", "주문금액", "할인쿠폰", "paymentmethod",
        )
        val FINAL_ORDER_COMMIT_TERMS = setOf(
            "결제하기", "결제및주문", "결제후주문", "paynow", "placeorderandpay",
        )
        val GENERIC_ORDER_COMMIT_TERMS = setOf("주문하기", "주문확정", "placeorder")
    }
}
