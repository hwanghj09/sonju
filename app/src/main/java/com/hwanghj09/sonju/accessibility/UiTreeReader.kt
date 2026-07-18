package com.hwanghj09.sonju.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

object UiTreeReader {
    internal const val MAX_ELEMENTS = 2_000
    private const val MAX_DEPTH = 24

    private data class TraversalState(
        val elements: MutableList<UiElement> = mutableListOf(),
        var truncated: Boolean = false,
    )

    private data class CredentialCandidate(
        val element: UiElement,
        val credentialUnits: Int,
        val singleDigitInteractive: Boolean,
        val numericValue: Int?,
    )

    private val sensitiveTerms = setOf(
        "비밀번호", "비번", "password", "passcode", "pin", "otp", "인증번호", "보안코드",
        "카드번호", "card number", "cvc", "cvv", "주민등록",
    )
    private val numericRunPattern = Regex(
        "(?<!\\p{Nd})\\p{Nd}(?:[\\s\\p{M}\\p{P}\\p{S}_]*\\p{Nd})*",
    )
    private const val ENGLISH_MONTH =
        "(?:january|february|march|april|may|june|july|august|september|" +
            "october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)"
    private const val DATE_YEAR = "(?:(?:19|20|21)[0-9]{2})"
    private const val DATE_MONTH = "(?:0?[1-9]|1[0-2])"
    private const val DATE_DAY = "(?:0?[1-9]|[12][0-9]|3[01])"
    private const val CLOCK_TIME =
        "(?:[01]?[0-9]|2[0-3])\\s*:\\s*[0-5][0-9](?:\\s*:\\s*[0-5][0-9])?"
    private const val ENGLISH_DATE_SEPARATOR = "(?:\\s*,\\s*|\\s+)"
    private val englishDatePattern = Regex(
        "(?<![A-Za-z0-9])(?:" +
            "$ENGLISH_MONTH\\s+$DATE_DAY(?:st|nd|rd|th)?$ENGLISH_DATE_SEPARATOR$DATE_YEAR|" +
            "$DATE_DAY(?:st|nd|rd|th)?\\s+$ENGLISH_MONTH\\s+$DATE_YEAR|" +
            "$ENGLISH_MONTH\\s+$DATE_YEAR" +
            ")(?:\\s+$CLOCK_TIME(?:\\s*(?:a\\.?m\\.?|p\\.?m\\.?))?)?(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val numericDatePatterns = listOf(
        Regex(
            "(?<!\\p{Nd})$DATE_YEAR\\s*([-./])\\s*$DATE_MONTH\\s*\\1\\s*" +
                "$DATE_DAY(?:\\s*\\.)?(?:\\s+$CLOCK_TIME)?(?!\\p{Nd})",
        ),
        Regex(
            "(?<!\\p{Nd})$DATE_DAY\\s*([-./])\\s*$DATE_MONTH\\s*\\1\\s*" +
                "$DATE_YEAR(?:\\s+$CLOCK_TIME)?(?!\\p{Nd})",
        ),
        Regex(
            "(?<!\\p{Nd})$DATE_MONTH\\s*([-./])\\s*$DATE_DAY\\s*\\1\\s*" +
                "$DATE_YEAR(?:\\s+$CLOCK_TIME)?(?!\\p{Nd})",
        ),
    )
    private val explicitTimeContextPattern = Regex(
        "(?:알람|시간|시각|시계|오전|오후|\\b(?:alarm|time|clock)\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val strongClockPattern = Regex(
        "(?<![A-Za-z0-9])(?:" +
            "$CLOCK_TIME\\s*(?:a\\.?m\\.?|p\\.?m\\.?)|" +
            "(?:오전|오후)\\s*$CLOCK_TIME|" +
            "(?:[01]?[0-9]|2[0-3])\\s*:\\s*[0-5][0-9]\\s*:\\s*[0-5][0-9]" +
            ")(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val englishCredentialSlotPattern = Regex(
        "\\b(?:digit|otp|pin|code)\\s*([0-9]{1,2})\\s*(?:of|/)\\s*" +
            "([0-9]{1,2})(?:\\s*[,;:]?\\s*(?:value|entered)\\s*(?:is\\s*)?" +
            "[0-9•●○◦*_\\-])?.*",
        RegexOption.IGNORE_CASE,
    )
    private val koreanCredentialSlotPattern = Regex(
        "(?:총\\s*)?([3-8])\\s*(?:자리|개)\\s*중\\s*([1-8])\\s*(?:번째|번)" +
            "(?:.*(?:값|입력)\\s*[0-9•●○◦*_\\-])?",
    )
    private val calendarContextTerms = setOf(
        "calendar", "datepicker", "date_picker", "month_view", "달력", "날짜선택",
    )
    private val maskedCredentialPattern = Regex("^[\\s•●○◦*_\\-]{4,19}$")

