package com.brickssoft.locationtracking

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.brickssoft.tracking.httpqueue.DrainResult
import com.brickssoft.tracking.httpqueue.QueueSyncException
import com.brickssoft.tracking.location.PositionRequest
import kotlinx.coroutines.*
import org.json.JSONObject

/** Bridge signatures verified in installed @capacitor/android 7.6.9 artifact, 2026-09-14:
 * https://registry.npmjs.org/@capacitor/android/-/android-7.6.9.tgz
 * Permission flow: https://developer.android.com/develop/sensors-and-location/location/permissions/background
 * Retrieved 2026-09-14. Foreground and background are separate host-initiated calls.
 */
@CapacitorPlugin(name = "LocationTracking", permissions = [
    Permission(alias = "location", strings = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION]),
    Permission(alias = "background", strings = [Manifest.permission.ACCESS_BACKGROUND_LOCATION]),
    Permission(alias = "notifications", strings = [Manifest.permission.POST_NOTIFICATIONS]),
])
class LocationTrackingPlugin : Plugin() {
    private lateinit var runtime: TrackingRuntime
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var settingsCall: PluginCall? = null
    private var listener: ((String, JSONObject) -> Unit)? = null
    override fun load() {
        runtime = TrackingRuntime.get(context)
        listener = { name, value -> bridge.executeOnMainThread { notifyListeners(name, JSObject(value.toString()), false) } }
        runtime.listeners.add(listener!!)
        jobs.launch {
            try {
                runtime.initialize()
                runtime.controller.stored.config?.let { runtime.logs.configure(it.diagnostics) }
                if (!runtime.controller.checkDeadline()) runtime.driver.stop()
                else runtime.deadlines.arm(runtime.controller.stored)
                val state = runtime.state()
                // Only two cold-start snapshots are retained. Continuous locations never replay.
                bridge.executeOnMainThread {
                    notifyListeners("enabledchange", JSObject().put("enabled", state.getBoolean("enabled"))
                        .put("requestedEnabled", state.getBoolean("requestedEnabled")).put("reason", state.optString("lastStopReason", "RESTORED")), true)
                    notifyListeners("providerchange", JSObject(state.getJSONObject("provider").toString()), true)
                }
            } catch (_: Exception) { runtime.record("BRIDGE_RESTORE_FAILED") }
        }
    }
    private fun run(call: PluginCall, block: suspend () -> JSONObject?) {
        jobs.launch {
            try { runtime.initialize(); val result = block(); if (result == null) call.resolve() else call.resolve(JSObject(result.toString())) }
            catch (error: QueueSyncException) { call.reject("Queue did not drain", "SYNC_INCOMPLETE", JSObject(syncJson(error.result).toString())) }
            catch (error: Exception) {
                val reason = when (error) {
                    is TrackingFailure -> error.reason
                    is IllegalArgumentException, is org.json.JSONException -> "INVALID_ARGUMENT"
                    is TimeoutCancellationException -> "TIMEOUT"
                    is CancellationException -> "CANCELLED"
                    else -> "OPERATION_FAILED"
                }
                runtime.record(reason)
                call.reject(reason, reason)
            }
        }
    }
    @PluginMethod fun ready(call: PluginCall) = run(call) {
        val data = call.data
        val config = TrackingConfig.parse(data.getJSONObject("config"))
        val auth = if (data.has("authorization")) Arguments.authorization(data.getJSONObject("authorization")) else null
        runtime.controller.ready(config, auth).also { runtime.logs.configure(config.diagnostics) }
    }
    @PluginMethod fun configure(call: PluginCall) = ready(call)
    @PluginMethod fun start(call: PluginCall) = run(call) {
        if (activity == null || activity.isFinishing || !activity.hasWindowFocus()) throw TrackingFailure("VISIBLE_ACTIVITY_REQUIRED")
        runtime.controller.start()
    }
    @PluginMethod fun stop(call: PluginCall) = run(call) { runtime.controller.stop(Arguments.optionalBool(call.data, "pauseUploads")) }
    @PluginMethod fun getState(call: PluginCall) = run(call) {
        if (!runtime.controller.checkDeadline()) runtime.driver.stop()
        runtime.state()
    }
    @PluginMethod fun getProviderState(call: PluginCall) = run(call) { TrackingController.providerJson(runtime.controller.providerState()) }
    @PluginMethod fun setConfig(call: PluginCall) = run(call) {
        runtime.controller.patch(call.data.getJSONObject("patch")).also { runtime.controller.stored.config?.let { runtime.logs.configure(it.diagnostics) } }
    }
    @PluginMethod fun setAuthorization(call: PluginCall) = run(call) { runtime.controller.authorize(Arguments.authorization(call.data)) }
    @PluginMethod fun clearAuthorization(call: PluginCall) = run(call) {
        runtime.controller.clearAuthorization(Arguments.text(call.data, "scopeKey")); null
    }
    private fun scope(call: PluginCall): String = if (call.data.has("scopeKey")) Arguments.text(call.data, "scopeKey")
        else runtime.controller.stored.config?.scopeKey ?: throw TrackingFailure("NOT_CONFIGURED")
    @PluginMethod fun getCount(call: PluginCall) = run(call) { JSONObject().put("count", runtime.locations.count(scope(call))) }
    @PluginMethod fun sync(call: PluginCall) = run(call) { syncJson(runtime.locations.sync(scope(call))) }
    @PluginMethod fun destroyLocations(call: PluginCall) = run(call) {
        JSONObject().put("deleted", runtime.locations.purge(Arguments.text(call.data, "scopeKey"), Arguments.uuids(call.data)))
    }
    @PluginMethod fun getCurrentPosition(call: PluginCall) = run(call) {
        val data = call.data
        val timeout = Arguments.optionalNumber(data, "timeoutSeconds", 15, 1, 900)
        val age = Arguments.optionalNumber(data, "maximumAgeMs", 0, 0, 86400000)
        val persist = Arguments.optionalBool(data, "persist")
        val state = runtime.controller.stored
        if (persist && state.config == null) throw TrackingFailure("NOT_CONFIGURED")
        if (persist && state.guard?.reason(state.config?.stopAt, runtime.clock) != null) throw TrackingFailure("DEADLINE")
        val request = state.config?.request(state.generation) ?: com.brickssoft.tracking.location.LocationRequest(state.generation)
        val fix = runtime.provider(state.config).currentPosition(PositionRequest(request, timeout * 1000, age))
        val position = LocationPayloadEncoder.position(fix, deviceBattery(context))
        if (persist) {
            val retained = runtime.controller.persistOneShot(state.generation, state.config!!, fix, position)
            runtime.emit("location", JSONObject().put("position", position).put("persisted", retained).put("generation", state.generation))
        }
        position
    }
    @PluginMethod override fun requestPermissions(call: PluginCall) {
        try {
            val background = Arguments.optionalBool(call.data, "background")
            if (background) {
                if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION))
                    throw TrackingFailure("FOREGROUND_PERMISSION_REQUIRED")
                if (Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) { permissionResult(call); return }
                require(hostDeclaresBackground()) { "Host must declare ACCESS_BACKGROUND_LOCATION" }
                if (Build.VERSION.SDK_INT >= 30) {
                    check(settingsCall == null) { "Permission request already pending" }
                    settingsCall = call
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                } else requestPermissionForAlias("background", call, "permissionResult")
            } else requestPermissionForAlias("location", call, "foregroundResult")
        } catch (error: Exception) {
            settingsCall = null
            call.reject((error as? TrackingFailure)?.reason ?: "INVALID_ARGUMENT", (error as? TrackingFailure)?.reason ?: "INVALID_ARGUMENT")
        }
    }
    @PermissionCallback private fun foregroundResult(call: PluginCall) {
        // Notifications are independent of foreground location and are not an FGS eligibility gate.
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
            requestPermissionForAlias("notifications", call, "permissionResult")
        else permissionResult(call)
    }
    @PermissionCallback private fun permissionResult(call: PluginCall) = run(call) { TrackingController.providerJson(runtime.controller.providerState()) }
    private fun granted(permission: String): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    @Suppress("DEPRECATION") private fun hostDeclaresBackground(): Boolean = context.packageManager
        .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == true
    override fun handleOnResume() {
        settingsCall?.let { settingsCall = null; permissionResult(it) }
        if (::runtime.isInitialized) jobs.launch {
            runtime.emit("providerchange", TrackingController.providerJson(runtime.controller.providerState()))
            if (!runtime.controller.checkDeadline()) runtime.driver.stop() else runtime.deadlines.arm(runtime.controller.stored)
        }
    }
    @PluginMethod fun log(call: PluginCall) = run(call) {
        val level = Arguments.text(call.data, "level")
        require(level in DiagnosticLog.LEVELS)
        runtime.logs.record(level, "HOST_LOG", Arguments.text(call.data, "message", 8192)); null
    }
    @PluginMethod fun getDiagnostics(call: PluginCall) = run(call) {
        val after = Arguments.optionalNumber(call.data, "afterId", 0)
        val limit = Arguments.optionalNumber(call.data, "limit", 100, 1, 500).toInt()
        val page = runtime.logs.page(after, limit)
        JSONObject().put("entries", page.first).putNullable("nextId", page.second).put("state", runtime.state())
    }
    @PluginMethod fun clearDiagnostics(call: PluginCall) = run(call) { runtime.logs.clear(); null }
    override fun handleOnDestroy() {
        listener?.let { runtime.listeners.remove(it) }
        settingsCall?.reject("WebView destroyed", "CANCELLED"); settingsCall = null
        jobs.cancel()
    }
    companion object {
        private fun syncJson(result: DrainResult): JSONObject = JSONObject().put("uploaded", result.uploaded).put("remaining", result.remaining)
            .put("outcome", when (result.outcome) {
                com.brickssoft.tracking.httpqueue.DrainOutcome.DRAINED -> "drained"
                com.brickssoft.tracking.httpqueue.DrainOutcome.AUTH_PAUSED -> "authPaused"
                com.brickssoft.tracking.httpqueue.DrainOutcome.BLOCKED, com.brickssoft.tracking.httpqueue.DrainOutcome.PAUSED -> "blocked"
                else -> "deferred"
            })
    }
}
