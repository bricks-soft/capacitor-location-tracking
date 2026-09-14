package com.brickssoft.locationtracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.brickssoft.tracking.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class LocationTrackingService : Service() {
    private companion object {
        // Provider registration can lag on slow devices and Huawei gives no ordering guarantee between
        // registration success and the first fix; the first delivered fix also completes readiness.
        const val SUBSCRIPTION_READY_TIMEOUT_MS = 60_000L
    }
    private lateinit var runtime: TrackingRuntime
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private var subscription: Subscription? = null
    private var provider: LocationProvider? = null
    private var startup: Job? = null
    private var ticks: Job? = null
    private var worker: Job? = null
    private var registered = false
    private val fixes = Channel<Pair<Long, LocationFix>>(256)
    @Volatile private var generation: Long = -1
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            jobs.launch {
                try {
                    val p = runtime.controller.providerState()
                    runtime.emit("providerchange", TrackingController.providerJson(p))
                    runtime.emit("powersavechange", JSONObject().put("enabled", p.powerSave))
                    runtime.locations.connectivity(connected())
                    if (!runtime.controller.checkDeadline()) shutdown()
                    else if (p.selected == null) suspendAcquisition("PROVIDER_UNAVAILABLE")
                    else {
                        runtime.deadlines.arm(runtime.controller.stored)
                        recoverAcquisition()
                    }
                } catch (_: Exception) { fail("ENVIRONMENT_CHECK_FAILED") }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        runtime = TrackingRuntime.get(this)
        runtime.driver.active = this
        val filter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION); addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
            @Suppress("DEPRECATION") addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        // System-only receiver, platform overload verified against SDK 36 android.jar (2026-09-14).
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changes, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(changes, filter)
        registered = true
        worker = jobs.launch {
            for ((gen, fix) in fixes) {
                try {
                    runtime.controller.accept(gen, listOf(fix), deviceBattery(this@LocationTrackingService))
                    if (!runtime.controller.checkDeadline()) shutdown()
                    else if (connected()) runtime.controller.stored.config?.let { runtime.requestDrain(it.scopeKey) }
                } catch (_: Exception) { fail("PERSISTENCE_OR_PROVIDER_FAILED") }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = runtime.controller.stored
        val config = state.config
        val requestedGeneration = intent?.getLongExtra("generation", -1) ?: state.generation
        if (!state.requested || config == null || requestedGeneration != state.generation) {
            runtime.driver.complete(requestedGeneration, TrackingFailure("STALE_START"))
            if (state.requested && generation == state.generation) return START_STICKY
            stopSelf(startId); return START_NOT_STICKY
        }
        try { promote(config) } catch (_: Exception) {
            runtime.driver.complete(requestedGeneration, TrackingFailure("FOREGROUND_PROMOTION_FAILED"))
            jobs.launch { runtime.controller.block("FOREGROUND_PROMOTION_FAILED", false); shutdown() }
            return START_NOT_STICKY
        }
        if (intent == null) {
            startup = jobs.launch {
                // Sticky restoration never grants a fresh deadline and requires background permission.
                val reason = TrackingController.eligibility(state, runtime.controller.providerState(), runtime.clock, true)
                if (reason != null) fail(reason, requestedGeneration) else subscribe(requestedGeneration, config)
            }
        } else if (generation != requestedGeneration) {
            startup?.cancel()
            startup = jobs.launch { subscribe(requestedGeneration, config) }
        }
        return START_STICKY
    }
    private suspend fun subscribe(gen: Long, config: TrackingConfig) {
        try {
            runtime.initialize()
            lifecycle.withLock {
                if (generation == gen && subscription != null) return
                removeSubscription()
                if (runtime.controller.stored.generation != gen || !runtime.controller.stored.requested) throw TrackingFailure("STALE_START")
                if (!runtime.controller.checkDeadline()) throw TrackingFailure("DEADLINE")
                generation = gen
                provider = runtime.provider(config)
                subscription = provider!!.start(config.request(gen), object : LocationSink {
                    override fun onLocation(generation: Long, fix: LocationFix) {
                        if (fixes.trySend(generation to fix).isFailure) jobs.launch { fail("CALLBACK_BUFFER_FULL") }
                    }
                    override fun onAvailability(generation: Long, availability: Availability) {
                        jobs.launch { runtime.controller.availability(generation, availability.available) }
                    }
                    override fun onError(generation: Long, error: Exception) {
                        runtime.driver.complete(generation, TrackingFailure("PROVIDER_ERROR"))
                        jobs.launch { if (generation == runtime.controller.stored.generation) fail("PROVIDER_ERROR") }
                    }
                })
                withTimeout(SUBSCRIPTION_READY_TIMEOUT_MS) { subscription!!.awaitReady() }
            }
            runtime.controller.serviceReady(gen)
            runtime.driver.complete(gen)
            ticks?.cancel()
            ticks = jobs.launch { maintenance(config) }
        } catch (error: Exception) {
            runtime.driver.complete(gen, error)
            if (error !is CancellationException) fail((error as? TrackingFailure)?.reason ?: "SUBSCRIPTION_FAILED", gen)
        }
    }
    private suspend fun maintenance(config: TrackingConfig) {
        var heartbeatAt = runtime.clock.elapsed()
        while (currentCoroutineContext().isActive) {
            delay(5000)
            if (!runtime.controller.checkDeadline()) { shutdown(); return }
            if (runtime.controller.providerState().selected == null) suspendAcquisition("PROVIDER_UNAVAILABLE")
            else recoverAcquisition()
            val interval = config.heartbeatSeconds
            if (interval > 0 && runtime.clock.elapsed() - heartbeatAt >= interval * 1000) {
                heartbeatAt = runtime.clock.elapsed()
                runtime.emit("heartbeat", JSONObject().put("at", iso(runtime.clock.wall()))
                    .putNullable("lastFixAt", runtime.controller.stored.lastFixAt?.let(::iso))
                    .put("queueCount", runtime.locations.count(config.scopeKey)))
            }
            if (connected() && config.http.getLong("maxBatchAgeSeconds") > 0) runtime.requestDrain(config.scopeKey)
        }
    }
    private suspend fun suspendAcquisition(reason: String) {
        if (runtime.controller.stored.reason == reason && subscription == null) return
        runtime.controller.block(reason, false)
        lifecycle.withLock { removeSubscription() }
    }
    private fun recoverAcquisition() {
        val state = runtime.controller.stored
        if (state.requested && !runtime.controller.enabled && state.reason == "PROVIDER_UNAVAILABLE") {
            val config = state.config ?: return
            jobs.launch { subscribe(state.generation, config) }
        }
    }
    private suspend fun fail(reason: String, expectedGeneration: Long = generation) {
        if (expectedGeneration != runtime.controller.stored.generation) return
        runtime.controller.block(reason, reason == "DEADLINE")
        shutdown()
    }
    internal suspend fun shutdown(): Unit = withContext(NonCancellable) {
        startup?.cancel(); startup = null
        // Called outside provider callbacks. Removal is bounded by the core subscription contract.
        ticks?.cancel(); ticks = null
        lifecycle.withLock { removeSubscription() }
        withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
        }
    }
    private suspend fun removeSubscription() {
        val old = subscription
        subscription = null; generation = -1
        if (old != null) try { withContext(NonCancellable) { provider?.stop(old) } }
        catch (_: Exception) { runtime.record("SUBSCRIPTION_REMOVAL_FAILED") }
        provider = null
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (runtime.controller.stored.config?.stopOnTerminate == true) runtime.scope.launch { runtime.controller.stop(reason = "TASK_REMOVED") }
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        if (registered) unregisterReceiver(changes)
        if (runtime.driver.active === this) runtime.driver.active = null
        val destroyedGeneration = generation
        runtime.scope.launch { runtime.controller.serviceDestroyed(destroyedGeneration) }
        fixes.close()
        startup?.cancel(); ticks?.cancel(); worker?.cancel()
        runtime.scope.launch { lifecycle.withLock { removeSubscription() }; jobs.cancel() }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun connected(): Boolean = networkConnected(this)
    @Suppress("DEPRECATION")
    private fun promote(config: TrackingConfig) {
        val n = config.notification
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = n.getString("channelId")
        // API 26+ requires a channel; a location FGS uses low importance or above.
        // https://developer.android.com/develop/ui/compose/notifications/channels
        // https://developer.android.com/develop/background-work/services/fgs/launch retrieved 2026-09-14.
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channel, n.getString("channelName"), NotificationManager.IMPORTANCE_LOW))
        val icon = resources.getIdentifier(n.getString("smallIcon"), "drawable", packageName)
            .takeIf { it != 0 } ?: resources.getIdentifier(n.getString("smallIcon"), "mipmap", packageName)
        require(icon != 0) { "Notification icon not found" }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel) else Notification.Builder(this)
        val notification = builder.setSmallIcon(icon).setContentTitle(n.getString("title")).setContentText(n.getString("text"))
            .setOngoing(true).setPriority(Notification.PRIORITY_LOW).setCategory(Notification.CATEGORY_SERVICE).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(n.getInt("id"), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(n.getInt("id"), notification)
    }
}
