package com.hwanghj09.sonju.accessibility

import android.os.Handler

/** One foreground action at a time: wake postcondition checks after accessibility revisions settle. */
class AccessibilityEventMonitor(
    private val handler: Handler,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MILLIS,
) {
    private data class Pending(
        val generation: Long,
        val beforeEpoch: Long,
        val callback: (Boolean) -> Unit,
        val timeout: Runnable,
        var sawRevision: Boolean = false,
        var debounce: Runnable? = null,
    )

    private var pending: Pending? = null

    fun waitForRevision(
        generation: Long,
        beforeEpoch: Long,
        currentEpoch: Long,
        timeoutMs: Long,
        callback: (Boolean) -> Unit,
    ) {
        cancel()
        if (currentEpoch > beforeEpoch) {
            handler.post { callback(true) }
            return
        }
        val timeout = Runnable {
            val active = pending?.takeIf { it.generation == generation } ?: return@Runnable
            pending = null
            active.debounce?.let(handler::removeCallbacks)
            active.callback(active.sawRevision)
        }
        pending = Pending(generation, beforeEpoch, callback, timeout)
        handler.postDelayed(timeout, timeoutMs)
    }

    fun onRevision(epoch: Long) {
        val active = pending?.takeIf { epoch > it.beforeEpoch } ?: return
        active.sawRevision = true
        active.debounce?.let(handler::removeCallbacks)
        val settled = Runnable {
            val current = pending?.takeIf { it.generation == active.generation } ?: return@Runnable
            pending = null
            handler.removeCallbacks(current.timeout)
            current.callback(true)
        }
        active.debounce = settled
        handler.postDelayed(settled, debounceMs.coerceAtLeast(0L))
    }

    fun cancel(generation: Long? = null) {
        val active = pending ?: return
        if (generation != null && active.generation != generation) return
        pending = null
        handler.removeCallbacks(active.timeout)
        active.debounce?.let(handler::removeCallbacks)
    }

    private companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 200L
    }
}
