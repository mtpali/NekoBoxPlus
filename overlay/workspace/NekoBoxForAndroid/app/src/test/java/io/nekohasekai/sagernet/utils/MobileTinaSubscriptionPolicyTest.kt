package io.nekohasekai.sagernet.utils

import org.junit.Assert.*
import org.junit.Test

class MobileTinaSubscriptionPolicyTest {
    @Test fun rawGithubSubscriptionsAreProtected() {
        for (url in listOf("https://raw.githubusercontent.com/user/repo/main/sub", " HTTPS://RAW.GITHUBUSERCONTENT.COM/a ",
                "https://raw.githubusercontent.com:443/a?x=1#name"))
            assertTrue(url, MobileTinaSubscriptionPolicy.isProtectedUrl(url))
    }
    @Test fun unrelatedAndLookalikeOriginsRemainOrdinary() {
        for (url in listOf(null, "", "not a URL", "http://raw.githubusercontent.com/a",
                "https://raw.githubusercontent.com.evil.test/a", "https://raw.githubusercontent.com@evil.test/a",
                "https://evil.test/?next=https://raw.githubusercontent.com", "https://raw.githubusercontent.com:8443/a",
                "https://github.com/a", "https://example.test/sub"))
            assertFalse(url, MobileTinaSubscriptionPolicy.isProtectedUrl(url))
    }
}
