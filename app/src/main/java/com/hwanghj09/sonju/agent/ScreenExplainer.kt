package com.hwanghj09.sonju.agent

import java.text.Normalizer
import java.net.URI

object ScreenExplainer {
    enum class RequestKind { QUESTION, COMMAND }

    private val browserPackages = setOf(
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.naver.whale",
    )
    private val addressBarIds = listOf(
        "url_bar",
        "location_bar",
        "address_bar",
        "omnibox",
    )

    fun classifyRequest(command: String): RequestKind {
        val normalized = Normalizer.normalize(command, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("\\s+"), "")
        val questionSignals = listOf(
            "어떻게", "방법", "어디", "어느", "어떤", "왜", "뭐야", "뭔가", "무엇",
            "알려", "설명", "사용법", "할수있", "할수있어", "가능해", "되나요", "돼요",
            "인가요", "인가", "맞나요", "what", "how", "where", "why", "which",
        )
        if (questionSignals.any(normalized::contains)) return RequestKind.QUESTION

        val executionSignals = listOf(
            "눌러줘", "눌러주세요", "눌러줄래", "클릭해", "열어줘", "열어주세요",
            "켜줘", "꺼줘", "바꿔줘", "변경해줘", "설정해줘", "검색해줘", "찾아줘",
            "입력해줘", "보내줘", "실행해줘", "시작해줘", "이동해줘", "내려줘",
            "올려줘", "뒤로가", "홈으로가", "주문해줘", "tap", "click", "open",
        )
        if (executionSignals.any(normalized::contains)) return RequestKind.COMMAND
        if (listOf(
                "눌러", "열어", "켜", "꺼", "검색해", "찾아", "입력해", "보내",
                "실행해", "시작해", "내려", "올려", "주문해",
            ).any(normalized::endsWith)
        ) return RequestKind.COMMAND

        val questionEnding = normalized.endsWith("?") || listOf(
            "니", "나요", "가요", "까요", "거야", "돼", "야",
        ).any(normalized::endsWith)
        return if (questionEnding) RequestKind.QUESTION else RequestKind.COMMAND
    }

    fun isExplanationRequest(command: String): Boolean =
        classifyRequest(command) == RequestKind.QUESTION

    fun needsScreenshotFallback(command: String, snapshot: UiSnapshot): Boolean {
        if (!isExplanationRequest(command)) return false
        val compactCommand = compact(command)
        if ((compactCommand.contains("카톡") || compactCommand.contains("카카오톡")) &&
            compactCommand.contains("프로필") && compactCommand.contains("사진")
        ) return false
        if (compactCommand.contains("검색") || compactCommand.contains("search")) {
            return snapshot.elements.none { element ->
                if (!element.visible || element.sensitive) return@none false
                val context = compact(
                    listOfNotNull(
                        element.text,
                        element.contentDescription,
                        element.viewId,
                    ).joinToString(" "),
                )
                context.contains("검색") || context.contains("search") || context.contains("query")
            }
        }
        if (listOf("화면설명", "뭐하는앱", "무슨앱").any(compactCommand::contains)) {
            return !snapshot.hasSemanticSignal()
        }
        val mentionedVisibleElement = snapshot.elements.any { element ->
            element.visible && !element.sensitive && sequenceOf(
                element.text,
                element.contentDescription,
            ).mapNotNull { it?.takeIf(String::isNotBlank) }
                .map(::compact)
                .any { label -> label.length >= 2 && compactCommand.contains(label) }
        }
        if (mentionedVisibleElement) return false
        if (listOf("어떻게", "방법", "어디", "where", "how").any(compactCommand::contains)) {
            return true
        }
        return !snapshot.hasSemanticSignal()
    }

    fun isBrowserSurface(snapshot: UiSnapshot): Boolean =
        snapshot.packageName in browserPackages || snapshot.elements.any { element ->
            element.visible && element.className.contains("WebView", ignoreCase = true)
        }

    fun detectBrowserUrl(snapshot: UiSnapshot): String? {
        if (snapshot.packageName !in browserPackages) return null
        val candidates = snapshot.elements.asSequence()
            .filter { it.visible && !it.sensitive }
            .sortedByDescending { element ->
                addressBarIds.any { token -> element.viewId.orEmpty().lowercase().contains(token) }
            }
            .flatMap { sequenceOf(it.text, it.contentDescription) }
            .mapNotNull { it?.trim() }
            .filter(String::isNotBlank)
        for (candidate in candidates) {
            val compact = candidate.replace(" ", "")
            if ('@' in compact || '.' !in compact || compact.length > 500) continue
            val withScheme = if (compact.startsWith("http://") || compact.startsWith("https://")) {
                compact
            } else {
                "https://$compact"
            }
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: continue
            val host = uri.host?.lowercase()?.removeSuffix(".") ?: continue
            if (!host.matches(Regex("[a-z0-9.-]+\\.[a-z0-9-]{2,}"))) continue
            return "https://$host"
        }
        return null
    }

