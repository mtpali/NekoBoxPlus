package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.bg.BaseService

internal object MobileTinaExpiryConnectionPolicy {
    fun shouldStop(groupId: Long, state: BaseService.State, profileGroup: Long?, selectorGroup: Long?): Boolean =
        state != BaseService.State.Idle && state != BaseService.State.Stopped &&
            (profileGroup == groupId || selectorGroup == groupId)
}
