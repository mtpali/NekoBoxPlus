package io.nekohasekai.sagernet.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.utils.MobileTinaImportNormalizer
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Expiration is scoped to its source group. No global database reset is used. */
internal object MobileTinaSubscriptionExpiry {
    const val ACTION_DATA_CHANGED = "com.nb4a.mobiletina.EXPIRY_DATA_CHANGED"
    private val app get() = SagerNet.application
    private val prefs by lazy { MobileTinaExpiryStore(app) }
    private val mutex = Mutex()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val timers = mutableMapOf<Long, Pair<Long, Runnable>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> Logs.w(error) })
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    private fun trustedNow(): Long? {
        val epoch = prefs.getLong("clock.epoch", 0)
        val elapsed = prefs.getLong("clock.elapsed", -1)
        val now = SystemClock.elapsedRealtime()
        val boot = bootCount()
        val sameClock = if (boot >= 0) prefs.getInt("clock.boot", -1) == boot
            else prefs.getString("clock.process", "") == clockProcess
        return if (sameClock && epoch > 0 && elapsed >= 0 && now >= elapsed) epoch + now - elapsed else null
    }

    private fun rememberTime(time: Long) {
        prefs.edit().putLong("clock.epoch", time).putLong("clock.elapsed", SystemClock.elapsedRealtime())
            .putInt("clock.boot", bootCount()).putString("clock.process", clockProcess).apply()
    }

    private fun alarmIntent(groupId: Long, trigger: Long): PendingIntent = PendingIntent.getBroadcast(app, 0,
        Intent(app, MobileTinaExpiryReceiver::class.java).setData(Uri.parse("mobiletina-expiry://group/$groupId/$trigger"))
            .putExtra("group", groupId).putExtra("trigger", trigger),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun cancelAlarm(groupId: Long, trigger: Long = prefs.getLong("expiry.$groupId", 0)) {
        runCatching {
            val alarm = app.getSystemService(AlarmManager::class.java)
            alarm.cancel(alarmIntent(groupId, trigger))
            // Cancel the v102 identity as well when upgrading an already scheduled group.
            val legacy = PendingIntent.getBroadcast(app, 0,
                Intent(app, MobileTinaExpiryReceiver::class.java).setData(Uri.parse("mobiletina-expiry://group/$groupId")),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            legacy?.let(alarm::cancel)
        }.onFailure(Logs::w)
        handler.post {
            timers[groupId]?.takeIf { it.first == trigger }?.let { (_, runnable) ->
                handler.removeCallbacks(runnable); timers.remove(groupId)
            }
        }
    }

    private fun requestVerification(groupId: Long, trigger: Long) {
        if (expiresAt(groupId) != trigger) return
        val work = OneTimeWorkRequestBuilder<MobileTinaExpiryWorker>()
            .addTag("mobiletina.expiry.$groupId")
            .setInputData(workDataOf("group" to groupId, "trigger" to trigger))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        RemoteWorkManager.getInstance(app).enqueueUniqueWork(
            "mobiletina.expiry.verify.$groupId.$trigger", ExistingWorkPolicy.KEEP, listOf(work))
    }

    /** A broadcast only queues work; it never waits for network or VPN shutdown. */
    suspend fun onAlarm(groupId: Long, trigger: Long) = withContext(Dispatchers.IO) {
        try { requestVerification(groupId, trigger) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Logs.w(e) }
    }

    private suspend fun verifyForeground(groupId: Long, trigger: Long) {
        if (!inFlight.add(groupId)) return
        try {
            val done = try { withTimeoutOrNull(45_000L) { retire(groupId, trigger) } == true }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Logs.w(e); false }
            if (!done && expiresAt(groupId) == trigger) schedule(groupId, trigger, retry = true)
        } finally { inFlight.remove(groupId) }
    }
    private val clockProcess = java.util.UUID.randomUUID().toString()

    private fun bootCount(): Int = if (android.os.Build.VERSION.SDK_INT >= 24) {
        android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
    } else -1

    fun isMarker(profile: ProxyEntity): Boolean = profile.socksBean?.let {
        it.serverAddress == "1" && it.serverPort == 1 && it.username.isNullOrEmpty() && it.password.isNullOrEmpty()
    } == true

    fun hasSchedules(): Boolean = prefs.all.keys.any { it.startsWith("expiry.") }

    /** Serialize a full database cleanup with an already-running expiry worker. */
    suspend fun clearConfigurations(groupIds: List<Long>, clearDatabase: () -> Unit) {
        mutex.withLock {
            val edit = prefs.edit()
            groupIds.forEach { id ->
                cancelAlarm(id)
                edit.remove("expiry.$id").remove("link.$id").remove("updated.$id")
                RemoteWorkManager.getInstance(app).cancelUniqueWork("mobiletina.expiry.$id")
                RemoteWorkManager.getInstance(app).cancelAllWorkByTag("mobiletina.expiry.$id")
            }
            edit.apply()
            clearDatabase()
        }
    }

    fun expiresAt(groupId: Long): Long? {
        val trigger = prefs.getLong("expiry.$groupId", 0).takeIf { it > 0 } ?: return null
        val group = AppData.groups.getById(groupId) ?: return null
        if (digest(group.subscription?.link.orEmpty()) != prefs.getString("link.$groupId", "")) return null
        return trigger
    }

    fun nowMillis(): Long {
        val epoch = prefs.getLong("clock.epoch", 0)
        val elapsed = prefs.getLong("clock.elapsed", -1)
        val now = SystemClock.elapsedRealtime()
        val boot = bootCount()
        val sameClock = if (boot >= 0) prefs.getInt("clock.boot", -1) == boot
            else prefs.getString("clock.process", "") == clockProcess
        return if (sameClock && epoch > 0 && elapsed >= 0 && now >= elapsed) epoch + now - elapsed
        else maxOf(System.currentTimeMillis(), epoch)
    }

    internal fun commentExpiry(payload: String): Pair<Boolean, Long?> {
        for (candidate in MobileTinaImportNormalizer.payloadCandidates(payload)) {
            val json = runCatching { JsonParser.parseString(candidate).asJsonObject }.getOrNull() ?: continue
            if (!json.has("outbounds")) continue
            val comment = json.get("_comment")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            if (comment.isNullOrBlank()) return true to null
            val parsed = parseDate(comment) ?: return false to null
            return true to parsed
        }
        return false to null
    }

    internal fun parseDate(value: String): Long? {
        val text = value.trim()
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text.replace(' ', 'T')).atZone(ZoneId.of("Asia/Tehran")).toInstant().toEpochMilli() }.getOrNull()
    }

    suspend fun syncPayload(groupId: Long, payload: String) {
        val group = AppData.groups.getById(groupId) ?: return
        val marker = AppData.profiles.getByGroup(groupId).any(::isMarker)
        val (recognized, parsed) = commentExpiry(payload)
        if (!recognized && !marker) return
        val trigger = parsed ?: if (marker) 1L else null
        mutex.withLock {
            val previousTrigger = prefs.getLong("expiry.$groupId", 0)
            if (previousTrigger > 0L && previousTrigger != trigger) cancelAlarm(groupId, previousTrigger)
            val edit = prefs.edit()
            if (trigger == null) {
                cancelAlarm(groupId)
                edit.remove("expiry.$groupId").remove("link.$groupId").remove("updated.$groupId").apply()
                RemoteWorkManager.getInstance(app).cancelUniqueWork("mobiletina.expiry.$groupId")
                RemoteWorkManager.getInstance(app).cancelAllWorkByTag("mobiletina.expiry.$groupId")
            } else {
                edit.putLong("expiry.$groupId", trigger)
                    .putString("link.$groupId", digest(group.subscription?.link.orEmpty()))
                    .putInt("updated.$groupId", group.subscription?.lastUpdated ?: 0).apply()
                schedule(groupId, trigger)
                requestVerification(groupId, trigger)
            }
        }
    }

    fun recover() {
        scope.launch {
            prefs.all.keys.filter { it.startsWith("expiry.") }.forEach { key ->
                val id = key.substringAfter('.').toLongOrNull() ?: return@forEach
                val trigger = expiresAt(id) ?: return@forEach
                schedule(id, trigger)
                requestVerification(id, trigger)
            }
        }
    }

    private fun schedule(groupId: Long, trigger: Long, retry: Boolean = false, fromDeadlineWorker: Boolean = false) {
        if (expiresAt(groupId) != trigger) return
        val wait = MobileTinaExpirySchedulePolicy.delayMillis(trigger, nowMillis(), retry)
        handler.post {
            if (prefs.getLong("expiry.$groupId", 0) != trigger) return@post
            timers.remove(groupId)?.second?.let(handler::removeCallbacks)
            val runnable = Runnable { scope.launch { verifyForeground(groupId, trigger) } }
            timers[groupId] = trigger to runnable
            handler.postDelayed(runnable, wait)
        }
        runCatching {
            val alarm = app.getSystemService(AlarmManager::class.java)
            val at = SystemClock.elapsedRealtime() + wait
            if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent(groupId, trigger))
            } else alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent(groupId, trigger))
        }.onFailure(Logs::w)
        val work = OneTimeWorkRequestBuilder<MobileTinaExpiryWorker>()
            .addTag("mobiletina.expiry.$groupId")
            .setInputData(workDataOf("group" to groupId, "trigger" to trigger, "deadline" to true))
            .setInitialDelay(wait, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        // A running deadline worker may discover a corrected, later trusted deadline.
        // Append its successor; replacing itself would cancel its own coroutine.
        RemoteWorkManager.getInstance(app).enqueueUniqueWork("mobiletina.expiry.deadline.$groupId.$trigger",
            if (fromDeadlineWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, listOf(work))
    }

    fun isVerifiedExpired(trigger: Long): Boolean = trigger == 1L || trustedNow()?.let { it >= trigger } == true

    suspend fun canStart(profileId: Long): Boolean = withContext(Dispatchers.IO) {
        val profile = AppData.profiles.getById(profileId) ?: return@withContext true // upstream handles a missing profile
        if (isMarker(profile)) return@withContext false
        val deadline = expiresAt(profile.groupId) ?: return@withContext true
        val verifiedNow = trustedNow() ?: return@withContext true
        deadline > verifiedNow
    }

    private data class Retirement(val replacement: ProxyEntity?, val changed: Boolean, val retry: Boolean = false)

    suspend fun retire(groupId: Long, trigger: Long, fromDeadlineWorker: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (expiresAt(groupId) != trigger) return@withContext true
        val initial = AppData.groups.getById(groupId) ?: return@withContext true
        val existingBefore = AppData.profiles.getByGroup(groupId)
        if (MobileTinaExpirySchedulePolicy.isTerminalMarker(initial.type == GroupType.BASIC,
                existingBefore.size, existingBefore.singleOrNull()?.let(::isMarker) == true)) {
            if (prefs.forget(groupId, trigger)) cancelAlarm(groupId, trigger)
            return@withContext true
        }
        if (GroupUpdater.updating.contains(groupId)) return@withContext false
        val serverTime = if (trigger == 1L) null else trustedNow() ?: fetchTime()
        if (serverTime == null && trigger != 1L) return@withContext false
        if (serverTime != null) rememberTime(serverTime)
        if (nowMillis() < trigger) {
            schedule(groupId, trigger, fromDeadlineWorker = fromDeadlineWorker)
            return@withContext true
        }
        val activeGroup = AppData.profiles.getById(DataStore.currentProfile)?.groupId
        if (activeGroup == groupId && !confirmStopped(groupId)) return@withContext false
        val result = mutex.withLock {
            currentCoroutineContext().ensureActive()
            AppData.transactions.run {
                val group = AppData.groups.getById(groupId)
                    ?: return@run Retirement(null, false)
                if (expiresAt(groupId) != trigger) return@run Retirement(null, false)
                if ((group.subscription?.lastUpdated ?: 0) != prefs.getInt("updated.$groupId", 0))
                    return@run Retirement(null, false, retry = true)
                val existing = AppData.profiles.getByGroup(groupId)
                if (MobileTinaExpirySchedulePolicy.isTerminalMarker(group.type == GroupType.BASIC,
                        existing.size, existing.singleOrNull()?.let(::isMarker) == true))
                    return@run Retirement(existing.single(), false)
                val marker = existing.firstOrNull(::isMarker)?.socksBean ?: SOCKSBean().applyDefaultValues().apply {
                    serverAddress = "1"; serverPort = 1; username = ""; password = ""
                    name = app.getString(R.string.mobiletina_expired)
                }
                val replacement = ProxyEntity(groupId = groupId, userOrder = 1L).apply { putBean(marker) }
                AppData.profiles.deleteByGroup(groupId)
                group.type = GroupType.BASIC
                group.subscription = if (group.subscription?.protectedContent == true) {
                    io.nekohasekai.sagernet.database.SubscriptionBean().applyDefaultValues().apply {
                        protectedContent = true; autoUpdate = false
                    }
                } else null
                AppData.groups.updateGroup(group)
                replacement.id = AppData.profiles.addProxy(replacement)
                Retirement(replacement, true)
            }
        }
        if (result.retry) return@withContext false
        if (prefs.forget(groupId, trigger)) cancelAlarm(groupId, trigger)
        // Do not cancel the currently executing verification worker. Its removed trigger
        // makes any already queued duplicate finish as a no-op (as in Exclave).
        if (!result.changed) return@withContext true
        val replacement = result.replacement ?: return@withContext true
        if (DataStore.selectedGroup == groupId) DataStore.selectedProxy = replacement.id
        if (activeGroup == groupId) DataStore.currentProfile = 0
        // Listener code can switch dispatcher or await a UI lifecycle. Never hold the mutation lock here.
        if (app.isBgProcess) {
            app.sendBroadcast(Intent(ACTION_DATA_CHANGED).setPackage(app.packageName).putExtra("group", groupId))
        }
        scope.launch {
            GroupManager.postReload(groupId)
            SubscriptionUpdater.reconfigureUpdater()
        }
        withContext(Dispatchers.Main) { Toast.makeText(app, R.string.mobiletina_expired, Toast.LENGTH_LONG).show() }
        true
    }

    private suspend fun confirmStopped(groupId: Long): Boolean = withContext(Dispatchers.Main) {
        val stopped = CompletableDeferred<Boolean>()
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_RESTART_BG)
        val callback = object : SagerConnection.Callback {
            override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
                if (state == BaseService.State.Stopped) stopped.complete(true)
            }
            override fun onServiceConnected(service: ISagerNetService) {
                if (BaseService.State.values().getOrNull(service.state) == BaseService.State.Stopped) stopped.complete(true)
                else launch {
                    val activeGroup = withContext(Dispatchers.IO) { AppData.profiles.getById(DataStore.currentProfile)?.groupId }
                    if (activeGroup != groupId) stopped.complete(true) else SagerNet.stopService()
                }
            }
        }
        try {
            connection.connect(app, callback)
            withTimeoutOrNull(15_000) { stopped.await() } == true
        } finally { connection.disconnect(app) }
    }

    private fun fetchTime(): Long? {
        for (source in listOf("https://www.gstatic.com/generate_204", "https://www.cloudflare.com/cdn-cgi/trace", "https://api.github.com/zen")) {
            val date = runCatching {
                val connection = URL(source).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 3_000; connection.readTimeout = 3_000; connection.requestMethod = "HEAD"
                    connection.connect()
                    connection.date.takeIf { it in 1_704_067_200_000L..4_102_444_800_000L }
                } finally { connection.disconnect() }
            }.getOrNull()
            if (date != null) return date
        }
        return null
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

class MobileTinaExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (MobileTinaSubscriptionExpiry.retire(inputData.getLong("group", 0), inputData.getLong("trigger", 0),
                fromDeadlineWorker = inputData.getBoolean("deadline", false))) Result.success() else Result.retry()
    } catch (e: CancellationException) { throw e }
      catch (e: Exception) { Logs.w(e); Result.retry() }
}
