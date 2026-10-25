package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class MobileTinaAccessCounterTest {
    @Test fun fifteenTapsBelongToOneGroupAndOneSequence() {
        val counter = MobileTinaAccessCounter()
        repeat(14) { assertFalse(counter.tap(1L, it * 100L)) }
        assertFalse(counter.tap(2L, 1500L))
        repeat(13) { assertFalse(counter.tap(2L, 1600L + it * 100L)) }
        assertTrue(counter.tap(2L, 2900L))
        assertFalse(counter.tap(2L, 3000L))
    }
    @Test fun pauseOrClockRollbackStartsANewSequence() {
        val counter = MobileTinaAccessCounter()
        repeat(14) { counter.tap(1L, 100L + it * 100L) }
        assertFalse(counter.tap(1L, 5000L))
        assertFalse(counter.tap(1L, 100L))
    }
    @Test fun cancelledPermissionScreenCannotStartAnOldAttempt() {
        val first = MobileTinaConnectionGate.begin()
        assertTrue(MobileTinaConnectionGate.accepts(first))
        MobileTinaConnectionGate.cancel()
        assertFalse(MobileTinaConnectionGate.accepts(first))
        val second = MobileTinaConnectionGate.begin()
        assertTrue(MobileTinaConnectionGate.accepts(second))
        assertFalse(MobileTinaConnectionGate.accepts(first))
    }
}
