package com.hwanghj09.sonju.agent

import java.net.URI
import java.text.Normalizer

/** Local authentication checkpoint. Only its kind survives redaction; never a challenge or answer. */
object UserIntervention {
    enum class Kind(val label: String) {
        DEVICE_UNLOCK("기기 잠금 해제"),
        LOGIN("로그인"), BIOMETRIC("생체인식"), CAPTCHA("보안문자 확인"),
        IDENTITY("본인인증"), TWO_FACTOR("추가 인증"), OTHER("직접 확인"),
    }

    fun kindFromLabel(label: String): Kind? {
        val text = compact(label)
        return LABEL_KINDS.firstOrNull { it.first.containsMatchIn(text) }?.second
    }

    private val LABEL_KINDS = listOf(
        Regex("캡[챠차]|captcha|로봇이아닙니다|로봇이아님|보안문자|자동입력방지|verifyyouarehuman|notarobot") to Kind.CAPTCHA,
        Regex("^(?:지문|얼굴|생체)인증$|(?:지문|얼굴|생체).*인증.*(?:필요|해주세요|진행)|(?:지문|얼굴|생체)인식.*(?:필요|해주세요|진행)|지문.*(?:센서|입력|대세요|대주세요|올려)|얼굴.*(?:카메라|비춰|보여)|(?:fingerprint|faceid|biometric).*(?:verify|scan|touch|authentication)|(?:use|confirm|scan).*(?:fingerprint|face)|touch.*sensor") to Kind.BIOMETRIC,
        Regex("2단계인증|2차인증|이중인증|추가인증|two.factor|two.step|^(?:인증번호|보안코드|otp)$|인증번호.*입력|보안코드.*입력|(?:enter|type).*(?:verificationcode|securitycode|one.time|otp)") to Kind.TWO_FACTOR,
        Regex("^(?:(?:휴대폰)?본인(?:인증|확인)|(?:휴대폰|휴대전화|문자)인증)$|본인(?:인증|확인).*(?:필요|해주세요|진행)|verifyyouridentity|identityverification") to Kind.IDENTITY,
        Regex("^(?:회원)?로그인$|로그인.*(?:필요|해주세요)|^(?:signin|login)$|please(?:signin|login)|^(?:비밀번호|암호|pin|passcode|password)(?:입력|를입력.*)?$") to Kind.LOGIN
    )


    fun required(snapshot: UiSnapshot, command: String): Kind? {
        if (HospitalReservationWorkflow.loginRequired(snapshot, command)) return Kind.LOGIN
        snapshot.userIntervention?.let { return it }
        return snapshot.elements.asSequence().filter { it.visible && !it.sensitive && !it.editable }
            .mapNotNull { node -> sequenceOf(node.text, node.contentDescription, node.paneTitle)
                .filterNotNull().mapNotNull(::kindFromLabel).firstOrNull()?.takeIf {
                    !node.clickable && !node.checkable && snapshot.elements.none { parent ->
                        parent.clickable && node.path.startsWith("${parent.path}.")
                    } || node.heading || it == Kind.CAPTCHA
                } }.firstOrNull()
            ?: if (snapshot.elements.any { it.visible && it.editable && it.sensitive }) Kind.OTHER else null
    }

    fun guide(kind: Kind): String = "${kind.label}이 필요해요. 현재 앱에서 직접 완료해 주세요. " +
        "화면 밖을 누르면 이 안내가 접힙니다. 완료된 화면을 확인하면 이어갈게요. " +
        "확인을 마쳤으면 ‘손주야’라고 부른 뒤 ‘완료했어요’라고 말씀해 주세요. ‘완료했어요 · 계속’을 눌러도 돼요."

