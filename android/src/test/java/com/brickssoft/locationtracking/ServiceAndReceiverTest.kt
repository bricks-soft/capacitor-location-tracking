package com.brickssoft.locationtracking

import android.app.AlarmManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import com.brickssoft.tracking.location.LocationPermission
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ServiceAndReceiverTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @After fun cleanup() { TrackingRuntime.resetForTest() }
    @Test fun coldStickyServiceWithStoppedIntentStopsWithoutNotification() {
        val service = Robolectric.buildService(LocationTrackingService::class.java).create()
        assertEquals(Service.START_NOT_STICKY, service.get().onStartCommand(null, 0, 1))
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
        assertNull(shadowOf(service.get()).lastForegroundNotification)
        service.destroy()
    }
    @Test @Config(sdk = [34]) fun servicePromotesLocationNotificationWithHostConfiguration() {
        val value = configJson().put("stopAtEpochMs", System.currentTimeMillis() + 60000)
        value.getJSONObject("notification").put("smallIcon", "android:drawable/ic_menu_mylocation")
        val config = TrackingConfig.parse(value)
        TrackingStateStore(context).write(StoredTrackingState(config, 7, 1, true,
            guard = DeadlineGuard.create(config.stopAt, AndroidTrackingClock(context))))
        val service = Robolectric.buildService(LocationTrackingService::class.java).create()
        assertEquals(Service.START_STICKY, service.get().onStartCommand(Intent().putExtra("generation", 7L), 0, 1))
        val shadow = shadowOf(service.get())
        assertEquals(1001, shadow.lastForegroundNotificationId)
        assertEquals("Tracking", shadow.lastForegroundNotification.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, service.get().foregroundServiceType)
        service.destroy()
    }
    @Test fun staleStartDoesNotSubscribeOrPromote() {
        val service = Robolectric.buildService(LocationTrackingService::class.java).create()
        assertEquals(Service.START_NOT_STICKY, service.get().onStartCommand(Intent().putExtra("generation", 99L), 0, 3))
        assertNull(shadowOf(service.get()).lastForegroundNotification)
        service.destroy()
    }
    @Test fun unrecognizedBroadcastCannotRestoreService() {
        TrackingRestoreReceiver().onReceive(context, Intent("external.action.START"))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }
    @Test fun receiverEligibilityChecksProviderAndBackgroundGrantIndependently() {
        val clock = FakeClock()
        val state = StoredTrackingState(config(), requested = true, guard = DeadlineGuard.create(200000, clock))
        assertEquals("BACKGROUND_PERMISSION_REQUIRED", TrackingController.eligibility(state, provider().copy(backgroundPermission = false), clock, true))
        assertNull(TrackingController.eligibility(state, provider().copy(backgroundPermission = false), clock, false))
        assertEquals("PERMISSION_DENIED", TrackingController.eligibility(state, provider().copy(permission = LocationPermission.DENIED), clock, false))
        clock.now = 200000
        assertEquals("DEADLINE", TrackingController.eligibility(state, provider(), clock, true))
    }
    @Test fun exactAlarmSchedulesWallAndElapsedCutoffsAndCancelRemovesBoth() {
        val clock = FakeClock(); val scheduler = DeadlineScheduler(context, clock)
        val alarm = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        scheduler.arm(StoredTrackingState(config(), requested = true, guard = DeadlineGuard.create(200000, clock)))
        assertEquals(2, alarm.scheduledAlarms.size)
        assertEquals(setOf(AlarmManager.RTC_WAKEUP, AlarmManager.ELAPSED_REALTIME_WAKEUP), alarm.scheduledAlarms.map { it.getType() }.toSet())
        scheduler.cancel(); assertTrue(alarm.scheduledAlarms.isEmpty())
    }
    @Test @Config(sdk = [31]) fun deniedExactAccessUsesIdleAllowedInexactAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val clock = FakeClock(); val scheduler = DeadlineScheduler(context, clock)
        scheduler.arm(StoredTrackingState(config(), requested = true, guard = DeadlineGuard.create(200000, clock)))
        val alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms
        assertEquals(2, alarms.size)
        assertTrue(alarms.all { it.windowLengthMs != ShadowAlarmManager.WINDOW_EXACT })
    }
    @Test @Config(sdk = [34]) fun mergedManifestContainsLocationOnlyPrivateServiceAndReceivers() {
        val service = context.packageManager.getServiceInfo(android.content.ComponentName(context, LocationTrackingService::class.java), 0)
        assertFalse(service.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, service.foregroundServiceType)
        assertEquals(0, service.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        for (type in listOf(TrackingRestoreReceiver::class.java, DeadlineReceiver::class.java)) {
            assertFalse(context.packageManager.getReceiverInfo(android.content.ComponentName(context, type), 0).exported)
        }
        @Suppress("DEPRECATION") val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue("android.permission.FOREGROUND_SERVICE_LOCATION" in permissions)
        assertFalse("android.permission.ACCESS_BACKGROUND_LOCATION" in permissions)
    }
}
