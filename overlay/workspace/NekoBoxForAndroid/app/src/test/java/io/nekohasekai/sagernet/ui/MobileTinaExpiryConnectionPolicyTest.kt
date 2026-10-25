package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.bg.BaseService
import org.junit.Assert.*
import org.junit.Test

class MobileTinaExpiryConnectionPolicyTest {
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
