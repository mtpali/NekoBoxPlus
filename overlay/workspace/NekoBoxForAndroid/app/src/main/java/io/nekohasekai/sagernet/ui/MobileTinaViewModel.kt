package io.nekohasekai.sagernet.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.utils.MobileTinaSmartConnect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class MobileTinaHomeState(
    val service: BaseService.State = BaseService.State.Idle,
    val pending: Boolean = false,
    val testing: Boolean = false,
    val failed: Boolean = false,
    val expired: Boolean = false,
    val message: String = "",
    val groupId: Long = 0,
    val groupName: String = "",
    val profileName: String = "",
    val ping: Int = 0,
    val count: Int = 0,
    val usage: MobileTinaUsage = MobileTinaUsage(null, null, null),
)

/** Activity-scoped state survives Auto/Manual navigation and configuration changes. */
internal class MobileTinaViewModel(application: Application) : AndroidViewModel(application), GroupManager.Listener {
    private val mutable = MutableStateFlow(MobileTinaHomeState())
    val state = mutable.asStateFlow()
    private val starts = Channel<Long>(Channel.CONFLATED)
    val startRequests = starts.receiveAsFlow()
    private var attempt: Job? = null
    private var watchdog: Job? = null
    private var metadata: Job? = null
    private var foregroundUpdate: Job? = null
    private var automaticUpdate: Job? = null
    private var generation = 0L
    private var permissionAccepted = false
    private var sawStarting = false
    private var lastForegroundUpdate = -60_000L

    init {
        GroupManager.addListener(this)
        refresh()
    }

    private data class Snapshot(
        val groupId: Long,
        val groupName: String,
        val profiles: List<ProxyEntity>,
        val selected: ProxyEntity?,
        val usage: MobileTinaUsage,
        val expired: Boolean,
    )

    private fun load(): Snapshot {
        var group = AppData.groups.getById(DataStore.currentGroupId())
        if (group?.subscription == null && (group == null || AppData.profiles.countByGroup(group.id) == 0L)) {
            group = AppData.groups.allGroups().firstOrNull { it.subscription != null || AppData.profiles.countByGroup(it.id) > 0 } ?: group
            group?.let { DataStore.selectedGroup = it.id }
        }
        val profiles = group?.let { AppData.profiles.getByGroup(it.id) }.orEmpty()
        val selected = profiles.firstOrNull { it.id == DataStore.selectedProxy }
        val subscription = group?.subscription
        val expiry = subscription?.expireAt?.takeIf { it > 0 }
            ?: subscription?.expiryDate?.toLong()?.takeIf { it > 0 } ?: 0L
        val usage = MobileTinaUsage.parse(subscription?.subscriptionUserinfo, expiry)
        val marker = profiles.any(MobileTinaSubscriptionExpiry::isMarker)
        val managedExpiry = group?.let { MobileTinaSubscriptionExpiry.expiresAt(it.id) }
        val expirySeconds = managedExpiry?.div(1000L) ?: usage.expiresAt
        return Snapshot(
            group?.id ?: 0, group?.displayName().orEmpty(), profiles, selected,
            usage.copy(expiresAt = expirySeconds),
            marker || (expirySeconds != null && expirySeconds <= MobileTinaSubscriptionExpiry.nowMillis() / 1000L),
        )
    }

