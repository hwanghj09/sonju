package com.hwanghj09.sonju.agent

/** Uses a monotonic clock supplied by the caller and rejects impossible negative ages. */
object ContextLifetime {
    fun isFresh(nowElapsedRealtime: Long, capturedAtElapsedRealtime: Long, ttlMillis: Long): Boolean {
        if (ttlMillis < 0) return false
        val age = nowElapsedRealtime - capturedAtElapsedRealtime
        return age in 0..ttlMillis
    }

    fun remainingMillis(
        nowElapsedRealtime: Long,
        capturedAtElapsedRealtime: Long,
        ttlMillis: Long,
    ): Long {
        if (!isFresh(nowElapsedRealtime, capturedAtElapsedRealtime, ttlMillis)) return 0L
        return ttlMillis - (nowElapsedRealtime - capturedAtElapsedRealtime)
    }
}
