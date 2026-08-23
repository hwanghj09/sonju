package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.FeedbackMemorySanitizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFeedbackMemoryTest {
    @Test
    fun feedbackTextDoesNotRetainPersonalValues() {
        val sanitized = FeedbackMemorySanitizer.sanitize(
            "010-1234-5678, user@example.com, 인증번호 839201 입력",
        )

        assertTrue(sanitized.contains("<phone>"))
        assertTrue(sanitized.contains("<email>"))
        assertTrue(sanitized.contains("<number>"))
        assertFalse(sanitized.contains("839201"))
    }
}
