package com.brickssoft.locationtracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import kotlinx.coroutines.launch

/** BOOT_COMPLETED and MY_PACKAGE_REPLACED exempt the general background-start restriction,
 * not location's while-in-use checks. Android 15's boot-prohibited type list excludes location.
 * https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
 * https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed
 * Retrieved 2026-09-14. No direct-boot storage or LOCKED_BOOT_COMPLETED subscription.
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
