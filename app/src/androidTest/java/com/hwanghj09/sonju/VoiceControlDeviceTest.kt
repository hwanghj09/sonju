package com.hwanghj09.sonju

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hwanghj09.sonju.accessibility.SonjuAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the actual voice callbacks with transcripts; it does not claim acoustic recognition. */
@RunWith(AndroidJUnit4::class)
class VoiceControlDeviceTest {
    @Test fun staleOrPartialRepliesCannotConfirmAndEveryApprovalIsConsumedOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val service = connectedService()
        val type = service.javaClass
        fun invoke(name: String) = type.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
        fun listener(): RecognitionListener {
            val generation = type.getDeclaredField("voiceRecognitionGeneration").apply { isAccessible = true }.getLong(service)
            return type.getDeclaredMethod("voicePanelRecognitionListener", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, generation) as RecognitionListener
        }
        fun reply(text: String) = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        }
        var approvals = 0
        val approve: () -> Unit = { approvals++ }
        instrumentation.runOnMainSync {
            try {
                type.getDeclaredMethod("showVoicePanel", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(service, false)
                val confirm = type.getDeclaredMethod("showVoicePanelConfirmation", String::class.java,
                    String::class.java, Boolean::class.javaPrimitiveType, Function0::class.java).apply { isAccessible = true }
                confirm.invoke(service, "테스트용 동작을 실행할까요?", "진행", true, approve)
                invoke("stopExplanationSpeech")
                val stale = listener()
                stale.onPartialResults(reply("진행해"))
                assertEquals(0, approvals)
                invoke("stopVoicePanelRecognizer")
                stale.onResults(reply("진행해"))
                assertEquals(0, approvals)
                val current = listener()
                current.onResults(reply("네, 진행해 주세요"))
                current.onResults(reply("진행해"))
                assertEquals(1, approvals)

                confirm.invoke(service, "두 번째 테스트 동작을 실행할까요?", "진행", true, approve)
                invoke("stopExplanationSpeech")
                listener().onResults(reply("네이버 열어줘"))
                assertEquals(1, approvals)
                invoke("stopExplanationSpeech")
                listener().onResults(reply("네 하지 마세요"))
                assertEquals(1, approvals)
                assertNull(type.getDeclaredField("pendingVoiceConfirmation").apply { isAccessible = true }.get(service))

                confirm.invoke(service, "시간이 지난 동작입니다.", "진행", true, approve)
                val expired = listener()
                (type.getDeclaredField("voiceConfirmationTimeout").apply { isAccessible = true }.get(service) as Runnable).run()
                expired.onResults(reply("네"))
                assertEquals(1, approvals)
                assertNull(type.getDeclaredField("pendingVoiceConfirmation").apply { isAccessible = true }.get(service))
            } finally {
                type.getDeclaredMethod("dismissVoicePanel", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(service, false)
            }
        }
    }

    @Test fun completedNoticeCanStartTheNextVoiceRequestWithoutTouchingFeedback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val service = connectedService()
        val type = service.javaClass
        instrumentation.runOnMainSync {
            try {
                type.getDeclaredMethod("showVoicePanel", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(service, false)
                type.getDeclaredMethod("deliverScreenExplanation", String::class.java)
                    .apply { isAccessible = true }.invoke(service, "테스트 완료")
                val panel = type.getDeclaredField("voicePanel").apply { isAccessible = true }
                val previous = requireNotNull(panel.get(service))
                type.getDeclaredMethod("routeOverlayVoice", Boolean::class.javaPrimitiveType, String::class.java)
                    .apply { isAccessible = true }.invoke(service, false, null)
                assertNotSame(previous, requireNotNull(panel.get(service)))
                assertFalse(type.getDeclaredField("voicePanelCommandDispatched").apply { isAccessible = true }.getBoolean(service))
                assertEquals("", type.getDeclaredField("voicePanelAccumulatedCommand").apply { isAccessible = true }.get(service))
            } finally {
                type.getDeclaredMethod("dismissVoicePanel", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(service, false)
            }
        }
    }

    @Test fun spokenStopPreemptsExecutionAndReleasesTheScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val service = connectedService()
        val type = service.javaClass
        fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        var generation = 0L
        try {
            instrumentation.runOnMainSync {
                type.getDeclaredMethod("showVoicePanel", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(service, false)
                assertEquals(true, type.getDeclaredMethod("beginCommandControl")
                    .apply { isAccessible = true }.invoke(service))
                field("overlayCommandExecutionActive").setBoolean(service, true)
                field("overlayCaptureInProgress").setBoolean(service, true)
                generation = field("executionGeneration").getLong(service)
                service.requestVoiceWake("손주야 멈춰")
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertFalse(field("overlayCommandExecutionActive").getBoolean(service))
                assertFalse(field("overlayCaptureInProgress").getBoolean(service))
                assertFalse(field("commandControlActive").getBoolean(service))
                assertNull(field("controlGlow").get(service))
                assertTrue(field("executionGeneration").getLong(service) > generation)
            }
        } finally {
            instrumentation.runOnMainSync {
                type.getDeclaredMethod("dismissVoicePanel", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(service, false)
            }
        }
    }

    /** Instrumentation kills the app process. Restart only an already-enabled service through
     * the visible Settings controls; never write accessibility permissions through the shell. */
    internal fun connectedService(): SonjuAccessibilityService {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context = instrumentation.targetContext
        fun <T : Any> awaitValue(description: String, value: () -> T?): T {
            val deadline = SystemClock.uptimeMillis() + 10_000
            do {
                value()?.let { return it }
                SystemClock.sleep(100)
            } while (SystemClock.uptimeMillis() < deadline)
            error("Timed out: $description")
        }
        SonjuAccessibilityService.instance?.let { return it }
        val component = ComponentName(context, SonjuAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':')
            .mapNotNull(ComponentName::unflattenFromString)
        check(component in enabled) { "Sonju must already be enabled by the device owner" }
        val label = context.getString(R.string.accessibility_service_label)
        fun nodes(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            yield(node)
            repeat(node.childCount) { index -> node.getChild(index)?.let { yieldAll(nodes(it)) } }
        }
        fun screenNodes() = ui.rootInActiveWindow?.let { root ->
            nodes(root).filter { it.isVisibleToUser }.toList()
        }.orEmpty()
        fun current() = screenNodes().takeIf { list ->
                list.any { it.text?.contains(label) == true }
        }.orEmpty()
        fun click(node: AccessibilityNodeInfo) {
            var target = node
            while (!target.isClickable && target.parent != null) target = target.parent
            check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Settings rejected the test click" }
        }
        instrumentation.startActivitySync(Intent(context,
            com.hwanghj09.sonju.accessibility.TouchGuardTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        val apps = setOf("설치된 앱", "Installed apps")
        val entry = awaitValue("Sonju or installed apps row") {
            val visible = screenNodes()
            visible.firstOrNull { it.text?.toString() == label }
                ?: visible.firstOrNull { it.text?.toString() in apps }
        }
        click(entry)
        if (entry.text?.toString() != label) click(awaitValue("Sonju row") {
            screenNodes().singleOrNull { it.text?.toString() == label }
        })
        val toggle = awaitValue("enabled service switch") { current().firstOrNull { it.isCheckable && it.isChecked } }
        click(toggle)
        val stop = setOf("끄기", "중지", "Stop", "Turn off")
        click(awaitValue("stop service dialog") { current().singleOrNull { it.text?.toString() in stop } })
        click(awaitValue("disabled service switch") { current().firstOrNull { it.isCheckable && !it.isChecked } })
        val allow = setOf("허용", "Allow")
        click(awaitValue("restart service dialog") { current().singleOrNull { it.text?.toString() in allow } })
        return awaitValue("accessibility reconnect") { SonjuAccessibilityService.instance }
    }
}
