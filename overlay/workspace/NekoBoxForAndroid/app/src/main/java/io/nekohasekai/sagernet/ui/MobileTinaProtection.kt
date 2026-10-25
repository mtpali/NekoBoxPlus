package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.ProxyEntity

/** The flag belongs to the source group and survives subscription refreshes and backup restores. */
object MobileTinaProtection {
    fun isProtected(groupId: Long): Boolean =
        groupId > 0L && AppData.groups.getById(groupId)?.subscription?.protectedContent == true

    fun isProtected(profile: ProxyEntity): Boolean = isProtected(profile.groupId)
}
