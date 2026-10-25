package io.nekohasekai.sagernet.utils

import java.net.URI

/** Match the actual HTTPS origin, including existing subscriptions, rather than a string prefix. */
object MobileTinaSubscriptionPolicy {
    fun isProtectedUrl(link: String?): Boolean = runCatching {
        val uri = URI(link?.trim().orEmpty())
        uri.scheme.equals("https", true) &&
            uri.host.equals("raw.githubusercontent.com", true) &&
            uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)
}
