package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.ActionType
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
}
