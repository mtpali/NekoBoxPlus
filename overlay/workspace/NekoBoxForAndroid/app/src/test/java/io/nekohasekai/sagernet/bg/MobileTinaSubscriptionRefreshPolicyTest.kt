package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileTinaSubscriptionRefreshPolicyTest {
    private fun due(
        now: Long = 4_600L,
        last: Int? = 1_000,
        interval: Int? = 60,
        enabled: Boolean = true,
        link: String? = "https://example.test/sub",
        connectedOnly: Boolean = false,
        connected: Boolean = false,
    ) = MobileTinaSubscriptionRefreshPolicy.isDue(
        enabled, link, connectedOnly, connected, last, interval, now,
    )

    @Test fun neverUpdatedSubscriptionIsDue() { assertTrue(due(now = 1L, last = 0)) }
    @Test fun beforeDeadlineDoesNotDownload() { assertFalse(due(now = 4_599L)) }
    @Test fun deadlineIsInclusive() { assertTrue(due()) }
    @Test fun movingClockBackDoesNotDownloadRepeatedly() { assertFalse(due(now = 999L)) }
    @Test fun shortIntervalsRespectWorkManagerMinimum() {
        assertFalse(due(now = 1_899L, interval = 1))
        assertTrue(due(now = 1_900L, interval = 1))
    }
    @Test fun connectedOnlyPreferenceIsRespected() {
        assertFalse(due(connectedOnly = true))
        assertTrue(due(connectedOnly = true, connected = true))
    }
    @Test fun disabledOrBlankSubscriptionsAreSkipped() {
        assertFalse(due(enabled = false))
        assertFalse(due(link = "  "))
        assertFalse(due(link = null))
    }
    @Test fun largeIntervalsDoNotOverflow() { assertFalse(due(interval = Int.MAX_VALUE)) }
}
