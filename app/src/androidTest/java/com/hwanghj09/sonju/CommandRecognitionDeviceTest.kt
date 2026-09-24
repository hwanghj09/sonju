package com.hwanghj09.sonju

import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hwanghj09.sonju.voice.CommandRecognition
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommandRecognitionDeviceTest {
    private class Output : RecognitionListener {
        val drafts = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val errors = mutableListOf<Int>()
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onError(error: Int) { errors += error }
        override fun onResults(results: Bundle?) { finals += CommandRecognition.firstText(results).orEmpty() }
        override fun onPartialResults(partialResults: Bundle?) { drafts += CommandRecognition.firstText(partialResults).orEmpty() }
    }

    private fun words(vararg values: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(values.toList()))
    }

    @Test fun segmentedSpeechPreservesRepeatsAndRevisesDraftsWithoutExecutingThem() {
        val output = Output()
        val listener = CommandRecognition.listener(output)
        listener.onSegmentResults(words("볼륨을"))
        listener.onPartialResults(words("주금"))
        listener.onPartialResults(words("조금"))
        listener.onSegmentResults(words("조금"))
        listener.onSegmentResults(words("조금"))
        listener.onSegmentResults(words("낮춰 줘"))
        assertTrue(output.finals.isEmpty())
        assertEquals("볼륨을 조금 조금 낮춰 줘", output.drafts.last())
        listener.onEndOfSegmentedSession()
        listener.onEndOfSegmentedSession()
        listener.onResults(words("중복 결과"))
        listener.onPartialResults(words("늦은 자막"))
        assertEquals(listOf("볼륨을 조금 조금 낮춰 줘"), output.finals)
        assertEquals("볼륨을 조금 조금 낮춰 줘", output.drafts.last())
    }

    @Test fun anErrorInTheNextSegmentNeverExecutesAnEarlierFragment() {
        val output = Output()
        val listener = CommandRecognition.listener(output)
        listener.onSegmentResults(words("문자 보내"))
        listener.onPartialResults(words("지 말고"))
        listener.onError(SpeechRecognizer.ERROR_NETWORK)
        listener.onEndOfSegmentedSession()
        listener.onResults(words("문자 보내"))
        assertTrue(output.finals.isEmpty())
        assertEquals(listOf(SpeechRecognizer.ERROR_NETWORK), output.errors)
    }

    @Test fun unsupportedSegmentationUsesOnlyTheEnginesFinalText() {
        val output = Output()
        val listener = CommandRecognition.listener(output)
        listener.onPartialResults(words("카카오 턱"))
        listener.onPartialResults(words("카카오톡"))
        listener.onResults(words("", "카카오톡 열어 줘"))
        listener.onResults(words("중복 명령"))
        assertEquals(listOf("카카오톡 열어 줘"), output.finals)
        assertTrue(output.errors.isEmpty())
    }

    @Test fun emptyOrOversizedFinalsAreRejectedWithoutTruncation() {
        for (value in listOf("", "가".repeat(1_001))) {
            val output = Output()
            CommandRecognition.listener(output).onResults(words(value))
            assertTrue(output.finals.isEmpty())
            assertEquals(listOf(SpeechRecognizer.ERROR_NO_MATCH), output.errors)
        }
    }

    @Test fun changingTheEngineDiscardsEveryOldRecognitionCallback() {
        val output = Output()
        var current = true
        val old = CommandRecognition.listener(output) { current }
        old.onPartialResults(words("오래된 자막"))
        current = false
        old.onSegmentResults(words("오래된 명령"))
        old.onResults(words("오래된 명령"))
        old.onEndOfSegmentedSession()
        old.onError(SpeechRecognizer.ERROR_NETWORK)
        old.onPartialResults(words("늦은 자막"))
        CommandRecognition.listener(output).onResults(words("새로운 명령"))
        assertEquals(listOf("오래된 자막"), output.drafts)
        assertEquals(listOf("새로운 명령"), output.finals)
        assertTrue(output.errors.isEmpty())
    }
}
