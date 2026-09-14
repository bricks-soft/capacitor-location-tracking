package com.brickssoft.locationtracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.brickssoft.tracking.httpqueue.QueueSyncException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.UUID

private const val TAG = "SMOKE"
private const val DEFAULT_PROVIDER = "hms"
private const val DEFAULT_URL = "http://10.0.2.2:8787/locations"
private const val SCOPE_KEY = "smoke"

@RunWith(AndroidJUnit4::class)
class EmulatorSmokeTest {
    @get:Rule
    val grantPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @Test
    fun emulatorSmokeEndToEnd() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Smoke test disabled by default", isSmokeEnabled(args))

        val requestedProvider = normalizeProvider(args.getString("provider") ?: DEFAULT_PROVIDER)
        val endpoint = normalizeEndpoint(args.getString("url") ?: DEFAULT_URL)
        // Soak knobs: sampling interval and run length (defaults reproduce the 3-minute smoke run).
        val intervalMs = (args.getString("intervalMs") ?: "15000").toLong()
        val runMinutes = (args.getString("runMinutes") ?: "3").toLong()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stopAtEpochMs = System.currentTimeMillis() + (runMinutes + 30L) * 60L * 1000L

        grantPermissionViaUiAutomation(context)
        assertPermissionsEnabled(context)

        val runtime = TrackingRuntime.get(context)
        var persistedLocations = 0
        var lastDiagnosticId = 0L
        val observedReasons = mutableSetOf<String>()

        val listener: (String, JSONObject) -> Unit = { name, value ->
            if (name == "location" && value.optBoolean("persisted")) {
                persistedLocations += 1
                Log.i(TAG, "persisted location: ${value.optJSONObject("position")?.optString("uuid")}")
            }
            if (name == "http") {
                Log.i(TAG, "http event: ${value.toString()}")
            }
            if (name == "providerchange") {
                Log.i(TAG, "providerchange: selected=${value.optString("selected")} reasons=${value.optJSONArray("degradedReasons")}")
            }
        }

