package com.hwanghj09.sonju

import android.Manifest
import android.app.UiAutomation
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hwanghj09.sonju.voice.VoiceWaveformView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Injects recognizer callbacks into the real screen; acoustic recognition is a separate check. */
@RunWith(AndroidJUnit4::class)
class ListeningUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun openHome() {
        val context = instrumentation.targetContext
        check(context.getSharedPreferences("sonju_preferences", Context.MODE_PRIVATE)
            .getBoolean(OnboardingActivity.KEY_ONBOARDING_COMPLETED, false)) {
            "Complete onboarding on the test device first"
        }
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun closeHome() {
        if (::scenario.isInitialized) scenario.close()
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun begin(activity: MainActivity): RecognitionListener {
        MainActivity::class.java.getDeclaredMethod("startVoiceInput", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, false)
        // Keep this test deterministic and prevent microphone input from executing real requests.
        val start = field("voiceStartRunnable").get(activity) as Runnable
        (field("contextExpiryHandler").get(activity) as Handler).removeCallbacks(start)
        field("voiceStartRunnable").set(activity, null)
        val generation = field("voiceRecognitionGeneration").getLong(activity)
        return MainActivity::class.java.getDeclaredMethod("voiceRecognitionListener", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, generation) as RecognitionListener
    }

    private fun transcript(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    @Test fun fourDotsRespondToVolumeAndCancelRejectsStaleCallbacks() {
        lateinit var oldListener: RecognitionListener
        scenario.onActivity { activity ->
            oldListener = begin(activity)
            oldListener.onReadyForSpeech(null)
            assertEquals("듣고 있어요", activity.findViewById<TextView>(R.id.listeningStatus).text.toString())
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                activity.findViewById<View>(R.id.mainScroll).importantForAccessibility)
            assertFalse(activity.findViewById<View>(R.id.voiceButton).isEnabled)
        }
        SystemClock.sleep(600)
        var quietHeight = 0
        scenario.onActivity { activity ->
            val overlay = activity.findViewById<View>(R.id.listeningOverlay)
            val root = activity.findViewById<View>(R.id.main)
            assertEquals(root.width, overlay.width)
            assertEquals(root.height, overlay.height)
            quietHeight = dotHeight(activity)
            oldListener.onRmsChanged(10f)
        }
        SystemClock.sleep(100)
        scenario.onActivity { activity ->
            oldListener.onRmsChanged(10f)
            assertTrue("Speech should visibly stretch the dots", dotHeight(activity) > quietHeight * 1.5f)
        }
        val screenshot = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        ).takeScreenshot()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "voice-ui-qa/listening.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        SystemClock.sleep(650)
        scenario.onActivity { activity ->
            assertTrue("Silence must settle back to dots", dotHeight(activity) < quietHeight * 1.25f)
        }
        scenario.onActivity { activity -> activity.findViewById<View>(R.id.cancelListeningButton).performClick() }
        SystemClock.sleep(400)
        scenario.onActivity { activity ->
            assertNotEquals(View.VISIBLE, activity.findViewById<View>(R.id.listeningOverlay).visibility)
            val current = begin(activity)
            current.onReadyForSpeech(null)
            oldListener.onPartialResults(transcript("취소한 이전 발화"))
            assertEquals("듣고 있어요", activity.findViewById<TextView>(R.id.listeningStatus).text.toString())
            oldListener.onResults(transcript("취소한 이전 명령"))
            oldListener.onError(SpeechRecognizer.ERROR_NETWORK)
            assertTrue(field("awaitingVoiceRecognition").getBoolean(activity))
            assertEquals("", activity.findViewById<TextView>(R.id.commandInput).text.toString())
            activity.onBackPressedDispatcher.onBackPressed()
        }
        SystemClock.sleep(400)
        scenario.onActivity { activity ->
            assertFalse(field("awaitingVoiceRecognition").getBoolean(activity))
            assertNull(field("voiceRecognizer").get(activity))
            assertTrue(activity.findViewById<View>(R.id.voiceButton).isEnabled)
        }
    }

    /** Counts four white columns and their height in the rendered waveform, without internal state assertions. */
    private fun dotHeight(activity: MainActivity): Int {
        val wave = activity.findViewById<VoiceWaveformView>(R.id.listeningWaveform)
        val bitmap = Bitmap.createBitmap(wave.width, wave.height, Bitmap.Config.ARGB_8888)
        wave.draw(Canvas(bitmap))
        var groups = 0
        var previousFilled = false
        var tallest = 0
        for (x in 0 until bitmap.width) {
            var height = 0
            for (y in 0 until bitmap.height) if (Color.alpha(bitmap.getPixel(x, y)) > 128) height++
            if (height > 0 && !previousFilled) groups++
            previousFilled = height > 0
            tallest = maxOf(tallest, height)
        }
        bitmap.recycle()
        assertEquals("Exactly four separate dots or bars", 4, groups)
        return tallest
    }

    @Test fun finalTranscriptIsAcceptedOnceAndErrorsReturnToHome() {
        scenario.onActivity { activity ->
            val listener = begin(activity)
            listener.onReadyForSpeech(null)
            val status = activity.findViewById<TextView>(R.id.listeningStatus)
            listener.onBeginningOfSpeech()
            assertEquals("", status.text.toString())
            listener.onPartialResults(transcript("음성"))
            assertEquals("음성", status.text.toString())
            listener.onPartialResults(transcript("음성 화명"))
            listener.onPartialResults(transcript("음성 화면 테스트 중"))
            listener.onPartialResults(transcript("  "))
            assertEquals("음성 화면 테스트 중", status.text.toString())
            assertEquals("", activity.findViewById<TextView>(R.id.commandInput).text.toString())
            listener.onEndOfSpeech()
            assertEquals("음성 화면 테스트 중", status.text.toString())
            listener.onResults(transcript("음성 화면 테스트"))
            assertEquals("음성 화면 테스트", status.text.toString())
            listener.onResults(transcript("중복 결과"))
        }
        SystemClock.sleep(400)
        scenario.onActivity { activity ->
            assertEquals("음성 화면 테스트", activity.findViewById<TextView>(R.id.commandInput).text.toString())
            assertNull(field("voiceRecognizer").get(activity))
            begin(activity).onError(SpeechRecognizer.ERROR_NETWORK)
        }
        SystemClock.sleep(400)
        scenario.onActivity { activity ->
            assertEquals(activity.getString(R.string.voice_input_network_error),
                activity.findViewById<TextView>(R.id.resultText).text.toString())
            assertTrue(activity.findViewById<View>(R.id.voiceButton).isEnabled)
        }
    }

    @Test fun leavingTheAppReleasesListeningAndDiscardsLateResults() {
        lateinit var listener: RecognitionListener
        scenario.onActivity { listener = begin(it).also { callback -> callback.onReadyForSpeech(null) } }
        scenario.moveToState(Lifecycle.State.CREATED)
        instrumentation.runOnMainSync { listener.onResults(transcript("백그라운드의 오래된 명령")) }
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.onActivity { activity ->
            assertFalse(field("awaitingVoiceRecognition").getBoolean(activity))
            assertNull(field("voiceStartRunnable").get(activity))
            assertNull(field("voiceTimeoutRunnable").get(activity))
            assertNull(field("voiceRecognizer").get(activity))
            assertNotEquals(View.VISIBLE, activity.findViewById<View>(R.id.listeningOverlay).visibility)
            assertEquals("", activity.findViewById<TextView>(R.id.commandInput).text.toString())
            assertTrue(activity.findViewById<View>(R.id.voiceButton).isEnabled)
        }
    }
}
