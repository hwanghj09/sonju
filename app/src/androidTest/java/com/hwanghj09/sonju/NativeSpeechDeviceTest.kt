package com.hwanghj09.sonju

import android.content.Intent
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hwanghj09.sonju.voice.CommandRecognition
import com.hwanghj09.sonju.voice.CommandSpeechRecognizer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in synthetic speaker-to-microphone diagnostic. No paid API, no command execution. */
@RunWith(AndroidJUnit4::class)
class NativeSpeechDeviceTest {
    @Test fun installedKoreanRecognizer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("nativeSpeechProbe") == "true")
        val context = instrumentation.targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        val previousVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val result = JSONObject()
        val partials = JSONArray()
        val done = CountDownLatch(1)
        val player = MediaPlayer()
        instrumentation.context.assets.openFd("korean-command.wav").use {
            player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
        }
        player.prepare()
        player.setPreferredDevice(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })
        var closeRecognizer: () -> Unit = {}
        var started = 0L
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, maxOf(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 3), 0)
                scenario.onActivity { activity ->
                    val callback = object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            started = SystemClock.elapsedRealtime()
                            if (arguments.getString("externalPlayback") == "true") {
                                File(context.getExternalFilesDir(null), "speech-accuracy/native-ready").apply {
                                    parentFile!!.mkdirs(); writeText("ready")
                                }
                            } else player.start()
                        }
                        override fun onError(error: Int) { result.put("error", error); done.countDown() }
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() { result.put("captureMs", SystemClock.elapsedRealtime() - started) }
                        override fun onResults(results: Bundle?) {
                            result.put("actual", JSONArray(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()))
                            result.put("confidence", JSONArray(results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.toList().orEmpty()))
                            done.countDown()
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            partials.put(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    }
                    val configured = arguments.getString("commandConfig") == "true"
                    if (configured) {
                        val recognizer = CommandSpeechRecognizer(activity)
                        closeRecognizer = { recognizer.cancel(); recognizer.destroy() }
                        recognizer.setRecognitionListener(callback)
                        recognizer.startListening(CommandRecognition.intent(activity))
                    } else {
                        val recognizer = if (arguments.getString("onDevice") == "true") SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
                            else SpeechRecognizer.createSpeechRecognizer(activity)
                        closeRecognizer = { recognizer.cancel(); recognizer.destroy() }
                        recognizer.setRecognitionListener(callback)
                        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                        })
                    }
                }
                assertTrue("Recognition timed out", done.await(20, TimeUnit.SECONDS))
                result.put("partials", partials)
                result.put("playerDurationMs", player.duration)
                result.put("playerPositionMs", player.currentPosition)
                result.put("audioMode", audio.mode)
                result.put("outputDeviceType", player.routedDevice?.type)
                File(context.getExternalFilesDir(null), "speech-accuracy/native-microphone.json").apply {
                    parentFile!!.mkdirs(); writeText(result.toString(2))
                }
                assertFalse("Recognizer error: $result", result.has("error"))
                assertTrue("No transcript: $result", result.optJSONArray("actual")?.length()?.let { it > 0 } == true)
                if (arguments.getString("commandConfig") == "true") {
                    assertEquals("유튜브에서임영웅노래검색해줘",
                        result.getJSONArray("actual").getString(0).replace(Regex("[\\s\\p{P}]+"), ""))
                }
            } finally {
                instrumentation.runOnMainSync { closeRecognizer() }
                player.release()
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
            }
        }
    }
}
