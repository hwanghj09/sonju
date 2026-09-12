package com.hwanghj09.sonju

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hwanghj09.sonju.accessibility.UiTreeReader
import com.hwanghj09.sonju.agent.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserInterventionDeviceTest {
    @Test fun androidCredentialPromptKeepsOnlyTheLocalKindAfterRedaction() {
        @Suppress("DEPRECATION") val node = AccessibilityNodeInfo.obtain().apply {
            packageName = "example.client"
            className = "android.widget.EditText"
            isEditable = true
            isVisibleToUser = true
            isEnabled = true
            text = "938471"
            hintText = "인증번호를 입력해 주세요"
        }
        val snapshot = UiTreeReader.snapshot(node, 1)
        assertEquals(UserIntervention.Kind.TWO_FACTOR, snapshot.userIntervention)
        assertTrue(snapshot.elements.single().sensitive)
        assertFalse(snapshot.toString().contains("938471"))
        assertFalse(snapshot.compactText().contains("938471"))
        assertEquals(UserIntervention.Kind.TWO_FACTOR, UserIntervention.required(snapshot, "내역 알려줘"))
    }

    @Test fun pendingGoalRestoresBindingExpiresAndClearsWithoutPageOrCredentialData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("pending_user_login", Context.MODE_PRIVATE)
        val previous = prefs.all
        assertTrue("Run this test only when no user handoff is pending", previous.isEmpty())
        val store = PendingUserInterventionStore(context)
        try {
            val handoff = AutonomySession.UserHandoff(null, 100, "example.auth", "hash", "template", null,
                UserIntervention.Kind.BIOMETRIC, "example.client")
            store.save("내역 알려줘", handoff)
            val restored = requireNotNull(PendingUserInterventionStore(context).restore(UiSnapshot.empty()))
            assertEquals("example.client", restored.resumePackage)
            assertEquals(UserIntervention.Kind.BIOMETRIC, restored.kind)
            assertFalse(restored.automaticResume)
            assertEquals(setOf("goal", "package", "before_package", "kind", "before", "template", "saved_at"), prefs.all.keys)
            prefs.edit().putLong("saved_at", System.currentTimeMillis() - 86_400_001).commit()
            assertNull(store.read())
            store.save("내역 알려줘", handoff)
            prefs.edit().putLong("saved_at", System.currentTimeMillis() + 60_000).commit()
            assertNull(store.read())
            store.clear()
            assertTrue(prefs.all.isEmpty())
        } finally {
            store.clear()
        }
    }
}
