package io.nekohasekai.sagernet.bg

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet

/** Measures one continuous connection using monotonic time, without a daemon Worker. */
internal object MobileTinaSessionLimiter {
    private const val DURATION = 24 * 60 * 60 * 1_000L
    private var sessionToken = 0L
    private fun preferences(context: Context) = context.getSharedPreferences("mobiletina.session", Context.MODE_PRIVATE)
    private fun pending(context: Context, token: Long) = PendingIntent.getBroadcast(
        context, 2401, Intent(context, MobileTinaSessionReceiver::class.java).putExtra("session", token),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @Synchronized
    fun onState(context: Context, state: BaseService.State) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (state == BaseService.State.Stopped) {
            alarm.cancel(pending(context, sessionToken))
            sessionToken = 0
            preferences(context).edit().clear().apply()
        } else if (state == BaseService.State.Connected && sessionToken == 0L) {
            sessionToken = SystemClock.elapsedRealtimeNanos()
            val end = SystemClock.elapsedRealtime() + DURATION
            preferences(context).edit().putLong("token", sessionToken).putLong("end", end).apply()
            val intent = pending(context, sessionToken)
            try {
                if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, end, intent)
                } else alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, end, intent)
            } catch (_: SecurityException) {
                alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, end, intent)
            }
        }
    }

    fun isDue(context: Context, token: Long): Boolean {
        val prefs = preferences(context)
        return token > 0 && prefs.getLong("token", 0) == token &&
            SystemClock.elapsedRealtime() >= prefs.getLong("end", Long.MAX_VALUE)
    }
}

class MobileTinaSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (MobileTinaSessionLimiter.isDue(context, intent.getLongExtra("session", 0))) SagerNet.stopService()
    }
}
