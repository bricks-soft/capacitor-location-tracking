package com.brickssoft.locationtracking

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONObject

// The sticky broadcast provides the current battery snapshot.
// https://developer.android.com/reference/android/os/BatteryManager
// https://developer.android.com/reference/android/content/Intent#ACTION_BATTERY_CHANGED
internal fun deviceBattery(context: Context): JSONObject {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    return JSONObject().putNullable("level", if (level >= 0 && scale > 0) (level.toDouble() / scale).coerceIn(0.0, 1.0) else null)
        .put("isCharging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
}

// Active connectivity is an observation, not proof that the configured endpoint is reachable.
// https://developer.android.com/reference/android/net/ConnectivityManager
internal fun networkConnected(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val network = manager.activeNetwork ?: return false
    return manager.getNetworkCapabilities(network)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
