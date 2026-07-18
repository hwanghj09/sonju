package com.hwanghj09.sonju.agent

import java.text.Normalizer
import java.net.URI

object ScreenExplainer {
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

    fun isExplanationRequest(command: String): Boolean {
        val normalized = Normalizer.normalize(command, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("\\s+"), "")
        return normalized.contains("어떻게사용") ||
            normalized.contains("어떻게바꿔") ||
            normalized.contains("어떻게변경") ||
            normalized.contains("어떻게설정") ||
            normalized.contains("방법알려") ||
            normalized.contains("방법을알려") ||
            normalized.contains("단계별로설명") ||
            normalized.contains("단계별설명") ||
            normalized.contains("사용법") ||
            normalized.contains("화면설명") ||
            normalized.contains("뭐하는앱") ||
            normalized.contains("무슨앱")
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
}
