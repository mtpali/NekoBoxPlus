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

    fun forget(groupId: Long, expectedTrigger: Long): Boolean {
        var removed = false
        PublicDatabase.instance.runInTransaction(Callable {
            if (getLong("expiry.$groupId", 0) == expectedTrigger) {
                listOf("expiry.", "link.", "updated.").forEach { dao.delete(prefix + it + groupId) }
                removed = true
            }
            Unit
        })
        return removed
    }
}

internal object MobileTinaExpirySchedulePolicy {
    const val RETRY_DELAY_MILLIS = 30_000L
    fun delayMillis(trigger: Long, now: Long, retry: Boolean = false): Long =
        if (retry) RETRY_DELAY_MILLIS else (trigger - now).coerceAtLeast(0L)
    fun isTerminalMarker(basicGroup: Boolean, profileCount: Int, soleProfileIsMarker: Boolean): Boolean =
        basicGroup && profileCount == 1 && soleProfileIsMarker
}
