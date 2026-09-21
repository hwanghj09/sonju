package com.hwanghj09.sonju.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.provider.AlarmClock
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.Gravity
import android.view.Display
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.hwanghj09.sonju.R
import com.hwanghj09.sonju.BuildConfig
import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.AgentAction
import com.hwanghj09.sonju.agent.AgentPlan
import com.hwanghj09.sonju.agent.AppWorkflowRoute
import com.hwanghj09.sonju.agent.AppWorkflowRouter
import com.hwanghj09.sonju.agent.AutonomySession
import com.hwanghj09.sonju.agent.ContextLifetime
import com.hwanghj09.sonju.agent.EssentialSafetyPolicy
import com.hwanghj09.sonju.agent.ExecutionResult
import com.hwanghj09.sonju.agent.LearnedRouteMemory
import com.hwanghj09.sonju.agent.PlanSource
import com.hwanghj09.sonju.agent.RiskLevel
import com.hwanghj09.sonju.agent.RuleBasedPlanner
import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.ScreenExplainer
import com.hwanghj09.sonju.agent.ScreenContextHandoff
import com.hwanghj09.sonju.agent.SonjuAgentRuntime
import com.hwanghj09.sonju.agent.TrustedSettingsRoute
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiNodeAction
import com.hwanghj09.sonju.agent.UiSnapshot
import com.hwanghj09.sonju.agent.UserFeedbackMemory
import com.hwanghj09.sonju.agent.ToastFeedback
import com.hwanghj09.sonju.agent.hasNewToastSince
import com.hwanghj09.sonju.ai.OpenAiPlanner
import com.hwanghj09.sonju.ai.VisualScreenResult
import com.hwanghj09.sonju.execution.ExecutionFailureReason
import com.hwanghj09.sonju.execution.ExecutionMethod
import com.hwanghj09.sonju.shopping.BaeminNavigator
import com.hwanghj09.sonju.shopping.BaeminOrderLocalPlanner
import com.hwanghj09.sonju.shopping.BaeminScreenAction
import com.hwanghj09.sonju.voice.WakeWordService
import com.hwanghj09.sonju.verifier.VerificationResult
import com.hwanghj09.sonju.verifier.VerifiedAction
import com.hwanghj09.sonju.verifier.VerifiedPlan
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

class SonjuAccessibilityService : AccessibilityService() {
    private data class ScreenshotFrame(
        val jpegBase64: String,
        val width: Int,
        val height: Int,
    )

    data class PendingOverlayContext(
        val snapshot: UiSnapshot,
        val semanticMapJpegBase64: String?,
        val capturedAtElapsedRealtime: Long,
        val sessionId: Long,
    )

    private data class TrustedSettingsContext(
        val route: TrustedSettingsRoute,
        val armedAtElapsedRealtime: Long,
        var establishedActivityClass: String? = null,
        var establishedWindowTitle: String? = null,
    )

    private data class ObservedApplicationSnapshot(
        val snapshot: UiSnapshot,
        val capturedAtElapsedRealtime: Long,
    )

    private data class SnapshotCaptureRequest(
        val root: AccessibilityNodeInfo,
        val expectedPackage: String,
        val snapshotEpoch: Long,
        val windowGeneration: Long,
    )

    private data class ActiveExecution(
        val generation: Long,
        val complete: (ExecutionResult) -> Unit,
    )

    private data class BaeminOrderSession(
        val query: String,
        val generation: Long,
        val startedAtElapsedRealtime: Long,
        var completedSteps: Int = 0,
        var actionInFlight: Boolean = false,
        var itemAdded: Boolean = false,
        var completionBaseline: List<String>? = null,
    )

    private data class PendingBaeminCommit(
        val generation: Long,
        val clickablePath: String,
        val completionBaseline: List<String>,
    )


