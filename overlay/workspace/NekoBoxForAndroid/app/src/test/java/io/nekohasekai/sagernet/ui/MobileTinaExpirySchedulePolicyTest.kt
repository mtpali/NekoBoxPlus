package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class MobileTinaExpirySchedulePolicyTest {
    @Test fun manyGroupsShareTheFailedClockCooldownAcrossProcessRestarts() {
        val failedAt = 10_000L
        repeat(1_000) { index ->
            assertFalse(MobileTinaExpirySchedulePolicy.mayFetchTime(failedAt + index, failedAt, true))
        }
        assertFalse(MobileTinaExpirySchedulePolicy.mayFetchTime(failedAt + 899_999L, failedAt, true))
        assertTrue(MobileTinaExpirySchedulePolicy.mayFetchTime(failedAt + 900_000L, failedAt, true))
        assertTrue(MobileTinaExpirySchedulePolicy.mayFetchTime(500L, failedAt, false))
        assertTrue(MobileTinaExpirySchedulePolicy.mayFetchTime(500L, failedAt, true))
        assertTrue(MobileTinaExpirySchedulePolicy.mayFetchTime(500L, -1L, true))
    }
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

    @org.junit.Test fun previousSchedulersCannotExecuteAnExpiryAfterUpgrade() {
        for (version in listOf(0, 102, 103, 104)) {
            org.junit.Assert.assertFalse(MobileTinaExpirySchedulePolicy.acceptsWork(version, "valid-old-revision"))
        }
        org.junit.Assert.assertFalse(MobileTinaExpirySchedulePolicy.acceptsWork(105, null))
        org.junit.Assert.assertFalse(MobileTinaExpirySchedulePolicy.acceptsWork(105, ""))
        org.junit.Assert.assertTrue(MobileTinaExpirySchedulePolicy.acceptsWork(105, "current-revision"))
    }
}
