package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.AgentAction
import com.hwanghj09.sonju.agent.AppWorkflowRouter
import com.hwanghj09.sonju.agent.PlanSource
import com.hwanghj09.sonju.agent.RuleBasedPlanner
import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.TrustedSettingsRoute
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleBasedPlannerTest {
    @Test
    fun wifiSettings_staysOnDevice() {
        val plan = RuleBasedPlanner.plan("와이파이 설정을 열어 줘")

        assertEquals(PlanSource.LOCAL_RULE, plan?.source)
        assertEquals(ActionType.OPEN_WIFI_SETTINGS, plan?.actions?.first()?.type)
        assertEquals(1.0, plan?.confidence ?: 0.0, 0.0)
    }

    @Test
    fun explicitEnglishSettingsCommandsStayOnDevice() {
        assertEquals(
            ActionType.OPEN_DISPLAY_SETTINGS,
            RuleBasedPlanner.plan("Open display settings")?.actions?.first()?.type,
        )
        assertEquals(
            ActionType.OPEN_SOUND_SETTINGS,
            RuleBasedPlanner.plan("Open sound settings")?.actions?.first()?.type,
        )
        assertEquals(
            ActionType.OPEN_DATE_SETTINGS,
            RuleBasedPlanner.plan("Open date and time settings")?.actions?.first()?.type,
        )
    }

    @Test
    fun unknownRequest_isDelegatedToGemini() {
        assertNull(RuleBasedPlanner.plan("화면에서 배송 조회 버튼을 찾아 줘"))
    }

    @Test
    fun quickScrollLabel_staysOnDevice() {
        val plan = RuleBasedPlanner.plan("화면 아래로 내리기")

        assertEquals(PlanSource.LOCAL_RULE, plan?.source)
        assertEquals(ActionType.SCROLL_DOWN, plan?.actions?.first()?.type)
    }

    @Test
    fun explicitAppLaunchesStayOnDevice() {
        mapOf(
            "유튜브 열어 줘" to "유튜브",
            "카카오톡 앱 실행해 줘" to "카카오톡",
            "배민 들어가 줘" to "배민",
            "Open Chrome" to "chrome",
        ).forEach { (command, target) ->
            val plan = RuleBasedPlanner.plan(command)

            assertEquals(command, PlanSource.LOCAL_RULE, plan?.source)
            assertEquals(command, ActionType.OPEN_APP, plan?.actions?.first()?.type)
            assertEquals(command, target, plan?.actions?.first()?.target)
        }
    }

    @Test
    fun genericObjectsAreNotTreatedAsApps() {
        listOf("설정 열어 줘", "버튼 열어 줘", "파일 열어 줘").forEach { command ->
            assertNull(command, RuleBasedPlanner.plan(command))
        }
    }

    @Test
    fun orderVerbEndingInKyeoIsNotMisparsedAsAnAppLaunch() {
        assertNull(RuleBasedPlanner.plan("배민에서 피자 시켜줘"))
        assertNull(RuleBasedPlanner.plan("배달의민족에서 치킨 주문해 줘"))
    }

    @Test
    fun appInternalTargetsAreSeparatedFromTheAppName() {
        mapOf(
            "카톡 프로필 열어줘" to ("카톡" to "프로필"),
            "노트 들어가서 가장 최근 파일 열어줘" to ("노트" to "가장 최근 파일"),
            "배민에서 가장 인기 있는 피자 집 찾아줘" to ("배민" to "가장 인기 있는 피자 집"),
        ).forEach { (command, expected) ->
            val route = AppWorkflowRouter.route(command)

            assertEquals(command, expected.first, route?.appLabel)
            assertEquals(command, true, route?.targetSurface?.contains(expected.second))
            assertNull(command, RuleBasedPlanner.plan(command))
        }
        assertNull(AppWorkflowRouter.route("카톡 열어줘"))
    }

    @Test
    fun appInternalWorkflowOpensOnlyTheOwningAppBeforeInspectingItsScreen() {
        val command = "카톡 프로필 열어줘"
        val route = requireNotNull(AppWorkflowRouter.route(command))
        val entry = AppWorkflowRouter.entryPlan(
            command,
            route,
            currentPackage = "com.sec.android.app.launcher",
            targetPackage = "com.kakao.talk",
        )

        assertEquals(ActionType.OPEN_APP, entry?.actions?.first()?.type)
        assertEquals("카톡", entry?.actions?.first()?.target)
        assertEquals(true, entry?.continueAfterAction)
        assertNull(
            AppWorkflowRouter.entryPlan(
                command,
                route,
                currentPackage = "com.kakao.talk",
                targetPackage = "com.kakao.talk",
            ),
        )
    }

    @Test
    fun appInternalWorkflowUsesTheVisibleOwningAppStructure() {
        val recentRoute = requireNotNull(
            AppWorkflowRouter.route("노트 들어가서 가장 최근 파일 열어줘"),
        )
        val notes = snapshot().copy(
            packageName = "com.samsung.android.app.notes",
            elements = listOf(
                element(
                    path = "0.3",
                    viewId = "com.samsung.android.app.notes:id/root_cardview",
                    bounds = ScreenBounds(20, 700, 500, 1_200),
                ),
                element(
                    path = "0.4",
                    viewId = "com.samsung.android.app.notes:id/root_cardview",
                    bounds = ScreenBounds(520, 700, 1_000, 1_200),
                ),
            ),
        )
        val notesPlan = AppWorkflowRouter.inAppPlan(
            "노트 들어가서 가장 최근 파일 열어줘",
            recentRoute,
            notes,
        )

        assertEquals(ActionType.CLICK, notesPlan?.actions?.first()?.type)
        assertEquals("0.3", notesPlan?.actions?.first()?.target)

        val profileRoute = requireNotNull(AppWorkflowRouter.route("카톡 프로필 열어줘"))
        val kakao = snapshot().copy(
            packageName = "com.kakao.talk",
            elements = listOf(
                element(
                    path = "0.8",
                    contentDescription = "친구 프로필 사진",
                    bounds = ScreenBounds(20, 800, 160, 940),
                ),
                element(
                    path = "0.1",
                    contentDescription = "내 프로필 사진",
                    bounds = ScreenBounds(20, 100, 500, 260),
                ),
            ),
        )
        val kakaoPlan = AppWorkflowRouter.inAppPlan("카톡 프로필 열어줘", profileRoute, kakao)

        assertEquals("0.1", kakaoPlan?.actions?.first()?.target)
        assertNull(AppWorkflowRouter.inAppPlan("카톡 프로필 열어줘", profileRoute, snapshot()))
    }

    @Test
    fun installedAppLabelsEnableInternalWorkflowsWithoutAHardcodedAlias() {
        val route = AppWorkflowRouter.route(
            "Instagram 프로필 열어줘",
            installedAppLabels = listOf("지도", "Instagram"),
        )

        assertEquals("Instagram", route?.appLabel)
        assertEquals("프로필 열어줘", route?.targetSurface)
        assertNull(AppWorkflowRouter.route("Instagram 프로필 열어줘"))
    }

    @Test
    fun installedAppLabelIsNotTypedIntoThatAppsSearchField() {
        val command = "Instagram 고양이 찾아줘"
        val route = requireNotNull(AppWorkflowRouter.route(command, listOf("Instagram")))
        val plan = AppWorkflowRouter.inAppPlan(
            command,
            route,
            snapshot().copy(
                packageName = "com.instagram.android",
                elements = listOf(
                    element(
                        path = "0.query",
                        hintText = "검색",
                        clickable = false,
                        editable = true,
                    ),
                ),
            ),
        )

        assertEquals("고양이", plan?.actions?.first()?.value)
    }

    @Test
    fun genericAppWorkflowUsesOneSemanticMenuTargetAndRejectsAmbiguity() {
        val route = requireNotNull(
            AppWorkflowRouter.route("샘플앱 보관함 열어줘", listOf("샘플앱")),
        )
        val unique = snapshot().copy(
            packageName = "com.example.sample",
            elements = listOf(
                element(path = "0.4", clickable = true),
                element(path = "0.4.1", text = "보관함", clickable = false),
            ),
        )
        val plan = AppWorkflowRouter.inAppPlan("샘플앱 보관함 열어줘", route, unique)

        assertEquals(ActionType.CLICK, plan?.actions?.first()?.type)
        assertEquals("0.4", plan?.actions?.first()?.target)
        assertEquals(true, plan?.continueAfterAction)

        val ambiguousRoute = requireNotNull(
            AppWorkflowRouter.route("샘플앱 설정 열어줘", listOf("샘플앱")),
        )
        val ambiguous = unique.copy(
            elements = listOf(
                element(path = "0.2", text = "설정"),
                element(path = "0.8", text = "설정"),
            ),
        )
        assertNull(
            AppWorkflowRouter.inAppPlan("샘플앱 설정 열어줘", ambiguousRoute, ambiguous),
        )
    }

    @Test
    fun genericSearchAdvancesOneObservedSemanticActionAtATime() {
        val command = "동영상에서 클래식 음악 찾아줘"
        val route = requireNotNull(AppWorkflowRouter.route(command))
        val openSearch = AppWorkflowRouter.inAppPlan(
            command,
            route,
            snapshot().copy(elements = listOf(element(path = "0.search", text = "검색"))),
        )
        assertEquals(ActionType.CLICK, openSearch?.actions?.first()?.type)
        assertEquals("0.search", openSearch?.actions?.first()?.target)

        val nestedSearch = AppWorkflowRouter.inAppPlan(
            command,
            route,
            snapshot().copy(
                elements = listOf(
                    element(path = "0.search", contentDescription = "검색 버튼"),
                    element(
                        path = "0.search.0",
                        contentDescription = "검색 버튼, 오늘의 추천",
                    ),
                ),
            ),
        )
        assertEquals("0.search.0", nestedSearch?.actions?.first()?.target)

        val typeQuery = AppWorkflowRouter.inAppPlan(
            command,
            route,
            snapshot().copy(
                elements = listOf(
                    element(
                        path = "0.query",
                        hintText = "검색",
                        clickable = false,
                        editable = true,
                    ),
                ),
            ),
        )
        assertEquals(ActionType.SET_TEXT, typeQuery?.actions?.first()?.type)
        assertEquals("클래식 음악", typeQuery?.actions?.first()?.value)

        val submitOriginalQuery = AppWorkflowRouter.inAppPlan(
            command,
            route,
            snapshot().copy(
                elements = listOf(
                    element(
                        path = "0.query",
                        text = "클래식 음악",
                        hintText = "검색",
                        clickable = false,
                        editable = true,
                        focused = true,
                    ),
                    element(path = "0.suggestion", text = "클래식 음악"),
                    element(path = "0.suggestion2", text = "클래식 음악 추천"),
                    element(path = "0.suggestion3", text = "클래식 음악가"),
                    element(path = "0.clear", contentDescription = "검색어 지우기"),
                ),
            ),
        )
        assertEquals(ActionType.SUBMIT_TEXT, submitOriginalQuery?.actions?.first()?.type)
        assertEquals("0.query", submitOriginalQuery?.actions?.first()?.target)

        val submitSearch = AppWorkflowRouter.inAppPlan(
            command,
            route,
            snapshot().copy(
                elements = listOf(
                    element(
                        path = "0.query",
                        text = "클래식 음악",
                        hintText = "검색",
                        clickable = false,
                        editable = true,
                    ),
                    element(path = "0.clear", contentDescription = "검색어 지우기"),
                    element(path = "0.submit", text = "검색"),
                ),
            ),
        )
        assertEquals("0.submit", submitSearch?.actions?.first()?.target)

        val completed = AppWorkflowRouter.inAppPlan(
            command,
            route,
            snapshot().copy(
                elements = listOf(
                    element(path = "0.title", text = "클래식 음악", clickable = false),
                    element(path = "0.result", text = "교향곡 모음"),
                    element(path = "0.result2", text = "피아노 모음"),
                ),
            ),
        )
        assertEquals(true, completed?.goalCompleted)
        assertEquals(ActionType.FINISH, completed?.actions?.single()?.type)

        val headerOnly = AppWorkflowRouter.inAppPlan(
            command,
            route,
            snapshot().copy(
                elements = listOf(
                    element(path = "0.title", text = "클래식 음악", clickable = false),
                    element(path = "0.back", text = "뒤로"),
                ),
            ),
        )
        assertNull(headerOnly)
    }

    @Test
    fun popularityPhraseBecomesAQueryPlusAConstraint() {
        val command = "배민에서 가장 인기 있는 피자 집 찾아줘"
        val intent = com.hwanghj09.sonju.task.DeterministicTaskParser.parse(command)

        assertEquals("피자", intent.entities["query"])
        assertEquals("popular", intent.constraints.single().value)

        val route = requireNotNull(AppWorkflowRouter.route(command))
        val homePlan = AppWorkflowRouter.inAppPlan(
            command,
            route,
            snapshot().copy(
                packageName = "com.sampleapp",
                elements = listOf(
                    element(path = "0.category", contentDescription = "피자"),
                    element(
                        path = "0.search",
                        contentDescription = "검색 버튼, 오늘의 추천",
                    ),
                ),
            ),
        )
        assertEquals("0.search", homePlan?.actions?.first()?.target)

        val resultScreen = snapshot().copy(
            packageName = "com.sampleapp",
            elements = listOf(
                element(
                    path = "0.query",
                    text = "피자",
                    hintText = "검색",
                    clickable = false,
                    editable = true,
                ),
                element(path = "0.sort", text = "정렬"),
                element(path = "0.restaurant1", text = "멜로피자"),
                element(path = "0.restaurant2", text = "파파존스"),
            ),
        )
        val openSort = AppWorkflowRouter.inAppPlan(command, route, resultScreen)
        assertEquals(ActionType.CLICK, openSort?.actions?.first()?.type)
        assertEquals("0.sort", openSort?.actions?.first()?.target)

        val sortSheet = resultScreen.copy(
            elements = resultScreen.elements + listOf(
                element(path = "0.sheet.default", text = "기본순"),
                element(path = "0.sheet.popular", text = "주문 많은 순"),
                element(path = "0.sheet.rating", text = "별점 높은 순"),
            ),
        )
        val choosePopular = AppWorkflowRouter.inAppPlan(command, route, sortSheet)
        assertEquals("0.sheet.popular", choosePopular?.actions?.first()?.target)

        val sorted = resultScreen.copy(
            elements = resultScreen.elements.map { candidate ->
                if (candidate.path == "0.sort") candidate.copy(text = "주문 많은 순") else candidate
            },
        )
        assertEquals(true, AppWorkflowRouter.inAppPlan(command, route, sorted)?.goalCompleted)

        val informationalDefaultOnly = resultScreen.copy(
            elements = resultScreen.elements.map { candidate ->
                if (candidate.path == "0.sort") candidate.copy(text = "기본순") else candidate
            },
        )
        assertEquals(
            true,
            AppWorkflowRouter.inAppPlan(command, route, informationalDefaultOnly)?.goalCompleted,
        )
    }

    @Test
    fun finalGoalCommandsInferAnInstalledDomainAppWithoutTreatingEntitiesAsApps() {
        val order = "가장 인기 있는 파스타 집에서 가장 인기 있는 파스타 주문해줘"
        val orderIntent = com.hwanghj09.sonju.task.DeterministicTaskParser.parse(order)
        assertNull(orderIntent.targetApp)
        assertEquals("파스타", orderIntent.entities["restaurant_query"])
        assertEquals("파스타", orderIntent.entities["menu_query"])
        assertEquals(
            "배달의민족",
            AppWorkflowRouter.route(order, listOf("요기요", "배달의민족"))?.appLabel,
        )

        val profile = requireNotNull(AppWorkflowRouter.route("내 카톡 프로필 바꾸고 싶어"))
        assertEquals("카톡", profile.appLabel)
        assertEquals(true, profile.targetSurface.contains("프로필"))

        val message = "엄마한테 안녕이라고 보내줘"
        assertEquals(
            "카카오톡",
            AppWorkflowRouter.route(message, listOf("메시지", "카카오톡"))?.appLabel,
        )
        val messageIntent = com.hwanghj09.sonju.task.DeterministicTaskParser.parse(message)
        assertEquals("엄마", messageIntent.entities["recipient"])
        assertEquals("안녕", messageIntent.entities["message"])
        val placeholderMessage = "OO한테 손주 테스트라고 보내줘"
        assertEquals(
            "카카오톡",
            AppWorkflowRouter.route(placeholderMessage, listOf("손주", "카카오톡"))?.appLabel,
        )
        assertEquals(
            "OO",
            com.hwanghj09.sonju.task.DeterministicTaskParser.parse(placeholderMessage)
                .entities["recipient"],
        )

        val directions = "서울역 가는 길 알려줘"
        val directionsRoute = AppWorkflowRouter.route(
            directions,
            listOf("Google 지도", "카카오맵", "네이버 지도"),
        )
        assertEquals("네이버 지도", directionsRoute?.appLabel)
        assertEquals("서울역", directionsRoute?.launchQuery)
        assertNull(com.hwanghj09.sonju.task.DeterministicTaskParser.parse(directions).targetApp)
    }

    @Test
    fun profileMessageAndDirectionsStopAtTheirRequestedBoundaries() {
        val profileCommand = "내 카톡 프로필 바꾸고 싶어"
        val profileRoute = requireNotNull(AppWorkflowRouter.route(profileCommand))
        val openProfileTab = AppWorkflowRouter.inAppPlan(
            profileCommand,
            profileRoute,
            snapshot().copy(elements = listOf(element("0.friends", contentDescription = "친구 탭"))),
        )
        assertEquals("0.friends", openProfileTab?.actions?.first()?.target)
        val openOwnProfile = AppWorkflowRouter.inAppPlan(
            profileCommand,
            profileRoute,
            snapshot().copy(elements = listOf(element("0.me", contentDescription = "내 프로필 사진"))),
        )
        assertEquals("0.me", openOwnProfile?.actions?.first()?.target)
        val openEdit = AppWorkflowRouter.inAppPlan(
            profileCommand,
            profileRoute,
            snapshot().copy(elements = listOf(element("0.edit", text = "프로필 편집"))),
        )
        assertEquals("0.edit", openEdit?.actions?.first()?.target)
        val editReached = AppWorkflowRouter.inAppPlan(
            profileCommand,
            profileRoute,
            snapshot().copy(elements = listOf(element("0.title", text = "프로필 편집", clickable = false))),
        )
        assertEquals(true, editReached?.goalCompleted)

        val messageCommand = "민수한테 조금 늦는다고 보내줘"
        val messageRoute = requireNotNull(
            AppWorkflowRouter.route(messageCommand, listOf("카카오톡")),
        )
        val leaveNestedScreen = AppWorkflowRouter.inAppPlan(
            messageCommand,
            messageRoute,
            snapshot().copy(
                elements = listOf(
                    element("0.back", contentDescription = "이전"),
                    element("0.done", contentDescription = "완료"),
                ),
            ),
        )
        assertEquals("0.back", leaveNestedScreen?.actions?.first()?.target)
        val leaveSensitiveNestedScreen = AppWorkflowRouter.inAppPlan(
            messageCommand,
            messageRoute,
            snapshot().copy(
                elements = listOf(
                    element("0.back", contentDescription = "이전"),
                    element("0.private", text = null).copy(sensitive = true),
                ),
            ),
        )
        assertEquals("0.back", leaveSensitiveNestedScreen?.actions?.first()?.target)
        val leaveUnlabelledNestedScreen = AppWorkflowRouter.inAppPlan(
            messageCommand,
            messageRoute,
            snapshot().copy(
                elements = listOf(element("0.title", text = "프로필", clickable = false)),
            ),
        )
        assertEquals(ActionType.BACK, leaveUnlabelledNestedScreen?.actions?.first()?.type)
        val waitForMessagingHome = AppWorkflowRouter.inAppPlan(
            messageCommand,
            messageRoute,
            snapshot().copy(
                elements = listOf(element("0.chat-tab", contentDescription = "채팅 탭")),
            ),
        )
        assertEquals(ActionType.WAIT, waitForMessagingHome?.actions?.first()?.type)
        val typeRecipient = AppWorkflowRouter.inAppPlan(
            messageCommand,
            messageRoute,
            snapshot().copy(
                elements = listOf(
                    element("0.search", hintText = "검색", clickable = false, editable = true),
                ),
            ),
        )
        assertEquals(ActionType.SET_TEXT, typeRecipient?.actions?.first()?.type)
        assertEquals("민수", typeRecipient?.actions?.first()?.value)
        val typeRecipientIntoUnlabelledWebSearch = AppWorkflowRouter.inAppPlan(
            messageCommand,
            messageRoute,
            snapshot().copy(
                elements = listOf(
                    element(
                        "0.web",
                        bounds = ScreenBounds(0, 0, 1080, 1920),
                        clickable = false,
                        editable = true,
                    ).copy(className = "android.webkit.WebView", focusable = true),
                    element(
                        "0.search",
                        bounds = ScreenBounds(75, 139, 1005, 217),
                        clickable = true,
                        editable = true,
                    ).copy(className = "android.widget.EditText", focusable = true),
                    element("0.back", contentDescription = "이전"),
                ),
            ),
        )
        assertEquals(ActionType.SET_TEXT, typeRecipientIntoUnlabelledWebSearch?.actions?.first()?.type)
        assertEquals("0.search", typeRecipientIntoUnlabelledWebSearch?.actions?.first()?.target)
        assertEquals("민수", typeRecipientIntoUnlabelledWebSearch?.actions?.first()?.value)
        val ignoreNarrowAuxiliaryInput = AppWorkflowRouter.inAppPlan(
            messageCommand,
            messageRoute,
            snapshot().copy(
                elements = listOf(
                    element(
                        "0.search",
                        bounds = ScreenBounds(75, 139, 1005, 217),
                        clickable = true,
                        editable = true,
                    ).copy(className = "android.widget.EditText", focusable = true),
                    element(
                        "0.auxiliary",
                        bounds = ScreenBounds(0, 94, 30, 232),
                        clickable = true,
                        editable = true,
                    ).copy(className = "android.widget.EditText", focusable = true),
                    element(
                        "0.screen",
                        bounds = ScreenBounds(0, 0, 1080, 1920),
                        clickable = false,
                    ),
                ),
            ),
        )
        assertEquals(ActionType.SET_TEXT, ignoreNarrowAuxiliaryInput?.actions?.first()?.type)
        assertEquals("0.search", ignoreNarrowAuxiliaryInput?.actions?.first()?.target)
        assertEquals("민수", ignoreNarrowAuxiliaryInput?.actions?.first()?.value)
        val narrowToRecipientResults = AppWorkflowRouter.inAppPlan(
            messageCommand,
            messageRoute,
            snapshot().copy(
                elements = listOf(
                    element(
                        "0.search",
                        text = "민수",
                        bounds = ScreenBounds(75, 139, 1005, 217),
                        clickable = true,
                        editable = true,
                    ).copy(className = "android.widget.EditText", focusable = true),
                    element("0.all", text = "전체").copy(selected = true),
                    element("0.friends", text = "친구"),
                    element(
                        "0.screen",
                        bounds = ScreenBounds(0, 0, 1080, 1920),
                        clickable = false,
                    ),
                ),
            ),
        )
        assertEquals(ActionType.CLICK, narrowToRecipientResults?.actions?.first()?.type)
        assertEquals("0.friends", narrowToRecipientResults?.actions?.first()?.target)
        val typeRecipientBesideSensitiveContent = AppWorkflowRouter.inAppPlan(
            messageCommand,
            messageRoute,
            snapshot().copy(
                elements = listOf(
                    element("0.search", hintText = "검색", clickable = false, editable = true),
                    element("0.private", text = null).copy(sensitive = true),
                ),
            ),
        )
        assertEquals(
            ActionType.SET_TEXT,
            typeRecipientBesideSensitiveContent?.actions?.first()?.type,
        )
        val openChat = AppWorkflowRouter.inAppPlan(
            messageCommand,
            messageRoute,
            snapshot().copy(
                elements = listOf(
                    element(
                        "0.search",
                        text = "민수",
                        hintText = "검색",
                        clickable = false,
                        editable = true,
                    ),
                    element("0.minsu", text = "민수"),
                ),
            ),
        )
        assertEquals("0.minsu", openChat?.actions?.first()?.target)
        val hiddenProfileSnapshot = snapshot().copy(
            packageName = "com.kakao.talk",
            windowBounds = ScreenBounds(0, 0, 1080, 2640),
            elements = listOf(
                element(
                    "0",
                    viewId = "com.kakao.talk:id/profile_home",
                    bounds = ScreenBounds(0, 94, 1080, 2520),
                ),
                element(
                    "0.profile.photo",
                    contentDescription = "사진",
                    bounds = ScreenBounds(0, 1_581, 358, 2_061),
                ),
            ),
        )
        val openChatFromProfileWithVisualGrounding = AppWorkflowRouter.inAppPlan(
            messageCommand,
            messageRoute,
            hiddenProfileSnapshot,
            successfulActions = listOf(
                AgentAction(ActionType.CLICK, "민수 대화방을 엽니다.", target = "0.result"),
            ),
        )
        assertEquals(
            ActionType.CLICK_COORDINATE,
            openChatFromProfileWithVisualGrounding?.actions?.first()?.type,
        )
        assertEquals(PlanSource.APP_ADAPTER, openChatFromProfileWithVisualGrounding?.source)
        assertEquals(true, openChatFromProfileWithVisualGrounding?.visualFallback)
        assertEquals(
            AppWorkflowRouter.KAKAO_PROFILE_CHAT_ADAPTER_TARGET,
            openChatFromProfileWithVisualGrounding?.actions?.first()?.target,
        )
        assertEquals(
            AppWorkflowRouter.KAKAO_PROFILE_CHAT_X_RATIO,
            openChatFromProfileWithVisualGrounding?.actions?.first()?.xRatio ?: 0.0,
            0.0,
        )
        assertEquals(
            requireNotNull(AppWorkflowRouter.kakaoProfileChatYRatio(hiddenProfileSnapshot)),
            openChatFromProfileWithVisualGrounding?.actions?.first()?.yRatio ?: 0.0,
            0.0,
        )
        val wrongRecipientHistory = AppWorkflowRouter.inAppPlan(
            messageCommand,
            messageRoute,
            hiddenProfileSnapshot,
            successfulActions = listOf(
                AgentAction(ActionType.CLICK, "영희 대화방을 엽니다.", target = "0.result"),
            ),
        )
        assertEquals(ActionType.BACK, wrongRecipientHistory?.actions?.first()?.type)
        val findUnexposedChatControlVisually = AppWorkflowRouter.inAppPlan(
            messageCommand,
            messageRoute,
            snapshot().copy(
                windowBounds = ScreenBounds(0, 0, 1080, 1920),
                elements = listOf(
                    element(
                        "0",
                        bounds = ScreenBounds(0, 0, 1080, 1920),
                    ),
                    element(
                        "0.profile.back",
                        contentDescription = "이전",
                        clickable = false,
                    ),
                    element(
                        "0.profile.photo",
                        contentDescription = "사진",
                        bounds = ScreenBounds(0, 900, 360, 1_400),
                    ),
                ),
            ),
        )
        assertEquals(ActionType.CLICK, findUnexposedChatControlVisually?.actions?.first()?.type)
        assertNull(findUnexposedChatControlVisually?.actions?.first()?.target)
        val typeDraft = AppWorkflowRouter.inAppPlan(
            messageCommand,
            messageRoute,
            snapshot().copy(
                elements = listOf(
                    element(
                        "0.input",
                        hintText = "메시지 입력",
                        clickable = false,
                        editable = true,
                    ),
                ),
            ),
        )
        assertEquals("조금 늦는", typeDraft?.actions?.first()?.value)
        val beforeSend = AppWorkflowRouter.inAppPlan(
            messageCommand,
            messageRoute,
            snapshot().copy(
                elements = listOf(
                    element("0.person", text = "민수", clickable = false),
                    element(
                        "0.input",
                        text = "조금 늦는",
                        hintText = "메시지 입력",
                        clickable = false,
                        editable = true,
                    ),
                    element("0.send", contentDescription = "전송"),
                ),
            ),
        )
        assertEquals(true, beforeSend?.goalCompleted)
        assertEquals(listOf(ActionType.FINISH), beforeSend?.actions?.map { it.type })

        val directionsCommand = "서울역 가는 길 알려줘"
        val directionsRoute = requireNotNull(
            AppWorkflowRouter.route(directionsCommand, listOf("네이버 지도")),
        )
        val openDirections = AppWorkflowRouter.inAppPlan(
            directionsCommand,
            directionsRoute,
            snapshot().copy(
                elements = listOf(
                    element("0.destination", text = "서울역", clickable = false),
                    element("0.route", text = "길찾기"),
                ),
            ),
        )
        assertEquals("0.route", openDirections?.actions?.first()?.target)
        val repeatedMapResults = AppWorkflowRouter.inAppPlan(
            directionsCommand,
            directionsRoute,
            snapshot().copy(
                elements = listOf(
                    element(
                        "0.query",
                        text = "서울역",
                        bounds = ScreenBounds(0, 100, 600, 200),
                    ),
                    element(
                        "0.result1",
                        text = "서울역 공항철도",
                        bounds = ScreenBounds(0, 800, 600, 900),
                    ),
                    element(
                        "0.route1",
                        text = "길찾기",
                        bounds = ScreenBounds(0, 960, 300, 1_060),
                    ),
                    element(
                        "0.result2",
                        text = "서울역 1호선",
                        bounds = ScreenBounds(0, 1_200, 600, 1_300),
                    ),
                    element(
                        "0.route2",
                        text = "길찾기",
                        bounds = ScreenBounds(0, 1_360, 300, 1_460),
                    ),
                ),
            ),
        )
        assertEquals("0.route1", repeatedMapResults?.actions?.first()?.target)
        val routeReached = AppWorkflowRouter.inAppPlan(
            directionsCommand,
            directionsRoute,
            snapshot().copy(
                elements = listOf(
                    element("0.destination", text = "서울역", clickable = false),
                    element("0.from", text = "출발", clickable = false),
                    element("0.to", text = "도착", clickable = false),
                ),
            ),
        )
        assertEquals(true, routeReached?.goalCompleted)
    }

    @Test
    fun truncatedFoodHomeCanOpenOnlyTheExactVisibleSearchControl() {
        val command = "가장 인기 있는 파스타 집에서 가장 인기 있는 파스타 주문해줘"
        val route = requireNotNull(
            AppWorkflowRouter.route(command, listOf("배달의민족")),
        )
        val truncatedHome = snapshot().copy(
            packageName = "com.sampleapp",
            treeTruncated = true,
            elements = listOf(
                element("0.category", text = "음식배달"),
                element("0.search", contentDescription = "검색 버튼, 배민클럽은 배달팁 0원에 쿠폰까지!"),
            ),
        )

        val plan = AppWorkflowRouter.inAppPlan(command, route, truncatedHome)

        assertEquals("0.search", plan?.actions?.first()?.target)
        assertEquals(ActionType.CLICK, plan?.actions?.first()?.type)

        val ambiguousHome = truncatedHome.copy(
            elements = truncatedHome.elements +
                element("0.search2", contentDescription = "검색하기"),
        )
        assertNull(AppWorkflowRouter.inAppPlan(command, route, ambiguousHome))
    }

    @Test
    fun genericSearchNeverUsesAnUnlabelledLowerFormField() {
        val command = "가장 인기 있는 파스타 집에서 가장 인기 있는 파스타 주문해줘"
        val route = requireNotNull(AppWorkflowRouter.route(command, listOf("배달의민족")))
        val form = snapshot().copy(
            packageName = "com.example.anyapp",
            elements = listOf(
                element(
                    "0.form.note",
                    text = "요청사항",
                    bounds = ScreenBounds(20, 1_500, 1_060, 1_650),
                    editable = true,
                ),
                element(
                    "0.form.submit",
                    text = "다음",
                    bounds = ScreenBounds(20, 1_800, 1_060, 1_950),
                ),
            ),
        )

        assertNull(AppWorkflowRouter.inAppPlan(command, route, form))
    }

    @Test
    fun popularFoodOrderUsesRankedSemanticCardsAndStopsBeforePayment() {
        val command = "가장 인기 있는 파스타 집에서 가장 인기 있는 파스타 주문해줘"
        val route = requireNotNull(
            AppWorkflowRouter.route(command, listOf("배달의민족")),
        )
        val sortedRestaurants = snapshot().copy(
            packageName = "com.sampleapp",
            elements = listOf(
                element("0.sort", text = "주문 많은 순", bounds = ScreenBounds(0, 100, 500, 180)),
                element(
                    "0.sort.label",
                    text = "주문 많은 순",
                    bounds = ScreenBounds(0, 100, 500, 180),
                ),
                element(
                    "0.ad",
                    contentDescription = "광고 파스타마켓 별점 4.9 리뷰 999",
                    bounds = ScreenBounds(0, 200, 500, 360),
                ),
                element(
                    "0.closed",
                    contentDescription = "파스타예요 별점 4.9 리뷰 900 오늘 오전 06:00 오픈",
                    bounds = ScreenBounds(0, 370, 500, 540),
                ),
                element(
                    "0.restaurant",
                    contentDescription = "파스타하우스 별점 4.8 리뷰 300 배달비 무료",
                    bounds = ScreenBounds(0, 560, 500, 740),
                ),
                element(
                    "0.restaurant.rating",
                    viewId = "com.example:id/star_rating",
                    text = "4.8",
                    clickable = false,
                    bounds = ScreenBounds(220, 420, 300, 470),
                ).copy(stateDescription = "rating"),
                element(
                    "0.restaurant.badge",
                    viewId = "com.example:id/restaurant_badge",
                    text = "배달팁 할인",
                    clickable = false,
                    bounds = ScreenBounds(300, 420, 450, 470),
                ),
            ),
        )
        val restaurant = AppWorkflowRouter.inAppPlan(command, route, sortedRestaurants)
        assertEquals("0.restaurant", restaurant?.actions?.first()?.target)

        val closedRestaurant = snapshot().copy(
            elements = listOf(
                element("0.info", text = "운영안내", clickable = false),
                element("0.open", text = "오늘 오전 06:00 오픈", clickable = false),
            ),
        )
        assertEquals(
            ActionType.BACK,
            AppWorkflowRouter.inAppPlan(command, route, closedRestaurant)?.actions?.first()?.type,
        )

        val restaurantMenu = snapshot().copy(
            packageName = "com.sampleapp",
            elements = listOf(
                element(
                    "0.heading",
                    text = "인기 메뉴",
                    clickable = false,
                    bounds = ScreenBounds(0, 100, 500, 180),
                ),
                element(
                    "0.pasta",
                    contentDescription = "크림 파스타 14,000원",
                    bounds = ScreenBounds(0, 220, 500, 360),
                ),
                element(
                    "0.pasta2",
                    contentDescription = "토마토 파스타 13,000원",
                    bounds = ScreenBounds(0, 380, 500, 520),
                ),
            ),
        )
        val menu = AppWorkflowRouter.inAppPlan(command, route, restaurantMenu)
        assertEquals("0.pasta", menu?.actions?.first()?.target)

        val scrolledMenuWithoutHeading = snapshot().copy(
            packageName = "com.another.delivery",
            elements = listOf(
                element("0.search-tab", text = "검색한 메뉴"),
                element(
                    "0.first-menu",
                    contentDescription = "인기 1위 그릴드 쉬림프 샐러드 파스타 15,900원리뷰 4",
                    bounds = ScreenBounds(0, 220, 500, 420),
                ),
                element(
                    "0.restaurant-result",
                    contentDescription = "파스타 식당 최소주문 5,000원 배달비 2,000원 2.7km",
                    bounds = ScreenBounds(0, 440, 500, 640),
                ),
            ),
        )
        val scrolledMenu = AppWorkflowRouter.inAppPlan(command, route, scrolledMenuWithoutHeading)
        assertEquals("0.first-menu", scrolledMenu?.actions?.first()?.target)

        val partiallyLoadedMenuSection = snapshot().copy(
            elements = listOf(
                element("0.heading", text = "파스타로 검색한 메뉴", clickable = false),
                element(
                    "0.list",
                    bounds = ScreenBounds(0, 0, 1_080, 2_400),
                    clickable = false,
                    scrollable = true,
                ),
            ),
        )
        val revealMenu = AppWorkflowRouter.inAppPlan(command, route, partiallyLoadedMenuSection)
        assertEquals(ActionType.SCROLL_DOWN, revealMenu?.actions?.first()?.type)
        assertEquals("0.list", revealMenu?.actions?.first()?.target)

        val detail = snapshot().copy(
            elements = listOf(
                element("0.title", text = "크림 파스타", clickable = false),
                element("0.add", text = "14,000원 담기"),
                element("0.redacted-review", text = null, clickable = false).copy(sensitive = true),
            ),
        )
        val addMenu = AppWorkflowRouter.inAppPlan(command, route, detail)
        assertEquals("0.add", addMenu?.actions?.first()?.target)
        assertEquals(true, addMenu?.actions?.first()?.description?.contains("파스타"))

        val afterAdd = snapshot().copy(elements = listOf(element("0.cart", text = "장바구니 보기")))
        assertNull(AppWorkflowRouter.inAppPlan(command, route, afterAdd))
        assertEquals(
            "0.cart",
            AppWorkflowRouter.inAppPlan(
                command,
                route,
                afterAdd,
                successfulActions = listOf(requireNotNull(addMenu).actions.first()),
            )?.actions?.first()?.target,
        )

        val staleCartAlongsideMenu = restaurantMenu.copy(
            elements = restaurantMenu.elements + element("0.cart", text = "장바구니 보기"),
        )
        assertEquals(
            "0.pasta",
            AppWorkflowRouter.inAppPlan(command, route, staleCartAlongsideMenu)
                ?.actions?.first()?.target,
        )

        val replaceExistingCart = snapshot().copy(
            elements = listOf(
                element(
                    "0.message",
                    text = "장바구니에는 같은 가게의 메뉴만 담을 수 있습니다. " +
                        "선택하신 메뉴를 장바구니에 담을 경우 이전에 담은 메뉴가 삭제됩니다.",
                    clickable = false,
                ),
                element("0.cancel", text = "취소"),
                element("0.confirm", text = "담기"),
                element("0.background", text = "15,900원 담기"),
            ),
        )
        val replaceCart = AppWorkflowRouter.inAppPlan(command, route, replaceExistingCart)
        assertEquals("0.confirm", replaceCart?.actions?.first()?.target)
        assertEquals(true, replaceCart?.actions?.first()?.description?.contains("장바구니 교체"))

        val emptyCart = snapshot().copy(
            elements = listOf(
                element("0.title", text = "장바구니", clickable = false),
                element("0.empty", text = "담은 메뉴가 없어요", clickable = false),
            ),
        )
        assertEquals(
            ActionType.BACK,
            AppWorkflowRouter.inAppPlan(command, route, emptyCart)?.actions?.first()?.type,
        )

        val cart = snapshot().copy(
            elements = listOf(
                element("0.title", text = "장바구니", clickable = false),
                element("0.item", text = "크림 파스타", clickable = false),
                element("0.review", text = "배달 주문하기"),
            ),
        )
        val review = AppWorkflowRouter.inAppPlan(command, route, cart)
        assertEquals("0.review", review?.actions?.first()?.target)
        assertEquals(true, review?.actions?.first()?.description?.contains("주문서 화면"))

        val checkout = snapshot().copy(
            elements = listOf(
                element("0.item", text = "크림 파스타", clickable = false),
                element("0.payment", text = "결제수단", clickable = false),
                element("0.commit", text = "23,000원 결제하기"),
            ),
        )
        val completed = AppWorkflowRouter.inAppPlan(command, route, checkout)
        assertEquals(true, completed?.goalCompleted)
        assertEquals(listOf(ActionType.FINISH), completed?.actions?.map { it.type })

        val renamedMenuCheckout = snapshot().copy(
            elements = listOf(
                element("0.heading", text = "담은 메뉴", clickable = false),
                element("0.item", text = "알리오 시그니처 링귀니", clickable = false),
                element("0.private", text = null, clickable = false).copy(sensitive = true),
                element("0.commit", text = "11,900원 결제하기"),
            ),
        )
        assertNull(AppWorkflowRouter.inAppPlan(command, route, renamedMenuCheckout))
        val completedFromVerifiedSelection = AppWorkflowRouter.inAppPlan(
            command,
            route,
            renamedMenuCheckout,
            successfulActions = listOf(
                AgentAction(
                    ActionType.CLICK,
                    "요청한 인기 메뉴 첫 번째 '파스타' 항목을 선택합니다.",
                    "0.menu",
                ),
            ),
        )
        assertEquals(true, completedFromVerifiedSelection?.goalCompleted)
        assertEquals(listOf(ActionType.FINISH), completedFromVerifiedSelection?.actions?.map { it.type })

        val redactedCheckout = snapshot().copy(
            elements = listOf(
                element("0.item", text = "알리오 시그니처 링귀니", clickable = false),
                element("0.private", text = null, clickable = false).copy(sensitive = true),
                element("0.commit", text = "11,900원 결제하기"),
            ),
        )
        val verifiedOrderActions = listOf(
            AgentAction(
                ActionType.CLICK,
                "요청한 인기 메뉴 '파스타'를 장바구니에 담습니다.",
                "0.menu",
            ),
            AgentAction(
                ActionType.CLICK,
                "추천 상품은 추가하지 않고 주문서 화면으로 이동합니다.",
                "0.review",
            ),
        )
        assertNull(
            AppWorkflowRouter.inAppPlan(
                command,
                route,
                redactedCheckout,
                successfulActions = verifiedOrderActions.take(1),
            ),
        )
        assertEquals(
            true,
            AppWorkflowRouter.inAppPlan(
                command,
                route,
                redactedCheckout,
                successfulActions = verifiedOrderActions,
            )?.goalCompleted,
        )

        val membershipOffer = snapshot().copy(
            elements = listOf(
                element("0.close", text = "닫기"),
                element("0.offer", text = "멤버십 가입하고 이번 주문 할인 받으세요", clickable = false),
                element("0.join", text = "멤버십으로 할인받고 주문하기"),
            ),
        )
        val dismissOffer = AppWorkflowRouter.inAppPlan(command, route, membershipOffer)
        assertEquals("0.close", dismissOffer?.actions?.first()?.target)

        val discountOffer = membershipOffer.copy(
            elements = listOf(
                element("0.close", text = "닫기"),
                element("0.offer", text = "잠깐, 지금 선택하면 더 할인받아요", clickable = false),
                element("0.decline", text = "할인 없이 결제"),
            ),
        )
        assertEquals(
            "0.close",
            AppWorkflowRouter.inAppPlan(command, route, discountOffer)?.actions?.first()?.target,
        )

        val crossSellOffer = membershipOffer.copy(
            elements = listOf(
                element("0.close", text = "닫기"),
                element("0.offer", text = "함께 먹으면 더 좋아요", clickable = false),
                element("0.extra", text = "추천 상품 3,900원"),
                element("0.continue", text = "배달 주문하기"),
            ),
        )
        assertEquals(
            "0.continue",
            AppWorkflowRouter.inAppPlan(command, route, crossSellOffer)?.actions?.first()?.target,
        )

        val recoverableAlert = snapshot().copy(
            elements = listOf(
                element(
                    "0.message",
                    text = "선택하신 옵션으로 진행할 수 없습니다. 다른 옵션을 선택하거나 다시 시도해주세요.",
                    clickable = false,
                ),
                element("0.ok", text = "확인"),
            ),
        )
        val dismissAlert = AppWorkflowRouter.inAppPlan(command, route, recoverableAlert)
        assertEquals("0.ok", dismissAlert?.actions?.first()?.target)
        assertEquals(true, dismissAlert?.actions?.first()?.description?.contains("오류 안내"))
    }

    @Test
    fun explicitNegation_neverRunsTheOppositeLocalAction() {
        listOf(
            "카메라 열지 마",
            "뒤로 가지 마",
            "홈 화면으로 가지 마",
            "화면 아래로 내리지 마",
            "카메라 열면 안 돼",
            "뒤로 가서는 안 돼",
            "카메라 열어선 안 돼",
            "카메라 켜는 건 안 돼",
            "카메라 켜는 것은 안 돼",
            "카메라 켜면 절대 안 돼",
        ).forEach { command ->
            assertNull(command, RuleBasedPlanner.plan(command))
        }
    }

    @Test
    fun ordinaryPhraseContainingSeonAnIsNotTreatedAsNegation() {
        assertNull(RuleBasedPlanner.plan("우선 안내를 보여 줘"))
        assertNull(RuleBasedPlanner.plan("홈 화면 버튼이 안 보여서 홈 화면으로 가 줘"))
    }

    @Test
    fun questionsExplanationsAndQuotedCommandsNeverExecuteLocally() {
        listOf(
            "카메라 켜는 법 알려줘",
            "카메라를 켜는 순서를 적어 줘",
            "카메라를 켜는 방법을 화면에 보여 줘",
            "카메라 켜도 돼?",
            "홈 화면이 뭐야?",
            "카메라를 켜라고 했어",
            "와이파이 설정은 어떻게 열어?",
        ).forEach { command ->
            assertNull(command, RuleBasedPlanner.plan(command))
        }
    }

    @Test
    fun explicitImperativeFormsStillUseTheLocalPath() {
        assertEquals(ActionType.OPEN_CAMERA, RuleBasedPlanner.plan("카메라를 켜 줘")?.actions?.first()?.type)
        assertEquals(ActionType.HOME, RuleBasedPlanner.plan("홈 화면으로 가 줘")?.actions?.first()?.type)
        assertEquals(ActionType.BACK, RuleBasedPlanner.plan("이전 화면으로 가기")?.actions?.first()?.type)
        assertEquals(
            ActionType.OPEN_DISPLAY_SETTINGS,
            RuleBasedPlanner.plan("디스플레이 설정을 열어 줘")?.actions?.first()?.type,
        )
        assertEquals(
            ActionType.OPEN_DATE_SETTINGS,
            RuleBasedPlanner.plan("날짜 및 시간 설정 열기")?.actions?.first()?.type,
        )
    }

    @Test
    fun trustedWifiToggleUsesExactVisibleLabelWithoutCallingGemini() {
        val label = UiElement(
            path = "0.1.0.0",
            viewId = "android:id/title",
            className = "android.widget.TextView",
            text = "Wi‑Fi",
            contentDescription = null,
            bounds = ScreenBounds(60, 700, 200, 850),
            clickable = false,
            editable = false,
            scrollable = false,
            enabled = true,
            visible = true,
            sensitive = false,
        )
        val snapshot = UiSnapshot(
            packageName = "com.android.settings",
            windowTitle = "Internet",
            epoch = 1,
            elements = listOf(label),
            trustedSettingsRoute = TrustedSettingsRoute.WIFI,
        )

        val plan = RuleBasedPlanner.plan("Turn off Wi-Fi", snapshot)

        assertEquals(PlanSource.LOCAL_RULE, plan?.source)
        assertEquals(ActionType.CLICK, plan?.actions?.first()?.type)
        assertEquals("Wi‑Fi", plan?.actions?.first()?.target)
        assertEquals("unchecked", plan?.actions?.first()?.value)
    }

    @Test
    fun trustedWifiToggleTreatsUnicodeHyphenVariantsAsOneVisibleControl() {
        val labels = listOf("Wi-Fi", "Wi‑Fi").mapIndexed { index, text ->
            UiElement(
                path = "0.1.$index",
                viewId = "android:id/title",
                className = "android.widget.TextView",
                text = text,
                contentDescription = null,
                bounds = ScreenBounds(60, 700, 200, 850),
                clickable = false,
                editable = false,
                scrollable = false,
                enabled = true,
                visible = true,
                sensitive = false,
            )
        }
        val snapshot = UiSnapshot(
            packageName = "com.android.settings",
            windowTitle = "Internet",
            epoch = 1,
            elements = labels,
            trustedSettingsRoute = TrustedSettingsRoute.WIFI,
        )

        val plan = RuleBasedPlanner.plan("Turn off Wi-Fi", snapshot)

        assertEquals(PlanSource.LOCAL_RULE, plan?.source)
        assertEquals("Wi-Fi", plan?.actions?.first()?.target)
    }

    @Test
    fun toggleRuleRequiresMatchingTrustedRouteAndWholeControlName() {
        val snapshot = UiSnapshot.empty().copy(
            packageName = "com.android.settings",
            windowTitle = "Internet",
            trustedSettingsRoute = TrustedSettingsRoute.SOUND,
        )

        assertNull(RuleBasedPlanner.plan("Turn off Wi-Fi", snapshot))
        assertNull(RuleBasedPlanner.plan("Turn off Wi-Fi and payments", snapshot))
    }

    private fun snapshot() = UiSnapshot(
        packageName = "com.example.target",
        windowTitle = "Target",
        epoch = 1,
        elements = emptyList(),
    )

    private fun element(
        path: String,
        viewId: String? = null,
        contentDescription: String? = null,
        bounds: ScreenBounds = ScreenBounds(20, 100, 500, 260),
        text: String? = null,
        hintText: String? = null,
        clickable: Boolean = true,
        editable: Boolean = false,
        focused: Boolean = false,
        scrollable: Boolean = false,
    ) = UiElement(
        path = path,
        viewId = viewId,
        className = "android.view.View",
        text = text,
        contentDescription = contentDescription,
        bounds = bounds,
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        enabled = true,
        visible = true,
        sensitive = false,
        hintText = hintText,
        focused = focused,
    )
}
