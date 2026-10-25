package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import org.junit.Assert.*
import org.junit.Test

class ProtectedSubscriptionRefreshTest {
    @Test fun refreshCannotClearPreviouslyProtectedSource() {
        val current = SubscriptionBean().applyDefaultValues().apply { protectedContent = true }
        val updated = SubscriptionBean().applyDefaultValues()
        mergeCurrentSubscriptionSettings(current, updated)
        assertTrue(updated.protectedContent)
    }
    @Test fun responseMarkerProtectsOnlyItsOwnSource() {
        val normal = SubscriptionBean().applyDefaultValues()
        val marked = SubscriptionBean().applyDefaultValues().apply { protectedContent = true }
        mergeCurrentSubscriptionSettings(normal, marked)
        assertTrue(marked.protectedContent)
        assertFalse(normal.protectedContent)
        val ordinaryUpdate = SubscriptionBean().applyDefaultValues()
        mergeCurrentSubscriptionSettings(normal, ordinaryUpdate)
        assertFalse(ordinaryUpdate.protectedContent)
    }
}
