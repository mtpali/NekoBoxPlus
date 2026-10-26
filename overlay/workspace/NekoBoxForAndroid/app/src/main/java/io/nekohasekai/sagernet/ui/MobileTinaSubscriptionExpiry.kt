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
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.workDataOf
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.VpnService
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
import kotlinx.coroutines.Job
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
 * The live service also owns a one-shot deadline; all retirement paths share one mutex.
 * PublicDatabase is never accessed while a SagerDatabase transaction is held.
 */
internal object MobileTinaSubscriptionExpiry {
    const val ACTION_DATA_CHANGED = "com.nb4a.mobiletina.EXPIRY_DATA_CHANGED"
    private val app get() = SagerNet.application
    private val prefs by lazy { MobileTinaExpiryStore(app) }
    private val mutations = Mutex()
    private val clockMutex = Mutex()
    private val verificationMutex = Mutex()
    private val directVerifications = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val liveDeadlines = mutableMapOf<Long, Pair<String, Job>>()
    private val liveLock = Any()
    private var liveGeneration = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Logs.w(e) })
    private val processToken = UUID.randomUUID().toString()
    private val recovering = java.util.concurrent.atomic.AtomicBoolean(false)
    private data class Clock(val epoch: Long, val elapsed: Long, val boot: Int)
    @Volatile private var clock: Clock? = null
    private data class Record(val group: Long, val trigger: Long, val link: String, val updated: Int) {
        val revision: String get() = digest("$group|$trigger|$link|$updated")
        val tag: String get() = "mobiletina.expiry.v105.$group.$revision"
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

    fun nowMillis(): Long? = clock?.let {
        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed >= it.elapsed) it.epoch + elapsed - it.elapsed else null
    }

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

    suspend fun refreshClock() = withContext(Dispatchers.IO) {
        verifiedTime()?.also { recover() }
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
        fun inspect(value: com.google.gson.JsonElement): Pair<Boolean, Long?> {
            if (value.isJsonArray) {
                val entries = value.asJsonArray.map(::inspect)
                val dates = entries.mapNotNull { it.second }
                return when {
                    dates.isNotEmpty() -> true to dates.minOrNull()
                    entries.isNotEmpty() && entries.all { it.first } -> true to null
                    else -> false to null
                }
            }
            if (!value.isJsonObject) return false to null
            val json = value.asJsonObject
            val comment = json.get("_comment")
            if (comment != null && !comment.isJsonNull) {
                if (!comment.isJsonPrimitive || !comment.asJsonPrimitive.isString) return false to null
                val text = comment.asString.trim()
                if (text.isNotEmpty()) return parseDate(text)?.let { true to it } ?: (false to null)
            }
            return if (json.has("outbounds")) true to null else false to null
        }
        for (candidate in MobileTinaImportNormalizer.payloadCandidates(payload)) {
            val json = runCatching { JsonParser.parseString(candidate) }.getOrNull() ?: continue
            val result = inspect(json)
            if (result.first) return result
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
        readRecord(groupId)?.let { old ->
            workManager().cancelAllWorkByTag("mobiletina.expiry.v104.$groupId.${old.revision}").get(5, TimeUnit.SECONDS)
        }
        workManager().cancelAllWorkByTag("mobiletina.expiry.$groupId").get(5, TimeUnit.SECONDS)
        workManager().cancelUniqueWork("mobiletina.expiry.$groupId").get(5, TimeUnit.SECONDS)
    }
    private fun cancelScheduled(record: Record) {
        cancelAlarm(record.group, record.trigger)
        workManager().cancelAllWorkByTag(record.tag).get(5, TimeUnit.SECONDS)
    }

    private fun requestVerification(record: Record, relay: Boolean = true) {
        if (readRecord(record.group) != record) return
        if (relay && !GroupUpdater.updating.contains(record.group)) {
            if (app.isBgProcess) launchDirectVerification(record)
            else app.sendBroadcast(Intent(app, MobileTinaExpiryReceiver::class.java)
                .putExtra("group", record.group).putExtra("trigger", record.trigger))
        }
        val now = trustedNow()
        val requireNetwork = record.trigger != 1L && now == null
        val builder = OneTimeWorkRequestBuilder<MobileTinaExpiryWorker>()
            .addTag(record.tag)
            .setInputData(workDataOf("group" to record.group, "trigger" to record.trigger, "revision" to record.revision, "scheduler" to MobileTinaExpirySchedulePolicy.CURRENT_SCHEDULER))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MobileTinaExpirySchedulePolicy.RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS)
        if (Build.VERSION.SDK_INT >= 31 && (record.trigger == 1L || now?.let { it >= record.trigger } == true)) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        if (requireNetwork) builder.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        workManager().enqueueUniqueWork("mobiletina.expiry.v105.verify.${record.group}.${record.revision}",
            ExistingWorkPolicy.KEEP, listOf(builder.build())).get(5, TimeUnit.SECONDS)
    }
    private suspend fun scheduleDeadline(record: Record, trustedEpoch: Long) {
        if (readRecord(record.group) != record) return
        val wait = (record.trigger - trustedEpoch).coerceAtLeast(1_000L)
        runCatching {
            val manager = app.getSystemService(AlarmManager::class.java)
            val at = SystemClock.elapsedRealtime() + wait
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms())
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent(record.group, record.trigger)!!)
            else manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent(record.group, record.trigger)!!)
        }.onFailure(Logs::w)
        scheduleFallback(record, wait)
        armLiveDeadline(record, wait)
    }

    private fun scheduleFallback(record: Record, wait: Long) {
        if (readRecord(record.group) != record) return
        // This lightweight request can be replaced without cancelling a retirement worker.
        val fallback = OneTimeWorkRequestBuilder<MobileTinaExpiryWorker>().addTag(record.tag)
            .setInputData(workDataOf("group" to record.group, "trigger" to record.trigger,
                "revision" to record.revision, "deadline" to true, "scheduler" to MobileTinaExpirySchedulePolicy.CURRENT_SCHEDULER))
            .setInitialDelay(wait, TimeUnit.MILLISECONDS).build()
        workManager().enqueueUniqueWork("mobiletina.expiry.v105.deadline.${record.group}.${record.revision}",
            ExistingWorkPolicy.REPLACE, listOf(fallback)).get(5, TimeUnit.SECONDS)
    }

    suspend fun deferVerification(groupId: Long, trigger: Long, revision: String) = withContext(Dispatchers.IO) {
        validRecord(groupId)?.takeIf { it.trigger == trigger && it.revision == revision }?.let {
            scheduleFallback(it, MobileTinaExpirySchedulePolicy.RETRY_DELAY_MILLIS)
        }
    }

    suspend fun syncImportedPayloads(groupId: Long, payloads: List<String>) {
        val recognized = payloads.map { it to commentExpiry(it) }.filter { it.second.first }
        val earliest = recognized.filter { it.second.second != null }.minByOrNull { it.second.second!! }
        (earliest ?: recognized.firstOrNull())?.let { syncPayload(groupId, it.first) }
        if (recognized.isEmpty() && AppData.profiles.getByGroup(groupId).any(::isMarker)) syncPayload(groupId, "")
    }

    suspend fun syncPayload(groupId: Long, payload: String) = withContext(Dispatchers.IO) {
        val (recognized, parsed) = commentExpiry(payload)
        val group = AppData.groups.getById(groupId) ?: return@withContext
        val marker = AppData.profiles.getByGroup(groupId).any(::isMarker)
        if (!recognized && !marker) { syncMetadata(groupId); return@withContext }
        val trigger = parsed ?: if (marker) 1L else MobileTinaExpiryConnectionPolicy.metadataDeadline(group.subscription)
        mutations.withLock {
            val old = readRecord(groupId)
            val next = trigger?.let { Record(groupId, it, digest(group.subscription?.link.orEmpty()), group.subscription?.lastUpdated ?: 0) }
            prefs.edit().putInt("metadata.$groupId", if (parsed == null && !marker && trigger != null) 1 else 0).apply()
            if (old == next) return@withLock
            old?.let(::cancelScheduled)
            cancelLegacy(groupId, old?.trigger ?: 0)
            val edit = prefs.edit()
            if (next == null) edit.remove("expiry.$groupId").remove("link.$groupId").remove("updated.$groupId").remove("metadata.$groupId").apply()
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
            prefs.edit().remove("expiry.$groupId").remove("link.$groupId").remove("updated.$groupId").remove("metadata.$groupId").apply()
        }
    }
    suspend fun clearConfigurations(groupIds: List<Long>, clearDatabase: () -> Unit) = withContext(Dispatchers.IO) {
        groupIds.forEach { cancel(it) }
        mutations.withLock { clearDatabase() }
    }

    fun recover() {
        if (!recovering.compareAndSet(false, true)) return
        scope.launch {
          try {
            // Recover JSON profiles imported through older clipboard/file paths that lost metadata.
            if (prefs.getInt("payload-recovery.v105", 0) != 1) {
                for (group in AppData.groups.allGroups()) {
                    if (readRecord(group.id) != null) continue
                    val payloads = AppData.profiles.getByGroup(group.id).mapNotNull { it.configBean?.config }
                        .filter { "_comment" in it }
                    if (payloads.isNotEmpty()) syncPayload(group.id, "[" + payloads.joinToString(",") + "]")
                }
                prefs.edit().putInt("payload-recovery.v105", 1).apply()
            }
            val legacy = prefs.getInt("scheduler.v105", 0) != 1
            AppData.groups.allGroups().forEach { syncMetadata(it.id) }
            val ids = prefs.all.keys.filter { it.startsWith("expiry.") }.mapNotNull { it.substringAfter('.').toLongOrNull() }
            for (id in ids) {
                val record = readRecord(id) ?: continue
                if (legacy) cancelLegacy(id, record.trigger)
                val group = AppData.groups.getById(id)
                if (group == null || record.link != digest(group.subscription?.link.orEmpty())) {
                    cancelScheduled(record)
                    prefs.forget(id, record.trigger, record.link, record.updated)
                    continue
                }
                val now = trustedNow()
                if (now != null && now < record.trigger) scheduleDeadline(record, now)
                else requestVerification(record)
            }
            prefs.edit().putInt("scheduler.v105", 1).apply()
          } finally {
            recovering.set(false)
          }
        }
    }
    suspend fun onAlarm(groupId: Long, trigger: Long) = withContext(Dispatchers.IO) {
        validRecord(groupId)?.takeIf { it.trigger == trigger }?.let {
            launchDirectVerification(it)
            requestVerification(it, relay = false)
        }
    }
    suspend fun canStart(profileId: Long): Boolean = withContext(Dispatchers.IO) {
        val profile = AppData.profiles.getById(profileId) ?: return@withContext true
        syncMetadata(profile.groupId)
        if (isMarker(profile)) { syncPayload(profile.groupId, ""); return@withContext false }
        val deadline = expiresAt(profile.groupId) ?: return@withContext true
        val now = trustedNow() ?: return@withContext true
        if (deadline <= now) validRecord(profile.groupId)?.let { requestVerification(it) }
        deadline > now
    }

    suspend fun verify(groupId: Long, trigger: Long, revision: String, deadline: Boolean): Boolean =
        verificationMutex.withLock { verifyLocked(groupId, trigger, revision, deadline) }

    private suspend fun verifyLocked(groupId: Long, trigger: Long, revision: String, deadline: Boolean): Boolean = withContext(Dispatchers.IO) {
        val record = validRecord(groupId)?.takeIf { it.trigger == trigger && it.revision == revision } ?: return@withContext true
        if (deadline) { requestVerification(record); return@withContext true }
        if (GroupUpdater.updating.contains(groupId)) return@withContext false
        val now = if (trigger == 1L) Long.MAX_VALUE else verifiedTime() ?: return@withContext false
        if (now < trigger) { scheduleDeadline(record, now); return@withContext true }
        if (readRecord(groupId) != record) return@withContext true
        val previousProfileId = DataStore.currentProfile
        val previousProfileGroup = AppData.profiles.getById(previousProfileId)?.groupId
        val selectedProfileGroup = AppData.profiles.getById(DataStore.selectedProxy)?.groupId
        if (!confirmStopped(groupId)) return@withContext false
        // Stopping can suspend; a successful renewal must never be retired by this old revision.
        if (GroupUpdater.updating.contains(groupId)) return@withContext false
        val replacement = mutations.withLock {
            currentCoroutineContext().ensureActive()
            if (readRecord(groupId) != record) return@withLock null
            // All PublicDatabase reads occur BEFORE entering this profile transaction.
            val marker = SOCKSBean().applyDefaultValues().apply {
                serverAddress = "1"; serverPort = 1; username = ""; password = ""
                name = app.getString(R.string.mobiletina_expired)
            }
            MobileTinaExpiryRetirement.retire(groupId, record.link, record.updated, marker, previousProfileId)
        }
        // Compare-and-remove cannot erase a renewed revision; do not cancel this running worker.
        if (prefs.forget(groupId, record.trigger, record.link, record.updated)) {
            cancelAlarm(groupId, trigger)
            workManager().cancelUniqueWork("mobiletina.expiry.v105.deadline.$groupId.$revision")
        }
        if (replacement == null) return@withContext true
        if (DataStore.selectedGroup == groupId || selectedProfileGroup == groupId) DataStore.selectedProxy = replacement.id
        if (previousProfileGroup == groupId && DataStore.currentProfile == previousProfileId) DataStore.currentProfile = 0
        app.sendBroadcast(Intent(ACTION_DATA_CHANGED).setPackage(app.packageName).putExtra("group", groupId))
        postExpiredNotification(groupId)
        scope.launch { SubscriptionUpdater.reconfigureUpdater() }
        withContext(Dispatchers.Main) { Toast.makeText(app, R.string.mobiletina_expired, Toast.LENGTH_LONG).show() }
        true
    }

    private fun postExpiredNotification(groupId: Long) {
        // Disabled notification permission must not retry an already committed retirement.
        runCatching {
            val manager = app.getSystemService(android.app.NotificationManager::class.java)
            val channelId = "mobiletina-expiry-status"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(android.app.NotificationChannel(
                    channelId, app.getString(R.string.mobiletina_expired), android.app.NotificationManager.IMPORTANCE_DEFAULT
                ))
            }
            val openApp = PendingIntent.getActivity(app, 0,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = androidx.core.app.NotificationCompat.Builder(app, channelId)
                .setSmallIcon(R.drawable.mobiletina_notification)
                .setContentTitle(app.getString(R.string.mobiletina_expired))
                .setContentIntent(openApp).setAutoCancel(true).build()
            manager.notify("mobiletina-expiry-$groupId", 10525, notification)
        }.onFailure(Logs::w)
    }

    private suspend fun confirmStopped(groupId: Long): Boolean {
        if (!app.isBgProcess) return false
        val stoppedService = withContext(Dispatchers.Main.immediate) {
            val service = DataStore.baseService ?: return@withContext null
            val data = service.data
            val proxy = data.proxy
            if (!MobileTinaExpiryConnectionPolicy.shouldStop(groupId, data.state,
                    proxy?.profile?.groupId, proxy?.lastSelectorGroupId)) return@withContext null
            // Invalidate pending starts and automatic recovery, then stop this exact live service.
            SagerNet.stopService()
            service.stopRunner()
            service
        } ?: return true
        return withTimeoutOrNull(15_000L) {
            while (withContext(Dispatchers.Main.immediate) {
                    stoppedService.data.state != BaseService.State.Stopped ||
                        (stoppedService as? VpnService)?.hasTunConnection == true
                }) delay(100)
            true
        } == true
    }
    private fun launchDirectVerification(record: Record) {
        if (!app.isBgProcess || !directVerifications.add(record.revision)) return
        // Separate from the deadline Job: service teardown must not cancel final retirement.
        scope.launch {
            try {
                if (!verify(record.group, record.trigger, record.revision, false))
                    deferVerification(record.group, record.trigger, record.revision)
            } finally { directVerifications.remove(record.revision) }
        }
    }

    private suspend fun armLiveDeadline(record: Record, wait: Long) {
        if (!app.isBgProcess) return
        val active = withContext(Dispatchers.Main.immediate) {
            val data = DataStore.baseService?.data ?: return@withContext false
            MobileTinaExpiryConnectionPolicy.shouldStop(record.group, data.state,
                data.proxy?.profile?.groupId, data.proxy?.lastSelectorGroupId)
        }
        if (!active) return
        synchronized(liveLock) {
            if (liveDeadlines[record.group]?.first == record.revision) return
            liveDeadlines.remove(record.group)?.second?.cancel()
            val job = scope.launch {
                delay(wait)
                launchDirectVerification(record)
            }
            liveDeadlines[record.group] = record.revision to job
        }
    }

    fun onServiceStateChanged(service: BaseService.Interface, state: BaseService.State) {
        val generation = synchronized(liveLock) {
            liveDeadlines.values.forEach { it.second.cancel() }
            liveDeadlines.clear()
            ++liveGeneration
        }
        if (!state.started) return
        scope.launch {
            val groups = withContext(Dispatchers.Main.immediate) {
                listOfNotNull(service.data.proxy?.profile?.groupId, service.data.proxy?.lastSelectorGroupId)
                    .filter { it > 0 }.distinct()
            }
            for (group in groups) {
                if (synchronized(liveLock) { generation != liveGeneration }) return@launch
                syncMetadata(group)
                validRecord(group)?.let { requestVerification(it) }
            }
        }
    }

    fun onUpdateFinished(groupId: Long) {
        scope.launch {
            syncMetadata(groupId)
            validRecord(groupId)?.let { requestVerification(it) }
        }
    }

    private suspend fun syncMetadata(groupId: Long) = withContext(Dispatchers.IO) {
        mutations.withLock {
            val group = AppData.groups.getById(groupId) ?: return@withLock
            val old = readRecord(groupId)
            // A JSON _comment or explicit expiry marker has priority over headers.
            if (old != null && prefs.getInt("metadata.$groupId", 0) != 1) return@withLock
            val next = MobileTinaExpiryConnectionPolicy.metadataDeadline(group.subscription)?.let {
                Record(groupId, it, digest(group.subscription?.link.orEmpty()), group.subscription?.lastUpdated ?: 0)
            }
            if (old == next) return@withLock
            old?.let(::cancelScheduled)
            val edit = prefs.edit()
            if (next == null) edit.remove("expiry.$groupId").remove("link.$groupId")
                .remove("updated.$groupId").remove("metadata.$groupId").apply()
            else {
                edit.putLong("expiry.$groupId", next.trigger).putString("link.$groupId", next.link)
                    .putInt("updated.$groupId", next.updated).putInt("metadata.$groupId", 1).apply()
                requestVerification(next)
            }
        }
    }

    private suspend fun fetchTime(): Long? {
        for (source in listOf("https://api.github.com/zen", "https://github.com/robots.txt", "https://www.cloudflare.com/cdn-cgi/trace")) {
            currentCoroutineContext().ensureActive()
            val date = runCatching {
                val connection = URL(source + "?mobiletina_clock=" + SystemClock.elapsedRealtime()).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 3_000; connection.readTimeout = 3_000
                    connection.requestMethod = "HEAD"; connection.useCaches = false
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Cache-Control", "no-cache, no-store")
                    if (connection.responseCode !in 200..499 || (connection.getHeaderField("Age")?.toLongOrNull() ?: 0L) > 5L) null
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
        if (!MobileTinaExpirySchedulePolicy.acceptsWork(inputData.getInt("scheduler", 0), revision)) Result.success()
        else {
            val group = inputData.getLong("group", 0)
            val trigger = inputData.getLong("trigger", 0)
            if (!MobileTinaSubscriptionExpiry.verify(group, trigger, revision!!, inputData.getBoolean("deadline", false))) {
                // Finish this verification. A durable delayed request retries later, while resume
                // or a newly verified clock may enqueue fresh work immediately using KEEP.
                MobileTinaSubscriptionExpiry.deferVerification(group, trigger, revision!!)
            }
            Result.success()
        }
    } catch (e: CancellationException) { throw e }
      catch (e: Exception) { Logs.w(e); Result.retry() }
}
