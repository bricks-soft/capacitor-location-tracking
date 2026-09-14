package com.brickssoft.locationtracking

import android.content.Context
import com.brickssoft.tracking.httpqueue.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SerializationAndValidationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun nativeValidationRejectsWrongTypesBoundsAndReservedHeaders() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("intervalMs", "60000") }, { it.put("intervalMs", 1.5) }, { it.put("intervalMs", 0) },
            { it.put("allowPlatformFallback", "false") }, { it.put("schemaVersion", 2) },
            { it.getJSONObject("notification").put("id", 0) }, { it.getJSONObject("http").put("url", "http://example.invalid") },
            { it.getJSONObject("http").put("url", "https://user:pass@example.invalid") },
            { it.getJSONObject("http").put("method", "DELETE") }, { it.getJSONObject("http").put("maxBatchSize", -1) },
            { it.getJSONObject("http").getJSONObject("params").put("records", 1) },
            { it.getJSONObject("http").getJSONObject("headers").put("AUTHORIZATION", "private") },
            { it.getJSONObject("http").getJSONObject("headers").put("X-Header", "value\r\ninjected") },
            { it.getJSONObject("retention").put("maxRecordsToPersist", -2) },
        )
        mutations.forEach { mutation -> assertThrows(IllegalArgumentException::class.java) { TrackingConfig.parse(configJson().also(mutation)) } }
    }
    @Test fun bridgeOptionValidationRejectsCoercionAndUnsafeIntegers() {
        assertThrows(IllegalArgumentException::class.java) { Arguments.optionalBool(JSONObject().put("persist", 1), "persist") }
        assertThrows(IllegalArgumentException::class.java) { Arguments.number(JSONObject().put("revision", 9007199254740992L), "revision") }
        assertThrows(IllegalArgumentException::class.java) { Arguments.uuids(JSONObject().put("uuids", JSONArray().put(12))) }
        assertEquals(emptySet<String>(), Arguments.uuids(JSONObject().put("uuids", JSONArray())))
    }
    @Test fun templateKeepsTypesNestedObjectsLiteralAndNullMeasurements() {
        val template = JSONObject().put("id", JSONObject().put("\$field", "uuid"))
            .put("nested", JSONArray().put(JSONObject().put("\$field", "coords.altitudeAccuracy")).put(JSONObject().put("\$field", "mock")))
            .put("literal", JSONObject().put("\$literal", JSONObject().put("\$field", "not-an-expression")))
            .put("coords", JSONObject().put("\$field", "coords"))
        val position = LocationPayloadEncoder.position(fix(), battery(), "stable")
        val encoded = JSONObject(LocationPayloadEncoder.encode(template, position, true))
        assertEquals("stable", encoded.getString("id")); assertTrue(encoded.getJSONArray("nested").isNull(0))
        assertFalse(encoded.getJSONArray("nested").getBoolean(1)); assertEquals(0.0, encoded.getJSONObject("coords").getDouble("speed"), 0.0)
        assertEquals("not-an-expression", encoded.getJSONObject("literal").getString("\$field"))
        assertFalse(encoded.has("activity")); assertFalse(position.has("is_moving"))
    }
    @Test fun templateRejectsUnknownFieldsAmbiguousLeavesAndDeepTrees() {
        assertThrows(IllegalArgumentException::class.java) { LocationPayloadEncoder.validate(JSONObject().put("x", JSONObject().put("\$field", "credentials"))) }
        assertThrows(IllegalArgumentException::class.java) { LocationPayloadEncoder.validate(JSONObject().put("\$field", "uuid").put("also", 1)) }
        var nested = JSONObject().put("x", 1)
        repeat(34) { nested = JSONObject().put("nested", nested) }
        assertThrows(IllegalArgumentException::class.java) { LocationPayloadEncoder.validate(nested) }
    }
    @Test fun compatibilityFieldsOnlyAppearWhenTemplateRequestsThem() {
        val template = JSONObject().put("moving", JSONObject().put("\$field", "is_moving"))
            .put("activity", JSONObject().put("\$field", "activity"))
        val encoded = JSONObject(LocationPayloadEncoder.encode(template, LocationPayloadEncoder.position(fix(), battery()), true))
        assertTrue(encoded.getBoolean("moving")); assertEquals("unknown", encoded.getJSONObject("activity").getString("type"))
        assertEquals(0, encoded.getJSONObject("activity").getInt("confidence"))
    }
    @Test fun stateReopenPreservesIntentIdentityRevisionAndDeadline() {
        val clock = FakeClock()
        val value = StoredTrackingState(config(), 15, 9, true, "diagnostic", 100000, DeadlineGuard.create(200000, clock), 7)
        TrackingStateStore(context).write(value)
        val restored = TrackingStateStore(context).read()
        assertEquals(value.toJson().toString(), restored.toJson().toString())
        assertTrue(File(context.noBackupFilesDir, "location-tracking-state.json").exists())
    }
    @Test fun futureStateSchemaFailsClosedWithoutReset() {
        val file = File(context.noBackupFilesDir, "location-tracking-state.json")
        val future = """{"schemaVersion":99,"requested":true}"""
        file.writeText(future)
        assertThrows(IllegalArgumentException::class.java) { TrackingStateStore(context).read() }
        assertEquals(future, file.readText())
    }
    @Test fun clockRollbackCannotExtendElapsedGuardAndRebootIsDetected() {
        val clock = FakeClock(); val guard = DeadlineGuard.create(200000, clock)
        clock.now = 50000; clock.uptime = 100999
        assertNull(guard.reason(200000, clock))
        clock.uptime++; assertEquals("DEADLINE", guard.reason(200000, clock))
        clock.bootId++; clock.uptime = 10
        assertEquals("CLOCK_ROLLBACK", guard.reason(200000, clock, true))
    }
    @Test fun logsRedactBoundPaginateAndKeepIdsAfterClear() {
        DiagnosticLog(context).use { log ->
            log.configure(JSONObject().put("level", "debug").put("maxBytes", 4096).put("maxDays", 3))
            repeat(50) { log.record("info", "TEST", "Authorization: Bearer private token=abc https://example.invalid/private 30.123456") }
            val first = log.page(0, 2); assertEquals(2, first.first.length()); assertNotNull(first.second)
            assertFalse(first.first.toString().contains("private")); assertFalse(first.first.toString().contains("30.123456"))
            val all = log.page(0, 500).first; assertTrue(all.length() < 50)
            val id = all.getJSONObject(all.length() - 1).getLong("id")
            log.clear(); log.record("info", "NEXT"); assertTrue(log.page(0, 10).first.getJSONObject(0).getLong("id") > id)
        }
    }
    @Test fun logsExpireByAge() {
        var now = 1000000000L
        DiagnosticLog(context) { now }.use { log ->
            log.record("info", "OLD"); now += 3 * 86400000
            assertEquals(0, log.page(0, 10).first.length())
        }
    }
    @Test fun realCoreQueueBatchesRootParamsAndRetainsPinnedRevision() = runBlocking {
        val cipher = object : CredentialCipher {
            override fun encrypt(scopeKey: String, plaintext: ByteArray) = plaintext
            override fun decrypt(scopeKey: String, ciphertext: ByteArray) = ciphertext
        }
        QueueDatabase(context, "plugin-contract", clock = Clock { 100000 }, credentialCipher = cipher).use { db ->
            val client = QueueClient(db)
            val original = config(); val revised = TrackingConfig.parse(configJson().also {
                it.getJSONObject("http").put("url", "https://example.invalid/new").getJSONObject("params").put("context", "new")
            })
            client.register(original.queue(1)); client.register(revised.queue(2))
            val position = LocationPayloadEncoder.position(fix(), battery(), "uuid-one")
            client.enqueue(QueueRecord(LocationStore.QUEUE, original.scopeKey, 1, 100000,
                LocationPayloadEncoder.encode(original.http.getJSONObject("template"), position, true), uuid = "uuid-one"))
            val requests = mutableListOf<RenderedRequest>()
            val result = QueueDispatcher(db, QueueTransport { request, _, _ -> requests.add(request); TransportResult(204) }).sync(LocationStore.QUEUE, original.scopeKey)
            assertEquals(1, result.uploaded); assertEquals(0, result.remaining)
            assertEquals("https://example.invalid/points", requests.single().url)
            val body = JSONObject(requests.single().body!!)
            assertEquals(1, body.getJSONObject("context").getInt("schema"))
            assertEquals("uuid-one", body.getJSONArray("records").getJSONObject(0).getString("id"))
        }
    }
}
