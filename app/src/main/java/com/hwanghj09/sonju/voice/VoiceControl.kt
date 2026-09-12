package com.hwanghj09.sonju.voice

import java.text.Normalizer

/** Only a complete, explicit reply can authorize a pending confirmation. */
object VoiceControl {
    enum class Reply { CONFIRM, CANCEL, UNKNOWN }

    fun confirmationReply(text: String): Reply {
        val normalized = compact(text)
        return when {
            isStopRequest(text) || NEGATIVE.any(normalized::contains) -> Reply.CANCEL
            normalized in AFFIRMATIVE -> Reply.CONFIRM
            else -> Reply.UNKNOWN
        }
    }

    fun isStopRequest(text: String): Boolean = compact(
        WakeWordMatcher.commandAfterWakeWord(text) ?: text,
    ) in STOP

    private fun compact(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase().replace(Regex("[\\s\\p{P}]+"), "")
    private val AFFIRMATIVE = setOf("네", "예", "응", "좋아요", "진행해", "진행해줘", "진행해주세요",
        "네진행해", "네진행해줘", "네진행해주세요", "실행해", "실행해줘", "실행해주세요",
        "확인", "확인했어요", "완료", "완료했어요", "계속", "계속해", "계속해줘", "계속해주세요")
    private val NEGATIVE = setOf("아니", "하지마", "하지말", "진행하지", "실행하지", "취소")
    private val STOP = setOf("멈춰", "멈춰줘", "멈춰주세요", "그만", "그만해", "그만해줘", "그만해주세요",
        "중단", "중단해", "중단해줘", "중단해주세요", "취소", "취소해", "취소해줘", "취소해주세요",
        "닫아", "닫아줘", "닫아주세요")
}
