package com.hwanghj09.sonju

import com.hwanghj09.sonju.accessibility.UiTreeReader
import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeReaderTest {
    @Test
    fun fourAndFiveDigitCodesAreRedacted() {
        assertTrue(UiTreeReader.isSensitiveText("인증번호 1234"))
        assertTrue(UiTreeReader.isSensitiveText("OTP 83 920"))
        assertTrue(UiTreeReader.isSensitiveText("839201"))
        assertTrue(UiTreeReader.isSensitiveText("1234"))
        assertTrue(UiTreeReader.isSensitiveText("١٢٣٤"))
        assertTrue(UiTreeReader.isSensitiveText("••••"))
    }

    @Test
    fun separatedPinIsSensitive() {
        assertTrue(UiTreeReader.isSensitiveText("p i n"))
        assertTrue(UiTreeReader.isSensitiveText("p\u034Fi\u034Fn"))
        assertTrue(UiTreeReader.isSensitiveText("Digit 1 of 4, value 7"))
    }

    @Test
    fun ordinaryWordsContainingPinAreNotSensitive() {
        assertFalse(UiTreeReader.isSensitiveText("shopping"))
        assertFalse(UiTreeReader.isSensitiveText("App info"))
        assertFalse(UiTreeReader.isSensitiveText("spinner"))
        assertFalse(UiTreeReader.isSensitiveText("2026년"))
        assertFalse(UiTreeReader.isSensitiveText("5000원"))
        assertTrue(UiTreeReader.isSensitiveText("12:34"))
        assertFalse(UiTreeReader.isSensitiveText("12:34 알람"))
        assertFalse(UiTreeReader.isSensitiveText("현재 시간 12:34"))
        assertFalse(UiTreeReader.isSensitiveText("12:34:56"))
        assertFalse(UiTreeReader.isSensitiveText("July 18, 2026 12:34 PM"))
        assertFalse(UiTreeReader.isSensitiveText("July 18, 2026"))
        assertFalse(UiTreeReader.isSensitiveText("18 July 2026"))
        assertFalse(UiTreeReader.isSensitiveText("July 2026"))
        assertFalse(UiTreeReader.isSensitiveText("2026-07-18"))
        assertFalse(UiTreeReader.isSensitiveText("2026-07-18 12:34"))
        assertFalse(UiTreeReader.isSensitiveText("2026. 7. 18."))
        assertFalse(UiTreeReader.isSensitiveText("18/07/2026"))
        assertFalse(UiTreeReader.isSensitiveText("07/18/2026"))
        assertFalse(UiTreeReader.isSensitiveText("100,000원"))
        assertFalse(UiTreeReader.isSensitiveText("5000원짜리 상품"))
        assertFalse(UiTreeReader.isSensitiveText("5000원어치 상품"))
        assertFalse(UiTreeReader.isSensitiveText("가격은 5000원입니다."))
        assertTrue(UiTreeReader.isSensitiveText("2026"))
        assertTrue(UiTreeReader.isSensitiveText("29:99"))
    }

    @Test
    fun looseDateOrWonContextCannotHideSensitiveNumbers() {
        assertTrue(UiTreeReader.isSensitiveText("4111111111111111 원래 번호"))
        assertTrue(UiTreeReader.isSensitiveText("411111111111 원본"))
        assertTrue(UiTreeReader.isSensitiveText("411111111111 원하면"))
        assertTrue(UiTreeReader.isSensitiveText("July 4111 1111 1111 2026"))
        assertTrue(UiTreeReader.isSensitiveText("July 2026 4111 1111"))
        assertTrue(UiTreeReader.isSensitiveText("4111\u034F1111\u034F1111\u034F1111"))
    }

    @Test
    fun splitFourDigitCodeAcrossNodesIsRedactedAsOneCredential() {
        val elements = listOf("1", "2", "3", "4").mapIndexed { index, digit ->
            element(path = "0.$index", text = digit, left = index * 110)
        }

        val redacted = UiTreeReader.markSplitCredentialClusters(elements)

        assertTrue(redacted.all { it.sensitive })
        assertTrue(redacted.all { it.text == null })
    }

    @Test
    fun fourEmptyEditableOtpSlotsAreRedacted() {
        val elements = (0 until 4).map { index ->
            element(path = "0.$index", text = null, left = index * 110, editable = true)
        }

        val redacted = UiTreeReader.markSplitCredentialClusters(elements)

        assertTrue(redacted.all { it.sensitive })
    }

    @Test
    fun unrelatedEmptyClickableControlsAreNotAssumedToBeOtpSlots() {
        val ordinary = (0 until 4).map { index ->
            element(path = "0.$index", text = null, left = index * 110, clickable = true)
        }
        val otpSlots = (0 until 4).map { index ->
            element(
                path = "0.$index",
                text = null,
                left = index * 110,
                clickable = true,
                viewId = "com.example:id/otp_slot_$index",
            )
        }

        assertEquals(0, UiTreeReader.markSplitCredentialClusters(ordinary).count { it.sensitive })
        assertTrue(UiTreeReader.markSplitCredentialClusters(otpSlots).all { it.sensitive })
    }

    @Test
    fun twoMultiDigitFragmentsAndMaskedSlotsAreRedacted() {
        val splitDigits = listOf("12", "34").mapIndexed { index, digits ->
            element(path = "0.d$index", text = digits, left = index * 110)
        }
        val masks = listOf("•", "_", "*", "●").mapIndexed { index, mask ->
            element(path = "0.m$index", text = mask, left = index * 110)
        }

        assertTrue(UiTreeReader.markSplitCredentialClusters(splitDigits).all { it.sensitive })
        assertTrue(UiTreeReader.markSplitCredentialClusters(masks).all { it.sensitive })
    }

    @Test
    fun partiallyFilledEditableSlotsAreRedacted() {
        val elements = listOf("1", "2", null, null).mapIndexed { index, value ->
            element(path = "0.$index", text = value, left = index * 110, editable = true)
        }

        assertTrue(UiTreeReader.markSplitCredentialClusters(elements).all { it.sensitive })
    }

    @Test
    fun verticallySeparatedEmptyFieldsAreNotTreatedAsOtpSlots() {
        val elements = (0 until 4).map { index ->
            element(
                path = "0.$index",
                text = null,
                left = 0,
                top = index * 220,
                editable = true,
            )
        }

        assertEquals(0, UiTreeReader.markSplitCredentialClusters(elements).count { it.sensitive })
    }

    @Test
    fun threeUnrelatedSingleDigitsDoNotFormACredentialCluster() {
        val elements = listOf("1", "2", "3").mapIndexed { index, digit ->
            element(path = "0.$index", text = digit, left = index * 110)
        }

        val redacted = UiTreeReader.markSplitCredentialClusters(elements)

        assertEquals(0, redacted.count { it.sensitive })
    }

    @Test
    fun threeInteractiveSingleDigitCvcSlotsAreRedacted() {
        val elements = listOf("1", "2", "3").mapIndexed { index, digit ->
            element(
                path = "0.$index",
                text = digit,
                left = index * 110,
                editable = true,
            )
        }

        val redacted = UiTreeReader.markSplitCredentialClusters(elements)

        assertTrue(redacted.all { it.sensitive })
        assertTrue(redacted.all { it.text == null })
    }

    @Test
    fun describedOtpSlotsAreRedactedUsingTheirSlotMetadata() {
        val elements = (1..4).map { slot ->
            element(
                path = "0.$slot",
                text = null,
                contentDescription = "Digit $slot of 4, value $slot",
                left = (slot - 1) * 110,
                editable = true,
            )
        }

        val redacted = UiTreeReader.markSplitCredentialClusters(elements)

        assertTrue(redacted.all { it.sensitive })
        assertTrue(redacted.all { it.contentDescription == null })
    }

    @Test
    fun datePickerWeekRowIsNotMistakenForSplitCredential() {
        val elements = (14..20).mapIndexed { index, day ->
            element(
                path = "0.$index",
                text = day.toString(),
                left = index * 110,
                clickable = true,
                viewId = "com.example.calendar:id/month_view",
                className = "android.view.View",
            )
        }

        val protected = UiTreeReader.markSplitCredentialClusters(elements)

        assertEquals(0, protected.count { it.sensitive })
        assertEquals((14..20).map(Int::toString), protected.map(UiElement::text))
    }

    @Test
    fun sixConsecutiveOtpDigitsRemainSensitiveWithoutDatePickerMetadata() {
        val elements = (1..6).map { digit ->
            element(
                path = "0.$digit",
                text = digit.toString(),
                left = (digit - 1) * 110,
            )
        }

        val redacted = UiTreeReader.markSplitCredentialClusters(elements)

        assertTrue(redacted.all { it.sensitive })
    }

    private fun element(
        path: String,
        text: String?,
        left: Int,
        top: Int = 100,
        editable: Boolean = false,
        clickable: Boolean = editable,
        contentDescription: String? = null,
        viewId: String? = null,
        className: String = if (editable) "android.widget.EditText" else "android.widget.TextView",
    ) = UiElement(
        path = path,
        viewId = viewId,
        className = className,
        text = text,
        contentDescription = contentDescription,
        bounds = ScreenBounds(left, top, left + 90, top + 90),
        clickable = clickable,
        editable = editable,
        scrollable = false,
        enabled = true,
        visible = true,
        sensitive = false,
    )
}
