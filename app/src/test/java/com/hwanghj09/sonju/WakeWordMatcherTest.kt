package com.hwanghj09.sonju

import com.hwanghj09.sonju.voice.WakeWordMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordMatcherTest {
    @Test
    fun matchesExactAndSpacedWakeWord() {
        assertTrue(WakeWordMatcher.matches("손주야"))
        assertTrue(WakeWordMatcher.matches("손 주야, 도와줘"))
    }

    @Test
    fun matchesCommonRecognitionVariant() {
        assertTrue(WakeWordMatcher.matches("손주아"))
        assertTrue(WakeWordMatcher.matches("선주야"))
        assertTrue(WakeWordMatcher.matches("손쥬야"))
    }

    @Test
    fun rejectsUnrelatedSpeech() {
        assertFalse(WakeWordMatcher.matches("오늘 날씨 알려줘"))
        assertFalse(WakeWordMatcher.matches("손주에게 전화해 줘"))
    }

    @Test
    fun extractsCommandSpokenAfterWakeWord() {
        assertEquals(
            "배민 들어가서 피자 시켜줘",
            WakeWordMatcher.commandAfterWakeWord("손주야, 배민 들어가서 피자 시켜줘"),
        )
        assertEquals(
            "와이파이 설정 열어 줘",
            WakeWordMatcher.commandAfterWakeWord("손 주 야 와이파이 설정 열어 줘"),
        )
        assertEquals(
            "카카오톡 열어 줘",
            WakeWordMatcher.commandAfterWakeWord("선쥬야 카카오톡 열어 줘"),
        )
    }

    @Test
    fun returnsNullWhenOnlyWakeWordWasSpoken() {
        assertNull(WakeWordMatcher.commandAfterWakeWord("손주야"))
    }
}
