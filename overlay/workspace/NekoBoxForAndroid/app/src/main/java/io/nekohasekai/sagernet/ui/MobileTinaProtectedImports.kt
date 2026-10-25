package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.utils.MobileTinaImportNormalizer
import io.nekohasekai.sagernet.utils.MobileTinaProtectedPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object MobileTinaProtectedImports {
    suspend fun import(activity: ThemedActivity, input: String): Boolean {
        val payload = MobileTinaProtectedPayload.decode(input) ?: return false
        return withContext(Dispatchers.IO) {
            val url = MobileTinaImportNormalizer.subscriptionUrl(payload)?.takeIf {
                runCatching { java.net.URI(it).rawUserInfo == null }.getOrDefault(false)
            }
            val group: ProxyGroup
            if (url != null) {
                val candidate = SubscriptionLinkImportPolicy.singleHttpCandidate(url)
                    ?: return@withContext true
                group = AppData.groups.subscriptions().firstOrNull { it.subscription?.link == candidate.link }
                    ?: GroupManager.createGroup(ProxyGroup(type = GroupType.SUBSCRIPTION).apply {
                        name = io.nekohasekai.sagernet.utils.MobileTinaAboutContent.brandTitle()
                        subscription = SubscriptionBean().applyDefaultValues().apply {
                            link = candidate.link
                            protectedContent = true
                            autoUpdate = true
                        }
                    })
                group.subscription?.protectedContent = true
                GroupManager.updateGroup(group)
                GroupUpdater.startUpdate(group, true)
            } else {
                val profiles = RawUpdater.parseRaw(payload).orEmpty()
                if (profiles.isEmpty()) {
                    withContext(Dispatchers.Main) { activity.snackbar(R.string.no_proxies_found).show() }
                    return@withContext true
                }
                group = GroupManager.createGroup(ProxyGroup(type = GroupType.BASIC).apply {
                    name = io.nekohasekai.sagernet.utils.MobileTinaAboutContent.brandTitle()
                    subscription = SubscriptionBean().applyDefaultValues().apply {
                        protectedContent = true
                        autoUpdate = false
                    }
                })
                ProfileManager.createProfiles(group.id, profiles)
                MobileTinaSubscriptionExpiry.syncPayload(group.id, payload)
            }
            DataStore.selectedGroup = group.id
            withContext(Dispatchers.Main) {
                activity.snackbar(R.string.action_import_msg).show()
                (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_configuration)
            }
            true
        }
    }
}
