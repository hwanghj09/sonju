package com.hwanghj09.sonju.task

import com.hwanghj09.sonju.perception.ScreenState
import java.text.Normalizer
import java.util.LinkedHashMap

enum class CompletionLevel {
    INFORMATION_ONLY,
    NAVIGATE_TO_TARGET,
    BEFORE_IRREVERSIBLE_ACTION,
    EXECUTE_IRREVERSIBLE_ACTION,
}

enum class TaskRisk { LOW, MEDIUM, HIGH, CRITICAL }

enum class ConstraintOperator { EQUALS, MATCHES, LESS_THAN, AT_MOST, NOT }

data class Constraint(
    val field: String,
    val operator: ConstraintOperator,
    val value: String,
)

enum class ParameterSource {
    USER_EXPLICIT,
    USER_CONTEXT,
    DERIVED_DATE,
    SCREEN_STATE,
    MODEL_INFERRED,
}

data class TaskParameter(
    val name: String,
    val value: String?,
    val required: Boolean = true,
    val source: ParameterSource = ParameterSource.USER_EXPLICIT,
    val confidence: Double = 1.0,
)

data class UserIntent(
    val rawText: String,
    val goal: String,
    val targetApp: String?,
    val entities: Map<String, String>,
    val constraints: List<Constraint>,
    val requestedCompletionLevel: CompletionLevel,
    val risk: TaskRisk,
)

data class CanonicalTask(
    val appId: String?,
    val taskType: String,
    val parameters: Map<String, TaskParameter>,
    val constraints: List<Constraint>,
    val risk: TaskRisk,
    val completionLevel: CompletionLevel = CompletionLevel.NAVIGATE_TO_TARGET,
) {
    val key: String
        get() = listOf(appId ?: "any", taskType, parameters.keys.sorted().joinToString(","))
            .joinToString(":")
}

interface TaskParser {
    fun parse(text: String): UserIntent
}

interface TaskCanonicalizer {
    fun canonicalize(intent: UserIntent, screen: ScreenState): CanonicalTask
}