    fun snapshot(root: AccessibilityNodeInfo?, epoch: Long): UiSnapshot {
        if (root == null) return UiSnapshot.empty(epoch)

        val traversal = TraversalState()
        traverse(root, "0", 0, traversal)
        val rawWindowTitle = root.window?.title?.toString()?.take(120)
        val sensitiveWindow = isSensitiveText(rawWindowTitle)
        val splitProtectedElements = markSplitCredentialClusters(traversal.elements)
        val elements = if (sensitiveWindow) {
            splitProtectedElements.map { it.redacted() }
        } else {
            propagateSensitiveContext(splitProtectedElements)
        }
        return UiSnapshot(
            packageName = root.packageName?.toString().orEmpty().ifBlank { "unknown" },
            windowTitle = if (sensitiveWindow) "[민감 화면]" else rawWindowTitle,
            epoch = epoch,
            elements = elements,
            treeTruncated = traversal.truncated,
        )
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        path: String,
        depth: Int,
        state: TraversalState,
    ) {
        if (depth > MAX_DEPTH || state.elements.size >= MAX_ELEMENTS) {
            state.truncated = true
            return
        }

        val rawText = node.text?.toString()?.trim()?.take(120)
        val rawDescription = node.contentDescription?.toString()?.trim()?.take(120)
        val rawStateDescription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.stateDescription?.toString()?.trim()?.take(120)
        } else {
            null
        }
        val rawViewId = node.viewIdResourceName
        val sensitive = node.isPassword ||
            isSensitiveText(rawText) ||
            isSensitiveText(rawDescription) ||
            isSensitiveText(rawStateDescription) ||
            isSensitiveText(rawViewId)
        val bounds = Rect().also(node::getBoundsInScreen)

        state.elements += UiElement(
            path = path,
            viewId = rawViewId,
            className = node.className?.toString().orEmpty(),
            text = rawText.takeUnless { sensitive },
            contentDescription = rawDescription.takeUnless { sensitive },
            bounds = ScreenBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            clickable = node.isClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            visible = node.isVisibleToUser,
            sensitive = sensitive,
            checkable = node.isCheckable,
            checked = node.isChecked,
            selected = node.isSelected,
            stateDescription = rawStateDescription.takeUnless { sensitive },
        )

