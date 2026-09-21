package com.hwanghj09.sonju

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hwanghj09.sonju.accessibility.UiTreeReader
import com.hwanghj09.sonju.agent.*
import com.hwanghj09.sonju.ai.PlannerObservation
import com.hwanghj09.sonju.verifier.VerificationResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class ToastFeedbackDeviceTest {
    @Test fun realToastSurvivesDisappearanceAndPreventsABlindSecondClick() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val service = VoiceControlDeviceTest().connectedService()
        assertTrue(service.serviceInfo.eventTypes and AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED != 0)
        instrumentation.targetContext.startActivity(Intent().setComponent(ComponentName(
            "com.hwanghj09.sonju.test", AppSkillLifecycleFixtureActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("toast", true))

        fun observe(): UiSnapshot? {
            var snapshot: UiSnapshot? = null
            instrumentation.runOnMainSync {
                val type = service.javaClass
                val root = type.getDeclaredMethod("bestAvailableApplicationRoot").apply { isAccessible = true }
                    .invoke(service) as? AccessibilityNodeInfo
                if (root?.packageName?.toString() == "com.hwanghj09.sonju.test") {
                    val epoch = (type.getDeclaredField("epoch").apply { isAccessible = true }.get(service) as AtomicLong).get()
                    val raw = UiTreeReader.snapshot(root, epoch, service.currentDisplayBounds())
                    snapshot = type.getDeclaredMethod("snapshotWithTrustedRoute", UiSnapshot::class.java)
                        .apply { isAccessible = true }.invoke(service, raw) as UiSnapshot
                }
            }
            return snapshot
        }
        fun awaitScreen(predicate: (UiSnapshot) -> Boolean): UiSnapshot {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < deadline) {
                observe()?.takeIf(predicate)?.let { return it }
                SystemClock.sleep(100)
            }
            error("Expected toast fixture state was not observed")
        }
        val initial = awaitScreen { it.elements.any { node -> node.text == "기본 항목" } }
        val runtime = SonjuAgentRuntime.get(instrumentation.targetContext)
        fun click(label: String): ExecutionResult {
            val before = requireNotNull(observe())
            val node = before.elements.single { it.text == label }
            val plan = AgentPlan("기본 항목이 불가능하면 대체 항목 실행", label, RiskLevel.LOW, .99,
                listOf(AgentAction(ActionType.CLICK, label, node.path)), PlanSource.LOCAL_RULE)
            val verified = runtime.verify(plan.goal, plan, before)
            assertTrue(verified.toString(), verified is VerificationResult.Allowed)
            val latch = CountDownLatch(1)
            var result: ExecutionResult? = null
            instrumentation.runOnMainSync {
                service.executePlan((verified as VerificationResult.Allowed).verifiedPlan, before) {
                    result = it
                    latch.countDown()
                }
            }
            assertTrue(latch.await(25, TimeUnit.SECONDS))
            return requireNotNull(result)
        }
        try {
            val first = click("기본 항목")
            val observed = awaitScreen { it.recentToasts.any { toast -> toast.text.contains("대체 항목을 선택하세요") } }
            assertFalse("Toast alone must not prove success: $first", first.success)
            assertTrue(first.message.contains("토스트"))
            assertTrue(observed.hasNewToastSince(initial))
            assertFalse(observed.elements.any { it.text?.contains("대체 항목을 선택하세요") == true })
            SystemClock.sleep(3_500)
            val afterDisappearance = requireNotNull(observe())
            assertTrue(PlannerObservation.render(afterDisappearance, "기본 항목 실행").contains("대체 항목을 선택하세요"))
            assertTrue(click("대체 항목").success)
            val result = awaitScreen { it.elements.any { node -> node.text == "대체 작업 완료" } }
            assertTrue("Executor must not blindly dispatch the toast-producing click again",
                result.elements.any { it.text == "기본 항목 실행 횟수: 1" })
        } finally {
            instrumentation.runOnMainSync { service.stopCurrentExecution() }
        }
    }
}
