package com.hwanghj09.sonju.ai

import com.hwanghj09.sonju.agent.*
import org.junit.Assert.*
import org.junit.Test

class PlannerObservationTest {
    @Test fun aLongControlListDoesNotHideTheRequestedResultBody() {
        val controls = (0..150).map { node("0.$it", "항목 $it", true) }
        val result = node("0.result", "내일 예보 서울 맑음 최저 19도 최고 27도", false)
        val snapshot = screen(controls + result)
        assertFalse(snapshot.compactText().contains("최저 19도"))
        val observed = PlannerObservation.render(snapshot, "내일 날씨 알려줘", maxElements = 40)
        assertTrue(observed.contains("node=151"))
        assertTrue(observed.contains("최저 19도 최고 27도"))
        assertTrue(observed.contains("omitted=112"))
        assertEquals(40, observed.lines().count { it.startsWith("node=") })
    }

    @Test fun relevantControlsAndTheirDescendantLabelsKeepTheirRealPaths() {
        val target = node("0.200", "알림 기록", true)
        val parent = node("0.201", null, true)
        val child = node("0.201.0", "새로운 기능", false)
        val observed = PlannerObservation.render(screen((0..100).map { node("0.$it", "항목 $it", true) } +
            listOf(target, parent, child)), "알림 기록 열어줘", maxElements = 25)
        assertTrue(observed.contains("node=101"))
        val small = PlannerObservation.render(screen(listOf(parent, child)), "새로운 기능 열어줘")
        assertTrue(small.contains("descendant_labels"))
        assertTrue(small.contains("node=1: 새로운 기능"))
    }

    @Test fun compactReferencesCannotResolveUnobservedOrSensitiveNodes() {
        val deep = node("0" + ".0".repeat(28), "결과", false)
        val snapshot = screen(listOf(deep, node("0.secret", "private", false).copy(sensitive = true),
            node("0.hidden", "hidden", false).copy(visible = false)))
        assertEquals(deep.path, PlannerObservation.resolveSelector("node=0", snapshot))
        for (invalid in listOf("node=1", "node=2", "node=3", "node=-1", "node=9999999")) {
            assertEquals(invalid, PlannerObservation.resolveSelector(invalid, snapshot))
        }
        assertEquals("node=0", PlannerObservation.resolveSelector("node=0", null))
        assertFalse(PlannerObservation.render(snapshot, "결과 알려줘").contains(deep.path))
    }

    @Test fun privateHiddenAndLocalOcrContentsNeverEnterThePrompt() {
        val private = node("0.secret", "private-secret", false).copy(sensitive = true)
        val hidden = node("0.hidden", "hidden-secret", false).copy(visible = false)
        val result = PlannerObservation.render(screen(listOf(node("0", null, true), private, hidden))
            .copy(localReadOnlyText = listOf("local-only-secret")), "secret", 1)
        assertFalse(result.contains("private-secret"))
        assertFalse(result.contains("hidden-secret"))
        assertFalse(result.contains("local-only-secret"))
    }

    private fun screen(nodes: List<UiElement>) = UiSnapshot("org.example.app", "App", epoch = 1, elements = nodes)
    private fun node(path: String, text: String?, clickable: Boolean) = UiElement(path, null,
        "android.widget.TextView", text, null, ScreenBounds(0, 0, 400, 100), clickable,
        editable = false, scrollable = false, enabled = true, visible = true, sensitive = false)
}
