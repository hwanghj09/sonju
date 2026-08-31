package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.AgentAction
import com.hwanghj09.sonju.agent.AgentPlan
import com.hwanghj09.sonju.agent.AppWorkflowRouter
import com.hwanghj09.sonju.agent.AutonomySession
import com.hwanghj09.sonju.agent.ExecutionResult
import com.hwanghj09.sonju.agent.PlanSource
import com.hwanghj09.sonju.agent.RiskLevel
import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.SonjuAgentRuntime
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import com.hwanghj09.sonju.execution.BoundedLoopDetector
import com.hwanghj09.sonju.execution.LoopObservation
import com.hwanghj09.sonju.execution.LoopStatus
import com.hwanghj09.sonju.grounding.DeterministicSemanticGrounder
import com.hwanghj09.sonju.grounding.GroundingQuery
import com.hwanghj09.sonju.grounding.GroundingResult
import com.hwanghj09.sonju.grounding.InteractionRequirement
import com.hwanghj09.sonju.logging.RedactionPolicy
import com.hwanghj09.sonju.logging.ProcessLogRepository
import com.hwanghj09.sonju.logging.TraceStep
import com.hwanghj09.sonju.perception.AccessibilityScreenParser
import com.hwanghj09.sonju.perception.SemanticRole
import com.hwanghj09.sonju.planner.PlannedAction
import com.hwanghj09.sonju.skill.FastPathPlanner
import com.hwanghj09.sonju.skill.InMemorySkillRepository
import com.hwanghj09.sonju.skill.SkillLearner
import com.hwanghj09.sonju.skill.SkillQuery
import com.hwanghj09.sonju.skill.SkillStatus
import com.hwanghj09.sonju.task.CanonicalTask
import com.hwanghj09.sonju.task.DeterministicTaskCanonicalizer
import com.hwanghj09.sonju.task.DeterministicTaskParser
import com.hwanghj09.sonju.task.TaskParameter
import com.hwanghj09.sonju.task.TaskRisk
import com.hwanghj09.sonju.verifier.DeterministicActionVerifier
import com.hwanghj09.sonju.verifier.DeterministicGoalEvaluator
import com.hwanghj09.sonju.verifier.DeterministicPredicateEvaluator
import com.hwanghj09.sonju.verifier.Predicate
import com.hwanghj09.sonju.verifier.StateValue
import com.hwanghj09.sonju.verifier.VerificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentArchitectureTest {
    @Test
    fun perceptionBuildsSemanticRolesAndStableTemplateFingerprint() {
        val first = snapshot(
            element("0", className = "android.widget.LinearLayout"),
            element(
                "0.0",
                text = "검색",
                className = "android.widget.Button",
                clickable = true,
            ),
            element(
                "0.1",
                text = "가격 18,000원",
                className = "android.widget.TextView",
            ),
            element("0.2", visible = false, text = "hidden"),
        )
        val second = first.copy(
            elements = first.elements.map {
                if (it.path == "0.1") it.copy(text = "가격 19,000원") else it
            },
        )

        val screen = AccessibilityScreenParser.parse(first)

        assertEquals(SemanticRole.BUTTON, screen.nodes.single { it.nodeId == "0.0" }.role)
        assertFalse(screen.nodes.any { it.nodeId == "0.2" })
        assertEquals(screen.fingerprint, AccessibilityScreenParser.parse(second).fingerprint)
        assertNotEquals(first.screenFingerprint(), second.screenFingerprint())
    }

    @Test
    fun grounderResolvesClickableAncestorAndRejectsTies() {
        val screen = AccessibilityScreenParser.parse(
            snapshot(
                element("0", clickable = true),
                element("0.0", text = "서울역"),
            ),
        )
        val result = DeterministicSemanticGrounder().ground(
            GroundingQuery(selector = "서울역", interaction = InteractionRequirement.CLICK),
            screen,
        )
        assertTrue(result is GroundingResult.Success)
        assertEquals("0", (result as GroundingResult.Success).nodeId)

        val ambiguous = AccessibilityScreenParser.parse(
            snapshot(
                element("0"),
                element("0.0", text = "확인", clickable = true),
                element("0.1", text = "확인", clickable = true),
            ),
        )
        assertTrue(
            DeterministicSemanticGrounder().ground(
                GroundingQuery(selector = "확인", interaction = InteractionRequirement.CLICK),
                ambiguous,
            ) is GroundingResult.Ambiguous,
        )
    }

    @Test
    fun normalPlannerCannotCreateExecutableCoordinates() {
        val snapshot = snapshot(element("0"), element("0.0", text = "아이콘", clickable = true))
        val verifier = DeterministicActionVerifier()
        val normal = plan(
            AgentAction(
                ActionType.CLICK_COORDINATE,
                "아이콘 좌표를 누릅니다.",
                xRatio = .5,
                yRatio = .5,
            ),
        )
        val intent = DeterministicTaskParser.parse("아이콘 눌러줘")
        val screen = AccessibilityScreenParser.parse(snapshot)
        val task = DeterministicTaskCanonicalizer.canonicalize(intent, screen)

        assertTrue(verifier.verify(intent, task, normal, snapshot, screen) is VerificationResult.Blocked)

        val visual = normal.copy(
            source = PlanSource.GEMINI_SEMANTIC_MAP,
            visualFallback = true,
        )
        assertTrue(verifier.verify(intent, task, visual, snapshot, screen) is VerificationResult.Allowed)
    }

    @Test
    fun kakaoProfileAdapterRequiresTheExactTaskRecipientAndProfileSurface() {
        val command = "엄마한테 안녕이라고 보내줘"
        val kakaoSnapshot = snapshot(
            element(
                "0",
                viewId = "com.kakao.talk:id/profile_home",
                bounds = ScreenBounds(0, 94, 1080, 2520),
                clickable = true,
            ),
            element(
                "0.photo",
                contentDescription = "사진",
                bounds = ScreenBounds(0, 1_581, 358, 2_061),
                clickable = true,
            ),
        ).copy(
            packageName = "com.kakao.talk",
            windowBounds = ScreenBounds(0, 0, 1080, 2640),
        )
        val screen = AccessibilityScreenParser.parse(kakaoSnapshot)
        val intent = DeterministicTaskParser.parse(command)
        val task = DeterministicTaskCanonicalizer.canonicalize(intent, screen)
        val adapterPlan = plan(
            AgentAction(
                ActionType.CLICK_COORDINATE,
                "엄마 프로필의 1:1 채팅을 엽니다.",
                target = AppWorkflowRouter.KAKAO_PROFILE_CHAT_ADAPTER_TARGET,
                xRatio = AppWorkflowRouter.KAKAO_PROFILE_CHAT_X_RATIO,
                yRatio = requireNotNull(AppWorkflowRouter.kakaoProfileChatYRatio(kakaoSnapshot)),
            ),
        ).copy(source = PlanSource.APP_ADAPTER, visualFallback = true)
        val verifier = DeterministicActionVerifier()

        assertTrue(
            verifier.verify(intent, task, adapterPlan, kakaoSnapshot, screen) is
                VerificationResult.Allowed,
        )
        val wrongRecipientPlan = adapterPlan.copy(
            actions = listOf(
                adapterPlan.actions.first().copy(description = "영희 프로필의 1:1 채팅을 엽니다."),
                adapterPlan.actions.last(),
            ),
        )
        assertTrue(
            verifier.verify(intent, task, wrongRecipientPlan, kakaoSnapshot, screen) is
                VerificationResult.Blocked,
        )

        val wrongPackage = kakaoSnapshot.copy(packageName = "com.example.app")
        assertTrue(
            verifier.verify(
                intent,
                task,
                adapterPlan,
                wrongPackage,
                AccessibilityScreenParser.parse(wrongPackage),
            ) is VerificationResult.Blocked,
        )
        val missingProfileHome = kakaoSnapshot.copy(
            elements = kakaoSnapshot.elements.filterNot {
                it.viewId == "com.kakao.talk:id/profile_home"
            },
        )
        assertTrue(
            verifier.verify(
                intent,
                task,
                adapterPlan,
                missingProfileHome,
                AccessibilityScreenParser.parse(missingProfileHome),
            ) is VerificationResult.Blocked,
        )
    }

    @Test
    fun messageRequestStopsBeforeTheObservedSendControl() {
        val command = "엄마한테 안녕이라고 보내줘"
        val chatSnapshot = snapshot(
            element("0.input", text = "안녕", editable = true),
            element("0.send", text = "전송", clickable = true),
        ).copy(packageName = "com.kakao.talk")
        val screen = AccessibilityScreenParser.parse(chatSnapshot)
        val intent = DeterministicTaskParser.parse(command)
        val task = DeterministicTaskCanonicalizer.canonicalize(intent, screen)
        val sendPlan = plan(
            AgentAction(ActionType.CLICK, "메시지를 전송합니다.", target = "0.send"),
        ).copy(source = PlanSource.LOCAL_RULE)

        assertTrue(
            DeterministicActionVerifier().verify(
                intent,
                task,
                sendPlan,
                chatSnapshot,
                screen,
                userConfirmed = true,
            ) is VerificationResult.Blocked,
        )
    }

    @Test
    fun verifierBindsAllowedClickToOneNodeAndScreenRevision() {
        val snapshot = snapshot(
            element("0"),
            element("0.0", text = "검색", clickable = true),
        )
        val screen = AccessibilityScreenParser.parse(snapshot)
        val intent = DeterministicTaskParser.parse("검색 눌러줘")
        val task = DeterministicTaskCanonicalizer.canonicalize(intent, screen)
        val result = DeterministicActionVerifier().verify(
            intent,
            task,
            plan(AgentAction(ActionType.CLICK, "검색을 엽니다.", target = "검색")),
            snapshot,
            screen,
        )

        assertTrue(result is VerificationResult.Allowed)
        val verified = (result as VerificationResult.Allowed).verifiedPlan
        assertEquals(snapshot.epoch, verified.sourceEpoch)
        assertEquals(snapshot.screenFingerprint(), verified.sourceFingerprint)
        assertEquals("0.0", verified.actions.values.single().resolvedNodeId)
        assertTrue(verified.actions.values.single().canRetryAfterNoEffect)
    }

    @Test
    fun querySubmitRequiresTheExactLocallyParsedQuery() {
        val snapshot = snapshot(
            element(
                path = "0.query",
                text = "피자",
                className = "android.widget.EditText",
                editable = true,
            ),
        )
        val screen = AccessibilityScreenParser.parse(snapshot)
        val intent = DeterministicTaskParser.parse("배민에서 피자 찾아줘")
        val task = DeterministicTaskCanonicalizer.canonicalize(intent, screen)
        val local = plan(
            AgentAction(
                ActionType.SUBMIT_TEXT,
                "입력한 검색어를 그대로 제출합니다.",
                target = "0.query",
                value = "피자",
            ),
        ).copy(source = PlanSource.LOCAL_RULE)
        val verifier = DeterministicActionVerifier()

        assertTrue(verifier.verify(intent, task, local, snapshot, screen) is VerificationResult.Allowed)
        assertTrue(
            verifier.verify(
                intent,
                task,
                local.copy(source = PlanSource.GEMINI_STRUCTURE),
                snapshot,
                screen,
            ) is VerificationResult.Blocked,
        )
        assertTrue(
            verifier.verify(
                intent,
                task,
                local.copy(
                    actions = listOf(
                        local.actions.first().copy(value = "피자헛"),
                        local.actions.last(),
                    ),
                ),
                snapshot,
                screen,
            ) is VerificationResult.Blocked,
        )
    }

    @Test
    fun directionsSubmitRequiresTheExactLocallyParsedDestination() {
        val snapshot = snapshot(
            element(
                path = "0.destination",
                text = "서울역",
                className = "android.widget.EditText",
                editable = true,
            ),
        )
        val screen = AccessibilityScreenParser.parse(snapshot)
        val intent = DeterministicTaskParser.parse("서울역 가는 길 알려줘")
        val task = DeterministicTaskCanonicalizer.canonicalize(intent, screen)
        val local = plan(
            AgentAction(
                ActionType.SUBMIT_TEXT,
                "입력한 목적지를 그대로 검색합니다.",
                target = "0.destination",
                value = "서울역",
            ),
        ).copy(source = PlanSource.LOCAL_RULE)
        val verifier = DeterministicActionVerifier()

        assertTrue(verifier.verify(intent, task, local, snapshot, screen) is VerificationResult.Allowed)
        assertTrue(
            verifier.verify(
                intent,
                task,
                local.copy(
                    actions = listOf(
                        local.actions.first().copy(value = "부산역"),
                        local.actions.last(),
                    ),
                ),
                snapshot,
                screen,
            ) is VerificationResult.Blocked,
        )
    }

    @Test
    fun truncatedTreeStillAllowsAnExactLocalNodePath() {
        val snapshot = snapshot(
            element("0"),
            element("0.0", text = "Recent item", clickable = true),
        ).copy(treeTruncated = true)
        val screen = AccessibilityScreenParser.parse(snapshot)
        val intent = DeterministicTaskParser.parse("Open the recent item")
        val task = DeterministicTaskCanonicalizer.canonicalize(intent, screen)
        val local = plan(
            AgentAction(ActionType.CLICK, "Open the first visible item", target = "0.0"),
        ).copy(source = PlanSource.LOCAL_RULE)

        assertTrue(
            DeterministicActionVerifier().verify(intent, task, local, snapshot, screen) is
                VerificationResult.Allowed,
        )
        assertTrue(
            DeterministicActionVerifier().verify(
                intent,
                task,
                local.copy(source = PlanSource.GEMINI_STRUCTURE),
                snapshot,
                screen,
            ) is VerificationResult.NeedsReplan,
        )
    }

    @Test
    fun highRiskCommitNeedsConfirmationAndCriticalPaymentFailsClosed() {
        val snapshot = snapshot(
            element("0"),
            element("0.0", text = "택시 호출", clickable = true),
            element("0.1", text = "결제하기", clickable = true),
        )
        val screen = AccessibilityScreenParser.parse(snapshot)
        val verifier = DeterministicActionVerifier()
        val taxiIntent = DeterministicTaskParser.parse("택시 호출해줘")
        val taxiTask = DeterministicTaskCanonicalizer.canonicalize(taxiIntent, screen)
        val taxiPlan = plan(AgentAction(ActionType.CLICK, "택시 호출을 확정합니다.", "택시 호출"))

        assertTrue(
            verifier.verify(taxiIntent, taxiTask, taxiPlan, snapshot, screen) is
                VerificationResult.NeedsConfirmation,
        )
        assertTrue(
            verifier.verify(taxiIntent, taxiTask, taxiPlan, snapshot, screen, userConfirmed = true) is
                VerificationResult.Allowed,
        )

        val paymentIntent = DeterministicTaskParser.parse("결제해줘")
        val paymentTask = DeterministicTaskCanonicalizer.canonicalize(paymentIntent, screen)
        val paymentPlan = plan(AgentAction(ActionType.CLICK, "결제를 확정합니다.", "결제하기"))
        assertTrue(
            verifier.verify(
                paymentIntent,
                paymentTask,
                paymentPlan,
                snapshot,
                screen,
                userConfirmed = true,
            ) is VerificationResult.Blocked,
        )
    }

    @Test
    fun paymentControlInsideFoodOrderIsCriticalEvenWhenTheRequestDidNotSayPayment() {
        val snapshot = snapshot(
            element("0"),
            element("0.0", text = "결제하기", clickable = true),
        ).copy(packageName = "com.sampleapp")
        val screen = AccessibilityScreenParser.parse(snapshot)
        val intent = DeterministicTaskParser.parse("배민에서 피자 시켜줘")
        val task = DeterministicTaskCanonicalizer.canonicalize(intent, screen)
        val result = DeterministicActionVerifier().verify(
            intent,
            task,
            plan(AgentAction(ActionType.CLICK, "결제하기 버튼을 누릅니다.", "결제하기")),
            snapshot,
            screen,
            userConfirmed = true,
        )

        assertTrue(result is VerificationResult.Blocked)
    }

    @Test
    fun underSpecifiedFoodOrderRequiresConfirmationBeforeChoosingOneRestaurant() {
        val snapshot = snapshot(
            element("0"),
            element("0.0", text = "도미노피자", clickable = true),
        ).copy(packageName = "com.sampleapp")
        val screen = AccessibilityScreenParser.parse(snapshot)
        val intent = DeterministicTaskParser.parse("배민에서 피자 시켜줘")
        val task = DeterministicTaskCanonicalizer.canonicalize(intent, screen)
        val restaurantPlan = plan(
            AgentAction(ActionType.CLICK, "도미노피자 식당을 선택합니다.", "도미노피자"),
        )
        val verifier = DeterministicActionVerifier()

        assertTrue(
            verifier.verify(intent, task, restaurantPlan, snapshot, screen) is
                VerificationResult.NeedsConfirmation,
        )
        assertTrue(
            verifier.verify(
                intent,
                task,
                restaurantPlan,
                snapshot,
                screen,
                userConfirmed = true,
            ) is VerificationResult.Allowed,
        )
    }

    @Test
    fun explicitPopularRankAllowsOnlyTheLocalRankedChoiceAndCartReviewNavigation() {
        val command = "가장 인기 있는 파스타 집에서 가장 인기 있는 파스타 주문해줘"
        val restaurantSnapshot = snapshot(
            element("0", text = "주문 많은 순"),
            element("0.0", text = "파스타하우스 별점 4.8 리뷰 300", clickable = true),
        ).copy(packageName = "com.sampleapp")
        val restaurantScreen = AccessibilityScreenParser.parse(restaurantSnapshot)
        val intent = DeterministicTaskParser.parse(command)
        val task = DeterministicTaskCanonicalizer.canonicalize(intent, restaurantScreen)
        val rankedPlan = plan(
            AgentAction(
                ActionType.CLICK,
                "요청한 인기순 결과의 첫 번째 비광고 파스타 식당을 선택합니다.",
                "0.0",
            ),
        ).copy(source = PlanSource.LOCAL_RULE)
        val verifier = DeterministicActionVerifier()

        assertTrue(
            verifier.verify(intent, task, rankedPlan, restaurantSnapshot, restaurantScreen) is
                VerificationResult.Allowed,
        )

        listOf(
            snapshot(
                element("0", text = "인기 메뉴"),
                element("0.0", text = "크림 파스타 14,000원", clickable = true),
            ).copy(packageName = "com.sampleapp") to AgentAction(
                ActionType.CLICK,
                "요청한 인기 메뉴 첫 번째 '파스타' 항목을 선택합니다.",
                "0.0",
            ),
            snapshot(
                element("0", text = "크림 파스타"),
                element("0.0", text = "14,000원 담기", clickable = true),
            ).copy(packageName = "com.sampleapp") to AgentAction(
                ActionType.CLICK,
                "선택한 인기 메뉴를 장바구니에 담습니다.",
                "0.0",
            ),
            snapshot(
                element("0.0", text = "장바구니 보기", clickable = true),
            ).copy(packageName = "com.sampleapp") to AgentAction(
                ActionType.CLICK,
                "담은 메뉴를 확인하기 위해 장바구니 보기를 엽니다.",
                "0.0",
            ),
            snapshot(
                element("0", text = "같은 가게의 메뉴만 담을 수 있습니다"),
                element("0.0", text = "담기", clickable = true),
            ).copy(packageName = "com.sampleapp") to AgentAction(
                ActionType.CLICK,
                "새 요청의 메뉴를 담기 위해 기존 장바구니 교체를 진행합니다.",
                "0.0",
            ),
            snapshot(
                element("0", text = "멤버십 가입하고 할인 받으세요"),
                element("0.0", text = "나중에", clickable = true),
            ).copy(packageName = "com.sampleapp") to AgentAction(
                ActionType.CLICK,
                "현재 요청과 무관한 가입·구독 제안 화면을 닫습니다.",
                "0.0",
            ),
        ).forEach { (stepSnapshot, action) ->
            val stepScreen = AccessibilityScreenParser.parse(stepSnapshot)
            assertTrue(
                action.description,
                verifier.verify(
                    intent,
                    task,
                    plan(action).copy(source = PlanSource.LOCAL_RULE),
                    stepSnapshot,
                    stepScreen,
                ) is VerificationResult.Allowed,
            )
        }

        val cartSnapshot = snapshot(
            element("0", text = "장바구니"),
            element("0.0", text = "배달 주문하기", clickable = true),
        ).copy(packageName = "com.sampleapp")
        val cartScreen = AccessibilityScreenParser.parse(cartSnapshot)
        val reviewPlan = plan(
            AgentAction(
                ActionType.CLICK,
                "최종 확정은 누르지 않고 주문서 화면으로 이동합니다.",
                "0.0",
            ),
        ).copy(source = PlanSource.LOCAL_RULE)
        assertTrue(
            verifier.verify(intent, task, reviewPlan, cartSnapshot, cartScreen) is
                VerificationResult.Allowed,
        )

        val crossSellSnapshot = snapshot(
            element("0", text = "함께 먹으면 더 좋아요"),
            element("0.0", text = "배달 주문하기", clickable = true),
        ).copy(packageName = "com.sampleapp")
        assertTrue(
            verifier.verify(
                intent,
                task,
                reviewPlan.copy(
                    actions = reviewPlan.actions.map { action ->
                        if (action.type == ActionType.CLICK) action.copy(target = "0.0") else action
                    },
                ),
                crossSellSnapshot,
                AccessibilityScreenParser.parse(crossSellSnapshot),
            ) is VerificationResult.Allowed,
        )
    }

    @Test
    fun sensitiveInputAndTruncatedTreesFailClosed() {
        val passwordSnapshot = snapshot(
            element("0"),
            element("0.0", className = "android.widget.EditText", editable = true, sensitive = true),
        )
        val passwordScreen = AccessibilityScreenParser.parse(passwordSnapshot)
        val intent = DeterministicTaskParser.parse("비밀번호 입력해줘")
        val task = DeterministicTaskCanonicalizer.canonicalize(intent, passwordScreen)
        val result = DeterministicActionVerifier().verify(
            intent,
            task,
            plan(AgentAction(ActionType.SET_TEXT, "비밀번호 입력", "0.0", "1234")),
            passwordSnapshot,
            passwordScreen,
        )
        assertTrue(result is VerificationResult.Blocked)

        val sensitiveClickSnapshot = snapshot(
            element("0"),
            element("0.0", clickable = true, sensitive = true),
        )
        val sensitiveClickScreen = AccessibilityScreenParser.parse(sensitiveClickSnapshot)
        val openIntent = DeterministicTaskParser.parse("Open the recent file")
        val openTask = DeterministicTaskCanonicalizer.canonicalize(openIntent, sensitiveClickScreen)
        assertTrue(
            DeterministicActionVerifier().verify(
                openIntent,
                openTask,
                plan(AgentAction(ActionType.CLICK, "Open the requested file", "0.0")).copy(
                    source = PlanSource.LOCAL_RULE,
                ),
                sensitiveClickSnapshot,
                sensitiveClickScreen,
            ) is VerificationResult.Allowed,
        )

        val truncated = snapshot(
            element("0"),
            element("0.0", text = "검색", clickable = true),
        ).copy(treeTruncated = true)
        val truncatedIntent = DeterministicTaskParser.parse("검색 눌러줘")
        val truncatedScreen = AccessibilityScreenParser.parse(truncated)
        val truncatedTask = DeterministicTaskCanonicalizer.canonicalize(truncatedIntent, truncatedScreen)
        assertTrue(
            DeterministicActionVerifier().verify(
                truncatedIntent,
                truncatedTask,
                plan(AgentAction(ActionType.CLICK, "검색", "검색")),
                truncated,
                truncatedScreen,
            ) is VerificationResult.NeedsReplan,
        )
    }

    @Test
    fun predicateAndGoalEvaluationUseObservedState() {
        val screen = AccessibilityScreenParser.parse(
            snapshot(
                element("0"),
                element("0.0", text = "서울역", clickable = true),
            ),
        )
        val predicates = DeterministicPredicateEvaluator()
        assertTrue(predicates.evaluate(Predicate.TextExists("서울역"), screen))
        assertTrue(
            predicates.evaluate(
                Predicate.NumericAtMost("fare", 20_000.0),
                screen,
                mapOf("fare" to StateValue.Number(18_000.0)),
            ),
        )

        val task = CanonicalTask(
            appId = screen.packageName,
            taskType = "search",
            parameters = mapOf("destination" to TaskParameter("destination", "서울역")),
            constraints = emptyList(),
            risk = TaskRisk.LOW,
        )
        assertTrue(DeterministicGoalEvaluator().evaluate(task, screen).satisfied)
        assertFalse(
            DeterministicGoalEvaluator().evaluate(
                task.copy(parameters = mapOf("destination" to TaskParameter("destination", "부산역"))),
                screen,
            ).satisfied,
        )
    }

    @Test
    fun foodOrderIntentPreservesAppAndQueryAndCannotFinishOnSearchResults() {
        val intent = DeterministicTaskParser.parse("배민에서 피자 시켜줘")
        val searchResults = AccessibilityScreenParser.parse(
            snapshot(element("0", text = "피자 검색 결과")),
        )
        val task = DeterministicTaskCanonicalizer.canonicalize(intent, searchResults)

        assertEquals("배민", intent.targetApp)
        assertEquals("피자", intent.entities["query"])
        assertEquals("baemin", task.appId)
        assertEquals("order_food", task.taskType)
        assertEquals(TaskRisk.HIGH, task.risk)
        assertFalse(DeterministicGoalEvaluator().evaluate(task, searchResults).satisfied)

        val receipt = AccessibilityScreenParser.parse(
            snapshot(element("0", text = "주문이 접수되었습니다")),
        )
        assertFalse(DeterministicGoalEvaluator().evaluate(task, receipt).satisfied)
        assertFalse(
            DeterministicGoalEvaluator().evaluate(
                task,
                receipt,
                mapOf("order_transition_verified" to StateValue.Flag(true)),
            ).satisfied,
        )

        val checkout = AccessibilityScreenParser.parse(
            snapshot(
                element("0", text = "결제수단"),
                element("0.0", text = "25,000원 결제하기", clickable = true),
            ),
        )
        assertFalse(DeterministicGoalEvaluator().evaluate(task, checkout).satisfied)
        assertTrue(
            DeterministicGoalEvaluator().evaluate(
                task,
                checkout,
                mapOf("order_transition_verified" to StateValue.Flag(true)),
            ).satisfied,
        )

        val popularIntent = DeterministicTaskParser.parse(
            "가장 인기 있는 파스타 집에서 가장 인기 있는 파스타 주문해줘",
        )
        val popularTask = DeterministicTaskCanonicalizer.canonicalize(popularIntent, checkout)
        assertFalse(
            DeterministicGoalEvaluator().evaluate(
                popularTask,
                checkout,
                mapOf("order_transition_verified" to StateValue.Flag(true)),
            ).satisfied,
        )
        assertTrue(
            DeterministicGoalEvaluator().evaluate(
                popularTask,
                checkout,
                mapOf(
                    "order_transition_verified" to StateValue.Flag(true),
                    "requested_menu_selected" to StateValue.Flag(true),
                ),
            ).satisfied,
        )
    }

    @Test
    fun runtimeCarriesVerifiedMenuSelectionIntoARedactedCheckoutGoal() {
        val command = "가장 인기 있는 파스타 집에서 가장 인기 있는 파스타 주문해줘"
        val before = snapshot(
            element("0", text = "파스타 메뉴"),
            element("0.0", text = "담기", clickable = true),
        ).copy(packageName = "com.sampleapp", epoch = 1)
        val checkout = snapshot(
            element("0", text = "알리오 시그니처 링귀니"),
            element("0.0", text = "11,900원 결제하기", clickable = true),
            element("0.private", sensitive = true),
        ).copy(packageName = "com.sampleapp", epoch = 2)
        val session = AutonomySession(command, before, startedAtMillis = 0)
        val selected = session.acceptPlan(
            plan(
                AgentAction(
                    ActionType.CLICK,
                    "요청한 인기 메뉴 '파스타'를 장바구니에 담습니다.",
                    "0.0",
                ),
            ).copy(goal = command, source = PlanSource.LOCAL_RULE),
            before,
        )
        session.recordExecution(
            selected,
            before,
            ExecutionResult(true, "성공", 1, postconditionSatisfied = true),
        )
        session.observe(checkout)
        val processLog = object : ProcessLogRepository {
            override fun append(step: TraceStep) = Unit
            override fun exportRedacted(): List<String> = emptyList()
        }
        val runtime = SonjuAgentRuntime.createForTest(InMemorySkillRepository(), processLog)
        val completed = AgentPlan(
            goal = command,
            summary = "주문서를 확인했습니다.",
            modelRisk = RiskLevel.HIGH,
            confidence = 1.0,
            actions = listOf(AgentAction(ActionType.FINISH, "완료")),
            source = PlanSource.LOCAL_RULE,
            goalCompleted = true,
        )

        assertTrue(runtime.goalSatisfied(command, completed, checkout, session))
    }

    @Test
    fun successfulParameterizedTraceBecomesOfflineFastPath() {
        val task = CanonicalTask(
            appId = "com.example.delivery",
            taskType = "search",
            parameters = mapOf("query" to TaskParameter("query", "피자")),
            constraints = emptyList(),
            risk = TaskRisk.LOW,
        )
        val trace = AutonomySession.Trace(
            action = AgentAction(ActionType.SET_TEXT, "검색어 입력", "검색창", "피자"),
            beforePackage = task.appId!!,
            beforeFingerprint = "raw-before",
            succeeded = true,
            message = "ok",
            afterPackage = task.appId,
            afterFingerprint = "raw-after",
            screenChanged = true,
            beforeTemplateFingerprint = "screen-search",
            afterTemplateFingerprint = "screen-result",
        )
        val skill = requireNotNull(
            SkillLearner.learn(task, listOf(trace), "fallback-before", "fallback-after"),
        )
        assertEquals("${'$'}{query}", skill.steps.single().action.valueTemplate)

        val repository = InMemorySkillRepository()
        repository.save(skill)
        val nextTask = task.copy(
            parameters = mapOf("query" to TaskParameter("query", "치킨")),
        )
        val screen = AccessibilityScreenParser.parse(snapshot(element("0"))).copy(
            packageName = task.appId,
            fingerprint = "screen-search",
        )
        val found = repository.find(
            SkillQuery(nextTask.key, nextTask.appId, nextTask.taskType, screen.fingerprint),
        )
        val plan = requireNotNull(FastPathPlanner().plan(nextTask, screen, found))
        val action = plan.steps.single().action as PlannedAction.SetText
        assertEquals("치킨", action.value)

        repository.markFailure(skill.skillId)
        assertEquals(SkillStatus.SUSPECT, repository.get(skill.skillId)?.status)
        assertTrue(
            repository.find(
                SkillQuery(
                    "${task.appId}:search:destination",
                    task.appId,
                    task.taskType,
                    screen.fingerprint,
                ),
            ).isEmpty(),
        )

        val coordinateTrace = trace.copy(
            action = AgentAction(
                ActionType.CLICK_COORDINATE,
                "시각 좌표",
                xRatio = .5,
                yRatio = .5,
            ),
        )
        assertTrue(
            SkillLearner.learn(
                task,
                listOf(trace, coordinateTrace),
                "fallback-before",
                "fallback-after",
            ) == null,
        )
    }

    @Test
    fun loopDetectorHasFiniteRepeatedActionAndCycleGuards() {
        val repeated = BoundedLoopDetector()
        repeat(2) {
            assertEquals(
                LoopStatus.CLEAR,
                repeated.observe(LoopObservation("A", "CLICK:x", "B")),
            )
        }
        assertEquals(
            LoopStatus.REPEATED_STATE_ACTION,
            repeated.observe(LoopObservation("A", "CLICK:x", "B")),
        )

        val cycle = BoundedLoopDetector()
        cycle.observe(LoopObservation("A", "x", "B"))
        cycle.observe(LoopObservation("B", "y", "A"))
        cycle.observe(LoopObservation("A", "z", "B"))
        assertEquals(
            LoopStatus.TWO_STATE_CYCLE,
            cycle.observe(LoopObservation("B", "w", "A")),
        )
    }

    @Test
    fun processLogRedactionNeverKeepsSecrets() {
        assertEquals("<REDACTED_SENSITIVE>", RedactionPolicy.redact("OTP 123456"))
        assertFalse(RedactionPolicy.redact("mail user@example.com")!!.contains("user@example.com"))
        assertEquals(
            RedactionPolicy.requestHash("서울역 검색"),
            RedactionPolicy.requestHash("서울역 검색"),
        )
    }

    private fun plan(action: AgentAction): AgentPlan = AgentPlan(
        goal = "test goal",
        summary = "test",
        modelRisk = RiskLevel.LOW,
        confidence = 1.0,
        actions = listOf(action, AgentAction(ActionType.FINISH, "observe")),
        source = PlanSource.GEMINI_STRUCTURE,
    )

    private fun snapshot(vararg elements: UiElement): UiSnapshot = UiSnapshot(
        packageName = "com.example.app",
        windowTitle = "Example",
        windowId = 1,
        epoch = 42,
        elements = elements.toList(),
    )

    private fun element(
        path: String,
        text: String? = null,
        className: String = "android.view.View",
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        visible: Boolean = true,
        sensitive: Boolean = false,
        viewId: String? = null,
        contentDescription: String? = null,
        bounds: ScreenBounds = ScreenBounds(0, path.length * 10, 200, path.length * 10 + 60),
    ): UiElement = UiElement(
        path = path,
        viewId = viewId,
        className = className,
        text = text,
        contentDescription = contentDescription,
        bounds = bounds,
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        enabled = true,
        visible = visible,
        sensitive = sensitive,
    )
}
