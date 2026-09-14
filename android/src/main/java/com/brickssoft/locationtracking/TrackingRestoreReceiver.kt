package com.brickssoft.locationtracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import kotlinx.coroutines.launch

/** Restores eligible tracking after boot or package replacement.
 * These broadcasts are background-start exemptions but remain subject to location while-in-use checks.
 * Direct-boot storage and LOCKED_BOOT_COMPLETED are not used.
 * https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
 * https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed
 */
class TrackingRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val unlocked = Build.VERSION.SDK_INT < 24 || (context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
        if (!unlocked) return
        val pending = goAsync()
        val runtime = try { TrackingRuntime.get(context) } catch (_: Exception) {
            try { DiagnosticLog(context).use { it.record("error", "STATE_RESTORE_FAILED") } } finally { pending.finish() }
            return
        }
        runtime.scope.launch {
            try {
                runtime.initialize()
                runtime.controller.restore(action == Intent.ACTION_BOOT_COMPLETED, true)
            } catch (_: Exception) { runtime.record("RESTORE_FAILED") }
            finally { pending.finish() }
        }
    }
}
