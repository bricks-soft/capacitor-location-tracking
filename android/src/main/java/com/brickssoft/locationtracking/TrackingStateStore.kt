package com.brickssoft.locationtracking

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

internal interface TrackingClock {
    fun wall(): Long
    fun elapsed(): Long
    fun boot(): Int
}
internal class AndroidTrackingClock(private val context: Context) : TrackingClock {
    override fun wall(): Long = System.currentTimeMillis()
    override fun elapsed(): Long = SystemClock.elapsedRealtime()
    // BOOT_COUNT is available from API 24. API 23 restores conservatively using receiver + elapsed reset.
    // https://developer.android.com/reference/android/provider/Settings.Global#BOOT_COUNT
    override fun boot(): Int = if (Build.VERSION.SDK_INT >= 24)
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) else -1
}
internal data class DeadlineGuard(val boot: Int, val elapsedAt: Long, val wallHigh: Long, val elapsedEnd: Long?) {
    fun reason(deadline: Long?, clock: TrackingClock, reboot: Boolean = false): String? {
        if (deadline == null) return null
        if (clock.wall() >= deadline) return "DEADLINE"
        if (differentBoot(clock, reboot)) return if (clock.wall() < wallHigh) "CLOCK_ROLLBACK" else null
        if (elapsedEnd != null && clock.elapsed() >= elapsedEnd) return "DEADLINE"
        return null
    }
    fun differentBoot(clock: TrackingClock, reboot: Boolean = false): Boolean = reboot ||
        (boot >= 0 && clock.boot() >= 0 && boot != clock.boot()) || clock.elapsed() < elapsedAt
    fun refreshed(deadline: Long?, clock: TrackingClock, reboot: Boolean = false): DeadlineGuard =
        if (differentBoot(clock, reboot)) create(deadline, clock)
        else copy(elapsedAt = clock.elapsed(), wallHigh = maxOf(wallHigh, clock.wall()))
    fun toJson(): JSONObject = JSONObject().put("boot", boot).put("elapsedAt", elapsedAt).put("wallHigh", wallHigh)
        .putNullable("elapsedEnd", elapsedEnd)
    companion object {
        fun create(deadline: Long?, clock: TrackingClock): DeadlineGuard = DeadlineGuard(clock.boot(), clock.elapsed(), clock.wall(),
            deadline?.let { clock.elapsed() + (it - clock.wall()).coerceAtLeast(0) })
        fun parse(o: JSONObject): DeadlineGuard = DeadlineGuard(o.getInt("boot"), o.getLong("elapsedAt"), o.getLong("wallHigh"),
            if (o.isNull("elapsedEnd")) null else o.getLong("elapsedEnd"))
    }
}
internal data class StoredTrackingState(
    val config: TrackingConfig? = null,
    val generation: Long = 0,
    val revision: Long = 0,
    val requested: Boolean = false,
    val reason: String? = null,
    val lastFixAt: Long? = null,
    val guard: DeadlineGuard? = null,
    val rejectedFixes: Long = 0,
    val lastExitAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("schemaVersion", 1).putNullable("config", config?.objectValue())
        .put("generation", generation).put("revision", revision).put("requested", requested).putNullable("reason", reason)
        .putNullable("lastFixAt", lastFixAt).putNullable("guard", guard?.toJson()).put("rejectedFixes", rejectedFixes).put("lastExitAt", lastExitAt)
    companion object {
        fun parse(o: JSONObject): StoredTrackingState {
            require(o.getInt("schemaVersion") == 1) { "Unsupported tracking state schema" }
            return StoredTrackingState(if (o.isNull("config")) null else TrackingConfig.parse(o.getJSONObject("config")),
                Arguments.number(o, "generation"), Arguments.number(o, "revision"), Arguments.bool(o, "requested"),
                if (o.isNull("reason")) null else o.getString("reason"),
                if (o.isNull("lastFixAt")) null else o.getLong("lastFixAt"),
                if (o.isNull("guard")) null else DeadlineGuard.parse(o.getJSONObject("guard")), o.optLong("rejectedFixes", 0), o.optLong("lastExitAt", 0))
        }
    }
}
internal interface StatePersistence {
    fun read(): StoredTrackingState
    fun write(state: StoredTrackingState)
}
/** Persists controller state with AtomicFile under noBackupFilesDir; points remain in the core queue. */
internal class TrackingStateStore(context: Context) : StatePersistence {
    private val file = AtomicFile(File(context.noBackupFilesDir, "location-tracking-state.json"))
    @Synchronized override fun read(): StoredTrackingState = try {
        StoredTrackingState.parse(JSONObject(file.openRead().bufferedReader().use { it.readText() }))
    } catch (_: FileNotFoundException) { StoredTrackingState() }
    @Synchronized override fun write(state: StoredTrackingState) {
        val stream = file.startWrite()
        try {
            stream.write(state.toJson().toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) { file.failWrite(stream); throw error }
    }
}
