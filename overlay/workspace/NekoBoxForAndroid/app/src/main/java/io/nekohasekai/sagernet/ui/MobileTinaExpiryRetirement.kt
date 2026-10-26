package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import java.security.MessageDigest

/** A short, idempotent transaction in ONE database. No callbacks, settings, service or network IO. */
internal object MobileTinaExpiryRetirement {
    fun isMarker(profile: ProxyEntity): Boolean = profile.socksBean?.let {
        it.serverAddress == "1" && it.serverPort == 1 && it.username.isNullOrEmpty() && it.password.isNullOrEmpty()
    } == true

    fun retire(groupId: Long, expectedLinkDigest: String, expectedUpdated: Int, marker: SOCKSBean, previousProfileId: Long = 0L): ProxyEntity? =
        AppData.transactions.run {
            val group = AppData.groups.getById(groupId) ?: return@run null
            val link = group.subscription?.link.orEmpty()
            val digest = MessageDigest.getInstance("SHA-256").digest(link.toByteArray()).joinToString("") { "%02x".format(it) }
            if (digest != expectedLinkDigest || expectedUpdated != (group.subscription?.lastUpdated ?: 0)) return@run null
            val profiles = AppData.profiles.getByGroup(groupId)
            val result = ProxyEntity(groupId = groupId, userOrder = 1L).apply {
                id = profiles.firstOrNull { it.id == previousProfileId }?.id
                    ?: profiles.singleOrNull()?.takeIf(::isMarker)?.id ?: 0L
                putBean(marker)
            }
            val protected = MobileTinaProtection.isProtected(group.subscription)
            AppData.profiles.deleteByGroup(groupId)
            group.type = GroupType.BASIC
            group.subscription = if (protected) SubscriptionBean().applyDefaultValues().apply {
                protectedContent = true; autoUpdate = false
            } else null
            AppData.groups.updateGroup(group)
            result.id = AppData.profiles.addProxy(result)
            result
        }
}
