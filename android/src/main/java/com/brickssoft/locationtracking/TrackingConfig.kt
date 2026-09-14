package com.brickssoft.locationtracking

import com.brickssoft.tracking.httpqueue.Authorization
import com.brickssoft.tracking.httpqueue.QueueConfig
import com.brickssoft.tracking.httpqueue.RetentionPolicy
import com.brickssoft.tracking.location.Accuracy
import com.brickssoft.tracking.location.LocationRequest
import com.brickssoft.tracking.location.ProviderKind
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal object Arguments {
    fun keys(o: JSONObject, allowed: Set<String>) {
        require(o.keys().asSequence().all { it in allowed }) { "Unsupported configuration key" }
    }
    const val MAX_SAFE = 9_007_199_254_740_991L
    fun text(o: JSONObject, key: String, max: Int = 1024): String =
        (o.get(key) as? String)?.also { require(it.isNotBlank() && it.length <= max) { "Invalid $key" } }
            ?: throw IllegalArgumentException("Invalid $key")
    fun bool(o: JSONObject, key: String): Boolean = o.get(key) as? Boolean
        ?: throw IllegalArgumentException("Invalid $key")
    fun number(o: JSONObject, key: String, min: Long = 0, max: Long = MAX_SAFE): Long {
        val value = o.get(key) as? Number ?: throw IllegalArgumentException("Invalid $key")
        val d = value.toDouble()
        require(d.isFinite() && d >= min && d <= max && d == value.toLong().toDouble()) { "Invalid $key" }
        return value.toLong()
    }
    fun optionalBool(o: JSONObject, key: String, default: Boolean = false): Boolean =
        if (o.has(key)) bool(o, key) else default
    fun optionalNumber(o: JSONObject, key: String, default: Long, min: Long = 0, max: Long = MAX_SAFE): Long =
        if (o.has(key)) number(o, key, min, max) else default
    fun headers(o: JSONObject, credentials: Boolean = false): Map<String, String> {
        val result = o.keys().asSequence().associateWith { key ->
            require(key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "Invalid header name" }
            (o.get(key) as? String)?.also { value ->
                require(value.length <= 16384 && value.all { it == '\t' || it.code in 32..126 }) { "Invalid header value" }
            } ?: throw IllegalArgumentException("Invalid header value")
        }
        val names = result.keys.map { it.lowercase(java.util.Locale.ROOT) }
        require(names.distinct().size == names.size) { "Duplicate header" }
        if (!credentials) require(names.none { it in setOf("authorization", "proxy-authorization", "cookie") }) {
            "Credentials require setAuthorization"
        }
        return result
    }
    fun authorization(o: JSONObject): Authorization = Authorization(text(o, "scopeKey"), number(o, "revision", 1),
        headers(o.getJSONObject("headers"), true),
        if (o.has("expiresAtEpochMs")) number(o, "expiresAtEpochMs", 1) else null)
    fun uuids(o: JSONObject): Set<String>? = if (!o.has("uuids")) null else o.getJSONArray("uuids").let { a ->
        require(a.length() <= 10000)
        (0 until a.length()).map { i ->
            (a.get(i) as? String)?.also { require(it.isNotBlank() && it.length <= 128) }
                ?: throw IllegalArgumentException("Invalid uuid")
        }.toSet()
    }
    fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        is Number -> { require(value.toDouble().isFinite()); value.toString() }
        is Boolean -> value.toString()
        else -> throw IllegalArgumentException("Invalid JSON")
    }
}

