package com.hwanghj09.sonju.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hwanghj09.sonju.MainActivity
import com.hwanghj09.sonju.R
import com.hwanghj09.sonju.accessibility.SonjuAccessibilityService

/** Temporary bottom sheet shown only after the on-device wake word has been detected. */
class VoiceCommandActivity : AppCompatActivity(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var transcript: TextView
    private var recognizer: SpeechRecognizer? = null
    private var commandDispatched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_command)
        transcript = findViewById(R.id.liveTranscript)
        findViewById<TextView>(R.id.cancelVoiceCommand).setOnClickListener { finish() }

        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.attributes = window.attributes.apply {
            gravity = Gravity.BOTTOM
            dimAmount = 0.16f
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        handler.postDelayed(::startCommandListening, PANEL_START_DELAY_MILLIS)
        handler.postDelayed(::finishIfStillListening, COMMAND_TIMEOUT_MILLIS)
    }

    private fun startCommandListening() {
        if (isFinishing || isDestroyed) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            showFailureAndClose(R.string.voice_unavailable)
            return
        }
        recognizer = runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also {
                it.setRecognitionListener(this)
            }
        }.getOrElse {
            showFailureAndClose(R.string.voice_unavailable)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        runCatching { recognizer?.startListening(intent) }
            .onFailure { showFailureAndClose(R.string.voice_unavailable) }
    }

    private fun updateTranscript(results: Bundle?) {
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { transcript.text = it.take(500) }
    }

    private fun dispatchCommand(results: Bundle?) {
        val command = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.take(500)
        if (command == null) {
            showFailureAndClose(R.string.voice_no_result)
            return
        }
        commandDispatched = true
        transcript.text = command
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(SonjuAccessibilityService.EXTRA_FROM_OVERLAY, true)
                .putExtra(SonjuAccessibilityService.EXTRA_VOICE_COMMAND, command),
        )
        finish()
    }

    private fun finishIfStillListening() {
        if (!commandDispatched && !isFinishing) showFailureAndClose(R.string.voice_no_result)
    }

    private fun showFailureAndClose(message: Int) {
        transcript.setText(message)
        handler.postDelayed({ if (!isFinishing) finish() }, FAILURE_CLOSE_DELAY_MILLIS)
    }

    override fun onReadyForSpeech(params: Bundle?) {
        transcript.setText(R.string.live_voice_listening)
    }

    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(partialResults: Bundle?) = updateTranscript(partialResults)
    override fun onResults(results: Bundle?) = dispatchCommand(results)

    override fun onError(error: Int) {
        if (!isFinishing) showFailureAndClose(R.string.voice_no_result)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.runCatching {
            cancel()
            destroy()
        }
        recognizer = null
        runCatching {
            startService(Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_RESUME))
        }
        super.onDestroy()
    }

    companion object {
        private const val PANEL_START_DELAY_MILLIS = 180L
        private const val COMMAND_TIMEOUT_MILLIS = 12_000L
        private const val FAILURE_CLOSE_DELAY_MILLIS = 1_200L
    }
}
