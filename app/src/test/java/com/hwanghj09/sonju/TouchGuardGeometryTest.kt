package com.hwanghj09.sonju

import com.hwanghj09.sonju.accessibility.TouchGuardGeometry
import com.hwanghj09.sonju.agent.ScreenBounds
import org.junit.Assert.*
import org.junit.Test

class TouchGuardGeometryTest {
    @Test fun everyPixelOutsideTheOpeningIsBlockedExactlyOnceIncludingEdgesAndInsets() {
        val frame = ScreenBounds(7, 11, 87, 131)
        for ((x, y) in listOf(7 to 11, 86 to 130, 7 to 80, 45 to 70, 86 to 11)) {
            val layout = requireNotNull(TouchGuardGeometry.aroundPoint(frame, x, y, 2))
            assertTrue(layout.opening.contains(x, y))
            assertTrue(layout.blockers.size <= 4)
            assertTrue((layout.opening.right - layout.opening.left) *
                (layout.opening.bottom - layout.opening.top) <= 25)
            for (px in frame.left until frame.right) for (py in frame.top until frame.bottom) {
                assertEquals("Coverage at $px,$py around $x,$y", 1,
                    layout.blockers.count { it.contains(px, py) } + if (layout.opening.contains(px, py)) 1 else 0)
            }
        }
    }

    @Test fun swipeEndAndUnrelatedControlsRemainCovered() {
        val layout = requireNotNull(TouchGuardGeometry.aroundPoint(ScreenBounds(0, 0, 1080, 2400), 540, 1800, 6))
        assertTrue(layout.opening.contains(540, 1800))
        assertTrue(layout.blockers.any { it.contains(540, 500) })
        assertTrue(layout.blockers.any { it.contains(900, 1800) })
    }

    @Test fun invalidOrOffscreenOpeningNeverProducesAPassthroughLayout() {
        val frame = ScreenBounds(0, 94, 1080, 2400)
        assertNull(TouchGuardGeometry.aroundPoint(frame, 500, 93, 2))
        assertNull(TouchGuardGeometry.aroundPoint(frame, 1080, 500, 2))
        assertNull(TouchGuardGeometry.aroundPoint(frame, 500, 500, 0))
        assertNull(TouchGuardGeometry.aroundPoint(frame, 500, 500, 33))
        assertNull(TouchGuardGeometry.aroundPoint(ScreenBounds(0, 0, 0, 0), 0, 0, 2))
    }

    private fun ScreenBounds.contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
}
