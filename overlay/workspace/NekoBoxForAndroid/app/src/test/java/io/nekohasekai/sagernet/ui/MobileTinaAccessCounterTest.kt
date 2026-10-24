package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class MobileTinaAccessCounterTest {
    @Test fun tenTapsBelongToOneGroupAndOneSequence() {
        val counter = MobileTinaAccessCounter()
        repeat(9) { assertFalse(counter.tap(1L, it * 100L)) }
        assertFalse(counter.tap(2L, 1000L))
        repeat(8) { assertFalse(counter.tap(2L, 1100L + it * 100L)) }
        assertTrue(counter.tap(2L, 2000L))
        assertFalse(counter.tap(2L, 2100L))
    }
    @Test fun pauseOrClockRollbackStartsANewSequence() {
        val counter = MobileTinaAccessCounter()
        repeat(9) { counter.tap(1L, 100L + it * 100L) }
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
