package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AppSelectionAssetPolicyTest {
    @Test fun bitmapOomUsesFallbackAndStopsFurtherPackageResourceAllocations() {
        val policy = AppSelectionAssetPolicy()
        assertNull(policy.load<String> { throw OutOfMemoryError("OOM allocating Bitmap with dimensions 304 x 304") })
        assertTrue(policy.exhausted)
        var retried = false
        repeat(500) { assertNull(policy.load { retried = true; "icon" }) }
        assertFalse(retried)
    }
    @Test fun oneUnavailableAppDoesNotPreventOtherAppResourcesFromLoading() {
        val policy = AppSelectionAssetPolicy()
        assertNull(policy.load<String> { throw SecurityException("APK resource unavailable") })
        assertFalse(policy.exhausted)
        assertEquals("valid app", policy.load { "valid app" })
    }
    @Test fun leavingTheScreenStillCancelsItsWork() {
        val policy = AppSelectionAssetPolicy()
        val cancelled = CancellationException("screen closed")
        try { policy.load<String> { throw cancelled }; fail("Cancellation swallowed") }
        catch (actual: CancellationException) { assertSame(cancelled, actual) }
        assertFalse(policy.exhausted)
    }
    @Test fun unrelatedFatalErrorsAreNotHidden() {
        val policy = AppSelectionAssetPolicy()
        val fatal = AssertionError("programming error")
        try { policy.load<String> { throw fatal }; fail("Fatal error swallowed") }
        catch (actual: AssertionError) { assertSame(fatal, actual) }
    }
    @Test fun extremeDisplayDensitiesCannotCreateUnboundedIconBitmaps() {
        for (requested in listOf(Int.MIN_VALUE, -1, 0, 1, 40, 120, 304, Int.MAX_VALUE)) {
            val pixels = AppSelectionAssetPolicy.iconPixels(requested)
            assertTrue(pixels in 1..128)
            assertTrue(pixels.toLong() * pixels * 4 <= 64 * 1024)
        }
        assertEquals(120, AppSelectionAssetPolicy.iconPixels(120))
    }
}
