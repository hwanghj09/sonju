package com.hwanghj09.sonju

import android.Manifest
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import com.hwanghj09.sonju.accessibility.SonjuAccessibilityService
import com.hwanghj09.sonju.voice.WakeWordService
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun applicationSecurityFlags_areFailClosed() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.hwanghj09.sonju", appContext.packageName)
        val flags = appContext.applicationInfo.flags
        assertEquals(0, flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals(0, flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
    }

    @Test
    fun accessibilityService_isPrivateAndDoesNotRequestRawCapabilities() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val serviceInfo = appContext.packageManager.getServiceInfo(
            ComponentName(appContext, SonjuAccessibilityService::class.java),
            PackageManager.GET_META_DATA,
        )
        assertFalse(serviceInfo.exported)
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, serviceInfo.permission)
        assertEquals(
            R.xml.accessibility_service_config,
            serviceInfo.metaData.getInt("android.accessibilityservice"),
        )

        appContext.resources.getXml(R.xml.accessibility_service_config).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT &&
                parser.eventType != XmlPullParser.START_TAG
            ) {
                parser.next()
            }
            val androidNamespace = "http://schemas.android.com/apk/res/android"
            assertNull(parser.getAttributeValue(androidNamespace, "canTakeScreenshot"))
            assertTrue(
                parser.getAttributeBooleanValue(
                    androidNamespace,
                    "canRetrieveWindowContent",
                    false,
                ),
            )
            assertNull(parser.getAttributeValue(androidNamespace, "canPerformGestures"))
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun wakeWordService_isPrivateAndDeclaresMicrophoneType() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val serviceInfo = appContext.packageManager.getServiceInfo(
            ComponentName(appContext, WakeWordService::class.java),
            PackageManager.GET_META_DATA,
        )
        assertFalse(serviceInfo.exported)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            serviceInfo.foregroundServiceType and
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }
}
