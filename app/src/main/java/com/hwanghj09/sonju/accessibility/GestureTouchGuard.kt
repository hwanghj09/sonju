package com.hwanghj09.sonju.accessibility

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.view.doOnPreDraw
import com.hwanghj09.sonju.agent.ScreenBounds

/** Only the initial DOWN needs a hole: Android retains that window for the rest of the gesture. */
internal object TouchGuardGeometry {
    data class Layout(val opening: ScreenBounds, val blockers: List<ScreenBounds>)

    fun aroundPoint(frame: ScreenBounds, x: Int, y: Int, radius: Int): Layout? {
        if (frame.right <= frame.left || frame.bottom <= frame.top || radius !in 1..32 ||
            x < frame.left || x >= frame.right || y < frame.top || y >= frame.bottom) return null
        val hole = ScreenBounds(
            maxOf(frame.left.toLong(), x.toLong() - radius).toInt(),
            maxOf(frame.top.toLong(), y.toLong() - radius).toInt(),
            minOf(frame.right.toLong(), x.toLong() + radius + 1).toInt(),
            minOf(frame.bottom.toLong(), y.toLong() + radius + 1).toInt(),
        )
        val blockers = listOf(
            ScreenBounds(frame.left, frame.top, frame.right, hole.top),
            ScreenBounds(frame.left, hole.bottom, frame.right, frame.bottom),
            ScreenBounds(frame.left, hole.top, hole.left, hole.bottom),
            ScreenBounds(hole.right, hole.top, frame.right, hole.bottom),
        ).filter { it.right > it.left && it.bottom > it.top }
        return Layout(hole, blockers)
    }
}

/** Opaque to input, transparent to sight; the original aurora remains attached throughout. */
internal class GestureTouchGuard private constructor(
    private val shield: View,
    private val manager: WindowManager,
    private val frame: ScreenBounds,
    private val windows: List<Pair<View, ScreenBounds>>,
) {
    var disposed = false
        private set
    var onDispose: (() -> Unit)? = null

    fun hasExpectedCoverage(): Boolean = !disposed && boundsOf(shield) == frame &&
        windows.all { (view, bounds) -> view.isAttachedToWindow && boundsOf(view) == bounds }

    internal fun coverageDescription(): String = "shield=$frame actual=${boundsOf(shield)}; " +
        windows.joinToString { (view, expected) -> "expected=$expected actual=${boundsOf(view)}" }

    fun open(onApplied: () -> Unit) = setPassthrough(true, onApplied)

    fun close(onClosed: () -> Unit) = setPassthrough(false) {
        dispose()
        onClosed()
    }

    private fun setPassthrough(enabled: Boolean, onApplied: () -> Unit): Boolean {
        if (disposed || !shield.isAttachedToWindow) return false
        return runCatching {
            val params = shield.layoutParams as WindowManager.LayoutParams
            params.flags = if (enabled) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            manager.updateViewLayout(shield, params)
            // InputDispatcher receives the window change after traversal. Keep the surrounding
            // blockers until that update settles, both before injection and when closing the hole.
            shield.doOnPreDraw {
                shield.postDelayed({ if (!disposed && shield.isAttachedToWindow) onApplied() }, 120L)
            }
            shield.invalidate()
        }.isSuccess
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        windows.forEach { (view, _) -> runCatching { manager.removeView(view) } }
        onDispose?.invoke()
        onDispose = null
    }

    companion object {
        private fun boundsOf(view: View): ScreenBounds {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return ScreenBounds(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }

        @SuppressLint("ClickableViewAccessibility", "RtlHardcoded") // Input shields use absolute screen pixels.
        fun create(shield: View, manager: WindowManager, x: Int, y: Int,
                   radius: Int, onBlockedTouch: () -> Unit): GestureTouchGuard? {
            if (!shield.isAttachedToWindow) return null
            val frame = boundsOf(shield)
            val layout = TouchGuardGeometry.aroundPoint(frame, x, y, radius) ?: return null
            val added = mutableListOf<Pair<View, ScreenBounds>>()
            return runCatching {
                layout.blockers.forEach { bounds ->
                    val view = View(shield.context).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        setOnTouchListener { _, event ->
                            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) onBlockedTouch()
                            true
                        }
                    }
                    val params = WindowManager.LayoutParams().apply {
                        copyFrom(shield.layoutParams as WindowManager.LayoutParams)
                        width = bounds.right - bounds.left
                        height = bounds.bottom - bounds.top
                        gravity = Gravity.TOP or Gravity.LEFT
                        this.x = bounds.left
                        this.y = bounds.top
                        flags = flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    }
                    manager.addView(view, params)
                    added += view to bounds
                }
                GestureTouchGuard(shield, manager, frame, added.toList())
            }.getOrElse {
                added.forEach { (view, _) -> runCatching { manager.removeView(view) } }
                null
            }
        }
    }
}
