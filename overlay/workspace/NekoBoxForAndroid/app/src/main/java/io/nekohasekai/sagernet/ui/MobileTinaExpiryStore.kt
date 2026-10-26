package io.nekohasekai.sagernet.ui

import android.content.Context
import java.util.concurrent.Callable
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase

/** Expiry is shared by the UI and :bg workers. SharedPreferences caches are process-local. */
internal class MobileTinaExpiryStore(context: Context) {
    private val prefix = "mobiletina.expiry.v103."
    private val dao get() = PublicDatabase.kvPairDao

    init {
        PublicDatabase.instance.runInTransaction(Callable {
            if (dao[prefix + "migrated"]?.boolean != true) {
                context.getSharedPreferences("mobiletina.expiry", Context.MODE_PRIVATE).all.forEach { (key, value) ->
                    val item = KeyValuePair(prefix + key)
                    when (value) {
                        is Long -> dao.put(item.put(value))
                        is Int -> dao.put(item.put(value.toLong()))
                        is String -> dao.put(item.put(value))
                    }
                }
                dao.put(KeyValuePair(prefix + "migrated").put(true))
            }
            Unit
        })
        context.getSharedPreferences("mobiletina.expiry", Context.MODE_PRIVATE).edit().clear().apply()
    }

    val all: Map<String, Any?> get() = dao.all().filter { it.key.startsWith(prefix) }
        .associate { it.key.removePrefix(prefix) to (it.long ?: it.string) }
    fun getLong(key: String, default: Long): Long = dao[prefix + key]?.long ?: default
    fun getInt(key: String, default: Int): Int = getLong(key, default.toLong()).toInt()
    fun getString(key: String, default: String): String = dao[prefix + key]?.string ?: default
    fun edit() = Editor()

    inner class Editor {
        private val changes = linkedMapOf<String, Any?>()
        fun putLong(key: String, value: Long) = apply { changes[key] = value }
        fun putInt(key: String, value: Int) = putLong(key, value.toLong())
        fun putString(key: String, value: String) = apply { changes[key] = value }
        fun remove(key: String) = apply { changes[key] = null }
        fun apply() = PublicDatabase.instance.runInTransaction(Callable {
            changes.forEach { (key, value) ->
                when (value) {
                    null -> dao.delete(prefix + key)
                    is Long -> dao.put(KeyValuePair(prefix + key).put(value))
                    is String -> dao.put(KeyValuePair(prefix + key).put(value))
                }
            }
            Unit
        })
    }

    fun record(groupId: Long): Triple<Long, String, Int>? =
        PublicDatabase.instance.runInTransaction(Callable {
            val trigger = getLong("expiry.$groupId", 0)
            if (trigger <= 0L) null else Triple(trigger, getString("link.$groupId", ""), getInt("updated.$groupId", 0))
        })

    fun forget(groupId: Long, expectedTrigger: Long, expectedLink: String, expectedUpdated: Int): Boolean {
        var removed = false
        PublicDatabase.instance.runInTransaction(Callable {
            if (getLong("expiry.$groupId", 0) == expectedTrigger &&
                getString("link.$groupId", "") == expectedLink && getInt("updated.$groupId", 0) == expectedUpdated) {
                listOf("expiry.", "link.", "updated.", "metadata.").forEach { dao.delete(prefix + it + groupId) }
                removed = true
            }
            Unit
        })
        return removed
    }
}

internal object MobileTinaExpirySchedulePolicy {
    const val CURRENT_SCHEDULER = 105
    fun acceptsWork(version: Int, revision: String?): Boolean = version == CURRENT_SCHEDULER && !revision.isNullOrBlank()
    const val RETRY_DELAY_MILLIS = 15 * 60_000L
    fun mayFetchTime(elapsed: Long, lastFailure: Long, sameBoot: Boolean): Boolean =
        !sameBoot || lastFailure < 0L || elapsed < lastFailure || elapsed - lastFailure >= RETRY_DELAY_MILLIS
    fun delayMillis(trigger: Long, now: Long, retry: Boolean = false): Long =
        if (retry) RETRY_DELAY_MILLIS else (trigger - now).coerceAtLeast(0L)
    fun isTerminalMarker(basicGroup: Boolean, profileCount: Int, soleProfileIsMarker: Boolean): Boolean =
        basicGroup && profileCount == 1 && soleProfileIsMarker
}
