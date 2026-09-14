package com.brickssoft.locationtracking

import android.app.ForegroundServiceStartNotAllowedException
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import com.brickssoft.tracking.httpqueue.Diagnostics
import com.brickssoft.tracking.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

internal class TrackingRuntime private constructor(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val clock = AndroidTrackingClock(context)
    val logs = DiagnosticLog(context)
    val listeners = CopyOnWriteArrayList<(String, JSONObject) -> Unit>()
    val deadlines = DeadlineScheduler(context, clock)
    val driver = AndroidServiceDriver(context)
    val locations = LocationStore(context, Diagnostics { event ->
        scope.launch {
            logs.record("info", event.reason, details = JSONObject().putNullable("status", event.status)
                .putNullable("retryAt", event.nextAttemptAt).put("expired", event.pruned.expired).put("overflow", event.pruned.overflow))
            if (event.status != null || event.reason == "NETWORK") emit("http", JSONObject().putNullable("status", event.status)
                .put("uuids", JSONArray(event.uuids)).put("outcome", event.reason)
                .put("remaining", event.scopeKey?.let { locationsCount(it) } ?: 0).put("authRevision", event.authRevision ?: 0))
        }
    })
    private val drainRequests = Channel<String>(Channel.CONFLATED)
    init {
        scope.launch {
            for (scopeKey in drainRequests) try { locations.drain(scopeKey) } catch (_: Exception) { record("DRAIN_FAILED") }
        }
    }
    fun requestDrain(scopeKey: String) { drainRequests.trySend(scopeKey) }
    private suspend fun locationsCount(scope: String): Int = locations.count(scope)
    private val capability = CapabilityProbe(context)
    val controller = TrackingController(TrackingStateStore(context), locations, clock, driver, deadlines,
        { config -> ProviderSelector(capability::snapshot).select(config?.provider, config?.fallback ?: false) }, ::emit, ::record)
    private val initialization = Mutex()
    private var initialized = false
    suspend fun initialize() = initialization.withLock {
        if (initialized) return@withLock
        // User Stop removes the process without callbacks. Inspect each exit only once.
        // https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping
        // https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,int,int)
        if (Build.VERSION.SDK_INT >= 30) {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                .filter { it.processName == context.applicationInfo.processName }
                .maxByOrNull { it.timestamp }?.let {
                    controller.observeExit(it.timestamp, it.reason == ApplicationExitInfo.REASON_USER_REQUESTED)
                }
        }
        controller.stored.config?.let { logs.configure(it.diagnostics) }
        locations.initialize()
        initialized = true
    }
    fun provider(config: TrackingConfig?): LocationProvider {
        val selected = ProviderSelector(capability::snapshot).select(config?.provider, config?.fallback ?: false)
        return when (selected.selected) {
            ProviderKind.GMS -> GmsFusedProvider(context, probe = capability::snapshot)
            ProviderKind.HMS -> HmsFusedProvider(context, probe = capability::snapshot)
            ProviderKind.PLATFORM -> PlatformProvider(context, probe = capability::snapshot)
            null -> throw ProviderUnavailableException(selected)
        }
    }
    fun emit(name: String, value: JSONObject) {
        // Plain loop: CopyOnWriteArrayList.forEach needs API 24 and minSdk is 23.
        for (listener in listeners) {
            try { listener(name, value) } catch (_: Exception) { }
        }
    }
    fun record(reason: String) { try { logs.record("warn", reason) } catch (_: Exception) { } }
    suspend fun state(): JSONObject = controller.state().also {
        if (it.getString("uploadState") == "idle") it.put("uploadState", when {
            !networkConnected(context) -> "offline"
            locations.uploading -> "uploading"
            else -> "idle"
        })
        it.putNullable("lastFixGapMs", controller.stored.lastFixAt?.let { at -> (clock.wall() - at).coerceAtLeast(0) })
        it.putNullable("effectiveMinUpdateIntervalMs", controller.stored.config?.request(0)?.effectiveMinUpdateIntervalMs)
    }
    companion object {
        @Volatile private var instance: TrackingRuntime? = null
        fun get(context: Context): TrackingRuntime = instance ?: synchronized(this) {
            instance ?: TrackingRuntime(context.applicationContext).also { instance = it }
        }
        internal fun resetForTest() { instance?.scope?.cancel(); instance = null }
    }
}

internal class AndroidServiceDriver(private val context: Context) : TrackingServiceDriver {
    private companion object { const val START_TIMEOUT_MS = 90_000L } // > service readiness window
    @Volatile var active: LocationTrackingService? = null
    private val lock = Any()
    private var pending: Pair<Long, CompletableDeferred<Unit>>? = null
    override suspend fun start(generation: Long) {
        val completion = CompletableDeferred<Unit>()
        synchronized(lock) { pending?.second?.cancel(); pending = generation to completion }
        try {
            launchService(generation)
            withTimeout(START_TIMEOUT_MS) { completion.await() }
        } finally { synchronized(lock) { if (pending?.first == generation) pending = null } }
    }
    override suspend fun restore(generation: Long) { launchService(generation) }
    private fun launchService(generation: Long) {
            val intent = Intent(context, LocationTrackingService::class.java).putExtra("generation", generation)
            // https://developer.android.com/develop/background-work/services/fgs/launch
            // https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (error: SecurityException) { throw TrackingFailure("FGS_PERMISSION_DENIED") }
            catch (error: IllegalStateException) {
                if (Build.VERSION.SDK_INT >= 31 && error is ForegroundServiceStartNotAllowedException)
                    throw TrackingFailure("FGS_START_NOT_ALLOWED")
                throw TrackingFailure("FGS_START_FAILED")
            }
    }
    fun complete(generation: Long, error: Exception? = null) = synchronized(lock) {
        pending?.takeIf { it.first == generation }?.second?.let {
            if (error == null) it.complete(Unit) else it.completeExceptionally(error)
        }
    }
    override suspend fun stop() {
        active?.shutdown()
        context.stopService(Intent(context, LocationTrackingService::class.java))
    }
}
