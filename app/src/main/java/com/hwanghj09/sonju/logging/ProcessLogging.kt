package com.hwanghj09.sonju.logging

import android.content.Context
import androidx.core.content.edit
import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.PlanSource
import com.hwanghj09.sonju.execution.ExecutionFailureReason
import com.hwanghj09.sonju.execution.ExecutionMethod
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID

enum class ExecutionStatus { RUNNING, SUCCEEDED, FAILED, BLOCKED, CANCELLED }
enum class FallbackType { NONE, REFRESH, REGROUND, REPLAN, VISUAL, USER }

data class TraceStep(
    val sessionId: String,
    val requestHash: String,
    val canonicalTaskKey: String,
    val index: Int,
    val screenFingerprintBefore: String,
    val screenTypeBefore: String?,
    val plannerSource: PlanSource,
    val skillId: String?,
    val skillVersion: Int?,
    val actionType: ActionType,
    val semanticSelectorRedacted: String?,
    val groundingConfidence: Double?,
    val verificationEvidence: List<String>,
    val executionMethod: ExecutionMethod?,
    val failureReason: ExecutionFailureReason?,
    val screenFingerprintAfter: String?,
    val postconditionSatisfied: Boolean?,
    val fallbackUsed: FallbackType,
    val latencyMs: Long,
    val status: ExecutionStatus,
    val createdAt: Long = System.currentTimeMillis(),
)

interface ProcessLogRepository {
    fun append(step: TraceStep)
    fun exportRedacted(): List<String>
}

object RedactionPolicy {
    fun redact(value: String?): String? {
        if (value.isNullOrBlank()) return value
        var output = Normalizer.normalize(value, Normalizer.Form.NFKC)
        output = output.replace(EMAIL, "<REDACTED_EMAIL>")
            .replace(PHONE, "<REDACTED_PHONE>")
            .replace(LONG_NUMBER, "<REDACTED_NUMBER>")
        if (SENSITIVE_TERMS.any { output.contains(it, ignoreCase = true) }) {
            return "<REDACTED_SENSITIVE>"
        }
        return output.take(MAX_FIELD_LENGTH)
    }

    fun requestHash(request: String): String = sha256(
        Normalizer.normalize(request, Normalizer.Form.NFKC).lowercase(),
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private val EMAIL = Regex("[\\p{L}\\p{Nd}._%+-]+@[\\p{L}\\p{Nd}.-]+")
    private val PHONE = Regex("(?<!\\d)(?:\\+?\\d[\\d .-]{7,}\\d)(?!\\d)")
    private val LONG_NUMBER = Regex("(?<!\\d)\\d{4,}(?!\\d)")
    private val SENSITIVE_TERMS = setOf(
        "비밀번호", "비번", "password", "passcode", "pin", "otp", "인증번호",
        "보안코드", "카드번호", "cvc", "cvv", "주민등록", "계좌번호",
    )
    private const val MAX_FIELD_LENGTH = 240
}

class LocalProcessLogRepository(context: Context) : ProcessLogRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun append(step: TraceStep) {
        val key = "$KEY_PREFIX${step.createdAt}_${UUID.randomUUID()}"
        preferences.edit { putString(key, encode(step)) }
        trim()
    }

    override fun exportRedacted(): List<String> = preferences.all.entries.asSequence()
        .filter { it.key.startsWith(KEY_PREFIX) }
        .sortedBy { it.key }
        .mapNotNull { it.value as? String }
        .toList()

    private fun trim() {
        val keys = preferences.all.keys.filter { it.startsWith(KEY_PREFIX) }.sortedDescending()
        if (keys.size <= MAX_STEPS) return
        preferences.edit { keys.drop(MAX_STEPS).forEach(::remove) }
    }

    private fun encode(step: TraceStep): String = JSONObject()
        .put("session_id", step.sessionId)
        .put("request_hash", step.requestHash)
        .put("canonical_task", step.canonicalTaskKey)
        .put("index", step.index)
        .put("before_fingerprint", step.screenFingerprintBefore)
        .put("before_screen", step.screenTypeBefore ?: JSONObject.NULL)
        .put("planner_source", step.plannerSource.name)
        .put("skill_id", step.skillId ?: JSONObject.NULL)
        .put("skill_version", step.skillVersion ?: JSONObject.NULL)
        .put("action_type", step.actionType.name)
        .put("selector", RedactionPolicy.redact(step.semanticSelectorRedacted) ?: JSONObject.NULL)
        .put("grounding_confidence", step.groundingConfidence ?: JSONObject.NULL)
        .put("verification", step.verificationEvidence.joinToString(";") { RedactionPolicy.redact(it).orEmpty() })
        .put("method", step.executionMethod?.name ?: JSONObject.NULL)
        .put("failure", step.failureReason?.name ?: JSONObject.NULL)
        .put("after_fingerprint", step.screenFingerprintAfter ?: JSONObject.NULL)
        .put("postcondition", step.postconditionSatisfied ?: JSONObject.NULL)
        .put("fallback", step.fallbackUsed.name)
        .put("latency_ms", step.latencyMs)
        .put("status", step.status.name)
        .put("created_at", step.createdAt)
        .toString()

    companion object {
        private const val PREFERENCES = "sonju_process_log_v1"
        private const val KEY_PREFIX = "step_"
        private const val MAX_STEPS = 100
    }
}

data class AgentMetrics(
    val taskAttempts: Long = 0,
    val taskSuccesses: Long = 0,
    val stepAttempts: Long = 0,
    val stepSuccesses: Long = 0,
    val blockedUnsafeActions: Long = 0,
    val visualFallbacks: Long = 0,
    val skillReuses: Long = 0,
    val loops: Long = 0,
)

