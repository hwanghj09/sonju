package com.hwanghj09.sonju

import com.hwanghj09.sonju.agent.ScreenBounds
import com.hwanghj09.sonju.agent.ScreenExplainer
import com.hwanghj09.sonju.agent.UiElement
import com.hwanghj09.sonju.agent.UiSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenExplainerTest {
    @Test fun informationRequestsDoNotTurnIntoScreenHelpBecauseTheySayTellMe() {
        for (request in listOf("아산병원 예약기록 알려줘", "분당서울대병원 예약 내역 확인해줘", "내일 날씨 알려줘",
            "카카오톡에서 안 읽은 메시지 알려줘", "이번 주 일정 알려줘", "택배 언제 오는지 알려줘",
            "삼성전자 주가 알려줘", "지금 환율이 얼마야?", "서울역 가는 길 알려줘", "이 화면에서 내 예약 기록 알려줘",
            "조회 방법을 검색해서 알려줘", "사용법 검색해줘", "사용법 찾아줘",
            "아산병원 예약해 둔 내역 알려줘", "여기서 이번 주 일정 확인해서 알려줘",
            "카카오톡 열어서 새 메시지 알려줘", "버스 언제 도착해?", "예약 내역 좀 보여줄래?")) {
            assertEquals(request, ScreenExplainer.RequestKind.COMMAND, ScreenExplainer.classifyRequest(request))
        }
        for (request in listOf("현재 화면 내용 알려줘", "이 메시지 내용을 읽어줘", "이 화면에서 검색 어떻게 해?",
            "예약 조회 방법 알려줘", "검색 버튼이 어디야?")) {
            assertEquals(request, ScreenExplainer.RequestKind.QUESTION, ScreenExplainer.classifyRequest(request))
        }
    }

    @Test
    fun recognizesNaturalKoreanExplanationRequests() {
        assertTrue(ScreenExplainer.isExplanationRequest("이거 어떻게 사용하는 앱이야?"))
        assertTrue(ScreenExplainer.isExplanationRequest("현재 화면 설명해 줘"))
        assertTrue(ScreenExplainer.isExplanationRequest("현재 화면을 읽어 줘"))
        assertTrue(ScreenExplainer.isExplanationRequest("이 메시지 내용을 읽어주세요"))
        assertTrue(ScreenExplainer.isExplanationRequest("이거 카톡 프로필 사진 어떻게 바꿔?"))
        assertTrue(ScreenExplainer.isExplanationRequest("설정 방법을 알려 줘"))
        assertTrue(ScreenExplainer.isExplanationRequest("이 화면에서 검색 어떻게 해?"))
        assertTrue(ScreenExplainer.isExplanationRequest("검색 버튼이 어디야?"))
        assertFalse(ScreenExplainer.isExplanationRequest("가족 대화방 눌러 줘"))
        assertFalse(ScreenExplainer.isExplanationRequest("검색 버튼 눌러줄래?"))
        assertFalse(ScreenExplainer.isExplanationRequest("이 화면에서 검색해 줘"))
        assertFalse(ScreenExplainer.isExplanationRequest("서울역 가는 길 알려줘"))
        assertFalse(ScreenExplainer.isExplanationRequest("강남역까지 경로 알려줘"))
    }

    @Test
    fun explainsHowToSearchUsingTheCurrentScreen() {
        val search = element("검색", false).copy(
            path = "0.1",
            contentDescription = "검색",
            clickable = true,
            bounds = ScreenBounds(0, 0, 200, 100),
        )
        val content = element("최근 항목", false).copy(
            path = "0.2",
            bounds = ScreenBounds(0, 100, 200, 600),
        )
        val explanation = ScreenExplainer.explain(
            "이 화면에서 검색 어떻게 해?",
            "테스트 앱",
            UiSnapshot(
                packageName = "com.example.test",
                windowTitle = "테스트",
                epoch = 1,
                elements = listOf(search, content),
            ),
        )

        assertTrue(explanation.contains("위쪽"))
        assertTrue(explanation.contains("‘검색’"))
        assertTrue(explanation.contains("검색어를 입력"))
        assertFalse(
            ScreenExplainer.needsScreenshotFallback("이 화면에서 검색 어떻게 해?", UiSnapshot(
                packageName = "com.example.test",
                windowTitle = "테스트",
                epoch = 1,
                elements = listOf(search, content),
            )),
        )
    }

    @Test
    fun requestsScreenshotFallbackWhenSearchIsMissingFromAccessibility() {
        val snapshot = UiSnapshot(
            packageName = "com.example.canvas",
            windowTitle = "Canvas 화면",
            epoch = 1,
            elements = listOf(element("화면 내용", false)),
        )

        assertTrue(
            ScreenExplainer.needsScreenshotFallback("이 화면에서 검색 어떻게 해?", snapshot),
        )
    }

    @Test
    fun omitsSensitiveLabelsFromExplanation() {
        val snapshot = UiSnapshot(
            packageName = "com.kakao.talk",
            windowTitle = "카카오톡",
            epoch = 1,
            elements = listOf(
                element("친구", false),
                element(null, true),
            ),
        )
        val explanation = ScreenExplainer.explain("현재 화면 설명해 줘", "카카오톡", snapshot)

        assertTrue(explanation.contains("친구"))
        assertFalse(explanation.contains("123456"))
    }

    @Test
    fun recognizesChromeAndSamsungInternetAddressBarsWithoutQueryData() {
        val chrome = UiSnapshot(
            packageName = "com.android.chrome",
            windowTitle = "Chrome",
            epoch = 1,
            elements = listOf(
                element(
                    text = "https://www.example.com/private?id=1234",
                    sensitive = false,
                    viewId = "com.android.chrome:id/url_bar",
                ),
            ),
        )
        val samsung = chrome.copy(
            packageName = "com.sec.android.app.sbrowser",
            elements = listOf(
                element(
                    text = "news.example.co.kr/article/42",
                    sensitive = false,
                    viewId = "com.sec.android.app.sbrowser:id/location_bar_edit_text",
                ),
            ),
        )

        assertEquals("https://www.example.com", ScreenExplainer.detectBrowserUrl(chrome))
        assertEquals("https://news.example.co.kr", ScreenExplainer.detectBrowserUrl(samsung))
        assertFalse(
            ScreenExplainer.explain("현재 화면 설명해 줘", "Chrome", chrome).contains("private"),
        )
    }

    @Test
    fun givesStepByStepKakaoProfilePhotoInstructions() {
        val explanation = ScreenExplainer.explain(
            "이거 카톡 프로필 사진 어떻게 바꿔?",
            "카카오톡",
            UiSnapshot.empty(),
        )

        assertTrue(explanation.contains("1단계"))
        assertTrue(explanation.contains("프로필 편집"))
        assertTrue(explanation.contains("완료"))
    }

    @Test
    fun recognizesEmbeddedWebViewAsBrowserSurface() {
        val snapshot = UiSnapshot(
            packageName = "com.example.reader",
            windowTitle = "기사",
            epoch = 1,
            elements = listOf(
                element("기사 본문", false).copy(className = "android.webkit.WebView"),
            ),
        )

        assertTrue(ScreenExplainer.isBrowserSurface(snapshot))
    }

    private fun element(
        text: String?,
        sensitive: Boolean,
        viewId: String? = null,
    ) = UiElement(
        path = if (sensitive) "0.2" else "0.1",
        viewId = viewId,
        className = "android.widget.TextView",
        text = text ?: "123456",
        contentDescription = null,
        bounds = ScreenBounds(0, 0, 100, 100),
        clickable = false,
        editable = false,
        scrollable = false,
        enabled = true,
        visible = true,
        sensitive = sensitive,
    )
}
