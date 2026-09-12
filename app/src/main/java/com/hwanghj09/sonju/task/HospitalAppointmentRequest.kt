package com.hwanghj09.sonju.task

import java.text.Normalizer

/** Hospital identity is resolved separately; unregistered hospitals retain their lookup intent. */
data class HospitalAppointmentRequest(val hospitalName: String?, val scope: String = "appointments") {
    companion object {
        fun parse(command: String): HospitalAppointmentRequest? {
            val raw = Normalizer.normalize(command, Normalizer.Form.NFKC).lowercase()
            val text = raw.replace(Regex("\\s+"), "")
            if (RequestInterpreter.understand(command).purpose == RequestPurpose.HOW_TO ||
                MUTATION.containsMatchIn(text) || listOf("뜻", "의미", "정의").any(text::contains)) return null
            val hospital = Regex("([가-힣a-z0-9]+\\s*(?:병원|의료원|의원))").find(raw)?.value
            val medical = hospital != null || listOf("진료", "병원", "서울아산", "분당서울대").any(text::contains)
            if (!medical || !(text.contains("예약") || text.contains("진료") && text.contains("언제"))) return null
            if (!QUERY.containsMatchIn(text)) return null
            val scope = when {
                listOf("취소내역", "취소기록", "취소한예약").any(text::contains) -> "cancelled_appointments"
                listOf("검사예약", "검사일정").any(text::contains) -> "exam_appointments"
                else -> "appointments"
            }
            return HospitalAppointmentRequest(hospital, scope)
        }
        private val QUERY = Regex("(?:기록|내역|현황|알려|조회|확인|읽어|보여|일정|날짜|시간|언제|있는지|예약돼|예약되어|잡아둔)")
        private val MUTATION = Regex("(?:(?:취소|변경|예약)해(?!둔|둠|놓|뒀|놨)(?:줘|주세요|줄|라|요|[.!?]|$)|예약잡아|잡아줘|새로예약|결제|삭제|전송|보내)")
    }
}
