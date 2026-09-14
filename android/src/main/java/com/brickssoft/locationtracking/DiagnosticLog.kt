package com.brickssoft.locationtracking

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Stores bounded operational records without queue payloads or exception or HTTP response text. */
internal class DiagnosticLog(context: Context, private val now: () -> Long = System::currentTimeMillis) :
    SQLiteOpenHelper(context, File(context.noBackupFilesDir, "location-tracking-log.db").path, null, 1), java.io.Closeable {
    private var maxBytes = 1048576L
    private var maxDays = 3L
    private var level = "info"
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, json TEXT NOT NULL, bytes INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported diagnostics migration")
    }
    @Synchronized fun configure(config: JSONObject) {
        maxBytes = config.getLong("maxBytes"); maxDays = config.getLong("maxDays"); level = config.getString("level")
        prune(writableDatabase)
    }
    @Synchronized fun record(severity: String, reason: String, message: String? = null, details: JSONObject? = null) {
        require(severity in LEVELS)
        if (LEVELS.indexOf(severity) > LEVELS.indexOf(level)) return
        val at = now()
        val entry = JSONObject().put("at", iso(at)).put("level", severity).put("reason", reason.take(80))
        if (message != null) entry.put("message", redact(message))
        // Only internally generated numeric/status metadata is accepted here.
        if (details != null) entry.put("details", details)
        val encoded = entry.toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("entries", null, ContentValues().apply {
                put("at", at); put("json", encoded); put("bytes", encoded.toByteArray(Charsets.UTF_8).size + 32)
            })
            prune(db); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun page(afterId: Long, limit: Int): Pair<JSONArray, Long?> {
        require(afterId >= 0 && limit in 1..500)
        val db = writableDatabase
        prune(db)
        val result = JSONArray()
        var next: Long? = null
        db.rawQuery("SELECT id,json FROM entries WHERE id>? ORDER BY id LIMIT ?", arrayOf(afterId.toString(), (limit + 1).toString())).use { c ->
            while (c.moveToNext()) {
                if (result.length() == limit) { next = result.getJSONObject(limit - 1).getLong("id"); break }
                result.put(JSONObject(c.getString(1)).put("id", c.getLong(0)))
            }
        }
        return result to next
    }
    @Synchronized fun clear() { writableDatabase.delete("entries", null, null) }
    private fun prune(db: SQLiteDatabase) {
        db.delete("entries", "at<=?", arrayOf((now() - maxDays * 86400000).toString()))
        var total = db.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM entries", null).use { it.moveToFirst(); it.getLong(0) }
        db.rawQuery("SELECT id,bytes FROM entries ORDER BY id", null).use { c ->
            while (total > maxBytes && c.moveToNext()) {
                db.delete("entries", "id=?", arrayOf(c.getLong(0).toString())); total -= c.getLong(1)
            }
        }
    }
    companion object {
        val LEVELS = listOf("error", "warn", "info", "debug")
        fun redact(message: String): String = message.take(2048)
            .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer [REDACTED]")
            .replace(Regex("(?i)(authorization|cookie|token|password|secret|api[-_]?key)\\s*[:=]\\s*[^\\s,;]+"), "$1=[REDACTED]")
            .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer [REDACTED]")
            .replace(Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"), "[REDACTED]")
            .replace(Regex("https?://\\S+"), "[URL]")
            .replace(Regex("[-+]?\\d{1,3}\\.\\d{3,}"), "[NUMBER]")
    }
}
