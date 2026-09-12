package com.hwanghj09.sonju

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hwanghj09.sonju.agent.HospitalReservationWorkflow
import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import com.hwanghj09.sonju.vision.OnDevicePageReader
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in local recognition of captures. Does not navigate or enter any credentials. */
@RunWith(AndroidJUnit4::class)
class HospitalPageReadDeviceTest {
    @Test fun recognizesObservedSamsungLoginWithoutWebAccessibilityNodes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("loginFixture") == "true")
        val snapshot = readFixture("sonju-login-diagnostic.png")
        assertEquals("snubh.org", HospitalReservationWorkflow.location(snapshot)?.host)
        assertTrue("Observed login must pause", HospitalReservationWorkflow.loginRequired(snapshot))
        assertFalse(HospitalReservationWorkflow.canResume(snapshot))
        assertNull(HospitalReservationWorkflow.resultText(snapshot))
    }

    @Test fun recognizesObservedSamsungEmptyTreatmentResult() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("resultFixture") == "true")
        val snapshot = readFixture("sonju-result-diagnostic.png")
        assertFalse(HospitalReservationWorkflow.loginRequired(snapshot))
        assertTrue(HospitalReservationWorkflow.canResume(snapshot))
        assertTrue("Observed result must complete", HospitalReservationWorkflow.resultText(snapshot)
            .orEmpty().replace(" ", "").contains("진료예약현황이존재하지않습니다"))
        assertTrue(requireNotNull(HospitalReservationWorkflow.completionPlan(
            "분당서울대병원 예약 기록 알려줘.", snapshot)).goalCompleted)
    }

    @Test fun lossyJpegDropsEvidenceFromObservedEmptyResultsPage() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("resultFixture") == "true")
        val registry = InstrumentationRegistry.getInstrumentation()
        val original = requireNotNull(BitmapFactory.decodeFile(
            File(registry.targetContext.cacheDir, "sonju-result-diagnostic.png").path))
        val output = java.io.ByteArrayOutputStream()
        original.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, output)
        original.recycle()
        val bytes = output.toByteArray()
        val lines = readBitmap(requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)))
        val snapshot = readFixture("sonju-result-diagnostic.png").copy(localReadOnlyText = lines)
        val publicLabels = lines.filter { it.replace(" ", "") in setOf("로그아웃", "예약현황조회", "예약조회및취소",
            "진료예약현황이존재하지않습니다.", "진료예약현황") }
        Log.i("SonjuReadTest", "jpeg lines=${lines.size} publicLabels=$publicLabels result=${HospitalReservationWorkflow.resultText(snapshot) != null}")
        Log.i("SonjuReadTest", "empty result label=" + lines.filter { it.replace(" ", "").contains("존재하지") })
        assertNull("Fixture reproduces the old lossy capture failure", HospitalReservationWorkflow.resultText(snapshot))
        assertNotNull("Original pixels must complete", HospitalReservationWorkflow.resultText(readFixture("sonju-result-diagnostic.png")))
    }

    private fun readFixture(name: String): UiSnapshot {
        val registry = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(BitmapFactory.decodeFile(
            File(registry.targetContext.cacheDir, name).path))
        val lines = readBitmap(bitmap)
        return UiSnapshot("com.sec.android.app.sbrowser", "Samsung Internet", epoch = 1,
            elements = listOf(UiElement("0.1", "com.sec.android.app.sbrowser:id/location_bar_edit_text",
                "android.widget.EditText", "\u200esnubh.org", null, ScreenBounds(222, 2367, 918, 2481),
                clickable = true, editable = true, scrollable = false, enabled = true, visible = true, sensitive = false)),
            localReadOnlyText = lines)
    }

    private fun readBitmap(bitmap: android.graphics.Bitmap): List<String> {
        val latch = CountDownLatch(1)
        var read: Result<List<String>>? = null
        OnDevicePageReader.read(bitmap) { read = it; latch.countDown() }
        assertTrue("Local OCR timeout", latch.await(30, TimeUnit.SECONDS))
        return requireNotNull(read).getOrThrow()
    }
}