    fun explain(
        command: String,
        appLabel: String,
        snapshot: UiSnapshot,
        browserUrl: String? = detectBrowserUrl(snapshot),
    ): String {
        val normalizedCommand = Normalizer.normalize(command, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("\\s+"), "")
        if ((normalizedCommand.contains("카톡") || normalizedCommand.contains("카카오톡")) &&
            normalizedCommand.contains("프로필") && normalizedCommand.contains("사진")
        ) {
            return "카카오톡 프로필 사진은 다음 순서로 바꿀 수 있어요. " +
                "1단계, 카카오톡 아래쪽의 친구 탭을 누르세요. " +
                "2단계, 목록 위쪽에 있는 내 프로필을 누르세요. " +
                "3단계, 프로필 편집을 누른 뒤 프로필 사진을 누르세요. " +
                "4단계, 사진을 선택하고 완료나 확인을 누르세요. " +
                "화면 구성이 다르면 현재 보이는 버튼 이름을 말씀해 주세요."
        }
        taskHelp(command, snapshot)?.let { return it }
        val purpose = when (snapshot.packageName) {
            "com.kakao.talk" -> "대화방을 선택해 메시지를 주고받고 사진이나 파일을 확인하는 앱이에요."
            "com.sampleapp" -> "화면에 보이는 메뉴와 버튼을 선택해 사용하는 앱이에요."
            "com.android.settings" -> "휴대폰의 소리, 화면, 네트워크 같은 설정을 바꾸는 앱이에요."
            "kr.co.woowahan" -> "음식점과 메뉴를 찾아 배달이나 포장 주문을 준비하는 앱이에요."
            in browserPackages -> "인터넷 웹사이트를 찾아보고 이용하는 웹 브라우저예요."
            else -> "화면에 보이는 메뉴와 버튼을 선택해 사용하는 앱이에요."
        }
        val websiteSummary = browserUrl?.let { url ->
            "현재 ${url.removePrefix("https://")} 사이트를 보고 있어요. "
        }.orEmpty()
        val visibleLabels = snapshot.elements.asSequence()
            .filter { element ->
                element.visible && !element.sensitive &&
                    addressBarIds.none { token ->
                        element.viewId.orEmpty().lowercase().contains(token)
                    }
            }
            .flatMap { sequenceOf(it.text, it.contentDescription) }
            .mapNotNull { it?.trim()?.replace(Regex("\\s+"), " ") }
            .filter { it.length in 1..40 && it.any(Char::isLetter) }
            .distinct()
            .take(5)
            .toList()
        val visibleSummary = if (visibleLabels.isEmpty()) {
            "현재 화면의 메뉴 이름은 충분히 읽히지 않아요."
        } else {
            "현재 화면에는 ${visibleLabels.joinToString(", ")} 항목이 보여요."
        }
        return "현재 ${appLabel.ifBlank { "이" }} 앱이에요. $purpose $websiteSummary$visibleSummary " +
            "원하는 항목 이름과 함께 눌러 달라고 말씀해 주세요."
    }

    private fun taskHelp(command: String, snapshot: UiSnapshot): String? {
        val compactCommand = compact(command)
        val asksAboutSearch = compactCommand.contains("검색") || compactCommand.contains("search")
        val candidates = snapshot.elements.asSequence()
            .filter { it.visible && !it.sensitive }
            .mapNotNull { element ->
                val label = sequenceOf(element.text, element.contentDescription)
                    .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
                    .firstOrNull()
                    ?: element.viewId?.substringAfterLast('/')?.replace('_', ' ')
                    ?: return@mapNotNull null
                val compactLabel = compact(label)
                val matches = if (asksAboutSearch) {
                    compactLabel.contains("검색") || compactLabel.contains("search") ||
                        element.viewId.orEmpty().lowercase().let { id ->
                            id.contains("search") || id.contains("query")
                        }
                } else {
                    compactLabel.length >= 2 && compactCommand.contains(compactLabel)
                }
                if (matches) element to label else null
            }
            .sortedWith(compareBy({ it.first.bounds.top }, { it.first.bounds.left }))
            .toList()
        val (element, rawLabel) = candidates.firstOrNull() ?: if (asksAboutSearch) {
            return "현재 화면의 접근성 구조에서는 검색 버튼이나 검색창을 찾지 못했어요. " +
                "돋보기 모양, ‘검색’이라는 글자, 또는 화면 위쪽의 검색창을 찾아 누른 뒤 " +
                "검색어를 입력해 보세요. 보이는 버튼 이름을 말씀해 주시면 더 정확히 안내할게요."
        } else {
            return null
        }
        val label = rawLabel.replace(Regex("\\s+"), " ").take(40)
        val location = locationOf(element, snapshot)
        return when {
            element.editable ->
                "현재 화면 ${location}에 ‘$label’ 입력란이 있어요. 입력란을 한 번 누르고 " +
                    "찾을 내용을 입력한 다음, 키보드의 검색 또는 돋보기 버튼을 누르세요."

            asksAboutSearch ->
                "현재 화면 ${location}에 ‘$label’ 항목이 있어요. 그 항목을 누르면 검색 화면이나 " +
                    "검색 입력란이 열립니다. 검색어를 입력한 뒤 검색 버튼을 누르세요."

            element.clickable ->
                "현재 화면 ${location}에 ‘$label’ 항목이 있어요. 그 항목을 누른 뒤 나타나는 " +
                    "안내에 따라 진행하면 됩니다."

            else ->
                "현재 화면 ${location}에 ‘$label’ 항목이 보여요. 직접 눌리지 않으면 그 항목을 " +
                    "포함한 주변 행이나 버튼을 눌러 보세요."
        }
    }

    private fun locationOf(element: UiElement, snapshot: UiSnapshot): String {
        val screenBottom = snapshot.elements.asSequence()
            .filter { it.visible }
            .maxOfOrNull { it.bounds.bottom }
            ?.takeIf { it > 0 }
            ?: return "안"
        return when {
            element.bounds.centerY < screenBottom / 3 -> "위쪽"
            element.bounds.centerY > screenBottom * 2 / 3 -> "아래쪽"
            else -> "가운데"
        }
    }

    private fun compact(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]"), "")
}
