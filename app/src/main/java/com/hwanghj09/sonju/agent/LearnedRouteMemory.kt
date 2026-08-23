package com.hwanghj09.sonju.agent

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer

data class LearnedRouteStep(
    val type: ActionType,
    val target: String?,
    val xRatio: Double?,
    val yRatio: Double?,
)

data class LearnedRoute(
    val key: String,
    val goalPattern: String,
    val startPackage: String,
    val targetApp: String,
    val startFingerprint: String,
    val steps: List<LearnedRouteStep>,
    val cost: Double,
    val savedAtMillis: Long,
)

/**
 * Local route memory. It retains only tool types, stable selectors and normalized coordinates.
 * Text values, screenshots, raw commands and screen text are deliberately never persisted.
 */
class LearnedRouteMemory(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun recallHint(
        goal: String,
        snapshot: UiSnapshot,
        targetApp: String? = null,
    ): String? {
        val now = System.currentTimeMillis()
        val goalPattern = RouteLearningPolicy.normalizedGoalPattern(goal)
        val candidates = preferences.all.values.mapNotNull { raw ->
            (raw as? String)?.let(::decode)
        }.filter { route ->
            now - route.savedAtMillis in 0..MAX_AGE_MILLIS &&
                route.steps.isNotEmpty()
        }
        val best = candidates.maxByOrNull { route ->
            RouteLearningPolicy.matchScore(
                goalPattern = goalPattern,
                packageName = snapshot.packageName,
                fingerprint = snapshot.screenFingerprint(),
                targetApp = targetApp,
                route = route,
            )
        } ?: return null
        val score = RouteLearningPolicy.matchScore(
            goalPattern,
            snapshot.packageName,
            snapshot.screenFingerprint(),
            targetApp,
            best,
        )
        if (score < MIN_RECALL_SCORE) return null
        return buildString {
            appendLine("예상 비용 ${"%.1f".format(best.cost)}, ${best.steps.size}개 도구 호출")
            best.steps.forEachIndexed { index, step ->
                append(index + 1).append(". ").append(step.type.name)
                step.target?.takeIf(String::isNotBlank)?.let { append(" target=").append(it) }
                if (step.type == ActionType.CLICK_COORDINATE) {
                    append(" x=").append(step.xRatio ?: "?")
                    append(" y=").append(step.yRatio ?: "?")
                }
                if (step.type == ActionType.SET_TEXT) append(" value=<현재 요청에서 가져오기>")
                appendLine()
            }
        }.trim()
    }

    fun remember(session: AutonomySession, completedPlan: AgentPlan) {
        val steps = RouteLearningPolicy.shortestReusableSteps(session.history)
        if (steps.isEmpty()) return
        val route = LearnedRoute(
            key = RouteLearningPolicy.routeKey(
                session.finalGoal,
                session.initialSnapshot.packageName,
                session.initialSnapshot.screenFingerprint(),
            ),
            goalPattern = RouteLearningPolicy.normalizedGoalPattern(session.finalGoal),
            startPackage = session.initialSnapshot.packageName.take(160),
            targetApp = completedPlan.targetApp.take(160),
            startFingerprint = session.initialSnapshot.screenFingerprint(),
            steps = steps,
            cost = RouteLearningPolicy.routeCost(steps),
            savedAtMillis = System.currentTimeMillis(),
        )
        val existing = preferences.getString(route.key, null)?.let(::decode)
        if (existing != null && !RouteLearningPolicy.shouldReplace(existing, route)) return
        preferences.edit { putString(route.key, encode(route)) }
        trimOldEntries()
    }

    private fun trimOldEntries() {
        val routes = preferences.all.mapNotNull { (key, raw) ->
            (raw as? String)?.let(::decode)?.let { key to it }
        }.sortedWith(compareByDescending<Pair<String, LearnedRoute>> { it.second.savedAtMillis }
            .thenBy { it.second.cost })
        if (routes.size <= MAX_ENTRIES) return
        preferences.edit {
            routes.drop(MAX_ENTRIES).forEach { (key, _) -> remove(key) }
        }
    }

    private fun encode(route: LearnedRoute): String = JSONObject()
        .put("version", 2)
        .put("goal_pattern", route.goalPattern)
        .put("start_package", route.startPackage)
        .put("target_app", route.targetApp)
        .put("start_fingerprint", route.startFingerprint)
        .put("cost", route.cost)
        .put("saved_at", route.savedAtMillis)
        .put(
            "steps",
            JSONArray().apply {
                route.steps.forEach { step ->
                    put(
                        JSONObject()
                            .put("type", step.type.name)
                            .put("target", step.target ?: JSONObject.NULL)
                            .put("x_ratio", step.xRatio ?: JSONObject.NULL)
                            .put("y_ratio", step.yRatio ?: JSONObject.NULL),
                    )
                }
            },
        )
        .toString()

    private fun decode(raw: String): LearnedRoute? = runCatching {
        val json = JSONObject(raw)
        if (json.optInt("version") != 2) return null
        val stepsJson = json.getJSONArray("steps")
        val steps = buildList {
            for (index in 0 until stepsJson.length()) {
                val item = stepsJson.getJSONObject(index)
                val type = ActionType.valueOf(item.getString("type"))
                add(
                    LearnedRouteStep(
                        type = type,
                        target = item.optNullableString("target")?.take(160),
                        xRatio = item.optNullableDouble("x_ratio"),
                        yRatio = item.optNullableDouble("y_ratio"),
                    ),
                )
            }
        }
        val startPackage = json.getString("start_package")
        val startFingerprint = json.getString("start_fingerprint")
        LearnedRoute(
            key = RouteLearningPolicy.routeKeyFromParts(
                json.getString("goal_pattern"),
                startPackage,
                startFingerprint,
            ),
            goalPattern = json.getString("goal_pattern"),
            startPackage = startPackage,
            targetApp = json.optString("target_app"),
            startFingerprint = startFingerprint,
            steps = steps,
            cost = json.getDouble("cost"),
            savedAtMillis = json.getLong("saved_at"),
        )
    }.getOrNull()

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

    private fun JSONObject.optNullableDouble(name: String): Double? =
        if (isNull(name)) null else optDouble(name).takeIf(Double::isFinite)

    companion object {
        private const val PREFERENCES = "sonju_learned_routes_v2"
        private const val MAX_ENTRIES = 80
        private const val MAX_AGE_MILLIS = 90L * 24 * 60 * 60 * 1_000
        private const val MIN_RECALL_SCORE = 0.58
    }
}

