package com.hwanghj09.sonju.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.hwanghj09.sonju.agent.InstalledApps

/** Shared Android recognition settings; no metered transcription service or text-model correction. */
internal object CommandRecognition {
    fun intent(context: Context) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
            putExtra(RecognizerIntent.EXTRA_ENABLE_BIASING_DEVICE_CONTEXT, true)
            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(
                InstalledApps.query(context.packageManager).map { it.label.trim() }
                    .filter { it.isNotBlank() && it.length <= 60 }.distinct().take(80),
            ))
        }
    }

    fun firstText(results: Bundle?): String? = results
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull { it.isNotBlank() }?.trim()

    /** Keep one recognizer/microphone open until the engine ends the session. Never merge partials. */
    fun listener(target: RecognitionListener, isCurrent: () -> Boolean = { true }): RecognitionListener = object : RecognitionListener {
        private val segments = mutableListOf<String>()
        private var finished = false
        private fun active() = !finished && isCurrent()

        override fun onReadyForSpeech(params: Bundle?) { if (active()) target.onReadyForSpeech(params) }
        override fun onBeginningOfSpeech() { if (active()) target.onBeginningOfSpeech() }
        override fun onRmsChanged(rmsdB: Float) { if (active()) target.onRmsChanged(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) { if (active()) target.onBufferReceived(buffer) }
        override fun onEndOfSpeech() { if (active()) target.onEndOfSpeech() }
        override fun onEvent(eventType: Int, params: Bundle?) { if (active()) target.onEvent(eventType, params) }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (!active()) return
            firstText(segmentResults)?.let(segments::add)
            publishDraft(null)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!active()) return
            publishDraft(firstText(partialResults))
        }

        private fun publishDraft(partial: String?) {
            val text = (segments + listOfNotNull(partial)).joinToString(" ")
            if (text.isNotBlank()) target.onPartialResults(bundle(text))
        }

        override fun onEndOfSegmentedSession() {
            if (!active()) return
            finish(segments.joinToString(" "))
        }

        override fun onResults(results: Bundle?) {
            if (!active()) return
            // A normal (non-segmented) result is the engine's whole final hypothesis.
            finish(firstText(results).orEmpty())
        }

        private fun finish(text: String) {
            finished = true
            if (text.isBlank() || text.length > 1_000) target.onError(SpeechRecognizer.ERROR_NO_MATCH)
            else target.onResults(bundle(text))
        }

        override fun onError(error: Int) {
            if (!active()) return
            finished = true
            target.onError(error)
        }
    }

    private fun bundle(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }
}

/** Prefer the installed on-device engine; unsupported languages fall back BEFORE listening starts. */
internal class CommandSpeechRecognizer(private val context: Context) {
    private var engine: SpeechRecognizer? = null
    private lateinit var target: RecognitionListener
    private var generation = 0L

    fun setRecognitionListener(listener: RecognitionListener) { target = listener }

    fun startListening(intent: Intent) {
        start(intent, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
    }

    private fun start(intent: Intent, onDevice: Boolean) {
        val token = ++generation
        engine?.destroy()
        var ready = false
        val callback = object : RecognitionListener by target {
            override fun onReadyForSpeech(params: Bundle?) {
                ready = true
                target.onReadyForSpeech(params)
            }

            override fun onError(error: Int) {
                if (onDevice && !ready && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                    runCatching { start(intent, onDevice = false) }
                        .onFailure { target.onError(SpeechRecognizer.ERROR_CLIENT) }
                } else target.onError(error)
            }
        }
        engine = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        engine!!.setRecognitionListener(CommandRecognition.listener(callback) { token == generation })
        engine!!.startListening(intent)
    }

    fun cancel() { generation++; engine?.cancel() }
    fun destroy() { generation++; engine?.destroy(); engine = null }
}
