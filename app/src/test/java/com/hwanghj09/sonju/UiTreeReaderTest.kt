package com.hwanghj09.sonju

import com.hwanghj09.sonju.accessibility.UiTreeReader
import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiNodeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeReaderTest {
    @Test
    fun densePublicRouteLabelsAreClassifiedWithoutRepeatedRegexCompilation() {
        val labels = listOf(
            "테스트 목적지",
            "가상시 예시구 테스트로 123",
            "39분",
            "오후 9:19",
            "오후 9:58",
            "3,200원",
            "예시마을1단지·테스트중",
            "350",
            "330",
            "375",
            "누리4",
            "가상역·예시빌딩",
            "바로 안내시작",
        )
        val startedAt = System.nanoTime()
        var sensitiveLabels = 0

        repeat(10_000) {
            labels.forEach {
                if (UiTreeReader.isSensitiveText(it)) sensitiveLabels += 1
            }
        }

        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(0, sensitiveLabels)
        assertTrue("route label classification took ${elapsedMillis}ms", elapsedMillis < 3_000)
    }

    @Test
    fun deliveryDurationRangeIsPublicNotAShortCredential() {
        assertFalse(UiTreeReader.isSensitiveText("44~59분 후 도착"))
        assertFalse(UiTreeReader.isSensitiveText("45 minutes"))
    }

    @Test
    fun setTextCapabilityKeepsTransientComposeInputEditable() {
        assertTrue(UiTreeReader.hasEditableSemantics(false, setOf(UiNodeAction.SET_TEXT)))
        assertFalse(UiTreeReader.hasEditableSemantics(false, emptySet()))
    }

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
        assertFalse(UiTreeReader.isSensitiveText("1,163개"))
        assertTrue(UiTreeReader.isSensitiveText("(1,163)"))
        assertTrue(UiTreeReader.isMirroredPublicCount("(1,163)", "1,163개"))
        assertFalse(UiTreeReader.isMirroredPublicCount("1234", "OTP 1234"))
        assertTrue(UiTreeReader.isMirroredPublicAmount("15900", "15,900원"))
        assertFalse(UiTreeReader.isMirroredPublicAmount("1234", "OTP 1234"))
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
        assertFalse(UiTreeReader.isSensitiveText("15,900원리뷰 4"))
        assertFalse(UiTreeReader.isSensitiveText("15,900원4,800원0원0원20,700원"))
        assertTrue(UiTreeReader.isSensitiveText("411111111111원5000원"))
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

    @Test
    fun hiddenSensitiveNodesDoNotContaminateVisibleControlsAtReusedCoordinates() {
        val hiddenSecret = element("0.hidden", "1234", left = 600, top = 2_500)
            .copy(sensitive = true, visible = false)
        val visibleButton = element(
            "0.order",
            "주문서로 이동",
            left = 600,
            top = 2_500,
            clickable = true,
        )

        val protected = UiTreeReader.propagateSensitiveContext(
            listOf(hiddenSecret, visibleButton),
        )

        assertTrue(protected.first { it.path == hiddenSecret.path }.sensitive)
        assertFalse(protected.first { it.path == visibleButton.path }.sensitive)
    }

    @Test
    fun visibleSensitiveSiblingsStillProtectTheirSharedControl() {
        val visibleSecret = element("0.row.secret", "1234", left = 0)
            .copy(sensitive = true)
        val siblingButton = element("0.row.button", "복사", left = 120, clickable = true)

        val protected = UiTreeReader.propagateSensitiveContext(
            listOf(visibleSecret, siblingButton),
        )

        assertTrue(protected.all { it.sensitive })
    }

    @Test
    fun distantSiblingsInOneLargeLayoutDoNotContaminateEachOther() {
        val visibleSecret = element("0.content.phone", "01012345678", left = 0, top = 100)
            .copy(sensitive = true)
        val distantMenu = element(
            "0.content.menu",
            "크림 파스타 14,000원",
            left = 0,
            top = 900,
            clickable = true,
        )

        val protected = UiTreeReader.propagateSensitiveContext(
            listOf(visibleSecret, distantMenu),
        )

        assertTrue(protected.first { it.path == visibleSecret.path }.sensitive)
        assertFalse(protected.first { it.path == distantMenu.path }.sensitive)
    }

    @Test
    fun sameRowElementsInDifferentBranchesNeedActualHorizontalProximity() {
        val edgeSecret = element("0.toolbar.secret", "1234", left = 950, top = 100)
            .copy(sensitive = true)
        val unrelatedCard = element(
            "0.content.card",
            "인기 파스타 15,900원",
            left = 0,
            top = 100,
            clickable = true,
        )

        val protected = UiTreeReader.propagateSensitiveContext(
            listOf(edgeSecret, unrelatedCard),
        )

        assertTrue(protected.first { it.path == edgeSecret.path }.sensitive)
        assertFalse(protected.first { it.path == unrelatedCard.path }.sensitive)
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
