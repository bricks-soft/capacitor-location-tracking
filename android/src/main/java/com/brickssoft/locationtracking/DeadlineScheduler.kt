package com.brickssoft.locationtracking

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Schedules wall and elapsed deadlines through an explicit receiver without starting a service. */
internal class DeadlineScheduler(private val context: Context, private val clock: TrackingClock) : DeadlinePlan {
    private val alarms get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private fun pending(code: Int): PendingIntent = PendingIntent.getBroadcast(context, code,
        Intent(context, DeadlineReceiver::class.java).setAction("com.brickssoft.locationtracking.DEADLINE.$code"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    override fun arm(state: StoredTrackingState) {
        cancel()
        if (!state.requested) return
        val end = state.config?.stopAt ?: return
        schedule(AlarmManager.RTC_WAKEUP, end, pending(0))
        state.guard?.takeUnless { it.differentBoot(clock) }?.elapsedEnd?.let {
            schedule(AlarmManager.ELAPSED_REALTIME_WAKEUP, it, pending(1))
        }
    }
    private fun schedule(type: Int, at: Long, intent: PendingIntent) {
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            try { alarms.setExactAndAllowWhileIdle(type, at, intent); return }
            catch (_: SecurityException) { /* Fall back if special access changed. */ }
        }
        alarms.setAndAllowWhileIdle(type, at, intent)
    }
    override fun cancel() { alarms.cancel(pending(0)); alarms.cancel(pending(1)) }
}
