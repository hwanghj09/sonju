package com.hwanghj09.sonju.task

import java.text.Normalizer

/** Describes the requested outcome, not whether the sentence contains a question word. */
enum class RequestPurpose { SCREEN_EXPLANATION, HOW_TO, INFORMATION_LOOKUP, ACTION, NEEDS_INTERPRETATION }

data class RequestUnderstanding(val purpose: RequestPurpose) {
    val explainsCurrentContext: Boolean get() = purpose in setOf(RequestPurpose.SCREEN_EXPLANATION, RequestPurpose.HOW_TO)

    val plannerContext: String get() = when (purpose) {
        RequestPurpose.INFORMATION_LOOKUP -> "정보 조회 요청이다. 필요한 앱/사이트에서 실제 값을 확인하고 사용자에게 알려주는 것이 목표다. " +
            "현재 화면의 사용법이나 조회 방법만 설명해서 끝내지 않는다. 제목이나 조회 버튼만으로 완료하지 않는다."
        RequestPurpose.ACTION -> "사용자가 요청한 동작과 그 결과를 확인하는 것이 목표다. 사용법 설명으로 대체하지 않는다."
        RequestPurpose.NEEDS_INTERPRETATION -> "문장 전체의 대상, 원하는 결과와 문맥으로 의도를 해석한다. " +
            "알려줘/어디/언제/물음표만으로 화면 설명 요청이라고 가정하지 않는다. 대상을 추측해 민감한 행동을 하지 않는다."
        else -> "현재 화면 설명 또는 사용 방법 안내 요청이다. 대신 실행하라는 요청으로 바꾸지 않는다."
    }
}

object RequestInterpreter {
    // Mixed or unclear action clauses keep their original completion boundary.
    fun isReadOnlyLookup(command: String): Boolean {
        val subject = LOOKUP_REQUEST.matchEntire(normalize(command))?.groupValues?.get(1) ?: return false
        return understand(command).purpose == RequestPurpose.INFORMATION_LOOKUP &&
            !ACTION_CLAUSE.containsMatchIn(subject)
    }

    fun isLookupSurface(label: String): Boolean {
        val text = normalize(label)
        return LOOKUP_SURFACE.matches(text) && !ACTION_CLAUSE.containsMatchIn(text)
    }

    private fun normalize(value: String) =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase().replace(Regex("\\s+"), "")

    private val LOOKUP_REQUEST = Regex("(.+?)(?:알려|보여|읽어|조회해|확인해)(?:줘|주세요)[.!?]?")
    private val LOOKUP_SURFACE = Regex(".+?(?:내역|기록|현황|목록|상세)(?:조회|보기)?(?:버튼|메뉴)?")
    private val ACTION_CLAUSE = Regex("(?:하(?:고|여|면|시|셨|는|기|려)|해(?:서|주|줘|놓|둔|둬|버|보)|한(?:후|뒤|다음)|보내|지워|지우|바꿔|바꾸|실행|진행|확정|완료|제출|동의)")

    fun understand(command: String): RequestUnderstanding {
        val text = normalize(command)
        // A compound request such as '검색해서 알려줘' still requires the requested action.
        val explicitAction = ACTION.containsMatchIn(text) ||
            Regex("(?:취소해|중지해|정지해|멈춰)(?:줘|주세요)[.!?]?$").containsMatchIn(text)
        val delegatedLookup = Regex("(?:검색해|찾아|조회해|확인해)").containsMatchIn(text)
        val howTo = !delegatedLookup && (HOW_TO.containsMatchIn(text) ||
            (UI_CONTROL.containsMatchIn(text) && WHERE.containsMatchIn(text) && !explicitAction))
        val currentReference = CURRENT_REFERENCE.containsMatchIn(text)
        val readOrExplain = READ_OR_EXPLAIN.containsMatchIn(text)
        val purpose = when {
            explicitAction && !howTo -> if (readOrExplain) RequestPurpose.INFORMATION_LOOKUP else RequestPurpose.ACTION
            howTo -> RequestPurpose.HOW_TO
            currentReference && readOrExplain && !LOOKUP_OBJECT.containsMatchIn(text) -> RequestPurpose.SCREEN_EXPLANATION
            readOrExplain || INFORMATION_QUESTION.containsMatchIn(text) -> RequestPurpose.INFORMATION_LOOKUP
            else -> RequestPurpose.NEEDS_INTERPRETATION
        }
        return RequestUnderstanding(purpose)
    }

    private val ACTION = Regex("(?:검색해서|찾아서|열어서|들어가서|조회해서|확인해서|눌러|클릭해|열어줘|열어주세요|켜줘|꺼줘|바꿔줘|변경해줘|설정해줘|검색해줘|찾아줘|입력해줘|보내줘|실행해줘|시작해줘|이동해줘|내려줘|올려줘|주문해줘|홈으로가|뒤로가|\\b(?:tap|click|open)\\b)")
    private val HOW_TO = Regex("(?:사용법|쓰는법|하는법|설정방법|조회방법|예약방법|변경방법|방법(?:을|좀|이|은|알려|설명)|어떻게.*(?:사용|쓰|써|하|해|바꿔|바꾸|설정|켜|꺼|눌|열|예약))")
    private val CURRENT_REFERENCE = Regex("(?:(?:이|현재|지금)(?:화면|(?:웹)?페이지|문서)|(?:화면|(?:웹)?페이지|문서)(?:내용|설명)|여기|이거|이앱|이메시지|이글|이내용|이오류|이사진)")
    private val LOOKUP_OBJECT = Regex("(?:예약|진료|날씨|기온|주가|환율|잔액|배송|택배|일정|시간표|뉴스|전화번호|연락처|운행|도착|내역|기록)")
    private val READ_OR_EXPLAIN = Regex("(?:알려|설명|읽어|요약|보여|조회|확인|뭐야|무엇|어떤|왜|tellme|showme)")
    private val UI_CONTROL = Regex("(?:버튼|메뉴|아이콘|입력란|설정항목)")
    private val WHERE = Regex("(?:어디|어느|where)")
    private val INFORMATION_QUESTION = Regex("(?:언제|얼마|몇시|몇개|어디|무슨|있어|있나|있는지|내역|기록|현황|일정|날씨|주가|환율)")
}
