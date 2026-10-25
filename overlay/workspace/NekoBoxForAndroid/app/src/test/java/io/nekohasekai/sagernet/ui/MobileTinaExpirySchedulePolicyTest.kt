package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class MobileTinaExpirySchedulePolicyTest {
    @Test fun overdueVerificationFailureCannotScheduleAnImmediateRetry() {
        val now = 1_800_000_000_000L
        for (trigger in listOf(1L, now - 1, now, now + 60_000L)) {
            val delay = MobileTinaExpirySchedulePolicy.delayMillis(trigger, now, retry = true)
            assertTrue("A failed verification must yield the event loop", delay >= 30_000L)
        }
    }

    @Test fun aFreshFutureDeadlineKeepsItsExactDelayAndImmediateMarkerCanRunOnce() {
        val now = 1_800_000_000_000L
        assertEquals(45_123L, MobileTinaExpirySchedulePolicy.delayMillis(now + 45_123L, now))
        assertEquals(0L, MobileTinaExpirySchedulePolicy.delayMillis(1L, now))
    }

    @Test fun aRetiredMarkerIsTerminalOnlyWhenItIsTheOnlyProfileInABasicGroup() {
        assertTrue(MobileTinaExpirySchedulePolicy.isTerminalMarker(true, 1, true))
        assertFalse(MobileTinaExpirySchedulePolicy.isTerminalMarker(false, 1, true))
        assertFalse(MobileTinaExpirySchedulePolicy.isTerminalMarker(true, 2, true))
        assertFalse(MobileTinaExpirySchedulePolicy.isTerminalMarker(true, 1, false))
        assertFalse(MobileTinaExpirySchedulePolicy.isTerminalMarker(true, 0, false))
    }
}
