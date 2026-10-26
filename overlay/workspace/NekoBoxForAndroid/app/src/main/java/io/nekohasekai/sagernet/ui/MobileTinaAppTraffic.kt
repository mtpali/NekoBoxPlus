package io.nekohasekai.sagernet.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.util.AtomicFile
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import libcore.BoxInstance
import java.util.UUID
import java.io.File

/** The :bg service is the only writer. Work is on demand or once at normal teardown. */
internal object MobileTinaAppTraffic {
    const val KEY = "mobiletinaDevAppTraffic"
    const val ACTION_RESULT = "com.nb4a.mobiletina.APP_TRAFFIC_RESULT"
    private val lock = Any()
    private data class Session(val box: BoxInstance, val period: MobileTinaAppTrafficPeriod)
    private var active: Session? = null
    private val file get() = AtomicFile(File(SagerNet.application.filesDir, "mobiletina-app-traffic.json"))
    val enabled: Boolean get() = DataStore.configurationStore.getBoolean(KEY, true)

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
        file.openRead().use(MobileTinaAppTrafficModel::read).copy(live = false)
    }.getOrDefault(MobileTinaAppTrafficSnapshot())

    /** A short-lived private reply receiver, shared by the page and full app cleanup. */
    suspend fun query(clear: Boolean = false): MobileTinaAppTrafficSnapshot = withContext(Dispatchers.IO) {
        val app = SagerNet.application
        if (app.isBgProcess) return@withContext snapshot(clear)
        if (!clear && !DataStore.serviceState.started) return@withContext readSaved()
        val request = UUID.randomUUID().toString()
        val result = CompletableDeferred<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra("request") == request)
                    result.complete(intent.getStringExtra("snapshot").orEmpty())
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            app.sendBroadcast(Intent(app, MobileTinaAppTrafficReceiver::class.java)
                .putExtra("request", request).putExtra("clear", clear))
            MobileTinaAppTrafficModel.decode(withTimeout(5_000L) { result.await() })
        } finally { app.unregisterReceiver(receiver) }
    }

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
