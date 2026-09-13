package com.hwanghj09.sonju.agent

import java.net.URI
import java.text.Normalizer

/** Reviewed, read-only entry point. Credentials and reservation contents never become skill inputs. */
object HospitalReservationWorkflow {
    const val ORIGIN = "https://www.snubh.org"
    const val RESERVATIONS_URL = "$ORIGIN/personal/resvrStatusList.do"
    data class HospitalSite(
        val id: String,
        val name: String,
        val aliases: List<String>,
        val origin: String,
        val reservationsUrl: String,
        val resultPaths: Set<String>,
    ) {
        val host: String get() = URI(origin).host
        fun owns(uri: URI): Boolean = uri.host?.lowercase() in setOf(host, host.removePrefix("www."))
        fun namedIn(command: String): Boolean = !Regex("(?:강릉|금강|정읍|보성|보령|홍천|영덕)\\s*아산").containsMatchIn(command) && aliases.any { alias ->
            Regex("(?<![가-힣a-z0-9])" + alias.map { Regex.escape(it.toString()) }.joinToString("\\s*"),
                RegexOption.IGNORE_CASE).containsMatchIn(command)
        }
    }

    val sites = listOf(
        HospitalSite("snubh", "분당서울대학교병원", listOf("분당서울대학교병원", "분당서울대병원", "분당서울대"),
            ORIGIN, RESERVATIONS_URL, setOf("/personal/resvrStatusList.do")),
        HospitalSite("asan", "서울아산병원", listOf("서울아산병원", "아산병원", "서울아산"),
            "https://www.amc.seoul.kr", "https://www.amc.seoul.kr/asan/mychart/medical/appointment/medicalAppointmentList.do",
            setOf("/asan/mychart/medical/appointment/medicalAppointmentList.do", "/asan/mychart/main.do")),
    )

    fun request(command: String) = com.hwanghj09.sonju.task.HospitalAppointmentRequest.parse(command)
    fun matches(command: String): Boolean = request(command) != null
    fun supports(command: String): Boolean = matches(command) && unsupportedReason(command) == null
    fun siteFor(command: String): HospitalSite? = request(command)?.let {
        sites.filter { site -> site.namedIn(command) }.singleOrNull()
    }
    fun unsupportedReason(command: String): String? {
        val request = request(command) ?: return null
        if (siteFor(command) == null) return if (request.hospitalName == null) {
            "어느 병원의 예약을 조회할까요? 병원 이름을 포함해 말씀해 주세요."
        } else {
            "${request.hospitalName}의 예약 기록 조회 요청으로 이해했어요. 이 병원의 공식 조회 경로는 아직 확인되지 않았어요. " +
                "병원의 정확한 이름을 확인해 주세요."
        }
        if (request.scope != "appointments") return "${siteFor(command)!!.name}의 ${if (request.scope == "exam_appointments") "검사 예약" else "취소 기록"} 조회 요청으로 이해했어요. 해당 조회 화면은 아직 검증되지 않았어요."
        return null
    }
    fun loginGuide(@Suppress("UNUSED_PARAMETER") command: String): String = UserIntervention.guide(UserIntervention.Kind.LOGIN)
    fun isReviewedUrl(url: String?): Boolean = sites.any { it.reservationsUrl == url }
    fun isExpectedDestination(url: String?, snapshot: UiSnapshot): Boolean =
        sites.firstOrNull { it.reservationsUrl == url }?.let { site -> location(snapshot)?.let(site::owns) } == true

    /** Shared address validation retains the hospital-specific origin restriction. */
    fun location(snapshot: UiSnapshot, command: String? = null): URI? =
        UserIntervention.browserLocation(snapshot)?.takeIf { uri ->
            if (command == null) sites.any { it.owns(uri) } else siteFor(command)?.owns(uri) == true
        }

    fun loginRequired(snapshot: UiSnapshot, command: String? = null): Boolean {
        val uri = location(snapshot, command) ?: return false
        val labels = visibleLabels(snapshot).map(::compact)
        val loginPath = uri.path in setOf("/member/login.do", "/sso/login.do", "/reserve/guestSearch.do", "/asan/member/login.do", "/asan/member/preLogin.do", "/asan/mobile/member/login.do")
        return loginPath && labels.any { it.contains("로그인") || it.contains("본인인증") } ||
            labels.any { it.contains("로그인") } &&
            (snapshot.elements.any { it.visible && it.editable && it.sensitive } ||
                labels.any { it.contains("비밀번호") } && labels.any { it.contains("아이디") } &&
                labels.none { it == "로그아웃" })
    }

    fun needsLocalPageRead(snapshot: UiSnapshot, command: String? = null): Boolean = location(snapshot, command) != null &&
        snapshot.localReadOnlyText.isEmpty() && !loginRequired(snapshot, command) && resultText(snapshot, command) == null

