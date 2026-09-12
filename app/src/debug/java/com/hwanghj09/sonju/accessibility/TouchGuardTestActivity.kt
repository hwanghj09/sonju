package com.hwanghj09.sonju.accessibility

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicInteger

/** Local, unexported input-routing fixture; absent from release builds. */
class TouchGuardTestActivity : Activity() {
    val appDowns = AtomicInteger()
    val appMoves = AtomicInteger()
    val appUps = AtomicInteger()

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> appDowns.incrementAndGet()
                    MotionEvent.ACTION_MOVE -> appMoves.incrementAndGet()
                    MotionEvent.ACTION_UP -> appUps.incrementAndGet()
                }
                true
            }
        })
    }
}
