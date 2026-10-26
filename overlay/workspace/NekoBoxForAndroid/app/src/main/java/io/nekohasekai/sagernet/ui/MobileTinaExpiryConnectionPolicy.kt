package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.bg.BaseService

internal object MobileTinaExpiryConnectionPolicy {
    fun metadataDeadline(subscription: SubscriptionBean?): Long? {
        subscription ?: return null
        val fallback = subscription.expireAt?.takeIf { it > 0 } ?: subscription.expiryDate?.toLong() ?: 0L
        val seconds = MobileTinaUsage.parse(subscription.subscriptionUserinfo, fallback).expiresAt ?: return null
        return seconds.takeIf { it in 1..Long.MAX_VALUE / 1000L }?.times(1000L)
    }

    fun shouldStop(groupId: Long, state: BaseService.State, profileGroup: Long?, selectorGroup: Long?): Boolean =
        state != BaseService.State.Idle && state != BaseService.State.Stopped &&
            (profileGroup == groupId || selectorGroup == groupId)
}