internal object RouteLearningPolicy {
    private val emailPattern = Regex("[\\p{L}\\p{Nd}._%+-]+@[\\p{L}\\p{Nd}.-]+")
    private val phonePattern = Regex("(?<!\\d)(?:\\+?\\d[\\d .-]{7,}\\d)(?!\\d)")
    private val longNumberPattern = Regex("(?<!\\d)\\d{4,}(?!\\d)")

    fun normalizedGoalPattern(goal: String): String = Normalizer.normalize(goal, Normalizer.Form.NFKC)
        .lowercase()
        .replace(emailPattern, " <email> ")
        .replace(phonePattern, " <phone> ")
        .replace(longNumberPattern, " <number> ")
        .replace(Regex("[^\\p{L}\\p{Nd}<>]+"), " ")
        .trim()
        .take(500)

    fun shortestReusableSteps(history: List<AutonomySession.Trace>): List<LearnedRouteStep> {
        val loopErased = mutableListOf<AutonomySession.Trace>()
        history.asSequence().filter(AutonomySession.Trace::succeeded).forEach { trace ->
            val returnedState = trace.afterFingerprint
            if (returnedState != null) {
                val previousStateIndex = loopErased.indexOfFirst { prior ->
                    prior.beforePackage == trace.afterPackage &&
                        prior.beforeFingerprint == returnedState
                }
                if (previousStateIndex >= 0) {
                    while (loopErased.size > previousStateIndex) loopErased.removeAt(loopErased.lastIndex)
                    return@forEach
                }
            }
            loopErased += trace
        }
        return loopErased.asSequence()
            .map(AutonomySession.Trace::action)
            .filterNot { it.type in setOf(ActionType.WAIT, ActionType.FINISH) }
            .mapNotNull(::sanitize)
            .fold(mutableListOf()) { output, step ->
                if (output.lastOrNull() != step) output += step
                output
            }
    }

