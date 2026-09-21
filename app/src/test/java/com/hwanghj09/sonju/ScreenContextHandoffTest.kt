package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.ScreenContextHandoff
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenContextHandoffTest {
    @Test
    fun exactSingleCharacterLabelsSurviveLiveTargetRevalidation() {
        assertTrue(ScreenContextHandoff.labelsCompatible(listOf("1"), listOf("1")))
        assertTrue(ScreenContextHandoff.labelsCompatible(listOf("+"), listOf("+")))
        assertTrue(ScreenContextHandoff.labelsCompatible(listOf("１"), listOf("1")))
        assertFalse(ScreenContextHandoff.labelsCompatible(listOf("1"), listOf("2")))
        assertFalse(ScreenContextHandoff.labelsCompatible(listOf("+"), listOf("-")))
    }

    @Test
    fun captureRetriesWhileTheScreenIsChangingOrStillEmpty() {
        val empty = UiSnapshot.empty(epoch = 10).copy(packageName = "com.sampleapp")
        val oneElement = snapshot(epoch = 10)
        val ready = oneElement.copy(
            elements = List(4) { index -> oneElement.elements.single().copy(path = "0.$index") },
        )

        assertTrue(ScreenContextHandoff.shouldRetryCapture(empty, 10, 10, true, 0, 3))
        assertTrue(ScreenContextHandoff.shouldRetryCapture(ready, 10, 11, true, 0, 3))
        assertFalse(ScreenContextHandoff.shouldRetryCapture(ready, 10, 10, true, 0, 3))
        assertFalse(ScreenContextHandoff.shouldRetryCapture(empty, 10, 10, true, 3, 3))
    }

    @Test
    fun partialTreesAreRecapturedWithinTheExistingBudgetEvenWhenLabelsArePresent() {
        val label = snapshot(epoch = 10).elements.single()
        val ready = snapshot(epoch = 10).copy(
            elements = List(4) { label.copy(path = "0.$it") },
        )
        val partial = ready.copy(treeTruncated = true)
        for (semanticRequired in listOf(true, false)) {
            assertTrue(ScreenContextHandoff.shouldRetryCapture(partial, 10, 10, semanticRequired, 0, 6))
            assertTrue(ScreenContextHandoff.shouldRetryCapture(partial, 10, 10, semanticRequired, 5, 6))
            assertFalse(ScreenContextHandoff.shouldRetryCapture(partial, 10, 10, semanticRequired, 6, 6))
            assertFalse(ScreenContextHandoff.shouldRetryCapture(ready, 10, 10, semanticRequired, 0, 6))
        }
    }

    @Test
    fun nullPlanGetsOnlyTheBoundedLoadingGraceEvenWhenTheShellLooksStable() {
        assertTrue(ScreenContextHandoff.shouldWaitForStableReplan(0, 3))
        assertTrue(ScreenContextHandoff.shouldWaitForStableReplan(1, 3))
        assertTrue(ScreenContextHandoff.shouldWaitForStableReplan(2, 3))
        assertFalse(ScreenContextHandoff.shouldWaitForStableReplan(3, 3))
    }

    @Test
    fun onlyAVisiblePublicLoadingIndicatorQualifiesForExtendedGrace() {
        val progress = element("0.progress", "").copy(
            className = "android.widget.ProgressBar",
        )
        val loading = snapshot(epoch = 10).copy(elements = listOf(progress))

        assertTrue(ScreenContextHandoff.hasVisibleLoadingIndicator(loading))
        assertTrue(ScreenContextHandoff.shouldWaitForStableReplan(3, 5))
        assertFalse(ScreenContextHandoff.shouldWaitForStableReplan(5, 5))
        assertFalse(
            ScreenContextHandoff.hasVisibleLoadingIndicator(
                loading.copy(elements = listOf(progress.copy(visible = false))),
            ),
        )
        assertFalse(
            ScreenContextHandoff.hasVisibleLoadingIndicator(
                loading.copy(elements = listOf(progress.copy(sensitive = true))),
            ),
        )
        assertFalse(ScreenContextHandoff.hasVisibleLoadingIndicator(snapshot(epoch = 10)))
    }

    @Test
    fun reusesOnlyFreshExternalApplicationSnapshots() {
        val snapshot = snapshot(epoch = 10)

        assertTrue(ScreenContextHandoff.isReusable(snapshot, "com.sonju", 2_000, 1_000, 5_000))
        assertFalse(
            ScreenContextHandoff.isReusable(
                snapshot.copy(packageName = "com.sonju"),
                "com.sonju",
                2_000,
                1_000,
                5_000,
            ),
        )
        assertFalse(ScreenContextHandoff.isReusable(snapshot, "com.sonju", 6_001, 1_000, 5_000))
    }

    @Test
    fun planningCacheRequiresARecentMatchingPackageAndWindow() {
        val snapshot = snapshot(epoch = 10)

        assertTrue(
            ScreenContextHandoff.isRecentPlanningSnapshot(
                snapshot, "com.example.target", 7, 2_000, 1_000, 2_000,
            ),
        )
        assertFalse(
            ScreenContextHandoff.isRecentPlanningSnapshot(
                snapshot, "com.other", 7, 2_000, 1_000, 2_000,
            ),
        )
        assertFalse(
            ScreenContextHandoff.isRecentPlanningSnapshot(
                snapshot, "com.example.target", 8, 2_000, 1_000, 2_000,
            ),
        )
        assertTrue(
            ScreenContextHandoff.isRecentPlanningSnapshot(
                snapshot.copy(epoch = 9), "com.example.target", 7, 2_000, 1_000, 2_000,
            ),
        )
        assertFalse(
            ScreenContextHandoff.isRecentPlanningSnapshot(
                snapshot.copy(treeTruncated = true),
                "com.example.target",
                7,
                2_000,
                1_000,
                2_000,
            ),
        )
        assertFalse(
            ScreenContextHandoff.isRecentPlanningSnapshot(
                snapshot, "com.example.target", 7, 3_001, 1_000, 2_000,
            ),
        )
    }

    @Test
    fun aFreshTimestampCannotReuseAnOlderLoadingObservationAfterContentChanges() {
        val before = snapshot(epoch = 10)
        assertTrue(ScreenContextHandoff.isRecentPlanningSnapshot(before, before.packageName, before.windowId,
            1001, 1000, 2000, activeEpoch = 10))
        assertFalse(ScreenContextHandoff.isRecentPlanningSnapshot(before, before.packageName, before.windowId,
            1001, 1000, 2000, activeEpoch = 11))
        val loading = before.copy(elements = before.elements + element("0.progress", "로딩 중"))
        assertFalse(ScreenContextHandoff.isRecentPlanningSnapshot(loading, loading.packageName, loading.windowId,
            1001, 1000, 2000, activeEpoch = 10))
    }

    @Test
    fun resumeAcceptsEpochOnlyTransitionAndRejectsChangedScreen() {
        val expected = snapshot(epoch = 10)
        val returned = expected.copy(epoch = 11)
        val changed = returned.copy(
            elements = returned.elements.map { it.copy(text = "다른 화면") },
        )

        assertEquals(returned, ScreenContextHandoff.resumeOnUnchangedScreen(expected, returned))
        assertNull(ScreenContextHandoff.resumeOnUnchangedScreen(expected, changed))
    }

    @Test
    fun textInputHandoffAllowsOnlyAnUnchangedEditableTarget() {
        val expected = snapshot(epoch = 4).copy(
            elements = listOf(
                element("0.query", "피자", editable = true),
                element("0.suggestion", "피자스쿨", clickable = true),
            ),
        )
        val refreshedSuggestions = expected.copy(
            epoch = 8,
            elements = listOf(
                element("0.query", "피자", editable = true),
                element("0.suggestion", "피자헛", clickable = true),
            ),
        )
        val changedInput = refreshedSuggestions.copy(
            elements = refreshedSuggestions.elements.map { candidate ->
                if (candidate.path == "0.query") candidate.copy(text = "치킨") else candidate
            },
        )

        assertEquals(
            refreshedSuggestions,
            ScreenContextHandoff.resumeForTextInput(expected, refreshedSuggestions, "0.query"),
        )
        assertNull(ScreenContextHandoff.resumeForTextInput(expected, changedInput, "0.query"))
        assertNull(ScreenContextHandoff.resumeForTextInput(expected, refreshedSuggestions, null))
        val entering = expected.copy(elements = expected.elements.map { it.copy(
            bounds = ScreenBounds(180, 0, 480, 100), focused = !it.focused) })
        assertEquals(entering, ScreenContextHandoff.resumeForTextInput(expected, entering, "0.query"))
        assertNull(ScreenContextHandoff.resumeForTextInput(expected,
            entering.copy(elements = entering.elements.map { if (it.editable) it.copy(text = "치킨") else it }), "0.query"))
    }

    @Test
    fun dynamicListClickRebindsOnlyOneSemanticallyIdenticalSurface() {
        val parent = UiElement(
            path = "0.4",
            viewId = null,
            className = "android.view.View",
            text = null,
            contentDescription = null,
            bounds = ScreenBounds(20, 500, 500, 620),
            clickable = true,
            editable = false,
            scrollable = false,
            enabled = true,
            visible = true,
            sensitive = false,
        )
        val label = element("0.4.0", "기본순").copy(
            bounds = ScreenBounds(40, 520, 200, 600),
        )
        val expected = snapshot(epoch = 4).copy(elements = listOf(parent, label))
        val movedParent = parent.copy(path = "0.7")
        val movedLabel = label.copy(path = "0.7.2")
        val live = expected.copy(epoch = 8, elements = listOf(movedParent, movedLabel))

        assertEquals("0.7", ScreenContextHandoff.relocateClickTarget(expected, live, "0.4"))
        assertNull(
            ScreenContextHandoff.relocateClickTarget(
                expected,
                live.copy(
                    elements = live.elements + listOf(
                        movedParent.copy(path = "0.9"),
                        movedLabel.copy(path = "0.9.1"),
                    ),
                ),
                "0.4",
            ),
        )
        assertNull(
            ScreenContextHandoff.relocateClickTarget(
                expected,
                live.copy(elements = listOf(movedParent, movedLabel.copy(text = "평점순"))),
                "0.4",
            ),
        )
    }

    @Test
    fun clickHandoffAllowsUnrelatedDynamicContentWhenExactButtonRemainsUnique() {
        val button = UiElement(
            path = "0.route",
            viewId = null,
            className = "android.widget.Button",
            text = null,
            contentDescription = "길찾기",
            bounds = ScreenBounds(255, 1729, 489, 1840),
            clickable = true,
            editable = false,
            scrollable = false,
            enabled = true,
            visible = true,
            sensitive = false,
        )
        val expected = snapshot(epoch = 4).copy(elements = listOf(button))
        val live = snapshot(epoch = 9).copy(
            elements = listOf(
                button,
                button.copy(
                    path = "0.updated-map-label",
                    className = "android.widget.TextView",
                    contentDescription = "지도 정보 갱신",
                    bounds = ScreenBounds(0, 0, 100, 100),
                    clickable = false,
                ),
            ),
        )

        assertEquals(live, ScreenContextHandoff.resumeForClick(expected, live, button.path))
    }

    @Test
    fun enteringPageAnimationCanMoveAUniqueButtonButChangedValuesCannot() {
        val button = element("0.1", "글자 크기와 스타일", clickable = true)
            .copy(bounds = ScreenBounds(198, 2572, 1218, 2640), viewId = "android:id/row")
        val amount = element("0.2", "금액 1000원")
        val expected = snapshot(1).copy(elements = listOf(button, amount))
        val live = expected.copy(epoch = 2, elements = listOf(
            button.copy(bounds = ScreenBounds(30, 2572, 1050, 2640), focused = true), amount))
        assertEquals(button.path, ScreenContextHandoff.relocateClickTarget(expected, live, button.path))
        assertNull(ScreenContextHandoff.relocateClickTarget(expected,
            live.copy(elements = live.elements.map { if (it.path == amount.path) it.copy(text = "금액 9000원") else it }),
            button.path))
        assertNull(ScreenContextHandoff.relocateClickTarget(expected,
            live.copy(elements = listOf(button.copy(text = "다른 메뉴"), amount)), button.path))
    }

    @Test
    fun unlabeledSwitchMovesOnlyWhenItsUniqueResourceAndWholeScreenStateStayTheSame() {
        val toggle = element("0.1.1", "", clickable = true).copy(
            viewId = "android:id/switch_widget", className = "android.widget.Switch",
            checkable = true, checked = false, bounds = ScreenBounds(35, 1257, 122, 1311))
        val label = element("0.1.0", "글자 굵게")
        val expected = snapshot(1).copy(elements = listOf(toggle, label))
        val movedToggle = toggle.copy(bounds = ScreenBounds(888, 1250, 984, 1310))
        val live = expected.copy(epoch = 2, elements = listOf(movedToggle, label))
        assertEquals(toggle.path, ScreenContextHandoff.relocateClickTarget(expected, live, toggle.path))
        assertNull(ScreenContextHandoff.relocateClickTarget(expected,
            live.copy(elements = listOf(movedToggle, label.copy(text = "다른 설정"))), toggle.path))
        assertNull(ScreenContextHandoff.relocateClickTarget(expected,
            live.copy(elements = listOf(movedToggle.copy(checked = true), label)), toggle.path))
        assertNull(ScreenContextHandoff.relocateClickTarget(expected,
            live.copy(elements = live.elements + movedToggle.copy(path = "0.2.1")), toggle.path))
        assertNull(ScreenContextHandoff.relocateClickTarget(expected,
            expected.copy(elements = listOf(toggle.copy(checked = true), label)), toggle.path))
    }

    @Test
    fun scrollHandoffAllowsContentChangesInsideTheSameContainer() {
        val container = element("0.list", "목록").copy(
            scrollable = true,
            bounds = ScreenBounds(0, 100, 1_080, 2_300),
        )
        val expected = snapshot(epoch = 10).copy(
            elements = listOf(container, element("0.list.0", "첫 항목")),
        )
        val live = expected.copy(
            epoch = 11,
            elements = listOf(container, element("0.list.0", "늦게 로드된 항목")),
        )

        assertEquals(live, ScreenContextHandoff.resumeForScroll(expected, live, container.path))
        assertFalse(ScreenContextHandoff.hasObservedScrollEffect(expected, live, container.path))
        val moved = live.copy(
            elements = live.elements.map { element ->
                if (element.path == "0.list.0") {
                    element.copy(bounds = ScreenBounds(0, 300, 300, 400))
                } else {
                    element
                }
            },
        )
        assertTrue(ScreenContextHandoff.hasObservedScrollEffect(expected, moved, container.path))
        assertNull(
            ScreenContextHandoff.resumeForScroll(
                expected,
                live.copy(elements = listOf(container.copy(scrollable = false))),
                container.path,
            ),
        )
    }

    private fun snapshot(epoch: Long) = UiSnapshot(
        packageName = "com.example.target",
        windowTitle = "Target",
        epoch = epoch,
        windowId = 7,
        elements = listOf(
            UiElement(
                path = "0",
                viewId = "com.example.target:id/title",
                className = "android.widget.TextView",
                text = "현재 화면",
                contentDescription = null,
                bounds = ScreenBounds(0, 0, 300, 100),
                clickable = false,
                editable = false,
                scrollable = false,
                enabled = true,
                visible = true,
                sensitive = false,
            ),
        ),
    )

    private fun element(
        path: String,
        text: String,
        clickable: Boolean = false,
        editable: Boolean = false,
    ) = UiElement(
        path = path,
        viewId = if (editable) "com.example.target:id/query" else null,
        className = if (editable) "android.widget.EditText" else "android.widget.TextView",
        text = text,
        contentDescription = null,
        bounds = ScreenBounds(0, 0, 300, 100),
        clickable = clickable,
        editable = editable,
        scrollable = false,
        enabled = true,
        visible = true,
        sensitive = false,
    )
}
