package com.hwanghj09.sonju.voice

import java.text.Normalizer

internal object WakeWordMatcher {
    private val variants = setOf("손주야", "손주아")
    private val spokenPattern = Regex("손[\\s\\p{P}]*주[\\s\\p{P}]*[야아]")

    fun matches(value: String): Boolean {
        val compact = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        return variants.any(compact::contains)
    }

    fun commandAfterWakeWord(value: String): String? {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        val match = spokenPattern.find(normalized) ?: return null
        return normalized.substring(match.range.last + 1)
            .trim { it.isWhitespace() || it in ",.!?·:;-_" }
            .takeIf(String::isNotBlank)
            ?.take(500)
    }
}
