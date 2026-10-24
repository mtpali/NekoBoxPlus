package io.nekohasekai.sagernet.ui

import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val app get() = SagerNet.application
    private val prefs get() = app.getSharedPreferences("mobiletina.expiry", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    fun isMarker(profile: ProxyEntity): Boolean = profile.socksBean?.let {
        it.serverAddress == "1" && it.serverPort == 1 && it.username.isNullOrEmpty() && it.password.isNullOrEmpty()
    } == true

    fun hasSchedules(): Boolean = prefs.all.keys.any { it.startsWith("expiry.") }

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
        return if (epoch > 0 && elapsed >= 0 && now >= elapsed) epoch + now - elapsed
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
        val trigger = if (marker) 1L else parsed
        mutex.withLock {
            val edit = prefs.edit()
            if (trigger == null) {
                edit.remove("expiry.$groupId").remove("link.$groupId").remove("updated.$groupId").apply()
                WorkManager.getInstance(app).cancelUniqueWork("mobiletina.expiry.$groupId")
            } else {
                edit.putLong("expiry.$groupId", trigger)
                    .putString("link.$groupId", digest(group.subscription?.link.orEmpty()))
                    .putInt("updated.$groupId", group.subscription?.lastUpdated ?: 0).apply()
                schedule(groupId, trigger)
            }
        }
    }

    fun recover() {
        prefs.all.keys.filter { it.startsWith("expiry.") }.forEach { key ->
            key.substringAfter('.').toLongOrNull()?.let { id -> expiresAt(id)?.let { schedule(id, it) } }
        }
    }

    private fun schedule(groupId: Long, trigger: Long) {
        val work = OneTimeWorkRequestBuilder<MobileTinaExpiryWorker>()
            .setInputData(workDataOf("group" to groupId, "trigger" to trigger))
            .setInitialDelay((trigger - nowMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(app).enqueueUniqueWork("mobiletina.expiry.$groupId", ExistingWorkPolicy.REPLACE, work)
    }

    suspend fun canStart(profileId: Long): Boolean = withContext(Dispatchers.IO) {
        val profile = AppData.profiles.getById(profileId) ?: return@withContext true // upstream handles a missing profile
        if (isMarker(profile)) return@withContext false
        expiresAt(profile.groupId)?.let { it > nowMillis() } ?: true
    }

    suspend fun retire(groupId: Long, trigger: Long): Boolean = withContext(Dispatchers.IO) {
        if (expiresAt(groupId) != trigger) return@withContext true
        val initial = AppData.groups.getById(groupId) ?: return@withContext true
        if (digest(initial.subscription?.link.orEmpty()) != prefs.getString("link.$groupId", "")) return@withContext true
        val serverTime = fetchTime()
        if (serverTime != null) prefs.edit().putLong("clock.epoch", serverTime)
            .putLong("clock.elapsed", SystemClock.elapsedRealtime()).apply()
        if (nowMillis() < trigger) { schedule(groupId, trigger); return@withContext true }

        val activeGroup = AppData.profiles.getById(DataStore.currentProfile)?.groupId
        if (activeGroup == groupId && !confirmStopped()) return@withContext false
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (expiresAt(groupId) != trigger) return@withLock true
            val group = AppData.groups.getById(groupId) ?: return@withLock true
            if (digest(group.subscription?.link.orEmpty()) != prefs.getString("link.$groupId", "")) return@withLock true
            if ((group.subscription?.lastUpdated ?: 0) != prefs.getInt("updated.$groupId", 0)) return@withLock false
            val existing = AppData.profiles.getByGroup(groupId)
            if (group.type == GroupType.BASIC && existing.size == 1 && isMarker(existing.first())) return@withLock true
            val marker = existing.firstOrNull(::isMarker)?.socksBean ?: SOCKSBean().applyDefaultValues().apply {
                serverAddress = "1"; serverPort = 1; username = ""; password = ""
                name = app.getString(R.string.mobiletina_expired)
            }
            val replacement = ProxyEntity(groupId = groupId, userOrder = 1L).apply { putBean(marker) }
            AppData.transactions.run {
                AppData.profiles.deleteByGroup(groupId)
                group.type = GroupType.BASIC
                group.subscription = null
                AppData.groups.updateGroup(group)
                replacement.id = AppData.profiles.addProxy(replacement)
            }
            if (DataStore.selectedGroup == groupId) DataStore.selectedProxy = replacement.id
            if (activeGroup == groupId) DataStore.currentProfile = 0
            prefs.edit().putString("link.$groupId", digest("")).putInt("updated.$groupId", 0).apply()
            GroupManager.postReload(groupId)
            SubscriptionUpdater.reconfigureUpdater()
            true
        }
    }

    private suspend fun confirmStopped(): Boolean = withContext(Dispatchers.Main) {
        val stopped = CompletableDeferred<Boolean>()
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_RESTART_BG)
        val callback = object : SagerConnection.Callback {
            override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
                if (state == BaseService.State.Stopped) stopped.complete(true)
            }
            override fun onServiceConnected(service: ISagerNetService) {
                if (BaseService.State.values().getOrNull(service.state) == BaseService.State.Stopped) stopped.complete(true)
                else SagerNet.stopService()
            }
        }
        try {
            connection.connect(app, callback)
            withTimeoutOrNull(15_000) { stopped.await() } == true
        } finally { connection.disconnect(app) }
    }

    private fun fetchTime(): Long? = runCatching {
        val connection = URL("https://www.gstatic.com/generate_204").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3_000; connection.readTimeout = 3_000; connection.requestMethod = "HEAD"
            connection.connect()
            connection.date.takeIf { it in 1_704_067_200_000L..4_102_444_800_000L }
        } finally { connection.disconnect() }
    }.getOrNull()

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

class MobileTinaExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (MobileTinaSubscriptionExpiry.retire(inputData.getLong("group", 0), inputData.getLong("trigger", 0))) Result.success()
        else Result.retry()
}
