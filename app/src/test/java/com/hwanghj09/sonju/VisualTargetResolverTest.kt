package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import com.hwanghj09.sonju.agent.VisualTargetResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualTargetResolverTest {
    @Test
    fun normalizedCoordinateResolvesSmallestClickableNode() {
        val snapshot = UiSnapshot(
            packageName = "com.example",
            windowTitle = "Example",
            epoch = 1L,
            elements = listOf(
                element("0", ScreenBounds(0, 0, 1000, 2000)),
                element("0.1", ScreenBounds(300, 700, 700, 900)),
            ),
            treeTruncated = true,
            visualFingerprint = "0123456789abcdef",
            visualScreenWidth = 1000,
            visualScreenHeight = 2000,
        )

        assertEquals("0.1", VisualTargetResolver.resolveClickablePath(snapshot, 500, 400))
    }

    @Test
    fun coordinateWithoutExposedClickableNodeFailsClosed() {
        val snapshot = UiSnapshot.empty().copy(
            visualScreenWidth = 1000,
            visualScreenHeight = 2000,
        )

        assertNull(VisualTargetResolver.resolveClickablePath(snapshot, 500, 500))
        assertNull(VisualTargetResolver.resolveClickablePath(snapshot, -1, 500))
        assertEquals(Pair(500, 1000), VisualTargetResolver.resolveScreenPoint(snapshot, 500, 500))
        assertNull(VisualTargetResolver.resolveScreenPoint(snapshot, -1, 500))
    }

    @Test
    fun perceptualFingerprintDistanceCountsChangedBits() {
        assertEquals(0, VisualTargetResolver.fingerprintDistance("0f", "0f"))
        assertEquals(8, VisualTargetResolver.fingerprintDistance("00", "ff"))
        assertTrue(VisualTargetResolver.fingerprintDistance("0", "00") == null)
    }

    private fun element(path: String, bounds: ScreenBounds) = UiElement(
        path = path,
        viewId = null,
        className = "android.widget.Button",
        text = "버튼",
        contentDescription = null,
        bounds = bounds,
        clickable = true,
        editable = false,
        scrollable = false,
        enabled = true,
        visible = true,
        sensitive = false,
    )
}