    /** A successful launch and a verified result are separate observations. */
    fun shouldOpenPage(session: AutonomySession, snapshot: UiSnapshot): Boolean {
        val lastLogin = session.history.indexOfLast { it.action.type == ActionType.WAIT_FOR_USER }
        return session.history.drop(lastLogin + 1).none { it.action.type == ActionType.OPEN_URL } &&
            !loginRequired(snapshot, session.finalGoal) && resultText(snapshot, session.finalGoal) == null
    }

    fun canResume(snapshot: UiSnapshot, command: String? = null): Boolean = location(snapshot, command) != null && !loginRequired(snapshot, command) &&
        (visibleLabels(snapshot).any { compact(it) == "로그아웃" } || resultText(snapshot, command) != null)

    /** A menu link or a generic page title is never evidence that medical records were retrieved. */
    fun resultText(snapshot: UiSnapshot, command: String? = null): String? {
        val uri = location(snapshot, command) ?: return null
        val site = sites.singleOrNull { it.owns(uri) } ?: return null
        if (command != null && unsupportedReason(command) != null) return null
        if (snapshot.treeTruncated || loginRequired(snapshot, command)) return null
        val labels = visibleLabels(snapshot)
        // Some browser address controls expose only the origin when unfocused.
        if (uri.path !in site.resultPaths &&
            !(uri.path in setOf("", "/") && labels.any { compact(it) == "로그아웃" })) return null
        val headingIndex = labels.indexOfLast { compact(it) in RESULT_HEADINGS }
        if (headingIndex < 0) return null
        val body = labels.drop(headingIndex + 1).takeWhile { compact(it) !in FOOTER_HEADINGS }
        val empty = body.firstOrNull { EMPTY_RESULT.matches(compact(it)) }
        if (empty != null) return "${site.name} 예약현황에 표시된 결과입니다. $empty"
        val text = body.joinToString("\n")
        val normalized = compact(text)
        if (!normalized.contains("진료과") || !listOf("진료일", "예약일").any(normalized::contains) ||
            !DATE.containsMatchIn(text) || !TIME.containsMatchIn(text)) return null
        return "현재 병원 예약현황 화면에 표시된 기록입니다.\n$text".take(4_000)
    }

    fun entryPlan(command: String): AgentPlan {
        val site = requireNotNull(siteFor(command)) { "Hospital must be resolved before navigation" }
        return AgentPlan(
            goal = command, summary = "${site.name} 공식 예약현황을 엽니다.",
            modelRisk = RiskLevel.LOW, confidence = 1.0, source = PlanSource.LOCAL_RULE,
            actions = listOf(AgentAction(ActionType.OPEN_URL, "공식 예약현황 웹페이지를 엽니다.", site.reservationsUrl)),
            continueAfterAction = true,
        )
    }

    fun loginPlan(command: String): AgentPlan = AgentPlan(
        goal = command, summary = loginGuide(command), modelRisk = RiskLevel.LOW, confidence = 1.0,
        source = PlanSource.LOCAL_RULE,
        actions = listOf(AgentAction(ActionType.WAIT_FOR_USER, "사용자가 병원 웹사이트에서 로그인합니다.", requireNotNull(siteFor(command)).origin)),
        continueAfterAction = true,
    )

    fun completionPlan(command: String, snapshot: UiSnapshot): AgentPlan? = resultText(snapshot, command)?.let {
        AgentPlan(goal = command, summary = "병원 예약현황의 조회 결과를 확인했습니다.",
            modelRisk = RiskLevel.LOW, confidence = 1.0, source = PlanSource.LOCAL_RULE,
            actions = listOf(AgentAction(ActionType.FINISH, "현재 조회 결과를 기기에서 읽습니다.")),
            goalCompleted = true)
    }

    fun isAllowedUrl(command: String, url: String?): Boolean = siteFor(command)?.let {
        unsupportedReason(command) == null && url == it.reservationsUrl
    } == true

    private fun visibleLabels(snapshot: UiSnapshot): List<String> = snapshot.elements.asSequence()
        .filter { it.visible && !it.sensitive && !it.editable }
        .mapNotNull { it.text?.takeIf(String::isNotBlank) ?: it.contentDescription?.takeIf(String::isNotBlank) }
        .toList() + snapshot.localReadOnlyText
    private fun compact(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("\\s+"), "").lowercase()
    private val RESULT_HEADINGS = setOf("예약현황조회", "예약조회및취소", "예약현황", "예약조회", "진료예약현황", "진료예약조회", "진료예약내역", "예약내역조회")
    private val FOOTER_HEADINGS = setOf("바로가기메뉴", "개인정보처리방침")
    private val EMPTY_RESULT = Regex("(?:(?:진료|검사)?예약(?:하신)?(?:내역|현황|기록|정보)|조회된(?:예약)?(?:내역|결과|정보))(?:이|가)?(?:없습니다|없어요|없음|존재하지않습니다)[.!]?")
    private val DATE = Regex("(?:20[0-9]{2})[./년-]\\s*[01]?[0-9][./월-]\\s*[0-3]?[0-9]")
    private val TIME = Regex("(?:[01]?[0-9]|2[0-3])[:시]\\s*[0-5][0-9]")
}
