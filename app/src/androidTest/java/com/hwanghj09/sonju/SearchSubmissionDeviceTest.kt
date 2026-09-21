package com.hwanghj09.sonju

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hwanghj09.sonju.accessibility.SonjuAccessibilityService
import com.hwanghj09.sonju.accessibility.UiTreeReader
import com.hwanghj09.sonju.agent.*
import com.hwanghj09.sonju.verifier.VerificationResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class SearchSubmissionDeviceTest {
    @Test fun nativeSearchAction() = scenario("native")
    @Test fun customEditorActionId() = scenario("custom")
    @Test fun anonymousSearchEditor() = scenario("anonymous")
    @Test fun noOpActionsFallBackToARealEnterKey() = scenario("key")
    @Test fun delayedSearchIsSubmittedOnlyOnce() = scenario("slow")
    @Test fun webViewFormIsActuallySubmitted() = scenario("web")
    @Test fun deeplyNestedWebSearchIsObservedAndSubmitted() = scenario("deep_web")
    @Test fun acceptedActionsWithoutResultsNeverPass() = scenario("no_effect", expectedSuccess = false)

    private fun scenario(mode: String, expectedSuccess: Boolean = true) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val service = VoiceControlDeviceTest().connectedService()
        instrumentation.targetContext.startActivity(Intent().setComponent(ComponentName(
            "com.hwanghj09.sonju.test", SearchSubmissionFixtureActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("mode", mode))
        val deadline = SystemClock.uptimeMillis() + 25_000
        fun observe(): UiSnapshot? {
            var snapshot: UiSnapshot? = null
            instrumentation.runOnMainSync {
                val type = service.javaClass
                val root = type.getDeclaredMethod("bestAvailableApplicationRoot").apply { isAccessible = true }
                    .invoke(service) as? android.view.accessibility.AccessibilityNodeInfo
                val epoch = (type.getDeclaredField("epoch").apply { isAccessible = true }.get(service) as AtomicLong).get()
                if (root?.packageName?.toString() == "com.hwanghj09.sonju.test") {
                    val raw = UiTreeReader.snapshot(root, epoch, service.currentDisplayBounds())
                    snapshot = type.getDeclaredMethod("snapshotWithTrustedRoute", UiSnapshot::class.java)
                        .apply { isAccessible = true }.invoke(service, raw) as UiSnapshot
                }
            }
            return snapshot
        }
        var before: UiSnapshot? = null
        fun editors(snapshot: UiSnapshot) = snapshot.elements.filter { node ->
            node.editable && node.visible && !node.sensitive && !node.className.endsWith("WebView") &&
                snapshot.elements.none { it.editable && it.visible && it.path.startsWith("${node.path}.") }
        }
        while (SystemClock.uptimeMillis() < deadline) {
            before = observe()
            if (before?.let(::editors)?.size == 1 &&
                before.elements.any { it.text == "Fixture $mode" }) break
            SystemClock.sleep(150)
        }
        val input = editors(requireNotNull(before)).single()
        if (mode == "deep_web") {
            assertTrue("Fixture must exceed the former traversal limit", input.path.count { it == '.' } > 32)
            assertFalse("Ordinary nested web content must be complete", before.treeTruncated)
        }
        val runtime = SonjuAgentRuntime.get(instrumentation.targetContext)
        fun execute(action: AgentAction): ExecutionResult {
            val observed = requireNotNull(observe())
            val currentInput = editors(observed).single()
            val plan = AgentPlan("손주 검색해줘", "검색", RiskLevel.LOW, .99,
                listOf(action.copy(target = currentInput.viewId?.takeIf(String::isNotBlank) ?: currentInput.path)), PlanSource.LOCAL_RULE)
            val verified = runtime.verify("손주 검색해줘", plan, observed)
            assertTrue("$mode verifier: $verified", verified is VerificationResult.Allowed)
            val done = CountDownLatch(1)
            var result: ExecutionResult? = null
            instrumentation.runOnMainSync {
                service.executePlan((verified as VerificationResult.Allowed).verifiedPlan, observed) {
                    result = it; done.countDown()
                }
            }
            assertTrue("$mode timed out", done.await(35, TimeUnit.SECONDS))
            return requireNotNull(result)
        }
        try {
            assertTrue("$mode text must be applied", execute(AgentAction(ActionType.SET_TEXT, "검색어 입력", input.path, "손주")).success)
            SystemClock.sleep(500)
            val typed = editors(requireNotNull(observe())).single()
            val submitted = execute(AgentAction(ActionType.SUBMIT_TEXT, "검색 제출", typed.path, "손주"))
            assertEquals("$mode result: $submitted", expectedSuccess, submitted.success)
            assertEquals(expectedSuccess, submitted.postconditionSatisfied)
            if (mode == "slow") SystemClock.sleep(1500)
            val labels = requireNotNull(observe()).elements.mapNotNull { it.text }
            assertEquals("$mode must submit exactly once", expectedSuccess, labels.any { it.contains("검색 결과: 손주 제출 1") })
            assertFalse("$mode must not duplicate submission", labels.any { it.contains("제출 2") })
        } finally {
            instrumentation.runOnMainSync { service.stopCurrentExecution() }
        }
    }
}
