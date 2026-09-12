package com.hwanghj09.sonju

import com.hwanghj09.sonju.voice.WakeWordMatcher
import com.hwanghj09.sonju.voice.VoiceControl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
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

    @Test fun confirmationNeedsAnExplicitWholeReplyAndNegationAlwaysWins() {
        listOf("네", "네, 진행해 주세요", "완료했어요", "계속해줘").forEach {
            assertEquals(it, VoiceControl.Reply.CONFIRM, VoiceControl.confirmationReply(it))
        }
        listOf("아니요", "네 하지 마", "진행하지 마세요", "취소해줘", "손주야 멈춰").forEach {
            assertEquals(it, VoiceControl.Reply.CANCEL, VoiceControl.confirmationReply(it))
        }
        listOf("네이버 열어줘", "네 전화번호가 뭐야", "내일", "", "아마도", "네 취소할까요").forEach {
            assertNotEquals(it, VoiceControl.Reply.CONFIRM, VoiceControl.confirmationReply(it))
        }
        assertTrue(VoiceControl.isStopRequest("손 주 야, 멈춰!"))
        assertFalse(VoiceControl.isStopRequest("멈춰라는 글자를 메모해줘"))
    }
}