internal class TrackingConfig private constructor(val json: String) {
    fun objectValue(): JSONObject = JSONObject(json)
    private val value = objectValue()
    val scopeKey: String = value.getString("scopeKey")
    val sessionId: String = value.getString("sessionId")
    val stopAt: Long? = if (value.isNull("stopAtEpochMs")) null else value.getLong("stopAtEpochMs")
    val startOnBoot: Boolean = value.getBoolean("startOnBoot")
    val stopOnTerminate: Boolean = value.getBoolean("stopOnTerminate")
    val provider: ProviderKind? = ProviderKind.entries.find { it.wireValue == value.getString("provider") }
    val fallback: Boolean = value.getBoolean("allowPlatformFallback")
    val heartbeatSeconds: Long = value.getLong("heartbeatIntervalSeconds")
    val http: JSONObject get() = objectValue().getJSONObject("http")
    val notification: JSONObject get() = objectValue().getJSONObject("notification")
    val diagnostics: JSONObject get() = objectValue().getJSONObject("diagnostics")
    fun sameSession(other: TrackingConfig): Boolean = scopeKey == other.scopeKey && sessionId == other.sessionId
    fun request(generation: Long): LocationRequest = LocationRequest(generation, value.getLong("intervalMs"),
        value.getLong("minUpdateIntervalMs"), Accuracy.valueOf(value.getString("accuracy").uppercase(java.util.Locale.ROOT)))
    fun queue(revision: Long): QueueConfig = http.let { h ->
        val r = value.getJSONObject("retention")
        QueueConfig(LocationStore.QUEUE, scopeKey, revision, h.getString("url"), h.getString("method"),
            Arguments.headers(h.getJSONObject("headers")), h.getJSONObject("params").toString(), h.getString("rootProperty"),
            authRequired = h.getBoolean("authRequired"), autoSync = h.getBoolean("autoSync"),
            autoSyncThreshold = h.getInt("autoSyncThreshold"), maxBatchSize = if (h.getBoolean("batchSync")) h.getInt("maxBatchSize") else 1,
            maxBatchAgeSeconds = h.getLong("maxBatchAgeSeconds"), timeoutMillis = h.getLong("timeoutSeconds") * 1000,
            retention = RetentionPolicy(r.getInt("maxDaysToPersist"), r.getInt("maxRecordsToPersist")))
    }
    companion object {
        fun parse(o: JSONObject): TrackingConfig {
            require(o.keys().asSequence().all { it in setOf("schemaVersion", "scopeKey", "sessionId", "provider", "allowPlatformFallback",
                "intervalMs", "minUpdateIntervalMs", "heartbeatIntervalSeconds", "accuracy", "stopAtEpochMs", "startOnBoot", "stopOnTerminate",
                "http", "retention", "notification", "diagnostics") }) { "Unsupported config key" }
            require(o.toString().length <= 262144) { "Config too large" }
            Arguments.number(o, "schemaVersion", 1, 1)
            Arguments.text(o, "scopeKey"); Arguments.text(o, "sessionId")
            require(Arguments.text(o, "provider") in setOf("auto", "gms", "hms", "platform"))
            require(Arguments.text(o, "accuracy") in setOf("high", "balanced", "low"))
            Arguments.number(o, "intervalMs", 1, 86400000)
            Arguments.number(o, "minUpdateIntervalMs", 1, 86400000)
            Arguments.number(o, "heartbeatIntervalSeconds", 0, 86400)
            listOf("allowPlatformFallback", "startOnBoot", "stopOnTerminate").forEach { Arguments.bool(o, it) }
            require(o.has("stopAtEpochMs"))
            if (!o.isNull("stopAtEpochMs")) Arguments.number(o, "stopAtEpochMs", 1)
            validateHttp(o.getJSONObject("http"))
            val r = o.getJSONObject("retention")
            Arguments.keys(r, setOf("maxDaysToPersist", "maxRecordsToPersist"))
            Arguments.number(r, "maxDaysToPersist", 0, 36500)
            Arguments.number(r, "maxRecordsToPersist", -1, Int.MAX_VALUE.toLong())
            val n = o.getJSONObject("notification")
            Arguments.keys(n, setOf("id", "channelId", "channelName", "title", "text", "smallIcon"))
            Arguments.number(n, "id", 1, Int.MAX_VALUE.toLong())
            listOf("channelId", "channelName", "title", "text", "smallIcon").forEach { Arguments.text(n, it) }
            val d = o.getJSONObject("diagnostics")
            Arguments.keys(d, setOf("level", "maxBytes", "maxDays"))
            require(Arguments.text(d, "level") in DiagnosticLog.LEVELS)
            Arguments.number(d, "maxBytes", 4096, 16777216)
            Arguments.number(d, "maxDays", 1, 365)
            return TrackingConfig(Arguments.canonical(o))
        }
        private fun validateHttp(h: JSONObject) {
            Arguments.keys(h, setOf("url", "method", "rootProperty", "headers", "params", "template", "authRequired", "autoSync",
                "batchSync", "autoSyncThreshold", "maxBatchSize", "maxBatchAgeSeconds", "timeoutSeconds"))
            val url = URI(Arguments.text(h, "url", 8192))
            require(url.scheme == "https" && !url.host.isNullOrBlank() && url.rawUserInfo == null && url.rawFragment == null) { "HTTPS destination required" }
            require(Arguments.text(h, "method") in setOf("POST", "PUT", "PATCH"))
            val root = Arguments.text(h, "rootProperty")
            require(!h.getJSONObject("params").has(root)) { "params cannot overwrite rootProperty" }
            Arguments.headers(h.getJSONObject("headers"))
            LocationPayloadEncoder.validate(h.getJSONObject("template"))
            listOf("authRequired", "autoSync", "batchSync").forEach { Arguments.bool(h, it) }
            Arguments.number(h, "autoSyncThreshold", 0, 10000)
            Arguments.number(h, "maxBatchSize", 1, 10000)
            Arguments.number(h, "maxBatchAgeSeconds", 0, 86400)
            Arguments.number(h, "timeoutSeconds", 1, 900)
        }
    }
}
