package com.hwanghj09.sonju.agent

import com.hwanghj09.sonju.logging.RedactionPolicy

/** Ephemeral app feedback, never an executable node or a persisted success condition. */
data class ToastMessage(val id: Long, val packageName: String, val text: String, val receivedAtMillis: Long)

class ToastFeedback {
    private val messages = ArrayDeque<ToastMessage>()
    private var sequence = 0L

    @Synchronized
    fun record(packageName: String, text: String, nowMillis: Long): Boolean {
        prune(nowMillis)
        val safe = RedactionPolicy.redact(text)?.trim()?.takeIf(String::isNotBlank) ?: return false
        if (packageName.isBlank() || packageName == "unknown") return false
        val previous = messages.lastOrNull()
        if (previous?.packageName == packageName && previous.text == safe &&
            nowMillis - previous.receivedAtMillis in 0..250) return false
        messages.addLast(ToastMessage(++sequence, packageName, safe, nowMillis))
        while (messages.size > 4) messages.removeFirst()
        return true
    }

    @Synchronized
    fun recent(packageName: String, nowMillis: Long): List<ToastMessage> {
        prune(nowMillis)
        return messages.filter { it.packageName == packageName }
    }

    @Synchronized
    fun clear() = messages.clear()

    private fun prune(nowMillis: Long) {
        messages.removeAll { nowMillis - it.receivedAtMillis !in 0..MAX_AGE_MILLIS }
    }

    companion object {
        const val MAX_AGE_MILLIS = 30_000L
    }
}

fun UiSnapshot.hasNewToastSince(before: UiSnapshot): Boolean = recentToasts.any { message ->
    message.packageName == packageName && before.recentToasts.none { it.id == message.id }
}
