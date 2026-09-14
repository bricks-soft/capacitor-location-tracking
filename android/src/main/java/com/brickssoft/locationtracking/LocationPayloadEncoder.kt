package com.brickssoft.locationtracking

import com.brickssoft.tracking.location.LocationFix
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal fun iso(epoch: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(epoch))
internal fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

internal object LocationPayloadEncoder {
    private val fields = setOf("uuid", "timestamp", "provider", "coords", "mock", "battery", "battery.level", "battery.isCharging",
        "coords.latitude", "coords.longitude", "coords.accuracy", "coords.altitude", "coords.altitudeAccuracy", "coords.speed", "coords.heading",
        "is_moving", "activity", "activity.type", "activity.confidence")
    fun position(fix: LocationFix, battery: JSONObject, uuid: String = UUID.randomUUID().toString()): JSONObject {
        require(fix.latitude.isFinite() && fix.latitude in -90.0..90.0 && fix.longitude.isFinite() && fix.longitude in -180.0..180.0)
        require(fix.acquiredAtEpochMs >= 0)
        return JSONObject().put("uuid", uuid).put("timestamp", iso(fix.acquiredAtEpochMs)).put("provider", fix.provider.wireValue)
            .put("coords", JSONObject().put("latitude", fix.latitude).put("longitude", fix.longitude)
                .putNullable("accuracy", fix.accuracy).putNullable("altitude", fix.altitude)
                .putNullable("altitudeAccuracy", fix.altitudeAccuracy).putNullable("speed", fix.speed).putNullable("heading", fix.heading))
            .put("mock", fix.mock).put("battery", battery)
    }
    fun validate(template: JSONObject) { visit(template, null, false) }
    fun encode(template: JSONObject, position: JSONObject, active: Boolean): String = (visit(template, position, active) as JSONObject).toString()
    private fun visit(value: Any?, position: JSONObject?, active: Boolean, depth: Int = 0): Any {
        require(depth <= 32) { "Template too deep" }
        return when (value) {
            is JSONObject -> when {
                value.has("\$literal") -> {
                    require(value.length() == 1) { "Literal must be a leaf" }
                    value.get("\$literal")
                }
                value.has("\$field") -> {
                    require(value.length() == 1) { "Field must be a leaf" }
                    val field = Arguments.text(value, "\$field")
                    require(field in fields) { "Unknown template field" }
                    if (position == null) JSONObject.NULL else field(position, field, active)
                }
                else -> JSONObject().apply { value.keys().forEach { put(it, visit(value.get(it), position, active, depth + 1)) } }
            }
            is JSONArray -> JSONArray().apply { for (i in 0 until value.length()) put(visit(value.get(i), position, active, depth + 1)) }
            null -> JSONObject.NULL
            else -> { Arguments.canonical(value); value }
        }
    }
    private fun field(position: JSONObject, path: String, active: Boolean): Any {
        // Compatibility values describe tracking mode only, never measured movement.
        val compatibility = JSONObject().put("type", "unknown").put("confidence", 0)
        if (path == "is_moving") return active
        if (path == "activity") return compatibility
        if (path.startsWith("activity.")) return compatibility.get(path.substringAfter('.'))
        var value: Any = position
        for (key in path.split('.')) value = (value as? JSONObject)?.get(key)
            ?: throw IllegalArgumentException("Missing template field")
        return value
    }
}