        try {
            val config = buildSmokeConfig(
                endpoint = endpoint,
                provider = requestedProvider,
                sessionId = UUID.randomUUID().toString(),
                stopAtEpochMs = stopAtEpochMs,
                intervalMs = intervalMs,
            )
            runtime.logs.configure(config.diagnostics)
            runtime.logs.clear()
            runtime.initialize()
            runtime.listeners.add(listener)
            runtime.controller.ready(config)
            runtime.controller.start()

            val deadlineMs = SystemClock.elapsedRealtime() + runMinutes * 60_000L
            while (SystemClock.elapsedRealtime() < deadlineMs) {
                val state = runtime.state()
                val providerState = TrackingController.providerJson(runtime.controller.providerState())
                val queueCount = state.getInt("queueCount")
                val uploadState = state.getString("uploadState")
                Log.i(TAG, "state: ${state.toString(2)}")
                Log.i(TAG, "providerState: ${providerState.toString(2)}")
                Log.i(TAG, "queueCount=$queueCount uploadState=$uploadState")

                lastDiagnosticId = logDiagnostics(runtime, lastDiagnosticId, observedReasons)
                // Short smoke runs stop at two fixes; soak runs (runMinutes > 3) run for the full window.
                if (persistedLocations >= 2 && runMinutes <= 3) {
                    break
                }
                SystemClock.sleep(if (runMinutes > 3) 30_000L else 3_000L)
            }

            val syncOutcome = try {
                runtime.locations.sync(SCOPE_KEY)
                "drained"
            } catch (error: QueueSyncException) {
                error.result.outcome.toString().lowercase(Locale.ROOT)
            }

            runtime.controller.stop()

            val finalState = runtime.state()
            val finalProvider = finalState.getJSONObject("provider").optString("selected")
            val finalUploadState = finalState.getString("uploadState")
            val finalQueueCount = finalState.getInt("queueCount")

            Log.i(TAG, "sync outcome: $syncOutcome")
            Log.i(TAG, "final state: ${finalState.toString(2)}")

            assertEquals("provider selected mismatch", requestedProvider, finalProvider)
            assertTrue("Expected at least 2 persisted locations", persistedLocations >= 2)
            if (finalUploadState != "idle" || finalQueueCount != 0) {
                assertTrue(
                    "Expected explicit upload reason when queue is not fully drained, sync outcome: $syncOutcome", 
                    syncOutcome != "drained" || observedReasons.any { it.isNotBlank() }
                )
            } else {
                assertEquals("expected queue drained when uploadState is idle", 0, finalQueueCount)
            }
        } finally {
            runtime.listeners.remove(listener)
            runCatching { runtime.controller.stop() }
            TrackingRuntime.resetForTest()
            Log.i(TAG, "persisted locations observed: $persistedLocations")
        }
    }

    @After
    fun cleanRuntimeState() {
        TrackingRuntime.resetForTest()
    }

    private fun isSmokeEnabled(args: Bundle): Boolean {
        return args.getString("smoke") == "true" || System.getProperty("smoke") == "true"
    }

    private fun normalizeProvider(value: String): String {
        return when (value.lowercase(Locale.ROOT)) {
            "hms", "gms", "platform", "auto" -> value.lowercase(Locale.ROOT)
            else -> DEFAULT_PROVIDER
        }
    }

    private fun normalizeEndpoint(value: String): String {
        return when {
            value.startsWith("https://") -> value
            value.startsWith("http://") -> value // allowed via http.allowCleartext=true
            else -> "https://$value"
        }
    }

    private fun buildSmokeConfig(
        endpoint: String,
        provider: String,
        sessionId: String,
        stopAtEpochMs: Long,
        intervalMs: Long = 15_000,
    ): TrackingConfig {
        val template = JSONObject()
            .put("uuid", JSONObject().put("\$field", "uuid"))
            .put("timestamp", JSONObject().put("\$field", "timestamp"))
            .put("event", "location")
            .put(
                "coords", JSONObject()
                    .put("latitude", JSONObject().put("\$field", "coords.latitude"))
                    .put("longitude", JSONObject().put("\$field", "coords.longitude"))
                    .put("accuracy", JSONObject().put("\$field", "coords.accuracy"))
            )
            .put(
                "raw_event", JSONObject()
                    .put("provider", JSONObject().put("\$field", "provider"))
                    .put(
                        "position", JSONObject()
                            .put("uuid", JSONObject().put("\$field", "uuid"))
                            .put("coords", JSONObject().put("\$field", "coords"))
                    )
            )

        return TrackingConfig.parse(
            JSONObject()
                .put("schemaVersion", 1)
                .put("scopeKey", SCOPE_KEY)
                .put("sessionId", sessionId)
                .put("provider", provider)
                .put("allowPlatformFallback", false)
                .put("intervalMs", intervalMs)
                .put("minUpdateIntervalMs", intervalMs)
                .put("heartbeatIntervalSeconds", 30)
                .put("accuracy", "high")
                .put("stopAtEpochMs", stopAtEpochMs)
                .put("startOnBoot", false)
                .put("stopOnTerminate", true)
                .put(
                    "http", JSONObject()
                        .put("url", endpoint)
                        .put("method", "POST")
                        .put("rootProperty", "locations")
                        .put("headers", JSONObject().put("X-Test", "smoke"))
                        .put("params", JSONObject().put("info", JSONObject().put("service", "smoke")))
                        .put("template", template)
                        .put("authRequired", false)
                        .put("autoSync", true)
                        .put("batchSync", true)
                        .put("autoSyncThreshold", 2)
                        .put("maxBatchSize", 50)
                        .put("maxBatchAgeSeconds", 0)
                        .put("timeoutSeconds", 30)
                        .put("allowCleartext", true)
                )
                .put(
                    "retention", JSONObject()
                        .put("maxDaysToPersist", 1)
                        .put("maxRecordsToPersist", -1)
                )
                .put(
                    "notification", JSONObject()
                        .put("id", 1001)
                        .put("channelId", "smoke")
                        .put("channelName", "Smoke")
                        .put("title", "Smoke test")
                        .put("text", "Smoke test tracking")
                        .put("smallIcon", "android:drawable/ic_menu_mylocation")
                )
                .put(
                    "diagnostics", JSONObject()
                        .put("level", "debug")
                        .put("maxBytes", 1_048_576)
                        .put("maxDays", 3)
                )
        )
    }

    private fun assertPermissionsEnabled(context: Context) {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        assumeTrue("coarse permission required", coarse)
        assumeTrue("fine permission required", fine)
        if (Build.VERSION.SDK_INT >= 33) {
            assumeTrue(
                "notification permission required",
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )
        }
    }

    private fun grantPermissionViaUiAutomation(context: Context) {
        val packageName = context.packageName
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        ).forEach { permission ->
            if (permission == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33) return@forEach
            runCatching {
                instrumentation.uiAutomation.executeShellCommand("pm grant $packageName $permission").close()
            }.onFailure { error ->
                Log.w(TAG, "uiAutomation grant failed for $permission: ${error.message}")
            }
        }
    }

    private fun logDiagnostics(runtime: TrackingRuntime, afterId: Long, observed: MutableSet<String>): Long {
        val page = runtime.logs.page(afterId, 200)
        val entries = page.first
        if (entries.length() == 0) {
            return afterId
        }

        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val id = entry.optLong("id")
            val reason = entry.optString("reason")
            val level = entry.optString("level")
            val at = entry.optString("at")
            val message = entry.optString("message")
            val details = entry.optJSONObject("details")
            if (reason.isNotBlank()) {
                observed.add(reason)
            }
            Log.i(
                TAG,
                "diag id=$id level=$level at=$at reason=$reason message=$message details=${details ?: "none"}",
            )
        }

        return entries.getJSONObject(entries.length() - 1).optLong("id", afterId)
    }
}
