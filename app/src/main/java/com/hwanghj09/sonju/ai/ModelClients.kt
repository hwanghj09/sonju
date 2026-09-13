package com.hwanghj09.sonju.ai

import com.hwanghj09.sonju.agent.AgentPlan
import com.hwanghj09.sonju.agent.UiSnapshot

interface ExploratoryPlanClient {
    val isConfigured: Boolean
    fun planAsync(
        command: String,
        snapshot: UiSnapshot,
        semanticMapJpegBase64: String?,
        userFeedbackGuidance: String? = null,
        autonomyContext: String? = null,
        excludedClickPaths: Set<String> = emptySet(),
        callback: (Result<AgentPlan>) -> Unit,
    )
    fun cancelPending()
}

interface VisualGroundingClient {
    fun analyzeScreenshotAsync(
        command: String,
        screenshotJpegBase64: String,
        question: Boolean,
        callback: (Result<VisualScreenResult>) -> Unit,
    )
}

interface ScreenExplanationClient {
    fun explainScreenAsync(
        command: String,
        snapshot: UiSnapshot,
        semanticMapJpegBase64: String,
        browserUrl: String? = null,
        callback: (Result<String>) -> Unit,
    )
}
