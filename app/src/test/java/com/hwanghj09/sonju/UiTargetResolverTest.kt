package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.AgentAction
import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import com.hwanghj09.sonju.agent.UiTargetResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiTargetResolverTest {
    @Test
    fun resolvesExactViewIdAndClickableAncestor() {
        val snapshot = snapshot(
            element("0.0", "container", null, clickable = true),
            element("0.0.0", "title", "설정", clickable = false),
        )
        val action = AgentAction(ActionType.CLICK, "설정 열기", "title")

        assertEquals("0.0", UiTargetResolver.resolveClickable(action, snapshot)?.clickablePath)
    }

    @Test
    fun refusesTwoEquallyMatchingClickTargets() {
        val snapshot = snapshot(
            element("0.0", "first", "확인", clickable = true),
            element("0.1", "second", "확인", clickable = true),
        )

        assertNull(
            UiTargetResolver.resolveClickable(
                AgentAction(ActionType.CLICK, "확인", "확인"),
                snapshot,
            ),
        )
    }

    @Test
    fun resolvesEditableFieldByHint() {
        val field = element("0.0", "query", null, clickable = false).copy(
            editable = true,
            hintText = "검색어",
        )

        assertEquals(
            "0.0",
            UiTargetResolver.resolveEditablePath(
                AgentAction(ActionType.SET_TEXT, "입력", "검색어", "서울역"),
                snapshot(field),
            ),
        )
    }

    private fun snapshot(vararg elements: UiElement) = UiSnapshot(
        packageName = "com.example",
        windowTitle = "예시",
        epoch = 1,
        elements = elements.toList(),
    )

    private fun element(
        path: String,
        id: String,
        text: String?,
        clickable: Boolean,
    ) = UiElement(
        path = path,
        viewId = "com.example:id/$id",
        className = "android.widget.TextView",
        text = text,
        contentDescription = null,
        bounds = ScreenBounds(0, 0, 500, 200),
        clickable = clickable,
        editable = false,
        scrollable = false,
        enabled = true,
        visible = true,
        sensitive = false,
    )
}
