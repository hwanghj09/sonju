package com.hwanghj09.sonju.vision

import com.hwanghj09.sonju.perception.ScreenState

data class ScreenshotFrame(
    val jpegBase64: String,
    val width: Int,
    val height: Int,
)

data class VisualObservation(
    val targetDescription: String,
    val leftRatio: Double,
    val topRatio: Double,
    val rightRatio: Double,
    val bottomRatio: Double,
    val confidence: Double,
)

interface ScreenshotProvider {
    fun capture(callback: (Result<ScreenshotFrame>) -> Unit)
}

interface ScreenshotCropper {
    fun crop(frame: ScreenshotFrame, left: Int, top: Int, right: Int, bottom: Int): ScreenshotFrame
}

interface VlmClient {
    fun ground(
        semanticTarget: String,
        frame: ScreenshotFrame,
        nearbyAccessibilityContext: String,
        callback: (Result<VisualObservation>) -> Unit,
    )
}

class VisualFallbackPolicy(private val minimumQuality: Double = .55) {
    fun shouldUse(
        screen: ScreenState,
        groundingFailed: Boolean,
        containsSensitiveNode: Boolean,
    ): Boolean = !containsSensitiveNode &&
        (groundingFailed || screen.quality.score < minimumQuality)
}

