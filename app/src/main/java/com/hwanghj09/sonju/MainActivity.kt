package com.hwanghj09.sonju

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.NestedScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.hwanghj09.sonju.accessibility.SonjuAccessibilityService
import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.AgentPlan
import com.hwanghj09.sonju.agent.AppWorkflowRouter
import com.hwanghj09.sonju.agent.AutonomySession
import com.hwanghj09.sonju.agent.ContextLifetime
import com.hwanghj09.sonju.agent.LearnedRouteMemory
import com.hwanghj09.sonju.agent.PlanSource
import com.hwanghj09.sonju.agent.RuleBasedPlanner
import com.hwanghj09.sonju.agent.RiskLevel
import com.hwanghj09.sonju.agent.SafetyAssessment
import com.hwanghj09.sonju.agent.SafetyDecision
import com.hwanghj09.sonju.agent.ScreenExplainer
import com.hwanghj09.sonju.agent.SonjuAgentRuntime
import com.hwanghj09.sonju.agent.UiSnapshot
import com.hwanghj09.sonju.agent.displayName
import com.hwanghj09.sonju.ai.OpenAiPlanner
import com.hwanghj09.sonju.shopping.BaeminOrderLocalPlanner
import com.hwanghj09.sonju.voice.WakeWordService
import com.hwanghj09.sonju.voice.ListeningOverlayView
import com.hwanghj09.sonju.voice.CommandRecognition
import com.hwanghj09.sonju.voice.CommandSpeechRecognizer
import com.hwanghj09.sonju.verifier.VerificationResult
import com.hwanghj09.sonju.verifier.VerifiedPlan
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var serviceStatusCard: MaterialCardView
    private lateinit var serviceStatusDot: View
    private lateinit var serviceStatusTitle: TextView
    private lateinit var serviceStatusDescription: TextView
    private lateinit var serviceActionButton: MaterialButton
    private lateinit var wakeWordDescription: TextView
    private lateinit var wakeWordActionButton: MaterialButton
    private lateinit var mainScroll: NestedScrollView
    private lateinit var commandInput: TextInputEditText
    private lateinit var voiceReviewText: TextView
    private lateinit var voiceButton: MaterialButton
    private lateinit var listeningOverlay: ListeningOverlayView
    private lateinit var runCommandButton: MaterialButton
    private lateinit var progressCard: MaterialCardView
    private lateinit var progressDetail: TextView
    private lateinit var progressIndicator: LinearProgressIndicator
    private lateinit var resultCard: MaterialCardView
    private lateinit var resultText: TextView
    private var competingControls: List<View> = emptyList()

    private val openAiPlanner = OpenAiPlanner(installedApps = {
        com.hwanghj09.sonju.agent.InstalledApps.query(packageManager)
    })
    private val architectureRuntime by lazy { SonjuAgentRuntime.get(this) }
    private lateinit var learnedRouteMemory: LearnedRouteMemory
    private var autonomySession: AutonomySession? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var fromOverlay = false
    private var externalSnapshot: UiSnapshot? = null
    private var externalSemanticMapJpegBase64: String? = null
    private var externalContextCapturedAtElapsedRealtime = 0L
    private var externalContextSessionId = 0L
    private val contextExpiryHandler = Handler(Looper.getMainLooper())
    private var contextExpiryRunnable: Runnable? = null
    private var confirmationDialog: AlertDialog? = null
    private var awaitingVoiceRecognition = false
    private var autoExecuteVoiceResult = false
    private var voiceOverlaySessionId = 0L
    private var busy = false
    private var requestGeneration = 0L
    private var automaticCommandRunnable: Runnable? = null
    private var voiceRecognizer: CommandSpeechRecognizer? = null
    private var voiceRecognitionGeneration = 0L
    private var voiceStartRunnable: Runnable? = null
    private var voiceTimeoutRunnable: Runnable? = null
    private var pendingVoiceAutoExecute: Boolean? = null
    private var previousLightStatusBars = true
    private var previousLightNavigationBars = true
    private val voiceBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = finishVoiceInput()
    }

    private val voicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val autoExecute = pendingVoiceAutoExecute ?: return@registerForActivityResult
        pendingVoiceAutoExecute = null
        if (granted) startVoiceInput(autoExecute)
        else showResult(getString(R.string.voice_input_permission_denied), success = false)
    }

    private val wakeWordPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            setWakeWordEnabled(true)
            startWakeWordService()
        } else {
            showToast(getString(R.string.wake_word_permission_denied))
            updateWakeWordStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(
                OnboardingActivity.KEY_ONBOARDING_COMPLETED,
                false,
            )
        ) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        bindViews()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            mainScroll.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            listeningOverlay.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        onBackPressedDispatcher.addCallback(this, voiceBackCallback)
        listeningOverlay.onCancel { finishVoiceInput() }
        ViewCompat.setAccessibilityPaneTitle(listeningOverlay, getString(R.string.voice_listening))
        configureActions()
        learnedRouteMemory = LearnedRouteMemory(this)
        textToSpeech = TextToSpeech(this, this)
        receiveOverlayContext(intent)

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finishVoiceInput(animated = false)
        setIntent(intent)
        receiveOverlayContext(intent)
    }

    override fun onResume() {
        super.onResume()
        maybeRestoreWakeWordService()
        updateServiceStatus()
        updateWakeWordStatus()
    }

    override fun onStop() {
        pendingVoiceAutoExecute = null
        finishVoiceInput(animated = false)
        if (fromOverlay && !awaitingVoiceRecognition) {
            requestGeneration += 1
            openAiPlanner.cancelPending()
            setBusy(false, keepProgress = false)
            clearOverlayContext()
        }
        super.onStop()
    }

    override fun onDestroy() {
        finishVoiceInput(animated = false)
        requestGeneration += 1
        automaticCommandRunnable?.let(contextExpiryHandler::removeCallbacks)
        automaticCommandRunnable = null
        awaitingVoiceRecognition = false
        clearOverlayContext()
        openAiPlanner.close()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = (textToSpeech?.setLanguage(Locale.KOREAN) ?: TextToSpeech.LANG_NOT_SUPPORTED) >=
                TextToSpeech.LANG_AVAILABLE
        }
    }

    private fun bindViews() {
        mainScroll = findViewById(R.id.mainScroll)
        serviceStatusCard = findViewById(R.id.serviceStatusCard)
        serviceStatusDot = findViewById(R.id.serviceStatusDot)
        serviceStatusTitle = findViewById(R.id.serviceStatusTitle)
        serviceStatusDescription = findViewById(R.id.serviceStatusDescription)
        serviceActionButton = findViewById(R.id.serviceActionButton)
        wakeWordDescription = findViewById(R.id.wakeWordDescription)
        wakeWordActionButton = findViewById(R.id.wakeWordActionButton)
        commandInput = findViewById(R.id.commandInput)
        voiceReviewText = findViewById(R.id.voiceReviewText)
        voiceButton = findViewById(R.id.voiceButton)
        listeningOverlay = findViewById(R.id.listeningOverlay)
        runCommandButton = findViewById(R.id.runCommandButton)
        progressCard = findViewById(R.id.progressCard)
        progressDetail = findViewById(R.id.progressDetail)
        progressIndicator = findViewById(R.id.progressIndicator)
        resultCard = findViewById(R.id.resultCard)
        resultText = findViewById(R.id.resultText)
        competingControls = listOf<View>(
            serviceActionButton,
            wakeWordActionButton,
            findViewById(R.id.chipWifi),
            findViewById(R.id.chipDisplay),
            findViewById(R.id.chipScroll),
            findViewById(R.id.chipBack),
            findViewById(R.id.chipCamera),
            findViewById(R.id.privacyDetailsButton),
        )
    }

    private fun configureActions() {
        serviceActionButton.setOnClickListener {
            val accepted = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_DISCLOSURE_ACCEPTED, false)
            when {
                !accepted -> showPrivacyDisclosure(firstRun = true)
                isServiceEnabledInSettings() -> openAccessibilitySettings()
                else -> showAccessibilityGuide()
            }
        }
        wakeWordActionButton.setOnClickListener {
            if (WakeWordService.running) {
                stopWakeWordService()
            } else {
                showWakeWordDisclosure()
            }
        }
        voiceButton.setOnClickListener {
            val accepted = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_VOICE_DISCLOSURE_ACCEPTED, false)
            if (accepted) startVoiceInput(autoExecute = true)
            else showVoiceDisclosure(autoExecute = true)
        }
        runCommandButton.setOnClickListener { handleCommand() }
        commandInput.doAfterTextChanged {
            if (voiceReviewText.visibility == View.VISIBLE) {
                voiceReviewText.visibility = View.GONE
            }
        }
        commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                handleCommand()
                true
            } else {
                false
            }
        }

        findViewById<View>(R.id.chipWifi).setOnClickListener {
            runQuickCommand(getString(R.string.quick_wifi))
        }
        findViewById<View>(R.id.chipDisplay).setOnClickListener {
            runQuickCommand(getString(R.string.quick_display))
        }
        findViewById<View>(R.id.chipScroll).setOnClickListener {
            runScreenQuickCommand(getString(R.string.quick_scroll))
        }
        findViewById<View>(R.id.chipBack).setOnClickListener {
            runScreenQuickCommand(getString(R.string.quick_back))
        }
        findViewById<View>(R.id.chipCamera).setOnClickListener {
            runQuickCommand(getString(R.string.quick_camera))
        }
        findViewById<View>(R.id.privacyDetailsButton).setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }
        findViewById<View>(R.id.stopButton).setOnClickListener { stopCurrentWork() }
    }

    private fun receiveOverlayContext(intent: Intent) {
        val directVoiceCommand = intent.getStringExtra(
            SonjuAccessibilityService.EXTRA_VOICE_COMMAND,
        )?.trim()?.takeIf(String::isNotBlank)
        val autoStartVoice = intent.getBooleanExtra(
            SonjuAccessibilityService.EXTRA_AUTO_START_VOICE,
            false,
        )
        val requestedFromOverlay = intent.getBooleanExtra(
            SonjuAccessibilityService.EXTRA_FROM_OVERLAY,
            false,
        )
        if (BuildConfig.DEBUG) android.util.Log.d("SonjuFlow", "command received direct=${directVoiceCommand != null} overlay=$requestedFromOverlay")
        if (!requestedFromOverlay) {
            if (busy || confirmationDialog != null) {
                requestGeneration += 1
                openAiPlanner.cancelPending()
                SonjuAccessibilityService.instance?.stopCurrentExecution()
                autonomySession = null
                setBusy(false, keepProgress = false)
            }
            clearOverlayContext()
            when {
                directVoiceCommand != null -> scheduleAccessibilityCommand(directVoiceCommand)
                autoStartVoice -> scheduleAutomaticVoiceInput()
            }
            return
        }
        if (fromOverlay || busy || confirmationDialog != null) {
            requestGeneration += 1
            openAiPlanner.cancelPending()
            setBusy(false, keepProgress = false)
        }
        clearOverlayContext()
        val context = SonjuAccessibilityService.consumePendingOverlayContext()
        if (context == null) {
            showResult(getString(R.string.overlay_context_expired), success = false)
            return
        }
        fromOverlay = true
        externalSnapshot = context.snapshot
        externalSemanticMapJpegBase64 = context.semanticMapJpegBase64
        externalContextCapturedAtElapsedRealtime = context.capturedAtElapsedRealtime
        externalContextSessionId = context.sessionId
        scheduleOverlayContextExpiry(context.sessionId, context.capturedAtElapsedRealtime)
        if (directVoiceCommand != null) {
            scheduleAutomaticCommand(directVoiceCommand)
        } else if (autoStartVoice) {
            scheduleAutomaticVoiceInput()
        } else {
            commandInput.post {
                commandInput.requestFocus()
                showKeyboard()
            }
        }
    }

    private fun scheduleAutomaticVoiceInput() {
        commandInput.post {
            val accepted = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_VOICE_DISCLOSURE_ACCEPTED, false)
            if (accepted) {
                startVoiceInput(autoExecute = true)
            } else {
                showVoiceDisclosure(autoExecute = true)
            }
        }
    }

    private fun scheduleAutomaticCommand(command: String) {
        automaticCommandRunnable?.let(contextExpiryHandler::removeCallbacks)
        val runnable = Runnable {
            automaticCommandRunnable = null
            val safeCommand = command.take(500)
            commandInput.setText(safeCommand)
            commandInput.setSelection(safeCommand.length)
            voiceReviewText.visibility = View.GONE
            resumeWakeWordListening()
            handleCommand()
        }
        automaticCommandRunnable = runnable
        contextExpiryHandler.post(runnable)
    }

    /** Keep externally supplied voice commands on the same observe/replan executor as wake words. */
    private fun scheduleAccessibilityCommand(command: String) {
        automaticCommandRunnable?.let(contextExpiryHandler::removeCallbacks)
        val startedAt = SystemClock.elapsedRealtime()
        val generation = requestGeneration
        val runnable = object : Runnable {
            override fun run() {
                if (generation != requestGeneration || isFinishing || isDestroyed) return
                val service = SonjuAccessibilityService.instance
                val enabled = isServiceEnabledInSettings()
                if (BuildConfig.DEBUG) android.util.Log.d("SonjuFlow", "command dispatch connected=${service != null} enabled=$enabled")
                if (enabled && service == null && SystemClock.elapsedRealtime() - startedAt < 5_000) {
                    contextExpiryHandler.postDelayed(this, 250)
                    return
                }
                automaticCommandRunnable = null
                if (service == null || !enabled) {
                    scheduleAutomaticCommand(command)
                    return
                }
                service.requestVoiceWake(command)
                finish()
            }
        }
        automaticCommandRunnable = runnable
        contextExpiryHandler.post(runnable)
    }

    private fun updateServiceStatus() {
        val connected = SonjuAccessibilityService.instance != null && isServiceEnabledInSettings()
        val backgroundColor = if (connected) R.color.sonju_green_soft else R.color.sonju_orange_soft
        val dotColor = if (connected) R.color.sonju_success else R.color.sonju_warning
        serviceStatusCard.setCardBackgroundColor(ContextCompat.getColor(this, backgroundColor))
        serviceStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, dotColor))
        serviceStatusTitle.setText(
            if (connected) R.string.service_ready_title else R.string.service_off_title,
        )
        serviceStatusDescription.setText(
            if (connected) R.string.service_ready_description else R.string.service_off_description,
        )
        serviceActionButton.setText(
            if (connected) R.string.service_check_again else R.string.service_open_settings,
        )
    }

    private fun isServiceEnabledInSettings(): Boolean {
        val expected = ComponentName(this, SonjuAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabledServices.split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == expected }
    }

    private fun startVoiceInput(autoExecute: Boolean = false) {
        if (busy || awaitingVoiceRecognition || pendingVoiceAutoExecute != null ||
            listeningOverlay.visibility == View.VISIBLE || isFinishing || isDestroyed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            pendingVoiceAutoExecute = autoExecute
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showResult(getString(R.string.voice_input_unavailable), success = false)
            return
        }
        textToSpeech?.stop()
        SonjuAccessibilityService.instance?.silenceForVoiceInput()
        hideKeyboard()
        pauseWakeWordListening()
        autoExecuteVoiceResult = autoExecute
        voiceReviewText.visibility = View.GONE
        awaitingVoiceRecognition = true
        voiceOverlaySessionId = if (fromOverlay) externalContextSessionId else 0L
        val generation = ++voiceRecognitionGeneration
        voiceButton.isEnabled = false
        mainScroll.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        voiceBackCallback.isEnabled = true
        WindowCompat.getInsetsController(window, window.decorView).apply {
            previousLightStatusBars = isAppearanceLightStatusBars
            previousLightNavigationBars = isAppearanceLightNavigationBars
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        listeningOverlay.showFrom(voiceButton)
        voiceTimeoutRunnable = Runnable {
            if (generation == voiceRecognitionGeneration) {
                finishVoiceInput(errorRes = R.string.voice_input_no_result)
            }
        }.also { contextExpiryHandler.postDelayed(it, 45_000L) }
        // Let the wake-word service release its microphone while the button expands.
        voiceStartRunnable = Runnable {
            voiceStartRunnable = null
            if (generation != voiceRecognitionGeneration || !awaitingVoiceRecognition) return@Runnable
            runCatching {
                voiceRecognizer = CommandSpeechRecognizer(this).also {
                    it.setRecognitionListener(voiceRecognitionListener(generation))
                }
                voiceRecognizer?.startListening(CommandRecognition.intent(this))
            }.onFailure { finishVoiceInput(errorRes = R.string.voice_input_unavailable) }
        }.also { contextExpiryHandler.postDelayed(it, 180L) }
    }

    private fun voiceRecognitionListener(generation: Long) = object : RecognitionListener {
        private fun isCurrent() = awaitingVoiceRecognition && generation == voiceRecognitionGeneration

        override fun onReadyForSpeech(params: Bundle?) {
            if (isCurrent()) listeningOverlay.setListening(true)
        }

        override fun onBeginningOfSpeech() {
            if (isCurrent()) listeningOverlay.onSpeechStarted()
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (isCurrent()) listeningOverlay.updateSoundLevel(rmsdB)
        }

        override fun onEndOfSpeech() {
            if (isCurrent()) listeningOverlay.setListening(false)
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrent()) return
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
            listeningOverlay.showTranscript(heard)
            finishVoiceInput(heard, if (heard == null) R.string.voice_input_no_result else null)
        }

        override fun onError(error: Int) {
            if (!isCurrent()) return
            finishVoiceInput(errorRes = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_input_no_result
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.voice_input_network_error
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_input_permission_denied
                else -> R.string.voice_input_unavailable
            })
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onPartialResults(partialResults: Bundle?) {
            if (!isCurrent()) return
            listeningOverlay.showTranscript(
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(),
            )
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun finishVoiceInput(heard: String? = null, errorRes: Int? = null, animated: Boolean = true) {
        if (!::listeningOverlay.isInitialized ||
            (!awaitingVoiceRecognition && listeningOverlay.visibility != View.VISIBLE)) return
        val shouldAutoExecute = autoExecuteVoiceResult
        val launchedOverlaySessionId = voiceOverlaySessionId
        val wasListening = awaitingVoiceRecognition
        val generation = ++voiceRecognitionGeneration
        awaitingVoiceRecognition = false
        autoExecuteVoiceResult = false
        voiceOverlaySessionId = 0L
        voiceStartRunnable?.let(contextExpiryHandler::removeCallbacks)
        voiceStartRunnable = null
        voiceTimeoutRunnable?.let(contextExpiryHandler::removeCallbacks)
        voiceTimeoutRunnable = null
        val recognizer = voiceRecognizer
        voiceRecognizer = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        if (wasListening) resumeWakeWordListening()
        voiceBackCallback.isEnabled = false
        listeningOverlay.hide(animated) {
            if (generation != voiceRecognitionGeneration) return@hide
            mainScroll.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            voiceButton.isEnabled = !busy
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = previousLightStatusBars
                isAppearanceLightNavigationBars = previousLightNavigationBars
            }
            if (isFinishing || isDestroyed) return@hide
            if (errorRes != null) {
                showResult(getString(errorRes), success = false)
                voiceButton.requestFocus()
                return@hide
            }
            if (heard == null) {
                voiceButton.requestFocus()
                return@hide
            }
            if (launchedOverlaySessionId != 0L &&
                (!fromOverlay || externalContextSessionId != launchedOverlaySessionId ||
                    !ContextLifetime.isFresh(SystemClock.elapsedRealtime(),
                        externalContextCapturedAtElapsedRealtime, OVERLAY_CONTEXT_TTL_MILLIS))) {
                val message = getString(R.string.overlay_context_expired)
                showResult(message, success = false)
                speak(message)
                return@hide
            }
            commandInput.setText(heard)
            commandInput.setSelection(heard.length)
            if (shouldAutoExecute) {
                handleCommand()
            } else {
                val reviewMessage = getString(R.string.voice_review, heard)
                resultCard.visibility = View.GONE
                voiceReviewText.text = reviewMessage
                voiceReviewText.visibility = View.VISIBLE
                runCommandButton.requestFocus()
                speak(reviewMessage)
            }
        }
    }

    private fun showVoiceDisclosure(autoExecute: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.voice_privacy_title)
            .setMessage(R.string.voice_privacy_message)
            .setNegativeButton(R.string.privacy_not_now, null)
            .setPositiveButton(R.string.voice_privacy_accept) { _, _ ->
                getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_VOICE_DISCLOSURE_ACCEPTED, true)
                    .apply()
                startVoiceInput(autoExecute)
            }
            .show()
    }

    private fun updateWakeWordStatus() {
        wakeWordDescription.setText(
            if (WakeWordService.running) {
                R.string.wake_word_on_description
            } else {
                R.string.wake_word_off_description
            },
        )
        wakeWordActionButton.setText(
            if (WakeWordService.running) R.string.wake_word_stop else R.string.wake_word_start,
        )
    }

    private fun showWakeWordDisclosure() {
        if (!isServiceEnabledInSettings()) {
            showToast(getString(R.string.wake_word_accessibility_required))
            showAccessibilityGuide()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            showToast(getString(R.string.wake_word_on_device_unavailable))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wake_word_disclosure_title)
            .setMessage(R.string.wake_word_disclosure_message)
            .setNegativeButton(R.string.privacy_not_now, null)
            .setPositiveButton(R.string.wake_word_disclosure_accept) { _, _ ->
                requestWakeWordPermissions()
            }
            .show()
    }

    private fun requestWakeWordPermissions() {
        val permissions = buildList {
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isEmpty()) {
            setWakeWordEnabled(true)
            startWakeWordService()
        } else {
            wakeWordPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun maybeRestoreWakeWordService() {
        val shouldRun = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_WAKE_WORD_ENABLED, false)
        if (!shouldRun || WakeWordService.running || !isServiceEnabledInSettings()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            return
        }
        startWakeWordService()
    }

    private fun setWakeWordEnabled(enabled: Boolean) {
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WAKE_WORD_ENABLED, enabled)
            .apply()
    }

    private fun startWakeWordService() {
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, WakeWordService::class.java),
            )
        }.onFailure {
            showToast(getString(R.string.generic_error))
        }
        wakeWordActionButton.postDelayed({ updateWakeWordStatus() }, 500L)
    }

    private fun stopWakeWordService() {
        setWakeWordEnabled(false)
        stopService(Intent(this, WakeWordService::class.java))
        wakeWordActionButton.postDelayed({ updateWakeWordStatus() }, 300L)
    }

    private fun pauseWakeWordListening() {
        WakeWordService.pauseForCommand()
    }

    private fun resumeWakeWordListening() {
        sendWakeWordAction(WakeWordService.ACTION_RESUME)
    }

    private fun sendWakeWordAction(action: String) {
        if (!WakeWordService.running) return
        startService(Intent(this, WakeWordService::class.java).setAction(action))
    }

    private fun runQuickCommand(command: String) {
        commandInput.setText(command)
        commandInput.setSelection(command.length)
        handleCommand()
    }

    private fun runScreenQuickCommand(command: String) {
        commandInput.setText(command)
        commandInput.setSelection(command.length)
        attachRecentApplicationContext()
        if (fromOverlay && externalSnapshot != null) {
            handleCommand()
        } else {
            val message = getString(R.string.quick_requires_overlay)
            showResult(message, success = false)
            showToast(message)
            speak(message)
        }
    }

    private fun handleCommand() {
        if (busy) return
        val requestedCommand = commandInput.text?.toString()?.trim().orEmpty()
        if (requestedCommand.isNotBlank() && !ScreenExplainer.isExplanationRequest(requestedCommand)) {
            if (SonjuAccessibilityService.instance == null || !isServiceEnabledInSettings()) {
                finishBusyWithMessage(getString(R.string.accessibility_required), success = false)
                return
            }
            hideKeyboard()
            requestGeneration++
            openAiPlanner.cancelPending()
            clearOverlayContext()
            scheduleAccessibilityCommand(requestedCommand)
            return
        }
        attachRecentApplicationContext()
        if (fromOverlay && !ContextLifetime.isFresh(
                SystemClock.elapsedRealtime(),
                externalContextCapturedAtElapsedRealtime,
                OVERLAY_CONTEXT_TTL_MILLIS,
            )
        ) {
            clearOverlayContext()
            val message = getString(R.string.overlay_context_expired)
            showResult(message, success = false)
            speak(message)
            return
        }
        val command = commandInput.text?.toString()?.trim().orEmpty()
        if (command.isBlank()) {
            commandInput.error = getString(R.string.empty_command)
            commandInput.requestFocus()
            return
        }
        commandInput.error = null
        voiceReviewText.visibility = View.GONE
        resultCard.visibility = View.GONE
        hideKeyboard()
        val generation = ++requestGeneration
        setBusy(true)
        showProgress(getString(R.string.progress_observe))

        val snapshot = externalSnapshot ?: UiSnapshot.empty()
        externalSnapshot = null
        val preRenderedSemanticMap = externalSemanticMapJpegBase64
        externalSemanticMapJpegBase64 = null

        val session = autonomySession?.takeIf { it.finalGoal == command }
            ?: AutonomySession(
                finalGoal = command,
                initialSnapshot = snapshot,
                startedAtMillis = SystemClock.elapsedRealtime(),
            ).also { autonomySession = it }
        session.observe(snapshot)

        if (ScreenExplainer.isExplanationRequest(command)) {
            if (snapshot.packageName == "unknown") {
                finishBusyWithMessage(getString(R.string.quick_requires_overlay), success = false)
                return
            }
            val appLabel = runCatching {
                @Suppress("DEPRECATION")
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(snapshot.packageName, 0),
                ).toString()
            }.getOrDefault(snapshot.windowTitle.orEmpty())
            val explanation = ScreenExplainer.explain(
                command = command,
                appLabel = appLabel,
                snapshot = snapshot,
                browserUrl = ScreenExplainer.detectBrowserUrl(snapshot),
            )
            autonomySession = null
            finishBusyWithMessage(explanation, success = true)
            speak(explanation)
            return
        }

        if (com.hwanghj09.sonju.agent.UserIntervention.required(snapshot, command) != null) {
            val service = SonjuAccessibilityService.instance
            if (service != null) {
                service.continueAutonomousCommand(command, session)
                autonomySession = null
            }
            finishBusyWithMessage("현재 앱에서 직접 확인을 마치면 손주가 요청을 이어갑니다.", success = false)
            if (service != null) moveTaskToBack(true)
            return
        }
        val baeminPlan = BaeminOrderLocalPlanner.plan(command, snapshot)
        if (baeminPlan != null) {
            showProgress(getString(R.string.progress_check))
            handlePlan(command, snapshot, baeminPlan)
            return
        }

        val planningService = SonjuAccessibilityService.instance
        val appWorkflowRoute = if (planningService != null) planningService.resolveAppWorkflowRoute(command)
            else if (RuleBasedPlanner.plan(command, snapshot) == null) AppWorkflowRouter.route(command) else null
        val appEntryPlan = appWorkflowRoute?.let { route ->
            AppWorkflowRouter.entryPlan(
                command = command,
                route = route,
                currentPackage = snapshot.packageName,
                targetPackage = SonjuAccessibilityService.instance
                    ?.resolveInstalledAppPackage(route.appLabel),
            )
        }
        if (appEntryPlan != null) {
            showProgress(getString(R.string.progress_check))
            handlePlan(command, snapshot, appEntryPlan)
            return
        }

        val inAppPlan = appWorkflowRoute?.let { route ->
            AppWorkflowRouter.inAppPlan(
                command,
                route,
                snapshot,
                successfulActions = session.successfulActions(),
            )
        }
        if (inAppPlan != null) {
            showProgress(getString(R.string.progress_check))
            handlePlan(command, snapshot, inAppPlan)
            return
        }
        val fastPathPlan = architectureRuntime.fastPathPlan(command, snapshot, session)
        if (fastPathPlan != null) {
            showProgress(getString(R.string.progress_check))
            handlePlan(command, snapshot, fastPathPlan)
            return
        }
        val localPlan = if (appWorkflowRoute == null) {
            RuleBasedPlanner.plan(command, snapshot)
        } else {
            null
        }
        if (localPlan != null) {
            showProgress(getString(R.string.progress_check))
            handlePlan(command, snapshot, localPlan)
            return
        }

        if (!openAiPlanner.isConfigured) {
            finishBusyWithMessage(getString(R.string.api_missing), success = false)
            return
        }

        showProgress(getString(R.string.progress_plan))
        if (preRenderedSemanticMap != null) {
            progressDetail.text = "민감 정보를 뺀 버튼 배치도를 한 번 더 확인하고 있어요"
            requestOpenAiPlan(
                command,
                snapshot,
                preRenderedSemanticMap,
                generation,
            )
        } else {
            requestOpenAiPlan(
                command,
                snapshot,
                semanticMap = null,
                generation,
            )
        }
    }

    private fun requestOpenAiPlan(
        command: String,
        snapshot: UiSnapshot,
        semanticMap: String?,
        generation: Long,
    ) {
        val session = autonomySession?.takeIf { it.finalGoal == command }
        val routeHint = learnedRouteMemory.recallHint(
            goal = command,
            snapshot = snapshot,
            targetApp = session?.latestPlan?.targetApp,
        )
        openAiPlanner.planAsync(
            command = command,
            snapshot = snapshot,
            semanticMapJpegBase64 = semanticMap,
            autonomyContext = session?.plannerContext(snapshot, routeHint),
        ) { result ->
            runOnUiThread {
                if (generation != requestGeneration || isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { plan ->
                        showProgress(getString(R.string.progress_check))
                        handlePlan(command, snapshot, plan)
                    },
                    onFailure = {
                        clearOverlayContext()
                        finishBusyWithMessage(getString(R.string.generic_error), success = false)
                    },
                )
            }
        }
    }

    private fun handlePlan(command: String, snapshot: UiSnapshot, candidate: AgentPlan) {
        val plan = autonomySession?.takeIf { it.finalGoal == command }
            ?.acceptPlan(candidate, snapshot)
            ?: candidate
        if (plan.goalCompleted && plan.actions.none { it.type != ActionType.FINISH }) {
            if (!architectureRuntime.goalSatisfied(command, plan, snapshot, autonomySession)) {
                showBlocked(
                    SafetyAssessment(
                        SafetyDecision.BLOCK,
                        RiskLevel.BLOCKED,
                        "최종 목표 상태를 실제 화면에서 확인하지 못해 완료로 처리하지 않았습니다.",
                    ),
                )
                return
            }
            autonomySession?.takeIf { it.finalGoal == command }?.let { session ->
                learnedRouteMemory.remember(session, plan)
                architectureRuntime.rememberSuccessfulSkill(command, session, snapshot)
            }
            autonomySession = null
            val message = "최종 목표를 화면에서 확인했어요. " +
                plan.summary.ifBlank { "요청한 상태에 도달했습니다." }
            finishBusyWithMessage(message, success = true)
            speak(message)
            return
        }
        if (!fromOverlay && plan.actions.any { it.type.requiresExternalScreen() }) {
            showBlocked(
                SafetyAssessment(
                    decision = SafetyDecision.BLOCK,
                    level = RiskLevel.BLOCKED,
                    reason = getString(R.string.quick_requires_overlay),
                ),
            )
            return
        }
        when (val verification = architectureRuntime.verify(command, plan, snapshot)) {
            is VerificationResult.Blocked -> showBlocked(
                SafetyAssessment(SafetyDecision.BLOCK, RiskLevel.BLOCKED, verification.reason),
            )
            is VerificationResult.NeedsReplan -> showBlocked(
                SafetyAssessment(SafetyDecision.BLOCK, RiskLevel.BLOCKED, verification.reason),
            )
            is VerificationResult.NeedsConfirmation ->
                showConfirmation(command, plan, verification, snapshot)
            is VerificationResult.Allowed -> executePlan(
                command,
                verification.verifiedPlan,
                snapshot,
            )
        }
    }

    private fun showConfirmation(
        command: String,
        plan: AgentPlan,
        verification: VerificationResult.NeedsConfirmation,
        snapshot: UiSnapshot,
    ) {
        val confirmationSessionId = externalContextSessionId
        setBusy(false, keepProgress = true)
        val steps = plan.actions
            .filterNot { it.type.name == "FINISH" }
            .joinToString("\n") { action ->
                val target = action.target?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                val value = action.value?.takeIf { it.isNotBlank() }
                    ?.let {
                        when (action.type) {
                            ActionType.SET_TEXT -> "\n  입력할 내용: “$it”"
                            ActionType.SUBMIT_TEXT -> "\n  그대로 검색할 내용: “$it”"
                            ActionType.CLICK -> "\n  목표 상태: $it"
                            else -> ""
                        }
                    }
                    .orEmpty()
                val coordinate = if (action.type == ActionType.CLICK_COORDINATE) {
                    "\n  좌표: x=${action.xRatio ?: "?"}, y=${action.yRatio ?: "?"}"
                } else ""
                "• ${action.type.displayName()}$target\n  ${action.description}$value$coordinate"
            }
        val message = buildString {
            appendLine("최종 목표: ${plan.goal}")
            appendLine("실행 앱: ${plan.targetApp.ifBlank { "현재 앱" }}")
            appendLine("목표 화면/기능: ${plan.targetSurface.ifBlank { "현재 화면" }}")
            appendLine("사용 도구: ${plan.requiredTools.joinToString { it.name }}")
            appendLine()
            appendLine(plan.summary)
            appendLine()
            appendLine(steps)
            appendLine()
            appendLine(verification.summary)
            append(verification.reason)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.confirm_cancel) { _, _ ->
                progressCard.visibility = View.GONE
                clearOverlayContext()
                startVoiceInput(autoExecute = true)
            }
            .setPositiveButton(R.string.confirm_execute) { _, _ ->
                if (plan.actions.any { it.type.requiresExternalScreen() } &&
                    (!fromOverlay || externalContextSessionId != confirmationSessionId ||
                        !ContextLifetime.isFresh(
                            SystemClock.elapsedRealtime(),
                            externalContextCapturedAtElapsedRealtime,
                            OVERLAY_CONTEXT_TTL_MILLIS,
                        ))
                ) {
                    finishBusyWithMessage(getString(R.string.overlay_context_expired), success = false)
                    return@setPositiveButton
                }
                val confirmationGeneration = requestGeneration
                setBusy(true)
                showProgress(getString(R.string.progress_execute))
                mainScroll.postDelayed(
                    {
                        if (confirmationGeneration == requestGeneration &&
                            !isFinishing && !isDestroyed
                        ) {
                            when (val confirmed = architectureRuntime.verify(
                                command = command,
                                plan = plan,
                                snapshot = snapshot,
                                userConfirmed = true,
                            )) {
                                is VerificationResult.Allowed -> executePlan(
                                    command,
                                    confirmed.verifiedPlan,
                                    snapshot,
                                )
                                is VerificationResult.Blocked -> showBlocked(
                                    SafetyAssessment(
                                        SafetyDecision.BLOCK,
                                        RiskLevel.BLOCKED,
                                        confirmed.reason,
                                    ),
                                )
                                is VerificationResult.NeedsReplan -> showBlocked(
                                    SafetyAssessment(
                                        SafetyDecision.BLOCK,
                                        RiskLevel.BLOCKED,
                                        confirmed.reason,
                                    ),
                                )
                                is VerificationResult.NeedsConfirmation -> showBlocked(
                                    SafetyAssessment(
                                        SafetyDecision.BLOCK,
                                        RiskLevel.BLOCKED,
                                        "확인을 실행 권한으로 변환하지 못했습니다.",
                                    ),
                                )
                            }
                        }
                    },
                    CONFIRMATION_DISMISS_DELAY_MILLIS,
                )
            }
            .setOnCancelListener {
                progressCard.visibility = View.GONE
                clearOverlayContext()
            }
            .create()
        confirmationDialog = dialog
        dialog.setOnDismissListener {
            if (confirmationDialog === dialog) confirmationDialog = null
        }
        dialog.show()
    }

    private fun showBlocked(assessment: SafetyAssessment) {
        setBusy(false, keepProgress = false)
        showResult(assessment.reason, success = false)
        speak(assessment.reason)
        if (!isFinishing && !isDestroyed) {
            runCatching {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.blocked_title)
                    .setMessage(assessment.reason)
                    .setPositiveButton(R.string.blocked_close, null)
                    .show()
            }
        }
        clearOverlayContext()
    }

    private fun executePlan(
        command: String,
        verifiedPlan: VerifiedPlan,
        snapshot: UiSnapshot,
    ) {
        val plan = verifiedPlan.plan
        val service = SonjuAccessibilityService.instance
        if (service == null || !isServiceEnabledInSettings()) {
            finishBusyWithMessage(getString(R.string.accessibility_required), success = false)
            showToast(getString(R.string.accessibility_required))
            return
        }

        setBusy(true)
        showProgress(getString(R.string.progress_execute))
        val shouldReturn = fromOverlay && plan.actions.any { it.type.requiresExternalScreen() }
        val executionRequestGeneration = requestGeneration
        val executionSession = autonomySession?.takeIf { it.finalGoal == command }
        fromOverlay = false
        service.executePlan(
            verifiedPlan,
            expectedSnapshot = snapshot,
            returnToPreviousApp = shouldReturn,
        ) { result ->
            executionSession?.recordExecution(plan, snapshot, result)
            architectureRuntime.recordExecution(command, verifiedPlan, result, executionSession)
            val adaptivePlan = plan.continueAfterAction || plan.source in setOf(
                PlanSource.SKILL_FAST_PATH,
                PlanSource.OPENAI_STRUCTURE,
                PlanSource.OPENAI_SEMANTIC_MAP,
            )
            val continuing = result.success && adaptivePlan &&
                executionSession?.canContinue(SystemClock.elapsedRealtime()) == true
            if (continuing) {
                service.continueAutonomousCommand(command, executionSession)
                if (autonomySession === executionSession) autonomySession = null
            }
            runOnUiThread {
                if (executionRequestGeneration != requestGeneration || isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                if (continuing) {
                    finishBusyWithMessage(
                        "화면 변화를 확인하고 다음 도구를 계획하고 있어요.",
                        success = true,
                    )
                    return@runOnUiThread
                }
                val message = if (result.success) {
                    executionSession?.let { learnedRouteMemory.remember(it, plan) }
                    plan.summary.ifBlank { getString(R.string.action_completed) }
                } else {
                    result.message.ifBlank { getString(R.string.action_failed) }
                }
                autonomySession = null
                finishBusyWithMessage(message, result.success)
                speak(message)
                if (shouldReturn) showToast(message)
            }
        }
    }

    private fun showPrivacyDisclosure(firstRun: Boolean) {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.privacy_dialog_title)
            .setMessage(R.string.privacy_dialog_message)

        if (firstRun) {
            builder
                .setNegativeButton(R.string.privacy_not_now, null)
                .setPositiveButton(R.string.privacy_accept) { _, _ ->
                    getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_DISCLOSURE_ACCEPTED, true)
                        .apply()
                    showAccessibilityGuide()
                }
        } else {
            builder.setPositiveButton(R.string.privacy_close, null)
        }
        builder.show()
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { showToast(getString(R.string.generic_error)) }
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }
            .onFailure { showToast(getString(R.string.generic_error)) }
    }

    private fun showAccessibilityGuide() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.restricted_settings_guide_title)
                .setMessage(R.string.restricted_settings_guide_message)
                .setNegativeButton(R.string.privacy_not_now, null)
                .setNeutralButton(R.string.restricted_settings_open_accessibility) { _, _ ->
                    openAccessibilitySettings()
                }
                .setPositiveButton(R.string.restricted_settings_open_app_info) { _, _ ->
                    openAppDetailsSettings()
                }
                .show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.accessibility_guide_title)
            .setMessage(R.string.accessibility_guide_message)
            .setNegativeButton(R.string.privacy_not_now, null)
            .setPositiveButton(R.string.accessibility_guide_open) { _, _ ->
                openAccessibilitySettings()
            }
            .show()
    }

    private fun stopCurrentWork() {
        requestGeneration += 1
        openAiPlanner.cancelPending()
        SonjuAccessibilityService.instance?.stopCurrentExecution()
        clearOverlayContext()
        finishBusyWithMessage(getString(R.string.stopped_message), success = false)
        speak(getString(R.string.stopped_message))
    }

    private fun setBusy(value: Boolean, keepProgress: Boolean = value) {
        busy = value
        commandInput.isEnabled = !value
        voiceButton.isEnabled = !value
        runCommandButton.isEnabled = !value
        competingControls.forEach { it.isEnabled = !value }
        progressIndicator.isIndeterminate = value
        if (!keepProgress) progressCard.visibility = View.GONE
    }

    private fun showProgress(step: String) {
        progressCard.visibility = View.VISIBLE
        progressDetail.text = step
        mainScroll.post { mainScroll.smoothScrollTo(0, progressCard.top) }
    }

    private fun finishBusyWithMessage(message: String, success: Boolean) {
        setBusy(false, keepProgress = false)
        clearOverlayContext()
        showResult(message, success)
    }

    private fun showResult(message: String, success: Boolean) {
        resultCard.visibility = View.VISIBLE
        resultCard.setCardBackgroundColor(
            ContextCompat.getColor(
                this,
                if (success) R.color.sonju_green_soft else R.color.sonju_orange_soft,
            ),
        )
        resultText.text = message
        mainScroll.post { mainScroll.smoothScrollTo(0, resultCard.top) }
    }

    private fun clearOverlayContext() {
        confirmationDialog?.dismiss()
        confirmationDialog = null
        contextExpiryRunnable?.let(contextExpiryHandler::removeCallbacks)
        contextExpiryRunnable = null
        fromOverlay = false
        externalSnapshot = null
        externalSemanticMapJpegBase64 = null
        externalContextCapturedAtElapsedRealtime = 0L
        externalContextSessionId = 0L
    }

    private fun attachRecentApplicationContext(): Boolean {
        if (fromOverlay && externalSnapshot != null) return true
        val context = SonjuAccessibilityService.instance?.recentApplicationContext() ?: return false
        fromOverlay = true
        externalSnapshot = context.snapshot
        externalSemanticMapJpegBase64 = context.semanticMapJpegBase64
        externalContextCapturedAtElapsedRealtime = context.capturedAtElapsedRealtime
        externalContextSessionId = context.sessionId
        scheduleOverlayContextExpiry(context.sessionId, context.capturedAtElapsedRealtime)
        return true
    }

    private fun scheduleOverlayContextExpiry(sessionId: Long, capturedAtElapsedRealtime: Long) {
        contextExpiryRunnable?.let(contextExpiryHandler::removeCallbacks)
        val remaining = ContextLifetime.remainingMillis(
            SystemClock.elapsedRealtime(),
            capturedAtElapsedRealtime,
            OVERLAY_CONTEXT_TTL_MILLIS,
        )
        contextExpiryRunnable = Runnable {
            if (fromOverlay && externalContextSessionId == sessionId) {
                finishVoiceInput(animated = false)
                requestGeneration += 1
                openAiPlanner.cancelPending()
                val message = getString(R.string.overlay_context_expired)
                finishBusyWithMessage(message, success = false)
                speak(message)
            }
        }.also { contextExpiryHandler.postDelayed(it, remaining) }
    }

    private fun ActionType.requiresExternalScreen(): Boolean = this in setOf(
        ActionType.CLICK,
        ActionType.CLICK_COORDINATE,
        ActionType.SET_TEXT,
        ActionType.SUBMIT_TEXT,
        ActionType.SCROLL_DOWN,
        ActionType.SCROLL_UP,
        ActionType.SCROLL_LEFT,
        ActionType.SCROLL_RIGHT,
        ActionType.BACK,
    )

    private fun speak(message: String) {
        if (!ttsReady) return
        val accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (accessibilityManager.isTouchExplorationEnabled) return
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "sonju-result")
    }

    private fun showKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(commandInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(commandInput.windowToken, 0)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val PREFERENCES = "sonju_preferences"
        private const val KEY_DISCLOSURE_ACCEPTED = "accessibility_disclosure_accepted_v1"
        private const val KEY_VOICE_DISCLOSURE_ACCEPTED = "voice_disclosure_accepted_v1"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled_v1"
        private const val CONFIRMATION_DISMISS_DELAY_MILLIS = 200L
        private const val OVERLAY_CONTEXT_TTL_MILLIS = 120_000L
    }
}
