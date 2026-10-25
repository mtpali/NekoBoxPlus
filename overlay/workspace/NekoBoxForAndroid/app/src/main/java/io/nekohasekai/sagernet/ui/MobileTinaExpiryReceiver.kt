package io.nekohasekai.sagernet.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import io.nekohasekai.sagernet.ktx.Logs

class MobileTinaExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val group = intent.getLongExtra("group", 0)
        val trigger = intent.getLongExtra("trigger", 0)
        if (group <= 0 || trigger <= 0) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { MobileTinaSubscriptionExpiry.onAlarm(group, trigger) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Logs.w(e) }
            finally { result.finish() }
        }
    }
}
