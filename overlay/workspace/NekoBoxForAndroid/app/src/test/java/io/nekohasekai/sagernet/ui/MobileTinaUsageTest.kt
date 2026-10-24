package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class MobileTinaUsageTest {
    @Test fun zeroUsageIsStillValid() {
        val usage = MobileTinaUsage.parse("UPLOAD=0; download=0; total=1073741824")
        assertEquals(0L, usage.used)
        assertEquals(1073741824L, usage.total)
        assertTrue(usage.visible)
        assertEquals(0f, usage.fraction)
    }
    @Test fun missingTrafficIsNotInventedFromExpiry() {
        val usage = MobileTinaUsage.parse("expire=2000000000")
        assertNull(usage.used); assertNull(usage.total)
        assertEquals(2000000000L, usage.expiresAt)
    }
    @Test fun invalidOrOverflowingValuesAreIgnoredAndSumsSaturate() {
        val usage = MobileTinaUsage.parse("upload=${Long.MAX_VALUE}; download=10; total=999999999999999999999999999")
        assertEquals(Long.MAX_VALUE, usage.used); assertNull(usage.total)
        assertFalse(MobileTinaUsage.parse("expire=-4; total=oops").visible)
    }
    @Test fun limitedProgressNeverOverflows() {
        assertEquals(1f, MobileTinaUsage.parse("upload=20;download=30;total=10").fraction)
        assertEquals(0f, MobileTinaUsage.parse("upload=20;total=0").fraction)
    }
}
