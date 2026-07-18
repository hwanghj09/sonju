package com.hwanghj09.sonju.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.hwanghj09.sonju.MainActivity
import com.hwanghj09.sonju.R
import com.hwanghj09.sonju.accessibility.SonjuAccessibilityService

class WakeWordService : Service(), RecognitionListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var pausedForCommand = false
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        running = true
        createNotificationChannel()
        if (!hasMicrophonePermission()) {
            stopSelf()
            return
        }
        promoteToForeground(getString(R.string.wake_word_notification_listening))
        scheduleListening(START_DELAY_MILLIS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_PAUSE -> pauseListening()
            ACTION_RESUME -> resumeListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        releaseRecognizer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground(status: String) {
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.wake_word_notification_title))
            .setContentText(status)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.wake_word_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.wake_word_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.wake_word_channel_description)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun scheduleListening(delayMillis: Long) {
        mainHandler.removeCallbacks(startListeningRunnable)
        if (!destroyed && !pausedForCommand) {
            mainHandler.postDelayed(startListeningRunnable, delayMillis)
        }
    }

    private val startListeningRunnable = Runnable { startListening() }

    private fun startListening() {
        if (destroyed || pausedForCommand || !hasMicrophonePermission()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            notifyStatus(getString(R.string.wake_word_on_device_unavailable))
            stopSelf()
            return
        }

        if (recognizer == null) {
            recognizer = runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also {
                    it.setRecognitionListener(this)
                }
            }.getOrElse {
                notifyStatus(getString(R.string.wake_word_on_device_unavailable))
                stopSelf()
                return
            }
        }

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        runCatching { recognizer?.startListening(recognizerIntent) }
            .onFailure {
                releaseRecognizer()
                scheduleListening(ERROR_RETRY_MILLIS)
            }
    }

    private fun handleRecognition(bundle: Bundle?): Boolean {
        val candidates = bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
        val command = candidates.firstNotNullOfOrNull(WakeWordMatcher::commandAfterWakeWord)
        return when {
            command != null -> {
                onWakeWordDetected(command)
                true
            }
            candidates.any(WakeWordMatcher::matches) -> {
                onWakeWordDetected(command = null)
                true
            }
            else -> false
        }
    }

    private fun onWakeWordDetected(command: String?) {
        if (pausedForCommand) return
        pausedForCommand = true
        mainHandler.removeCallbacks(startListeningRunnable)
        releaseRecognizer()
        notifyStatus(getString(R.string.wake_word_notification_detected))
        val accessibilityService = SonjuAccessibilityService.instance
        if (accessibilityService != null) {
            accessibilityService.requestVoiceWake(command)
        } else {
            notifyStatus(getString(R.string.wake_word_accessibility_required))
        }
        mainHandler.postDelayed({ resumeListening() }, COMMAND_FAILSAFE_MILLIS)
    }

    private fun resumeListening() {
        if (destroyed) return
        pausedForCommand = false
        notifyStatus(getString(R.string.wake_word_notification_listening))
        scheduleListening(RESUME_DELAY_MILLIS)
    }

    private fun pauseListening() {
        if (destroyed) return
        pausedForCommand = true
        mainHandler.removeCallbacks(startListeningRunnable)
        releaseRecognizer()
        notifyStatus(getString(R.string.wake_word_notification_detected))
    }

    private fun releaseRecognizer() {
        recognizer?.runCatching {
            cancel()
            destroy()
        }
        recognizer = null
    }

    private fun notifyStatus(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun hasMicrophonePermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) {
        if (destroyed || pausedForCommand) return
        val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
            error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
        ) BUSY_RETRY_MILLIS else ERROR_RETRY_MILLIS
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            stopSelf()
        } else {
            scheduleListening(delay)
        }
    }

    override fun onResults(results: Bundle?) {
        val detected = handleRecognition(results)
        if (!detected && !pausedForCommand) scheduleListening(NORMAL_RETRY_MILLIS)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val candidates = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
        if (candidates.any(WakeWordMatcher::matches)) {
            // Open the live panel as soon as the wake word is stable; it captures the command next.
            onWakeWordDetected(command = null)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    companion object {
        const val ACTION_STOP = "com.hwanghj09.sonju.action.STOP_WAKE_WORD"
        const val ACTION_PAUSE = "com.hwanghj09.sonju.action.PAUSE_WAKE_WORD"
        const val ACTION_RESUME = "com.hwanghj09.sonju.action.RESUME_WAKE_WORD"
        private const val CHANNEL_ID = "sonju_wake_word"
        private const val NOTIFICATION_ID = 2001
        private const val START_DELAY_MILLIS = 400L
        private const val NORMAL_RETRY_MILLIS = 500L
        private const val ERROR_RETRY_MILLIS = 1_500L
        private const val BUSY_RETRY_MILLIS = 5_000L
        private const val RESUME_DELAY_MILLIS = 800L
        private const val COMMAND_FAILSAFE_MILLIS = 60_000L
        @Volatile
        var running: Boolean = false
            private set
    }
}
