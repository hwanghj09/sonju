package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.AgentAction
import com.hwanghj09.sonju.agent.AgentPlan
import com.hwanghj09.sonju.agent.PlanSource
import com.hwanghj09.sonju.agent.RiskLevel
import com.hwanghj09.sonju.agent.SafetyDecision
import com.hwanghj09.sonju.agent.SafetyPolicy
import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.TrustedSettingsRoute
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class SafetyPolicyTest {
    @Test
    fun transferCommand_isAlwaysBlocked() {
        val assessment = SafetyPolicy.evaluate(
            command = "딸에게 10만 원 송금해 줘",
            plan = planOf(AgentAction(ActionType.CLICK, "송금 누르기", "송금")),
            snapshot = snapshot(element(text = "송금", clickable = true)),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    @Test
    fun sixDigitSecret_isBlockedBeforeModelUse() {
        val assessment = SafetyPolicy.preflightCommand("여기에 839201을 입력해 줘")

        assertEquals(SafetyDecision.BLOCK, assessment?.decision)
    }

    @Test
    fun fourAndFiveDigitInput_isBlockedBeforeModelUse() {
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("여기에 1234를 입력해 줘")?.decision,
        )
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("code 83 920을 적어 줘")?.decision,
        )
        assertEquals(SafetyDecision.BLOCK, SafetyPolicy.preflightCommand("1234")?.decision)
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("여기에 1234 쳐 줘")?.decision,
        )
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("여기에 ١٢٣٤를 넣어 줘")?.decision,
        )
        assertEquals(null, SafetyPolicy.preflightCommand("2026년 달력을 열어 줘"))
        assertEquals(null, SafetyPolicy.preflightCommand("5000원 상품을 보여 줘"))
        assertEquals(null, SafetyPolicy.preflightCommand("12:34 알람을 보여 줘"))
        assertEquals(null, SafetyPolicy.preflightCommand("Show July 18, 2026 on the calendar"))
        assertEquals(null, SafetyPolicy.preflightCommand("2026-07-18 일정을 보여 줘"))
    }

    @Test
    fun wonSuffixOnlyExemptsAnExplicitBoundedAmount() {
        assertEquals(null, SafetyPolicy.preflightCommand("100,000원 상품을 보여 줘"))
        assertEquals(null, SafetyPolicy.preflightCommand("5000원짜리 상품을 보여 줘"))
        assertEquals(null, SafetyPolicy.preflightCommand("5000원어치 상품을 보여 줘"))
        assertEquals(null, SafetyPolicy.preflightCommand("가격은 5000원입니다. 상품을 보여 줘"))
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("4111111111111111 원래 번호를 보여 줘")?.decision,
        )
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("411111111111 원본을 보여 줘")?.decision,
        )
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("411111111111 원하면 보여 줘")?.decision,
        )
    }

    @Test
    fun strictDatesAreExemptOnlyWhenTheyExactlyCoverTheNumericRun() {
        listOf(
            "Show July 18, 2026 on the calendar",
            "Show 18 July 2026 on the calendar",
            "Show July 2026 on the calendar",
            "2026-07-18 12:34 일정을 보여 줘",
            "2026. 7. 18. 일정을 보여 줘",
            "18/07/2026 일정을 보여 줘",
            "07/18/2026 일정을 보여 줘",
        ).forEach { command ->
            assertEquals(command, null, SafetyPolicy.preflightCommand(command))
        }

        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("July 4111 1111 1111 2026을 보여 줘")?.decision,
        )
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("July 2026 4111 1111을 보여 줘")?.decision,
        )
    }

    @Test
    fun standaloneClockShapeRequiresExplicitTimeContext() {
        assertEquals(SafetyDecision.BLOCK, SafetyPolicy.preflightCommand("12:34")?.decision)
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("숫자 12:34를 보여 줘")?.decision,
        )
        assertEquals(null, SafetyPolicy.preflightCommand("12:34 알람을 보여 줘"))
        assertEquals(null, SafetyPolicy.preflightCommand("현재 시간 12:34를 보여 줘"))
        assertEquals(null, SafetyPolicy.preflightCommand("로그 시각 12:34:56을 보여 줘"))
        assertEquals(null, SafetyPolicy.preflightCommand("Show July 18, 2026 12:34 PM"))
    }

    @Test
    fun combiningMarksCannotSplitSensitiveDigitsOrKeywords() {
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand(
                "4111\u034F1111\u034F1111\u034F1111을 보여 줘",
            )?.decision,
        )
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("p\u034Fi\u034Fn을 입력해 줘")?.decision,
        )
    }

    @Test
    fun spacedFinancialKeyword_isBlockedBeforeModelUse() {
        val assessment = SafetyPolicy.preflightCommand("딸에게 송 금해 줘")

        assertEquals(SafetyDecision.BLOCK, assessment?.decision)
    }

    @Test
    fun punctuatedEightDigitSecret_isBlockedBeforeModelUse() {
        val assessment = SafetyPolicy.preflightCommand("인증 코드는 83.92_01-47이야")

        assertEquals(SafetyDecision.BLOCK, assessment?.decision)
    }

    @Test
    fun modelCannotInjectSixDigitSecretIntoTextField() {
        val field = element(text = null, editable = true)
        val assessment = SafetyPolicy.evaluate(
            command = "여기에 내용을 적어 줘",
            plan = planOf(
                AgentAction(ActionType.SET_TEXT, "내용 입력", field.path, "839201"),
            ),
            snapshot = snapshot(field),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    @Test
    fun modelCannotInjectSeparatedSecretIntoAnyAction() {
        val assessment = SafetyPolicy.evaluate(
            command = "확인 버튼을 눌러 줘",
            plan = planOf(
                AgentAction(ActionType.CLICK, "코드 839.201 확인", "확인"),
            ),
            snapshot = snapshot(element(text = "확인", clickable = true)),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    @Test
    fun zeroWidthFinancialKeyword_isBlockedBeforeModelUse() {
        val assessment = SafetyPolicy.preflightCommand("송\u200B금해 줘")

        assertEquals(SafetyDecision.BLOCK, assessment?.decision)
    }

    @Test
    fun separatedPin_isBlockedWithoutBlockingWordsThatContainThoseLetters() {
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("p i n을 입력해 줘")?.decision,
        )
        assertEquals(null, SafetyPolicy.preflightCommand("shopping 목록을 열어 줘"))
    }

    @Test
    fun explicitNegation_isHandledWithoutCallingOrExecutingAPlanner() {
        val assessment = SafetyPolicy.preflightCommand("카메라 열지 마")

        assertEquals(SafetyDecision.BLOCK, assessment?.decision)
    }

    @Test
    fun descriptiveProblemBeforeARequestIsNotMistakenForActionNegation() {
        listOf(
            "홈 화면 버튼이 안 보여서 홈 화면으로 가 줘",
            "카메라가 안 열려서 설정을 열어 줘",
            "화면이 안 켜져서 설정을 열어 줘",
        ).forEach { command -> assertEquals(command, null, SafetyPolicy.preflightCommand(command)) }
    }

    @Test
    fun directNegativeImperativeStillFailsClosed() {
        listOf("카메라를 안 열어 줘", "홈 화면으로 안 가 줘").forEach { command ->
            assertEquals(command, SafetyDecision.BLOCK, SafetyPolicy.preflightCommand(command)?.decision)
        }
    }

    @Test
    fun conjugatedNegation_isHandledDeterministically() {
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("카메라 열면 안 돼")?.decision,
        )
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("뒤로 가서는 안 돼")?.decision,
        )
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("카메라 열어선 안 돼")?.decision,
        )
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("카메라 켜는 건 안 돼")?.decision,
        )
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("카메라 켜는 것은 안 돼")?.decision,
        )
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("카메라 켜면 절대 안 돼")?.decision,
        )
        assertEquals(
            SafetyDecision.BLOCK,
            SafetyPolicy.preflightCommand("don't open the camera")?.decision,
        )
        assertEquals(null, SafetyPolicy.preflightCommand("우선 안내를 보여 줘"))
    }

    @Test
    fun passwordField_isAlwaysBlocked() {
        val password = element(text = null, editable = true, sensitive = true)
        val assessment = SafetyPolicy.evaluate(
            command = "여기에 글자를 넣어 줘",
            plan = planOf(AgentAction(ActionType.SET_TEXT, "글자 입력", password.path, "1234")),
            snapshot = snapshot(password),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    @Test
    fun duplicatedTarget_failsClosed() {
        val assessment = SafetyPolicy.evaluate(
            command = "확인 버튼 눌러 줘",
            plan = planOf(AgentAction(ActionType.CLICK, "확인 누르기", "확인")),
            snapshot = snapshot(
                element(path = "0.1", text = "확인", clickable = true),
                element(path = "0.2", text = "확인", clickable = true),
            ),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    @Test
    fun genericConfirmationOnFinancialScreen_isBlocked() {
        val assessment = SafetyPolicy.evaluate(
            command = "확인 눌러 줘",
            plan = planOf(AgentAction(ActionType.CLICK, "확인 누르기", "확인")),
            snapshot = UiSnapshot(
                packageName = "com.example.bank",
                windowTitle = "처리 확인",
                epoch = 1,
                elements = listOf(element(text = "확인", clickable = true)),
            ),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    @Test
    fun safeNavigation_isAllowed() {
        val assessment = SafetyPolicy.evaluate(
            command = "뒤로 가 줘",
            plan = planOf(AgentAction(ActionType.BACK, "이전 화면으로 이동")),
            snapshot = snapshot(),
        )

        assertEquals(SafetyDecision.ALLOW, assessment.decision)
    }

    @Test
    fun scrollOnUnknownScreen_isBlocked() {
        val assessment = SafetyPolicy.evaluate(
            command = "아래로 내려 줘",
            plan = planOf(AgentAction(ActionType.SCROLL_DOWN, "화면 아래로 이동")),
            snapshot = UiSnapshot.empty(),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    @Test
    fun appInfoText_doesNotLookLikePin() {
        val scrollable = element(text = "App info").copy(scrollable = true)
        val assessment = SafetyPolicy.evaluate(
            command = "아래로 내려 줘",
            plan = planOf(AgentAction(ActionType.SCROLL_DOWN, "화면 아래로 이동")),
            snapshot = snapshot(scrollable),
        )

        assertEquals(SafetyDecision.ALLOW, assessment.decision)
    }

    @Test
    fun textEntry_isDisabledInThePrototype() {
        val field = element(text = null, editable = true)
        val assessment = SafetyPolicy.evaluate(
            command = "검색창에 복지관이라고 써 줘",
            plan = planOf(
                AgentAction(ActionType.SET_TEXT, "검색어 입력", field.path, "복지관"),
            ),
            snapshot = trustedSettingsSnapshot(field),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    @Test
    fun shortNumericModelValue_isNeverEntered() {
        val field = element(text = null, editable = true)
        val assessment = SafetyPolicy.evaluate(
            command = "검색창에 내용을 적어 줘",
            plan = planOf(AgentAction(ActionType.SET_TEXT, "내용 입력", field.path, "1234")),
            snapshot = trustedSettingsSnapshot(field),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    @Test
    fun unknownPackageCannotReceiveGenericClicks() {
        val assessment = SafetyPolicy.evaluate(
            command = "확인을 눌러 줘",
            plan = planOf(AgentAction(ActionType.CLICK, "확인 누르기", "확인")),
            snapshot = snapshot(element(text = "확인", clickable = true)),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    @Test
    fun englishPurchaseTarget_isBlockedOnTrustedPackage() {
        val assessment = SafetyPolicy.evaluate(
            command = "tap the buy button",
            plan = planOf(AgentAction(ActionType.CLICK, "Buy", "Buy")),
            snapshot = trustedSettingsSnapshot(element(text = "Buy", clickable = true)),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    @Test
    fun checkableClickRequiresDifferentExplicitFinalState() {
        val toggle = element(text = "Wi‑Fi", clickable = true).copy(
            viewId = "android:id/switch_widget",
            checkable = true,
            checked = false,
        )
        val missingState = SafetyPolicy.evaluate(
            command = "와이파이를 켜 줘",
            plan = planOf(AgentAction(ActionType.CLICK, "Wi-Fi 켜기", "Wi-Fi")),
            snapshot = trustedWifiSnapshot(toggle),
        )
        val explicitState = SafetyPolicy.evaluate(
            command = "와이파이를 켜 줘",
            plan = planOf(AgentAction(ActionType.CLICK, "Wi-Fi 켜기", "Wi-Fi", "checked")),
            snapshot = trustedWifiSnapshot(toggle),
        )

        assertEquals(SafetyDecision.BLOCK, missingState.decision)
        assertEquals(SafetyDecision.ALLOW, explicitState.decision)
    }

    @Test
    fun clickableParentUsesItsCheckableChildState() {
        val row = element(path = "0.1", text = null, clickable = true)
        val label = element(path = "0.1.0", text = "Wi-Fi")
        val switch = element(path = "0.1.1", text = null).copy(
            viewId = "android:id/switch_widget",
            checkable = true,
            checked = false,
        )
        val elements = arrayOf(row, label, switch)

        val missingState = SafetyPolicy.evaluate(
            command = "와이파이를 켜 줘",
            plan = planOf(AgentAction(ActionType.CLICK, "Wi-Fi 켜기", "Wi-Fi")),
            snapshot = trustedWifiSnapshot(*elements),
        )
        val turnOn = SafetyPolicy.evaluate(
            command = "와이파이를 켜 줘",
            plan = planOf(AgentAction(ActionType.CLICK, "Wi-Fi 켜기", "Wi-Fi", "checked")),
            snapshot = trustedWifiSnapshot(*elements),
        )
        val alreadyOn = SafetyPolicy.evaluate(
            command = "와이파이를 켜 줘",
            plan = planOf(AgentAction(ActionType.CLICK, "Wi-Fi 켜기", "Wi-Fi", "checked")),
            snapshot = trustedWifiSnapshot(*elements).copy(
                elements = listOf(row, label, switch.copy(checked = true)),
            ),
        )

        assertEquals(SafetyDecision.BLOCK, missingState.decision)
        assertEquals(SafetyDecision.ALLOW, turnOn.decision)
        assertEquals(SafetyDecision.BLOCK, alreadyOn.decision)
    }

    @Test
    fun customToggleStateDescriptionPreventsStateInversion() {
        val customToggle = element(text = "확대", clickable = true).copy(
            viewId = "android:id/switch_widget",
            stateDescription = "켜짐",
        )
        val assessment = SafetyPolicy.evaluate(
            command = "확대를 켜 줘",
            plan = planOf(AgentAction(ActionType.CLICK, "확대 켜기", "확대", "checked")),
            snapshot = trustedSettingsSnapshot(customToggle),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    @Test
    fun desiredToggleStateWithoutReadableStateFailsClosed() {
        val assessment = SafetyPolicy.evaluate(
            command = "와이파이를 켜 줘",
            plan = planOf(AgentAction(ActionType.CLICK, "Wi-Fi 켜기", "Wi-Fi", "checked")),
            snapshot = trustedWifiSnapshot(
                element(text = "Wi-Fi", clickable = true).copy(
                    viewId = "android:id/switch_widget",
                ),
            ),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    @Test
    fun selectedOnlyToggleCannotInvertAnAlreadySelectedState() {
        val toggle = element(text = "Wi-Fi", clickable = true).copy(
            viewId = "android:id/switch_widget",
            selected = true,
        )
        val assessment = SafetyPolicy.evaluate(
            command = "와이파이를 켜 줘",
            plan = planOf(AgentAction(ActionType.CLICK, "Wi-Fi 켜기", "Wi-Fi", "checked")),
            snapshot = trustedWifiSnapshot(toggle),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    @Test
    fun spoofedSettingsTitleCannotAuthorizeAnUnknownVpnToggle() {
        val row = element(path = "0.1", text = "Always-on VPN", clickable = true)
        val toggle = element(path = "0.1.0", text = null).copy(
            viewId = "android:id/switch_widget",
            checkable = true,
            checked = false,
        )
        val assessment = SafetyPolicy.evaluate(
            command = "이걸 켜 줘",
            plan = planOf(
                AgentAction(ActionType.CLICK, "Always-on VPN 켜기", "Always-on VPN", "checked"),
            ),
            snapshot = UiSnapshot(
                packageName = "com.android.settings",
                windowTitle = "Internet",
                epoch = 1,
                elements = listOf(row, toggle),
            ),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    @Test
    fun settingsTitleAndKnownLabelWithoutSonjuRouteCannotAuthorizeClick() {
        val toggle = element(text = "Touch sounds", clickable = true).copy(
            viewId = "android:id/switch_widget",
            checkable = true,
            checked = false,
        )
        val assessment = SafetyPolicy.evaluate(
            command = "터치음을 켜 줘",
            plan = planOf(
                AgentAction(ActionType.CLICK, "Touch sounds 켜기", "Touch sounds", "checked"),
            ),
            snapshot = UiSnapshot(
                packageName = "com.android.settings",
                windowTitle = "Sound",
                epoch = 1,
                elements = listOf(toggle),
            ),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    @Test
    fun wifiMasterSwitchAliasesRemainSupportedOnTheTrustedRoute() {
        listOf("Use Wi-Fi", "Wi-Fi 사용").forEach { label ->
            val toggle = element(text = label, clickable = true).copy(
                viewId = "android:id/switch_widget",
                checkable = true,
                checked = false,
            )
            val assessment = SafetyPolicy.evaluate(
                command = "와이파이를 켜 줘",
                plan = planOf(AgentAction(ActionType.CLICK, "Wi-Fi 켜기", label, "checked")),
                snapshot = trustedWifiSnapshot(toggle),
            )
            assertEquals(label, SafetyDecision.ALLOW, assessment.decision)
        }
    }

    @Test
    fun emulatorDisplayAndTouchSurfaceAllowsOnlyKnownSystemToggle() {
        val toggle = element(text = "Dark theme", clickable = true).copy(
            viewId = "android:id/switch_widget",
            checkable = true,
            checked = false,
        )
        val assessment = SafetyPolicy.evaluate(
            command = "어두운 테마를 켜 줘",
            plan = planOf(AgentAction(ActionType.CLICK, "Dark theme 켜기", "Dark theme", "checked")),
            snapshot = UiSnapshot(
                packageName = "com.android.settings",
                windowTitle = "Display & touch",
                epoch = 1,
                elements = listOf(toggle),
                trustedSettingsRoute = TrustedSettingsRoute.DISPLAY,
            ),
        )

        assertEquals(SafetyDecision.ALLOW, assessment.decision)
    }

    @Test
    fun labelRowAndNestedSwitchWithSameNameResolveToOneToggle() {
        val row = element(path = "0.1", text = null, clickable = true)
        val label = element(path = "0.1.0", text = "Dark theme")
        val switch = element(path = "0.1.1", text = null, clickable = true).copy(
            viewId = "android:id/switch_widget",
            contentDescription = "Dark theme",
            checkable = true,
            checked = false,
        )
        val assessment = SafetyPolicy.evaluate(
            command = "어두운 테마를 켜 줘",
            plan = planOf(AgentAction(ActionType.CLICK, "Dark theme 켜기", "Dark theme", "checked")),
            snapshot = UiSnapshot(
                packageName = "com.android.settings",
                windowTitle = "Display & touch",
                epoch = 1,
                elements = listOf(row, label, switch),
                trustedSettingsRoute = TrustedSettingsRoute.DISPLAY,
            ),
        )

        assertEquals(SafetyDecision.ALLOW, assessment.decision)
    }

    @Test
    fun siblingLabelContainerAndExactNamedSwitchResolveToTheSwitchOnly() {
        val labelContainer = element(path = "0.1.0", text = null, clickable = true)
        val label = element(path = "0.1.0.0", text = "Dark theme")
        val switch = element(path = "0.1.2.0", text = null, clickable = true).copy(
            viewId = "com.android.settings:id/switchWidget",
            contentDescription = "Dark theme",
            checkable = true,
            checked = false,
        )
        val snapshot = UiSnapshot(
            packageName = "com.android.settings",
            windowTitle = "Display & touch",
            epoch = 1,
            elements = listOf(labelContainer, label, switch),
            trustedSettingsRoute = TrustedSettingsRoute.DISPLAY,
        )
        val action = AgentAction(ActionType.CLICK, "Dark theme 켜기", "Dark theme", "checked")

        val validated = SafetyPolicy.validateClick(action, snapshot)

        assertEquals("0.1.2.0", validated?.clickablePath)
        assertEquals("0.1.2.0", validated?.statePath)
    }

    @Test
    fun settingsSecurityAndUnknownSurfacesFailClosed() {
        val ok = element(text = "OK", clickable = true)
        val clearCredentials = SafetyPolicy.evaluate(
            command = "확인을 눌러 줘",
            plan = planOf(AgentAction(ActionType.CLICK, "확인", "OK")),
            snapshot = snapshot(ok).copy(
                packageName = "com.android.settings",
                windowTitle = "Clear credentials",
            ),
        )
        val developerOptions = SafetyPolicy.evaluate(
            command = "이걸 켜 줘",
            plan = planOf(AgentAction(ActionType.CLICK, "USB 디버깅 켜기", "USB 디버깅", "checked")),
            snapshot = snapshot(element(text = "USB 디버깅", clickable = true)).copy(
                packageName = "com.android.settings",
                windowTitle = "개발자 옵션",
            ),
        )

        assertEquals(SafetyDecision.BLOCK, clearCredentials.decision)
        assertEquals(SafetyDecision.BLOCK, developerOptions.decision)
    }

    @Test
    fun multipleScrollableRegionsFailClosed() {
        val assessment = SafetyPolicy.evaluate(
            command = "아래로 내려 줘",
            plan = planOf(AgentAction(ActionType.SCROLL_DOWN, "아래로 이동")),
            snapshot = snapshot(
                element(path = "0.1", text = "첫 목록").copy(scrollable = true),
                element(path = "0.2", text = "둘째 목록").copy(scrollable = true),
            ),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    @Test
    fun nestedScrollableWrappersResolveToTheInnermostRegion() {
        val assessment = SafetyPolicy.evaluate(
            command = "아래로 내려 줘",
            plan = planOf(AgentAction(ActionType.SCROLL_DOWN, "아래로 이동")),
            snapshot = snapshot(
                element(path = "0.1", text = "바깥 목록").copy(scrollable = true),
                element(path = "0.1.0", text = "실제 목록").copy(scrollable = true),
            ),
        )

        assertEquals(SafetyDecision.ALLOW, assessment.decision)
    }

    @Test
    fun truncatedTreeFailsClosed() {
        val scrollable = element(text = "목록").copy(scrollable = true)
        val assessment = SafetyPolicy.evaluate(
            command = "아래로 내려 줘",
            plan = planOf(AgentAction(ActionType.SCROLL_DOWN, "아래로 이동")),
            snapshot = snapshot(scrollable).copy(treeTruncated = true),
        )

        assertEquals(SafetyDecision.BLOCK, assessment.decision)
    }

    private fun planOf(vararg actions: AgentAction) = AgentPlan(
        goal = "test",
        summary = "test",
        modelRisk = RiskLevel.LOW,
        confidence = 0.95,
        actions = actions.toList(),
        source = PlanSource.GEMINI_STRUCTURE,
    )

    private fun snapshot(vararg elements: UiElement) = UiSnapshot(
        packageName = "com.example",
        windowTitle = "테스트",
        epoch = 1,
        elements = elements.toList(),
    )

    private fun trustedSettingsSnapshot(vararg elements: UiElement) = UiSnapshot(
        packageName = "com.android.settings",
        windowTitle = "Accessibility",
        epoch = 1,
        elements = elements.toList(),
        trustedSettingsRoute = TrustedSettingsRoute.ACCESSIBILITY,
    )

    private fun trustedWifiSnapshot(vararg elements: UiElement) = UiSnapshot(
        packageName = "com.android.settings",
        windowTitle = "Wi-Fi",
        epoch = 1,
        elements = elements.toList(),
        trustedSettingsRoute = TrustedSettingsRoute.WIFI,
    )

    private fun element(
        path: String = "0.1",
        text: String? = "입력",
        clickable: Boolean = false,
        editable: Boolean = false,
        sensitive: Boolean = false,
    ) = UiElement(
        path = path,
        viewId = null,
        className = if (editable) "android.widget.EditText" else "android.widget.Button",
        text = text,
        contentDescription = null,
        bounds = ScreenBounds(0, 0, 100, 100),
        clickable = clickable,
        editable = editable,
        scrollable = false,
        enabled = true,
        visible = true,
        sensitive = sensitive,
    )
}
