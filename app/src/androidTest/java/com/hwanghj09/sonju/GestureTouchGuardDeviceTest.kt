package com.hwanghj09.sonju

import android.app.UiAutomation
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hwanghj09.sonju.accessibility.GestureTouchGuard
import com.hwanghj09.sonju.accessibility.ScreenControlGlowView
import com.hwanghj09.sonju.accessibility.SonjuAccessibilityService
import com.hwanghj09.sonju.accessibility.TouchGuardTestActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class GestureTouchGuardDeviceTest {
    @Test fun windowScreenshotsExcludeTheAnimatedShieldWhilePhysicalTouchesRemainBlocked() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            TouchGuardTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TouchGuardTestActivity
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SonjuAccessibilityService.instance == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        val service = requireNotNull(SonjuAccessibilityService.instance)
        val type = service.javaClass
        val frames = mutableListOf<android.graphics.Bitmap>()
        try {
            instrumentation.runOnMainSync {
                assertEquals(true, type.getDeclaredMethod("beginCommandControl").apply { isAccessible = true }.invoke(service))
            }
            SystemClock.sleep(500)
            repeat(2) {
                val completed = CountDownLatch(1)
                val captured = java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap?>()
                instrumentation.runOnMainSync {
                    val revision = (type.getDeclaredField("epoch").apply { isAccessible = true }.get(service)
                        as java.util.concurrent.atomic.AtomicLong).get()
                    val snapshot = com.hwanghj09.sonju.accessibility.UiTreeReader.snapshot(ui.rootInActiveWindow, revision)
                    val callback: (android.graphics.Bitmap?) -> Unit = { bitmap -> captured.set(bitmap); completed.countDown() }
                    type.declaredMethods.single { it.name == "captureScreenshotBitmap" }.apply { isAccessible = true }
                        .invoke(service, snapshot, callback)
                }
                assertTrue(completed.await(5, TimeUnit.SECONDS))
                frames += requireNotNull(captured.get()) { "Actual window screenshot must be available" }
                SystemClock.sleep(500)
            }
            assertTrue("The animated overlay must not change the captured app pixels", frames[0].sameAs(frames[1]))
            assertEquals(android.graphics.Color.WHITE, frames[0].getPixel(frames[0].width / 2, frames[0].height / 2))
            tap(ui, frames[0].width / 2f, frames[0].height / 2f)
            assertEquals("Capture must not open a gap in touch blocking", 0, activity.appDowns.get())
            instrumentation.runOnMainSync {
                val shield = type.getDeclaredField("controlGlow").apply { isAccessible = true }.get(service) as View
                assertTrue(shield.isShown)
                assertEquals(0, (shield.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            }
        } finally {
            frames.forEach { it.recycle() }
            instrumentation.runOnMainSync { service.stopCurrentExecution(); activity.finish() }
        }
    }

    @Test fun realAccessibilitySwipeRetainsTheAuroraAndRestoresFullBlocking() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            TouchGuardTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TouchGuardTestActivity
        var service: SonjuAccessibilityService? = null
        try {
            val bindDeadline = SystemClock.uptimeMillis() + 30_000
            while (service == null && SystemClock.uptimeMillis() < bindDeadline) {
                service = SonjuAccessibilityService.instance
                if (service == null) SystemClock.sleep(100)
            }
            val connected = requireNotNull(service) { "Rebind the already-enabled Sonju service after instrumentation starts" }
            val type = SonjuAccessibilityService::class.java
            lateinit var shield: View
            lateinit var panel: View
            instrumentation.runOnMainSync {
                type.getDeclaredMethod("showVoicePanel", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(connected, false)
                panel = type.getDeclaredField("voicePanel").apply { isAccessible = true }.get(connected) as View
                assertEquals(true, type.getDeclaredMethod("beginCommandControl").apply { isAccessible = true }.invoke(connected))
                shield = type.getDeclaredField("controlGlow").apply { isAccessible = true }.get(connected) as View
            }
            val layoutDeadline = SystemClock.uptimeMillis() + 2_000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < layoutDeadline) {
                instrumentation.runOnMainSync { ready = shield.width > 0 && shield.height > 0 && panel.width > 0 && panel.height > 0 }
                if (!ready) SystemClock.sleep(20)
            }
            assertTrue(ready)
            val completed = CountDownLatch(1)
            val succeeded = AtomicBoolean()
            instrumentation.runOnMainSync {
                val location = IntArray(2)
                shield.getLocationOnScreen(location)
                val panelLocation = IntArray(2)
                panel.getLocationOnScreen(panelLocation)
                val x = panelLocation[0] + panel.width * .5f
                val y = panelLocation[1] + panel.height * .5f
                val path = Path().apply { moveTo(x, y); lineTo(x, location[1] + shield.height * .25f) }
                val gesture = GestureDescription.Builder().addStroke(
                    GestureDescription.StrokeDescription(path, 0, 320),
                ).build()
                val callback: (Boolean) -> Unit = { succeeded.set(it); completed.countDown() }
                type.declaredMethods.single { it.name == "dispatchGuardedGesture" }.apply { isAccessible = true }
                    .invoke(connected, gesture, x, y, callback)
            }
            assertTrue("Accessibility dispatch must finish", completed.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertTrue("The accessibility-generated gesture must reach the app", succeeded.get())
            assertEquals(1, activity.appDowns.get())
            assertTrue(activity.appMoves.get() > 0)
            assertEquals(1, activity.appUps.get())
            instrumentation.runOnMainSync {
                assertTrue(shield.isShown)
                assertNull(type.getDeclaredField("gestureTouchGuard").apply { isAccessible = true }.get(connected))
                assertEquals(0, (shield.layoutParams as WindowManager.LayoutParams).flags and
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            }
        } finally {
            instrumentation.runOnMainSync {
                service?.stopCurrentExecution()
                service?.javaClass?.getDeclaredMethod("dismissVoicePanel", Boolean::class.javaPrimitiveType)
                    ?.apply { isAccessible = true }?.invoke(service, false)
                activity.finish()
            }
        }
    }

    @Test fun surroundingTouchesStayBlockedWhileASwipeLeavesTheTinyOpening() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            TouchGuardTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TouchGuardTestActivity
        lateinit var shield: View
        lateinit var manager: WindowManager
        var guard: GestureTouchGuard? = null
        val blocked = AtomicInteger()
        try {
            instrumentation.runOnMainSync {
                manager = activity.windowManager
                shield = ScreenControlGlowView(activity).apply {
                    setOnTouchListener { _, _ -> true }
                }
                manager.addView(shield, WindowManager.LayoutParams(
                    -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply { token = activity.window.decorView.windowToken; gravity = Gravity.TOP or Gravity.START })
            }
            instrumentation.waitForIdleSync()
            val location = IntArray(2)
            var x = 0f
            var startY = 0f
            var endY = 0f
            var outsideX = 0f
            instrumentation.runOnMainSync {
                shield.getLocationOnScreen(location)
                x = location[0] + shield.width * .5f
                startY = location[1] + shield.height * .75f
                endY = location[1] + shield.height * .25f
                outsideX = location[0] + shield.width * .2f
            }
            tap(ui, x, startY)
            assertEquals(0, activity.appDowns.get())
            instrumentation.runOnMainSync {
                guard = GestureTouchGuard.create(shield, manager, x.toInt(), startY.toInt(), 6) {
                    blocked.incrementAndGet()
                }
                assertNotNull(guard)
            }
            instrumentation.waitForIdleSync()
            val layoutDeadline = SystemClock.uptimeMillis() + 2_000
            var laidOut = false
            while (!laidOut && SystemClock.uptimeMillis() < layoutDeadline) {
                instrumentation.runOnMainSync { laidOut = guard!!.hasExpectedCoverage() }
                if (!laidOut) SystemClock.sleep(20)
            }
            val opened = CountDownLatch(1)
            instrumentation.runOnMainSync {
                assertTrue("Input shield windows: ${guard!!.coverageDescription()}", guard!!.hasExpectedCoverage())
                assertTrue(guard!!.open { opened.countDown() })
            }
            assertTrue(opened.await(3, TimeUnit.SECONDS))
            tap(ui, outsideX, endY)
            assertTrue("The surrounding shield must consume touches", blocked.get() > 0)
            assertEquals(0, activity.appDowns.get())
            val downTime = SystemClock.uptimeMillis()
            inject(ui, downTime, MotionEvent.ACTION_DOWN, x, startY)
            repeat(8) { index ->
                inject(ui, downTime, MotionEvent.ACTION_MOVE, x, startY + (endY - startY) * (index + 1) / 8)
            }
            inject(ui, downTime, MotionEvent.ACTION_UP, x, endY)
            instrumentation.waitForIdleSync()
            assertEquals(1, activity.appDowns.get())
            assertTrue("The original app must retain the swipe outside the opening", activity.appMoves.get() > 0)
            assertEquals(1, activity.appUps.get())
            val closed = CountDownLatch(1)
            instrumentation.runOnMainSync { assertTrue(guard!!.close { closed.countDown() }) }
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            tap(ui, x, startY)
            assertEquals("The old opening must be blocked again", 1, activity.appDowns.get())
        } finally {
            instrumentation.runOnMainSync {
                guard?.dispose()
                runCatching { manager.removeView(shield) }
                activity.finish()
            }
        }
    }

    private fun tap(ui: UiAutomation, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        inject(ui, downTime, MotionEvent.ACTION_DOWN, x, y)
        inject(ui, downTime, MotionEvent.ACTION_UP, x, y)
    }

    private fun inject(ui: UiAutomation, downTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try { assertTrue(ui.injectInputEvent(event, true)) } finally { event.recycle() }
    }
}