        for (index in 0 until node.childCount) {
            if (state.elements.size >= MAX_ELEMENTS) {
                state.truncated = true
                break
            }
            val child = node.getChild(index)
            if (child == null) {
                state.truncated = true
            } else {
                traverse(child, "$path.$index", depth + 1, state)
            }
        }
    }

    private fun propagateSensitiveContext(elements: List<UiElement>): List<UiElement> {
        val sensitiveElements = elements.filter { it.sensitive }
        if (sensitiveElements.isEmpty()) return elements

        return elements.map { element ->
            if (element.sensitive || sensitiveElements.any { sensitive ->
                    isRelatedToSensitiveElement(element, sensitive)
                }
            ) {
                element.redacted()
            } else {
                element
            }
        }
    }

    internal fun markSplitCredentialClusters(elements: List<UiElement>): List<UiElement> {
        val candidates = elements.mapNotNull { element ->
            if (element.sensitive || !element.visible || !element.enabled) {
                return@mapNotNull null
            }
            val raw = element.text?.trim().takeUnless { it.isNullOrBlank() }
                ?: element.contentDescription?.trim().takeUnless { it.isNullOrBlank() }
                ?: element.stateDescription?.trim().takeUnless { it.isNullOrBlank() }
            val normalized = raw?.let {
                Normalizer.normalize(it, Normalizer.Form.NFKC)
                    .replace(Regex("[\\p{Cf}\\p{Cc}\\p{M}\\s]"), "")
            }.orEmpty()
            val digitCount = normalized.count(Char::isDigit)
            val digitFragment = normalized.isNotEmpty() &&
                normalized.all(Char::isDigit) && digitCount in 1..3
            val maskedFragment = normalized.isNotEmpty() && normalized.length <= 3 &&
                normalized.all { it in setOf('•', '●', '○', '◦', '*', '_', '-') }
            val emptyInputSlot = raw.isNullOrBlank() && (
                element.editable || element.clickable && hasCredentialSlotMetadata(element)
                )
            val describedCredentialSlot = raw?.let(::isCredentialSlotDescription) == true &&
                (element.editable || element.clickable)
            if (!digitFragment && !maskedFragment && !emptyInputSlot &&
                !describedCredentialSlot
            ) {
                return@mapNotNull null
            }
            CredentialCandidate(
                element = element,
                credentialUnits = when {
                    digitFragment -> digitCount
                    maskedFragment -> normalized.length
                    else -> 1
                },
                singleDigitInteractive = (digitFragment && digitCount == 1 ||
                    describedCredentialSlot) && (element.editable || element.clickable),
                numericValue = normalized.takeIf { digitFragment }?.toIntOrNull(),
            )
        }
        if (candidates.size < 2) return elements

        val positioned = candidates.filter { candidate ->
            candidate.element.bounds.right > candidate.element.bounds.left &&
                candidate.element.bounds.bottom > candidate.element.bounds.top
        }
        val unpositioned = candidates - positioned.toSet()
        val rows = mutableListOf<MutableList<CredentialCandidate>>()
        positioned.sortedBy { it.element.bounds.centerY }.forEach { candidate ->
            val row = rows.firstOrNull { existing ->
                abs(existing.map { it.element.bounds.centerY }.average() -
                    candidate.element.bounds.centerY) <= 120.0
            }
            if (row == null) rows += mutableListOf(candidate) else row += candidate
        }

        val sensitivePaths = mutableSetOf<String>()
        unpositioned
            .groupBy { candidate ->
                candidate.element.path.substringBeforeLast('.', missingDelimiterValue = "")
            }
            .values
            .filter { group ->
                group.size in 4..8 && group.all { it.element.editable || it.element.clickable }
            }
            .forEach { group -> sensitivePaths += group.map { it.element.path } }
        rows.forEach { row ->
            val ordered = row.sortedBy { it.element.bounds.left }
            val horizontalGroups = mutableListOf<MutableList<CredentialCandidate>>()
            ordered.forEach { candidate ->
                val current = horizontalGroups.lastOrNull()
                val previous = current?.lastOrNull()
                val maxGap = previous?.let {
                    max(
                        160,
                        max(
                            it.element.bounds.bottom - it.element.bounds.top,
                            candidate.element.bounds.bottom - candidate.element.bounds.top,
                        ) * 2,
                    )
                } ?: 0
                if (previous == null || candidate.element.bounds.left -
                    previous.element.bounds.right <= maxGap
                ) {
                    if (current == null) horizontalGroups += mutableListOf(candidate)
                    else current += candidate
                } else {
                    horizontalGroups += mutableListOf(candidate)
                }
            }
            horizontalGroups.forEach { group ->
                val totalUnits = group.sumOf(CredentialCandidate::credentialUnits)
                val splitCvc = group.size == 3 &&
                    group.all(CredentialCandidate::singleDigitInteractive)
                if (!looksLikeCalendarDateRow(group, elements) &&
                    group.size >= 2 && (totalUnits in 4..19 || splitCvc)
                ) {
                    sensitivePaths += group.map { it.element.path }
                }
            }
        }
        if (sensitivePaths.isEmpty()) return elements
        return elements.map { element ->
            if (element.path in sensitivePaths) element.redacted() else element
        }
    }

    private fun looksLikeCalendarDateRow(
        group: List<CredentialCandidate>,
        elements: List<UiElement>,
    ): Boolean {
        if (group.size != 7) return false
        val numericValues = group.mapNotNull(CredentialCandidate::numericValue)
        if (numericValues.size != group.size ||
            group.any { it.element.editable } || numericValues.any { it !in 1..31 }
        ) {
            return false
        }
        val everyDayHasCalendarMetadata = group.all { candidate ->
            hasCalendarClassOrViewId(candidate.element)
        }
        val calendarAncestorContainsWholeRow = elements.any { context ->
            hasCalendarClassOrViewId(context) && group.all { candidate ->
                candidate.element.path == context.path ||
                    candidate.element.path.startsWith("${context.path}.")
            }
        }
        return everyDayHasCalendarMetadata || calendarAncestorContainsWholeRow
    }

    private fun hasCalendarClassOrViewId(element: UiElement): Boolean =
        listOfNotNull(element.viewId, element.className).any { value ->
            val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase()
            calendarContextTerms.any(normalized::contains)
        }

    private fun hasCredentialSlotMetadata(element: UiElement): Boolean =
        listOfNotNull(element.viewId, element.className).any { value ->
            val compact = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .lowercase()
                .replace(Regex("[^a-z0-9가-힣]"), "")
            listOf("otp", "pin", "passcode", "password", "cvc", "cvv", "digit", "code", "slot")
                .any(compact::contains)
        }

    private fun isRelatedToSensitiveElement(element: UiElement, sensitive: UiElement): Boolean {
        val ancestorRelated = element.path.startsWith("${sensitive.path}.") ||
            sensitive.path.startsWith("${element.path}.")
        if (ancestorRelated) return true

        val elementParent = element.path.substringBeforeLast('.', missingDelimiterValue = "")
        val sensitiveParent = sensitive.path.substringBeforeLast('.', missingDelimiterValue = "")
        val closeSibling = elementParent.isNotBlank() && elementParent != "0" &&
            elementParent == sensitiveParent
        val closeOnScreen = abs(element.bounds.centerY - sensitive.bounds.centerY) <= 120 &&
            abs(element.bounds.centerX - sensitive.bounds.centerX) <= 1_000
        return closeSibling || closeOnScreen
    }

    private fun UiElement.redacted(): UiElement = copy(
        text = null,
        contentDescription = null,
        stateDescription = null,
        sensitive = true,
    )

    internal fun isSensitiveText(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("[\\p{Cf}\\p{Cc}\\p{M}]"), "")
        return isCredentialSlotDescription(normalized) ||
            sensitiveTerms.any { term -> containsTerm(normalized, term) } ||
            maskedCredentialPattern.matches(normalized) ||
            numericRunPattern.findAll(normalized).any { match ->
                match.value.count(Char::isDigit) >= 6 &&
                    !isClearlyNonSensitiveDate(normalized, match)
            } || containsUnsafeShortNumericValue(normalized)
    }

    private fun isCredentialSlotDescription(value: String): Boolean {
        englishCredentialSlotPattern.find(value)?.let { match ->
            val index = match.groupValues[1].toIntOrNull() ?: return@let
            val total = match.groupValues[2].toIntOrNull() ?: return@let
            if (total in 3..8 && index in 1..total) return true
        }
        koreanCredentialSlotPattern.find(value)?.let { match ->
            val total = match.groupValues[1].toIntOrNull() ?: return@let
            val index = match.groupValues[2].toIntOrNull() ?: return@let
            if (total in 3..8 && index in 1..total) return true
        }
        return false
    }

    private fun containsTerm(value: String, term: String): Boolean {
        val compactTerm = compact(term)
        if (compactTerm.matches(Regex("[a-z0-9]+"))) {
            val separatedTerm = compactTerm.toCharArray()
                .joinToString("[\\s\\p{M}\\p{P}\\p{S}_]*") {
                    Regex.escape(it.toString())
                }
            return Regex(
                "(?<![a-z0-9])$separatedTerm(?![a-z0-9])",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(value)
        }
        return compact(value).contains(compactTerm)
    }

    private fun compact(value: String): String = value
        .replace(Regex("[\\p{Cf}\\p{Cc}\\p{M}\\s\\p{P}\\p{S}_]+"), "")

    private fun containsUnsafeShortNumericValue(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("[\\p{Cf}\\p{Cc}\\p{M}]"), "")
        return numericRunPattern.findAll(normalized).any { match ->
            match.value.count(Char::isDigit) in 4..5 &&
                !isClearlyNonSensitiveShortNumber(normalized, match) &&
                !isClearlyNonSensitiveDate(normalized, match)
        }
    }

    private fun isClearlyNonSensitiveShortNumber(
        normalized: String,
        match: MatchResult,
    ): Boolean {
        val matched = match.value.trim()
        if (Regex(CLOCK_TIME).matches(matched) && hasExplicitTimeContext(normalized, match)) {
            return true
        }

        val suffix = normalized.substring(match.range.last + 1).trimStart()
        return suffix.startsWith("년") || isExplicitWonAmount(normalized, match)
    }

    private fun isClearlyNonSensitiveDate(
        normalized: String,
        match: MatchResult,
    ): Boolean {
        if (isExplicitWonAmount(normalized, match)) return true
        if (strongClockPattern.findAll(normalized).any { clock ->
                dateExactlyCoversNumericRun(clock, match)
            }
        ) return true
        if (englishDatePattern.findAll(normalized).any { date ->
                dateExactlyCoversNumericRun(date, match)
            }
        ) {
            return true
        }
        return numericDatePatterns.any { pattern ->
            pattern.findAll(normalized).any { date ->
                dateExactlyCoversNumericRun(date, match)
            }
        }
    }

    private fun isExplicitWonAmount(normalized: String, match: MatchResult): Boolean {
        if (match.value.count(Char::isDigit) !in 4..12) return false
        val suffix = normalized.substring(match.range.last + 1).trimStart()
        if (!suffix.startsWith("원")) return false
        val afterWon = suffix.drop(1)
        if (afterWon.firstOrNull()?.isLetterOrDigit() != true) return true
        return listOf("짜리", "어치", "입니다").any { allowedSuffix ->
            afterWon.startsWith(allowedSuffix) &&
                afterWon.drop(allowedSuffix.length).firstOrNull()?.isLetterOrDigit() != true
        }
    }

    private fun hasExplicitTimeContext(normalized: String, match: MatchResult): Boolean {
        val contextStart = maxOf(0, match.range.first - 24)
        val contextEnd = minOf(normalized.length, match.range.last + 1 + 24)
        return explicitTimeContextPattern.containsMatchIn(
            normalized.substring(contextStart, contextEnd),
        )
    }

    private fun dateExactlyCoversNumericRun(
        date: MatchResult,
        numericRun: MatchResult,
    ): Boolean = numericRunPattern.findAll(date.value).any { dateRun ->
        date.range.first + dateRun.range.first == numericRun.range.first &&
            date.range.first + dateRun.range.last == numericRun.range.last &&
            dateRun.value.count(Char::isDigit) == numericRun.value.count(Char::isDigit)
    }
}
