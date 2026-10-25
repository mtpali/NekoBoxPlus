package io.nekohasekai.sagernet.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.workDataOf
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.utils.MobileTinaImportNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * One verification stream per revision, one deadline alarm and one durable fallback.
 * There is no foreground polling/timer and no parallel foreground retirement path.
 * PublicDatabase is never accessed while a SagerDatabase transaction is held.
 */
internal object MobileTinaSubscriptionExpiry {
    const val ACTION_DATA_CHANGED = "com.nb4a.mobiletina.EXPIRY_DATA_CHANGED"
    private val app get() = SagerNet.application
    private val prefs by lazy { MobileTinaExpiryStore(app) }
    private val mutations = Mutex()
    private val clockMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Logs.w(e) })
    private val processToken = UUID.randomUUID().toString()
    private val recovering = java.util.concurrent.atomic.AtomicBoolean(false)
    private data class Clock(val epoch: Long, val elapsed: Long, val boot: Int)
    @Volatile private var clock: Clock? = null
    private data class Record(val group: Long, val trigger: Long, val link: String, val updated: Int) {
        val revision: String get() = digest("$group|$trigger|$link|$updated")
        val tag: String get() = "mobiletina.expiry.v104.$group.$revision"
    }
    private fun bootCount(): Int = if (Build.VERSION.SDK_INT >= 24)
        android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1) else -1

    /** Called only from IO. Painting the home screen never reads the database/boot settings. */
    private fun trustedNow(): Long? {
        val elapsed = SystemClock.elapsedRealtime()
        val boot = bootCount()
        clock?.takeIf { it.boot == boot && elapsed >= it.elapsed }?.let { return it.epoch + elapsed - it.elapsed }
        val savedElapsed = prefs.getLong("clock.elapsed", -1)
        val epoch = prefs.getLong("clock.epoch", 0)
        val sameBoot = if (boot >= 0) prefs.getInt("clock.boot", -1) == boot
            else prefs.getString("clock.process", "") == processToken
        if (!sameBoot || savedElapsed < 0 || epoch <= 0 || elapsed < savedElapsed) return null
        clock = Clock(epoch, savedElapsed, boot)
        return epoch + elapsed - savedElapsed
    }

    fun nowMillis(): Long = clock?.let {
        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed >= it.elapsed) it.epoch + elapsed - it.elapsed else null
    } ?: System.currentTimeMillis()

    private fun rememberTime(epoch: Long) {
        val elapsed = SystemClock.elapsedRealtime()
        val boot = bootCount()
        prefs.edit().putLong("clock.epoch", epoch).putLong("clock.elapsed", elapsed)
            .putInt("clock.boot", boot).putString("clock.process", processToken).apply()
        clock = Clock(epoch, elapsed, boot)
    }

    private suspend fun verifiedTime(): Long? = clockMutex.withLock {
        trustedNow()?.let { return@withLock it }
        val boot = bootCount()
        val sameBoot = if (boot >= 0) prefs.getInt("clock.failed.boot", -2) == boot
            else prefs.getString("clock.failed.process", "") == processToken
        if (!MobileTinaExpirySchedulePolicy.mayFetchTime(SystemClock.elapsedRealtime(),
                prefs.getLong("clock.failed.elapsed", -1), sameBoot)) return@withLock null
        val time = fetchTime()
        if (time != null) rememberTime(time)
        else prefs.edit().putLong("clock.failed.elapsed", SystemClock.elapsedRealtime())
            .putInt("clock.failed.boot", boot).putString("clock.failed.process", processToken).apply()
        time
    }

    private fun readRecord(groupId: Long): Record? = prefs.record(groupId)?.let {
        Record(groupId, it.first, it.second, it.third)
    }
    private fun validRecord(groupId: Long): Record? {
        val record = readRecord(groupId) ?: return null
        val group = AppData.groups.getById(groupId) ?: return null
        return record.takeIf { it.link == digest(group.subscription?.link.orEmpty()) }
    }
    fun expiresAt(groupId: Long): Long? = validRecord(groupId)?.trigger
    fun isVerifiedExpired(trigger: Long): Boolean = trigger == 1L || trustedNow()?.let { it >= trigger } == true
    fun isMarker(profile: ProxyEntity): Boolean = MobileTinaExpiryRetirement.isMarker(profile)

    internal fun parseDate(value: String): Long? {
        val text = value.trim()
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text.replace(' ', 'T')).atZone(ZoneId.of("Asia/Tehran")).toInstant().toEpochMilli() }.getOrNull()
    }
    internal fun commentExpiry(payload: String): Pair<Boolean, Long?> {
        for (candidate in MobileTinaImportNormalizer.payloadCandidates(payload)) {
            val json = runCatching { JsonParser.parseString(candidate).asJsonObject }.getOrNull() ?: continue
            if (!json.has("outbounds")) continue
            val comment = json.get("_comment")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            if (comment.isNullOrBlank()) return true to null
            return (parseDate(comment) ?: return false to null).let { true to it }
        }
        return false to null
    }

    private fun alarmIntent(groupId: Long, trigger: Long, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        PendingIntent.getBroadcast(app, 0, Intent(app, MobileTinaExpiryReceiver::class.java)
            .setData(Uri.parse("mobiletina-expiry://group/$groupId/$trigger"))
            .putExtra("group", groupId).putExtra("trigger", trigger), flags or PendingIntent.FLAG_IMMUTABLE)

    private fun cancelAlarm(groupId: Long, trigger: Long) {
        runCatching {
            val manager = app.getSystemService(AlarmManager::class.java)
            alarmIntent(groupId, trigger, PendingIntent.FLAG_NO_CREATE)?.let { manager.cancel(it); it.cancel() }
            val legacy = PendingIntent.getBroadcast(app, 0, Intent(app, MobileTinaExpiryReceiver::class.java)
                .setData(Uri.parse("mobiletina-expiry://group/$groupId")), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            legacy?.let { manager.cancel(it); it.cancel() }
        }.onFailure(Logs::w)
    }
    private fun workManager() = RemoteWorkManager.getInstance(app)
    private fun cancelLegacy(groupId: Long, trigger: Long) {
        cancelAlarm(groupId, trigger)
        // Old 102/103 work must be cancelled once on upgrade, not allowed to revive a retry loop.
        workManager().cancelAllWorkByTag("mobiletina.expiry.$groupId").get(5, TimeUnit.SECONDS)
        workManager().cancelUniqueWork("mobiletina.expiry.$groupId").get(5, TimeUnit.SECONDS)
    }
    private fun cancelScheduled(record: Record) {
        cancelAlarm(record.group, record.trigger)
        workManager().cancelAllWorkByTag(record.tag).get(5, TimeUnit.SECONDS)
    }

    private fun requestVerification(record: Record) {
        if (readRecord(record.group) != record) return
        val requireNetwork = record.trigger != 1L && trustedNow() == null
        val builder = OneTimeWorkRequestBuilder<MobileTinaExpiryWorker>()
            .addTag(record.tag)
            .setInputData(workDataOf("group" to record.group, "trigger" to record.trigger, "revision" to record.revision))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MobileTinaExpirySchedulePolicy.RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS)
        if (requireNetwork) builder.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        workManager().enqueueUniqueWork("mobiletina.expiry.v104.verify.${record.group}.${record.revision}",
            ExistingWorkPolicy.KEEP, listOf(builder.build())).get(5, TimeUnit.SECONDS)
    }
    private fun scheduleDeadline(record: Record, trustedEpoch: Long) {
        if (readRecord(record.group) != record) return
        val wait = (record.trigger - trustedEpoch).coerceAtLeast(1_000L)
        runCatching {
            val manager = app.getSystemService(AlarmManager::class.java)
            val at = SystemClock.elapsedRealtime() + wait
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms())
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent(record.group, record.trigger)!!)
            else manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent(record.group, record.trigger)!!)
        }.onFailure(Logs::w)
        // Only a verification worker schedules this lightweight fallback, never itself.
        val fallback = OneTimeWorkRequestBuilder<MobileTinaExpiryWorker>().addTag(record.tag)
            .setInputData(workDataOf("group" to record.group, "trigger" to record.trigger,
                "revision" to record.revision, "deadline" to true))
            .setInitialDelay(wait, TimeUnit.MILLISECONDS).build()
        workManager().enqueueUniqueWork("mobiletina.expiry.v104.deadline.${record.group}.${record.revision}",
            ExistingWorkPolicy.REPLACE, listOf(fallback)).get(5, TimeUnit.SECONDS)
    }

    suspend fun syncPayload(groupId: Long, payload: String) = withContext(Dispatchers.IO) {
        val (recognized, parsed) = commentExpiry(payload)
        val group = AppData.groups.getById(groupId) ?: return@withContext
        val marker = AppData.profiles.getByGroup(groupId).any(::isMarker)
        if (!recognized && !marker) return@withContext
        val trigger = parsed ?: if (marker) 1L else null
        mutations.withLock {
            val old = readRecord(groupId)
            val next = trigger?.let { Record(groupId, it, digest(group.subscription?.link.orEmpty()), group.subscription?.lastUpdated ?: 0) }
            if (old == next) return@withLock
            old?.let(::cancelScheduled)
            cancelLegacy(groupId, old?.trigger ?: 0)
            val edit = prefs.edit()
            if (next == null) edit.remove("expiry.$groupId").remove("link.$groupId").remove("updated.$groupId").apply()
            else {
                edit.putLong("expiry.$groupId", next.trigger).putString("link.$groupId", next.link)
                    .putInt("updated.$groupId", next.updated).apply()
                requestVerification(next)
            }
        }
    }

    suspend fun cancel(groupId: Long) = withContext(Dispatchers.IO) {
        mutations.withLock {
            val record = readRecord(groupId)
            record?.let(::cancelScheduled)
            cancelLegacy(groupId, record?.trigger ?: 0)
            prefs.edit().remove("expiry.$groupId").remove("link.$groupId").remove("updated.$groupId").apply()
        }
    }
    suspend fun clearConfigurations(groupIds: List<Long>, clearDatabase: () -> Unit) = withContext(Dispatchers.IO) {
        groupIds.forEach { cancel(it) }
        mutations.withLock { clearDatabase() }
    }

    fun recover() {
        if (!recovering.compareAndSet(false, true)) return
        scope.launch {
            val legacy = prefs.getInt("scheduler.v104", 0) != 1
            val ids = prefs.all.keys.filter { it.startsWith("expiry.") }.mapNotNull { it.substringAfter('.').toLongOrNull() }
            for (id in ids) {
                val record = readRecord(id) ?: continue
                if (legacy) cancelLegacy(id, record.trigger)
                val group = AppData.groups.getById(id)
                val profiles = if (group != null) AppData.profiles.getByGroup(id) else emptyList()
                if (group == null || record.link != digest(group.subscription?.link.orEmpty()) ||
                    MobileTinaExpirySchedulePolicy.isTerminalMarker(group.type == GroupType.BASIC, profiles.size, profiles.singleOrNull()?.let(::isMarker) == true)) {
                    cancelScheduled(record)
                    prefs.forget(id, record.trigger, record.link, record.updated)
                    continue
                }
                val now = trustedNow()
                if (now != null && now < record.trigger) scheduleDeadline(record, now)
                else requestVerification(record)
            }
            prefs.edit().putInt("scheduler.v104", 1).apply()
        }
    }
    suspend fun onAlarm(groupId: Long, trigger: Long) = withContext(Dispatchers.IO) {
        validRecord(groupId)?.takeIf { it.trigger == trigger }?.let(::requestVerification)
    }
    suspend fun canStart(profileId: Long): Boolean = withContext(Dispatchers.IO) {
        val profile = AppData.profiles.getById(profileId) ?: return@withContext true
        if (isMarker(profile)) return@withContext false
        val deadline = expiresAt(profile.groupId) ?: return@withContext true
        val now = trustedNow() ?: return@withContext true
        deadline > now
    }

    suspend fun verify(groupId: Long, trigger: Long, revision: String, deadline: Boolean): Boolean = withContext(Dispatchers.IO) {
        val record = validRecord(groupId)?.takeIf { it.trigger == trigger && it.revision == revision } ?: return@withContext true
        if (deadline) { requestVerification(record); return@withContext true }
        if (GroupUpdater.updating.contains(groupId)) return@withContext false
        val now = if (trigger == 1L) Long.MAX_VALUE else verifiedTime() ?: return@withContext false
        if (now < trigger) { scheduleDeadline(record, now); return@withContext true }
        if (readRecord(groupId) != record) return@withContext true
        val activeGroup = AppData.profiles.getById(DataStore.currentProfile)?.groupId
        if (activeGroup == groupId && !confirmStopped()) return@withContext false
        val replacement = mutations.withLock {
            currentCoroutineContext().ensureActive()
            if (readRecord(groupId) != record) return@withLock null
            // All PublicDatabase reads occur BEFORE entering this profile transaction.
            val marker = SOCKSBean().applyDefaultValues().apply {
                serverAddress = "1"; serverPort = 1; username = ""; password = ""
                name = app.getString(R.string.mobiletina_expired)
            }
            MobileTinaExpiryRetirement.retire(groupId, record.link, record.updated, marker)
        }
        // Compare-and-remove cannot erase a renewed revision; do not cancel this running worker.
        if (prefs.forget(groupId, record.trigger, record.link, record.updated)) {
            cancelAlarm(groupId, trigger)
            workManager().cancelUniqueWork("mobiletina.expiry.v104.deadline.$groupId.$revision")
        }
        if (replacement == null) return@withContext true
        if (DataStore.selectedGroup == groupId) DataStore.selectedProxy = replacement.id
        if (activeGroup == groupId) DataStore.currentProfile = 0
        app.sendBroadcast(Intent(ACTION_DATA_CHANGED).setPackage(app.packageName).putExtra("group", groupId))
        scope.launch { SubscriptionUpdater.reconfigureUpdater() }
        withContext(Dispatchers.Main) { Toast.makeText(app, R.string.mobiletina_expired, Toast.LENGTH_LONG).show() }
        true
    }

    private suspend fun confirmStopped(): Boolean {
        // Verification runs in WorkManager's :bg process, which owns the real service state.
        // Do not bind/start a service just to find out that the VPN was already off.
        if (!app.isBgProcess) return false
        if (DataStore.serviceState == BaseService.State.Idle || DataStore.serviceState == BaseService.State.Stopped) return true
        SagerNet.stopService()
        return withTimeoutOrNull(15_000L) {
            while (DataStore.serviceState != BaseService.State.Stopped && DataStore.serviceState != BaseService.State.Idle) delay(100)
            true
        } == true
    }
    private suspend fun fetchTime(): Long? {
        for (source in listOf("https://www.gstatic.com/generate_204", "https://www.cloudflare.com/cdn-cgi/trace", "https://api.github.com/zen")) {
            currentCoroutineContext().ensureActive()
            val date = runCatching {
                val connection = URL(source + "?mobiletina_clock=" + SystemClock.elapsedRealtime()).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 3_000; connection.readTimeout = 3_000
                    connection.requestMethod = "GET"; connection.useCaches = false
                    connection.setRequestProperty("Cache-Control", "no-cache, no-store")
                    if (connection.responseCode !in 200..399) null
                    else connection.getHeaderFieldDate("Date", -1L).takeIf { it in 1_704_067_200_000L..4_102_444_800_000L }
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
        val revision = inputData.getString("revision")
        // Persisted 102/103 requests are inert, even if cancellation races with their execution.
        if (revision.isNullOrBlank()) Result.success()
        else if (MobileTinaSubscriptionExpiry.verify(inputData.getLong("group", 0), inputData.getLong("trigger", 0),
                revision, inputData.getBoolean("deadline", false))) Result.success() else Result.retry()
    } catch (e: CancellationException) { throw e }
      catch (e: Exception) { Logs.w(e); Result.retry() }
}