/** Local parser for repeatable task shapes. Unknown requests remain generic for the model slow path. */
object DeterministicTaskParser : TaskParser {
    override fun parse(text: String): UserIntent {
        val raw = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC).take(MAX_COMMAND_LENGTH)
        val normalized = normalize(raw)
        val nestedOrder = NESTED_POPULAR_ORDER.matchEntire(raw)
        val messageRequest = MESSAGE_REQUEST.matchEntire(raw)
        val routeDestination = ROUTE_DESTINATION.find(raw)
        val targetApp = if (nestedOrder != null || routeDestination != null) {
            null
        } else {
            (
                APP_IN_PATTERN.find(raw) ?:
                    APP_WORKFLOW_PATTERN.find(raw) ?:
                    KNOWN_APP_PREFIX.find(raw) ?:
                    KNOWN_APP_MENTION.find(raw)
                )
                ?.groupValues?.getOrNull(1)
                ?.trim()?.takeIf(String::isNotBlank)
        }
        val entities = buildMap {
            nestedOrder?.let { match ->
                val restaurant = cleanFoodNoun(match.groupValues[1])
                val menu = cleanFoodNoun(match.groupValues[2])
                if (restaurant.isNotBlank()) {
                    put("query", restaurant)
                    put("restaurant_query", restaurant)
                }
                if (menu.isNotBlank()) put("menu_query", menu)
            }
            messageRequest?.let { match ->
                match.groupValues[1].trim().takeIf(String::isNotBlank)
                    ?.let { put("recipient", it) }
                match.groupValues[2].trim().takeIf(String::isNotBlank)
                    ?.let { put("message", it) }
            }
            if (normalized.contains("프로필")) {
                put(
                    "target_state",
                    if (PROFILE_EDIT_TERMS.any(normalized::contains)) "프로필 편집" else "프로필",
                )
            }
            (DESTINATION.find(raw)?.groupValues?.getOrNull(1)
                ?: routeDestination?.groupValues?.getOrNull(1))
                ?.trim()?.takeIf(String::isNotBlank)
                ?.let { put("destination", stripAppPrefix(it)) }
            if ("query" !in this && "message" !in this) {
                QUOTED.find(raw)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
                    ?.let { put("query", it) }
            }
            if ("query" !in this && "message" !in this) {
                orderQuery(raw)?.let { put("query", it) }
            }
            if ("query" !in this && "message" !in this) {
                SEARCH_QUERY.find(raw)?.groupValues?.getOrNull(1)?.trim()
                    ?.let(::cleanSearchQuery)?.takeIf { it.length in 1..120 }
                    ?.let { put("query", it) }
            }
        }
        val constraints = buildList {
            if (nestedOrder != null) {
                add(Constraint("restaurant_sort", ConstraintOperator.MATCHES, "popular"))
                add(Constraint("menu_sort", ConstraintOperator.MATCHES, "popular"))
            } else if (POPULARITY_TERMS.any(normalized::contains)) {
                add(Constraint("sort", ConstraintOperator.MATCHES, "popular"))
            }
            if (RATING_TERMS.any(normalized::contains)) {
                add(Constraint("sort", ConstraintOperator.MATCHES, "rating"))
            }
        }
        val risk = risk(normalized)
        return UserIntent(
            rawText = raw,
            goal = raw,
            targetApp = targetApp,
            entities = entities,
            constraints = constraints,
            requestedCompletionLevel = completionLevel(normalized, risk),
            risk = risk,
        )
    }

    private fun risk(normalized: String): TaskRisk = when {
        CRITICAL_TERMS.any(normalized::contains) -> TaskRisk.CRITICAL
        MESSAGE_REQUEST.matches(normalized) -> TaskRisk.HIGH
        ORDER_ENDING.containsMatchIn(normalized) -> TaskRisk.HIGH
        HIGH_TERMS.any(normalized::contains) -> TaskRisk.HIGH
        MEDIUM_TERMS.any(normalized::contains) -> TaskRisk.MEDIUM
        else -> TaskRisk.LOW
    }

    private fun completionLevel(normalized: String, risk: TaskRisk): CompletionLevel = when {
        ORDER_ENDING.containsMatchIn(normalized) || MESSAGE_REQUEST.matches(normalized) ->
            CompletionLevel.BEFORE_IRREVERSIBLE_ACTION
        INFORMATION_TERMS.any(normalized::contains) -> CompletionLevel.INFORMATION_ONLY
        risk >= TaskRisk.HIGH && EXECUTE_TERMS.any(normalized::contains) ->
            CompletionLevel.EXECUTE_IRREVERSIBLE_ACTION
        risk >= TaskRisk.HIGH -> CompletionLevel.BEFORE_IRREVERSIBLE_ACTION
        else -> CompletionLevel.NAVIGATE_TO_TARGET
    }

    private fun stripAppPrefix(value: String): String = value
        .replace(APP_PREFIX, "")
        .trim(' ', ',', '.', '을', '를')

    private fun cleanSearchQuery(value: String): String {
        var query = stripAppPrefix(value)
        var qualifierRemoved = false
        SEARCH_QUALIFIERS.forEach { qualifier ->
            if (query.contains(qualifier, ignoreCase = true)) {
                query = query.replace(qualifier, "", ignoreCase = true)
                qualifierRemoved = true
            }
        }
        query = query.replace(Regex("\\s+"), " ").trim()
        if (qualifierRemoved) query = query.replace(Regex("\\s+집$"), "").trim()
        return query
    }

    private fun orderQuery(raw: String): String? {
        if (!ORDER_ENDING.containsMatchIn(raw)) return null
        val withoutOrderVerb = raw.replace(ORDER_ENDING, "").trim()
        return stripAppPrefix(withoutOrderVerb).takeIf { it.length in 1..120 }
    }

    private fun cleanFoodNoun(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()
        .replace(Regex("\\s*(?:집|가게|식당)$"), "")
        .trim()

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("\\s+"), " ")

    private val APP_IN_PATTERN = Regex("^(.{1,40}?)(?:에서|으로|로)\\s+")
    private val APP_WORKFLOW_PATTERN = Regex("^(.{1,40}?)(?:\\s+들어가서|\\s+열어서)\\s+")
    private val KNOWN_APP_PREFIX = Regex(
        "^(카카오톡|카톡|배달의민족|배민|삼성 노트|삼성노트|노트|유튜브|크롬|네이버)(?=\\s|을|를|에서|으로|로)",
        RegexOption.IGNORE_CASE,
    )
    private val KNOWN_APP_MENTION = Regex(
        "(?:^|\\s)(카카오톡|카톡|배달의민족|배민|삼성 노트|삼성노트|노트|유튜브|크롬|네이버)" +
            "(?=\\s|을|를|에서|으로|로|프로필|$)",
        RegexOption.IGNORE_CASE,
    )
    private val APP_PREFIX = Regex("^.{1,40}?(?:(?:에서|으로|로)\\s+|(?:들어가서|열어서)\\s+)")
    private val QUOTED = Regex("[\\\"“”']([^\\\"“”']{1,120})[\\\"“”']")
    private val DESTINATION = Regex("(.{1,120}?)까지(?:\\s|$)")
    private val ROUTE_DESTINATION = Regex(
        "^(.{1,120}?)(?:으로|로)?\\s*가는\\s*길(?:을)?\\s*(?:알려|찾아|보여|안내)",
        RegexOption.IGNORE_CASE,
    )
    private val SEARCH_QUERY = Regex("(.{1,120}?)\\s*(?:검색|찾아|찾기|재생)(?:해|해줘|해주세요|줘|주세요)?[.!?]?$", RegexOption.IGNORE_CASE)
    private val ORDER_ENDING = Regex(
        "\\s*(?:시켜\\s*(?:줘|주세요)|주문(?:해)?\\s*(?:줘|주세요)|주문해|주문)[.!?]?$",
        RegexOption.IGNORE_CASE,
    )
    private val NESTED_POPULAR_ORDER = Regex(
        "^(?:가장|제일)\\s*인기\\s*(?:있는)?\\s*(.{1,40}?)(?:집|가게|식당)에서\\s*" +
            "(?:가장|제일)\\s*인기\\s*(?:있는)?\\s*(.{1,40}?)\\s*" +
            "(?:시켜\\s*(?:줘|주세요)|주문(?:해)?\\s*(?:줘|주세요)|주문해|주문)[.!?]?$",
        RegexOption.IGNORE_CASE,
    )
    private val MESSAGE_REQUEST = Regex(
        "^(.{1,40}?)(?:한테|에게)\\s+(.{1,500}?)(?:이라고|라고|다고)\\s*" +
            "(?:보내|전송)(?:\\s*(?:해)?\\s*(?:줘|주세요))?[.!?]?$",
        RegexOption.IGNORE_CASE,
    )
    private val INFORMATION_TERMS = setOf("알려", "설명", "보여", "찾아줘", "조회")
    private val PROFILE_EDIT_TERMS = setOf("바꾸", "변경", "수정", "편집")
    private val SEARCH_QUALIFIERS = setOf(
        "가장 인기 있는", "제일 인기 있는", "인기 많은", "평점 좋은", "평점이 좋은",
    )
    private val POPULARITY_TERMS = setOf("가장 인기", "제일 인기", "인기 많은", "인기순")
    private val RATING_TERMS = setOf("평점 좋은", "평점이 좋은", "평점순")
    private val EXECUTE_TERMS = setOf(
        "결제해", "송금해", "주문해", "시켜", "호출해", "예약해", "전송해", "삭제해",
    )
    private val CRITICAL_TERMS = setOf("송금", "이체", "결제", "비밀번호 변경", "계정 탈퇴", "신원 인증")
    private val HIGH_TERMS = setOf("주문 확정", "주문해", "호출해", "예약 확정", "예약해", "전송해", "삭제")
    private val MEDIUM_TERMS = setOf("장바구니", "담아", "입력", "선택")
    private const val MAX_COMMAND_LENGTH = 1_000
}

