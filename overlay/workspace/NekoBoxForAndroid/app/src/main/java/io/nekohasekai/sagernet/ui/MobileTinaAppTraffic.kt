package io.nekohasekai.sagernet.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import libcore.BoxInstance
import java.io.File

/** The :bg service is the only writer. Work is on demand or once at normal teardown. */
internal object MobileTinaAppTraffic {
    const val KEY = "mobiletinaDevAppTraffic"
    const val ACTION_RESULT = "com.nb4a.mobiletina.APP_TRAFFIC_RESULT"
    private val lock = Any()
    private data class Session(val box: BoxInstance, val period: MobileTinaAppTrafficPeriod)
    private var active: Session? = null
    private val file get() = AtomicFile(File(SagerNet.application.filesDir, "mobiletina-app-traffic.json"))
    val enabled: Boolean get() = DataStore.configurationStore.getBoolean(KEY, false)

    fun prepareConfig(config: String): String {
        val root = JsonParser.parseString(config).asJsonObject
        val route = root.getAsJsonObject("route") ?: JsonObject().also { root.add("route", it) }
        route.addProperty("find_process", true)
        return root.toString()
    }

    fun start(box: BoxInstance) = synchronized(lock) {
        box.enableAppTrafficStats()
        active = Session(box, MobileTinaAppTrafficPeriod(System.currentTimeMillis()))
    }
    private fun raw(session: Session) = MobileTinaAppTrafficModel.rows(session.box.queryAppTrafficStats())

    fun stop(box: BoxInstance) = synchronized(lock) {
        val session = active?.takeIf { it.box === box } ?: return@synchronized
        try { save(session.period.snapshot(raw(session), live = false)) }
        catch (e: Exception) { Logs.w(e) }
        finally { active = null }
    }

    fun snapshot(clear: Boolean): MobileTinaAppTrafficSnapshot = synchronized(lock) {
        val session = active
        if (clear) {
            session?.let { it.period.clear(raw(it), System.currentTimeMillis()) }
            file.delete()
            return@synchronized MobileTinaAppTrafficSnapshot(session?.period?.started ?: 0, session != null)
        }
        session?.let { it.period.snapshot(raw(it)) } ?: readSaved()
    }

    fun readSaved(): MobileTinaAppTrafficSnapshot = runCatching {
        file.openRead().use { input ->
            val bytes = ByteArray(MobileTinaAppTrafficModel.MAX_BYTES + 1)
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            if (size > MobileTinaAppTrafficModel.MAX_BYTES) MobileTinaAppTrafficSnapshot()
            else MobileTinaAppTrafficModel.decode(String(bytes, 0, size, Charsets.UTF_8)).copy(live = false)
        }
    }.getOrDefault(MobileTinaAppTrafficSnapshot())

    private fun save(snapshot: MobileTinaAppTrafficSnapshot) {
        val bytes = MobileTinaAppTrafficModel.encode(snapshot).toByteArray()
        require(bytes.size <= MobileTinaAppTrafficModel.MAX_BYTES)
        val store = file
        val output = store.startWrite()
        try { output.write(bytes); store.finishWrite(output) }
        catch (e: Exception) { store.failWrite(output); throw e }
    }
}

class MobileTinaAppTrafficReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = intent.getStringExtra("request")?.takeIf { it.length <= 64 } ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val snapshot = MobileTinaAppTraffic.snapshot(intent.getBooleanExtra("clear", false))
                context.sendBroadcast(Intent(MobileTinaAppTraffic.ACTION_RESULT).setPackage(context.packageName)
                    .putExtra("request", request).putExtra("snapshot", MobileTinaAppTrafficModel.encode(snapshot)))
            } catch (e: Exception) { Logs.w(e) }
            finally { pending.finish() }
        }
    }
}
