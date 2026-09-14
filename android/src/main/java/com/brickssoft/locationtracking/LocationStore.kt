package com.brickssoft.locationtracking

import android.content.Context
import com.brickssoft.tracking.httpqueue.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal interface LocationRepository {
    suspend fun register(config: TrackingConfig, revision: Long)
    suspend fun nextRevision(): Long
    suspend fun enqueue(config: TrackingConfig, revision: Long, capturedAt: Long, position: JSONObject, active: Boolean): Boolean
    suspend fun count(scope: String): Int
    suspend fun purge(scope: String, uuids: Set<String>?): Int
    suspend fun pause(scope: String)
    suspend fun gate(scope: String): UploadGate
    suspend fun authorize(value: Authorization): Boolean
    suspend fun clearAuthorization(scope: String)
    suspend fun sync(scope: String): DrainResult
}
/** Uses the core queue as the only point store and pins each encoded record to its routing revision. */
internal class LocationStore(
    context: Context,
    diagnosticSink: Diagnostics = Diagnostics.NONE,
) : LocationRepository {
    private val app = context.applicationContext
    private val database by lazy { QueueDatabase(app, DATABASE, diagnostics = diagnosticSink) }
    private val scheduler by lazy { QueueScheduler(app, DATABASE) }
    private val queue by lazy { QueueClient(database, scheduler) }
    private val authorization by lazy { AuthorizationStore(database) }
    private val dispatcher by lazy { QueueDispatcher(database) }
    @Volatile var uploading: Boolean = false
        private set
    suspend fun initialize() = withContext(Dispatchers.IO) { scheduler.initialize() }
    override suspend fun register(config: TrackingConfig, revision: Long) = withContext(Dispatchers.IO) {
        queue.register(config.queue(revision)); Unit
    }
    override suspend fun nextRevision(): Long = withContext(Dispatchers.IO) {
        (database.definitions().maxOfOrNull { it.revision } ?: 0) + 1
    }
    override suspend fun enqueue(config: TrackingConfig, revision: Long, capturedAt: Long, position: JSONObject, active: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val payload = LocationPayloadEncoder.encode(config.http.getJSONObject("template"), position, active)
            queue.enqueue(QueueRecord(QUEUE, config.scopeKey, revision, capturedAt, payloadJson = payload,
                uuid = position.getString("uuid"))).retained
        }
    override suspend fun count(scope: String): Int = withContext(Dispatchers.IO) { queue.count(QUEUE, scope).count }
    override suspend fun purge(scope: String, uuids: Set<String>?): Int = withContext(Dispatchers.IO) { queue.purge(QUEUE, scope, uuids) }
    override suspend fun pause(scope: String) = withContext(Dispatchers.IO) { queue.pause(QUEUE, scope) }
    override suspend fun gate(scope: String): UploadGate = withContext(Dispatchers.IO) { database.state(QUEUE, scope).uploadGate }
    override suspend fun authorize(value: Authorization): Boolean = withContext(Dispatchers.IO) {
        authorization.setAuthorization(value).also { if (it) scheduler.requestDrain() }
    }
    override suspend fun clearAuthorization(scope: String) = withContext(Dispatchers.IO) { authorization.clear(scope) }
    override suspend fun sync(scope: String): DrainResult = withContext(Dispatchers.IO) {
        // Explicit sync may reopen a logout-paused scope only with core-validated matching credentials.
        if (gate(scope) == UploadGate.PAUSED) queue.resume(QUEUE, scope)
        uploading = true
        try { dispatcher.sync(QUEUE, scope) } finally { uploading = false }
    }
    suspend fun drain(scope: String) = withContext(Dispatchers.IO) {
        uploading = true
        try { dispatcher.drain(QUEUE, scope) } finally { uploading = false }
    }
    suspend fun connectivity(connected: Boolean) = withContext(Dispatchers.IO) { scheduler.onConnectivityChanged(connected) }
    companion object {
        const val DATABASE = "location-tracking"
        const val QUEUE = "locations"
    }
}
