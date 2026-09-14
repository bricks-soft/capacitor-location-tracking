package com.brickssoft.locationtracking

import com.brickssoft.tracking.httpqueue.*
import com.brickssoft.tracking.location.*
import org.json.JSONObject

internal fun configJson(): JSONObject = JSONObject(object {}.javaClass.getResource("/config.json")!!.readText())
internal fun config(): TrackingConfig = TrackingConfig.parse(configJson())
internal fun fix(at: Long = 100000): LocationFix = LocationFix(ProviderKind.PLATFORM, at, at * 1000000, 30.0, 31.0, 5.0, 0.0, null, 0.0, null, false)
internal fun battery(): JSONObject = JSONObject().put("level", 0.5).put("isCharging", false)
internal fun provider(): ProviderState = ProviderState(ProviderKind.PLATFORM, 1, 1, true, null, LocationPermission.FINE, true, false, true, emptyList())
internal class FakeClock(var now: Long = 100000, var uptime: Long = 1000, var bootId: Int = 1) : TrackingClock {
    override fun wall() = now
    override fun elapsed() = uptime
    override fun boot() = bootId
}
internal class MemoryState : StatePersistence {
    var value = StoredTrackingState()
    var failure = false
    override fun read() = value
    override fun write(state: StoredTrackingState) { check(!failure); value = StoredTrackingState.parse(state.toJson()) }
}
internal class FakeLocations : LocationRepository {
    val configs = mutableMapOf<Long, TrackingConfig>()
    val rows = mutableListOf<Pair<Long, JSONObject>>()
    val gates = mutableMapOf<String, UploadGate>()
    var authRevision = 0L
    var failInsert = false
    var beforeInsert: suspend () -> Unit = {}
    override suspend fun register(config: TrackingConfig, revision: Long) { configs[revision] = config; gates.putIfAbsent(config.scopeKey, UploadGate.OPEN) }
    override suspend fun nextRevision(): Long = (configs.keys.maxOrNull() ?: 0) + 1
    override suspend fun enqueue(config: TrackingConfig, revision: Long, capturedAt: Long, position: JSONObject, active: Boolean): Boolean {
        beforeInsert(); check(!failInsert)
        rows.add(revision to JSONObject(position.toString())); return true
    }
    override suspend fun count(scope: String): Int = rows.count { configs[it.first]?.scopeKey == scope }
    override suspend fun purge(scope: String, uuids: Set<String>?): Int {
        val size = rows.size
        rows.removeAll { configs[it.first]?.scopeKey == scope && (uuids == null || it.second.getString("uuid") in uuids) }
        return size - rows.size
    }
    override suspend fun pause(scope: String) { gates[scope] = UploadGate.PAUSED }
    override suspend fun gate(scope: String) = gates[scope] ?: UploadGate.OPEN
    override suspend fun authorize(value: Authorization): Boolean = if (value.revision <= authRevision) false else { authRevision = value.revision; true }
    override suspend fun clearAuthorization(scope: String) { gates[scope] = UploadGate.PAUSED }
    override suspend fun sync(scope: String) = DrainResult(0, count(scope), DrainOutcome.DEFERRED)
}
internal class FakeDriver : TrackingServiceDriver {
    var starts = 0
    var stops = 0
    var startAction: suspend (Long) -> Unit = {}
    override suspend fun start(generation: Long) { starts++; startAction(generation) }
    override suspend fun stop() { stops++ }
}
internal class FakeDeadline : DeadlinePlan {
    var armed: StoredTrackingState? = null
    override fun arm(state: StoredTrackingState) { armed = state }
    override fun cancel() { armed = null }
}
