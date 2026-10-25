package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.utils.MobileTinaSubscriptionPolicy

/** The flag belongs to the source group and survives subscription refreshes and backup restores. */
object MobileTinaProtection {
    fun isProtected(subscription: SubscriptionBean?): Boolean =
        subscription?.protectedContent == true || MobileTinaSubscriptionPolicy.isProtectedUrl(subscription?.link)

    fun isProtected(groupId: Long): Boolean =
        groupId > 0L && isProtected(AppData.groups.getById(groupId)?.subscription)

    fun isProtected(profile: ProxyEntity): Boolean = isProtected(profile.groupId)
}