object DeterministicTaskCanonicalizer : TaskCanonicalizer {
    override fun canonicalize(intent: UserIntent, screen: ScreenState): CanonicalTask {
        val normalized = normalize(intent.rawText)
        val taskType = when {
            intent.entities.containsKey("recipient") && intent.entities.containsKey("message") ->
                "send_message"
            intent.entities["target_state"] == "프로필 편집" -> "edit_profile"
            intent.entities.containsKey("destination") && DIRECTIONS_TERMS.any(normalized::contains) ->
                "directions"
            intent.entities.containsKey("destination") && TAXI_TERMS.any(normalized::contains) -> "call_taxi"
            FOOD_ORDER_TERMS.any(normalized::contains) && intent.entities.containsKey("query") -> "order_food"
            SEARCH_TERMS.any(normalized::contains) -> "search"
            OPEN_TERMS.any(normalized::contains) -> "open_app"
            SCROLL_TERMS.any(normalized::contains) -> "scroll"
            CLICK_TERMS.any(normalized::contains) -> "select"
            else -> "navigate"
        }
        val parameters = intent.entities.mapValues { (name, value) ->
            TaskParameter(name = name, value = value)
        }
        return CanonicalTask(
            appId = canonicalApp(intent.targetApp) ?: screen.packageName.takeUnless {
                it == "unknown" || it == SONJU_PACKAGE
            },
            taskType = taskType,
            parameters = parameters,
            constraints = intent.constraints,
            risk = if (taskType in setOf("call_taxi", "order_food") && intent.risk < TaskRisk.HIGH) {
                TaskRisk.HIGH
            } else {
                intent.risk
            },
            completionLevel = intent.requestedCompletionLevel,
        )
    }