    fun refresh(foreground: Boolean = false) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            metadata?.cancel()
            metadata = viewModelScope.launch {
                val snapshot = withContext(Dispatchers.IO) { load() }
                mutable.value = mutable.value.copy(
                    groupId = snapshot.groupId, groupName = snapshot.groupName,
                    profileName = snapshot.selected?.displayName().orEmpty(),
                    ping = snapshot.selected?.ping ?: 0,
                    count = snapshot.profiles.count { !MobileTinaSubscriptionExpiry.isMarker(it) },
                    usage = snapshot.usage, expired = snapshot.expired,
                )
                if (foreground) refreshDueSubscriptions()
            }
        }
    }

    fun refreshNow(byUser: Boolean = true): Job? {
        foregroundUpdate?.takeIf { it.isActive }?.let { return it }
        if (!hasNetwork()) return null
        return viewModelScope.launch(Dispatchers.IO) {
            try {
                val group = AppData.groups.getById(DataStore.currentGroupId()) ?: return@launch
                if (group.subscription != null && group.id !in GroupUpdater.updating) {
                    GroupUpdater.executeUpdate(group, byUser)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Existing profiles remain available after a failed refresh. */ }
            finally { refresh() }
        }.also { foregroundUpdate = it }
    }

    private fun refreshDueSubscriptions() {
        if (automaticUpdate?.isActive == true || !hasNetwork()) return
        if (SystemClock.elapsedRealtime() - lastForegroundUpdate < 60_000L) return
        lastForegroundUpdate = SystemClock.elapsedRealtime()
        automaticUpdate = viewModelScope.launch(Dispatchers.IO) {
            try {
                SubscriptionUpdater.refreshDueSubscriptions()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The updater reports errors without deleting cached profiles. */ }
            finally { refresh() }
        }
    }

    fun cancelSubscriptionUpdates() {
        automaticUpdate?.cancel()
        foregroundUpdate?.cancel()
    }

    fun toggle() {
        if (mutable.value.pending || DataStore.serviceState.canStop || DataStore.serviceState == BaseService.State.Stopping) {
            cancelPending(stop = true)
            return
        }
        if (!hasNetwork()) { fail(R.string.mobiletina_no_internet); return }
        watchdog?.cancel()
        val token = MobileTinaConnectionGate.begin()
        generation = token
        permissionAccepted = false
        sawStarting = false
        mutable.value = mutable.value.copy(pending = true, testing = true, failed = false, message = "")
        attempt = viewModelScope.launch {
            try {
                var snapshot = withContext(Dispatchers.IO) { load() }
                if (snapshot.profiles.isEmpty()) {
                    withContext(Dispatchers.IO) {
                        val group = AppData.groups.getById(snapshot.groupId) ?: return@withContext
                        if (group.subscription?.link.isNullOrBlank()) return@withContext
                        if (group.id in GroupUpdater.updating) {
                            withTimeoutOrNull(6_000L) {
                                while (group.id in GroupUpdater.updating) delay(100)
                            }
                        } else GroupUpdater.executeUpdate(group, true)
                    }
                    snapshot = withContext(Dispatchers.IO) { load() }
                }
                if (!MobileTinaConnectionGate.accepts(token)) return@launch
                if (snapshot.expired) { fail(R.string.mobiletina_expired); return@launch }
                val profiles = snapshot.profiles.filterNot(MobileTinaSubscriptionExpiry::isMarker)
                if (profiles.isEmpty()) { fail(R.string.no_proxies_found); return@launch }
                val best = withContext(Dispatchers.IO) {
                    MobileTinaSmartConnect.selectBest(profiles, DataStore.selectedProxy) {
                        MobileTinaConnectionGate.accepts(token)
                    }
                }
                if (!MobileTinaConnectionGate.accepts(token)) return@launch
                if (best == null) { fail(R.string.mobiletina_no_working_server); return@launch }
                val current = withContext(Dispatchers.IO) { AppData.profiles.getById(best.id) }
                if (!MobileTinaConnectionGate.accepts(token)) return@launch
                if (current == null || current.groupId != snapshot.groupId) { fail(R.string.no_proxies_found); return@launch }
                DataStore.selectedProxy = best.id
                DataStore.selectedGroup = best.groupId
                mutable.value = mutable.value.copy(testing = false, profileName = best.displayName(), ping = best.ping)
                starts.send(token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (MobileTinaConnectionGate.accepts(token)) fail(R.string.mobiletina_no_working_server)
            }
        }
    }

    fun onPermissionResult(denied: Boolean) {
        if (!MobileTinaConnectionGate.accepts(generation)) return
        if (denied) { fail(R.string.vpn_permission_denied); return }
        permissionAccepted = true
        watchdog?.cancel()
        if (DataStore.serviceState.connected) return
        val token = generation
        watchdog = viewModelScope.launch {
            delay(15_000L)
            if (MobileTinaConnectionGate.accepts(token) && !DataStore.serviceState.connected) {
                cancelPending(stop = true)
                fail(R.string.mobiletina_start_timeout)
            }
        }
    }

    fun onServiceState(service: BaseService.State, message: String?) {
        if (service == BaseService.State.Connecting) sawStarting = true
        if (service.connected || (service == BaseService.State.Stopped && permissionAccepted && sawStarting) || message != null) {
            watchdog?.cancel()
            if (message != null) {
                attempt?.cancel()
                MobileTinaConnectionGate.cancel()
            }
            mutable.value = mutable.value.copy(pending = false, testing = false, failed = message != null, message = if (service.connected) "" else mutable.value.message)
        }
        mutable.value = mutable.value.copy(service = service)
        refresh()
    }

    fun cancelPending(stop: Boolean = false) {
        val starting = mutable.value.pending && permissionAccepted
        val stopToken = MobileTinaConnectionGate.begin()
        generation = 0
        attempt?.cancel()
        watchdog?.cancel()
        permissionAccepted = false
        mutable.value = mutable.value.copy(pending = false, testing = false, failed = false, message = "")
        if (stop && (starting || DataStore.serviceState.canStop || DataStore.serviceState == BaseService.State.Stopping)) {
            SagerNet.stopService()
            viewModelScope.launch {
                for (backoff in listOf(300L, 900L, 1_800L)) {
                    delay(backoff)
                    if (!MobileTinaConnectionGate.accepts(stopToken)) return@launch
                    if (DataStore.serviceState.canStop) SagerNet.stopService()
                }
            }
        }
    }

    fun fail(message: Int) {
        MobileTinaConnectionGate.cancel()
        watchdog?.cancel()
        permissionAccepted = false
        mutable.value = mutable.value.copy(pending = false, testing = false, failed = true,
            message = getApplication<Application>().getString(message))
    }

    private fun hasNetwork(): Boolean =
        (getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork != null

    override suspend fun groupAdd(group: io.nekohasekai.sagernet.database.ProxyGroup) = refresh()
    override suspend fun groupUpdated(group: io.nekohasekai.sagernet.database.ProxyGroup) = refresh()
    override suspend fun groupRemoved(groupId: Long) = refresh()
    override suspend fun groupUpdated(groupId: Long) = refresh()

    override fun onCleared() {
        GroupManager.removeListener(this)
        if (mutable.value.pending) cancelPending(stop = true)
        starts.close()
        super.onCleared()
    }
}