    fun routeCost(steps: List<LearnedRouteStep>): Double = steps.sumOf { step ->
        when (step.type) {
            ActionType.OPEN_APP,
            ActionType.OPEN_WIFI_SETTINGS,
            ActionType.OPEN_SOUND_SETTINGS,
            ActionType.OPEN_ACCESSIBILITY_SETTINGS,
            ActionType.OPEN_DISPLAY_SETTINGS,
            ActionType.OPEN_DATE_SETTINGS,
            ActionType.OPEN_CAMERA,
            ActionType.OPEN_DIALER,
            ActionType.OPEN_MESSAGES,
            -> 0.8

            ActionType.CLICK -> 1.0
            ActionType.SET_TEXT -> 1.2
            ActionType.SCROLL_UP,
            ActionType.SCROLL_DOWN,
            ActionType.SCROLL_LEFT,
            ActionType.SCROLL_RIGHT,
            -> 1.4

            ActionType.CLICK_COORDINATE -> 1.8
            ActionType.BACK,
            ActionType.HOME,
            ActionType.NOTIFICATIONS,
            ActionType.QUICK_SETTINGS,
            -> 1.1

            ActionType.WAIT -> 0.4
            ActionType.FINISH -> 0.0
        }
    }

    fun shouldReplace(existing: LearnedRoute, candidate: LearnedRoute): Boolean =
        candidate.cost < existing.cost ||
            candidate.cost == existing.cost && candidate.steps.size < existing.steps.size

    fun matchScore(
        goalPattern: String,
        packageName: String,
        fingerprint: String,
        targetApp: String?,
        route: LearnedRoute,
    ): Double {
        val goalScore = tokenSimilarity(goalPattern, route.goalPattern)
        val packageScore = if (packageName == route.startPackage) 0.22 else 0.0
        val fingerprintScore = if (fingerprint == route.startFingerprint) 0.28 else 0.0
        val appScore = if (!targetApp.isNullOrBlank() &&
            normalize(targetApp) == normalize(route.targetApp)
        ) 0.10 else 0.0
        return goalScore * 0.65 + packageScore + fingerprintScore + appScore
    }

    fun routeKey(goal: String, packageName: String, fingerprint: String): String =
        routeKeyFromParts(normalizedGoalPattern(goal), packageName, fingerprint)

    fun routeKeyFromParts(goalPattern: String, packageName: String, fingerprint: String): String =
        "route_" + sha256("$goalPattern\n$packageName\n$fingerprint")

    private fun sanitize(action: AgentAction): LearnedRouteStep? {
        if (action.type in setOf(ActionType.WAIT, ActionType.FINISH)) return null
        val safeTarget = action.target?.let(::sanitizeSelector)?.takeIf(String::isNotBlank)
        val x = action.xRatio?.takeIf { it.isFinite() && it in 0.0..1.0 }
        val y = action.yRatio?.takeIf { it.isFinite() && it in 0.0..1.0 }
        return LearnedRouteStep(action.type, safeTarget, x, y)
    }

    private fun sanitizeSelector(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(emailPattern, "<email>")
        .replace(phonePattern, "<phone>")
        .replace(longNumberPattern, "<number>")
        .trim()
        .take(160)

    private fun tokenSimilarity(left: String, right: String): Double {
        if (left == right && left.isNotBlank()) return 1.0
        val leftTokens = left.split(' ').filter(String::isNotBlank).toSet()
        val rightTokens = right.split(' ').filter(String::isNotBlank).toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
        return leftTokens.intersect(rightTokens).size.toDouble() /
            leftTokens.union(rightTokens).size.toDouble()
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase().trim()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