    fun plan(command: String, snapshot: UiSnapshot): AgentPlan? {
        val kind = required(snapshot, command) ?: return null
        val target = HospitalReservationWorkflow.siteFor(command)?.origin ?: snapshot.packageName
        return AgentPlan(command, guide(kind), RiskLevel.LOW, 1.0,
            listOf(AgentAction(ActionType.WAIT_FOR_USER, "사용자의 직접 확인을 기다립니다.", target)),
            source = PlanSource.LOCAL_RULE, continueAfterAction = true)
    }

    fun canResume(handoff: AutonomySession.UserHandoff, snapshot: UiSnapshot, command: String): Boolean {
        if (snapshot.treeTruncated) return false
        val required = required(snapshot, command)
        if (handoff.kind == Kind.DEVICE_UNLOCK) {
            return required != Kind.DEVICE_UNLOCK && snapshot.packageName !in setOf("unknown", "com.android.systemui", "com.hwanghj09.sonju") &&
                snapshot.elements.any { it.visible && it.enabled && !it.sensitive }
        }
        if (snapshot.screenFingerprint() == handoff.beforeFingerprint) return false
        if (snapshot.packageName != handoff.resumePackage || required != null) return false
        if (HospitalReservationWorkflow.matches(command)) return HospitalReservationWorkflow.canResume(snapshot, command)
        if (handoff.origin != null && browserOrigin(snapshot) != handoff.origin) return false
        // A hidden browser address cannot establish which site the user returned to.
        if (snapshot.packageName in BROWSERS && handoff.origin == null) return false
        val publicNodes = snapshot.elements.filter { it.visible && !it.sensitive && !it.editable }
        if (publicNodes.none { it.enabled && (it.clickable || it.scrollable) } ||
            publicNodes.count { !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() } < 2) return false
        return publicNodes.none { node ->
            NOT_READY.containsMatchIn(compact(node.text.orEmpty() + node.contentDescription.orEmpty()))
        }
    }

    /** Only browser-owned address controls establish an origin, never page labels or model output. */
    fun browserLocation(snapshot: UiSnapshot, httpsOnly: Boolean = true): URI? {
        if (snapshot.packageName !in BROWSERS) return null
        return snapshot.elements.asSequence().filter { node -> node.visible && !node.sensitive &&
            node.viewId?.let { id -> id.startsWith("${snapshot.packageName}:id/") &&
                ADDRESS_IDS.any { id.substringAfter(":id/").contains(it) } } == true
        }.mapNotNull { node ->
            val raw = node.text?.trim { it.isWhitespace() || it in ADDRESS_EDGE_MARKS }
                ?.takeIf { it.length in 1..1_000 && ' ' !in it } ?: return@mapNotNull null
            runCatching { URI(if ("://" in raw) raw else "https://$raw") }.getOrNull()
                ?.takeIf { !it.host.isNullOrBlank() && it.userInfo == null &&
                    if (httpsOnly) it.scheme == "https" && it.port in setOf(-1, 443)
                    else it.scheme in setOf("http", "https") && (it.port == -1 || it.port in 1..65535) }
        }.firstOrNull()
    }

    fun browserOrigin(snapshot: UiSnapshot): String? = browserLocation(snapshot)?.let { "https://${it.host.lowercase()}" }
    private fun compact(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase().replace(WHITESPACE, "")
    private val WHITESPACE = Regex("\\s+")
    private val NOT_READY = Regex("^(?:로딩중|불러오는중|잠시만기다려주세요|loading|pleasewait)|(?:인증|로그인).*(?:실패|취소)|authentication(?:failed|cancelled)")
    private val BROWSERS = setOf("com.android.chrome", "com.sec.android.app.sbrowser", "org.mozilla.firefox",
        "com.microsoft.emmx", "com.naver.whale")
    private val ADDRESS_IDS = setOf("url_bar", "location_bar", "address_bar", "omnibox")
    private val ADDRESS_EDGE_MARKS = setOf('\u200e', '\u200f', '\u202a', '\u202b', '\u202c', '\u2066', '\u2067', '\u2068', '\u2069')
}
