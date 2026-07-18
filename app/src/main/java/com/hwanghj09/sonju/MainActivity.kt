package com.hwanghj09.sonju

import android.Manifest
import android.app.Activity
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
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.NestedScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.hwanghj09.sonju.accessibility.SonjuAccessibilityService
import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.AgentPlan
import com.hwanghj09.sonju.agent.AppTaskMemory
import com.hwanghj09.sonju.agent.ContextLifetime
import com.hwanghj09.sonju.agent.RuleBasedPlanner
import com.hwanghj09.sonju.agent.RiskLevel
import com.hwanghj09.sonju.agent.SafetyAssessment
import com.hwanghj09.sonju.agent.SafetyDecision
import com.hwanghj09.sonju.agent.SafetyPolicy
import com.hwanghj09.sonju.agent.UiSnapshot
import com.hwanghj09.sonju.agent.displayName
import com.hwanghj09.sonju.ai.GeminiPlanner
import com.hwanghj09.sonju.shopping.BaeminOrderRequest
import com.hwanghj09.sonju.shopping.BaeminOrderRequestParser
import com.hwanghj09.sonju.voice.WakeWordService
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
    private lateinit var runCommandButton: MaterialButton
    private lateinit var progressCard: MaterialCardView
    private lateinit var progressDetail: TextView
    private lateinit var progressIndicator: LinearProgressIndicator
    private lateinit var resultCard: MaterialCardView
    private lateinit var resultText: TextView
    private var competingControls: List<View> = emptyList()

    private val geminiPlanner = GeminiPlanner()
    private lateinit var appTaskMemory: AppTaskMemory
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var fromOverlay = false
    private var externalSnapshot: UiSnapshot? = null
    private var externalVisionSemanticMap: String? = null
    private var externalRawScreenshot = false
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

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        resumeWakeWordListening()
        awaitingVoiceRecognition = false
        val shouldAutoExecute = autoExecuteVoiceResult
        autoExecuteVoiceResult = false
        val launchedOverlaySessionId = voiceOverlaySessionId
        voiceOverlaySessionId = 0L
        if (launchedOverlaySessionId != 0L &&
            (!fromOverlay || externalContextSessionId != launchedOverlaySessionId ||
                !ContextLifetime.isFresh(
                    SystemClock.elapsedRealtime(),
                    externalContextCapturedAtElapsedRealtime,
                    OVERLAY_CONTEXT_TTL_MILLIS,
                ))
        ) {
            voiceReviewText.visibility = View.GONE
            val message = getString(R.string.overlay_context_expired)
            showResult(message, success = false)
            speak(message)
            return@registerForActivityResult
        }
        if (result.resultCode == Activity.RESULT_OK) {
            val heard = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!heard.isNullOrBlank()) {
                commandInput.setText(heard)
                commandInput.setSelection(heard.length)
                if (shouldAutoExecute) {
                    voiceReviewText.visibility = View.GONE
                    commandInput.post { handleCommand() }
                    return@registerForActivityResult
                }
                val reviewMessage = getString(R.string.voice_review, heard)
                resultCard.visibility = View.GONE
                voiceReviewText.text = reviewMessage
                voiceReviewText.visibility = View.VISIBLE
                runCommandButton.post {
                    runCommandButton.requestFocus()
                }
                speak(reviewMessage)
            } else {
                voiceReviewText.visibility = View.GONE
                showToast(getString(R.string.voice_no_result))
            }
        } else {
            voiceReviewText.visibility = View.GONE
            showToast(getString(R.string.voice_no_result))
        }
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
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        bindViews()
        configureActions()
        appTaskMemory = AppTaskMemory(this)
        textToSpeech = TextToSpeech(this, this)
        receiveOverlayContext(intent)

        val preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(KEY_DISCLOSURE_ACCEPTED, false)) {
            showPrivacyDisclosure(firstRun = true)
        } else if (!preferences.contains(KEY_VISUAL_SCREEN_CONSENT)) {
            showVisualScreenDisclosure()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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
        if (fromOverlay && !awaitingVoiceRecognition) {
            requestGeneration += 1
            geminiPlanner.cancelPending()
            setBusy(false, keepProgress = false)
            clearOverlayContext()
        }
        super.onStop()
    }

    override fun onDestroy() {
        requestGeneration += 1
        awaitingVoiceRecognition = false
        clearOverlayContext()
        geminiPlanner.close()
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
            if (accepted) startVoiceInput() else showVoiceDisclosure(autoExecute = false)
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
            showPrivacyDisclosure(firstRun = false)
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
        if (!requestedFromOverlay) {
            clearOverlayContext()
            when {
                directVoiceCommand != null -> scheduleAutomaticCommand(directVoiceCommand)
                autoStartVoice -> scheduleAutomaticVoiceInput()
            }
            return
        }
        if (fromOverlay || busy || confirmationDialog != null) {
            requestGeneration += 1
            geminiPlanner.cancelPending()
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
        externalVisionSemanticMap = context.visionSemanticMap
        externalRawScreenshot = context.rawScreenshot
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
        commandInput.post {
            val safeCommand = command.take(500)
            commandInput.setText(safeCommand)
            commandInput.setSelection(safeCommand.length)
            voiceReviewText.visibility = View.GONE
            resumeWakeWordListening()
            handleCommand()
        }
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
        pauseWakeWordListening()
        autoExecuteVoiceResult = autoExecute
        voiceReviewText.visibility = View.GONE
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.command_title))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        awaitingVoiceRecognition = true
        voiceOverlaySessionId = if (fromOverlay) externalContextSessionId else 0L
        runCatching { voiceLauncher.launch(intent) }
            .onFailure {
                resumeWakeWordListening()
                autoExecuteVoiceResult = false
                awaitingVoiceRecognition = false
                voiceOverlaySessionId = 0L
                voiceReviewText.visibility = View.GONE
                showToast(getString(R.string.voice_unavailable))
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
        sendWakeWordAction(WakeWordService.ACTION_PAUSE)
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
        BaeminOrderRequestParser.parse(command)?.let { request ->
            hideKeyboard()
            voiceReviewText.visibility = View.GONE
            startBaeminAutomatically(request)
            return
        }
        SafetyPolicy.preflightCommand(command)?.let { assessment ->
            hideKeyboard()
            showBlocked(assessment)
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
        val preRenderedSemanticMap = externalVisionSemanticMap
        externalVisionSemanticMap = null
        val rawScreenshot = externalRawScreenshot
        externalRawScreenshot = false

        val localPlan = RuleBasedPlanner.plan(command, snapshot)
        if (localPlan != null) {
            showProgress(getString(R.string.progress_check))
            handlePlan(command, snapshot, localPlan)
            return
        }

        SafetyPolicy.highRiskScreenReason(
            snapshot,
            allowTruncated = true,
        )?.let { reason ->
            showBlocked(
                SafetyAssessment(
                    decision = SafetyDecision.BLOCK,
                    level = RiskLevel.BLOCKED,
                    reason = reason,
                ),
            )
            return
        }

        appTaskMemory.recall(command, snapshot)?.let { rememberedPlan ->
            showProgress("전에 성공한 화면 동작을 안전하게 다시 확인하고 있어요")
            handlePlan(command, snapshot, rememberedPlan)
            return
        }

        if (!geminiPlanner.isConfigured) {
            finishBusyWithMessage(getString(R.string.api_missing), success = false)
            return
        }

        showProgress(getString(R.string.progress_plan))
        if (preRenderedSemanticMap != null) {
            progressDetail.text = if (rawScreenshot) {
                "현재 화면 이미지에서 안전한 버튼 하나를 확인하고 있어요"
            } else {
                "민감 정보를 뺀 버튼 배치도를 한 번 더 확인하고 있어요"
            }
            requestGeminiPlan(
                command,
                snapshot,
                preRenderedSemanticMap,
                rawScreenshot,
                generation,
            )
        } else {
            requestGeminiPlan(
                command,
                snapshot,
                semanticMap = null,
                rawScreenshot = false,
                generation,
            )
        }
    }

    private fun requestGeminiPlan(
        command: String,
        snapshot: UiSnapshot,
        semanticMap: String?,
        rawScreenshot: Boolean,
        generation: Long,
    ) {
        geminiPlanner.planAsync(command, snapshot, semanticMap, rawScreenshot) { result ->
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

    private fun handlePlan(command: String, snapshot: UiSnapshot, plan: AgentPlan) {
        if (plan.goalCompleted && plan.actions.none { it.type != ActionType.FINISH }) {
            val message = "완료됐어요. ${plan.summary.ifBlank { "요청한 화면에 도착했어요." }}"
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
        val assessment = SafetyPolicy.evaluate(command, plan, snapshot)
        when (assessment.decision) {
            SafetyDecision.BLOCK -> showBlocked(assessment)
            SafetyDecision.REQUIRE_CONFIRMATION ->
                showConfirmation(command, plan, assessment, snapshot)
            SafetyDecision.ALLOW -> executePlan(command, plan, snapshot)
        }
    }

    private fun showConfirmation(
        command: String,
        plan: AgentPlan,
        assessment: SafetyAssessment,
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
                            ActionType.CLICK -> "\n  목표 상태: $it"
                            else -> ""
                        }
                    }
                    .orEmpty()
                "• ${action.type.displayName()}$target\n  ${action.description}$value"
            }
        val message = buildString {
            appendLine(plan.summary)
            appendLine()
            appendLine(steps)
            appendLine()
            append(assessment.reason)
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
                            executePlan(command, plan, snapshot)
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

    private fun executePlan(command: String, plan: AgentPlan, snapshot: UiSnapshot) {
        val service = SonjuAccessibilityService.instance
        if (service == null || !isServiceEnabledInSettings()) {
            finishBusyWithMessage(getString(R.string.accessibility_required), success = false)
            showToast(getString(R.string.accessibility_required))
            return
        }

        setBusy(true)
        showProgress(getString(R.string.progress_execute))
        val shouldReturn = fromOverlay
        val executionRequestGeneration = requestGeneration
        fromOverlay = false
        service.executePlan(
            plan,
            expectedSnapshot = snapshot,
            returnToPreviousApp = shouldReturn,
        ) { result ->
            runOnUiThread {
                if (executionRequestGeneration != requestGeneration || isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                val message = if (result.success) {
                    appTaskMemory.remember(command, snapshot, plan)
                    plan.summary.ifBlank { getString(R.string.action_completed) }
                } else {
                    result.message.ifBlank { getString(R.string.action_failed) }
                }
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
            builder
                .setNeutralButton(R.string.visual_screen_settings) { _, _ ->
                    showVisualScreenDisclosure()
                }
                .setPositiveButton(R.string.privacy_close, null)
        }
        builder.show()
    }

    private fun showVisualScreenDisclosure() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.visual_screen_disclosure_title)
            .setMessage(R.string.visual_screen_disclosure_message)
            .setNegativeButton(R.string.visual_screen_decline) { _, _ ->
                getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_VISUAL_SCREEN_CONSENT, false)
                    .apply()
            }
            .setPositiveButton(R.string.visual_screen_accept) { _, _ ->
                getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_VISUAL_SCREEN_CONSENT, true)
                    .apply()
                showToast(getString(R.string.visual_screen_enabled))
            }
            .show()
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { showToast(getString(R.string.generic_error)) }
    }

    private fun showBaeminStartConfirmation(request: BaeminOrderRequest) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.baemin_start_title)
            .setMessage(getString(R.string.baemin_start_message, request.query))
            .setNegativeButton(R.string.confirm_cancel) { _, _ ->
                startVoiceInput(autoExecute = true)
            }
            .setPositiveButton(R.string.baemin_start) { _, _ ->
                startBaeminAutomatically(request)
            }
            .show()
    }

    private fun startBaeminAutomatically(request: BaeminOrderRequest) {
        val service = SonjuAccessibilityService.instance
        if (service == null || !service.startBaeminOrder(request.query)) {
            showResult(getString(R.string.baemin_unavailable), success = false)
        } else {
            clearOverlayContext()
            showToast(getString(R.string.baemin_started, request.query))
        }
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
        geminiPlanner.cancelPending()
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
        externalVisionSemanticMap = null
        externalRawScreenshot = false
        externalContextCapturedAtElapsedRealtime = 0L
        externalContextSessionId = 0L
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
                requestGeneration += 1
                geminiPlanner.cancelPending()
                val message = getString(R.string.overlay_context_expired)
                finishBusyWithMessage(message, success = false)
                speak(message)
            }
        }.also { contextExpiryHandler.postDelayed(it, remaining) }
    }

    private fun ActionType.requiresExternalScreen(): Boolean = this in setOf(
        ActionType.CLICK,
        ActionType.VISUAL_CLICK,
        ActionType.SET_TEXT,
        ActionType.SCROLL_DOWN,
        ActionType.SCROLL_UP,
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
        private const val KEY_VISUAL_SCREEN_CONSENT = "visual_screen_consent_v1"
        private const val CONFIRMATION_DISMISS_DELAY_MILLIS = 200L
        private const val OVERLAY_CONTEXT_TTL_MILLIS = 120_000L
    }
}
