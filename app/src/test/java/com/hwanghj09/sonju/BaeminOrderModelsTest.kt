package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import com.hwanghj09.sonju.shopping.BaeminNavigator
import com.hwanghj09.sonju.shopping.BaeminOrderRequestParser
import com.hwanghj09.sonju.shopping.BaeminScreenAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaeminOrderModelsTest {
    @Test
    fun parsesBaeminOrderQuery() {
        assertEquals("피자", BaeminOrderRequestParser.parse("배민 들어가서 피자 시켜줘")?.query)
        assertEquals("치즈 피자", BaeminOrderRequestParser.parse("배달의민족에서 치즈 피자 주문해 줘")?.query)
    }

    @Test
    fun ignoresNonBaeminOrNonOrderCommands() {
        assertNull(BaeminOrderRequestParser.parse("피자 시켜줘"))
        assertNull(BaeminOrderRequestParser.parse("배민 열어 줘"))
    }

    @Test
    fun opensSearchBeforeChoosingHomeRecommendation() {
        val snapshot = snapshot(
            element("0.0", text = "검색", clickable = true, top = 10),
            element("0.1", text = "피자", clickable = true, top = 100),
        )
        val action = BaeminNavigator.next(snapshot, "피자", 0)
        assertTrue(action is BaeminScreenAction.Click && action.label == "검색")
    }

    @Test
    fun fillsOnlySingleSearchField() {
        val action = BaeminNavigator.next(
            snapshot(element("0.0", text = "", editable = true)),
            "피자",
            1,
        )
        assertEquals(BaeminScreenAction.SetSearchText("0.0", "피자"), action)
    }

    @Test
    fun stopsForCredentialScreen() {
        val action = BaeminNavigator.next(
            snapshot(element("0.0", text = "결제 비밀번호")),
            "피자",
            5,
        )
        assertTrue(action is BaeminScreenAction.Stop)
    }

    @Test
    fun requiresReviewBeforeOrderButton() {
        val action = BaeminNavigator.next(
            snapshot(element("0.0", text = "주문하기", clickable = true)),
            "피자",
            6,
        )
        assertTrue(action is BaeminScreenAction.Click && action.finalCommit)
    }

    @Test
    fun recognizesOrderButtonWithDisplayedAmount() {
        val action = BaeminNavigator.next(
            snapshot(element("0.0", text = "주문하기 25,000원", clickable = true)),
            "피자",
            6,
        )
        assertTrue(action is BaeminScreenAction.Click && action.finalCommit)
    }

    private fun snapshot(vararg elements: UiElement) = UiSnapshot(
        packageName = BaeminNavigator.PACKAGE_NAME,
        windowTitle = "배달의민족",
        epoch = 1L,
        elements = elements.toList(),
    )

    private fun element(
        path: String,
        text: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        top: Int = 0,
    ) = UiElement(
        path = path,
        viewId = null,
        className = "android.view.View",
        text = text,
        contentDescription = null,
        bounds = ScreenBounds(0, top, 500, top + 80),
        clickable = clickable,
        editable = editable,
        scrollable = false,
        enabled = true,
        visible = true,
        sensitive = false,
    )
}
