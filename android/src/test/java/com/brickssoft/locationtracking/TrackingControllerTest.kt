package com.brickssoft.locationtracking

import com.brickssoft.tracking.httpqueue.Authorization
import com.brickssoft.tracking.location.LocationPermission
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackingControllerTest {
    private lateinit var memory: MemoryState
    private lateinit var rows: FakeLocations
    private lateinit var driver: FakeDriver
    private lateinit var clock: FakeClock
    private lateinit var alarms: FakeDeadline
    private lateinit var controller: TrackingController
    private var capability = provider()
    private val events = mutableListOf<Pair<String, JSONObject>>()
    @Before fun setup() {
        memory = MemoryState(); rows = FakeLocations(); driver = FakeDriver(); clock = FakeClock(); alarms = FakeDeadline()
        controller = TrackingController(memory, rows, clock, driver, alarms, { capability }, { n, p -> events.add(n to p) })
    }
    @Test fun readyIsIdempotentAndDoesNotStart() = runBlocking {
        controller.ready(config()); val revision = controller.stored.revision
        controller.ready(TrackingConfig.parse(JSONObject(config().json)))
        assertEquals(revision, controller.stored.revision); assertEquals(0, driver.starts); assertFalse(controller.stored.requested)
    }
    @Test fun startWaitsForSubscriptionAndPublishesEnabled() = runBlocking {
        controller.ready(config())
        val ready = CompletableDeferred<Unit>()
        driver.startAction = { ready.await() }
        val start = async { controller.start() }; yield()
        assertFalse(start.isCompleted); assertTrue(controller.stored.requested); assertFalse(controller.enabled)
        ready.complete(Unit); start.await()
        assertTrue(controller.enabled); assertTrue(events.any { it.first == "enabledchange" && it.second.getBoolean("enabled") })
    }
    @Test fun startFailureRetainsIntentAndDurableReason() = runBlocking {
        controller.ready(config()); driver.startAction = { throw TrackingFailure("FGS_START_NOT_ALLOWED") }
        assertFails { controller.start() }
        assertTrue(controller.stored.requested); assertFalse(controller.enabled)
        assertEquals("FGS_START_NOT_ALLOWED", memory.value.reason)
    }
    @Test fun stoppedGenerationRejectsLateFixesAndPreservesQueue() = runBlocking {
        controller.ready(config()); controller.start(); val generation = controller.stored.generation
        controller.accept(generation, listOf(fix()), battery()); controller.stop()
        controller.accept(generation, listOf(fix()), battery())
        assertEquals(1, rows.rows.size); assertEquals(1, controller.stored.rejectedFixes)
        assertFalse(controller.stored.requested); assertNull(alarms.armed)
    }
    @Test fun callbackBatchSortsAndIdenticalCoordinatesHaveDifferentUuids() = runBlocking {
        controller.ready(config()); controller.start()
        val positions = controller.accept(controller.stored.generation, listOf(fix(100002), fix(100000), fix(100001)), battery())
        assertEquals(listOf(iso(100000), iso(100001), iso(100002)), positions.map { it.getString("timestamp") })
        assertEquals(3, positions.map { it.getString("uuid") }.distinct().size)
        assertEquals(3, events.count { it.first == "location" })
    }
    @Test fun persistenceCompletesBeforeEventAndStopWaitsForCommit() = runBlocking {
        controller.ready(config()); controller.start()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        rows.beforeInsert = { entered.complete(Unit); release.await() }
        val accepted = async { controller.accept(controller.stored.generation, listOf(fix()), battery()) }
        entered.await(); val stopped = async { controller.stop() }; yield()
        assertFalse(stopped.isCompleted); assertFalse(events.any { it.first == "location" })
        release.complete(Unit); accepted.await(); stopped.await()
        assertEquals(1, rows.rows.size); assertFalse(controller.stored.requested)
    }
    @Test fun failedInsertNeverEmitsLocation() = runBlocking {
        controller.ready(config()); controller.start(); rows.failInsert = true
        assertFails { controller.accept(controller.stored.generation, listOf(fix()), battery()) }
        assertFalse(events.any { it.first == "location" })
    }
    @Test fun switchingRequiresStoppedPausedOldSession() = runBlocking {
        controller.ready(config()); controller.start()
        val next = TrackingConfig.parse(configJson().put("scopeKey", "next").put("sessionId", "next"))
        assertFails { controller.ready(next) }; controller.stop()
        assertFails { controller.ready(next) }; controller.stop(true)
        controller.ready(next); assertEquals("next", controller.stored.config!!.scopeKey)
    }
    @Test fun activeConfigChangesFenceOldCallbacksAndKeepRevisions() = runBlocking {
        controller.ready(config()); controller.start(); val old = controller.stored.generation
        controller.accept(old, listOf(fix()), battery())
        controller.patch(JSONObject().put("intervalMs", 30000))
        controller.accept(old, listOf(fix()), battery())
        controller.accept(controller.stored.generation, listOf(fix()), battery())
        assertEquals(listOf(1L, 2L), rows.rows.map { it.first }); assertEquals(2, driver.starts)
    }
    @Test fun invalidPatchDoesNotPartiallyChangeConfig() = runBlocking {
        controller.ready(config()); val before = controller.stored.config!!.json
        assertFails { controller.patch(JSONObject().put("http", JSONObject().put("url", "http://bad"))) }
        assertEquals(before, controller.stored.config!!.json); assertEquals(1, rows.configs.size)
    }
    @Test fun restorationRequiresRequestedFlagBootFlagDeadlineAndPermission() = runBlocking {
        controller.ready(config()); assertFalse(controller.restore(true, true))
        controller.start(); controller.serviceDestroyed(controller.stored.generation)
        capability = capability.copy(backgroundPermission = false)
        assertFalse(controller.restore(true, true)); assertEquals("BACKGROUND_PERMISSION_REQUIRED", controller.stored.reason)
        capability = capability.copy(backgroundPermission = true)
        assertFalse(controller.restore(true, false))
        clock.now = 200000; assertFalse(controller.restore(true, true)); assertFalse(controller.stored.requested)
    }
    @Test fun restoreDoesNotExtendDeadlineAcrossReboot() = runBlocking {
        controller.ready(config()); controller.start(); controller.serviceDestroyed(controller.stored.generation)
        clock.now = 150000; clock.uptime = 10; clock.bootId++
        assertTrue(controller.restore(true, true)); assertEquals(200000L, controller.stored.config!!.stopAt)
        assertEquals(50010L, controller.stored.guard!!.elapsedEnd)
    }
    @Test fun bootRollbackBlocksWithoutCollecting() = runBlocking {
        controller.ready(config()); controller.start(); controller.serviceDestroyed(controller.stored.generation)
        clock.now = 90000; clock.bootId++
        assertFalse(controller.restore(true, true)); assertEquals("CLOCK_ROLLBACK", controller.stored.reason)
    }
    @Test fun elapsedDeadlineRejectsDelayedCallbacksAfterWallRollback() = runBlocking {
        controller.ready(config()); controller.start(); clock.now = 50000; clock.uptime = 101000
        controller.accept(controller.stored.generation, listOf(fix(99999)), battery())
        assertEquals(0, rows.rows.size); assertFalse(controller.checkDeadline()); assertFalse(controller.stored.requested)
    }
    @Test fun deniedPermissionIsARejectedStart() = runBlocking {
        controller.ready(config()); capability = capability.copy(permission = LocationPermission.DENIED, selected = null)
        assertFails { controller.start() }; assertEquals("PERMISSION_DENIED", controller.stored.reason); assertEquals(0, driver.starts)
    }
    @Test fun oneShotPersistenceIsFencedWithoutStartingService() = runBlocking {
        controller.ready(config()); val saved = controller.stored
        controller.persistOneShot(saved.generation, saved.config!!, fix(), LocationPayloadEncoder.position(fix(), battery()))
        assertEquals(0, driver.starts); assertFalse(controller.stored.requested)
        controller.stop()
        assertFails { controller.persistOneShot(saved.generation, saved.config, fix(), LocationPayloadEncoder.position(fix(), battery())) }
    }
    @Test fun authorizationScopeMismatchAndStaleRevisionReject() = runBlocking {
        assertFails { controller.ready(config(), Authorization("wrong", 1, emptyMap())) }
        controller.ready(config(), Authorization("test-identity", 2, emptyMap()))
        assertFails { controller.authorize(Authorization("test-identity", 1, emptyMap())) }
    }
    @Test fun stoppedIntentAndBootOptOutNeverRestore() = runBlocking {
        controller.ready(TrackingConfig.parse(configJson().put("startOnBoot", false))); controller.start()
        controller.serviceDestroyed(controller.stored.generation)
        assertFalse(controller.restore(true, true)); assertEquals(1, driver.starts)
        assertNotNull(alarms.armed)
        controller.stop(); assertFalse(controller.restore(true, true))
    }
    @Test fun userStopExitIsConsumedOnceAndDoesNotRestartCollection() = runBlocking {
        controller.ready(config()); controller.start()
        controller.observeExit(123, true)
        assertFalse(controller.stored.requested); assertEquals("USER_STOPPED", memory.value.reason)
        assertFalse(controller.restore(false, true))
        controller.start(); val generation = controller.stored.generation
        controller.observeExit(123, true)
        assertTrue(controller.stored.requested); assertEquals(generation, controller.stored.generation)
    }
    @Test fun explicitReadyReconcilesBootRollbackButRequiresNewStart() = runBlocking {
        controller.ready(config()); controller.start(); controller.serviceDestroyed(controller.stored.generation)
        clock.bootId++; clock.now = 90000
        assertFalse(controller.restore(true, true))
        controller.ready(config()); assertFalse(controller.stored.requested)
        controller.start(); assertTrue(controller.enabled)
    }
    @Test fun staleAuthorizationDoesNotPartiallyApplyNewConfiguration() = runBlocking {
        controller.ready(config(), Authorization("test-identity", 2, emptyMap()))
        val revision = controller.stored.revision
        assertFails { controller.ready(TrackingConfig.parse(configJson().put("intervalMs", 30000)), Authorization("test-identity", 1, emptyMap())) }
        assertEquals(revision, controller.stored.revision)
        assertEquals(60000L, controller.stored.config!!.request(1).intervalMs)
    }
    private suspend fun assertFails(block: suspend () -> Any?) {
        try { block(); fail("Expected failure") } catch (expected: Exception) { assertNotNull(expected) }
    }
}
