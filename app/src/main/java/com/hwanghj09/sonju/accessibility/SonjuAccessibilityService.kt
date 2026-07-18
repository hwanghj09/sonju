package com.hwanghj09.sonju.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.hwanghj09.sonju.MainActivity
import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.AgentAction
import com.hwanghj09.sonju.agent.AgentPlan
import com.hwanghj09.sonju.agent.ContextLifetime
import com.hwanghj09.sonju.agent.ExecutionResult
import com.hwanghj09.sonju.agent.SafetyPolicy
import com.hwanghj09.sonju.agent.TrustedSettingsRoute
import com.hwanghj09.sonju.agent.UiSnapshot
import com.hwanghj09.sonju.agent.ValidatedClick
import com.hwanghj09.sonju.agent.VisualTargetResolver
import com.hwanghj09.sonju.shopping.BaeminNavigator
import com.hwanghj09.sonju.shopping.BaeminScreenAction
import com.hwanghj09.sonju.voice.VoiceCommandActivity
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class SonjuAccessibilityService : AccessibilityService() {
    data class PendingOverlayContext(
        val snapshot: UiSnapshot,
        val visionSemanticMap: String?,
        val rawScreenshot: Boolean = false,
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
        var reviewLaunched: Boolean = false,
        var itemAdded: Boolean = false,
        var consecutiveFailures: Int = 0,
    )

    private data class PendingBaeminCommit(
        val generation: Long,
        val expectedFingerprint: String,
        val clickablePath: String,
        val actionLabel: String,
    )

    private data class VisualCapture(
        val jpegBase64: String,
        val fingerprint: String,
        val screenWidth: Int,
        val screenHeight: Int,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapshotExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SonjuSnapshot").apply { isDaemon = true }
    }
    private val epoch = AtomicLong(SystemClock.elapsedRealtime())
    private val overlaySession = AtomicLong(0L)
    private var executionGeneration = 0L
    private var executionActive = false
    private var activeExecution: ActiveExecution? = null
    private var overlayCaptureInProgress = false
    private var overlayAutoStartVoice = false
    private var overlayVoiceCommand: String? = null
    private var lastObservedApplicationSnapshot: ObservedApplicationSnapshot? = null
    private var lastObservedSnapshotCaptureAtElapsedRealtime = 0L
    private var lastObservedWindowPackage: String? = null
    private var lastObservedWindowClass: String? = null
    private var observedWindowGeneration = 0L
    private var pendingSnapshotCapture: SnapshotCaptureRequest? = null
    private var snapshotCaptureInFlight = false
    private var trustedSettingsContext: TrustedSettingsContext? = null
    private var baeminOrderGeneration = 0L
    private var baeminOrderSession: BaeminOrderSession? = null
    private var pendingBaeminCommit: PendingBaeminCommit? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val consented = getSharedPreferences(CONSENT_PREFERENCES, MODE_PRIVATE)
            .getBoolean(CONSENT_KEY, false)
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
        epoch.incrementAndGet()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventEpoch = epoch.incrementAndGet()
        val currentEvent = event ?: return
        val eventPackage = currentEvent.packageName?.toString().orEmpty()
        val eventClass = currentEvent.className?.toString().orEmpty()
        val applicationWindowChanged = eventPackage.isNotBlank() && eventPackage != packageName &&
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

        if (executionActive) return

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
        runCatching {
            snapshotExecutor.execute {
                val snapshot = runCatching {
                    UiTreeReader.snapshot(request.root, request.snapshotEpoch)
                }.getOrNull()
                mainHandler.post {
                    if (instance !== this) return@post
                    snapshotCaptureInFlight = false
                    if (snapshot != null &&
                        request.windowGeneration == observedWindowGeneration &&
                        snapshot.packageName == request.expectedPackage &&
                        snapshot.packageName != "unknown"
                    ) {
                        acceptObservedSnapshot(snapshot)
                    }
                    drainSnapshotCaptureQueue()
                }
            }
        }.onFailure {
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
        advanceBaeminOrder(accepted)
    }

    override fun onInterrupt() {
        stopCurrentExecution()
    }

    override fun onDestroy() {
        stopCurrentExecution()
        mainHandler.removeCallbacksAndMessages(null)
        pendingSnapshotCapture = null
        snapshotExecutor.shutdownNow()
        clearPendingOverlayContext()
        cancelBaeminOrder()
        clearTrustedSettingsContext()
        lastObservedApplicationSnapshot = null
        lastObservedSnapshotCaptureAtElapsedRealtime = 0L
        lastObservedWindowPackage = null
        lastObservedWindowClass = null
        snapshotCaptureInFlight = false
        overlayCaptureInProgress = false
        overlayAutoStartVoice = false
        overlayVoiceCommand = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun bestAvailableApplicationRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { direct ->
            if (!direct.packageName.isNullOrBlank()) return direct
        }
        return windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { it.isActive }
                    .thenByDescending { it.isFocused }
                    .thenByDescending { it.layer },
            )
            .mapNotNull(AccessibilityWindowInfo::getRoot)
            .firstOrNull { !it.packageName.isNullOrBlank() }
    }

    private fun rootFromEventSource(event: AccessibilityEvent): AccessibilityNodeInfo? {
        var node = event.source ?: return null
        repeat(MAX_EVENT_ANCESTOR_DEPTH) {
            val parent = node.parent ?: return node
            node = parent
        }
        return null
    }

    private fun snapshotWithTrustedRoute(snapshot: UiSnapshot): UiSnapshot {
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
        val established = !context.establishedActivityClass.isNullOrBlank() &&
            !context.establishedWindowTitle.isNullOrBlank()
        return if (fresh && established && snapshot.packageName == SETTINGS_PACKAGE &&
            snapshot.windowTitle == context.establishedWindowTitle &&
            isExpectedSettingsTitle(context.route, snapshot.windowTitle.orEmpty())
        ) {
            untrustedSnapshot.copy(trustedSettingsRoute = context.route)
        } else {
            if (!fresh) clearTrustedSettingsContext()
            untrustedSnapshot
        }
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

        val display = resources.displayMetrics
        val width = 480
        val height = (width * (display.heightPixels.toFloat() / display.widthPixels.coerceAtLeast(1)))
            .toInt()
            .coerceIn(480, 960)
        val scaleX = width.toFloat() / display.widthPixels.coerceAtLeast(1)
        val scaleY = height.toFloat() / display.heightPixels.coerceAtLeast(1)
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
        plan: AgentPlan,
        expectedSnapshot: UiSnapshot,
        returnToPreviousApp: Boolean = false,
        callback: (ExecutionResult) -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Accessibility execution must start on the main thread"
        }
        if (activeExecution != null) stopCurrentExecution()
        val generation = ++executionGeneration
        executionActive = true
        val startedAt = SystemClock.elapsedRealtime()
        var terminalDelivered = false
        val terminalCallback: (ExecutionResult) -> Unit = terminal@{ result ->
            if (terminalDelivered) return@terminal
            terminalDelivered = true
            if (activeExecution?.generation == generation) activeExecution = null
            if (generation == executionGeneration) {
                executionActive = false
                invalidateObservedSnapshot()
                scheduleObservedSnapshotRefresh()
            }
            callback(result)
        }
        activeExecution = ActiveExecution(generation, terminalCallback)
        if (returnToPreviousApp) {
            if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
                terminalCallback(
                    ExecutionResult(false, "이전 앱으로 돌아가지 못해 안전하게 멈췄습니다.", 0),
                )
                return
            }
            mainHandler.postDelayed(
                {
                    executeStep(
                        plan,
                        expectedSnapshot,
                        verifyExpectedScreen = true,
                        index = 0,
                        completedSteps = 0,
                        startedAt = startedAt,
                        generation = generation,
                        callback = terminalCallback,
                    )
                },
                RETURN_TO_APP_MILLIS,
            )
        } else {
            executeStep(
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

    fun stopCurrentExecution() {
        val pendingExecution = activeExecution
        activeExecution = null
        executionGeneration += 1
        executionActive = false
        overlayCaptureInProgress = false
        overlayAutoStartVoice = false
        overlayVoiceCommand = null
        if (baeminOrderSession != null) cancelBaeminOrder()
        clearPendingOverlayContext()
        invalidateObservedSnapshot()
        scheduleObservedSnapshotRefresh()
        pendingExecution?.complete(
            ExecutionResult(false, "사용자 요청으로 안전하게 멈췄습니다.", 0),
        )
    }

    fun startBaeminOrder(query: String): Boolean {
        val safeQuery = query.trim().take(40)
        if (safeQuery.isBlank()) return false
        val intent = packageManager.getLaunchIntentForPackage(BaeminNavigator.PACKAGE_NAME)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        baeminOrderGeneration += 1
        baeminOrderSession = BaeminOrderSession(
            query = safeQuery,
            generation = baeminOrderGeneration,
            startedAtElapsedRealtime = SystemClock.elapsedRealtime(),
        )
        pendingBaeminCommit = null
        invalidateObservedSnapshot()
        return runCatching {
            startActivity(intent)
            mainHandler.postDelayed(
                { captureAndAdvanceBaeminOrder(baeminOrderGeneration) },
                BAEMIN_INITIAL_DELAY_MILLIS,
            )
            true
        }.getOrElse {
            cancelBaeminOrder()
            false
        }
    }

    fun cancelBaeminOrder() {
        baeminOrderGeneration += 1
        baeminOrderSession = null
        pendingBaeminCommit = null
    }

    fun confirmBaeminCommit() {
        val pending = pendingBaeminCommit ?: return
        val session = baeminOrderSession?.takeIf { it.generation == pending.generation } ?: return
        pendingBaeminCommit = null
        session.reviewLaunched = false
        session.actionInFlight = true
        performGlobalAction(GLOBAL_ACTION_BACK)
        mainHandler.postDelayed(
            {
                performBaeminCommit(session, pending)
            },
            BAEMIN_RETURN_DELAY_MILLIS,
        )
    }

    private fun advanceBaeminOrder(snapshot: UiSnapshot) {
        val session = baeminOrderSession ?: return
        if (session.actionInFlight || session.reviewLaunched ||
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
        )) {
            is BaeminScreenAction.Click -> {
                if (action.finalCommit) {
                    pendingBaeminCommit = PendingBaeminCommit(
                        generation = session.generation,
                        expectedFingerprint = snapshot.screenFingerprint(),
                        clickablePath = action.path,
                        actionLabel = action.label,
                    )
                    session.reviewLaunched = true
                    launchBaeminReview(
                        BaeminNavigator.reviewSummary(snapshot, action.label),
                    )
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
            return
        }
        runCatching {
            snapshotExecutor.execute {
                val liveSnapshot = runCatching { UiTreeReader.snapshot(root, epoch.get()) }.getOrNull()
                mainHandler.post {
                    val current = baeminOrderSession?.takeIf { it.generation == generation }
                        ?: return@post
                    val node = if (liveSnapshot?.packageName == BaeminNavigator.PACKAGE_NAME &&
                        liveSnapshot.screenFingerprint() == expectedSnapshot.screenFingerprint()
                    ) nodeAtPath(root, path) else null
                    val dispatched = when (action) {
                        BaeminNodeAction.CLICK -> node?.takeIf {
                            it.isVisibleToUser && it.isEnabled && it.isClickable
                        }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

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
                    current.actionInFlight = false
                    if (dispatched) {
                        current.consecutiveFailures = 0
                        current.completedSteps += 1
                        if (markItemAdded) current.itemAdded = true
                        invalidateObservedSnapshot()
                    } else {
                        current.consecutiveFailures += 1
                        if (current.consecutiveFailures >= BAEMIN_MAX_CONSECUTIVE_FAILURES) {
                            finishBaeminOrder(
                                "배민 화면이 계속 바뀌어 다른 버튼을 누르지 않고 멈췄어요.",
                                false,
                            )
                            return@post
                        }
                    }
                    mainHandler.postDelayed(
                        { captureAndAdvanceBaeminOrder(generation) },
                        BAEMIN_STEP_SETTLE_MILLIS,
                    )
                }
            }
        }.onFailure {
            session.actionInFlight = false
            finishBaeminOrder("배민 화면을 다시 확인할 수 없어 주문 보조를 멈췄어요.", false)
        }
    }

    private fun captureAndAdvanceBaeminOrder(generation: Long) {
        val session = baeminOrderSession?.takeIf {
            it.generation == generation && !it.actionInFlight && !it.reviewLaunched
        } ?: return
        val root = bestAvailableApplicationRoot() ?: return
        runCatching {
            snapshotExecutor.execute {
                val snapshot = runCatching { UiTreeReader.snapshot(root, epoch.get()) }.getOrNull()
                mainHandler.post {
                    if (baeminOrderSession?.generation != generation || snapshot == null) return@post
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
        runCatching {
            snapshotExecutor.execute {
                val snapshot = runCatching { UiTreeReader.snapshot(root, epoch.get()) }.getOrNull()
                mainHandler.post {
                    if (baeminOrderSession?.generation != session.generation) return@post
                    val node = if (snapshot?.packageName == BaeminNavigator.PACKAGE_NAME &&
                        snapshot.screenFingerprint() == pending.expectedFingerprint
                    ) nodeAtPath(root, pending.clickablePath) else null
                    val dispatched = node?.takeIf {
                        it.isVisibleToUser && it.isEnabled && it.isClickable && !it.isPassword
                    }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    session.actionInFlight = false
                    if (!dispatched) {
                        finishBaeminOrder("확인하는 동안 주문 화면이 바뀌어 마지막 버튼을 누르지 않았어요.", false)
                        return@post
                    }
                    session.completedSteps += 1
                    invalidateObservedSnapshot()
                    mainHandler.postDelayed(
                        { captureAndAdvanceBaeminOrder(session.generation) },
                        BAEMIN_STEP_SETTLE_MILLIS,
                    )
                }
            }
        }.onFailure {
            session.actionInFlight = false
            finishBaeminOrder("마지막 주문 버튼을 안전하게 다시 확인하지 못했어요.", false)
        }
    }

    private fun launchBaeminReview(summary: String) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_BAEMIN_REVIEW, summary.take(2_000))
        startActivity(intent)
    }

    private fun finishBaeminOrder(message: String, success: Boolean) {
        cancelBaeminOrder()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_BAEMIN_STATUS, message.take(500))
            .putExtra(EXTRA_BAEMIN_STATUS_SUCCESS, success)
        startActivity(intent)
    }

    fun requestVoiceWake(command: String? = null) {
        mainHandler.post {
            if (instance !== this || overlayCaptureInProgress) return@post
            val safeCommand = command?.trim()?.takeIf(String::isNotBlank)?.take(500)
            val activePackage = bestAvailableApplicationRoot()?.packageName?.toString().orEmpty()
            if (activePackage.isBlank() || activePackage == packageName) {
                launchCommandActivity(
                    autoStartVoice = safeCommand == null,
                    fromOverlay = false,
                    voiceCommand = safeCommand,
                )
                return@post
            }
            overlayAutoStartVoice = safeCommand == null
            overlayVoiceCommand = safeCommand
            overlayCaptureInProgress = true
            captureOverlayContextAndLaunch(attempt = 0)
        }
    }

    private fun captureOverlayContextAndLaunch(attempt: Int) {
        if (!overlayCaptureInProgress) return
        val root = bestAvailableApplicationRoot()
        val activePackage = root?.packageName?.toString().orEmpty()
        val cachedSnapshot = lastObservedApplicationSnapshot?.takeIf { observed ->
            ContextLifetime.isFresh(
                SystemClock.elapsedRealtime(),
                observed.capturedAtElapsedRealtime,
                OBSERVED_SNAPSHOT_TTL_MILLIS,
            ) && observed.snapshot.packageName == activePackage
        }?.snapshot?.let(::snapshotWithTrustedRoute)
        if (cachedSnapshot != null) {
            completeOverlayCapture(cachedSnapshot, attempt)
            return
        }

        if (root == null || activePackage.isBlank() || activePackage == packageName) {
            retryOverlayCapture(attempt)
            return
        }
        val captureEpoch = epoch.get()
        runCatching {
            snapshotExecutor.execute {
                val rawSnapshot = runCatching {
                    UiTreeReader.snapshot(root, captureEpoch)
                }.getOrNull()
                mainHandler.post {
                    if (instance !== this || !overlayCaptureInProgress) return@post
                    val snapshot = rawSnapshot
                        ?.takeIf { it.packageName == activePackage }
                        ?.let(::snapshotWithTrustedRoute)
                    if (snapshot == null) {
                        retryOverlayCapture(attempt)
                    } else {
                        cacheObservedApplicationSnapshot(snapshot)
                        completeOverlayCapture(snapshot, attempt)
                    }
                }
            }
        }.onFailure {
            retryOverlayCapture(attempt)
        }
    }

    private fun retryOverlayCapture(attempt: Int) {
        if (attempt < OVERLAY_CAPTURE_MAX_RETRIES) {
            mainHandler.postDelayed(
                { captureOverlayContextAndLaunch(attempt + 1) },
                OVERLAY_CAPTURE_RETRY_MILLIS,
            )
        } else {
            overlayCaptureInProgress = false
            overlayAutoStartVoice = false
            overlayVoiceCommand = null
            Toast.makeText(
                this,
                "화면 정보를 읽지 못했어요. 잠시 후 ‘손주야’라고 다시 불러 주세요.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun completeOverlayCapture(snapshot: UiSnapshot, attempt: Int) {
        if (snapshot.packageName == "unknown") {
            retryOverlayCapture(attempt)
            return
        }

        val containsSensitiveNode = snapshot.elements.any { it.sensitive }
        val visualConsent = getSharedPreferences(CONSENT_PREFERENCES, MODE_PRIVATE)
            .getBoolean(VISUAL_CONSENT_KEY, false)
        val rawVisualAllowed = visualConsent && !containsSensitiveNode &&
            SafetyPolicy.highRiskScreenReason(snapshot, allowTruncated = true) == null
        if (rawVisualAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureVisualScreen { capture ->
                if (instance !== this || !overlayCaptureInProgress) return@captureVisualScreen
                if (capture != null) {
                    publishOverlayContext(
                        snapshot.copy(
                            visualFingerprint = capture.fingerprint,
                            visualScreenWidth = capture.screenWidth,
                            visualScreenHeight = capture.screenHeight,
                        ),
                        imageBase64 = capture.jpegBase64,
                        rawScreenshot = true,
                    )
                } else {
                    publishSemanticOverlayContext(snapshot)
                }
            }
            return
        }
        publishSemanticOverlayContext(snapshot)
    }

    private fun publishSemanticOverlayContext(snapshot: UiSnapshot) {
        val containsSensitiveNode = snapshot.elements.any { it.sensitive }
        val observableSignals = snapshot.elements.count { element ->
            element.visible && !element.sensitive &&
                (element.clickable || element.editable || element.scrollable ||
                    !element.text.isNullOrBlank() ||
                    !element.contentDescription.isNullOrBlank())
        }
        val visionFallbackAllowed = observableSignals in 1..3 &&
            !containsSensitiveNode &&
            SafetyPolicy.highRiskScreenReason(snapshot) == null
        val semanticMap = if (!snapshot.hasSemanticSignal() && visionFallbackAllowed) {
            renderSanitizedSemanticMap(snapshot)
        } else {
            null
        }
        publishOverlayContext(snapshot, semanticMap, rawScreenshot = false)
    }

    private fun publishOverlayContext(
        snapshot: UiSnapshot,
        imageBase64: String?,
        rawScreenshot: Boolean,
    ) {
        val sessionId = overlaySession.incrementAndGet()
        val context = PendingOverlayContext(
            snapshot = snapshot,
            visionSemanticMap = imageBase64,
            rawScreenshot = rawScreenshot,
            capturedAtElapsedRealtime = SystemClock.elapsedRealtime(),
            sessionId = sessionId,
        )
        setPendingOverlayContext(context)
        mainHandler.postDelayed(
            { clearPendingOverlayContext(sessionId) },
            OVERLAY_CONTEXT_TTL_MILLIS,
        )
        val autoStartVoice = overlayAutoStartVoice
        val voiceCommand = overlayVoiceCommand
        overlayAutoStartVoice = false
        overlayVoiceCommand = null
        overlayCaptureInProgress = false
        launchCommandActivity(
            autoStartVoice = autoStartVoice,
            fromOverlay = true,
            voiceCommand = voiceCommand,
        )
    }

    private fun captureVisualScreen(callback: (VisualCapture?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val source = runCatching {
                        Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace,
                        )?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    screenshot.hardwareBuffer.close()
                    if (source == null) {
                        callback(null)
                        return
                    }
                    runCatching {
                        snapshotExecutor.execute {
                            val capture = runCatching { encodeVisualCapture(source) }.getOrNull()
                            source.recycle()
                            mainHandler.post { callback(capture) }
                        }
                    }.onFailure {
                        source.recycle()
                        callback(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    callback(null)
                }
            },
        )
    }

    private fun encodeVisualCapture(source: Bitmap): VisualCapture {
        val masked = source.copy(Bitmap.Config.ARGB_8888, true)
        try {
            val canvas = Canvas(masked)
            val mask = Paint().apply { color = Color.BLACK }
            canvas.drawRect(0f, 0f, masked.width.toFloat(), masked.height * 0.065f, mask)
            canvas.drawRect(
                0f,
                masked.height * 0.94f,
                masked.width.toFloat(),
                masked.height.toFloat(),
                mask,
            )
            val fingerprint = averageHash(masked)
            val width = 720.coerceAtMost(masked.width)
            val height = (masked.height * (width.toFloat() / masked.width)).toInt().coerceAtLeast(1)
            val resized = Bitmap.createScaledBitmap(masked, width, height, true)
            try {
                val bytes = ByteArrayOutputStream().use { output ->
                    resized.compress(Bitmap.CompressFormat.JPEG, 68, output)
                    output.toByteArray()
                }
                return VisualCapture(
                    jpegBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    fingerprint = fingerprint,
                    screenWidth = source.width,
                    screenHeight = source.height,
                )
            } finally {
                if (resized !== masked) resized.recycle()
            }
        } finally {
            masked.recycle()
        }
    }

    private fun averageHash(bitmap: Bitmap): String {
        val sample = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        try {
            val values = IntArray(64)
            sample.getPixels(values, 0, 8, 0, 0, 8, 8)
            val luminance = values.map { color ->
                (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
            }
            val average = luminance.average()
            return luminance.chunked(4).joinToString("") { group ->
                group.fold(0) { bits, value -> (bits shl 1) or if (value >= average) 1 else 0 }
                    .toString(16)
            }
        } finally {
            sample.recycle()
        }
    }

    private fun launchCommandActivity(
        autoStartVoice: Boolean,
        fromOverlay: Boolean,
        voiceCommand: String? = null,
    ) {
        if (autoStartVoice && voiceCommand == null) {
            startActivity(
                Intent(this, VoiceCommandActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_FROM_OVERLAY, fromOverlay)
            .putExtra(EXTRA_AUTO_START_VOICE, autoStartVoice)
            .putExtra(EXTRA_VOICE_COMMAND, voiceCommand)
        startActivity(intent)
    }


    private fun ActionType.requiresStableScreen(): Boolean = this in setOf(
        ActionType.CLICK,
        ActionType.VISUAL_CLICK,
        ActionType.SET_TEXT,
        ActionType.SCROLL_DOWN,
        ActionType.SCROLL_UP,
        ActionType.BACK,
    )

    private fun ActionType.requiresRiskFreeScreen(): Boolean = this in setOf(
        ActionType.CLICK,
        ActionType.VISUAL_CLICK,
        ActionType.SET_TEXT,
        ActionType.SCROLL_DOWN,
        ActionType.SCROLL_UP,
    )

    private fun executeStep(
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

        if (action.type == ActionType.WAIT) {
            val wait = action.waitMillis.coerceIn(100, 2_000)
            mainHandler.postDelayed(
                {
                    executeStep(
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
            ActionType.VISUAL_CLICK,
            ActionType.SCROLL_DOWN,
            ActionType.SCROLL_UP,
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
                    val dispatched = dispatchSynchronousAction(action)
                    continueAfterDispatch(
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
            ActionType.VISUAL_CLICK -> {
                visualClickAsync(
                    action,
                    expectedSnapshot,
                    generation,
                    startedAt,
                ) { dispatched ->
                    continueAfterDispatch(
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

            ActionType.CLICK -> {
                clickValidatedNodeAsync(
                    action,
                    expectedSnapshot,
                    generation,
                    startedAt,
                ) { dispatched ->
                    continueAfterDispatch(
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

            ActionType.SCROLL_DOWN, ActionType.SCROLL_UP -> {
                scrollAsync(
                    forward = action.type == ActionType.SCROLL_DOWN,
                    expectedSnapshot = expectedSnapshot,
                    generation = generation,
                    startedAt = startedAt,
                ) { dispatched ->
                    continueAfterDispatch(
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

        val dispatched = dispatchSynchronousAction(action)
        continueAfterDispatch(
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
        ActionType.OPEN_APP,
        ActionType.OPEN_WIFI_SETTINGS,
        ActionType.OPEN_SOUND_SETTINGS,
        ActionType.OPEN_ACCESSIBILITY_SETTINGS,
        ActionType.OPEN_DISPLAY_SETTINGS,
        ActionType.OPEN_DATE_SETTINGS,
        ActionType.OPEN_CAMERA,
        ActionType.OPEN_DIALER,
        ActionType.OPEN_MESSAGES,
        -> launch(action)

        ActionType.SET_TEXT -> false
        ActionType.BACK -> performGlobalActionClearingRoute(GLOBAL_ACTION_BACK)
        ActionType.HOME -> performGlobalActionClearingRoute(GLOBAL_ACTION_HOME)
        ActionType.NOTIFICATIONS -> performGlobalActionClearingRoute(GLOBAL_ACTION_NOTIFICATIONS)
        ActionType.QUICK_SETTINGS -> performGlobalActionClearingRoute(GLOBAL_ACTION_QUICK_SETTINGS)
        ActionType.CLICK,
        ActionType.VISUAL_CLICK,
        ActionType.SCROLL_DOWN,
        ActionType.SCROLL_UP,
        -> false

        ActionType.WAIT, ActionType.FINISH -> true
    }

    private fun visualClickAsync(
        action: AgentAction,
        expectedSnapshot: UiSnapshot,
        generation: Long,
        startedAt: Long,
        callback: (Boolean) -> Unit,
    ) {
        val expectedPath = VisualTargetResolver.resolveClickablePath(
            expectedSnapshot,
            action.x,
            action.y,
        ) ?: run {
            callback(false)
            return
        }
        if (!executionWithinDeadline(startedAt)) {
            callback(false)
            return
        }
        captureVisualScreen visualCapture@{ capture ->
            if (generation != executionGeneration || capture == null ||
                VisualTargetResolver.fingerprintDistance(
                    expectedSnapshot.visualFingerprint,
                    capture.fingerprint,
                )?.let { it <= MAX_VISUAL_FINGERPRINT_DISTANCE } != true
            ) {
                callback(false)
                return@visualCapture
            }
            captureLiveSnapshotAsync(generation) liveCapture@{ liveSnapshot, root, captureEpoch ->
                if (liveSnapshot == null || root == null || epoch.get() != captureEpoch ||
                    liveSnapshot.packageName != expectedSnapshot.packageName ||
                    SafetyPolicy.highRiskScreenReason(
                        liveSnapshot,
                        allowTruncated = true,
                    ) != null
                ) {
                    callback(false)
                    return@liveCapture
                }
                val liveWithScreen = liveSnapshot.copy(
                    visualScreenWidth = capture.screenWidth,
                    visualScreenHeight = capture.screenHeight,
                )
                val livePath = VisualTargetResolver.resolveClickablePath(
                    liveWithScreen,
                    action.x,
                    action.y,
                )
                if (livePath != expectedPath) {
                    callback(false)
                    return@liveCapture
                }
                val node = nodeAtPath(root, expectedPath)
                callback(
                    node != null && node.isVisibleToUser && node.isEnabled && node.isClickable &&
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK),
                )
            }
        }
    }

    private fun continueAfterDispatch(
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
            callback(
                ExecutionResult(
                    success = false,
                    message = "‘${action.description}’ 단계에서 화면 요소를 확실히 찾지 못해 멈췄습니다.",
                    completedSteps = completedSteps,
                ),
            )
            return
        }

        mainHandler.postDelayed(
            {
                executeStep(
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
            STEP_SETTLE_MILLIS,
        )
    }

    private fun launch(action: AgentAction): Boolean {
        if (action.type == ActionType.OPEN_APP) {
            clearTrustedSettingsContext()
            return launchInstalledApp(action.target.orEmpty())
        }
        val settingsRoute = when (action.type) {
            ActionType.OPEN_WIFI_SETTINGS -> TrustedSettingsRoute.WIFI
            ActionType.OPEN_SOUND_SETTINGS -> TrustedSettingsRoute.SOUND
            ActionType.OPEN_ACCESSIBILITY_SETTINGS -> TrustedSettingsRoute.ACCESSIBILITY
            ActionType.OPEN_DISPLAY_SETTINGS -> TrustedSettingsRoute.DISPLAY
            ActionType.OPEN_DATE_SETTINGS -> TrustedSettingsRoute.DATE_TIME
            else -> null
        }
        val intent = when (action.type) {
            ActionType.OPEN_WIFI_SETTINGS -> Intent(Settings.ACTION_WIFI_SETTINGS)
            ActionType.OPEN_SOUND_SETTINGS -> Intent(Settings.ACTION_SOUND_SETTINGS)
            ActionType.OPEN_ACCESSIBILITY_SETTINGS -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            ActionType.OPEN_DISPLAY_SETTINGS -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            ActionType.OPEN_DATE_SETTINGS -> Intent(Settings.ACTION_DATE_SETTINGS)
            ActionType.OPEN_CAMERA -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            ActionType.OPEN_DIALER -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
            ActionType.OPEN_MESSAGES -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
            else -> return false
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

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

    private fun launchInstalledApp(target: String): Boolean {
        val normalized = target.trim().lowercase()
        if (normalized.isBlank()) return false
        val acceptedLabels = APP_LABEL_ALIASES[normalized].orEmpty() + normalized
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }
        val candidates = activities.filter { info ->
            val label = info.loadLabel(packageManager)?.toString()?.trim()?.lowercase().orEmpty()
            val packageName = info.activityInfo?.packageName?.lowercase().orEmpty()
            label in acceptedLabels || packageName == normalized
        }
        val match = candidates.distinctBy { it.activityInfo?.packageName }.singleOrNull() ?: return false
        val packageName = match.activityInfo?.packageName ?: return false
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)
    }

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

                action.type.requiresRiskFreeScreen() ->
                    SafetyPolicy.highRiskScreenReason(liveSnapshot)

                else -> null
            }
            callback(failureReason)
        }
    }

    private fun captureLiveSnapshotAsync(
        generation: Long,
        callback: (UiSnapshot?, AccessibilityNodeInfo?, Long) -> Unit,
    ) {
        val root = bestAvailableApplicationRoot()
        if (root == null) {
            callback(null, null, epoch.get())
            return
        }
        val captureEpoch = epoch.get()
        runCatching {
            snapshotExecutor.execute {
                val rawSnapshot = runCatching {
                    UiTreeReader.snapshot(root, captureEpoch)
                }.getOrNull()
                mainHandler.post {
                    if (instance !== this || generation != executionGeneration) return@post
                    callback(rawSnapshot?.let(::snapshotWithTrustedRoute), root, captureEpoch)
                }
            }
        }.onFailure {
            callback(null, root, captureEpoch)
        }
    }

    private fun clickValidatedNodeAsync(
        action: AgentAction,
        expectedSnapshot: UiSnapshot,
        generation: Long,
        startedAt: Long,
        callback: (Boolean) -> Unit,
    ) {
        captureLiveSnapshotAsync(generation) { liveSnapshot, root, dispatchEpoch ->
            if (liveSnapshot == null || root == null ||
                !sameVerifiedScreen(expectedSnapshot, liveSnapshot)
            ) {
                callback(false)
                return@captureLiveSnapshotAsync
            }
            val validated = SafetyPolicy.validateClick(action, liveSnapshot)
            val clickable = validated?.let { nodeAtPath(root, it.clickablePath) }
            val stateNode = validated?.let { nodeAtPath(root, it.statePath) }
            if (validated == null || clickable == null || stateNode == null ||
                !matchesValidatedState(stateNode, validated) ||
                !clickable.isClickable || !clickable.isEnabled || !clickable.isVisibleToUser ||
                epoch.get() != dispatchEpoch || !executionWithinDeadline(startedAt)
            ) {
                callback(false)
                return@captureLiveSnapshotAsync
            }
            clearTrustedSettingsContext()
            val dispatched = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (dispatched) invalidateObservedSnapshot()
            callback(dispatched)
        }
    }

    private fun matchesValidatedState(
        stateNode: AccessibilityNodeInfo,
        validated: ValidatedClick,
    ): Boolean {
        val stateViewId = stateNode.viewIdResourceName?.lowercase().orEmpty()
        if (stateViewId != validated.stateViewId.lowercase() ||
            stateViewId !in TRUSTED_SETTINGS_STATE_RESOURCE_IDS
        ) return false
        val currentState = if (stateNode.isCheckable) {
            stateNode.isChecked
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            booleanStateFromDescription(stateNode.stateDescription?.toString())
                ?: true.takeIf { stateNode.isSelected }
                ?: return false
        } else if (stateNode.isSelected) {
            true
        } else {
            return false
        }
        return currentState == validated.currentState && currentState != validated.desiredState
    }

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
            node = node.getChild(childIndex) ?: return null
        }
        return node
    }

    private fun scrollAsync(
        forward: Boolean,
        expectedSnapshot: UiSnapshot,
        generation: Long,
        startedAt: Long,
        callback: (Boolean) -> Unit,
    ) {
        captureLiveSnapshotAsync(generation) { liveSnapshot, root, dispatchEpoch ->
            if (liveSnapshot == null || root == null ||
                !sameVerifiedScreen(expectedSnapshot, liveSnapshot)
            ) {
                callback(false)
                return@captureLiveSnapshotAsync
            }
            val scrollablePath = uniqueLeafScrollablePath(liveSnapshot)
            val scrollable = scrollablePath?.let { nodeAtPath(root, it) }
            if (scrollable == null || !scrollable.isScrollable ||
                !scrollable.isEnabled || !scrollable.isVisibleToUser ||
                epoch.get() != dispatchEpoch || !executionWithinDeadline(startedAt)
            ) {
                callback(false)
                return@captureLiveSnapshotAsync
            }
            val scrollAction = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            val dispatched = scrollable.performAction(scrollAction)
            if (dispatched) invalidateObservedSnapshot()
            callback(dispatched)
        }
    }

    private fun sameVerifiedScreen(expected: UiSnapshot, live: UiSnapshot): Boolean =
        live.packageName == expected.packageName &&
            live.screenFingerprint() == expected.screenFingerprint() &&
            SafetyPolicy.highRiskScreenReason(live) == null

    private fun executionWithinDeadline(startedAt: Long): Boolean =
        SystemClock.elapsedRealtime() - startedAt in 0..MAX_EXECUTION_MILLIS

    private fun performGlobalActionClearingRoute(action: Int): Boolean {
        clearTrustedSettingsContext()
        return performGlobalAction(action)
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

    private fun booleanStateFromDescription(value: String?): Boolean? = when (
        value.orEmpty().trim().lowercase()
    ) {
        "켜짐", "사용", "사용 중", "on", "checked", "enabled", "true" -> true
        "꺼짐", "사용 안 함", "off", "unchecked", "disabled", "false" -> false
        else -> null
    }

    companion object {
        private const val STEP_SETTLE_MILLIS = 650L
        private const val RETURN_TO_APP_MILLIS = 800L
        private const val MAX_EXECUTION_MILLIS = 45_000L
        private const val TRUSTED_SETTINGS_ROUTE_TTL_MILLIS = 120_000L
        private const val OBSERVED_SNAPSHOT_TTL_MILLIS = 120_000L
        private const val OBSERVED_SNAPSHOT_REFRESH_MILLIS = 10_000L
        private const val POST_EXECUTION_REFRESH_MILLIS = 350L
        private const val OVERLAY_CAPTURE_RETRY_MILLIS = 120L
        private const val OVERLAY_CAPTURE_MAX_RETRIES = 4
        private const val MAX_VISUAL_FINGERPRINT_DISTANCE = 10
        private const val MAX_EVENT_ANCESTOR_DEPTH = 64
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private val TRUSTED_SETTINGS_STATE_RESOURCE_IDS = setOf(
            "android:id/switch_widget",
            "com.android.settings:id/switchwidget",
            "com.android.settings:id/switch_widget",
        )
        private const val CONSENT_PREFERENCES = "sonju_preferences"
        private const val CONSENT_KEY = "accessibility_disclosure_accepted_v1"
        private const val VISUAL_CONSENT_KEY = "visual_screen_consent_v1"
        const val EXTRA_FROM_OVERLAY = "com.hwanghj09.sonju.extra.FROM_OVERLAY"
        const val EXTRA_AUTO_START_VOICE = "com.hwanghj09.sonju.extra.AUTO_START_VOICE"
        const val EXTRA_VOICE_COMMAND = "com.hwanghj09.sonju.extra.VOICE_COMMAND"
        const val EXTRA_BAEMIN_REVIEW = "com.hwanghj09.sonju.extra.BAEMIN_REVIEW"
        const val EXTRA_BAEMIN_STATUS = "com.hwanghj09.sonju.extra.BAEMIN_STATUS"
        const val EXTRA_BAEMIN_STATUS_SUCCESS = "com.hwanghj09.sonju.extra.BAEMIN_STATUS_SUCCESS"
        private const val BAEMIN_INITIAL_DELAY_MILLIS = 1_500L
        private const val BAEMIN_STEP_SETTLE_MILLIS = 900L
        private const val BAEMIN_RETURN_DELAY_MILLIS = 700L
        private const val BAEMIN_SESSION_TIMEOUT_MILLIS = 5 * 60_000L
        private const val BAEMIN_MAX_STEPS = 14
        private const val BAEMIN_MAX_CONSECUTIVE_FAILURES = 3

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
            "네이버" to setOf("naver"),
            "크롬" to setOf("chrome"),
            "구글 지도" to setOf("지도", "maps", "google maps"),
            "구글지도" to setOf("지도", "maps", "google maps"),
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
