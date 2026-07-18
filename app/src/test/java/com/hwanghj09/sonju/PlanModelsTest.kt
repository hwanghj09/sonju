package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.TrustedSettingsRoute
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanModelsTest {
    @Test
    fun fingerprintIncludesTrustedSettingsRouteProof() {
        val raw = snapshotWith(lastText = "Wi-Fi")
        val trusted = raw.copy(trustedSettingsRoute = TrustedSettingsRoute.WIFI)

        assertNotEquals(raw.screenFingerprint(), trusted.screenFingerprint())
        assertTrue(raw.hasSameObservableContentAs(trusted))
        assertFalse(raw.hasSameRevisionAs(trusted))
    }

    @Test
    fun fingerprintIncludesElementsBeyondFirstHundred() {
        val original = snapshotWith(lastText = "확인")
        val changed = snapshotWith(lastText = "취소")

        assertNotEquals(original.screenFingerprint(), changed.screenFingerprint())
    }

    @Test
    fun fingerprintChangesWhenSensitiveNodeAppearsWithoutHashingItsValue() {
        val original = snapshotWith(lastText = "확인")
        val sensitive = original.copy(
            elements = original.elements + UiElement(
                path = "0.otp",
                viewId = "com.example:id/otp",
                className = "android.widget.EditText",
                text = null,
                contentDescription = null,
                bounds = ScreenBounds(0, 0, 100, 100),
                clickable = false,
                editable = true,
                scrollable = false,
                enabled = true,
                visible = true,
                sensitive = true,
            ),
        )

        assertNotEquals(original.screenFingerprint(), sensitive.screenFingerprint())
    }

    @Test
    fun fingerprintChangesWhenToggleStateChanges() {
        val original = snapshotWith(lastText = "Wi-Fi")
        val toggled = original.copy(
            elements = original.elements.mapIndexed { index, element ->
                if (index == 139) element.copy(checkable = true, checked = true) else element
            },
        )

        assertNotEquals(original.screenFingerprint(), toggled.screenFingerprint())
    }

    @Test
    fun revisionComparisonRejectsAbaAndSemanticScreenChange() {
        val expected = snapshotWith(lastText = "확인").copy(windowId = 7, epoch = 10)
        val exactRevision = expected.copy()
        val sameContentAfterTargetAppEvents = expected.copy(epoch = 99)
        val changedScreen = snapshotWith(lastText = "삭제").copy(windowId = 7, epoch = 99)
        val otherWindow = expected.copy(windowId = 8, epoch = 99)

        assertTrue(expected.hasSameRevisionAs(exactRevision))
        assertFalse(expected.hasSameRevisionAs(sameContentAfterTargetAppEvents))
        assertFalse(expected.hasSameRevisionAs(changedScreen))
        assertFalse(expected.hasSameRevisionAs(otherWindow))
    }

    @Test
    fun compactModelContextOmitsSensitiveNodeGeometryAndPath() {
        val snapshot = snapshotWith(lastText = "확인").copy(
            elements = listOf(
                UiElement(
                    path = "0.secret.3",
                    viewId = "com.example:id/otp_slot_3",
                    className = "android.widget.EditText",
                    text = null,
                    contentDescription = null,
                    bounds = ScreenBounds(333, 444, 555, 666),
                    clickable = true,
                    editable = true,
                    scrollable = false,
                    enabled = true,
                    visible = true,
                    sensitive = true,
                ),
            ),
        )

        val context = snapshot.compactText()

        assertFalse(context.contains("0.secret.3"))
        assertFalse(context.contains("333,444,555,666"))
        assertFalse(context.contains("otp_slot_3"))
    }

    @Test
    fun compactModelContextIncludesStateOnlyCheckableNode() {
        val toggle = snapshotWith(lastText = "Wi-Fi").copy(
            elements = listOf(
                UiElement(
                    path = "0.1.1",
                    viewId = "com.android.settings:id/switchWidget",
                    className = "android.widget.Switch",
                    text = null,
                    contentDescription = null,
                    bounds = ScreenBounds(900, 700, 1040, 850),
                    clickable = false,
                    editable = false,
                    scrollable = false,
                    enabled = true,
                    visible = true,
                    sensitive = false,
                    checkable = true,
                    checked = true,
                ),
            ),
        )

        val context = toggle.compactText()

        assertTrue(context.contains("switchWidget"))
        assertTrue(context.contains("checkable checked=true"))
    }

    private fun snapshotWith(lastText: String): UiSnapshot = UiSnapshot(
        packageName = "com.example",
        windowTitle = "테스트",
        epoch = 1,
        elements = (0 until 140).map { index ->
            UiElement(
                path = "0.$index",
                viewId = "com.example:id/item_$index",
                className = "android.widget.TextView",
                text = if (index == 139) lastText else "항목 $index",
                contentDescription = null,
                bounds = ScreenBounds(0, index * 10, 100, index * 10 + 10),
                clickable = index == 139,
                editable = false,
                scrollable = false,
                enabled = true,
                visible = true,
                sensitive = false,
            )
        },
    )
}
