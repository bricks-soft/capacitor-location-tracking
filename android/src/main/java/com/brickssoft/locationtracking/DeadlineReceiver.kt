package com.brickssoft.locationtracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

class DeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val runtime = try { TrackingRuntime.get(context) } catch (_: Exception) {
            try { DiagnosticLog(context).use { it.record("error", "STATE_RESTORE_FAILED") } } finally { pending.finish() }
            return
        }
        runtime.scope.launch {
            try {
                if (!runtime.controller.checkDeadline()) runtime.driver.stop()
                else runtime.deadlines.arm(runtime.controller.stored)
            } catch (_: Exception) { runtime.record("DEADLINE_CHECK_FAILED") }
            finally { pending.finish() }
        }
    }
}
