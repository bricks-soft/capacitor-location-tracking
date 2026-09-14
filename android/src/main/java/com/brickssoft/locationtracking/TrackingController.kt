package com.brickssoft.locationtracking

import com.brickssoft.tracking.httpqueue.Authorization
import com.brickssoft.tracking.httpqueue.UploadGate
import com.brickssoft.tracking.location.LocationFix
import com.brickssoft.tracking.location.ProviderState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal interface TrackingServiceDriver {
    suspend fun start(generation: Long)
    suspend fun restore(generation: Long) = start(generation)
    suspend fun stop()
}
internal interface DeadlinePlan {
    fun arm(state: StoredTrackingState)
    fun cancel()
}
internal class TrackingFailure(val reason: String) : IllegalStateException(reason)
internal class TrackingController(
    private val persistence: StatePersistence,
    private val locations: LocationRepository,
    private val clock: TrackingClock,
    private val service: TrackingServiceDriver,
    private val deadlines: DeadlinePlan,
    private val probe: (TrackingConfig?) -> ProviderState,
    private val emit: (String, JSONObject) -> Unit = { _, _ -> },
    private val diagnostic: (String) -> Unit = {},
) {
    private val operations = Mutex()
    private val stateLock = Mutex()
    @Volatile var stored: StoredTrackingState = persistence.read()
        private set
    @Volatile var enabled: Boolean = false
        private set
    @Volatile private var available: Boolean? = null
    private fun cutoffReason(): String? = stored.guard?.reason(stored.config?.stopAt, clock)
        ?: if (stored.config?.stopAt?.let { it <= clock.wall() } == true) "DEADLINE" else null
    private fun save(value: StoredTrackingState) { persistence.write(value); stored = value }
    private fun publish(reason: String) { emit("enabledchange", JSONObject().put("enabled", enabled)
        .put("requestedEnabled", stored.requested).put("reason", reason)) }
    fun providerState(): ProviderState = probe(stored.config).copy(locationAvailable = available)
    suspend fun ready(config: TrackingConfig, authorization: Authorization? = null): JSONObject = operations.withLock { readyInternal(config, authorization) }
    private suspend fun readyInternal(config: TrackingConfig, authorization: Authorization?): JSONObject {
        authorization?.let { require(it.scopeKey == config.scopeKey) { "Authorization scope mismatch" } }
        val previous = stored.config
        val switching = previous != null && !previous.sameSession(config)
        if (switching) {
            check(!stored.requested && !enabled) { "Stop old session first" }
            check(locations.gate(previous!!.scopeKey) == UploadGate.PAUSED) { "Pause old uploads first" }
        }
        if (authorization != null) {
            val configuredNames = config.http.getJSONObject("headers").keys().asSequence().map { it.lowercase(java.util.Locale.ROOT) }.toSet()
            require(authorization.headers.keys.none { it.lowercase(java.util.Locale.ROOT) in configuredNames }) { "Credential header in config" }
            if (!locations.authorize(authorization)) throw TrackingFailure("STALE_AUTHORIZATION")
        }
        val changed = previous?.json != config.json
        val reconcileClock = stored.reason == "CLOCK_ROLLBACK"
        val resume = stored.requested && !switching && !reconcileClock
        if (reconcileClock) {
            stateLock.withLock { save(stored.copy(requested = false, generation = stored.generation + 1,
                guard = DeadlineGuard.create(config.stopAt, clock), reason = "CLOCK_RECONCILED")) }
            service.stop()
        }
        if (changed) {
            // Register first; an orphan config after death contains no new points and is never reused.
            val revision = maxOf(stored.revision + 1, locations.nextRevision())
            locations.register(config, revision)
            stateLock.withLock {
                save(stored.copy(config = config, revision = revision, generation = stored.generation + 1,
                    requested = resume, reason = null, lastFixAt = if (switching) null else stored.lastFixAt,
                    guard = if (previous?.stopAt == config.stopAt && !switching) stored.guard ?: DeadlineGuard.create(config.stopAt, clock)
                    else DeadlineGuard.create(config.stopAt, clock)))
                enabled = false; available = null
            }
            service.stop()
        }
        if (changed && resume) startInternal(false)
        else if (stored.requested) checkDeadline()
        return state()
    }
    suspend fun patch(patch: JSONObject): JSONObject = operations.withLock {
        require(!patch.has("scopeKey") && !patch.has("sessionId") && !patch.has("authorization")) { "Use ready for identity changes" }
        // ready owns the lifecycle mutex; validate full nested replacements before mutating.
        val config = requireNotNull(stored.config) { "Call ready first" }.objectValue()
        patch.keys().forEach { config.put(it, patch.get(it)) }
        readyInternal(TrackingConfig.parse(config), null)
    }
    suspend fun start(): JSONObject = operations.withLock { startInternal(false); state() }
    private suspend fun startInternal(background: Boolean) {
        val config = stored.config ?: throw TrackingFailure("NOT_CONFIGURED")
        val reason = eligibility(stored, providerState(), clock, background)
        if (reason != null) { block(reason, reason == "DEADLINE"); throw TrackingFailure(reason) }
        if (enabled) return
        stateLock.withLock {
            save(stored.copy(requested = true, generation = stored.generation + 1, reason = null,
                guard = stored.guard?.refreshed(config.stopAt, clock) ?: DeadlineGuard.create(config.stopAt, clock)))
            available = null
        }
        val startingGeneration = stored.generation
        try {
            deadlines.arm(stored)
            if (background) service.restore(startingGeneration) else {
                service.start(startingGeneration)
                stateLock.withLock {
                    if (!stored.requested || stored.generation != startingGeneration) throw TrackingFailure(stored.reason ?: "CANCELLED")
                    enabled = true
                }
            }
            publish(if (background) "RESTORE_REQUESTED" else "STARTED")
        } catch (error: Exception) {
            block((error as? TrackingFailure)?.reason ?: "START_FAILED", false)
            service.stop()
            throw error
        }
    }
    suspend fun stop(pauseUploads: Boolean = false, reason: String = "STOPPED"): JSONObject = operations.withLock {
        stateLock.withLock {
            save(stored.copy(requested = false, generation = stored.generation + 1, reason = reason))
            enabled = false; available = null
        }
        deadlines.cancel()
        if (pauseUploads) stored.config?.let { locations.pause(it.scopeKey) }
        service.stop()
        publish(reason)
        state()
    }
    suspend fun block(reason: String, clearRequested: Boolean) = stateLock.withLock {
        save(stored.copy(requested = if (clearRequested) false else stored.requested,
            generation = stored.generation + 1, reason = reason))
        enabled = false; available = null
        if (clearRequested) deadlines.cancel()
        diagnostic(reason); publish(reason)
    }
    suspend fun checkDeadline(): Boolean = stateLock.withLock {
        val reason = cutoffReason() ?: return@withLock true
        if (stored.reason != reason || enabled || (reason == "DEADLINE" && stored.requested)) {
            save(stored.copy(requested = if (reason == "DEADLINE") false else stored.requested,
                generation = stored.generation + 1, reason = reason))
            enabled = false; available = null
            deadlines.cancel(); diagnostic(reason); publish(reason)
        }
        false
    }
    suspend fun restore(boot: Boolean, unlocked: Boolean): Boolean = operations.withLock {
        if (!unlocked || !stored.requested) return@withLock false
        val config = stored.config ?: return@withLock false
        val clockReason = stored.guard?.reason(config.stopAt, clock, boot)
        if (clockReason != null) { block(clockReason, clockReason == "DEADLINE"); return@withLock false }
        stateLock.withLock { save(stored.copy(guard = stored.guard?.refreshed(config.stopAt, clock, boot))) }
        deadlines.arm(stored)
        if (!config.startOnBoot) { block("RESTORE_DISABLED", false); return@withLock false }
        val reason = eligibility(stored, providerState(), clock, true)
        if (reason != null) { block(reason, reason == "DEADLINE"); return@withLock false }
        try { startInternal(true); true } catch (_: Exception) { false }
    }
    suspend fun accept(generation: Long, fixes: List<LocationFix>, battery: JSONObject): List<JSONObject> = stateLock.withLock {
        val accepted = mutableListOf<JSONObject>()
        for (fix in fixes.sortedWith(compareBy<LocationFix> { it.acquiredAtEpochMs }.thenBy { it.elapsedRealtimeNanos })) {
            val config = stored.config
            val deadlineReason = cutoffReason()
            if (config == null || generation != stored.generation || !stored.requested || deadlineReason != null) {
                save(stored.copy(rejectedFixes = stored.rejectedFixes + 1))
                diagnostic(deadlineReason ?: "STALE_CALLBACK")
                continue
            }
            if (providerState().selected == null) {
                diagnostic("PROVIDER_UNAVAILABLE"); continue
            }
            val position = LocationPayloadEncoder.position(fix, battery)
            // Persist clock high-water before enqueue. Stop/session changes use this SAME lock.
            save(stored.copy(guard = stored.guard?.refreshed(config.stopAt, clock)))
            val retained = locations.enqueue(config, stored.revision, fix.acquiredAtEpochMs, position, true)
            save(stored.copy(lastFixAt = maxOf(stored.lastFixAt ?: 0, fix.acquiredAtEpochMs)))
            emit("location", JSONObject().put("position", position).put("persisted", retained).put("generation", generation))
            accepted += position
        }
        accepted
    }
    suspend fun persistOneShot(generation: Long, config: TrackingConfig, fix: LocationFix, position: JSONObject): Boolean = stateLock.withLock {
        check(generation == stored.generation && stored.config?.sameSession(config) == true) { "Session changed" }
        check(cutoffReason() == null) { "Collection deadline reached" }
        locations.enqueue(config, stored.revision, fix.acquiredAtEpochMs, position, enabled)
    }
    suspend fun observeExit(at: Long, userRequested: Boolean) = stateLock.withLock {
        if (at <= stored.lastExitAt) return@withLock
        save(if (userRequested) stored.copy(lastExitAt = at, requested = false, generation = stored.generation + 1, reason = "USER_STOPPED")
            else stored.copy(lastExitAt = at))
        if (userRequested) { enabled = false; available = null; deadlines.cancel(); publish("USER_STOPPED") }
    }
    suspend fun serviceReady(generation: Long) = stateLock.withLock {
        if (generation == stored.generation && stored.requested && cutoffReason() == null) {
            save(stored.copy(reason = null))
            enabled = true
            publish("SUBSCRIBED")
        }
    }
    suspend fun serviceDestroyed(generation: Long) = stateLock.withLock {
        if (stored.generation == generation) { enabled = false; available = null; publish("SERVICE_DESTROYED") }
    }
    suspend fun availability(generation: Long, value: Boolean) = stateLock.withLock {
        if (generation != stored.generation || !stored.requested || cutoffReason() != null) return@withLock
        available = value
        emit("providerchange", providerJson(providerState()))
    }
    suspend fun authorize(value: Authorization): JSONObject = operations.withLock {
        check(locations.authorize(value)) { "STALE_AUTHORIZATION" }; state()
    }
    suspend fun clearAuthorization(scope: String) = operations.withLock { locations.clearAuthorization(scope) }
    suspend fun state(): JSONObject = stateLock.withLock {
        val scope = stored.config?.scopeKey
        val gate = scope?.let { locations.gate(it) }
        JSONObject().put("requestedEnabled", stored.requested).put("enabled", enabled)
            .putNullable("session", stored.config?.let { JSONObject().put("scopeKey", it.scopeKey).put("sessionId", it.sessionId) })
            .put("generation", stored.generation).put("configRevision", stored.revision)
            .put("uploadState", when (gate) { UploadGate.AUTH_PAUSED -> "authPaused"; UploadGate.BLOCKED, UploadGate.PAUSED -> "blocked"; else -> "idle" })
            .putNullable("lastFixAt", stored.lastFixAt?.let(::iso)).putNullable("lastStopReason", stored.reason)
            .put("queueCount", if (scope == null) 0 else locations.count(scope)).put("provider", providerJson(providerState()))
            .put("rejectedFixes", stored.rejectedFixes)
    }
    companion object {
        fun eligibility(state: StoredTrackingState, provider: ProviderState, clock: TrackingClock, background: Boolean): String? {
            val config = state.config ?: return "NOT_CONFIGURED"
            state.guard?.reason(config.stopAt, clock)?.let { return it }
            if (config.stopAt != null && config.stopAt <= clock.wall()) return "DEADLINE"
            if (provider.permission == com.brickssoft.tracking.location.LocationPermission.DENIED) return "PERMISSION_DENIED"
            if (!provider.locationEnabled) return "LOCATION_DISABLED"
            if (background && !provider.backgroundPermission) return "BACKGROUND_PERMISSION_REQUIRED"
            if (provider.selected == null) return "PROVIDER_UNAVAILABLE"
            return null
        }
        fun providerJson(p: ProviderState): JSONObject = JSONObject().putNullable("selected", p.selected?.wireValue)
            .putNullable("gmsStatus", p.gmsStatus).putNullable("hmsStatus", p.hmsStatus).put("locationEnabled", p.locationEnabled)
            .putNullable("locationAvailable", p.locationAvailable).put("permission", p.permission.wireValue)
            .put("backgroundPermission", p.backgroundPermission).put("powerSave", p.powerSave).put("exactAlarmAllowed", p.exactAlarmAllowed)
            .put("degradedReasons", JSONArray(p.degradedReasons))
    }
}
