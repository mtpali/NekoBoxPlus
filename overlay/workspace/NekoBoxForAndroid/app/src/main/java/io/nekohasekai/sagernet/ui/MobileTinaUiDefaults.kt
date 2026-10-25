package io.nekohasekai.sagernet.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.nekohasekai.sagernet.AppIconManager
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.Theme

/** Apply the requested defaults once on upgrade; subsequent user choices remain untouched. */
object MobileTinaUiDefaults {
    private const val MIGRATED = "mobiletinaPlainUiDefaultsV2"

    suspend fun applyStoredData() {
        val key = "mobiletinaDelayOrderV1"
        if (DataStore.configurationStore.getBoolean(key, false)) return
        AppData.groups.allGroups().filter { it.order == GroupOrder.ORIGIN }.forEach {
            it.order = GroupOrder.BY_DELAY
            AppData.groups.updateGroup(it)
            GroupManager.postUpdate(it)
        }
        DataStore.configurationStore.putBoolean(key, true)
    }

    internal fun defaultAppSelection(): String = AppSelectionLogic.pinnedPackages.filter { name ->
        runCatching { io.nekohasekai.sagernet.SagerNet.application.packageManager.getApplicationInfo(name, 0) }.isSuccess
    }.joinToString("\n")

    fun apply() {
        val appsKey = "mobiletinaAppSelectionV102"
        if (!DataStore.configurationStore.getBoolean(appsKey, false)) {
            DataStore.proxyApps = true
            DataStore.bypass = false
            DataStore.individual = defaultAppSelection()
            DataStore.configurationStore.putBoolean(appsKey, true)
        }
        val trafficKey = "mobiletinaTrafficV102"
        if (!DataStore.configurationStore.getBoolean(trafficKey, false)) {
            DataStore.profileTrafficStatistics = true
            if (DataStore.profileTrafficUpdateInterval <= 0) DataStore.profileTrafficUpdateInterval = 1000
            DataStore.configurationStore.putBoolean(trafficKey, true)
        }
        val phoneModeKey = "mobiletinaFollowPhoneThemeV1"
        if (!DataStore.configurationStore.getBoolean(phoneModeKey, false)) {
            DataStore.nightTheme = 0
            Theme.currentNightMode = 0
            DataStore.configurationStore.putBoolean(phoneModeKey, true)
        }
        if (DataStore.configurationStore.getBoolean(MIGRATED, false)) return
        if (DataStore.appTheme == Theme.BLUE) DataStore.appTheme = Theme.SIMPLE
        // The previous first-run setup forced this on. Keep the saved app selection.
        DataStore.proxyApps = true
        DataStore.appLanguage = "en"
        DataStore.configurationStore.putBoolean(MIGRATED, true)
    }
}

/** Recover the stable launcher after upgrading an installation using a removed alternate icon. */
class MobileTinaUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AppIconManager.ensureDefault(context)
        }
    }
}