    private fun canonicalApp(value: String?): String? {
        val compact = value?.let(::normalize)?.replace(Regex("[^\\p{L}\\p{Nd}]"), "")
            ?.takeIf(String::isNotBlank) ?: return null
        return APP_ALIASES[compact] ?: compact
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase().replace(Regex("\\s+"), " ").trim()

    private val APP_ALIASES = mapOf(
        "카카오t" to "kakao_t",
        "카카오택시" to "kakao_t",
        "카카오톡" to "kakaotalk",
        "카톡" to "kakaotalk",
        "배민" to "baemin",
        "배달의민족" to "baemin",
        "삼성노트" to "notes",
        "노트" to "notes",
        "유튜브" to "youtube",
        "크롬" to "chrome",
    )
    private val TAXI_TERMS = setOf("택시", "카카오t", "카카오택시")
    private val DIRECTIONS_TERMS = setOf("가는 길", "길찾기", "경로", "안내")
    private val FOOD_ORDER_TERMS = setOf("시켜", "주문")
    private val SEARCH_TERMS = setOf("검색", "찾아", "찾기", "재생")
    private val OPEN_TERMS = setOf("열어", "열기", "실행", "켜줘", "들어가")
    private val SCROLL_TERMS = setOf("스크롤", "내려", "올려", "넘겨")
    private val CLICK_TERMS = setOf("눌러", "선택", "클릭")
    private const val SONJU_PACKAGE = "com.hwanghj09.sonju"
}

/** Small bounded cache; it avoids re-canonicalizing identical requests without creating storage debt. */
class TaskCanonicalizationCache(private val maxEntries: Int = 64) {
    private val entries = object : LinkedHashMap<String, CanonicalTask>(maxEntries, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CanonicalTask>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun getOrPut(text: String, producer: () -> CanonicalTask): CanonicalTask =
        entries[text] ?: producer().also { entries[text] = it }
}
