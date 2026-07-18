package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.ContextLifetime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextLifetimeTest {
    @Test
    fun acceptsOnlyNonNegativeAgeWithinTtl() {
        assertTrue(ContextLifetime.isFresh(1_000, 1_000, 120_000))
        assertTrue(ContextLifetime.isFresh(121_000, 1_000, 120_000))
        assertFalse(ContextLifetime.isFresh(999, 1_000, 120_000))
        assertFalse(ContextLifetime.isFresh(121_001, 1_000, 120_000))
    }

    @Test
    fun invalidOrExpiredContextHasNoRemainingLifetime() {
        assertEquals(0L, ContextLifetime.remainingMillis(999, 1_000, 120_000))
        assertEquals(0L, ContextLifetime.remainingMillis(121_001, 1_000, 120_000))
    }
}
