package com.hwanghj09.sonju.verifier

import com.hwanghj09.sonju.agent.UserIntervention
import com.hwanghj09.sonju.agent.UiSnapshot
import java.net.URI

/** Browser navigation accepts web URLs only; it cannot dispatch arbitrary Android intents. */
object WebNavigationPolicy {
    fun allows(value: String?): Boolean = uri(value) != null

    fun arrived(target: String?, snapshot: UiSnapshot): Boolean {
        val expected = uri(target) ?: return false
        val observed = UserIntervention.browserLocation(snapshot, httpsOnly = false) ?: return false
        return expected.host.equals(observed.host, ignoreCase = true) &&
            (expected.scheme.equals(observed.scheme, true) && port(expected) == port(observed) ||
                expected.scheme.equals("http", true) && port(expected) == 80 &&
                observed.scheme.equals("https", true) && port(observed) == 443)
    }

    private fun uri(value: String?): URI? {
        if (value.isNullOrBlank() || value.length > 2048 || value.any { it.isWhitespace() || it.isISOControl() } ||
            value.contains('\\')) return null
        return runCatching { URI(value) }.getOrNull()?.takeIf {
            it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() &&
                it.rawUserInfo == null && !it.isOpaque && (it.port == -1 || it.port in 1..65535)
        }
    }

    private fun port(uri: URI): Int = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
}
