package io.nekohasekai.sagernet.ui

import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.database.isInsecureProfile
import io.nekohasekai.sagernet.ui.compose.showComposeMessageDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Cleanup queries the selected group directly, including when Auto has no profile-list view. */
internal object MobileTinaConfigurationCleanup {
    val actions = setOf(R.id.action_mobiletina_clear_configs, R.id.action_remove_duplicate,
        R.id.action_connection_test_delete_unavailable, R.id.action_remove_insecure)

    fun show(activity: ThemedActivity, action: Int, groupId: Long) = activity.lifecycleScope.launch {
        val profiles = withContext(Dispatchers.IO) { AppData.profiles.getByGroup(groupId) }
        val seen = HashSet<String>()
        val matches = profiles.filter { profile ->
            when (action) {
                R.id.action_mobiletina_clear_configs -> true
                R.id.action_remove_duplicate -> !seen.add(profile.requireBean().hash)
                R.id.action_connection_test_delete_unavailable -> profile.status > 1
                R.id.action_remove_insecure -> profile.isInsecureProfile(DataStore.globalAllowInsecure)
                else -> false
            }
        }
        if (activity.isFinishing || activity.isDestroyed) return@launch
        if (matches.isEmpty()) {
            activity.snackbar(R.string.mobiletina_no_matching_configs).show()
            return@launch
        }
        activity.showComposeMessageDialog(
            title = activity.getText(R.string.confirm),
            message = if (action == R.id.action_mobiletina_clear_configs) activity.getText(R.string.mobiletina_delete_configs_confirm)
                else matches.take(20).joinToString("\n", prefix = activity.getString(R.string.mobiletina_delete_count_confirm, matches.size) + "\n") { it.displayName() },
            positiveButton = activity.getText(R.string.delete),
            negativeButton = activity.getText(R.string.mobiletina_cancel),
            onPositive = {
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { GroupUpdater.cancelUpdate(groupId) }
                    if (matches.any { it.id == DataStore.currentProfile } &&
                        (DataStore.serviceState.canStop || DataStore.serviceState == BaseService.State.Stopping)) {
                        SagerNet.stopService()
                        val stopped = withTimeoutOrNull(5_000L) {
                            while (DataStore.serviceState.canStop || DataStore.serviceState == BaseService.State.Stopping) delay(100)
                            true
                        } == true
                        if (!stopped) {
                            activity.snackbar(R.string.mobiletina_cleanup_stop_failed).show()
                            return@launch
                        }
                    }
                    withContext(Dispatchers.IO) {
                        matches.forEach { ProfileManager.deleteProfile(it.groupId, it.id) }
                        GroupManager.postUpdate(groupId)
                    }
                    activity.snackbar(activity.resources.getQuantityString(R.plurals.removed, matches.size, matches.size)).show()
                }
            },
        )
    }
}