    private val mainHandler = Handler(Looper.getMainLooper())
    private val eventMonitor = AccessibilityEventMonitor(mainHandler)
    private val toastFeedback = ToastFeedback()
    private val snapshotExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SonjuSnapshot").apply { isDaemon = true }
    }
    private val epoch = AtomicLong(SystemClock.elapsedRealtime())
    private val overlaySession = AtomicLong(0L)
    private val overlayOpenAiPlanner = OpenAiPlanner(installedApps = { launcherCatalog() })
    private val architectureRuntime by lazy { SonjuAgentRuntime.get(this) }
    private val learnedRouteMemory by lazy { LearnedRouteMemory(this) }
    private val userFeedbackMemory by lazy { UserFeedbackMemory(this) }
    private var executionGeneration = 0L
    private var executionActive = false
    private var lastExecutionMethod: ExecutionMethod? = null
    private var lastDispatchSourceEpoch: Long? = null
    private var verifiedPostconditionGeneration = -1L
    private var activeExecution: ActiveExecution? = null
    private var overlayCaptureInProgress = false
    private var overlayCaptureGeneration = 0L
    private var overlayVoiceCommand: String? = null
    private var quickVoiceButton: ImageButton? = null
    private var overlayWindowManager: WindowManager? = null
    private var touchIndicator: View? = null
    private var touchIndicatorGeneration = 0L
    private var controlGlow: ScreenControlGlowView? = null
    private var commandControlActive = false
    private var gestureTouchGuard: GestureTouchGuard? = null
    private var controlGlowWindowManager: WindowManager? = null
    private var voicePanel: View? = null
    private var voicePanelTranscript: TextView? = null
    private var voicePanelRecognizer: SpeechRecognizer? = null
    private var voiceRecognitionGeneration = 0L
    private var pendingVoiceConfirmation: (() -> Unit)? = null
    private var voiceConfirmationMessage = ""
    private val voiceConfirmationTimeout = Runnable {
        if (pendingVoiceConfirmation != null) {
            clearVoiceConfirmation()
            finishAutonomyAttempt()
            deliverScreenExplanation("확인 시간이 지나 실행하지 않았어요. 필요하면 다시 말씀해 주세요.")
        }
    }
    private var voicePanelCommandDispatched = false
    private var voicePanelAccumulatedCommand = ""
    private var voicePanelWaitingForContinuation = false
    private var voicePanelFromOverlay = false
    private var voicePanelConfirmButton: TextView? = null
    private var feedbackPromptVisible = false
    private var activeFeedbackCommand: String? = null
    private var activeFeedbackSnapshot: UiSnapshot? = null
    private var activeFeedbackApproach = ""
    private var overlayCommandGeneration = 0L
    private var overlayCommandExecutionActive = false
    private var proactiveSearchCommand: String? = null
    private var proactiveSearchStepCount = 0
    private var transientPlanningRetryCount = 0
    private var autonomySession: AutonomySession? = null
    private val pendingUserInterventionStore by lazy { com.hwanghj09.sonju.agent.PendingUserInterventionStore(this) }
    private var userResumeRequested = false
    private val userHandoffObservation = object : Runnable {
        override fun run() {
            val handoff = autonomySession?.userHandoff ?: return
            if (instance !== this@SonjuAccessibilityService) return
            if (SystemClock.elapsedRealtime() - handoff.startedAtMillis !in 0..86_400_000L) {
                finishAutonomyAttempt()
                return
            }
            if (!overlayCaptureInProgress && !snapshotCaptureInFlight && !executionActive) requestCurrentApplicationSnapshot()
            mainHandler.postDelayed(this, AutonomySession.USER_RESUME_SETTLE_MILLIS)
        }
    }
    private var lastObservedApplicationSnapshot: ObservedApplicationSnapshot? = null
    private var lastObservedSnapshotCaptureAtElapsedRealtime = 0L
    private var lastObservedWindowPackage: String? = null
    private var lastObservedWindowClass: String? = null
    private var observedWindowGeneration = 0L
    private var pendingSnapshotCapture: SnapshotCaptureRequest? = null
    private var snapshotCaptureInFlight = false
    private var observedSnapshotFuture: Future<*>? = null
    private var observedSnapshotCaptureGeneration = 0L
    private var trustedSettingsContext: TrustedSettingsContext? = null
    private var baeminOrderGeneration = 0L
    private var baeminOrderSession: BaeminOrderSession? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var activeExplanationUtteranceId: String? = null
    private var explanationUtteranceGeneration = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        val consented = getSharedPreferences(CONSENT_PREFERENCES, MODE_PRIVATE)
            .getBoolean(CONSENT_KEY, false)
        debugTrace("service connected consent=$consented")
        if (!consented) {
            Toast.makeText(
                this,
                "손주 앱에서 화면 정보 이용 안내에 먼저 동의해 주세요.",
                Toast.LENGTH_LONG,
            ).show()
            disableSelf()
            return
        }
        instance = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            serviceInfo = serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
            }
        }
        epoch.incrementAndGet()
        initializeTextToSpeech()
        showQuickVoiceButton()
    }

    private fun initializeTextToSpeech() {
        if (textToSpeech != null) return
        textToSpeech = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS &&
                textToSpeech?.setLanguage(Locale.KOREAN) != TextToSpeech.LANG_MISSING_DATA
            if (ttsReady) {
                textToSpeech?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            finishExplanationSpeech(utteranceId)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            finishExplanationSpeech(utteranceId)
                        }
                    },
                )
                pendingSpeech?.let(::speakExplanation)
                pendingSpeech = null
            }
        }
    }

    private fun speakExplanation(message: String) {
        val safeMessage = message.take(1_500)
        if (!ttsReady) {
            pendingSpeech = safeMessage
            initializeTextToSpeech()
            return
        }
        val utteranceId = "sonju-screen-explanation-${++explanationUtteranceGeneration}"
        activeExplanationUtteranceId = utteranceId
        val result = textToSpeech?.speak(
            safeMessage,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId,
        )
        if (result == TextToSpeech.ERROR) finishExplanationSpeech(utteranceId)
    }

    private fun finishExplanationSpeech(utteranceId: String?) {
        mainHandler.post {
            if (utteranceId == null || activeExplanationUtteranceId != utteranceId) return@post
            activeExplanationUtteranceId = null
            if (pendingVoiceConfirmation != null) {
                mainHandler.removeCallbacks(voiceConfirmationTimeout)
                mainHandler.postDelayed(voiceConfirmationTimeout, 45_000)
                mainHandler.postDelayed(::startVoicePanelListening, 250)
            } else if (!commandControlActive && !overlayCommandExecutionActive) {
                // Reading a result must not require tapping the feedback card before the next request.
                resumeWakeWordListening()
            }
        }
    }

    private fun stopExplanationSpeech() {
        pendingSpeech = null
        if (activeExplanationUtteranceId != null) {
            activeExplanationUtteranceId = null
            textToSpeech?.stop()
        }
    }

    private fun showQuickVoiceButton() {
        if (quickVoiceButton != null) return
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val shouldStartHidden = rootInActiveWindow?.packageName?.toString() == packageName
        val size = dp(56)
        val displayWidth = resources.displayMetrics.widthPixels
        val displayHeight = resources.displayMetrics.heightPixels
        val preferences = getSharedPreferences(CONSENT_PREFERENCES, MODE_PRIVATE)
        val button = ImageButton(this).apply {
            contentDescription = getString(R.string.quick_voice_button_description)
            setImageResource(R.drawable.ic_mic)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@SonjuAccessibilityService, R.color.sonju_green))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(8).toFloat()
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt(
                QUICK_VOICE_X_KEY,
                displayWidth - size - dp(8),
            ).coerceIn(0, (displayWidth - size).coerceAtLeast(0))
            y = preferences.getInt(
                QUICK_VOICE_Y_KEY,
                (displayHeight - size) / 2,
            ).coerceIn(0, (displayHeight - size).coerceAtLeast(0))
        }
        button.setOnClickListener { requestVoiceWake() }
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var downWindowX = 0
        var downWindowY = 0
        var dragged = false
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downWindowX = params.x
                    downWindowY = params.y
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downRawX
                    val deltaY = event.rawY - downRawY
                    if (!dragged && (kotlin.math.abs(deltaX) > touchSlop ||
                            kotlin.math.abs(deltaY) > touchSlop)
                    ) dragged = true
                    if (dragged) {
                        params.x = (downWindowX + deltaX.toInt())
                            .coerceIn(0, (displayWidth - size).coerceAtLeast(0))
                        params.y = (downWindowY + deltaY.toInt())
                            .coerceIn(0, (displayHeight - size).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(button, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        preferences.edit()
                            .putInt(QUICK_VOICE_X_KEY, params.x)
                            .putInt(QUICK_VOICE_Y_KEY, params.y)
                            .apply()
                    } else {
                        view.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        runCatching { windowManager.addView(button, params) }
            .onSuccess {
                overlayWindowManager = windowManager
                quickVoiceButton = button
                if (shouldStartHidden) button.visibility = View.GONE
                button.post(::updateQuickVoiceVisibilityForForegroundApp)
            }
    }

    private fun updateQuickVoiceVisibilityForForegroundApp() {
        val foregroundPackage = runCatching {
            windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
                .mapNotNull { it.root?.packageName?.toString() }
                .firstOrNull()
        }.getOrNull().orEmpty()
        if (foregroundPackage.isBlank()) return
        val shouldShow = foregroundPackage != packageName && voicePanel == null &&
            !overlayCommandExecutionActive
        quickVoiceButton?.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun hideQuickVoiceButton() {
        val button = quickVoiceButton ?: return
        quickVoiceButton = null
        runCatching { overlayWindowManager?.removeView(button) }
        overlayWindowManager = null
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility") // Only the existing accessible stop button is clickable.
    private fun showControlGlow() {
        if (controlGlow != null) return
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val glow = ScreenControlGlowView(this)
        var stopPressed = false
        glow.setOnTouchListener { _, event ->
            // Consume app touches, but keep the existing stop control available above the shield.
            val stop = voicePanel?.findViewById<TextView>(R.id.cancelVoiceCommand)
            val location = IntArray(2)
            stop?.getLocationOnScreen(location)
            val bounds = Rect(location[0], location[1], location[0] + (stop?.width ?: 0),
                location[1] + (stop?.height ?: 0))
            val overStop = stop?.isShown == true &&
                stop.isEnabled && stop.text.toString() == "중단" &&
                bounds.contains(event.rawX.toInt(), event.rawY.toInt())
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> stopPressed = overStop
                MotionEvent.ACTION_MOVE -> if (!overStop) stopPressed = false
                MotionEvent.ACTION_UP -> {
                    if (stopPressed && overStop) stop?.performClick()
                    stopPressed = false
                }
                MotionEvent.ACTION_CANCEL -> stopPressed = false
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) debugTrace("user touch blocked")
            true
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        runCatching { windowManager.addView(glow, params) }
            .onSuccess {
                controlGlowWindowManager = windowManager
                controlGlow = glow
                debugTrace("control shield shown")
            }
            .onFailure { debugTrace("control shield unavailable reason=${it::class.java.simpleName}") }
    }

    private fun beginCommandControl(): Boolean {
        if (windows.isNotEmpty()) showControlGlow()
        commandControlActive = controlGlow != null
        if (commandControlActive) {
            voicePanel?.findViewById<TextView>(R.id.voicePanelTitle)?.text = "손주가 작업 중이에요 · 화면 잠금"
            sendWakeWordAction(WakeWordService.ACTION_CONTROL)
        } else {
            showOverlayMessage("화면 잠금을 준비하지 못해 작업을 시작하지 않았어요. 잠시 후 다시 말씀해 주세요.")
        }
        return commandControlActive
    }

    private fun endCommandControl() {
        if (commandControlActive) pauseWakeWordListening()
        commandControlActive = false
        voicePanel?.findViewById<TextView>(R.id.voicePanelTitle)?.text = "손주 안내"
        hideControlGlow()
    }

    private fun dispatchGuardedGesture(
        gesture: GestureDescription,
        startX: Float,
        startY: Float,
        callback: (Boolean) -> Unit,
    ) {
        val glow = controlGlow
        val manager = controlGlowWindowManager
        if (glow == null || manager == null || gestureTouchGuard != null) {
            callback(false)
            return
        }
        val guard = GestureTouchGuard.create(glow, manager, startX.toInt(), startY.toInt(),
            dp(2).coerceIn(1, 32)) { debugTrace("user touch blocked during gesture") }
        if (guard == null) {
            debugTrace("gesture aperture unavailable; full shield retained")
            callback(false)
            return
        }
        gestureTouchGuard = guard
        val generation = executionGeneration
        val sourceEpoch = epoch.get()
        val panel = voicePanel
        val quickButton = quickVoiceButton
        val panelVisibility = panel?.visibility
        val quickVisibility = quickButton?.visibility
        val overlayFlags = listOfNotNull(panel, quickButton).mapNotNull { view ->
            (view.layoutParams as? WindowManager.LayoutParams)?.let { Triple(view, it, it.flags) }
        }
        fun setOverlayPassthrough(enabled: Boolean): Boolean = overlayFlags.map { (view, params, flags) ->
            runCatching {
                if (view.isAttachedToWindow) {
                    params.flags = if (enabled) flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else flags
                    (overlayWindowManager ?: manager).updateViewLayout(view, params)
                }
            }.isSuccess
        }.all { it }
        guard.onDispose = {
            setOverlayPassthrough(false)
            if (voicePanel === panel) panelVisibility?.let { panel.visibility = it }
            if (quickVoiceButton === quickButton) quickVisibility?.let { quickButton.visibility = it }
        }
        if (!setOverlayPassthrough(true)) {
            gestureTouchGuard = null
            guard.dispose()
            callback(false)
            return
        }
        panel?.visibility = View.INVISIBLE
        quickButton?.visibility = View.INVISIBLE
        var finished = false
        val watchdog = Runnable {
            if (gestureTouchGuard === guard) {
                debugTrace("gesture aperture timeout; stopping request")
                forceStopFromVoicePanel()
            }
        }
        fun finish(success: Boolean) {
            if (finished || guard.disposed) return
            finished = true
            if (!guard.close {
                    mainHandler.removeCallbacks(watchdog)
                    if (gestureTouchGuard === guard) gestureTouchGuard = null
                    debugTrace("gesture aperture closed; full shield restored")
                    if (generation == executionGeneration) {
                        callback(success)
                    }
                }) forceStopFromVoicePanel()
        }
        mainHandler.postDelayed(watchdog, 2_000L)
        mainHandler.postDelayed({
            if (generation != executionGeneration || sourceEpoch != epoch.get() || !guard.hasExpectedCoverage()) {
                debugTrace("gesture aperture layout invalid; gesture cancelled")
                finish(false)
                return@postDelayed
            }
            if (!guard.open {
                    if (generation != executionGeneration || sourceEpoch != epoch.get() || !guard.hasExpectedCoverage()) {
                        finish(false)
                        return@open
                    }
                    debugTrace("gesture aperture ready; surrounding touches blocked")
                    val accepted = runCatching {
                        dispatchGesture(gesture, object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription) = finish(true)
                            override fun onCancelled(gestureDescription: GestureDescription) = finish(false)
                        }, mainHandler)
                    }.getOrDefault(false)
                    if (!accepted) finish(false)
                }) finish(false)
        }, SCREENSHOT_OVERLAY_SETTLE_MILLIS)
    }
    private fun hideControlGlow() {
        gestureTouchGuard?.dispose()
        gestureTouchGuard = null
        val glow = controlGlow ?: return
        controlGlow = null
        runCatching { controlGlowWindowManager?.removeView(glow) }
        controlGlowWindowManager = null
        debugTrace("control shield hidden")
    }

    private fun showTouchIndicator(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return
        showTouchIndicator(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun showTouchIndicator(x: Float, y: Float) {
        val displayWidth = resources.displayMetrics.widthPixels
        val displayHeight = resources.displayMetrics.heightPixels
        if (x !in 0f..displayWidth.toFloat() || y !in 0f..displayHeight.toFloat()) return

        val windowManager = overlayWindowManager
            ?: (getSystemService(WINDOW_SERVICE) as WindowManager).also {
                overlayWindowManager = it
            }
        touchIndicator?.let { previous ->
            runCatching { windowManager.removeView(previous) }
        }
        val size = dp(22)
        val marker = View(this).apply {
            contentDescription = "손주 터치 위치"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(12).toFloat()
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.toInt() - size / 2
            this.y = y.toInt() - size / 2
        }
        val generation = ++touchIndicatorGeneration
        runCatching { windowManager.addView(marker, params) }
            .onFailure { return }
        touchIndicator = marker
        mainHandler.postDelayed(
            {
                if (touchIndicatorGeneration == generation && touchIndicator === marker) {
                    touchIndicator = null
                    runCatching { windowManager.removeView(marker) }
                }
            },
            TOUCH_INDICATOR_DURATION_MILLIS,
        )
    }

    private fun hideTouchIndicator() {
        touchIndicatorGeneration += 1
        val marker = touchIndicator ?: return
        touchIndicator = null
        runCatching {
            (overlayWindowManager ?: getSystemService(WINDOW_SERVICE) as WindowManager)
                .removeView(marker)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    internal fun currentDisplayBounds(): ScreenBounds {
        val manager = getSystemService(WindowManager::class.java)
        // Accessibility bounds and screenshot ratios include the system bars. App resource metrics do not.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = manager.maximumWindowMetrics.bounds
            return ScreenBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealSize(size)
        return ScreenBounds(0, 0, size.x, size.y)
    }

    private fun showVoicePanel(fromOverlay: Boolean) {
        if (voicePanel != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.wake_word_permission_denied, Toast.LENGTH_LONG).show()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show()
            return
        }

        pauseWakeWordListening()
        val windowManager = overlayWindowManager
            ?: (getSystemService(WINDOW_SERVICE) as WindowManager).also {
                overlayWindowManager = it
            }
        val panel = LayoutInflater.from(this).inflate(R.layout.activity_voice_command, null).apply {
            findViewById<TextView>(R.id.cancelVoiceCommand).setOnClickListener {
                retryVoicePanel()
            }
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    if (!voicePanelCommandDispatched || autonomySession?.userHandoff != null ||
                        pendingUserInterventionStore.read() != null) {
                        dismissVoicePanelFromOutside(cancelBaemin = false)
                    }
                    true
                } else {
                    false
                }
            }
        }
        val params = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - dp(24),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(18)
        }
        runCatching { windowManager.addView(panel, params) }
            .onFailure {
                resumeWakeWordListening()
                return
            }
        voicePanel = panel
        voicePanelTranscript = panel.findViewById(R.id.liveTranscript)
        voicePanelFromOverlay = fromOverlay
        quickVoiceButton?.visibility = View.GONE
        beginVoicePanelListening()
    }

    private fun beginVoicePanelListening() {
        if (voicePanel == null) return
        stopVoicePanelRecognizer()
        feedbackPromptVisible = false
        voicePanel?.findViewById<View>(R.id.feedbackActions)?.visibility = View.GONE
        voicePanelCommandDispatched = false
        voicePanelAccumulatedCommand = ""
        voicePanelWaitingForContinuation = false
        mainHandler.postDelayed(::startVoicePanelListening, VOICE_PANEL_START_DELAY_MILLIS)
        resetVoicePanelTimeout()
    }

    private val voicePanelTimeoutRunnable = Runnable {
        if (!voicePanelCommandDispatched && voicePanel != null) {
            if (voicePanelAccumulatedCommand.isNotBlank()) {
                finalizeVoicePanelCommand()
            } else {
                showVoicePanelFailure(R.string.voice_no_result)
            }
        }
    }

    private val voicePanelFinalizeRunnable = Runnable { finalizeVoicePanelCommand() }

    private fun resetVoicePanelTimeout() {
        mainHandler.removeCallbacks(voicePanelTimeoutRunnable)
        mainHandler.postDelayed(voicePanelTimeoutRunnable, VOICE_PANEL_TIMEOUT_MILLIS)
    }

    private fun startVoicePanelListening() {
        if (voicePanel == null || (pendingVoiceConfirmation == null &&
                (voicePanelCommandDispatched || feedbackPromptVisible))) return
        if (voicePanelRecognizer != null) return
        val generation = ++voiceRecognitionGeneration
        val speechRecognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(voicePanelRecognitionListener(generation))
            }
        }.getOrElse {
            showVoicePanelFailure(R.string.voice_unavailable)
            return
        }
        voicePanelRecognizer = speechRecognizer
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                VOICE_COMPLETE_SILENCE_MILLIS,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                VOICE_POSSIBLY_COMPLETE_SILENCE_MILLIS,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                VOICE_MINIMUM_LENGTH_MILLIS,
            )
        }
        runCatching { speechRecognizer.startListening(intent) }
            .onFailure { showVoicePanelFailure(R.string.voice_unavailable) }
    }

    private fun voicePanelRecognitionListener(generation: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (generation != voiceRecognitionGeneration || pendingVoiceConfirmation != null) return
            if (voicePanelAccumulatedCommand.isBlank()) {
                voicePanelTranscript?.setText(R.string.live_voice_listening)
            }
        }

        override fun onBeginningOfSpeech() {
            if (generation != voiceRecognitionGeneration || pendingVoiceConfirmation != null) return
            voicePanelWaitingForContinuation = false
            mainHandler.removeCallbacks(voicePanelFinalizeRunnable)
            resetVoicePanelTimeout()
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) {
            if (generation != voiceRecognitionGeneration || pendingVoiceConfirmation != null) return
            mainHandler.removeCallbacks(voicePanelFinalizeRunnable)
            resetVoicePanelTimeout()
            updateVoicePanelText(partialResults)
        }

        override fun onResults(results: Bundle?) {
            if (generation != voiceRecognitionGeneration) return
            if (pendingVoiceConfirmation != null) {
                handleVoiceConfirmation(firstRecognizedText(results).orEmpty())
                return
            }
            val segment = firstRecognizedText(results)
            if (segment == null) {
                if (voicePanelAccumulatedCommand.isNotBlank()) {
                    finalizeVoicePanelCommand()
                    return
                }
                showVoicePanelFailure(R.string.voice_no_result)
                return
            }
            voicePanelAccumulatedCommand = mergeVoiceSegments(
                voicePanelAccumulatedCommand,
                segment,
            )
            voicePanelTranscript?.text = voicePanelAccumulatedCommand
            voicePanelWaitingForContinuation = true
            stopVoicePanelRecognizer()
            mainHandler.postDelayed(::startVoicePanelListening, VOICE_CONTINUATION_RESTART_MILLIS)
            mainHandler.removeCallbacks(voicePanelFinalizeRunnable)
            mainHandler.postDelayed(
                voicePanelFinalizeRunnable,
                VOICE_CONTINUATION_GRACE_MILLIS,
            )
        }

        override fun onError(error: Int) {
            if (generation != voiceRecognitionGeneration) return
            if (pendingVoiceConfirmation != null) {
                stopVoicePanelRecognizer()
                if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    mainHandler.postDelayed(::startVoicePanelListening, 1_000)
                }
                return
            }
            if (voicePanelCommandDispatched) return
            if (voicePanelWaitingForContinuation) return
            if (voicePanelAccumulatedCommand.isNotBlank()) {
                finalizeVoicePanelCommand()
            } else {
                showVoicePanelFailure(R.string.voice_no_result)
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun updateVoicePanelText(results: Bundle?) {
        firstRecognizedText(results)?.let { partial ->
            voicePanelTranscript?.text = mergeVoiceSegments(
                voicePanelAccumulatedCommand,
                partial,
            )
        }
    }

    private fun mergeVoiceSegments(accumulated: String, segment: String): String {
        val previous = accumulated.trim()
        val current = segment.trim()
        return when {
            previous.isBlank() -> current
            current.isBlank() -> previous
            current.startsWith(previous, ignoreCase = true) -> current
            previous.endsWith(current, ignoreCase = true) -> previous
            else -> "$previous $current"
        }.take(1_000)
    }

    private fun finalizeVoicePanelCommand() {
        if (voicePanelCommandDispatched || voicePanel == null) return
        val command = voicePanelAccumulatedCommand.trim()
        if (command.isBlank()) {
            showVoicePanelFailure(R.string.voice_no_result)
            return
        }
        voicePanelCommandDispatched = true
        voicePanelWaitingForContinuation = false
        if (!beginCommandControl()) return
        mainHandler.removeCallbacks(voicePanelTimeoutRunnable)
        mainHandler.removeCallbacks(voicePanelFinalizeRunnable)
        voicePanelTranscript?.text = command
        val capturedFromOverlay = voicePanelFromOverlay
        stopVoicePanelRecognizer()
        showVoicePanelWorking("말씀을 들었어요. 작업을 시작하고 있어요…")
        val generation = overlayCommandGeneration
        mainHandler.postDelayed({
            if (generation == overlayCommandGeneration && commandControlActive) {
                processOverlayCommand(command, capturedFromOverlay)
            }
        }, VOICE_PANEL_RESULT_DELAY_MILLIS)
    }

    private fun firstRecognizedText(results: Bundle?): String? = results
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.take(500)

    private fun showVoicePanelFailure(message: Int) {
        val panel = voicePanel ?: return
        feedbackPromptVisible = false
        panel.findViewById<View>(R.id.feedbackActions).visibility = View.GONE
        voicePanelCommandDispatched = true
        stopVoicePanelRecognizer()
        voicePanelTranscript?.setText(message)
        panel.findViewById<TextView>(R.id.cancelVoiceCommand).apply {
            setText(R.string.voice_retry)
            setOnClickListener {
                retryVoicePanel()
            }
        }
    }

    private fun retryVoicePanel() {
        if (voicePanel == null) return
        invalidateOverlayCapture()
        dismissVoicePanel(resumeWakeWord = false)
        mainHandler.post { requestVoiceWake() }
    }

    private fun dismissVoicePanelFromOutside(cancelBaemin: Boolean) {
        if (autonomySession?.userHandoff != null || pendingUserInterventionStore.read() != null) {
            dismissVoicePanel(resumeWakeWord = true)
            return
        }
        overlayCommandGeneration += 1
        overlayOpenAiPlanner.cancelPending()
        autonomySession = null
        invalidateOverlayCapture()
        clearProactiveSearch()
        clearPendingOverlayContext()
        dismissVoicePanel(resumeWakeWord = false)
        if (cancelBaemin) cancelBaeminOrder()
        resumeWakeWordListening()
    }

    private fun dismissVoicePanel(resumeWakeWord: Boolean) {
        clearVoiceConfirmation()
        endCommandControl()
        mainHandler.removeCallbacks(voicePanelTimeoutRunnable)
        mainHandler.removeCallbacks(voicePanelFinalizeRunnable)
        stopVoicePanelRecognizer()
        stopExplanationSpeech()
        voicePanelConfirmButton = null
        feedbackPromptVisible = false
        voicePanelWaitingForContinuation = false
        val panel = voicePanel
        voicePanel = null
        voicePanelTranscript = null
        voicePanelFromOverlay = false
        panel?.let { runCatching { overlayWindowManager?.removeView(it) } }
        if (!overlayCommandExecutionActive) quickVoiceButton?.visibility = View.VISIBLE
        if (resumeWakeWord) resumeWakeWordListening()
    }

    private fun stopVoicePanelRecognizer() {
        voiceRecognitionGeneration++
        voicePanelRecognizer?.runCatching {
            cancel()
            destroy()
        }
        voicePanelRecognizer = null
    }

    private fun processOverlayCommand(command: String, fromOverlay: Boolean) {
        if (voicePanel == null || overlayCommandExecutionActive) return
        if (!beginCommandControl()) return
        val pendingGoal = autonomySession?.takeIf { it.userHandoff != null }?.finalGoal ?: pendingUserInterventionStore.read()
        if (pendingGoal != null && command != pendingGoal) {
            if (isUserResumeCommand(command)) {
                userResumeRequested = true
                processOverlayCommand(pendingGoal, fromOverlay)
                return
            }
            pendingUserInterventionStore.clear()
            autonomySession = null
            userResumeRequested = false
            mainHandler.removeCallbacks(userHandoffObservation)
        }
        if (baeminOrderSession != null) {
            endCommandControl()
            voicePanelTranscript?.text =
                "배민 주문 보조가 진행 중이에요. 새 명령은 현재 주문 보조를 중단한 뒤 요청해 주세요."
            return
        }
        if (proactiveSearchCommand != null && proactiveSearchCommand != command) {
            clearProactiveSearch()
            autonomySession = null
        }
        if (fromOverlay && overlayCaptureInProgress) {
            overlayVoiceCommand = command
            voicePanelTranscript?.text = "말씀을 들었어요. 현재 화면을 확인하고 있어요…"
            return
        }
        val generation = ++overlayCommandGeneration
        activeFeedbackCommand = command
        activeFeedbackApproach = ""
        overlayOpenAiPlanner.cancelPending()
        showVoicePanelWorking("현재 화면에서 안전한 실행 방법을 확인하고 있어요…")

        val hospitalRequest = com.hwanghj09.sonju.agent.HospitalReservationWorkflow.supports(command)
        debugTrace("request purpose=${com.hwanghj09.sonju.task.RequestInterpreter.understand(command).purpose} " +
            "hospital=${com.hwanghj09.sonju.agent.HospitalReservationWorkflow.siteFor(command)?.id}")
        val explanationRequest = ScreenExplainer.isExplanationRequest(command)
        val context = if (fromOverlay) consumePendingOverlayContext() else null
        if (fromOverlay && context == null) {
            if (pendingGoal == command) {
                userResumeRequested = false
                showUserHandoff(command)
                return
            }
            finishAutonomyAttempt()
            showOverlayMessage(getString(R.string.overlay_context_expired))
            return
        }
        val snapshot = context?.snapshot ?: currentApplicationPlaceholder()
        if (!fromOverlay) {
            startOverlayCaptureForCommand(command)
            return
        }
        activeFeedbackSnapshot = snapshot
        if (explanationRequest && snapshot.userIntervention == com.hwanghj09.sonju.agent.UserIntervention.Kind.DEVICE_UNLOCK) {
            finishAutonomyAttempt()
            deliverScreenExplanation("휴대폰 잠금을 직접 해제한 다음 화면 설명을 요청해 주세요.")
            return
        }
        if (explanationRequest) {
            val appLabel = runCatching {
                @Suppress("DEPRECATION")
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(snapshot.packageName, 0),
                ).toString()
            }.getOrDefault(snapshot.windowTitle.orEmpty())
            val browserUrl = ScreenExplainer.detectBrowserUrl(snapshot)
            val fallback = ScreenExplainer.explain(command, appLabel, snapshot, browserUrl)
            if (ScreenExplainer.pageReadingExplanation(command, snapshot) != null) {
                debugTrace("screen explanation observed source=${if (snapshot.localReadOnlyText.isEmpty()) "accessibility" else "local_ocr"}")
                deliverScreenExplanation(fallback)
                return
            }
            if (ScreenExplainer.needsScreenshotFallback(command, snapshot) &&
                overlayOpenAiPlanner.isConfigured && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                EssentialSafetyPolicy.allowsRemoteScreenshot(snapshot)
            ) {
                voicePanelTranscript?.text = "접근성 정보에서 찾지 못해 화면을 보고 확인하고 있어요…"
                // A current-screen question can re-observe content that settled after app return.
                // Screenshot authorization is still checked against that fresh immutable read.
                captureLiveSnapshotAsync(executionGeneration) { live, _, _ ->
                    if (generation != overlayCommandGeneration || voicePanel == null) return@captureLiveSnapshotAsync
                    if (live == null || live.packageName != snapshot.packageName || live.windowId != snapshot.windowId ||
                        !EssentialSafetyPolicy.allowsRemoteScreenshot(live)) {
                        deliverScreenExplanation(fallback)
                        return@captureLiveSnapshotAsync
                    }
                    captureScreenshotAsync(live) { frame ->
                        if (generation != overlayCommandGeneration || voicePanel == null) return@captureScreenshotAsync
                        if (frame == null) {
                            deliverScreenExplanation(fallback)
                            return@captureScreenshotAsync
                        }
                        overlayOpenAiPlanner.analyzeScreenshotAsync(
                            command = command,
                            screenshotJpegBase64 = frame.jpegBase64,
                            question = true,
                        ) { result ->
                            mainHandler.post {
                                if (generation != overlayCommandGeneration || voicePanel == null) return@post
                                val explanation = result.getOrNull()?.explanation?.takeIf(String::isNotBlank)
                                if (explanation != null) debugTrace("screen explanation observed source=visual")
                                deliverScreenExplanation(explanation ?: fallback)
                            }
                        }
                    }
                }
                return
            }
            val semanticMap = context?.semanticMapJpegBase64?.takeIf(String::isNotBlank)
            if (semanticMap != null && overlayOpenAiPlanner.isConfigured) {
                voicePanelTranscript?.text = "민감 정보를 뺀 화면 구조로 사용법을 설명하고 있어요…"
                overlayOpenAiPlanner.explainScreenAsync(
                    command,
                    snapshot,
                    semanticMap,
                    browserUrl,
                ) { result ->
                    mainHandler.post {
                        if (generation != overlayCommandGeneration || voicePanel == null) return@post
                        deliverScreenExplanation(result.getOrDefault(fallback))
                    }
                }
            } else {
                deliverScreenExplanation(fallback)
            }
            return
        }
        val session = autonomySession
            ?.takeIf { it.finalGoal == command }
            ?: AutonomySession(
                finalGoal = command,
                initialSnapshot = snapshot,
                startedAtMillis = SystemClock.elapsedRealtime(),
            ).also { autonomySession = it }
        if (session.userHandoff != null || pendingUserInterventionStore.read() == command) {
            if (session.userHandoff == null) pendingUserInterventionStore.restore(snapshot)?.let {
                session.restoreUserHandoff(it, SystemClock.elapsedRealtime())
            }
            val resumed = userResumeRequested && session.resumeAfterUser(snapshot, SystemClock.elapsedRealtime())
            userResumeRequested = false
            if (!resumed) {
                showUserHandoff(command)
                return
            }
            pendingUserInterventionStore.clear()
            mainHandler.removeCallbacks(userHandoffObservation)
            debugTrace("user intervention resumed automatic=false originalGoal=true")
        }
        userResumeRequested = false
        session.observe(snapshot)
        if (!session.canContinue(SystemClock.elapsedRealtime())) {
            val reason = session.stopReason(SystemClock.elapsedRealtime())
            debugTrace("session stopped tools=${session.toolCallCount} modelCalls=${session.modelCallCount} reason=$reason")
            finishAutonomyAttempt()
            deliverScreenExplanation(reason ?: "현재 요청을 이어갈 수 없어 멈췄어요.")
            return
        }
        // All human checkpoints share the same local path, before cached actions or model calls.
        com.hwanghj09.sonju.agent.UserIntervention.plan(command, snapshot)?.let { handoff ->
            val checkpoint = (if (snapshot.userIntervention == com.hwanghj09.sonju.agent.UserIntervention.Kind.DEVICE_UNLOCK) null
                else architectureRuntime.fastPathPlan(command, snapshot, session))
                ?.takeIf { it.actions.singleOrNull { action -> action.type != ActionType.FINISH }?.type == ActionType.WAIT_FOR_USER }
                ?.let { it.copy(actions = it.actions.filterNot { action -> action.type == ActionType.FINISH }) }
            handleOverlayPlan(command, snapshot, checkpoint ?: handoff)
            return
        }
        if (hospitalRequest && session.modelCallCount > 0) {
            val workflow = com.hwanghj09.sonju.agent.HospitalReservationWorkflow
            workflow.completionPlan(command, snapshot)?.let {
                handleOverlayPlan(command, snapshot, it)
                return
            }
        }
        val fastPathPlan = architectureRuntime.fastPathPlan(command, snapshot, session)
        if (fastPathPlan != null) {
            handleOverlayPlan(command, snapshot, fastPathPlan)
            return
        }
        if (session.visualFallbackActive && tryVisualCommandFallback(command, snapshot)) return
        if (ScreenContextHandoff.hasUnobservedRenderedContent(snapshot) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && EssentialSafetyPolicy.allowsRemoteScreenshot(snapshot)) {
            captureLiveSnapshotAsync(executionGeneration, requireStableRevision = false) { live, _, _ ->
                if (generation != overlayCommandGeneration || voicePanel == null) return@captureLiveSnapshotAsync
                if (live != null && session.recordUnobservedRenderedContent(snapshot, live) &&
                    tryVisualCommandFallback(command, live)) {
                    debugTrace("renderer content missing in two live observations; native toolbar is not page content")
                } else requestModelPlan(command, live ?: snapshot)
            }
            return
        }
        requestModelPlan(command, snapshot)
    }

    private fun requestModelPlan(command: String, snapshot: UiSnapshot, screenshot: ScreenshotFrame? = null) {
        val session = autonomySession?.takeIf { it.finalGoal == command } ?: return
        val generation = overlayCommandGeneration
        if (!overlayOpenAiPlanner.isConfigured) {
            finishAutonomyAttempt()
            showOverlayMessage(getString(R.string.api_missing))
            return
        }

        val learnedRouteHint = learnedRouteMemory.recallHint(
            goal = command,
            snapshot = snapshot,
            targetApp = session.latestPlan?.targetApp,
        )

        if (!session.reserveModelCall(SystemClock.elapsedRealtime())) {
            finishAutonomyAttempt()
            deliverScreenExplanation("AI 호출 또는 실행 한도에 도달해 멈췄어요.")
            return
        }
        val callback: (Result<AgentPlan>) -> Unit = { result ->
            mainHandler.post {
                if (generation != overlayCommandGeneration || voicePanel == null) return@post
                result.fold(
                    onSuccess = { plan ->
                        val reused = architectureRuntime.reuseSuggestedSkill(command, plan, snapshot, session)
                        handleOverlayPlan(command, snapshot, reused ?: plan)
                    },
                    onFailure = {
                        debugTrace("model failure type=${it.javaClass.simpleName} reason=" +
                            (it.message?.takeIf { message -> message.startsWith("OpenAI ") }?.take(160) ?: "plan_or_transport"))
                        if (!retryTransientPlanning(command)) {
                            val madeProgress = autonomySession
                                ?.takeIf { session -> session.finalGoal == command }
                                ?.successfulActions()
                                ?.isNotEmpty() == true
                            finishAutonomyAttempt()
                            debugTrace("command failed source=OPENAI_STRUCTURE failure=PLANNING_FAILED")
                            if (madeProgress) {
                                deliverScreenExplanation(
                                    "요청을 진행했지만 마지막 화면에서 완료 상태를 확인하지 못해 멈췄어요. " +
                                        "현재 앱 화면을 확인해 주세요.",
                                )
                            } else {
                                showOverlayMessage(getString(R.string.generic_error))
                            }
                        }
                    },
                )
            }
        }
        debugTrace("model request mode=${if (screenshot == null) "accessibility" else "visual"} call=${session.modelCallCount}")
        val plannerContext = session.plannerContext(snapshot, learnedRouteHint) + "\n" +
            architectureRuntime.skillPlannerContext(command, snapshot, session)
        if (screenshot != null) {
            val imageContext = plannerContext + "\n첨부 원본 이미지 전체 크기: ${screenshot.width}px × ${screenshot.height}px."
            overlayOpenAiPlanner.planScreenshotAsync(command, snapshot, screenshot.jpegBase64, imageContext, callback,
                session.failedClickPaths(snapshot))
        } else {
            session.recordAccessibilityModelAttempt(snapshot)
            overlayOpenAiPlanner.planAsync(command, snapshot, null,
                userFeedbackMemory.guidance(command, snapshot.packageName), plannerContext,
                session.failedClickPaths(snapshot), callback)
        }
    }

    private fun handleOverlayPlan(command: String, snapshot: UiSnapshot, candidate: AgentPlan,
                                  completionObserved: Boolean = false) {
        // A toast can arrive while the model is planning without changing any UI node.
        if (withToastFeedback(snapshot).hasNewToastSince(snapshot)) {
            autonomySession?.let(architectureRuntime::requestToastReview)
            if (!scheduleProactiveReplan(command, "앱의 새 안내를 확인하고 있어요…")) {
                finishAutonomyAttempt()
                deliverScreenExplanation("앱에서 새 안내가 나타나 현재 결과를 다시 확인해야 해요.")
            }
            return
        }
        if (candidate.goalCompleted && candidate.actions.none { it.type != ActionType.FINISH } &&
            !completionObserved) {
            val commandGeneration = overlayCommandGeneration
            captureLiveSnapshotAsync(executionGeneration, requireStableRevision = false) { live, _, _ ->
                if (commandGeneration != overlayCommandGeneration || voicePanel == null) return@captureLiveSnapshotAsync
                if (live != null) {
                    if (live.hasNewToastSince(snapshot)) handleOverlayPlan(command, snapshot, candidate)
                    else handleOverlayPlan(command, live, candidate, completionObserved = true)
                } else if (!scheduleProactiveReplan(command, "마지막 결과를 다시 확인하고 있어요…")) {
                    finishAutonomyAttempt()
                    deliverScreenExplanation("마지막 결과를 화면에서 확인할 수 없어 멈췄어요.")
                }
            }
            return
        }
        if (candidate.actions.filterNot { it.type == ActionType.FINISH }.singleOrNull()?.type == ActionType.WAIT_FOR_USER) {
            val session = autonomySession?.takeIf { it.finalGoal == command }
            val returnPackage = if (com.hwanghj09.sonju.agent.UserIntervention.required(snapshot, command) ==
                com.hwanghj09.sonju.agent.UserIntervention.Kind.BIOMETRIC) {
                windows.asSequence().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .mapNotNull { it.root?.packageName?.toString() }
                    .filter { it != snapshot.packageName && it != packageName }.distinct().singleOrNull()
            } else null
            if (candidate.source in setOf(PlanSource.LOCAL_RULE, PlanSource.SKILL_FAST_PATH) &&
                session?.pauseForUser(candidate, snapshot, SystemClock.elapsedRealtime(), returnPackage) == true) {
                pendingUserInterventionStore.save(command, requireNotNull(session.userHandoff))
                clearProactiveSearch()
                overlayOpenAiPlanner.cancelPending()
                debugTrace("user intervention paused kind=${session.userHandoff?.kind} originalGoal=true")
                showUserHandoff(command)
                mainHandler.removeCallbacks(userHandoffObservation)
                mainHandler.postDelayed(userHandoffObservation, AutonomySession.USER_RESUME_SETTLE_MILLIS)
            } else {
                finishAutonomyAttempt()
                deliverScreenExplanation("직접 확인이 필요한 화면을 확인하지 못해 멈췄어요.")
            }
            return
        }
        if (candidate.visualFrameHash != null && !candidate.visualFrameVerified) {
            val generation = overlayCommandGeneration
            captureScreenshotAsync(snapshot) { frame ->
                if (generation != overlayCommandGeneration || voicePanel == null) return@captureScreenshotAsync
                if (frame != null && com.hwanghj09.sonju.agent.visualFrameHash(frame.jpegBase64) == candidate.visualFrameHash) {
                    handleOverlayPlan(command, snapshot, candidate.copy(visualFrameVerified = true))
                } else {
                    autonomySession?.let { architectureRuntime.recordPlanningFailure(candidate, it,
                        "시각 경로의 화면이 달라져 현재 화면부터 다시 계획합니다.") }
                    if (!scheduleProactiveReplan(command, "화면이 달라져 실행 경로를 다시 확인하고 있어요…")) {
                        finishAutonomyAttempt()
                        deliverScreenExplanation("현재 화면을 검증할 수 없어 멈췄어요.")
                    }
                }
            }
            return
        }
        val plan = autonomySession?.takeIf { it.finalGoal == command }
            ?.acceptPlan(candidate, snapshot)
            ?: candidate
        transientPlanningRetryCount = 0
        activeFeedbackCommand = command
        activeFeedbackSnapshot = snapshot
        activeFeedbackApproach = plan.summary
        if (plan.goalCompleted && plan.actions.none { it.type != ActionType.FINISH }) {
            if (!architectureRuntime.goalSatisfied(command, plan, snapshot, autonomySession)) {
                debugTrace("goal evidence rejected checks=${plan.goalChecks.size} matched=${plan.goalChecks.count { it.matches(snapshot) }}")
                val failures = plan.goalChecks.mapIndexedNotNull { index, check ->
                    if (check.matches(snapshot)) null else {
                        val node = check.resolveNode(snapshot)
                        val detail = when {
                            node == null -> "selector가 현재 화면에서 없거나 중복됩니다. 유일한 현재 path를 사용하세요."
                            check.checked != null && !node.checkable -> "일반 노드에는 checked=null이어야 합니다."
                            check.checked != null && node.checked != check.checked -> "실제 checked 상태가 다릅니다."
                            else -> "text 불일치. 실제 관찰 값: ${listOfNotNull(node.text, node.contentDescription, node.stateDescription).filter(String::isNotBlank).joinToString(" | ").take(200)}"
                        }
                        debugTrace("goal check rejected index=$index resolved=${node != null} checkable=${node?.checkable} hasChecked=${check.checked != null}")
                        "근거 $index (${check.selector.take(160)}): $detail"
                    }
                }.joinToString("\n").take(1600)
                autonomySession?.let {
                    architectureRuntime.recordPlanningFailure(plan, it,
                        architectureRuntime.completionRejectionReason(command, plan, snapshot) + "\n$failures", snapshot)
                }
                if (!scheduleProactiveReplan(
                        command,
                        "완료 선언을 실제 화면에서 확인하지 못해 다시 관찰하고 있어요…",
                    )
                ) {
                    finishAutonomyAttempt()
                    deliverScreenExplanation("최종 목표 상태를 화면에서 확인하지 못해 완료로 처리하지 않았어요.")
                }
                return
            }
            autonomySession?.takeIf { it.finalGoal == command }?.let { session ->
                debugTrace("goal verified source=${plan.source} tools=${session.toolCallCount} modelCalls=${session.modelCallCount}")
                if (BuildConfig.DEBUG) session.history.forEachIndexed { index, trace ->
                    debugTrace("skill trace index=$index action=${trace.action.type} ok=${trace.succeeded} " +
                        "before=${trace.beforeFingerprint} after=${trace.afterFingerprint} " +
                        "entry=${trace.beforeTemplateFingerprint} exit=${trace.afterTemplateFingerprint} " +
                        "inputBound=${trace.action.value?.let(command::contains) ?: true}")
                }
                if (!com.hwanghj09.sonju.agent.HospitalReservationWorkflow.matches(command)) {
                    learnedRouteMemory.remember(session, plan)
                }
                architectureRuntime.rememberSuccessfulSkill(command, session, snapshot)
            }
            val privateReadout = com.hwanghj09.sonju.agent.HospitalReservationWorkflow
                .takeIf { it.matches(command) }?.resultText(snapshot, command)
            autonomySession = null
            clearProactiveSearch()
            if (privateReadout != null) {
                pendingUserInterventionStore.clear()
                // Medical contents are displayed only here, outside feedback/history persistence.
                activeFeedbackSnapshot = null
                activeFeedbackApproach = ""
                voicePanelTranscript?.apply {
                    maxHeight = dp(320)
                    movementMethod = android.text.method.ScrollingMovementMethod()
                }
                voicePanel?.findViewById<TextView>(R.id.cancelVoiceCommand)?.apply {
                    text = "닫기"
                    setOnClickListener { dismissVoicePanel(resumeWakeWord = true) }
                }
                deliverScreenExplanation(privateReadout)
                return
            }
            showFeedbackPrompt(
                command = command,
                snapshot = snapshot,
                completed = true,
                message = "최종 목표를 화면에서 확인했어요. " +
                    plan.summary.ifBlank { "요청한 상태에 도달했습니다." },
                approach = plan.summary,
            )
            return
        }
        when (val verification = architectureRuntime.verify(command, plan, snapshot)) {
            is VerificationResult.Blocked,
            is VerificationResult.NeedsReplan -> {
                val reason = when (verification) {
                    is VerificationResult.Blocked -> verification.reason
                    is VerificationResult.NeedsReplan -> verification.reason
                    else -> error("unreachable")
                }
                debugTrace(
                    "verification=${verification::class.simpleName} source=${plan.source} " +
                        "action=${plan.actions.firstOrNull()?.type} reason=${reason.take(160)}",
                )
                val hasNoAction = plan.actions.none { it.type != ActionType.FINISH }
                val session = autonomySession
                if (session != null && (verification is VerificationResult.NeedsReplan || hasNoAction)) {
                    val targetMissing = (verification as? VerificationResult.NeedsReplan)?.accessibilityTargetMissing == true
                    val recoveryReason = if (hasNoAction) architectureRuntime.completionRejectionReason(command, plan, snapshot)
                        else reason
                    val noStructuredTarget = hasNoAction && plan.source == PlanSource.OPENAI_STRUCTURE && !plan.goalCompleted
                    architectureRuntime.recordPlanningFailure(plan, session, recoveryReason, snapshot,
                        accessibilityFailure = targetMissing || noStructuredTarget)
                    if (session.visualFallbackActive && tryVisualCommandFallback(command, snapshot)) return
                    if (scheduleProactiveReplan(command, "현재 화면부터 AI가 다른 실행 경로를 찾고 있어요…")) return
                }
                val recoverableTargetFailure = plan.actions.any { action ->
                    action.type == ActionType.CLICK
                } && reason.containsAny(
                    "찾지 못",
                    "확인하지 못",
                    "정확히 검증하지 못",
                    "식별",
                    "target not found",
                    "semantic target",
                )
                if (autonomySession?.visualFallbackActive == true && (hasNoAction || recoverableTargetFailure) &&
                    tryVisualCommandFallback(command, snapshot)
                ) return
                if ((hasNoAction || recoverableTargetFailure) &&
                    attemptExploratoryScroll(command, snapshot)
                ) return

                val attempts = proactiveSearchStepCount
                if (hasNoAction || attempts > 0) {
                    finishAutonomyAttempt()
                    val message = if (attempts > 0) {
                        "화면을 ${attempts}번 더 이동하고 캡처도 확인했지만 요청한 항목을 " +
                            "찾지 못했어요. $reason"
                    } else {
                        "접근성 구조와 의미 노드 배치도를 확인했지만 요청한 항목을 찾지 못했어요. " +
                            reason
                    }
                    deliverScreenExplanation(message)
                } else {
                    finishAutonomyAttempt()
                    showOverlayMessage(reason)
                }
            }
            is VerificationResult.Allowed -> {
                val verifiedAction = verification.verifiedPlan.actions.values.singleOrNull()
                val session = autonomySession
                val repeatedFailure = verifiedAction?.let {
                    session?.repeatedActionFailure(it.action, snapshot, it.resolvedNodeId)
                }
                if (session != null && repeatedFailure != null) {
                    architectureRuntime.recordPlanningFailure(plan, session, repeatedFailure, snapshot)
                    debugTrace("repeated action prevented source=${plan.source} action=${verifiedAction?.action?.type}")
                    if (scheduleProactiveReplan(command, "같은 실패를 반복하지 않고 다른 방법을 찾고 있어요…")) return
                    finishAutonomyAttempt()
                    deliverScreenExplanation(repeatedFailure)
                    return
                }
                debugTrace(
                    "verification=Allowed source=${plan.source} action=${verifiedAction?.action?.type} " +
                        "resolved=${verifiedAction?.resolvedNodeId.orEmpty()}",
                )
                executeOverlayPlan(command, snapshot, verification.verifiedPlan)
            }
            is VerificationResult.NeedsConfirmation -> showPlanConfirmation(
                command, snapshot, plan, verification,
            )
        }
    }

    private fun attemptExploratoryScroll(command: String, snapshot: UiSnapshot): Boolean {
        if (proactiveSearchStepCount >= MAX_PROACTIVE_SEARCH_STEPS ||
            uniqueLeafScrollablePath(snapshot) == null
        ) {
            return false
        }
        val plan = AgentPlan(
            goal = command,
            summary = "화면 아래쪽에서 요청한 항목을 더 찾아볼게요.",
            modelRisk = RiskLevel.LOW,
            confidence = 1.0,
            actions = listOf(
                AgentAction(ActionType.SCROLL_DOWN, "화면 아래쪽을 더 확인합니다."),
                AgentAction(ActionType.FINISH, "새 화면을 다시 확인합니다."),
            ),
            source = PlanSource.LOCAL_RULE,
            continueAfterAction = true,
        )
        handleOverlayPlan(command, snapshot, plan)
        return true
    }

    private fun tryVisualCommandFallback(command: String, snapshot: UiSnapshot): Boolean {
        val generation = overlayCommandGeneration
        val session = autonomySession?.takeIf { it.finalGoal == command } ?: return false
        val screenshotAllowed = EssentialSafetyPolicy.allowsRemoteScreenshot(snapshot)
        val policyAllowed = architectureRuntime.shouldUseVisualFallback(
            snapshot,
            groundingFailed = session.visualFallbackActive,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            !overlayOpenAiPlanner.isConfigured || !screenshotAllowed || !policyAllowed ||
            snapshot.treeTruncated || ScreenContextHandoff.hasVisibleLoadingIndicator(snapshot) ||
            !session.canAttemptVisualFallback(snapshot)
        ) {
            debugTrace(
                "visual fallback skipped sdk=${Build.VERSION.SDK_INT} " +
                    "configured=${overlayOpenAiPlanner.isConfigured} " +
                    "screenshotAllowed=$screenshotAllowed policyAllowed=$policyAllowed " +
                    "sensitive=${snapshot.elements.count { it.visible && it.sensitive }}",
            )
            return false
        }
        debugTrace("visual fallback started package=${snapshot.packageName}")
        voicePanelTranscript?.text = "접근성 정보에서 찾지 못해 화면을 보고 찾고 있어요…"
        captureScreenshotAsync(snapshot) { frame ->
            if (generation != overlayCommandGeneration || voicePanel == null) return@captureScreenshotAsync
            if (frame == null) {
                debugTrace("visual fallback capture failed")
                requestModelPlan(command, snapshot)
                return@captureScreenshotAsync
            }
            if (!session.reserveVisualFallback(snapshot, com.hwanghj09.sonju.agent.visualFrameHash(frame.jpegBase64))) {
                debugTrace("visual fallback unchanged frame or budget exhausted")
                session.requestAiRecovery("이미 분석한 동일 이미지입니다. 같은 화면의 다른 접근성 경로나 도구를 선택해야 합니다.")
                requestModelPlan(command, snapshot)
                return@captureScreenshotAsync
            }
            requestModelPlan(command, snapshot, frame)
        }
        return true
    }

    private fun clearProactiveSearch() {
        proactiveSearchCommand = null
        proactiveSearchStepCount = 0
        transientPlanningRetryCount = 0
    }

    private fun finishAutonomyAttempt() {
        endCommandControl()
        pendingUserInterventionStore.clear()
        userResumeRequested = false
        mainHandler.removeCallbacks(userHandoffObservation)
        autonomySession = null
        clearProactiveSearch()
    }

    private fun currentApplicationPlaceholder(): UiSnapshot {
        val activePackage = bestAvailableApplicationRoot()?.packageName?.toString().orEmpty()
            .takeUnless { it == packageName }
            .orEmpty()
        return UiSnapshot.empty(epoch.get()).copy(
            packageName = activePackage.ifBlank { "unknown" },
        )
    }

    private fun startOverlayCaptureForCommand(command: String) {
        if (overlayCaptureInProgress) {
            overlayVoiceCommand = command
            voicePanelTranscript?.text = "말씀을 들었어요. 현재 화면을 확인하고 있어요…"
            return
        }
        overlayVoiceCommand = command
        overlayCaptureInProgress = true
        voicePanelTranscript?.text = "현재 화면을 확인하고 있어요…"
        captureOverlayContextAndLaunch(
            attempt = 0,
            captureGeneration = ++overlayCaptureGeneration,
        )
    }

    private fun retryTransientPlanning(
        command: String,
        maxRetries: Int = MAX_TRANSIENT_PLANNING_RETRIES,
    ): Boolean {
        if (transientPlanningRetryCount >= maxRetries) return false
        invalidateObservedSnapshot()
        val loadingDelay = TRANSIENT_LOADING_RETRY_DELAYS_MILLIS[transientPlanningRetryCount]
        val scheduled = scheduleProactiveReplan(
            command,
            "화면 분석 응답이 불완전해 현재 화면을 다시 캡처하고 있어요…",
            loadingDelay,
        )
        debugTrace(
            "planning retry=${transientPlanningRetryCount + 1}/$maxRetries " +
                "delayMs=$loadingDelay scheduled=$scheduled",
        )
        if (scheduled) transientPlanningRetryCount += 1
        return scheduled
    }

    private fun scheduleProactiveReplan(
        command: String,
        status: String,
        delayMillis: Long = PROACTIVE_SEARCH_SETTLE_MILLIS,
    ): Boolean {
        if (proactiveSearchStepCount >= MAX_PROACTIVE_SEARCH_STEPS) return false
        proactiveSearchCommand = command
        proactiveSearchStepCount += 1
        voicePanelTranscript?.text = status
        val generation = overlayCommandGeneration
        mainHandler.postDelayed(
            {
                if (generation == overlayCommandGeneration && commandControlActive &&
                    proactiveSearchCommand == command) requestVoiceWake(command)
            },
            delayMillis,
        )
        return true
    }

    private fun String.containsAny(vararg values: String): Boolean =
        values.any { contains(it, ignoreCase = true) }

    private fun showPlanConfirmation(
        command: String,
        snapshot: UiSnapshot,
        plan: AgentPlan,
        verification: VerificationResult.NeedsConfirmation,
    ) {
        val appLabel = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(snapshot.packageName, 0)).toString()
        }.getOrDefault("현재 앱")
        val message = buildString {
            appendLine("요청: ${com.hwanghj09.sonju.logging.RedactionPolicy.redact(command).orEmpty().take(180)}")
            appendLine("앱: $appLabel")
            plan.actions.filterNot { it.type == ActionType.FINISH }.forEach { action ->
                appendLine(com.hwanghj09.sonju.logging.RedactionPolicy.redact(action.description).orEmpty().take(180))
                action.value?.let { appendLine("입력할 내용: ${com.hwanghj09.sonju.logging.RedactionPolicy.redact(it).orEmpty().take(100)}") }
            }
            append(verification.reason)
        }.take(650)
        showVoicePanelConfirmation(message, getString(R.string.confirm_execute)) {
            when (val confirmed = architectureRuntime.verify(
                command = command,
                plan = plan,
                snapshot = snapshot,
                userConfirmed = true,
            )) {
                is VerificationResult.Allowed -> executeOverlayPlan(
                    command,
                    snapshot,
                    confirmed.verifiedPlan,
                )
                is VerificationResult.Blocked -> showOverlayMessage(confirmed.reason)
                is VerificationResult.NeedsReplan -> showOverlayMessage(confirmed.reason)
                is VerificationResult.NeedsConfirmation ->
                    showOverlayMessage("확인을 실행 권한으로 변환하지 못해 안전하게 멈췄어요.")
            }
        }
    }

    private fun showUserHandoff(command: String) {
        prepareVoicePanelNotice()
        val handoff = autonomySession?.userHandoff
        val guide = com.hwanghj09.sonju.agent.UserIntervention.guide(handoff?.kind
            ?: com.hwanghj09.sonju.agent.UserIntervention.Kind.OTHER)
        showVoicePanelWorking(guide)
        showVoicePanelConfirmation(guide, "완료했어요 · 계속", listenForReply = false) {
            userResumeRequested = true
            invalidateObservedSnapshot()
            processOverlayCommand(command, fromOverlay = false)
        }
        quickVoiceButton?.visibility = View.VISIBLE

    }

    private fun resumeUserHandoffIfReady(snapshot: UiSnapshot) {
        val session = autonomySession?.takeIf { it.userHandoff != null } ?: return
        if (overlayCaptureInProgress || overlayCommandExecutionActive ||
            (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked) return
        if (windows.any { it.isFocused && it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                it.root?.packageName?.toString() != snapshot.packageName }) return
        if (!session.resumeAfterUser(snapshot, SystemClock.elapsedRealtime(), userConfirmed = false)) return
        pendingUserInterventionStore.clear()
        mainHandler.removeCallbacks(userHandoffObservation)
        debugTrace("user intervention resumed automatic=true originalGoal=true")
        // Re-capture through the normal command path; human completion never grants execution rights.
        userResumeRequested = true
        invalidateObservedSnapshot()
        routeOverlayVoice(fromOverlay = false, voiceCommand = session.finalGoal)
    }

    private fun isUserResumeCommand(command: String): Boolean = command.trim().trimEnd('.', '!', '?') in
        setOf("로그인 완료", "인증 완료", "확인 완료", "완료했어요", "다 했어요", "계속", "계속해 줘")

    private fun showVoicePanelConfirmation(
        message: String,
        confirmLabel: String,
        listenForReply: Boolean = true,
        onConfirm: () -> Unit,
    ) {
        prepareVoicePanelNotice()
        val panel = voicePanel as? LinearLayout ?: return
        voiceConfirmationMessage = if (listenForReply) {
            "$message\n진행하려면 ‘진행해’, 취소하려면 ‘취소해’라고 말씀해 주세요."
        } else message
        voicePanelTranscript?.text = voiceConfirmationMessage
        pendingVoiceConfirmation = onConfirm.takeIf { listenForReply }
        if (listenForReply) mainHandler.postDelayed(voiceConfirmationTimeout, 120_000)
        voicePanelConfirmButton?.let { existing ->
            (existing.parent as? LinearLayout)?.removeView(existing)
        }
        val confirm = TextView(this).apply {
            text = confirmLabel
            contentDescription = confirmLabel
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(ContextCompat.getColor(this@SonjuAccessibilityService, R.color.sonju_green))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48),
            ).apply { gravity = Gravity.END }
            setOnClickListener {
                if (listenForReply) confirmPendingVoiceAction()
                else if (isEnabled) {
                    isEnabled = false
                    onConfirm()
                }
            }
        }
        voicePanelConfirmButton = confirm
        panel.addView(confirm)
        speakExplanation(voiceConfirmationMessage)
    }

    private fun clearVoiceConfirmation() {
        pendingVoiceConfirmation = null
        voiceConfirmationMessage = ""
        mainHandler.removeCallbacks(voiceConfirmationTimeout)
        stopVoicePanelRecognizer()
    }

    private fun confirmPendingVoiceAction() {
        val action = pendingVoiceConfirmation ?: return
        clearVoiceConfirmation()
        voicePanelConfirmButton?.isEnabled = false
        action()
    }

    private fun handleVoiceConfirmation(text: String) {
        stopVoicePanelRecognizer()
        when (com.hwanghj09.sonju.voice.VoiceControl.confirmationReply(text)) {
            com.hwanghj09.sonju.voice.VoiceControl.Reply.CONFIRM -> confirmPendingVoiceAction()
            com.hwanghj09.sonju.voice.VoiceControl.Reply.CANCEL -> {
                clearVoiceConfirmation()
                forceStopFromVoicePanel()
            }
            com.hwanghj09.sonju.voice.VoiceControl.Reply.UNKNOWN -> {
                voicePanelTranscript?.text = voiceConfirmationMessage
                speakExplanation("잘 듣지 못했어요. 진행하려면 진행해, 취소하려면 취소해라고 말씀해 주세요.")
            }
        }
    }

    private fun executeOverlayPlan(
        command: String,
        snapshot: UiSnapshot,
        verifiedPlan: VerifiedPlan,
    ) {
        if (!beginCommandControl()) return
        val commandGeneration = overlayCommandGeneration
        val plan = verifiedPlan.plan
        overlayCommandExecutionActive = true
        showVoicePanelWorking(
            plan.summary.ifBlank { "요청한 동작을 실행하고 있어요…" },
        )
        quickVoiceButton?.visibility = View.GONE
        executePlan(
            verifiedPlan = verifiedPlan,
            expectedSnapshot = snapshot,
            returnToPreviousApp = false,
        ) { result ->
            if (commandGeneration != overlayCommandGeneration) return@executePlan
            overlayCommandExecutionActive = false
            quickVoiceButton?.visibility = View.VISIBLE
            debugTrace(
                "execution result success=${result.success} steps=${result.completedSteps} " +
                    "postcondition=${result.postconditionSatisfied} " +
                    "failure=${result.failureReason}",
            )
            autonomySession?.takeIf { it.finalGoal == command }
                ?.recordExecution(plan, snapshot, result, verifiedPlan.actions.values.singleOrNull()?.resolvedNodeId)
            architectureRuntime.recordExecution(command, verifiedPlan, result, autonomySession)
            if (result.success &&
                (plan.continueAfterAction || shouldVerifyGoalAfterAction(plan))
            ) {
                val sessionCanContinue = autonomySession?.canContinue(
                    SystemClock.elapsedRealtime(),
                ) != false
                if (proactiveSearchStepCount >= MAX_PROACTIVE_SEARCH_STEPS || !sessionCanContinue) {
                    val attempts = proactiveSearchStepCount
                    autonomySession = null
                    clearProactiveSearch()
                    deliverScreenExplanation(
                        "화면을 ${attempts}번 확인했지만 실행 한도 안에서 완료 조건을 확인하지 못해 멈췄어요.",
                    )
                    return@executePlan
                }
                proactiveSearchCommand = command
                mainHandler.postDelayed(
                    {
                        if (commandGeneration == overlayCommandGeneration && commandControlActive &&
                            proactiveSearchCommand == command) requestVoiceWake(command)
                    },
                    PROACTIVE_SEARCH_SETTLE_MILLIS,
                )
                return@executePlan
            }
            if (!result.success && shouldReplanAfterExecutionFailure(plan, result) &&
                scheduleProactiveReplan(
                    command,
                    "버튼 위치나 화면이 달라져 캡처와 화면 구조를 다시 확인하고 있어요…",
                )
            ) {
                return@executePlan
            }
            if (result.success) {
                autonomySession?.takeIf { it.finalGoal == command }?.let { session ->
                    learnedRouteMemory.remember(session, plan)
                    if (result.postconditionSatisfied == true) {
                        debugTrace("local postcondition verified source=${plan.source} tools=${session.toolCallCount} modelCalls=${session.modelCallCount}")
                    }
                }
            }
            autonomySession = null
            clearProactiveSearch()
            val message = if (result.success) {
                "완료됐어요. ${plan.summary.ifBlank { getString(R.string.action_completed) }}"
            } else {
                result.message.ifBlank { getString(R.string.action_failed) }
            }
            if (!result.success) {
                debugTrace("command failed source=${plan.source} failure=${result.failureReason}")
                deliverScreenExplanation(message)
                return@executePlan
            }
            showFeedbackPrompt(
                command = command,
                snapshot = snapshot,
                completed = true,
                message = message,
                approach = plan.summary,
            )
        }
    }

    private fun showVoicePanelWorking(message: String) {
        clearVoiceConfirmation()
        feedbackPromptVisible = false
        voicePanel?.findViewById<View>(R.id.feedbackActions)?.visibility = View.GONE
        voicePanelTranscript?.text = message.take(1_000)
        voicePanelConfirmButton?.let { existing ->
            (existing.parent as? LinearLayout)?.removeView(existing)
        }
        voicePanelConfirmButton = null
        voicePanel?.findViewById<TextView>(R.id.cancelVoiceCommand)?.apply {
            text = "중단"
            isEnabled = true
            alpha = 1f
            setOnClickListener { forceStopFromVoicePanel() }
        }
    }

    private fun forceStopFromVoicePanel() {
        debugTrace("user stop requested")
        val command = activeFeedbackCommand.orEmpty()
        val snapshot = activeFeedbackSnapshot ?: UiSnapshot.empty()
        val approach = activeFeedbackApproach
        overlayCommandGeneration += 1
        overlayOpenAiPlanner.cancelPending()
        pendingUserInterventionStore.clear()
        userResumeRequested = false
        mainHandler.removeCallbacks(userHandoffObservation)
        autonomySession = null
        stopCurrentExecution()
        mainHandler.post {
            if (voicePanel == null) return@post
            showFeedbackPrompt(
                command = command,
                snapshot = snapshot,
                completed = false,
                message = "작업을 중단했어요.",
                approach = approach,
            )
        }
    }

    private fun showFeedbackPrompt(
        command: String,
        snapshot: UiSnapshot,
        completed: Boolean,
        message: String,
        approach: String,
    ) {
        prepareVoicePanelNotice()
        val panel = voicePanel
        if (panel == null) {
            Toast.makeText(this, message.take(500), Toast.LENGTH_LONG).show()
            resumeWakeWordListening()
            return
        }
        clearProactiveSearch()
        activeFeedbackCommand = command
        activeFeedbackSnapshot = snapshot
        activeFeedbackApproach = approach
        feedbackPromptVisible = true
        voicePanelCommandDispatched = false
        voicePanelTranscript?.text = "$message\n이 처리 방식은 어땠나요?"
        panel.findViewById<View>(R.id.feedbackActions).visibility = View.VISIBLE
        panel.findViewById<TextView>(R.id.feedbackGood).setOnClickListener {
            submitFeedback(positive = true, completed = completed)
        }
        panel.findViewById<TextView>(R.id.feedbackBad).setOnClickListener {
            submitFeedback(positive = false, completed = completed)
        }
        panel.findViewById<TextView>(R.id.cancelVoiceCommand).apply {
            setText(R.string.feedback_close)
            isEnabled = true
            alpha = 1f
            setOnClickListener { dismissVoicePanel(resumeWakeWord = true) }
        }
        speakExplanation(message)

    }

    private fun submitFeedback(positive: Boolean, completed: Boolean) {
        stopExplanationSpeech()
        val command = activeFeedbackCommand.orEmpty()
        val snapshot = activeFeedbackSnapshot ?: UiSnapshot.empty()
        userFeedbackMemory.record(
            command = command,
            packageName = snapshot.packageName,
            completed = completed,
            positive = positive,
            approach = activeFeedbackApproach,
        )
        feedbackPromptVisible = false
        voicePanel?.findViewById<View>(R.id.feedbackActions)?.visibility = View.GONE
        voicePanelTranscript?.text = if (positive) {
            "좋다 평가를 저장했어요. 다음 비슷한 요청에 참고할게요."
        } else {
            "안 좋다 평가를 저장했어요. 다음에는 다른 방법을 찾을게요."
        }

    }

    private fun shouldVerifyGoalAfterAction(plan: AgentPlan): Boolean {
        if (plan.source !in setOf(
                PlanSource.OPENAI_STRUCTURE,
                PlanSource.OPENAI_SEMANTIC_MAP,
            )
        ) return false
        val action = plan.actions.singleOrNull { it.type != ActionType.FINISH } ?: return false
        return action.type !in setOf(ActionType.WAIT, ActionType.FINISH)
    }

    private fun shouldReplanAfterExecutionFailure(
        plan: AgentPlan,
        result: ExecutionResult,
    ): Boolean {
        if (result.failureReason == ExecutionFailureReason.DEVICE_LOCKED) return true
        // A dispatched timer may already be running even when its UI cannot be read. Never create
        // another timer as recovery; the user receives the unverified result instead.
        if (plan.actions.any { it.type == ActionType.START_TIMER }) return false
        if (result.failureReason == ExecutionFailureReason.POSTCONDITION_TIMEOUT) return true
        if (result.message.containsAny(
                "사용자 요청",
                "실행 시간이 길어져",
                "완료로 처리하지 않았습니다",
            )
        ) return false
        val action = plan.actions.singleOrNull { it.type != ActionType.FINISH } ?: return false
        return action.type in setOf(
            ActionType.CLICK,
            ActionType.CLICK_COORDINATE,
            ActionType.SET_TEXT,
            ActionType.SUBMIT_TEXT,
            ActionType.SCROLL_DOWN,
            ActionType.SCROLL_UP,
            ActionType.SCROLL_LEFT,
            ActionType.SCROLL_RIGHT,
        ) || result.message.containsAny(
            "화면이 바뀌어",
            "화면 구조를 다시 확인할 수 없어",
            "화면 요소를 확실히 찾지 못해",
        )
    }

    private fun showOverlayMessage(message: String) {
        prepareVoicePanelNotice()
        val panel = voicePanel
        if (panel == null) {
            Toast.makeText(this, message.take(500), Toast.LENGTH_LONG).show()
            resumeWakeWordListening()
            return
        }
        feedbackPromptVisible = false
        panel.findViewById<View>(R.id.feedbackActions).visibility = View.GONE
        voicePanelTranscript?.text = message.take(1_000)
        panel.findViewById<TextView>(R.id.cancelVoiceCommand)?.apply {
            text = "닫기"
            isEnabled = true
            alpha = 1f
            setOnClickListener { dismissVoicePanel(resumeWakeWord = true) }
        }
        voicePanelConfirmButton?.let { existing ->
            (existing.parent as? LinearLayout)?.removeView(existing)
        }
        voicePanelConfirmButton = null
        showVoicePanelConfirmation(message.take(1_000), getString(R.string.voice_retry)) { retryVoicePanel() }
    }

    private fun deliverScreenExplanation(explanation: String) {
        prepareVoicePanelNotice()
        voicePanel?.findViewById<TextView>(R.id.cancelVoiceCommand)?.apply {
            text = "닫기"
            isEnabled = true
            setOnClickListener { dismissVoicePanel(resumeWakeWord = true) }
        }
        voicePanelTranscript?.text = explanation
        speakExplanation(explanation)
    }

    private fun prepareVoicePanelNotice() {
        clearVoiceConfirmation()
        endCommandControl()
        voicePanelCommandDispatched = true
        stopVoicePanelRecognizer()
        stopExplanationSpeech()
        mainHandler.removeCallbacks(voicePanelTimeoutRunnable)
        mainHandler.removeCallbacks(voicePanelFinalizeRunnable)
    }

    private fun pauseWakeWordListening() {
        sendWakeWordAction(WakeWordService.ACTION_PAUSE)
    }

    private fun resumeWakeWordListening() {
        sendWakeWordAction(WakeWordService.ACTION_RESUME)
    }

    private fun sendWakeWordAction(action: String) {
        if (!WakeWordService.running) return
        runCatching {
            startService(Intent(this, WakeWordService::class.java).setAction(action))
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentEvent = event ?: return
        val eventPackage = currentEvent.packageName?.toString().orEmpty()
        val eventClass = currentEvent.className?.toString().orEmpty()
        if (currentEvent.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            // Toast events have no source node; the text must be read from the event itself.
            // Ordinary notifications and background apps are not part of this observation.
            val foregroundPackage = bestAvailableApplicationRoot()?.packageName?.toString()
            debugTrace("transient event package=$eventPackage class=$eventClass foreground=$foregroundPackage " +
                "textCount=${currentEvent.text.size} hasNotification=${currentEvent.parcelableData != null}")
            if (eventClass == Toast::class.java.name && currentEvent.parcelableData == null &&
                eventPackage != packageName && eventPackage.isNotBlank() &&
                SystemClock.uptimeMillis() - currentEvent.eventTime in 0..ToastFeedback.MAX_AGE_MILLIS &&
                getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != true &&
                foregroundPackage == eventPackage) {
                val text = currentEvent.text.take(8).joinToString("\n") { it.toString().take(2_000) }
                if (toastFeedback.record(eventPackage, text, SystemClock.elapsedRealtime())) {
                    eventMonitor.onRevision(epoch.incrementAndGet())
                    debugTrace("toast observed package=$eventPackage")
                }
            }
            // Feedback invalidates old plans, but does not create nodes or prove a UI transition.
            return
        }
        if (currentEvent.eventType in setOf(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            )
        ) {
            updateQuickVoiceVisibilityForForegroundApp()
        }
        val currentWindows = windows
        val eventWindowType = currentWindows.firstOrNull { it.id == currentEvent.windowId }?.type
        val isTargetRevision = eventPackage.isNotBlank() && eventPackage != packageName &&
            (eventWindowType == AccessibilityWindowInfo.TYPE_APPLICATION ||
                eventWindowType == null && currentWindows.asSequence()
                    .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .mapNotNull(AccessibilityWindowInfo::getRoot)
                    .any { it.packageName?.toString() == eventPackage })
        // Only application-window events revise the executable target screen. Accessibility
        // overlays and status-bar/system windows can emit continuously without changing it.
        val eventEpoch = if (isTargetRevision) {
            epoch.incrementAndGet()
        } else {
            epoch.get()
        }
        if (isTargetRevision) {
            eventMonitor.onRevision(eventEpoch)
        }
        val applicationWindowChanged = isTargetRevision &&
            currentEvent.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            (eventPackage != lastObservedWindowPackage || eventClass != lastObservedWindowClass)
        if (applicationWindowChanged) {
            // A new application window invalidates queued and cached work from the previous one.
            observedWindowGeneration += 1
            lastObservedApplicationSnapshot = null
            lastObservedSnapshotCaptureAtElapsedRealtime = 0L
            lastObservedWindowPackage = eventPackage
            lastObservedWindowClass = eventClass
        }

        trustedSettingsContext?.let { context ->
            if (!ContextLifetime.isFresh(
                    SystemClock.elapsedRealtime(),
                    context.armedAtElapsedRealtime,
                    TRUSTED_SETTINGS_ROUTE_TTL_MILLIS,
                )
            ) {
                clearTrustedSettingsContext()
            } else if (currentEvent.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                eventPackage.isNotBlank() && eventPackage != SETTINGS_PACKAGE &&
                eventPackage != packageName
            ) {
                // Trust is valid only for an uninterrupted Settings -> Sonju -> Settings flow.
                clearTrustedSettingsContext()
            } else if (eventPackage == SETTINGS_PACKAGE &&
                currentEvent.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            ) {
                if (!isExpectedSettingsActivity(context.route, eventClass)) {
                    clearTrustedSettingsContext()
                } else {
                    val establishedClass = context.establishedActivityClass
                    if (establishedClass == null) {
                        context.establishedActivityClass = eventClass
                    } else if (establishedClass != eventClass) {
                        clearTrustedSettingsContext()
                    }
                }
            }
        }

        // Passive observation must never queue ahead of a user-command capture. Dynamic apps can
        // keep a tree read busy long enough for the verified action node to become stale.
        if (executionActive || overlayCaptureInProgress || overlayCommandExecutionActive) return

        val now = SystemClock.elapsedRealtime()
        val captureImmediately = applicationWindowChanged || currentEvent.eventType in setOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        )
        val refreshDue = now - lastObservedSnapshotCaptureAtElapsedRealtime >=
            OBSERVED_SNAPSHOT_REFRESH_MILLIS
        val shouldCaptureSnapshot = eventPackage.isNotBlank() && eventPackage != packageName &&
            (lastObservedApplicationSnapshot == null || captureImmediately || refreshDue)
        if (!shouldCaptureSnapshot) return
        val eventRoot = rootInActiveWindow
            ?: bestAvailableApplicationRoot()
            ?: rootFromEventSource(currentEvent)
            ?: return
        enqueueSnapshotCapture(
            SnapshotCaptureRequest(
                root = eventRoot,
                expectedPackage = eventPackage,
                snapshotEpoch = eventEpoch,
                windowGeneration = observedWindowGeneration,
            ),
        )
    }

    private fun enqueueSnapshotCapture(request: SnapshotCaptureRequest) {
        pendingSnapshotCapture = request
        drainSnapshotCaptureQueue()
    }

    private fun drainSnapshotCaptureQueue() {
        if (snapshotCaptureInFlight) return
        val request = pendingSnapshotCapture ?: return
        pendingSnapshotCapture = null
        snapshotCaptureInFlight = true
        val captureGeneration = ++observedSnapshotCaptureGeneration
        runCatching {
            observedSnapshotFuture = snapshotExecutor.submit {
                val snapshot = runCatching {
                    UiTreeReader.snapshot(
                        request.root,
                        request.snapshotEpoch,
                        currentDisplayBounds(),
                    )
                }.getOrNull().takeUnless { Thread.currentThread().isInterrupted }
                mainHandler.post {
                    if (instance !== this ||
                        captureGeneration != observedSnapshotCaptureGeneration
                    ) return@post
                    observedSnapshotFuture = null
                    snapshotCaptureInFlight = false
                    val accepted = snapshot != null &&
                        snapshot.epoch == epoch.get() &&
                        request.windowGeneration == observedWindowGeneration &&
                        snapshot.packageName == request.expectedPackage &&
                        snapshot.packageName != "unknown"
                    if (accepted) {
                        acceptObservedSnapshot(snapshot!!)
                        // Requests queued during this traversal cannot be newer: acceptance above
                        // proves this snapshot already matches the current epoch and window.
                        pendingSnapshotCapture = null
                    }
                    if (overlayCaptureInProgress) {
                        // A voice command arrived while this passive read was already running.
                        // Use its accepted result when possible instead of reading the same
                        // accessibility tree concurrently from the command executor.
                        pendingSnapshotCapture = null
                        captureOverlayContextAndLaunch(0, overlayCaptureGeneration)
                        return@post
                    }
                    drainSnapshotCaptureQueue()
                }
            }
        }.onFailure {
            observedSnapshotFuture = null
            snapshotCaptureInFlight = false
        }
    }

    private fun acceptObservedSnapshot(snapshot: UiSnapshot) {
        var accepted = snapshot
        trustedSettingsContext?.let { context ->
            val fresh = ContextLifetime.isFresh(
                SystemClock.elapsedRealtime(),
                context.armedAtElapsedRealtime,
                TRUSTED_SETTINGS_ROUTE_TTL_MILLIS,
            )
            if (!fresh) {
                clearTrustedSettingsContext()
            } else if (snapshot.packageName == SETTINGS_PACKAGE &&
                !context.establishedActivityClass.isNullOrBlank()
            ) {
                val title = snapshot.windowTitle.orEmpty()
                if (!isExpectedSettingsTitle(context.route, title)) {
                    clearTrustedSettingsContext()
                } else {
                    val establishedTitle = context.establishedWindowTitle
                    if (establishedTitle == null) {
                        context.establishedWindowTitle = title
                    } else if (establishedTitle != title) {
                        clearTrustedSettingsContext()
                    }
                    if (trustedSettingsContext === context) {
                        accepted = snapshot.copy(trustedSettingsRoute = context.route)
                    }
                }
            }
        }
        cacheObservedApplicationSnapshot(accepted)
        resumeUserHandoffIfReady(accepted)
        advanceBaeminOrder(accepted)
    }

    override fun onInterrupt() {
        if (voicePanel != null && (commandControlActive || executionActive || overlayCaptureInProgress)) {
            forceStopFromVoicePanel()
        } else {
            stopCurrentExecution()
        }
    }

    override fun onDestroy() {
        toastFeedback.clear()
        stopCurrentExecution(preserveUserHandoff = true)
        overlayCommandGeneration += 1
        overlayOpenAiPlanner.close()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsReady = false
        pendingSpeech = null
        dismissVoicePanel(resumeWakeWord = false)
        hideTouchIndicator()
        hideControlGlow()
        hideQuickVoiceButton()
        eventMonitor.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        pendingSnapshotCapture = null
        observedSnapshotCaptureGeneration += 1
        observedSnapshotFuture?.cancel(true)
        observedSnapshotFuture = null
        snapshotExecutor.shutdownNow()
        clearPendingOverlayContext()
        cancelBaeminOrder()
        clearTrustedSettingsContext()
        lastObservedApplicationSnapshot = null
        lastObservedSnapshotCaptureAtElapsedRealtime = 0L
        lastObservedWindowPackage = null
        lastObservedWindowClass = null
        snapshotCaptureInFlight = false
        invalidateOverlayCapture()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun bestAvailableApplicationRoot(): AccessibilityNodeInfo? {
        val currentWindows = windows
        prefetchedActiveRoot()?.let { direct ->
            val directPackage = direct.packageName?.toString().orEmpty()
            val directWindowType = currentWindows.firstOrNull { it.id == direct.windowId }?.type
            val belongsToAccessibilityOverlay = directWindowType ==
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                directWindowType == null && currentWindows.asSequence()
                    .filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                    .mapNotNull(AccessibilityWindowInfo::getRoot)
                    .any { it.packageName?.toString() == directPackage }
            if (directPackage.isNotBlank() &&
                !belongsToAccessibilityOverlay
            ) {
                return direct
            }
        }
        return currentWindows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { it.isActive }
                    .thenByDescending { it.isFocused }
                    .thenByDescending { it.layer },
            )
            .mapNotNull(::prefetchedWindowRoot)
            .firstOrNull { !it.packageName.isNullOrBlank() }
    }

    private fun prefetchedActiveRoot(): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getRootInActiveWindow(
                AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or
                    AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE,
            )
        } else {
            rootInActiveWindow
        }

    private fun prefetchedWindowRoot(window: AccessibilityWindowInfo): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            window.getRoot(
                AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or
                    AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE,
            )
        } else {
            window.root
        }

    private fun rootFromEventSource(event: AccessibilityEvent): AccessibilityNodeInfo? {
        var node = event.source ?: return null
        repeat(MAX_EVENT_ANCESTOR_DEPTH) {
            val parent = node.parent ?: return node
            node = parent
        }
        return null
    }

    private fun snapshotWithTrustedRoute(rawSnapshot: UiSnapshot): UiSnapshot {
        val snapshot = withToastFeedback(snapshotWithEditorAction(rawSnapshot))
        if (getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) {
            return snapshot.copy(trustedSettingsRoute = null,
                recentToasts = emptyList(),
                userIntervention = com.hwanghj09.sonju.agent.UserIntervention.Kind.DEVICE_UNLOCK)
        }
        // Route provenance is capability-like data. Never trust a route carried by an old cache;
        // attach it again only while the matching, freshly established Settings context is live.
        val untrustedSnapshot = if (snapshot.trustedSettingsRoute == null) {
            snapshot
        } else {
            snapshot.copy(trustedSettingsRoute = null)
        }
        val context = trustedSettingsContext ?: return untrustedSnapshot
        val fresh = ContextLifetime.isFresh(
            SystemClock.elapsedRealtime(),
            context.armedAtElapsedRealtime,
            TRUSTED_SETTINGS_ROUTE_TTL_MILLIS,
        )
        val activityEstablished = !context.establishedActivityClass.isNullOrBlank()
        val title = snapshot.windowTitle.orEmpty()
        val titleAllowed = isExpectedSettingsTitle(context.route, title)
        if (fresh && activityEstablished && snapshot.packageName == SETTINGS_PACKAGE &&
            titleAllowed
        ) {
            val establishedTitle = context.establishedWindowTitle
            if (establishedTitle == null) {
                context.establishedWindowTitle = title
                return untrustedSnapshot.copy(trustedSettingsRoute = context.route)
            }
            if (establishedTitle == title) {
                return untrustedSnapshot.copy(trustedSettingsRoute = context.route)
            }
        }
        if (!fresh) clearTrustedSettingsContext()
        return untrustedSnapshot
    }

    private fun withToastFeedback(snapshot: UiSnapshot): UiSnapshot = snapshot.copy(recentToasts =
        if (snapshot.userIntervention != null || snapshot.elements.any { it.visible && it.sensitive } ||
            getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) emptyList()
        else toastFeedback.recent(snapshot.packageName, SystemClock.elapsedRealtime()))

    private fun snapshotWithEditorAction(snapshot: UiSnapshot): UiSnapshot {
        val clean = snapshot.copy(elements = snapshot.elements.map { it.copy(imeAction = null) })
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return clean
        val field = clean.elements.singleOrNull { it.focused && it.editable && it.visible &&
            it.enabled && !it.sensitive } ?: return clean
        val root = bestAvailableApplicationRoot()?.takeIf { it.windowId == clean.windowId &&
            it.packageName?.toString() == clean.packageName } ?: return clean
        val input = nodeAtPath(root, field.path) ?: return clean
        val editor = matchedInputConnection(input, clean.packageName, field.text.orEmpty()) ?: return clean
        val action = editor.first.imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION
        return clean.copy(elements = clean.elements.map { if (it.path == field.path) it.copy(imeAction = action) else it })
    }

    private fun clearTrustedSettingsContext() {
        trustedSettingsContext = null
        lastObservedApplicationSnapshot = lastObservedApplicationSnapshot?.let { observed ->
            if (observed.snapshot.trustedSettingsRoute == null) {
                observed
            } else {
                observed.copy(snapshot = observed.snapshot.copy(trustedSettingsRoute = null))
            }
        }
    }

    private fun invalidateObservedSnapshot() {
        observedWindowGeneration += 1
        pendingSnapshotCapture = null
        lastObservedApplicationSnapshot = null
        lastObservedSnapshotCaptureAtElapsedRealtime = 0L
    }

    private fun scheduleObservedSnapshotRefresh(delayMillis: Long = POST_EXECUTION_REFRESH_MILLIS) {
        mainHandler.postDelayed(
            {
                if (instance === this && !executionActive) {
                    requestCurrentApplicationSnapshot()
                }
            },
            delayMillis,
        )
    }

    private fun requestCurrentApplicationSnapshot() {
        val root = bestAvailableApplicationRoot() ?: return
        val currentPackage = root.packageName?.toString().orEmpty()
        if (currentPackage.isBlank() || currentPackage == packageName) return
        enqueueSnapshotCapture(
            SnapshotCaptureRequest(
                root = root,
                expectedPackage = currentPackage,
                snapshotEpoch = epoch.get(),
                windowGeneration = observedWindowGeneration,
            ),
        )
    }

    private fun cacheObservedApplicationSnapshot(snapshot: UiSnapshot) {
        if (snapshot.packageName == "unknown" || snapshot.packageName == packageName) return
        lastObservedApplicationSnapshot = ObservedApplicationSnapshot(
            snapshot = snapshot,
            capturedAtElapsedRealtime = SystemClock.elapsedRealtime(),
        )
        lastObservedSnapshotCaptureAtElapsedRealtime = SystemClock.elapsedRealtime()
    }

    /** Supplies Sonju's activity with the last immutable, redacted application tree it observed. */
    fun recentApplicationContext(): PendingOverlayContext? {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Recent application context must be requested on the main thread"
        }
        val observed = lastObservedApplicationSnapshot ?: return null
        val now = SystemClock.elapsedRealtime()
        if (!ScreenContextHandoff.isReusable(
                snapshot = observed.snapshot,
                ownPackageName = packageName,
                nowElapsedRealtime = now,
                capturedAtElapsedRealtime = observed.capturedAtElapsedRealtime,
                ttlMillis = OBSERVED_SNAPSHOT_TTL_MILLIS,
            )
        ) {
            if (!ContextLifetime.isFresh(
                    now,
                    observed.capturedAtElapsedRealtime,
                    OBSERVED_SNAPSHOT_TTL_MILLIS,
                )
            ) {
                lastObservedApplicationSnapshot = null
            }
            return null
        }
        return PendingOverlayContext(
            snapshot = snapshotWithTrustedRoute(observed.snapshot),
            semanticMapJpegBase64 = null,
            capturedAtElapsedRealtime = observed.capturedAtElapsedRealtime,
            sessionId = overlaySession.incrementAndGet(),
        )
    }

    /**
     * Builds a low-cost visual representation from already-redacted semantic nodes. It never reads
     * raw screen pixels, so Canvas/WebView content that Android did not expose cannot leak here.
     */
    private fun renderSanitizedSemanticMap(snapshot: UiSnapshot): String? = runCatching {
        val signals = snapshot.elements.filter { element ->
            element.visible && !element.sensitive &&
                (element.clickable || element.editable || element.scrollable ||
                    !element.text.isNullOrBlank() || !element.contentDescription.isNullOrBlank())
        }
        if (signals.isEmpty()) return@runCatching null

        val display = currentDisplayBounds()
        val width = 480
        val height = (width * (display.bottom.toFloat() / display.right.coerceAtLeast(1)))
            .toInt()
            .coerceIn(480, 960)
        val scaleX = width.toFloat() / display.right.coerceAtLeast(1)
        val scaleY = height.toFloat() / display.bottom.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.rgb(21, 95, 73)
            }
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(28, 38, 34)
                textSize = 14f
            }
            signals.take(24).forEach { element ->
                val rect = RectF(
                    element.bounds.left * scaleX,
                    element.bounds.top * scaleY,
                    element.bounds.right * scaleX,
                    element.bounds.bottom * scaleY,
                )
                if (rect.width() < 2f || rect.height() < 2f) return@forEach
                fill.color = when {
                    element.editable -> Color.rgb(230, 241, 255)
                    element.clickable -> Color.rgb(231, 246, 238)
                    element.scrollable -> Color.rgb(255, 244, 224)
                    else -> Color.rgb(246, 247, 246)
                }
                canvas.drawRoundRect(rect, 7f, 7f, fill)
                canvas.drawRoundRect(rect, 7f, 7f, stroke)
                val text = (element.text ?: element.contentDescription
                    ?: element.className.substringAfterLast('.'))
                    .replace('\n', ' ')
                    .take(42)
                canvas.save()
                canvas.clipRect(rect)
                canvas.drawText(text, rect.left + 5f, rect.top + 17f, label)
                canvas.restore()
            }
            val bytes = ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 65, output)
                output.toByteArray()
            }
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

    fun executePlan(
        verifiedPlan: VerifiedPlan,
        expectedSnapshot: UiSnapshot,
        returnToPreviousApp: Boolean = false,
        callback: (ExecutionResult) -> Unit,
    ) {
        val plan = verifiedPlan.plan
        if (getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) {
            callback(ExecutionResult(false, "휴대폰 잠금 해제를 기다립니다.", 0,
                failureReason = ExecutionFailureReason.DEVICE_LOCKED))
            return
        }
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Accessibility execution must start on the main thread"
        }
        if (verifiedPlan.sourceEpoch != expectedSnapshot.epoch ||
            verifiedPlan.sourceFingerprint != expectedSnapshot.screenFingerprint()
        ) {
            callback(
                ExecutionResult(
                    success = false,
                    message = "검증한 화면과 실행 요청이 달라 동작하지 않았습니다.",
                    completedSteps = 0,
                    failureReason = ExecutionFailureReason.ACTION_REJECTED_BY_VERIFIER,
                ),
            )
            return
        }
        if (baeminOrderSession != null) {
            callback(
                ExecutionResult(
                    false,
                    "배민 주문 보조가 진행 중이에요. 먼저 주문 보조를 중단해 주세요.",
                    0,
                ),
            )
            return
        }
        if (activeExecution != null) stopCurrentExecution()
        val generation = ++executionGeneration
        textSubmissionDispatch = null
        verifiedPostconditionGeneration = -1L
        executionActive = true
        lastExecutionMethod = null
        lastDispatchSourceEpoch = null
        // The shield is required for execution. A newly rebound service may not yet have a token.
        if (windows.isNotEmpty()) showControlGlow()
        val startedAt = SystemClock.elapsedRealtime()
        var terminalDelivered = false
        val terminalCallback: (ExecutionResult) -> Unit = terminal@{ result ->
            if (terminalDelivered) return@terminal
            terminalDelivered = true
            if (activeExecution?.generation == generation) activeExecution = null
            if (generation == executionGeneration) {
                textSubmissionDispatch = null
                executionActive = false
                if (!commandControlActive) hideControlGlow()
                // The verifier already captured the exact after-state. Keep that immutable
                // observation for the next planning step; execution still rebinds every target
                // against the live tree. Failed or unverifiable transitions must recapture.
                if (!result.success || verifiedPostconditionGeneration != generation) {
                    invalidateObservedSnapshot()
                    scheduleObservedSnapshotRefresh()
                }
            }
            callback(result)
        }
        activeExecution = ActiveExecution(generation, terminalCallback)
        if (controlGlow == null) {
            terminalCallback(ExecutionResult(false, "화면 잠금을 준비하지 못해 실행하지 않았습니다.", 0))
            return
        }
        if (returnToPreviousApp) {
            if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
                terminalCallback(
                    ExecutionResult(false, "이전 앱으로 돌아가지 못해 안전하게 멈췄습니다.", 0),
                )
                return
            }
            mainHandler.postDelayed(
                {
                    captureLiveSnapshotAsync(generation) { liveSnapshot, _, captureEpoch ->
                        val firstActionIndex = plan.actions.indexOfFirst {
                            it.type != ActionType.FINISH
                        }
                        val firstAction = plan.actions.getOrNull(firstActionIndex)
                        val resumedSnapshot = liveSnapshot?.takeIf { epoch.get() == captureEpoch }
                            ?.let { live ->
                                if (firstAction?.type in setOf(
                                        ActionType.SET_TEXT,
                                        ActionType.SUBMIT_TEXT,
                                    )
                                ) {
                                    ScreenContextHandoff.resumeForTextInput(
                                        expected = expectedSnapshot,
                                        live = live,
                                        editablePath = verifiedPlan.actionAt(firstActionIndex)
                                            ?.resolvedNodeId,
                                    )
                                } else if (firstAction?.type == ActionType.CLICK) {
                                    ScreenContextHandoff.resumeForClick(
                                        expected = expectedSnapshot,
                                        live = live,
                                        clickPath = verifiedPlan.actionAt(firstActionIndex)
                                            ?.resolvedNodeId,
                                    )
                                } else if (firstAction?.type in setOf(
                                        ActionType.SCROLL_DOWN,
                                        ActionType.SCROLL_UP,
                                        ActionType.SCROLL_LEFT,
                                        ActionType.SCROLL_RIGHT,
                                    )
                                ) {
                                    ScreenContextHandoff.resumeForScroll(
                                        expected = expectedSnapshot,
                                        live = live,
                                        scrollPath = verifiedPlan.actionAt(firstActionIndex)
                                            ?.resolvedNodeId,
                                    )
                                } else {
                                    ScreenContextHandoff.resumeOnUnchangedScreen(
                                        expectedSnapshot,
                                        live,
                                    )
                                }
                            }
                        if (resumedSnapshot == null) {
                            val editablePath = verifiedPlan.actionAt(firstActionIndex)?.resolvedNodeId
                            val expectedTarget = expectedSnapshot.elements.singleOrNull {
                                it.path == editablePath
                            }
                            val liveTarget = liveSnapshot?.elements?.singleOrNull {
                                it.path == editablePath
                            }
                            debugTrace(
                                "screen handoff rejected action=${firstAction?.type} " +
                                    "snapshot=${liveSnapshot != null} epochMatch=${epoch.get() == captureEpoch} " +
                                    "packageMatch=${liveSnapshot?.packageName == expectedSnapshot.packageName} " +
                                    "windowMatch=${liveSnapshot?.windowId == expectedSnapshot.windowId} " +
                                    "truncated=${expectedSnapshot.treeTruncated}/${liveSnapshot?.treeTruncated} " +
                                    "target=${expectedTarget != null}/${liveTarget != null} " +
                                    "classMatch=${expectedTarget?.className == liveTarget?.className} " +
                                    "boundsMatch=${expectedTarget?.bounds == liveTarget?.bounds} " +
                                    "textMatch=${expectedTarget?.text == liveTarget?.text}",
                            )
                            terminalCallback(
                                ExecutionResult(
                                    success = false,
                                    message = "직전 화면이 바뀌어 다른 곳을 조작하지 않고 멈췄어요.",
                                    completedSteps = 0,
                                    failureReason = ExecutionFailureReason.ACTION_REJECTED_BY_VERIFIER,
                                ),
                            )
                            return@captureLiveSnapshotAsync
                        }
                        executeStep(
                            verifiedPlan,
                            plan,
                            resumedSnapshot,
                            verifyExpectedScreen = true,
                            index = 0,
                            completedSteps = 0,
                            startedAt = startedAt,
                            generation = generation,
                            callback = terminalCallback,
                        )
                    }
                },
                RETURN_TO_APP_MILLIS,
            )
        } else {
            executeStep(
                verifiedPlan,
                plan,
                expectedSnapshot,
                verifyExpectedScreen = true,
                index = 0,
                completedSteps = 0,
                startedAt = startedAt,
                generation = generation,
                callback = terminalCallback,
            )
        }
    }

    fun stopCurrentExecution(preserveUserHandoff: Boolean = false) {
        overlayCommandGeneration += 1
        overlayOpenAiPlanner.cancelPending()
        mainHandler.removeCallbacks(userHandoffObservation)
        if (!preserveUserHandoff) {
            pendingUserInterventionStore.clear()
            userResumeRequested = false
            autonomySession = null
        }
        val pendingExecution = activeExecution
        activeExecution = null
        executionGeneration += 1
        textSubmissionDispatch = null
        executionActive = false
        eventMonitor.cancel()
        overlayCommandExecutionActive = false
        endCommandControl()
        invalidateOverlayCapture()
        clearProactiveSearch()
        if (baeminOrderSession != null) cancelBaeminOrder()
        clearPendingOverlayContext()
        invalidateObservedSnapshot()
        scheduleObservedSnapshotRefresh()
        pendingExecution?.complete(
            ExecutionResult(false, "사용자 요청으로 안전하게 멈췄습니다.", 0),
        )
    }

    @Deprecated("The command pipeline now uses the app-agnostic autonomous agent")
    fun startBaeminOrder(@Suppress("UNUSED_PARAMETER") query: String): Boolean = false

    fun cancelBaeminOrder() {
        baeminOrderGeneration += 1
        baeminOrderSession = null
        if (!executionActive && !commandControlActive) hideControlGlow()
    }

    private fun advanceBaeminOrder(snapshot: UiSnapshot) {
        val session = baeminOrderSession ?: return
        if (session.actionInFlight ||
            snapshot.packageName != BaeminNavigator.PACKAGE_NAME
        ) return
        if (SystemClock.elapsedRealtime() - session.startedAtElapsedRealtime >
            BAEMIN_SESSION_TIMEOUT_MILLIS || session.completedSteps >= BAEMIN_MAX_STEPS
        ) {
            finishBaeminOrder("주문 보조 단계가 길어져 안전하게 멈췄어요.", success = false)
            return
        }
        when (val action = BaeminNavigator.next(
            snapshot,
            session.query,
            session.completedSteps,
            session.itemAdded,
            session.completionBaseline,
        )) {
            is BaeminScreenAction.Click -> {
                if (action.finalCommit) {
                    val pending = PendingBaeminCommit(
                        generation = session.generation,
                        clickablePath = action.path,
                        completionBaseline = BaeminNavigator.completionState(snapshot),
                    )
                    session.actionInFlight = true
                    performBaeminCommit(session, pending)
                } else {
                    performBaeminAction(
                        snapshot,
                        action.path,
                        BaeminNodeAction.CLICK,
                        null,
                        markItemAdded = action.label.contains("담기"),
                    )
                }
            }

            is BaeminScreenAction.SetSearchText -> performBaeminAction(
                snapshot,
                action.path,
                BaeminNodeAction.SET_TEXT,
                action.value,
                markItemAdded = false,
            )

            is BaeminScreenAction.Scroll -> performBaeminAction(
                snapshot,
                action.path,
                BaeminNodeAction.SCROLL,
                null,
                markItemAdded = false,
            )

            is BaeminScreenAction.Stop -> finishBaeminOrder(action.reason, success = false)
            BaeminScreenAction.Complete -> finishBaeminOrder(
                "배민에서 주문 접수가 확인됐어요.",
                success = true,
            )
            BaeminScreenAction.Wait -> Unit
        }
    }

    private enum class BaeminNodeAction { CLICK, SET_TEXT, SCROLL }

    private fun performBaeminAction(
        expectedSnapshot: UiSnapshot,
        path: String,
        action: BaeminNodeAction,
        value: String?,
        markItemAdded: Boolean,
    ) {
        val session = baeminOrderSession ?: return
        val generation = session.generation
        session.actionInFlight = true
        val root = bestAvailableApplicationRoot()
        if (root == null) {
            session.actionInFlight = false
            finishBaeminOrder("배민 화면을 다시 확인할 수 없어 주문 보조를 멈췄어요.", false)
            return
        }
        val captureEpoch = epoch.get()
        runCatching {
            snapshotExecutor.execute {
                val liveSnapshot = runCatching {
                    UiTreeReader.snapshot(root, captureEpoch, currentDisplayBounds())
                }.getOrNull()
                mainHandler.post {
                    val current = baeminOrderSession?.takeIf { it.generation == generation }
                        ?: return@post
                    val node = if (action == BaeminNodeAction.CLICK) {
                        nodeAtPath(root, path)
                    } else if (epoch.get() == captureEpoch &&
                        liveSnapshot?.packageName == BaeminNavigator.PACKAGE_NAME &&
                        expectedSnapshot.hasSameRevisionAs(liveSnapshot)
                    ) {
                        nodeAtPath(root, path)
                    } else {
                        null
                    }
                    val dispatched = when (action) {
                        BaeminNodeAction.CLICK -> node?.let { clickable ->
                            showTouchIndicator(clickable)
                            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        } == true

                        BaeminNodeAction.SET_TEXT -> node?.takeIf {
                            it.isVisibleToUser && it.isEnabled && it.isEditable && !it.isPassword
                        }?.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    value.orEmpty().take(40),
                                )
                            },
                        ) == true

                        BaeminNodeAction.SCROLL -> node?.takeIf {
                            it.isVisibleToUser && it.isEnabled && it.isScrollable
                        }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
                    }
                    if (!dispatched) {
                        current.actionInFlight = false
                        finishBaeminOrder(
                            "배민 화면 요소에 동작이 전달되지 않아 자동으로 다시 시도하지 않고 멈췄어요.",
                            false,
                        )
                        return@post
                    }
                    if (action == BaeminNodeAction.CLICK) {
                        current.completedSteps += 1
                        if (markItemAdded) current.itemAdded = true
                        current.actionInFlight = false
                        invalidateObservedSnapshot()
                        mainHandler.postDelayed(
                            { captureAndAdvanceBaeminOrder(generation) },
                            BAEMIN_STEP_SETTLE_MILLIS,
                        )
                        return@post
                    }
                    mainHandler.postDelayed(
                        {
                            verifyBaeminActionPostcondition(
                                generation = generation,
                                beforeSnapshot = expectedSnapshot,
                                path = path,
                                action = action,
                                value = value,
                                markItemAdded = markItemAdded,
                            )
                        },
                        BAEMIN_STEP_SETTLE_MILLIS,
                    )
                }
            }
        }.onFailure {
            session.actionInFlight = false
            finishBaeminOrder("배민 화면을 다시 확인할 수 없어 주문 보조를 멈췄어요.", false)
        }
    }

    private fun verifyBaeminActionPostcondition(
        generation: Long,
        beforeSnapshot: UiSnapshot,
        path: String,
        action: BaeminNodeAction,
        value: String?,
        markItemAdded: Boolean,
    ) {
        val session = baeminOrderSession?.takeIf {
            it.generation == generation && it.actionInFlight
        } ?: return
        val root = bestAvailableApplicationRoot()
        if (root == null) {
            session.actionInFlight = false
            finishBaeminOrder("배민 동작 뒤 화면을 확인할 수 없어 완료로 처리하지 않았어요.", false)
            return
        }
        val captureEpoch = epoch.get()
        runCatching {
            snapshotExecutor.execute {
                val afterSnapshot = runCatching {
                    UiTreeReader.snapshot(root, captureEpoch, currentDisplayBounds())
                }.getOrNull()
                mainHandler.post {
                    val current = baeminOrderSession?.takeIf { it.generation == generation }
                        ?: return@post
                    val stable = afterSnapshot != null &&
                        epoch.get() == captureEpoch &&
                        afterSnapshot.packageName == BaeminNavigator.PACKAGE_NAME &&
                        !afterSnapshot.treeTruncated &&
                        afterSnapshot.windowTitle != "[민감 화면]" &&
                        afterSnapshot.elements.none { it.visible && it.sensitive }
                    val verified = stable && when {
                        action == BaeminNodeAction.SET_TEXT -> {
                            val entered = afterSnapshot!!.elements.singleOrNull { element ->
                                element.path == path && element.visible && element.enabled &&
                                    element.editable && !element.sensitive
                            }?.text
                            entered?.let(::normalizeBaeminText) ==
                                value?.let(::normalizeBaeminText)
                        }

                        markItemAdded -> BaeminNavigator.itemAddedPostcondition(
                            beforeSnapshot,
                            afterSnapshot!!,
                        )

                        else -> afterSnapshot!!.epoch > beforeSnapshot.epoch &&
                            !beforeSnapshot.hasSameObservableContentAs(afterSnapshot)
                    }
                    if (!verified) {
                        current.actionInFlight = false
                        finishBaeminOrder(
                            "배민 동작 뒤 요청한 상태 변화를 확인하지 못해 자동으로 다시 시도하지 않고 멈췄어요.",
                            false,
                        )
                        return@post
                    }
                    current.completedSteps += 1
                    if (markItemAdded) current.itemAdded = true
                    current.actionInFlight = false
                    invalidateObservedSnapshot()
                    advanceBaeminOrder(afterSnapshot!!)
                }
            }
        }.onFailure {
            session.actionInFlight = false
            finishBaeminOrder("배민 동작 결과를 안전하게 확인하지 못해 멈췄어요.", false)
        }
    }

    private fun normalizeBaeminText(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).trim()

    private fun captureAndAdvanceBaeminOrder(generation: Long) {
        val session = baeminOrderSession?.takeIf {
            it.generation == generation && !it.actionInFlight
        } ?: return
        val root = bestAvailableApplicationRoot() ?: return
        val captureEpoch = epoch.get()
        runCatching {
            snapshotExecutor.execute {
                val snapshot = runCatching {
                    UiTreeReader.snapshot(root, captureEpoch, currentDisplayBounds())
                }.getOrNull()
                mainHandler.post {
                    if (baeminOrderSession?.generation != generation || snapshot == null ||
                        epoch.get() != captureEpoch
                    ) return@post
                    advanceBaeminOrder(snapshot)
                }
            }
        }.onFailure {
            session.actionInFlight = false
        }
    }

    private fun performBaeminCommit(
        session: BaeminOrderSession,
        pending: PendingBaeminCommit,
    ) {
        if (baeminOrderSession?.generation != session.generation) return
        val root = bestAvailableApplicationRoot()
        if (root == null) {
            session.actionInFlight = false
            finishBaeminOrder("주문 화면으로 돌아가지 못해 마지막 버튼을 누르지 않았어요.", false)
            return
        }
        val node = nodeAtPath(root, pending.clickablePath)
        val dispatched = node?.let { clickable ->
            showTouchIndicator(clickable)
            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } == true
        session.actionInFlight = false
        if (!dispatched) {
            finishBaeminOrder("마지막 주문 버튼에 클릭을 전달하지 못했어요.", false)
            return
        }
        session.completedSteps += 1
        session.completionBaseline = pending.completionBaseline
        invalidateObservedSnapshot()
        mainHandler.postDelayed(
            { captureAndAdvanceBaeminOrder(session.generation) },
            BAEMIN_STEP_SETTLE_MILLIS,
        )
    }

    private fun finishBaeminOrder(message: String, success: Boolean) {
        cancelBaeminOrder()
        if (voicePanel != null) {
            if (success) {
                showFeedbackPrompt(
                    command = activeFeedbackCommand.orEmpty(),
                    snapshot = activeFeedbackSnapshot ?: UiSnapshot.empty().copy(
                        packageName = BaeminNavigator.PACKAGE_NAME,
                    ),
                    completed = true,
                    message = "완료됐어요. $message",
                    approach = activeFeedbackApproach,
                )
            } else {
                showOverlayMessage(message)
            }
        } else {
            Toast.makeText(this, message.take(500), Toast.LENGTH_LONG).show()
            quickVoiceButton?.visibility = View.VISIBLE
            resumeWakeWordListening()
        }
    }

    private fun cancelBaeminWithFeedback() {
        cancelBaeminOrder()
        showFeedbackPrompt(
            command = activeFeedbackCommand.orEmpty(),
            snapshot = activeFeedbackSnapshot ?: UiSnapshot.empty().copy(
                packageName = BaeminNavigator.PACKAGE_NAME,
            ),
            completed = false,
            message = "주문 보조를 중단했어요.",
            approach = activeFeedbackApproach,
        )
    }

    fun requestVoiceWake(command: String? = null) {
        mainHandler.post {
            if (instance === this && command != null &&
                com.hwanghj09.sonju.voice.VoiceControl.isStopRequest(command)) {
                forceStopFromVoicePanel()
                return@post
            }
            if (instance === this && pendingVoiceConfirmation != null && command != null) {
                handleVoiceConfirmation(command)
                return@post
            }
            if (instance !== this || overlayCaptureInProgress || overlayCommandExecutionActive ||
                baeminOrderSession != null
            ) {
                return@post
            }
            val safeCommand = command?.trim()?.takeIf(String::isNotBlank)?.take(1_000)
            val pendingGoal = autonomySession?.takeIf { it.userHandoff != null }?.finalGoal ?: pendingUserInterventionStore.read()
            if (pendingGoal != null && (safeCommand == null || isUserResumeCommand(safeCommand))) {
                if (safeCommand == null) {
                    dismissVoicePanel(resumeWakeWord = false)
                    showVoicePanel(fromOverlay = false)
                } else {
                    if (voicePanel == null) showVoicePanel(fromOverlay = false)
                    userResumeRequested = true
                    processOverlayCommand(pendingGoal, fromOverlay = false)
                }
                return@post
            }
            if (pendingGoal != null && safeCommand != null && safeCommand != pendingGoal) {
                pendingUserInterventionStore.clear()
                autonomySession = null
                userResumeRequested = false
                mainHandler.removeCallbacks(userHandoffObservation)
            }
            // Hear the command first. Only commands that actually depend on the current UI pay the
            // cost of a semantic capture; app launches and system intents execute immediately.
            routeOverlayVoice(fromOverlay = false, voiceCommand = safeCommand)
        }
    }

    /** Hands an in-progress session from the activity to the always-on accessibility executor. */
    fun continueAutonomousCommand(command: String, session: AutonomySession?) {
        if (command.isBlank()) return
        mainHandler.post {
            if (instance !== this) return@post
            if (session != null && session.finalGoal == command) autonomySession = session
            requestVoiceWake(command)
        }
    }

    private fun captureOverlayContextAndLaunch(attempt: Int, captureGeneration: Long) {
        if (!isCurrentOverlayCapture(captureGeneration)) return
        if (snapshotCaptureInFlight) {
            // User work preempts passive observation. Both tasks share one executor, so the
            // interrupted traversal unwinds before the command capture starts and never competes.
            observedSnapshotCaptureGeneration += 1
            observedSnapshotFuture?.cancel(true)
            observedSnapshotFuture = null
            snapshotCaptureInFlight = false
            pendingSnapshotCapture = null
            debugTrace("capture preempted in-flight observation")
        }
        val root = bestAvailableApplicationRoot()
        val activePackage = root?.packageName?.toString().orEmpty()
        if (root == null || activePackage.isBlank() || activePackage == packageName) {
            retryOverlayCapture(attempt, captureGeneration)
            return
        }
        val captureEpoch = epoch.get()
        val capturedAtElapsedRealtime = SystemClock.elapsedRealtime()
        lastObservedApplicationSnapshot?.takeIf { observed ->
            ScreenContextHandoff.isRecentPlanningSnapshot(
                snapshot = observed.snapshot,
                activePackageName = activePackage,
                activeWindowId = root.windowId,
                nowElapsedRealtime = capturedAtElapsedRealtime,
                capturedAtElapsedRealtime = observed.capturedAtElapsedRealtime,
                ttlMillis = PLANNING_SNAPSHOT_TTL_MILLIS,
                activeEpoch = captureEpoch,
            )
        }?.let { observed ->
            debugTrace(
                "capture reused package=$activePackage elements=${observed.snapshot.elements.size}",
            )
            completeOverlayCapture(
                snapshotWithTrustedRoute(observed.snapshot),
                attempt,
                captureGeneration,
                observed.capturedAtElapsedRealtime,
            )
            return
        }
        debugTrace("capture start attempt=$attempt package=$activePackage epoch=$captureEpoch")
        runCatching {
            snapshotExecutor.execute {
                val startedAt = SystemClock.elapsedRealtime()
                val rawSnapshot = runCatching {
                    UiTreeReader.snapshot(root, captureEpoch, currentDisplayBounds())
                }.getOrNull()
                val captureMillis = SystemClock.elapsedRealtime() - startedAt
                mainHandler.post {
                    if (!isCurrentOverlayCapture(captureGeneration)) return@post
                    val currentEpoch = epoch.get()
                    val snapshot = rawSnapshot?.takeIf { it.packageName == activePackage }
                        ?.let(::snapshotWithTrustedRoute)
                    if (ScreenContextHandoff.shouldRetryCapture(
                            snapshot = snapshot,
                            captureEpoch = captureEpoch,
                            // Planning may use a moving semantic observation because every
                            // executable node is recaptured and rebound immediately before action.
                            currentEpoch = captureEpoch,
                            requireSemanticSignal = true,
                            attempt = attempt,
                            maxRetries = OVERLAY_CAPTURE_MAX_RETRIES,
                        )
                    ) {
                        debugTrace(
                            "capture retry=$attempt package=${snapshot?.packageName.orEmpty()} " +
                                "elements=${snapshot?.elements?.size ?: 0} " +
                                "truncated=${snapshot?.treeTruncated} " +
                                "captureEpoch=$captureEpoch currentEpoch=$currentEpoch " +
                                "elapsedMs=$captureMillis",
                        )
                        retryOverlayCapture(attempt, captureGeneration)
                    } else if (snapshot == null) {
                        retryOverlayCapture(OVERLAY_CAPTURE_MAX_RETRIES, captureGeneration)
                    } else {
                        debugTrace(
                            "capture complete attempt=$attempt package=${snapshot.packageName} " +
                                "elements=${snapshot.elements.size} truncated=${snapshot.treeTruncated} " +
                                "display=${snapshot.windowBounds} missingBody=${ScreenContextHandoff.hasUnobservedRenderedContent(snapshot)} " +
                                "elapsedMs=$captureMillis",
                        )
                        cacheObservedApplicationSnapshot(snapshot)
                        completeOverlayCapture(
                            snapshot,
                            attempt,
                            captureGeneration,
                            capturedAtElapsedRealtime,
                        )
                    }
                }
            }
        }.onFailure {
            retryOverlayCapture(attempt, captureGeneration)
        }
    }

    private fun retryOverlayCapture(attempt: Int, captureGeneration: Long) {
        if (!isCurrentOverlayCapture(captureGeneration)) return
        if (attempt < OVERLAY_CAPTURE_MAX_RETRIES) {
            mainHandler.postDelayed(
                { captureOverlayContextAndLaunch(attempt + 1, captureGeneration) },
                OVERLAY_CAPTURE_RETRY_MILLIS,
            )
        } else {
            overlayCaptureInProgress = false
            overlayVoiceCommand = null
            val pendingGoal = autonomySession?.takeIf { it.userHandoff != null }?.finalGoal ?: pendingUserInterventionStore.read()
            if (pendingGoal != null) {
                userResumeRequested = false
                showUserHandoff(pendingGoal)
                return
            }
            finishAutonomyAttempt()
            val message = "화면 정보를 읽지 못했어요. 잠시 후 다시 시도해 주세요."
            if (voicePanel != null) {
                showOverlayMessage(message)
            } else {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun completeOverlayCapture(
        snapshot: UiSnapshot,
        attempt: Int,
        captureGeneration: Long,
        capturedAtElapsedRealtime: Long,
    ) {
        if (!isCurrentOverlayCapture(captureGeneration)) return
        if (snapshot.packageName == "unknown") {
            retryOverlayCapture(attempt, captureGeneration)
            return
        }
        publishSemanticOverlayContext(
            snapshot,
            captureGeneration,
            capturedAtElapsedRealtime,
            attempt,
        )
    }

    private fun publishSemanticOverlayContext(
        snapshot: UiSnapshot,
        captureGeneration: Long,
        capturedAtElapsedRealtime: Long,
        captureAttempt: Int,
    ) {
        if (!isCurrentOverlayCapture(captureGeneration)) return
        val workflow = com.hwanghj09.sonju.agent.HospitalReservationWorkflow
        if ((workflow.matches(overlayVoiceCommand.orEmpty()) && workflow.needsLocalPageRead(snapshot, overlayVoiceCommand) ||
                ScreenExplainer.needsLocalPageRead(overlayVoiceCommand.orEmpty(), snapshot)) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Read-only OCR stays on the device. Its text is never converted into executable nodes.
            readLocalPage(snapshot) { read ->
                if (!isCurrentOverlayCapture(captureGeneration)) return@readLocalPage
                if (read == null && epoch.get() != snapshot.epoch) {
                    // Planning may reuse a recent tree; pixel reads require the current revision.
                    invalidateObservedSnapshot()
                    retryOverlayCapture(captureAttempt, captureGeneration)
                } else {
                    publishOverlayContext(read ?: snapshot, null, captureGeneration, capturedAtElapsedRealtime)
                }
            }
            return
        }
        val containsSensitiveNode = snapshot.elements.any { it.sensitive }
        val observableSignals = snapshot.elements.count { element ->
            element.visible && !element.sensitive &&
                (element.clickable || element.editable || element.scrollable ||
                    !element.text.isNullOrBlank() ||
                    !element.contentDescription.isNullOrBlank())
        }
        val semanticMapAllowed = observableSignals in 1..3 &&
            !containsSensitiveNode &&
            EssentialSafetyPolicy.allowsRemoteScreenshot(snapshot)
        val semanticMap = if (!snapshot.hasSemanticSignal() && semanticMapAllowed) {
            renderSanitizedSemanticMap(snapshot)
        } else {
            null
        }
        publishOverlayContext(
            snapshot,
            semanticMap,
            captureGeneration,
            capturedAtElapsedRealtime,
        )
    }

    private fun publishOverlayContext(
        snapshot: UiSnapshot,
        semanticMapJpegBase64: String?,
        captureGeneration: Long,
        capturedAtElapsedRealtime: Long,
    ) {
        if (!isCurrentOverlayCapture(captureGeneration)) return
        val sessionId = overlaySession.incrementAndGet()
        val context = PendingOverlayContext(
            snapshot = snapshot,
            semanticMapJpegBase64 = semanticMapJpegBase64,
            capturedAtElapsedRealtime = capturedAtElapsedRealtime,
            sessionId = sessionId,
        )
        setPendingOverlayContext(context)
        mainHandler.postDelayed(
            { clearPendingOverlayContext(sessionId) },
            OVERLAY_CONTEXT_TTL_MILLIS,
        )
        val voiceCommand = overlayVoiceCommand
        overlayVoiceCommand = null
        overlayCaptureInProgress = false
        routeOverlayVoice(
            fromOverlay = true,
            voiceCommand = voiceCommand,
        )
    }

    private fun isCurrentOverlayCapture(captureGeneration: Long): Boolean =
        instance === this && overlayCaptureInProgress &&
            overlayCaptureGeneration == captureGeneration

    private fun invalidateOverlayCapture() {
        overlayCaptureGeneration += 1
        overlayCaptureInProgress = false
        overlayVoiceCommand = null
    }

    private fun routeOverlayVoice(
        fromOverlay: Boolean,
        voiceCommand: String? = null,
    ) {
        if (voiceCommand.isNullOrBlank()) {
            if (voicePanel != null && (feedbackPromptVisible || voicePanelCommandDispatched)) {
                dismissVoicePanel(resumeWakeWord = false)
            }
            showVoicePanel(fromOverlay)
            return
        }
        if (voicePanel == null) showVoicePanel(fromOverlay)
        if (voicePanel != null && !beginCommandControl()) return
        voicePanelCommandDispatched = true
        stopVoicePanelRecognizer()
        voicePanelTranscript?.text = voiceCommand
        processOverlayCommand(voiceCommand, fromOverlay)
    }


    private fun ActionType.requiresStableScreen(): Boolean = this in setOf(
        ActionType.CLICK_COORDINATE,
        ActionType.SET_TEXT,
        ActionType.SUBMIT_TEXT,
        ActionType.SCROLL_DOWN,
        ActionType.SCROLL_UP,
        ActionType.SCROLL_LEFT,
        ActionType.SCROLL_RIGHT,
        ActionType.BACK,
    )

    private fun executeStep(
        verifiedPlan: VerifiedPlan,
        plan: AgentPlan,
        expectedSnapshot: UiSnapshot,
        verifyExpectedScreen: Boolean,
        index: Int,
        completedSteps: Int,
        startedAt: Long,
        generation: Long,
        callback: (ExecutionResult) -> Unit,
    ) {
        if (generation != executionGeneration) {
            callback(ExecutionResult(false, "사용자 요청으로 안전하게 멈췄습니다.", completedSteps))
            return
        }
        if (!executionWithinDeadline(startedAt)) {
            callback(ExecutionResult(false, "실행 시간이 길어져 안전하게 멈췄습니다.", completedSteps))
            return
        }
        if (index >= plan.actions.size) {
            callback(ExecutionResult(true, "요청한 동작을 마쳤습니다.", completedSteps))
            return
        }

        val action = plan.actions[index]
        if (action.type == ActionType.FINISH) {
            callback(ExecutionResult(true, action.description, completedSteps))
            return
        }

        val verifiedAction = verifiedPlan.actionAt(index)
        if (verifiedAction == null || verifiedAction.action != action) {
            callback(
                ExecutionResult(
                    success = false,
                    message = "검증 허가가 없는 동작은 실행하지 않습니다.",
                    completedSteps = completedSteps,
                    failureReason = ExecutionFailureReason.ACTION_REJECTED_BY_VERIFIER,
                ),
            )
            return
        }

        if (action.type == ActionType.WAIT) {
            val wait = action.waitMillis.coerceIn(100, 2_000)
            mainHandler.postDelayed(
                {
                    executeStep(
                        verifiedPlan,
                        plan,
                        expectedSnapshot,
                        verifyExpectedScreen,
                        index + 1,
                        completedSteps + 1,
                        startedAt,
                        generation,
                        callback,
                    )
                },
                wait,
            )
            return
        }

        val validatesInsideSink = action.type in setOf(
            ActionType.CLICK,
            ActionType.CLICK_COORDINATE,
            ActionType.SET_TEXT,
            ActionType.SUBMIT_TEXT,
            ActionType.SCROLL_DOWN,
            ActionType.SCROLL_UP,
            ActionType.SCROLL_LEFT,
            ActionType.SCROLL_RIGHT,
        )
        if (verifyExpectedScreen && action.type.requiresStableScreen() && !validatesInsideSink) {
            if (expectedSnapshot.packageName == "unknown" ||
                expectedSnapshot.elements.none { it.visible && !it.sensitive }
            ) {
                callback(
                    ExecutionResult(
                        false,
                        "화면 구조를 다시 확인할 수 없어 다른 곳을 조작하지 않고 멈췄습니다.",
                        completedSteps,
                    ),
                )
                return
            }
            validateExpectedScreenAsync(
                action,
                expectedSnapshot,
                generation,
                startedAt,
            ) { failureReason ->
                if (generation != executionGeneration) return@validateExpectedScreenAsync
                if (failureReason != null) {
                    callback(ExecutionResult(false, failureReason, completedSteps))
                } else {
                    val dispatched = dispatchVerifiedSynchronousAction(action)
                    if (dispatched) lastExecutionMethod = ExecutionMethod.GLOBAL_ACTION
                    continueAfterDispatch(
                        verifiedPlan,
                        verifiedAction,
                        dispatched,
                        action,
                        plan,
                        expectedSnapshot,
                        verifyExpectedScreen,
                        index,
                        completedSteps,
                        startedAt,
                        generation,
                        callback,
                    )
                }
            }
            return
        }

        when (action.type) {
            ActionType.CLICK -> {
                clickNodeAsync(
                    action = action,
                    resolvedNodeId = verifiedAction.resolvedNodeId,
                    expectedSnapshot = expectedSnapshot,
                    generation = generation,
                    startedAt = startedAt,
                ) { dispatched ->
                    continueAfterDispatch(
                        verifiedPlan = verifiedPlan,
                        verifiedAction = verifiedAction,
                        dispatched = dispatched,
                        action = action,
                        plan = plan,
                        expectedSnapshot = expectedSnapshot,
                        verifyExpectedScreen = verifyExpectedScreen,
                        index = index,
                        completedSteps = completedSteps,
                        startedAt = startedAt,
                        generation = generation,
                        callback = callback,
                    )
                }
                return
            }

            ActionType.CLICK_COORDINATE -> {
                if (!verifiedAction.visualFallback) {
                    callback(
                        ExecutionResult(
                            false,
                            "시각 폴백 검증이 없는 좌표 동작은 실행하지 않습니다.",
                            completedSteps,
                            failureReason = ExecutionFailureReason.ACTION_REJECTED_BY_VERIFIER,
                        ),
                    )
                    return
                }
                clickCoordinateAsync(
                    action = action,
                    expectedSnapshot = expectedSnapshot,
                    generation = generation,
                    startedAt = startedAt,
                    method = if (plan.source == PlanSource.APP_ADAPTER) {
                        ExecutionMethod.GESTURE
                    } else {
                        ExecutionMethod.VLM_GESTURE
                    },
                ) { dispatched ->
                    continueAfterDispatch(
                        verifiedPlan, verifiedAction,
                        dispatched, action, plan, expectedSnapshot, verifyExpectedScreen,
                        index, completedSteps, startedAt, generation, callback,
                    )
                }
                return
            }

            ActionType.SET_TEXT -> {
                setTextAsync(
                    action = action,
                    resolvedNodeId = verifiedAction.resolvedNodeId,
                    expectedSnapshot = expectedSnapshot,
                    generation = generation,
                    startedAt = startedAt,
                ) { dispatched ->
                    continueAfterDispatch(
                        verifiedPlan, verifiedAction,
                        dispatched, action, plan, expectedSnapshot, verifyExpectedScreen,
                        index, completedSteps, startedAt, generation, callback,
                    )
                }
                return
            }

            ActionType.SUBMIT_TEXT -> {
                submitTextAsync(
                    action = action,
                    resolvedNodeId = verifiedAction.resolvedNodeId,
                    expectedSnapshot = expectedSnapshot,
                    generation = generation,
                    startedAt = startedAt,
                ) { dispatched ->
                    continueAfterDispatch(
                        verifiedPlan, verifiedAction,
                        dispatched, action, plan, expectedSnapshot, verifyExpectedScreen,
                        index, completedSteps, startedAt, generation, callback,
                    )
                }
                return
            }

            ActionType.SCROLL_DOWN,
            ActionType.SCROLL_UP,
            ActionType.SCROLL_LEFT,
            ActionType.SCROLL_RIGHT,
            -> {
                scrollAsync(
                    direction = action.type,
                    resolvedNodeId = verifiedAction.resolvedNodeId,
                    expectedSnapshot = expectedSnapshot,
                    generation = generation,
                    startedAt = startedAt,
                    visualFallback = verifiedAction.visualFallback,
                ) { dispatched ->
                    continueAfterDispatch(
                        verifiedPlan,
                        verifiedAction,
                        dispatched,
                        action,
                        plan,
                        expectedSnapshot,
                        verifyExpectedScreen,
                        index,
                        completedSteps,
                        startedAt,
                        generation,
                        callback,
                    )
                }
                return
            }

            else -> Unit
        }

        val dispatched = dispatchVerifiedSynchronousAction(action)
        if (dispatched) lastExecutionMethod = ExecutionMethod.GLOBAL_ACTION
        continueAfterDispatch(
            verifiedPlan,
            verifiedAction,
            dispatched,
            action,
            plan,
            expectedSnapshot,
            verifyExpectedScreen,
            index,
            completedSteps,
            startedAt,
            generation,
            callback,
        )
    }

    private fun dispatchSynchronousAction(action: AgentAction): Boolean = when (action.type) {
        ActionType.START_TIMER,
        ActionType.OPEN_APP,
        ActionType.OPEN_URL,
        ActionType.OPEN_WIFI_SETTINGS,
        ActionType.OPEN_SOUND_SETTINGS,
        ActionType.OPEN_ACCESSIBILITY_SETTINGS,
        ActionType.OPEN_DISPLAY_SETTINGS,
        ActionType.OPEN_DATE_SETTINGS,
        ActionType.OPEN_CAMERA,
        ActionType.OPEN_DIALER,
        ActionType.OPEN_MESSAGES,
        -> launch(action)

        ActionType.BACK -> performGlobalActionClearingRoute(GLOBAL_ACTION_BACK)
        ActionType.HOME -> performGlobalActionClearingRoute(GLOBAL_ACTION_HOME)
        ActionType.NOTIFICATIONS -> performGlobalActionClearingRoute(GLOBAL_ACTION_NOTIFICATIONS)
        ActionType.QUICK_SETTINGS -> performGlobalActionClearingRoute(GLOBAL_ACTION_QUICK_SETTINGS)
        ActionType.CLICK,
        ActionType.WAIT_FOR_USER,
        ActionType.CLICK_COORDINATE,
        ActionType.SET_TEXT,
        ActionType.SUBMIT_TEXT,
        ActionType.SCROLL_DOWN,
        ActionType.SCROLL_UP,
        ActionType.SCROLL_LEFT,
        ActionType.SCROLL_RIGHT,
        -> false

        ActionType.WAIT, ActionType.FINISH -> true
    }

    private fun continueAfterDispatch(
        verifiedPlan: VerifiedPlan,
        verifiedAction: VerifiedAction,
        dispatched: Boolean,
        action: AgentAction,
        plan: AgentPlan,
        expectedSnapshot: UiSnapshot,
        verifyExpectedScreen: Boolean,
        index: Int,
        completedSteps: Int,
        startedAt: Long,
        generation: Long,
        callback: (ExecutionResult) -> Unit,
    ) {
        if (generation != executionGeneration) return
        if (!dispatched) {
            val failureReason = when (action.type) {
                ActionType.OPEN_APP -> ExecutionFailureReason.APP_NOT_INSTALLED
                ActionType.CLICK_COORDINATE -> ExecutionFailureReason.GESTURE_FAILED
                else -> ExecutionFailureReason.NODE_ACTION_FAILED
            }
            val failureMessage = if (action.type == ActionType.OPEN_APP) {
                "‘${action.target.orEmpty()}’ 앱을 설치 목록에서 정확히 찾거나 실행하지 못했습니다."
            } else {
                "‘${action.description}’ 단계에서 화면 요소를 확실히 찾지 못해 멈췄습니다."
            }
            callback(
                ExecutionResult(
                    success = false,
                    message = failureMessage,
                    completedSteps = completedSteps,
                    failureReason = failureReason,
                    method = executionMethod(action),
                    beforeFingerprint = expectedSnapshot.semanticTemplateFingerprint(),
                    postconditionSatisfied = false,
                ),
            )
            return
        }
        if (!action.type.requiresObservablePostcondition()) {
            executeStep(
                verifiedPlan,
                plan,
                expectedSnapshot,
                verifyExpectedScreen,
                index + 1,
                completedSteps + 1,
                startedAt,
                generation,
                callback,
            )
            return
        }

        fun failPostcondition(afterSnapshot: UiSnapshot?) {
            callback(
                ExecutionResult(
                    success = false,
                    message = if (afterSnapshot?.hasNewToastSince(expectedSnapshot) == true)
                        "앱의 토스트 안내를 받았습니다. 같은 동작을 반복하지 말고 안내와 현재 결과를 확인해야 합니다."
                    else "‘${action.description}’ 동작 뒤 화면 변화를 확인하지 못해 완료로 처리하지 않았습니다.",
                    completedSteps = completedSteps,
                    failureReason = ExecutionFailureReason.POSTCONDITION_TIMEOUT,
                    method = executionMethod(action),
                    beforeFingerprint = expectedSnapshot.semanticTemplateFingerprint(),
                    afterFingerprint = afterSnapshot?.semanticTemplateFingerprint(),
                    postconditionSatisfied = false,
                ),
            )
        }
        var clickRetryUsed = false
        lateinit var verifyPostcondition: () -> Unit
        verifyPostcondition = verify@{
            if (generation != executionGeneration) return@verify
            verifyActionPostconditionAsync(
                action = action,
                verifiedAction = verifiedAction,
                expectedScreenFingerprint = plan.expectedScreenFingerprint,
                beforeSnapshot = expectedSnapshot,
                generation = generation,
                startedAt = startedAt,
            ) { verified, afterSnapshot ->
                if (generation != executionGeneration) return@verifyActionPostconditionAsync
                val afterFingerprint = afterSnapshot?.semanticTemplateFingerprint()
                if (verifiedAction.visualFallback && plan.visualFrameHash != null &&
                    afterSnapshot != null) {
                    captureScreenshotAsync(afterSnapshot) { frame ->
                        if (generation != executionGeneration) return@captureScreenshotAsync
                        val afterHash = frame?.let { com.hwanghj09.sonju.agent.visualFrameHash(it.jpegBase64) }
                        debugTrace("visual postcondition frame=${afterHash != null} changed=${afterHash != plan.visualFrameHash} " +
                            "expectedMatch=${plan.expectedScreenFingerprint?.let { it == afterFingerprint }}")
                        if (afterHash == null || afterHash == plan.visualFrameHash ||
                            plan.expectedScreenFingerprint?.let { it != afterFingerprint } == true) {
                            failPostcondition(afterSnapshot)
                        } else {
                            callback(ExecutionResult(success = true, message = "시각 동작 후 새 화면을 확인했습니다.",
                                completedSteps = completedSteps + 1, method = executionMethod(action),
                                beforeFingerprint = expectedSnapshot.semanticTemplateFingerprint(),
                                afterFingerprint = afterFingerprint, postconditionSatisfied = true,
                                afterVisualFrameHash = afterHash))
                        }
                    }
                    return@verifyActionPostconditionAsync
                }
                if (!verified) {
                    if (afterSnapshot?.hasNewToastSince(expectedSnapshot) == true) {
                        failPostcondition(afterSnapshot)
                        return@verifyActionPostconditionAsync
                    }
                    val submission = textSubmissionDispatch?.takeIf { it.generation == generation }
                    if (action.type == ActionType.SUBMIT_TEXT && submission != null && submission.method < 3 &&
                        afterSnapshot != null && submission.before.hasSameContentIgnoringLayoutAs(afterSnapshot) &&
                        !ScreenContextHandoff.hasVisibleLoadingIndicator(afterSnapshot)) {
                        debugTrace("submit-text accepted without search effect; trying next verified method")
                        submitTextAsync(action, submission.inputPath, submission.before, generation, startedAt,
                            afterMethod = submission.method) { dispatched ->
                            if (dispatched) mainHandler.postDelayed(verifyPostcondition, POSTCONDITION_INITIAL_DELAY_MILLIS)
                            else failPostcondition(afterSnapshot)
                        }
                        return@verifyActionPostconditionAsync
                    }
                    val unchangedScreen = afterSnapshot != null &&
                        expectedSnapshot.hasSameObservableContentAs(afterSnapshot)
                    val unchangedClickTarget = unchangedScreen &&
                        ScreenContextHandoff.relocateClickTarget(
                            expectedSnapshot,
                            afterSnapshot!!,
                            verifiedAction.resolvedNodeId,
                        ) != null
                    if (!clickRetryUsed && action.type == ActionType.CLICK &&
                        verifiedAction.canRetryAfterNoEffect && unchangedClickTarget
                    ) {
                        clickRetryUsed = true
                        debugTrace("click accepted without effect; trying one verified bounds tap")
                        retryVerifiedClickAsGestureAsync(
                            action = action,
                            resolvedNodeId = verifiedAction.resolvedNodeId,
                            expectedSnapshot = expectedSnapshot,
                            generation = generation,
                            startedAt = startedAt,
                        ) { dispatched ->
                            if (dispatched) {
                                mainHandler.postDelayed(
                                    verifyPostcondition,
                                    POSTCONDITION_INITIAL_DELAY_MILLIS,
                                )
                            } else {
                                failPostcondition(afterSnapshot)
                            }
                        }
                        return@verifyActionPostconditionAsync
                    }
                    failPostcondition(afterSnapshot)
                    return@verifyActionPostconditionAsync
                }
                val onlyFinishRemains = plan.actions.drop(index + 1).all {
                    it.type == ActionType.FINISH
                }
                if (onlyFinishRemains) {
                    callback(
                        ExecutionResult(
                            success = true,
                            message = plan.actions.drop(index + 1).firstOrNull()?.description
                                ?: "요청한 동작을 마쳤습니다.",
                            completedSteps = completedSteps + 1,
                            method = executionMethod(action),
                            beforeFingerprint = expectedSnapshot.semanticTemplateFingerprint(),
                            afterFingerprint = afterFingerprint,
                            postconditionSatisfied = true,
                            // This result proves one action transition only. Task-level success is
                            // decided later from a fresh ScreenState by DeterministicGoalEvaluator.
                            goalVerified = false,
                        ),
                    )
                    return@verifyActionPostconditionAsync
                }
                executeStep(
                    verifiedPlan,
                    plan,
                    expectedSnapshot,
                    verifyExpectedScreen,
                    index + 1,
                    completedSteps + 1,
                    startedAt,
                    generation,
                    callback,
                )
            }
        }
        if (plan.expectedScreenFingerprint != null ||
            action.type in SELF_AUTHENTICATING_POSTCONDITIONS
        ) {
            mainHandler.postDelayed(verifyPostcondition, POSTCONDITION_INITIAL_DELAY_MILLIS)
        } else {
            eventMonitor.waitForRevision(
                generation = generation,
                beforeEpoch = lastDispatchSourceEpoch ?: expectedSnapshot.epoch,
                currentEpoch = epoch.get(),
                timeoutMs = DEFAULT_POSTCONDITION_TIMEOUT_MILLIS,
                callback = { verifyPostcondition() },
            )
        }
    }

    private fun executionMethod(action: AgentAction): ExecutionMethod = lastExecutionMethod ?: when (action.type) {
        ActionType.START_TIMER -> ExecutionMethod.GLOBAL_ACTION
        ActionType.OPEN_URL -> ExecutionMethod.GLOBAL_ACTION
        ActionType.CLICK_COORDINATE -> ExecutionMethod.VLM_GESTURE
        ActionType.BACK,
        ActionType.HOME,
        ActionType.NOTIFICATIONS,
        ActionType.QUICK_SETTINGS,
        ActionType.OPEN_APP,
        ActionType.OPEN_WIFI_SETTINGS,
        ActionType.OPEN_SOUND_SETTINGS,
        ActionType.OPEN_ACCESSIBILITY_SETTINGS,
        ActionType.OPEN_DISPLAY_SETTINGS,
        ActionType.OPEN_DATE_SETTINGS,
        ActionType.OPEN_CAMERA,
        ActionType.OPEN_DIALER,
        ActionType.OPEN_MESSAGES,
        -> ExecutionMethod.GLOBAL_ACTION
        else -> ExecutionMethod.ACCESSIBILITY_NODE_ACTION
    }

    /**
     * Dispatch acceptance is not proof that Android applied an action. Re-observe the live tree
     * and require an action-specific destination, content revision change, or exact requested
     * Settings state before reporting completion. Read-only retries tolerate slow accessibility
     * events; the side effect itself is never retried.
     */
    private fun verifyActionPostconditionAsync(
        action: AgentAction,
        verifiedAction: VerifiedAction,
        expectedScreenFingerprint: String?,
        beforeSnapshot: UiSnapshot,
        generation: Long,
        startedAt: Long,
        attempt: Int = 0,
        timerBaseline: Int? = null,
        callback: (Boolean, UiSnapshot?) -> Unit,
    ) {
        if (generation != executionGeneration || !executionWithinDeadline(startedAt)) {
            callback(false, null)
            return
        }
        if (action.type in PACKAGE_IDENTITY_POSTCONDITIONS) {
            val expectedPackage = expectedPackageAfterAction(action)
            val destinationVisible = !expectedPackage.isNullOrBlank() && (
                bestAvailableApplicationRoot()?.packageName?.toString() == expectedPackage ||
                    windows.asSequence()
                        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                        .mapNotNull(AccessibilityWindowInfo::getRoot)
                        .any { it.packageName?.toString() == expectedPackage }
                )
            if (destinationVisible) {
                callback(
                    true,
                    UiSnapshot.empty(epoch.get()).copy(packageName = expectedPackage!!),
                )
            } else if (attempt < MAX_PACKAGE_POSTCONDITION_RETRIES) {
                mainHandler.postDelayed(
                    {
                        verifyActionPostconditionAsync(
                            action = action,
                            verifiedAction = verifiedAction,
                            expectedScreenFingerprint = expectedScreenFingerprint,
                            beforeSnapshot = beforeSnapshot,
                            generation = generation,
                            startedAt = startedAt,
                            attempt = attempt + 1,
                            callback = callback,
                        )
                    },
                    POSTCONDITION_RETRY_MILLIS,
                )
            } else {
                callback(false, null)
            }
            return
        }
        captureLiveSnapshotAsync(
            generation = generation,
            requireStableRevision = expectedScreenFingerprint == null &&
                action.type !in SELF_AUTHENTICATING_POSTCONDITIONS,
        ) { afterSnapshot, _, captureEpoch ->
            if (generation != executionGeneration) return@captureLiveSnapshotAsync
            val stableCapture = afterSnapshot != null && epoch.get() == captureEpoch
            val observedTextElement = if (action.type == ActionType.SET_TEXT) {
                afterSnapshot?.let { com.hwanghj09.sonju.agent.UiTargetResolver.rebindEditable(beforeSnapshot, it,
                    verifiedAction.resolvedNodeId) }
            } else {
                null
            }
            val timerRemaining = if (action.type == ActionType.START_TIMER && afterSnapshot != null &&
                afterSnapshot.packageName == expectedPackageAfterAction(action)) {
                action.value?.toIntOrNull()?.let { seconds ->
                    com.hwanghj09.sonju.agent.TimerRequest(seconds, action.target).runningSeconds(
                        afterSnapshot, (SystemClock.elapsedRealtime() - startedAt) / 1000)
                }
            } else null
            // Two observations prove the displayed countdown is actually progressing.
            if (timerRemaining != null && timerBaseline == null) {
                mainHandler.postDelayed({ verifyActionPostconditionAsync(action, verifiedAction,
                    expectedScreenFingerprint, beforeSnapshot, generation, startedAt, attempt,
                    timerRemaining, callback) }, 1100)
                return@captureLiveSnapshotAsync
            }
            // Toasts often move focus while the requested effect was rejected. Focus/layout
            // drift must not turn that feedback into a successful action transition.
            val toastWithoutEffect = afterSnapshot?.let { it.hasNewToastSince(beforeSnapshot) &&
                beforeSnapshot.hasSameContentIgnoringLayoutAs(it) } == true
            val verified = !toastWithoutEffect && when {
                action.type == ActionType.START_TIMER ->
                    timerRemaining != null && timerBaseline != null && timerRemaining < timerBaseline
                action.type == ActionType.OPEN_URL -> afterSnapshot?.let {
                    // Domain arrival proves navigation; the next observation separately verifies login/results.
                    com.hwanghj09.sonju.verifier.WebNavigationPolicy.arrived(action.target, it)
                } == true
                action.type == ActionType.SUBMIT_TEXT -> stableCapture &&
                    (expectedScreenFingerprint == null || afterSnapshot.semanticTemplateFingerprint() == expectedScreenFingerprint) &&
                    textSubmissionDispatch?.takeIf { it.generation == generation }?.let {
                        com.hwanghj09.sonju.verifier.SearchSubmissionPolicy.hasObservedEffect(it.before, afterSnapshot, it.inputPath)
                    } == true
                expectedScreenFingerprint != null ->
                    afterSnapshot?.semanticTemplateFingerprint() == expectedScreenFingerprint &&
                        if (action.type == ActionType.SET_TEXT) {
                            observedTextElement != null &&
                                UiTreeReader.matchesEnteredValue(observedTextElement, action.value)
                        } else {
                            action.type == ActionType.CLICK_COORDINATE ||
                                !beforeSnapshot.hasSameObservableContentAs(afterSnapshot!!)
                        }

                action.type in setOf(
                    ActionType.CLICK,
                    ActionType.CLICK_COORDINATE,
                ) ->
                    stableCapture && afterSnapshot!!.epoch > beforeSnapshot.epoch &&
                        !beforeSnapshot.hasSameObservableContentAs(afterSnapshot)

                action.type == ActionType.SET_TEXT -> {
                    observedTextElement != null &&
                        UiTreeReader.matchesEnteredValue(observedTextElement, action.value)
                }

                action.type in setOf(
                    ActionType.SCROLL_DOWN,
                    ActionType.SCROLL_UP,
                    ActionType.SCROLL_LEFT,
                    ActionType.SCROLL_RIGHT,
                ) ->
                    stableCapture && afterSnapshot!!.epoch > beforeSnapshot.epoch &&
                        !beforeSnapshot.hasSameObservableContentAs(afterSnapshot)

                action.type.settingsRoute() != null ->
                    afterSnapshot!!.packageName == SETTINGS_PACKAGE &&
                        afterSnapshot.trustedSettingsRoute == action.type.settingsRoute()

                action.type in setOf(
                    ActionType.OPEN_APP,
                    ActionType.OPEN_CAMERA,
                    ActionType.OPEN_DIALER,
                    ActionType.OPEN_MESSAGES,
                ) -> expectedPackageAfterAction(action)?.let { expectedPackage ->
                    afterSnapshot!!.packageName == expectedPackage
                } == true

                action.type == ActionType.HOME ->
                    expectedPackageAfterAction(action)?.let { expectedPackage ->
                        afterSnapshot!!.packageName == expectedPackage
                    } == true

                action.type in setOf(ActionType.NOTIFICATIONS, ActionType.QUICK_SETTINGS) ->
                    afterSnapshot!!.packageName.lowercase().contains("systemui")

                action.type == ActionType.BACK ->
                    afterSnapshot!!.packageName != packageName &&
                        !beforeSnapshot.hasSameObservableContentAs(afterSnapshot)

                else -> false
            }
            val clickedTargetStillAtSamePath = action.type == ActionType.CLICK &&
                afterSnapshot?.windowId == beforeSnapshot.windowId &&
                verifiedAction.resolvedNodeId?.let { resolvedPath ->
                    afterSnapshot?.elements?.any { element ->
                        element.path == resolvedPath && element.visible && element.enabled &&
                            (element.clickable || UiNodeAction.CLICK in element.availableActions)
                    }
                } == true
            val reusablePlanningObservation = verified && afterSnapshot != null &&
                !afterSnapshot.treeTruncated &&
                (stableCapture || action.type == ActionType.SET_TEXT) &&
                !clickedTargetStillAtSamePath
            if (reusablePlanningObservation) {
                cacheObservedApplicationSnapshot(afterSnapshot)
                verifiedPostconditionGeneration = generation
            }
            if (verified || afterSnapshot?.hasNewToastSince(beforeSnapshot) == true ||
                attempt >= MAX_POSTCONDITION_OBSERVE_RETRIES) {
                callback(verified, afterSnapshot)
                return@captureLiveSnapshotAsync
            }
            mainHandler.postDelayed(
                {
                    verifyActionPostconditionAsync(
                        action = action,
                        verifiedAction = verifiedAction,
                        expectedScreenFingerprint = expectedScreenFingerprint,
                        beforeSnapshot = beforeSnapshot,
                        generation = generation,
                        startedAt = startedAt,
                        attempt = attempt + 1,
                        timerBaseline = timerBaseline,
                        callback = callback,
                    )
                },
                POSTCONDITION_RETRY_MILLIS,
            )
        }
    }

    private fun ActionType.requiresObservablePostcondition(): Boolean = this in setOf(
        ActionType.START_TIMER,
        ActionType.OPEN_APP,
        ActionType.OPEN_URL,
        ActionType.OPEN_WIFI_SETTINGS,
        ActionType.OPEN_SOUND_SETTINGS,
        ActionType.OPEN_ACCESSIBILITY_SETTINGS,
        ActionType.OPEN_DISPLAY_SETTINGS,
        ActionType.OPEN_DATE_SETTINGS,
        ActionType.OPEN_CAMERA,
        ActionType.OPEN_DIALER,
        ActionType.OPEN_MESSAGES,
        ActionType.CLICK,
        ActionType.CLICK_COORDINATE,
        ActionType.SET_TEXT,
        ActionType.SUBMIT_TEXT,
        ActionType.SCROLL_DOWN,
        ActionType.SCROLL_UP,
        ActionType.SCROLL_LEFT,
        ActionType.SCROLL_RIGHT,
        ActionType.BACK,
        ActionType.HOME,
        ActionType.NOTIFICATIONS,
        ActionType.QUICK_SETTINGS,
    )

    private fun launch(action: AgentAction): Boolean {
        if (action.type == ActionType.OPEN_APP) {
            clearTrustedSettingsContext()
            return launchInstalledApp(action.target.orEmpty(), action.value)
        }
        val settingsRoute = action.type.settingsRoute()
        val intent = launchIntentFor(action) ?: return false

        if (settingsRoute != null) {
            intent.setPackage(SETTINGS_PACKAGE)
            val resolvedActivityClass = intent.resolveActivity(packageManager)?.className.orEmpty()
            if (!isExpectedSettingsActivity(settingsRoute, resolvedActivityClass)) return false
            trustedSettingsContext = TrustedSettingsContext(
                route = settingsRoute,
                armedAtElapsedRealtime = SystemClock.elapsedRealtime(),
                establishedActivityClass = resolvedActivityClass,
            )
        } else {
            clearTrustedSettingsContext()
        }

        return runCatching {
            startActivity(intent)
            true
        }.getOrElse {
            clearTrustedSettingsContext()
            false
        }
    }

    private fun launchIntentFor(action: AgentAction): Intent? = (when (action.type) {
            ActionType.START_TIMER -> action.value?.toIntOrNull()?.takeIf { it in 1..86400 }?.let {
                Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, it)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, action.target)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }
            ActionType.OPEN_URL -> action.target?.takeIf {
                com.hwanghj09.sonju.verifier.WebNavigationPolicy.allows(it)
            }?.let { url ->
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).apply {
                    action.value?.let { setPackage(it) }
                }
            }
            ActionType.OPEN_WIFI_SETTINGS -> Intent(Settings.ACTION_WIFI_SETTINGS)
            ActionType.OPEN_SOUND_SETTINGS -> Intent(Settings.ACTION_SOUND_SETTINGS)
            ActionType.OPEN_ACCESSIBILITY_SETTINGS -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            ActionType.OPEN_DISPLAY_SETTINGS -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            ActionType.OPEN_DATE_SETTINGS -> Intent(Settings.ACTION_DATE_SETTINGS)
            ActionType.OPEN_CAMERA -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            ActionType.OPEN_DIALER -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
            ActionType.OPEN_MESSAGES -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
            else -> null
        })?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun ActionType.settingsRoute(): TrustedSettingsRoute? = when (this) {
        ActionType.OPEN_WIFI_SETTINGS -> TrustedSettingsRoute.WIFI
        ActionType.OPEN_SOUND_SETTINGS -> TrustedSettingsRoute.SOUND
        ActionType.OPEN_ACCESSIBILITY_SETTINGS -> TrustedSettingsRoute.ACCESSIBILITY
        ActionType.OPEN_DISPLAY_SETTINGS -> TrustedSettingsRoute.DISPLAY
        ActionType.OPEN_DATE_SETTINGS -> TrustedSettingsRoute.DATE_TIME
        else -> null
    }

    private fun expectedPackageAfterAction(action: AgentAction): String? = when (action.type) {
        ActionType.OPEN_APP -> resolveInstalledAppPackage(action.target.orEmpty())
        ActionType.START_TIMER,
        ActionType.OPEN_CAMERA,
        ActionType.OPEN_DIALER,
        ActionType.OPEN_MESSAGES,
        -> launchIntentFor(action)?.resolveActivity(packageManager)?.packageName

        ActionType.HOME -> Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .resolveActivity(packageManager)
            ?.packageName

        else -> null
    }

    private fun launchInstalledApp(target: String, locationQuery: String? = null): Boolean {
        val packageName = resolveInstalledAppPackage(target) ?: return false
        val intent = locationQuery?.takeIf(String::isNotBlank)?.let { query ->
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=${Uri.encode(query)}"),
            ).setPackage(packageName).takeIf { it.resolveActivity(packageManager) != null }
        } ?: packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)
    }

    internal fun resolveAppWorkflowRoute(command: String): AppWorkflowRoute? {
        val localAction = RuleBasedPlanner.plan(command)?.actions
            ?.singleOrNull { it.type != ActionType.FINISH }
        val knownLocalAction = when {
            localAction == null -> false
            localAction.type != ActionType.OPEN_APP -> true
            else -> normalizeAppLabel(localAction.target.orEmpty()) in APP_PACKAGE_HINTS
        }
        if (knownLocalAction) {
            debugTrace("workflow route delegated to local action=${localAction?.type}")
            return null
        }
        val labels = launcherCatalog().map { it.label }
        // A requested alias may differ from Android's label (e.g. Maps is labelled simply 지도).
        // Resolve it against the live catalog before considering a preferred app for the domain.
        val explicitPrefix = Regex("^(.{1,40}?)에서\\s+").find(command.trim())?.groupValues?.get(1)
            ?.takeIf { resolveInstalledAppPackage(it) != null }
        return AppWorkflowRouter.route(command, labels + listOfNotNull(explicitPrefix)).also { route ->
            debugTrace(
                "workflow route inferred=${route?.appLabel.orEmpty()} launcherApps=${labels.size}",
            )
        }
    }

    internal fun resolveInstalledAppPackage(target: String): String? {
        val packageTarget = target.trim()
        if (packageTarget.matches(Regex("[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)+"))) {
            // An observed package is an exact target; never substitute a similar app label.
            return packageManager.getLaunchIntentForPackage(packageTarget)?.component?.packageName
        }
        val normalized = normalizeAppLabel(target)
        if (normalized.isBlank()) return null
        if (normalized in setOf("브라우저", "웹브라우저", "browser", "webbrowser")) {
            com.hwanghj09.sonju.agent.InstalledApps.preferredWebBrowser(launcherCatalog())?.let { return it.packageName }
        }
        APP_PACKAGE_HINTS[normalized]?.takeIf { hintedPackage ->
            packageManager.getLaunchIntentForPackage(hintedPackage) != null
        }?.let { hintedPackage ->
            return hintedPackage
        }
        val acceptedLabels = (
            APP_LABEL_ALIASES.entries.firstOrNull {
                normalizeAppLabel(it.key) == normalized
            }?.value.orEmpty() + target + normalized
        ).map(::normalizeAppLabel).filter(String::isNotBlank).toSet()
        val launchers = launcherCatalog()
        val exactMatches = launchers.filter { app ->
            val label = normalizeAppLabel(app.label)
            val packageName = app.packageName.lowercase()
            label in acceptedLabels || packageName == target.trim().lowercase()
        }
        val match = exactMatches.singleOrNull() ?: launchers.filter { app ->
            val label = normalizeAppLabel(app.label)
            acceptedLabels.any { requested ->
                requested.length >= 2 && (label.contains(requested) || requested.contains(label))
            }
        }.singleOrNull() ?: return null
        return match.packageName
    }

    private fun launcherCatalog() = com.hwanghj09.sonju.agent.InstalledApps.query(packageManager)

    private fun normalizeAppLabel(value: String): String = Normalizer.normalize(
        value.trim().lowercase(),
        Normalizer.Form.NFKC,
    ).replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        .removeSuffix("앱")

    private fun validateExpectedScreenAsync(
        action: AgentAction,
        expectedSnapshot: UiSnapshot,
        generation: Long,
        startedAt: Long,
        callback: (String?) -> Unit,
    ) {
        captureLiveSnapshotAsync(generation) { liveSnapshot, _, captureEpoch ->
            val failureReason = when {
                !executionWithinDeadline(startedAt) ->
                    "실행 시간이 길어져 안전하게 멈췄습니다."

                liveSnapshot == null ->
                    "화면 구조를 다시 확인할 수 없어 다른 곳을 조작하지 않고 멈췄습니다."

                epoch.get() != captureEpoch ||
                    liveSnapshot.packageName != expectedSnapshot.packageName ||
                    liveSnapshot.screenFingerprint() != expectedSnapshot.screenFingerprint() ->
                    "확인한 뒤 화면이 바뀌어 다른 곳을 누르지 않고 멈췄습니다."

                else -> null
            }
            callback(failureReason)
        }
    }

    private fun captureLiveSnapshotAsync(
        generation: Long,
        attempt: Int = 0,
        requireStableRevision: Boolean = true,
        callback: (UiSnapshot?, AccessibilityNodeInfo?, Long) -> Unit,
    ) {
        val root = bestAvailableApplicationRoot()
        if (root == null) {
            if (attempt < LIVE_SNAPSHOT_CAPTURE_MAX_RETRIES) {
                mainHandler.postDelayed(
                    {
                        captureLiveSnapshotAsync(
                            generation,
                            attempt + 1,
                            requireStableRevision,
                            callback,
                        )
                    },
                    LIVE_SNAPSHOT_CAPTURE_RETRY_MILLIS,
                )
            } else {
                callback(null, null, epoch.get())
            }
            return
        }
        val captureEpoch = epoch.get()
        runCatching {
            snapshotExecutor.execute {
                val rawSnapshot = runCatching {
                    UiTreeReader.snapshot(root, captureEpoch, currentDisplayBounds())
                }.getOrNull()
                mainHandler.post {
                    if (instance !== this || generation != executionGeneration) return@post
                    val snapshot = rawSnapshot?.let(::snapshotWithTrustedRoute)
                    if (ScreenContextHandoff.shouldRetryCapture(
                            snapshot = snapshot,
                            captureEpoch = captureEpoch,
                            currentEpoch = if (requireStableRevision) epoch.get() else captureEpoch,
                            requireSemanticSignal = false,
                            attempt = attempt,
                            maxRetries = LIVE_SNAPSHOT_CAPTURE_MAX_RETRIES,
                        )
                    ) {
                        mainHandler.postDelayed(
                            {
                                captureLiveSnapshotAsync(
                                    generation,
                                    attempt + 1,
                                    requireStableRevision,
                                    callback,
                                )
                            },
                            LIVE_SNAPSHOT_CAPTURE_RETRY_MILLIS,
                        )
                    } else {
                        callback(snapshot, root, captureEpoch)
                    }
                }
            }
        }.onFailure {
            callback(null, root, captureEpoch)
        }
    }

    private fun captureScreenshotAsync(
        expectedSnapshot: UiSnapshot,
        callback: (ScreenshotFrame?) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }
        val root = bestAvailableApplicationRoot()
        if (root == null) {
            debugTrace("screenshot preflight failed root=false")
            callback(null)
            return
        }
        val captureEpoch = epoch.get()
        runCatching {
            snapshotExecutor.execute {
                val rawSnapshot = runCatching {
                    UiTreeReader.snapshot(root, captureEpoch, currentDisplayBounds())
                }.getOrNull()
                mainHandler.post {
                    val liveSnapshot = rawSnapshot?.let(::snapshotWithTrustedRoute)
                    val epochMatch = epoch.get() == captureEpoch
                    val policyMatch = liveSnapshot?.let {
                        EssentialSafetyPolicy.allowsRemoteScreenshot(expectedSnapshot, it)
                    } == true
                    if (instance !== this || liveSnapshot == null || !epochMatch || !policyMatch) {
                        debugTrace(
                            "screenshot preflight failed instance=${instance === this} " +
                                "snapshot=${liveSnapshot != null} epochMatch=$epochMatch " +
                                "policyMatch=$policyMatch nodes=${expectedSnapshot.elements.size}/${liveSnapshot?.elements?.size} " +
                                "windowMatch=${expectedSnapshot.windowId == liveSnapshot?.windowId} " +
                                "titleMatch=${expectedSnapshot.windowTitle == liveSnapshot?.windowTitle} " +
                                "layoutMatch=${liveSnapshot?.let(expectedSnapshot::hasSameContentIgnoringLayoutAs)} " +
                                "sensitive=${liveSnapshot?.elements?.count { it.visible && it.sensitive }}",
                        )
                        callback(null)
                        return@post
                    }
                    captureScreenshotFrame(liveSnapshot, callback)
                }
            }
        }.onFailure {
            debugTrace("screenshot preflight executor failed")
            callback(null)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun readLocalPage(snapshot: UiSnapshot, callback: (UiSnapshot?) -> Unit) {
        val workflow = com.hwanghj09.sonju.agent.HospitalReservationWorkflow
        if (!ScreenExplainer.isBrowserSurface(snapshot)) { callback(null); return }
        captureScreenshotBitmap(snapshot) { bitmap ->
            if (bitmap == null) { callback(null); return@captureScreenshotBitmap }
            var delivered = false
            fun finish(result: Result<List<String>>) {
                if (delivered) return
                delivered = true
                val read = snapshot.takeIf { instance === this && epoch.get() == snapshot.epoch }
                    ?.copy(localReadOnlyText = result.getOrDefault(emptyList()))
                debugTrace("local page read success=${result.isSuccess} lines=${read?.localReadOnlyText?.size ?: 0} " +
                    "stale=${read == null} login=${read?.let(workflow::loginRequired)} result=${read?.let(workflow::resultText) != null}")
                callback(read)
            }
            val timeout = Runnable { finish(Result.failure(IllegalStateException("local read timeout"))) }
            mainHandler.postDelayed(timeout, 10_000)
            com.hwanghj09.sonju.vision.OnDevicePageReader.read(bitmap) { result ->
                mainHandler.post { mainHandler.removeCallbacks(timeout); finish(result) }
            }
        }
    }

    /** Debug-only, read-only live diagnostic through the exact production capture and OCR path. */
    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>?) {
        if (!BuildConfig.DEBUG || args?.contains("--read-hospital-page") != true ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            super.dump(fd, writer, args)
            return
        }
        writer.println("Read-only page probe requested; boolean evidence is reported in SonjuFlow.")
        mainHandler.post {
            if (overlayCommandExecutionActive || overlayCaptureInProgress || executionActive) {
                debugTrace("read-only page probe busy=true")
                return@post
            }
            val snapshot = UiTreeReader.snapshot(bestAvailableApplicationRoot(), epoch.get(), currentDisplayBounds())
            readLocalPage(snapshot) { read ->
                val command = "분당서울대병원 예약 기록 알려줘."
                val plan = read?.let { com.hwanghj09.sonju.agent.HospitalReservationWorkflow.completionPlan(command, it) }
                val goal = read != null && plan != null && architectureRuntime.goalSatisfied(command, plan, read)
                debugTrace("read-only page probe nodes=${snapshot.elements.size} truncated=${snapshot.treeTruncated} " +
                    "read=${read != null} goal=$goal actionsDispatched=0")
            }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreenshotFrame(
        snapshot: UiSnapshot,
        callback: (ScreenshotFrame?) -> Unit,
    ) {
        captureScreenshotBitmap(snapshot) { bitmap ->
            val frame = bitmap?.let { source ->
                runCatching {
                    val output = ByteArrayOutputStream()
                    source.compress(Bitmap.CompressFormat.JPEG, 82, output)
                    ScreenshotFrame(
                        jpegBase64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
                        width = source.width,
                        height = source.height,
                    )
                }.getOrNull().also { source.recycle() }
            }
            callback(frame)
        }
    }

    /** Caller owns the full-resolution pixels. Local OCR must never pass through lossy JPEG. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreenshotBitmap(
        snapshot: UiSnapshot,
        callback: (Bitmap?) -> Unit,
    ) {
        val captureEpoch = snapshot.epoch
        val windowCapture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val targetWindow = if (windowCapture) windows.singleOrNull {
            it.id == snapshot.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        } else null
        if (windowCapture && targetWindow == null) { callback(null); return }
        val windowBounds = android.graphics.Rect().also { targetWindow?.getBoundsInScreen(it) }
        val panel = voicePanel
        val quickButton = quickVoiceButton
        val panelVisibility = panel?.visibility
        val quickVisibility = quickButton?.visibility
        val glow = controlGlow as? ScreenControlGlowView
        val glowDrawingSuppressed = glow?.drawingSuppressed
        if (!windowCapture) {
            panel?.visibility = View.INVISIBLE
            quickButton?.visibility = View.INVISIBLE
            glow?.drawingSuppressed = true
        }
        fun restoreOverlay() {
            panelVisibility?.let { panel.visibility = it }
            quickVisibility?.let { quickButton.visibility = it }
            glowDrawingSuppressed?.let { glow.drawingSuppressed = it }
        }
        mainHandler.postDelayed(
            {
                if (epoch.get() != captureEpoch) {
                    debugTrace("screenshot capture cancelled by revision change before request")
                    restoreOverlay()
                    callback(null)
                    return@postDelayed
                }
                runCatching {
                    val captureCallback = object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                                val hardwareBuffer = screenshot.hardwareBuffer
                                if (epoch.get() != captureEpoch) {
                                    debugTrace("screenshot capture cancelled by revision change after success")
                                    hardwareBuffer.close()
                                    restoreOverlay()
                                    callback(null)
                                    return
                                }
                                var bitmap = runCatching {
                                    Bitmap.wrapHardwareBuffer(
                                        hardwareBuffer,
                                        screenshot.colorSpace,
                                    )?.copy(Bitmap.Config.ARGB_8888, false)
                                }.getOrNull()
                                hardwareBuffer.close()
                                if (bitmap != null && windowCapture) {
                                    val source = bitmap
                                    val display = currentDisplayBounds()
                                    // Keep screenshot ratios in physical display coordinates, including floating windows.
                                    bitmap = if (source.width == windowBounds.width() && source.height == windowBounds.height()) {
                                        Bitmap.createBitmap(display.right, display.bottom, Bitmap.Config.ARGB_8888).also { framed ->
                                            android.graphics.Canvas(framed).apply {
                                                drawColor(android.graphics.Color.BLACK)
                                                drawBitmap(source, windowBounds.left.toFloat(), windowBounds.top.toFloat(), null)
                                            }
                                        }
                                    } else {
                                        debugTrace("window screenshot geometry mismatch bitmap=${source.width}x${source.height} bounds=$windowBounds")
                                        null
                                    }
                                    source.recycle()
                                }
                                if (bitmap == null) debugTrace("screenshot bitmap conversion failed")
                                restoreOverlay()
                                callback(bitmap)
                            }

                            override fun onFailure(errorCode: Int) {
                                debugTrace("screenshot platform failure code=$errorCode")
                                restoreOverlay()
                                callback(null)
                            }
                        }
                    if (windowCapture) {
                        takeScreenshotOfWindow(snapshot.windowId, mainExecutor, captureCallback)
                    } else takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, captureCallback)
                }.onFailure { failure ->
                    debugTrace(
                        "screenshot request exception=${failure::class.java.simpleName}",
                    )
                    restoreOverlay()
                    callback(null)
                }
            },
            SCREENSHOT_OVERLAY_SETTLE_MILLIS,
        )
    }

    private fun dispatchVisualTap(
        frame: ScreenshotFrame,
        target: VisualScreenResult,
        callback: (Boolean) -> Unit,
    ) {
        val x = (target.xRatio!! * frame.width).toFloat().coerceIn(1f, frame.width - 2f)
        val y = (target.yRatio!! * frame.height).toFloat().coerceIn(1f, frame.height - 2f)
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, VISUAL_TAP_DURATION_MILLIS),
        ).build()
        dispatchGuardedGesture(gesture, x, y) { accepted ->
            if (accepted) invalidateObservedSnapshot()
            callback(accepted)
        }
    }
    private fun clickCoordinateAsync(
        action: AgentAction,
        expectedSnapshot: UiSnapshot,
        generation: Long,
        startedAt: Long,
        method: ExecutionMethod,
        callback: (Boolean) -> Unit,
    ) {
        val xRatio = action.xRatio
        val yRatio = action.yRatio
        if (xRatio == null || yRatio == null || xRatio !in 0.0..1.0 || yRatio !in 0.0..1.0) {
            callback(false)
            return
        }
        captureLiveSnapshotAsync(generation) { liveSnapshot, _, captureEpoch ->
            if (liveSnapshot == null || epoch.get() != captureEpoch ||
                !sameVerifiedScreen(expectedSnapshot, liveSnapshot) ||
                !executionWithinDeadline(startedAt)
            ) {
                callback(false)
                return@captureLiveSnapshotAsync
            }
            clearTrustedSettingsContext()
            lastDispatchSourceEpoch = captureEpoch
            val display = currentDisplayBounds()
            dispatchVisualTap(
                frame = ScreenshotFrame("", display.right, display.bottom),
                target = VisualScreenResult(
                    found = true,
                    explanation = action.description,
                    xRatio = xRatio,
                    yRatio = yRatio,
                ),
            ) { accepted ->
                if (accepted) lastExecutionMethod = method
                callback(accepted)
            }
        }
    }

    private fun setTextAsync(
        action: AgentAction,
        resolvedNodeId: String?,
        expectedSnapshot: UiSnapshot,
        generation: Long,
        startedAt: Long,
        callback: (Boolean) -> Unit,
    ) {
        val value = action.value
        if (value == null || value.length > 4_000) {
            callback(false)
            return
        }
        captureLiveSnapshotAsync(generation) { liveSnapshot, root, captureEpoch ->
            val previous = expectedSnapshot.elements.singleOrNull { it.path == resolvedNodeId }
            val rebound = liveSnapshot?.let {
                com.hwanghj09.sonju.agent.UiTargetResolver.rebindEditable(expectedSnapshot, it, resolvedNodeId)
            }
            val sameEditableTarget = previous != null && rebound != null &&
                sameNormalizedText(previous.text.orEmpty(), rebound.text.orEmpty())
            if (liveSnapshot == null || root == null || epoch.get() != captureEpoch ||
                (!sameVerifiedScreen(expectedSnapshot, liveSnapshot) && !sameEditableTarget) ||
                !executionWithinDeadline(startedAt)
            ) {
                debugTrace(
                    "set-text sink rejected snapshot=${liveSnapshot != null} root=${root != null} " +
                        "epochMatch=${epoch.get() == captureEpoch} " +
                        "revisionMatch=${liveSnapshot?.let { sameVerifiedScreen(expectedSnapshot, it) } == true} " +
                        "targetMatch=$sameEditableTarget " +
                        "expectedBounds=${expectedSnapshot.elements.singleOrNull { it.path == resolvedNodeId }?.bounds} " +
                        "liveBounds=${liveSnapshot?.elements?.singleOrNull { it.path == resolvedNodeId }?.bounds}",
                )
                callback(false)
                return@captureLiveSnapshotAsync
            }
            val path = rebound?.path?.takeIf { candidate ->
                liveSnapshot.elements.singleOrNull { it.path == candidate }?.let { element ->
                    element.visible && element.enabled && element.editable && !element.sensitive
                } == true
            }
            val editable = path?.let { nodeAtPath(root, it) }
            if (editable == null || !nodeSupportsTextInput(editable) || !editable.isEnabled ||
                !editable.isVisibleToUser
            ) {
                debugTrace("set-text target unavailable path=${resolvedNodeId != null}")
                callback(false)
                return@captureLiveSnapshotAsync
            }
            clearTrustedSettingsContext()
            lastDispatchSourceEpoch = captureEpoch
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            val focusAccepted = editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val setTextAccepted = editable.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                arguments,
            )
            if (setTextAccepted) {
                lastExecutionMethod = ExecutionMethod.ACCESSIBILITY_NODE_ACTION
                invalidateObservedSnapshot()
                callback(true)
                return@captureLiveSnapshotAsync
            }
            // Compose and WebView fields can report editable before their input connection is
            // ready. A verified tap plus one short frame wait gives that connection time to bind.
            val clickAccepted = editable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!focusAccepted && !clickAccepted) {
                callback(false)
                return@captureLiveSnapshotAsync
            }
            mainHandler.postDelayed(
                {
                    if (generation != executionGeneration ||
                        !executionWithinDeadline(startedAt) ||
                        !runCatching { editable.refresh() }.getOrDefault(false) ||
                        !nodeSupportsTextInput(editable) || !editable.isEnabled ||
                        !editable.isVisibleToUser
                    ) {
                        callback(false)
                        return@postDelayed
                    }
                    val dispatched = editable.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        arguments,
                    )
                    if (dispatched) {
                        lastExecutionMethod = ExecutionMethod.ACCESSIBILITY_NODE_ACTION
                        invalidateObservedSnapshot()
                    }
                    callback(dispatched)
                },
                TEXT_INPUT_FOCUS_SETTLE_MILLIS,
            )
        }
    }

    private data class TextSubmissionDispatch(
        val generation: Long, val method: Int, val before: UiSnapshot, val inputPath: String,
    )
    private var textSubmissionDispatch: TextSubmissionDispatch? = null

    private fun submitTextAsync(
        action: AgentAction,
        resolvedNodeId: String?,
        expectedSnapshot: UiSnapshot,
        generation: Long,
        startedAt: Long,
        afterMethod: Int = -1,
        focusSettled: Boolean = false,
        callback: (Boolean) -> Unit,
    ) {
        val value = action.value
        if (value.isNullOrBlank()) {
            callback(false)
            return
        }
        captureLiveSnapshotAsync(generation) { liveSnapshot, root, captureEpoch ->
            val rebound = liveSnapshot?.let {
                com.hwanghj09.sonju.agent.UiTargetResolver.rebindEditable(expectedSnapshot, it, resolvedNodeId)
            }
            val sameEditableTarget = rebound != null && sameNormalizedText(rebound.text.orEmpty(), value)
            if (liveSnapshot == null || root == null || epoch.get() != captureEpoch ||
                (!sameVerifiedScreen(expectedSnapshot, liveSnapshot) && !sameEditableTarget) ||
                !executionWithinDeadline(startedAt)
            ) {
                debugTrace(
                    "submit-text sink rejected snapshot=${liveSnapshot != null} root=${root != null} " +
                        "epochMatch=${epoch.get() == captureEpoch} " +
                        "revisionMatch=${liveSnapshot?.let { sameVerifiedScreen(expectedSnapshot, it) } == true} " +
                        "targetMatch=$sameEditableTarget",
                )
                callback(false)
                return@captureLiveSnapshotAsync
            }
            val path = rebound?.path?.takeIf { candidate ->
                liveSnapshot.elements.singleOrNull { it.path == candidate }?.let { element ->
                    element.visible && element.enabled && element.editable && !element.sensitive &&
                        sameNormalizedText(element.text.orEmpty(), value)
                } == true
            } ?: run {
                debugTrace("submit-text target unavailable path=${resolvedNodeId != null}")
                callback(false)
                return@captureLiveSnapshotAsync
            }
            val input = nodeAtPath(root, path)
            if (input == null || input.isPassword || !nodeSupportsTextInput(input) ||
                !input.isEnabled || !input.isVisibleToUser ||
                !sameNormalizedText(input.text?.toString().orEmpty(), value)
            ) {
                debugTrace("submit-text live input no longer matches the verified query")
                callback(false)
                return@captureLiveSnapshotAsync
            }
            clearTrustedSettingsContext()
            if (!focusSettled) {
                // Clicking an already focused editor can move the caret or open selection UI.
                if (!input.isFocused && !input.performAction(AccessibilityNodeInfo.ACTION_FOCUS) &&
                    !input.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    callback(false)
                    return@captureLiveSnapshotAsync
                }
                mainHandler.postDelayed({
                    if (generation != executionGeneration || !executionWithinDeadline(startedAt)) callback(false)
                    else submitTextAsync(action, path, liveSnapshot, generation, startedAt, afterMethod, true, callback)
                }, TEXT_INPUT_FOCUS_SETTLE_MILLIS)
                return@captureLiveSnapshotAsync
            }
            if (!com.hwanghj09.sonju.verifier.SearchSubmissionPolicy.isSearchOrAddressField(
                    liveSnapshot.elements.single { it.path == path }, liveSnapshot)) {
                callback(false)
                return@captureLiveSnapshotAsync
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val info = inputMethod?.currentInputEditorInfo?.takeIf { it.packageName == liveSnapshot.packageName }
                if (info != null && ((info.imeOptions and 0xff) in setOf(4, 5, 7) ||
                    !info.actionLabel.isNullOrBlank() && com.hwanghj09.sonju.verifier.SearchSubmissionPolicy.editorAction(
                        info.imeOptions, info.actionId, info.actionLabel?.toString()) == null)) {
                    callback(false)
                    return@captureLiveSnapshotAsync
                }
            }
            for (method in (afterMethod + 1)..3) {
                if (generation != executionGeneration || epoch.get() != captureEpoch ||
                    !runCatching { input.refresh() }.getOrDefault(false) || !input.isFocused ||
                    !sameNormalizedText(input.text?.toString().orEmpty(), value)) break
                lastDispatchSourceEpoch = captureEpoch
                val accepted = runCatching { when (method) {
                    0 -> performEditorSubmit(input, liveSnapshot.packageName, value)
                    1 -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        input.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id } &&
                        input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    2 -> performEditorSubmit(input, liveSnapshot.packageName, value, enterKey = true)
                    else -> clickObservedImeSubmit(input, liveSnapshot.packageName, value)
                } }.getOrDefault(false)
                if (accepted) {
                    textSubmissionDispatch = TextSubmissionDispatch(generation, method, liveSnapshot, path)
                    lastExecutionMethod = ExecutionMethod.ACCESSIBILITY_NODE_ACTION
                    invalidateObservedSnapshot()
                    debugTrace("submit-text dispatched method=$method; awaiting search effect")
                    callback(true)
                    return@captureLiveSnapshotAsync
                }
            }
            callback(false)
        }
    }

    private fun nodeSupportsTextInput(node: AccessibilityNodeInfo): Boolean =
        node.isEditable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun matchedInputConnection(input: AccessibilityNodeInfo, expectedPackage: String, value: String):
        Pair<android.view.inputmethod.EditorInfo, android.accessibilityservice.InputMethod.AccessibilityInputConnection>? = runCatching {
        val method = inputMethod ?: return null
        val info = method.currentInputEditorInfo ?: return null
        val connection = method.currentInputConnection ?: return null
        if (!input.refresh() || !input.isFocused || !input.isEnabled || !input.isVisibleToUser ||
            info.packageName != expectedPackage || input.packageName?.toString() != expectedPackage ||
            input.isPassword || !nodeSupportsTextInput(input) ||
            !sameNormalizedText(input.text?.toString().orEmpty(), value)) return null
        val surrounding = connection.getSurroundingText(4_001, 4_001, 0) ?: return null
        if (!com.hwanghj09.sonju.verifier.SearchSubmissionPolicy.matchesConnectionValue(
                value, surrounding.text.toString(), surrounding.offset)) return null
        info to connection
    }.getOrNull()

    /** The editor action is a dispatch, never an acknowledgement of a completed search. */
    private fun performEditorSubmit(input: AccessibilityNodeInfo, expectedPackage: String, value: String,
        enterKey: Boolean = false): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            val (info, connection) = matchedInputConnection(input, expectedPackage, value) ?: return false
            val policy = com.hwanghj09.sonju.verifier.SearchSubmissionPolicy
            if (enterKey) {
                if (!policy.allowsEnterKey(info.imeOptions, info.inputType) || !info.actionLabel.isNullOrBlank()) return false
                val now = SystemClock.uptimeMillis()
                for (keyAction in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
                    connection.sendKeyEvent(android.view.KeyEvent(now, SystemClock.uptimeMillis(), keyAction,
                        android.view.KeyEvent.KEYCODE_ENTER, 0, 0, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,
                        0, android.view.KeyEvent.FLAG_SOFT_KEYBOARD or android.view.KeyEvent.FLAG_KEEP_TOUCH_MODE))
                }
            } else {
                val editorAction = policy.editorAction(info.imeOptions, info.actionId, info.actionLabel?.toString()) ?: return false
                connection.performEditorAction(editorAction)
                debugTrace("submit-text editor action=$editorAction packageMatched=true valueMatched=true")
            }
            true
        }.getOrDefault(false)
    }

    /** Older Android versions can still expose a real, labelled action in the IME window. */
    private fun clickObservedImeSubmit(input: AccessibilityNodeInfo, expectedPackage: String, value: String): Boolean {
        if (!input.refresh() || !input.isFocused || input.packageName?.toString() != expectedPackage ||
            !sameNormalizedText(input.text?.toString().orEmpty(), value)) return false
        val roots = windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.mapNotNull { it.root }
        val candidates = roots.flatMap { root ->
            val snapshot = UiTreeReader.snapshot(root, epoch.get())
            snapshot.elements.filter { node -> node.visible && node.enabled && !node.sensitive &&
                listOfNotNull(node.text, node.contentDescription).any {
                    it.trim().lowercase() in setOf("검색", "검색하기", "search", "이동", "go", "완료", "done", "enter", "엔터")
                } }.mapNotNull { node ->
                com.hwanghj09.sonju.agent.UiTargetResolver.resolveClickable(
                    AgentAction(ActionType.CLICK, "키보드 검색", node.path), snapshot)?.clickablePath?.let { nodeAtPath(root, it) }
            }
        }.distinct()
        return candidates.singleOrNull()?.let { it.refresh() && it.isVisibleToUser && it.isEnabled &&
            it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true
    }

    private fun sameNormalizedText(left: String, right: String): Boolean =
        Normalizer.normalize(left, Normalizer.Form.NFKC).replace(Regex("\\p{Cf}"), "") ==
            Normalizer.normalize(right, Normalizer.Form.NFKC).replace(Regex("\\p{Cf}"), "")

    private fun clickNodeAsync(
        action: AgentAction,
        resolvedNodeId: String?,
        expectedSnapshot: UiSnapshot,
        generation: Long,
        startedAt: Long,
        callback: (Boolean) -> Unit,
    ) {
        resolveVerifiedClickNodeAsync(
            expectedSnapshot = expectedSnapshot,
            resolvedNodeId = resolvedNodeId,
            generation = generation,
            startedAt = startedAt,
        ) { clickable, captureEpoch ->
            if (clickable == null) {
                debugTrace("click target unavailable path=${resolvedNodeId != null}")
                callback(false)
                return@resolveVerifiedClickNodeAsync
            }
            clearTrustedSettingsContext()
            lastDispatchSourceEpoch = captureEpoch
            showTouchIndicator(clickable)
            val dispatched = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (dispatched) {
                lastExecutionMethod = ExecutionMethod.ACCESSIBILITY_NODE_ACTION
                invalidateObservedSnapshot()
                callback(true)
                return@resolveVerifiedClickNodeAsync
            }
            debugTrace("click node action rejected; scheduling one bounded retry")
            mainHandler.postDelayed(
                {
                    if (generation != executionGeneration || epoch.get() != captureEpoch ||
                        !executionWithinDeadline(startedAt)
                    ) {
                        debugTrace(
                            "click retry cancelled generation=${generation == executionGeneration} " +
                                "epochMatch=${epoch.get() == captureEpoch}",
                        )
                        callback(false)
                        return@postDelayed
                    }
                    val refreshed = runCatching { clickable.refresh() }.getOrDefault(false)
                    val retryDispatched = refreshed && runCatching {
                        clickable.isVisibleToUser && clickable.isEnabled &&
                            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }.getOrDefault(false)
                    if (retryDispatched) {
                        lastExecutionMethod = ExecutionMethod.ACCESSIBILITY_NODE_ACTION
                        invalidateObservedSnapshot()
                        callback(true)
                        return@postDelayed
                    }
                    debugTrace("click node retry rejected; using verified bounds gesture")
                    dispatchNodeBoundsGesture(clickable, action.description, callback)
                },
                NODE_ACTION_RETRY_MILLIS,
            )
        }
    }

    /** Handles platform controls that report ACTION_CLICK success without changing the screen. */
    private fun retryVerifiedClickAsGestureAsync(
        action: AgentAction,
        resolvedNodeId: String?,
        expectedSnapshot: UiSnapshot,
        generation: Long,
        startedAt: Long,
        callback: (Boolean) -> Unit,
    ) {
        resolveVerifiedClickNodeAsync(
            expectedSnapshot = expectedSnapshot,
            resolvedNodeId = resolvedNodeId,
            generation = generation,
            startedAt = startedAt,
        ) { clickable, captureEpoch ->
            if (clickable == null || generation != executionGeneration ||
                epoch.get() != captureEpoch
            ) {
                callback(false)
                return@resolveVerifiedClickNodeAsync
            }
            clearTrustedSettingsContext()
            lastDispatchSourceEpoch = captureEpoch
            showTouchIndicator(clickable)
            dispatchNodeBoundsGesture(clickable, action.description, callback)
        }
    }

    private fun dispatchNodeBoundsGesture(
        node: AccessibilityNodeInfo,
        description: String,
        callback: (Boolean) -> Unit,
    ) {
        val bounds = runCatching { Rect().also(node::getBoundsInScreen) }.getOrNull()
        if (bounds == null || bounds.isEmpty) {
            callback(false)
            return
        }
        val display = currentDisplayBounds()
        dispatchVisualTap(
            frame = ScreenshotFrame("", display.right, display.bottom),
            target = VisualScreenResult(
                found = true,
                explanation = description,
                xRatio = bounds.exactCenterX() /
                    display.right.coerceAtLeast(1).toDouble(),
                yRatio = bounds.exactCenterY() /
                    display.bottom.coerceAtLeast(1).toDouble(),
            ),
        ) { accepted ->
            if (accepted) {
                lastExecutionMethod = ExecutionMethod.GESTURE
                invalidateObservedSnapshot()
            }
            callback(accepted)
        }
    }

    private fun resolveVerifiedClickNodeAsync(
        expectedSnapshot: UiSnapshot,
        resolvedNodeId: String?,
        generation: Long,
        startedAt: Long,
        attempt: Int = 0,
        callback: (AccessibilityNodeInfo?, Long) -> Unit,
    ) {
        if (generation != executionGeneration || !executionWithinDeadline(startedAt)) {
            callback(null, epoch.get())
            return
        }
        val expected = resolvedNodeId?.let { path ->
            expectedSnapshot.elements.singleOrNull { element ->
                element.path == path && element.visible && element.enabled && !element.sensitive &&
                    (element.clickable || UiNodeAction.CLICK in element.availableActions)
            }
        }
        if (expected == null) {
            callback(null, epoch.get())
            return
        }
        val captureEpoch = epoch.get()
        val root = bestAvailableApplicationRoot()
        val rootMatches = root != null &&
            runCatching { root?.packageName?.toString() }.getOrNull() == expectedSnapshot.packageName &&
            runCatching { root?.windowId }.getOrDefault(-2) == expectedSnapshot.windowId &&
            !UiTreeReader.isSensitiveText(
                runCatching { root?.window?.title?.toString() }.getOrNull(),
            )
        val node = if (rootMatches) {
            runCatching { nodeAtPath(root!!, expected.path) }.getOrNull()
        } else {
            null
        }
        val uniqueExpected = expectedSnapshot.elements.count { element ->
            element.visible && element.enabled && !element.sensitive &&
                (element.clickable || UiNodeAction.CLICK in element.availableActions)
        } == 1
        if (node != null && epoch.get() == captureEpoch &&
            matchesLiveClickTarget(expected, node, uniqueExpected)
        ) {
            callback(node, captureEpoch)
            return
        }
        if (attempt < TARGET_REVALIDATION_MAX_RETRIES) {
            mainHandler.postDelayed(
                {
                    resolveVerifiedClickNodeAsync(
                        expectedSnapshot,
                        resolvedNodeId,
                        generation,
                        startedAt,
                        attempt + 1,
                        callback,
                    )
                },
                TARGET_REVALIDATION_RETRY_MILLIS,
            )
        } else {
            captureLiveSnapshotAsync(generation) { liveSnapshot, liveRoot, liveEpoch ->
                val relocatedPath = liveSnapshot?.let { live ->
                    ScreenContextHandoff.relocateClickTarget(
                        expectedSnapshot,
                        live,
                        resolvedNodeId,
                    )
                }
                val relocatedExpected = liveSnapshot?.elements?.singleOrNull {
                    it.path == relocatedPath
                }
                val relocatedNode = if (relocatedExpected != null && liveRoot != null &&
                    epoch.get() == liveEpoch
                ) {
                    runCatching { nodeAtPath(liveRoot, relocatedExpected.path) }.getOrNull()
                } else {
                    null
                }
                if (relocatedExpected != null && relocatedNode != null &&
                    matchesLiveClickTarget(relocatedExpected, relocatedNode, uniqueExpected = true)
                ) {
                    debugTrace("click target safely rebound after dynamic node insertion")
                    callback(relocatedNode, liveEpoch)
                } else {
                    debugTrace(
                        "click target revalidation failed root=$rootMatches " +
                            "epochMatch=${epoch.get() == captureEpoch} relocated=${relocatedPath != null} " +
                            "windowMatch=${liveSnapshot?.windowId == expectedSnapshot.windowId} " +
                            "truncated=${liveSnapshot?.treeTruncated} expectedBounds=${expected.bounds} " +
                            "liveBounds=${liveSnapshot?.elements?.singleOrNull { it.path == resolvedNodeId }?.bounds}",
                    )
                    callback(null, liveEpoch)
                }
            }
        }
    }

    private fun matchesLiveClickTarget(
        expected: UiElement,
        live: AccessibilityNodeInfo,
        uniqueExpected: Boolean,
    ): Boolean = runCatching {
        val text = live.text?.toString()?.trim()?.take(120)
        val description = live.contentDescription?.toString()?.trim()?.take(120)
        val hint = live.hintText?.toString()?.trim()?.take(120)
        val pane = live.paneTitle?.toString()?.trim()?.take(120)
        val tooltip = live.tooltipText?.toString()?.trim()?.take(120)
        val viewId = live.viewIdResourceName
        val bounds = Rect().also(live::getBoundsInScreen)
        val clickable = live.isClickable || live.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_CLICK
        }
        val sensitive = live.isPassword || listOf(
            text,
            description,
            hint,
            pane,
            tooltip,
            viewId,
        ).any(UiTreeReader::isSensitiveText)
        val expectedLabels = listOf(
            expected.text,
            expected.contentDescription,
            expected.hintText,
            expected.paneTitle,
            expected.tooltipText,
        ).filterNotNull().filter(String::isNotBlank)
        val liveLabels = listOf(text, description, hint, pane, tooltip)
            .filterNotNull().filter(String::isNotBlank)
        val labelsCompatible = ScreenContextHandoff.labelsCompatible(expectedLabels, liveLabels)
        val strongLocator =
            (!expected.viewId.isNullOrBlank() && expected.viewId == viewId) ||
                (expectedLabels.isNotEmpty() && labelsCompatible) || uniqueExpected
        live.isVisibleToUser && live.isEnabled && clickable && !sensitive &&
            live.className?.toString() == expected.className &&
            live.isCheckable == expected.checkable && (!expected.checkable || live.isChecked == expected.checked) &&
            bounds.left == expected.bounds.left && bounds.top == expected.bounds.top &&
            bounds.right == expected.bounds.right && bounds.bottom == expected.bounds.bottom &&
            strongLocator && labelsCompatible
    }.getOrDefault(false)

    private fun nodeAtPath(
        root: AccessibilityNodeInfo,
        path: String,
    ): AccessibilityNodeInfo? {
        val indices = path.split('.')
        if (indices.firstOrNull() != "0" || indices.any { it.toIntOrNull() == null }) return null
        var node = root
        for (indexToken in indices.drop(1)) {
            val childIndex = indexToken.toInt()
            if (childIndex !in 0 until node.childCount) return null
            node = UiTreeReader.childAt(node, childIndex) ?: return null
        }
        return node
    }

    private fun scrollAsync(
        direction: ActionType,
        resolvedNodeId: String?,
        expectedSnapshot: UiSnapshot,
        generation: Long,
        startedAt: Long,
        visualFallback: Boolean,
        callback: (Boolean) -> Unit,
    ) {
        captureLiveSnapshotAsync(generation) { liveSnapshot, root, captureEpoch ->
            if (liveSnapshot == null || root == null ||
                epoch.get() != captureEpoch
            ) {
                callback(false)
                return@captureLiveSnapshotAsync
            }
            val reboundSnapshot = if (resolvedNodeId != null && !visualFallback) {
                ScreenContextHandoff.resumeForScroll(expectedSnapshot, liveSnapshot, resolvedNodeId)
            } else {
                liveSnapshot.takeIf { sameVerifiedScreen(expectedSnapshot, it) }
            }
            if (reboundSnapshot == null) {
                debugTrace("scroll target rebind rejected path=${resolvedNodeId != null}")
                callback(false)
                return@captureLiveSnapshotAsync
            }
            if (!executionWithinDeadline(startedAt)) {
                callback(false)
                return@captureLiveSnapshotAsync
            }
            val visualBounds = if (visualFallback) {
                ScreenContextHandoff.unobservedRenderedSurfaces(reboundSnapshot).singleOrNull()
                    ?.takeIf { it.path == resolvedNodeId }?.bounds
                    ?: run { callback(false); return@captureLiveSnapshotAsync }
            } else null
            val scrollablePath = resolvedNodeId?.takeIf { candidate ->
                reboundSnapshot.elements.singleOrNull { it.path == candidate }?.let { element ->
                    element.scrollable && element.visible && element.enabled && !element.sensitive
                } == true
            }
            val scrollable = scrollablePath?.let { nodeAtPath(root, it) }
            val nodeAction = when (direction) {
                ActionType.SCROLL_DOWN ->
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                ActionType.SCROLL_UP ->
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                ActionType.SCROLL_LEFT ->
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                ActionType.SCROLL_RIGHT ->
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                else -> 0
            }
            val supportedIds = scrollable?.actionList?.mapTo(hashSetOf()) { it.id }.orEmpty()
            val preferredAction = when {
                nodeAction in supportedIds -> nodeAction
                direction == ActionType.SCROLL_DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                direction == ActionType.SCROLL_UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else -> nodeAction
            }
            lastDispatchSourceEpoch = captureEpoch
            val nodeDispatched = scrollable != null && scrollable.isEnabled &&
                scrollable.isVisibleToUser && preferredAction != 0 &&
                scrollable.performAction(preferredAction)
            debugTrace(
                "scroll dispatch path=${scrollablePath != null} node=${scrollable != null} " +
                    "supported=${supportedIds.size} action=$preferredAction accepted=$nodeDispatched",
            )
            if (nodeDispatched) {
                invalidateObservedSnapshot()
                mainHandler.postDelayed(
                    {
                        captureLiveSnapshotAsync(generation) { afterSnapshot, _, afterEpoch ->
                            val changed = afterSnapshot != null && epoch.get() == afterEpoch &&
                                ScreenContextHandoff.hasObservedScrollEffect(
                                    reboundSnapshot,
                                    afterSnapshot,
                                    resolvedNodeId.orEmpty(),
                                )
                            if (changed) {
                                lastExecutionMethod = ExecutionMethod.ACCESSIBILITY_NODE_ACTION
                                callback(true)
                            } else {
                                debugTrace("scroll node action had no observed effect; using gesture fallback")
                                dispatchSwipeGesture(direction, visualBounds, callback)
                            }
                        }
                    },
                    SCROLL_NODE_EFFECT_SETTLE_MILLIS,
                )
            } else {
                dispatchSwipeGesture(direction, visualBounds, callback)
            }
        }
    }

    private fun dispatchSwipeGesture(direction: ActionType, bounds: ScreenBounds? = null, callback: (Boolean) -> Unit) {
        val left = bounds?.left?.toFloat() ?: 0f
        val top = bounds?.top?.toFloat() ?: 0f
        val width = bounds?.let { (it.right - it.left).toFloat() } ?: resources.displayMetrics.widthPixels.toFloat()
        val height = bounds?.let { (it.bottom - it.top).toFloat() } ?: resources.displayMetrics.heightPixels.toFloat()
        if (width <= 2f || height <= 2f) {
            callback(false)
            return
        }
        val (startX, startY, endX, endY) = when (direction) {
            ActionType.SCROLL_DOWN -> listOf(width * .5f, height * .78f, width * .5f, height * .22f)
            ActionType.SCROLL_UP -> listOf(width * .5f, height * .22f, width * .5f, height * .78f)
            ActionType.SCROLL_RIGHT -> listOf(width * .82f, height * .5f, width * .18f, height * .5f)
            ActionType.SCROLL_LEFT -> listOf(width * .18f, height * .5f, width * .82f, height * .5f)
            else -> {
                callback(false)
                return
            }
        }
        val path = Path().apply {
            moveTo(left + startX, top + startY)
            lineTo(left + endX, top + endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    SWIPE_DURATION_MILLIS,
                ),
            )
            .build()
        clearTrustedSettingsContext()
        dispatchGuardedGesture(gesture, left + startX, top + startY) { accepted ->
            if (accepted) {
                lastExecutionMethod = ExecutionMethod.GESTURE
                invalidateObservedSnapshot()
                debugTrace("scroll gesture fallback completed")
            } else {
                debugTrace("scroll gesture fallback cancelled")
            }
            callback(accepted)
        }
    }
    private fun sameVerifiedScreen(expected: UiSnapshot, live: UiSnapshot): Boolean =
        expected.hasSameRevisionAs(live)

    private fun dispatchVerifiedSynchronousAction(action: AgentAction): Boolean {
        lastDispatchSourceEpoch = epoch.get()
        return dispatchSynchronousAction(action)
    }

    private fun executionWithinDeadline(startedAt: Long): Boolean =
        SystemClock.elapsedRealtime() - startedAt in 0..MAX_EXECUTION_MILLIS

    private fun performGlobalActionClearingRoute(action: Int): Boolean {
        clearTrustedSettingsContext()
        return performGlobalAction(action)
    }

    private fun debugTrace(message: String) {
        if (BuildConfig.DEBUG) Log.d(DEBUG_TAG, message)
    }

    private fun uniqueLeafScrollablePath(snapshot: UiSnapshot): String? {
        if (snapshot.treeTruncated) return null
        val scrollablePaths = snapshot.elements.asSequence()
            .filter { it.scrollable && it.visible && it.enabled && !it.sensitive }
            .map { it.path }
            .toList()
        val effectivePaths = scrollablePaths.filter { path ->
            scrollablePaths.none { otherPath ->
                otherPath != path && otherPath.startsWith("$path.")
            }
        }
        return effectivePaths.singleOrNull()
    }

    companion object {
        private const val DEBUG_TAG = "SonjuFlow"
        private const val SCREENSHOT_OVERLAY_SETTLE_MILLIS = 120L
        private const val VISUAL_TAP_DURATION_MILLIS = 80L
        private const val TEXT_INPUT_FOCUS_SETTLE_MILLIS = 120L
        private const val NODE_ACTION_RETRY_MILLIS = 120L
        private const val TARGET_REVALIDATION_RETRY_MILLIS = 60L
        // App-return animations can exceed 120 ms. These are read-only checks; dispatch occurs
        // once, only after the same target passes the existing content/bounds verification.
        private const val TARGET_REVALIDATION_MAX_RETRIES = 10
        private const val SWIPE_DURATION_MILLIS = 320L
        private const val POSTCONDITION_INITIAL_DELAY_MILLIS = 150L
        private const val POSTCONDITION_RETRY_MILLIS = 350L
        private const val DEFAULT_POSTCONDITION_TIMEOUT_MILLIS = 300L
        private const val MAX_POSTCONDITION_OBSERVE_RETRIES = 8
        private const val MAX_PACKAGE_POSTCONDITION_RETRIES = 12
        private const val RETURN_TO_APP_MILLIS = 800L
        private const val MAX_EXECUTION_MILLIS = 45_000L
        private const val TRUSTED_SETTINGS_ROUTE_TTL_MILLIS = 120_000L
        private const val OBSERVED_SNAPSHOT_TTL_MILLIS = 120_000L
        private const val PLANNING_SNAPSHOT_TTL_MILLIS = 2_000L
        private const val OBSERVED_SNAPSHOT_REFRESH_MILLIS = 10_000L
        private const val POST_EXECUTION_REFRESH_MILLIS = 350L
        private const val OVERLAY_CAPTURE_RETRY_MILLIS = 150L
        private const val OVERLAY_CAPTURE_MAX_RETRIES = 6
        private const val LIVE_SNAPSHOT_CAPTURE_RETRY_MILLIS = 150L
        private const val LIVE_SNAPSHOT_CAPTURE_MAX_RETRIES = 6
        private const val VOICE_PANEL_START_DELAY_MILLIS = 60L
        private const val VOICE_PANEL_TIMEOUT_MILLIS = 25_000L
        private const val VOICE_PANEL_RESULT_DELAY_MILLIS = 220L
        private const val VOICE_COMPLETE_SILENCE_MILLIS = 3_000L
        private const val VOICE_POSSIBLY_COMPLETE_SILENCE_MILLIS = 2_200L
        private const val VOICE_MINIMUM_LENGTH_MILLIS = 1_200L
        private const val VOICE_CONTINUATION_RESTART_MILLIS = 100L
        private const val VOICE_CONTINUATION_GRACE_MILLIS = 2_500L
        private const val TOUCH_INDICATOR_DURATION_MILLIS = 1_000L
        private const val PROACTIVE_SEARCH_SETTLE_MILLIS = 250L
        private const val SCROLL_NODE_EFFECT_SETTLE_MILLIS = 300L
        private const val MAX_PROACTIVE_SEARCH_STEPS = AutonomySession.DEFAULT_MAX_TOOL_CALLS
        private const val MAX_TRANSIENT_PLANNING_RETRIES = 3
        private const val MAX_LOADING_PLANNING_RETRIES = 5
        private val TRANSIENT_LOADING_RETRY_DELAYS_MILLIS =
            longArrayOf(300L, 900L, 1_800L, 4_000L, 8_000L)
        private const val MAX_EVENT_ANCESTOR_DEPTH = 64
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val CONSENT_PREFERENCES = "sonju_preferences"
        private const val QUICK_VOICE_X_KEY = "quick_voice_x_v1"
        private const val QUICK_VOICE_Y_KEY = "quick_voice_y_v1"
        private const val CONSENT_KEY = "accessibility_disclosure_accepted_v1"
        const val EXTRA_FROM_OVERLAY = "com.hwanghj09.sonju.extra.FROM_OVERLAY"
        const val EXTRA_AUTO_START_VOICE = "com.hwanghj09.sonju.extra.AUTO_START_VOICE"
        const val EXTRA_VOICE_COMMAND = "com.hwanghj09.sonju.extra.VOICE_COMMAND"
        private const val BAEMIN_INITIAL_DELAY_MILLIS = 1_500L
        private const val BAEMIN_STEP_SETTLE_MILLIS = 900L
        private const val BAEMIN_SESSION_TIMEOUT_MILLIS = 5 * 60_000L
        private const val BAEMIN_MAX_STEPS = 14

        @Volatile
        var instance: SonjuAccessibilityService? = null
            private set

        @Volatile
        private var pendingOverlayContext: PendingOverlayContext? = null

        @Synchronized
        private fun setPendingOverlayContext(context: PendingOverlayContext) {
            pendingOverlayContext = context
        }

        @Synchronized
        private fun clearPendingOverlayContext(sessionId: Long? = null) {
            if (sessionId == null || pendingOverlayContext?.sessionId == sessionId) {
                pendingOverlayContext = null
            }
        }

        @Synchronized
        fun consumePendingOverlayContext(): PendingOverlayContext? {
            val context = pendingOverlayContext
            pendingOverlayContext = null
            return context?.takeIf {
                ContextLifetime.isFresh(
                    SystemClock.elapsedRealtime(),
                    it.capturedAtElapsedRealtime,
                    OVERLAY_CONTEXT_TTL_MILLIS,
                )
            }
        }

        private const val OVERLAY_CONTEXT_TTL_MILLIS = 120_000L

        private val APP_LABEL_ALIASES = mapOf(
            "유튜브" to setOf("youtube"),
            "카톡" to setOf("카카오톡", "kakaotalk"),
            "배민" to setOf("배달의민족"),
            "노트" to setOf("삼성 노트", "삼성노트", "samsung notes", "notes"),
            "네이버" to setOf("naver"),
            "크롬" to setOf("chrome"),
            "구글 지도" to setOf("지도", "maps", "google maps"),
            "구글지도" to setOf("지도", "maps", "google maps"),
        )

        private val APP_PACKAGE_HINTS = mapOf(
            "카톡" to "com.kakao.talk",
            "카카오톡" to "com.kakao.talk",
            "kakaotalk" to "com.kakao.talk",
            "노트" to "com.samsung.android.app.notes",
            "삼성노트" to "com.samsung.android.app.notes",
            "notes" to "com.samsung.android.app.notes",
            "크롬" to "com.android.chrome",
            "chrome" to "com.android.chrome",
            "배민" to BaeminNavigator.PACKAGE_NAME,
            "배달의민족" to BaeminNavigator.PACKAGE_NAME,
        )

        private val SCREEN_INDEPENDENT_ACTIONS = setOf(
            ActionType.START_TIMER,
            ActionType.OPEN_APP,
            ActionType.OPEN_URL,
            ActionType.OPEN_WIFI_SETTINGS,
            ActionType.OPEN_SOUND_SETTINGS,
            ActionType.OPEN_ACCESSIBILITY_SETTINGS,
            ActionType.OPEN_DISPLAY_SETTINGS,
            ActionType.OPEN_DATE_SETTINGS,
            ActionType.OPEN_CAMERA,
            ActionType.OPEN_DIALER,
            ActionType.OPEN_MESSAGES,
            ActionType.HOME,
            ActionType.NOTIFICATIONS,
            ActionType.QUICK_SETTINGS,
        )

        private val SELF_AUTHENTICATING_POSTCONDITIONS = setOf(
            ActionType.START_TIMER,
            ActionType.OPEN_APP,
            ActionType.OPEN_URL,
            ActionType.OPEN_WIFI_SETTINGS,
            ActionType.OPEN_SOUND_SETTINGS,
            ActionType.OPEN_ACCESSIBILITY_SETTINGS,
            ActionType.OPEN_DISPLAY_SETTINGS,
            ActionType.OPEN_DATE_SETTINGS,
            ActionType.OPEN_CAMERA,
            ActionType.OPEN_DIALER,
            ActionType.OPEN_MESSAGES,
            ActionType.HOME,
            ActionType.NOTIFICATIONS,
            ActionType.QUICK_SETTINGS,
            ActionType.SET_TEXT,
        )

        private val PACKAGE_IDENTITY_POSTCONDITIONS = setOf(
            ActionType.OPEN_APP,
            ActionType.OPEN_CAMERA,
            ActionType.OPEN_DIALER,
            ActionType.OPEN_MESSAGES,
            ActionType.HOME,
        )

        private val SETTINGS_ACTIVITY_SUFFIXES = mapOf(
            TrustedSettingsRoute.WIFI to setOf("Settings\$WifiSettingsActivity"),
            TrustedSettingsRoute.SOUND to setOf("Settings\$SoundSettingsActivity"),
            TrustedSettingsRoute.ACCESSIBILITY to setOf("Settings\$AccessibilitySettingsActivity"),
            TrustedSettingsRoute.DISPLAY to setOf("Settings\$DisplaySettingsActivity"),
            TrustedSettingsRoute.DATE_TIME to setOf("Settings\$DateTimeSettingsActivity"),
        )

        private val SETTINGS_TITLES = mapOf(
            TrustedSettingsRoute.WIFI to setOf(
                "wifi", "와이파이", "internet", "인터넷", "networkinternet", "네트워크및인터넷",
            ),
            TrustedSettingsRoute.SOUND to setOf("sound", "soundvibration", "소리", "소리및진동"),
            TrustedSettingsRoute.ACCESSIBILITY to setOf("accessibility", "접근성"),
            TrustedSettingsRoute.DISPLAY to setOf("display", "displaytouch", "디스플레이"),
            TrustedSettingsRoute.DATE_TIME to setOf("datetime", "날짜및시간"),
        )

        private fun isExpectedSettingsActivity(
            route: TrustedSettingsRoute,
            className: String,
        ): Boolean = SETTINGS_ACTIVITY_SUFFIXES[route].orEmpty().any(className::endsWith)

        private fun isExpectedSettingsTitle(route: TrustedSettingsRoute, title: String): Boolean =
            compactSettingsLabel(title) in SETTINGS_TITLES[route].orEmpty()

        private fun compactSettingsLabel(value: String): String = Normalizer.normalize(
            value,
            Normalizer.Form.NFKC,
        ).lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
    }
}
