package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.bg.BaseService
import org.junit.Assert.*
import org.junit.Test

class MobileTinaExpiryConnectionPolicyTest {
    @Test fun headerExpiryMatchesTheDisplayedSubscriptionDeadline() {
        val subscription = SubscriptionBean().applyDefaultValues().apply {
            subscriptionUserinfo = "upload=1; download=2; expire=1800000000"
            expireAt = 1900000000L; expiryDate = 2000000000
        }
        assertEquals(1800000000000L, MobileTinaExpiryConnectionPolicy.metadataDeadline(subscription))
    }
    @Test fun metadataFallbacksAndNoDeadlineAreHandled() {
        val subscription = SubscriptionBean().applyDefaultValues().apply { expireAt = 1900000000L }
        assertEquals(1900000000000L, MobileTinaExpiryConnectionPolicy.metadataDeadline(subscription))
        subscription.expireAt = 0L; subscription.expiryDate = 2000000000
        assertEquals(2000000000000L, MobileTinaExpiryConnectionPolicy.metadataDeadline(subscription))
        subscription.expiryDate = 0
        assertNull(MobileTinaExpiryConnectionPolicy.metadataDeadline(subscription))
        assertNull(MobileTinaExpiryConnectionPolicy.metadataDeadline(null))
    }
    @Test fun anOverflowingDeadlineCannotBecomeAnAlreadyExpiredNegativeTimestamp() {
        val subscription = SubscriptionBean().applyDefaultValues().apply { subscriptionUserinfo = "expire=" + Long.MAX_VALUE }
        assertNull(MobileTinaExpiryConnectionPolicy.metadataDeadline(subscription))
    }

    @Test fun theActualConnectingProfileIsStoppedBeforeCurrentProfileIsWritten() {
        assertTrue(MobileTinaExpiryConnectionPolicy.shouldStop(7, BaseService.State.Connecting, 7, -1))
    }
    @Test fun aSelectedDifferentGroupDoesNotStopAnUnrelatedLiveConnection() {
        for (state in listOf(BaseService.State.Connecting, BaseService.State.Connected, BaseService.State.Stopping)) {
            assertFalse(MobileTinaExpiryConnectionPolicy.shouldStop(7, state, 8, -1))
        }
    }
    @Test fun anExpiredSelectorGroupAlsoStopsTheLiveCore() {
        assertTrue(MobileTinaExpiryConnectionPolicy.shouldStop(7, BaseService.State.Connected, 8, 7))
        assertTrue(MobileTinaExpiryConnectionPolicy.shouldStop(7, BaseService.State.Stopping, 7, -1))
    }
    @Test fun staleProfileMetadataCannotStopAnAlreadyStoppedService() {
        for (state in listOf(BaseService.State.Idle, BaseService.State.Stopped)) {
            assertFalse(MobileTinaExpiryConnectionPolicy.shouldStop(7, state, 7, 7))
        }
    }
}
