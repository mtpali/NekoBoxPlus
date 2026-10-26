package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.ui.MobileTinaStartupPermissions.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MobileTinaStartupPermissionsTest {
    @Test
    fun deniedCameraStillRequestsNotificationsBeforeVpn() {
        val flow = MobileTinaStartupPermissions()
        assertEquals(Request.Camera, flow.next(33, false, false))
        flow.complete(Request.Camera)
        assertEquals(Request.Notifications, flow.next(33, false, false))
        flow.complete(Request.Notifications)
        assertEquals(Request.Vpn, flow.next(33, false, false))
        flow.complete(Request.Vpn)
        assertNull(flow.next(33, false, false))
    }

    @Test
    fun upgradeWithCameraAlreadyGrantedStillRequestsNotifications() {
        val flow = MobileTinaStartupPermissions()
        assertEquals(Request.Notifications, flow.next(36, true, false))
        assertNull(flow.next(36, true, false))
    }

    @Test
    fun olderAndroidSkipsUnsupportedNotificationPermission() {
        for (sdk in listOf(23, 26, 32)) {
            assertEquals(Request.Vpn, MobileTinaStartupPermissions().next(sdk, true, false))
        }
    }

    @Test
    fun grantedPermissionsAreNotRequestedAgain() {
        assertEquals(Request.Vpn, MobileTinaStartupPermissions().next(37, true, true))
    }

    @Test
    fun recreationWaitsForEachOutstandingActivityResult() {
        for (request in Request.entries) {
            val restored = MobileTinaStartupPermissions(request.ordinal, true)
            assertNull(restored.next(36, false, false))
            restored.complete(Request.entries[(request.ordinal + 1) % Request.entries.size])
            assertNull(restored.next(36, false, false))
            restored.complete(request)
            assertFalse(restored.waiting)
            assertEquals(request.ordinal + 1, restored.stage)
        }
    }

    @Test
    fun completedPassRemainsCompleteAfterRecreation() {
        val restored = MobileTinaStartupPermissions(Request.entries.size, true)
        assertNull(restored.next(36, false, false))
        assertFalse(restored.waiting)
    }
}
