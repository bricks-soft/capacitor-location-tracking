# @bricks-soft/capacitor-location-tracking

Generic Android Capacitor plugin with fixed-interval location acquisition, durable scoped uploads, native recovery and a collection deadline. No endpoint, identity scheme, notification copy, login/refresh flow or business payload is embedded. The accepted API is in `src/definitions.ts`; `configure` is an exact alias of `ready`. Web and iOS reject `UNIMPLEMENTED`.

## Install and integrate

The standalone package is tested with **Capacitor 7.6.9**, JDK 21, Kotlin 2.2.0, AGP 8.7.2 and compile SDK 36. Its peer range stays within the tested Capacitor major. The parallel host upgrade must qualify its resulting major separately. The scaffold AGP emits an advisory that its tested compile SDK is 35; no warning suppression was added.

```sh
npm install @bricks-soft/capacitor-location-tracking @bricks-soft/android-tracking-core
npx cap sync android
```

Add the core's one Gradle include to the host's maintained `android/settings.gradle`, outside generated Capacitor files:

```groovy
include ':bricks-soft-android-tracking-core'
project(':bricks-soft-android-tracking-core').projectDir =
    new File(settingsDir, '../node_modules/@bricks-soft/android-tracking-core/android')
```

The host needs Google, Maven Central and `https://developer.huawei.com/repo/` repositories. The plugin depends on `:capacitor-android` and `:bricks-soft-android-tracking-core`. Capacitor discovers the plugin through package metadata. Use the same core instance in the default app process. This plugin neither applies AG Connect nor ships host AGC configuration.

`npm install --save-dev ../android-tracking-core` supplies the local standalone core dependency; the peer dependency remains for consumers. Standalone settings resolve the symlink to its real source and place dependency build products under this repository's `android/build-dependencies/`, keeping sibling sources untouched.

## Host configuration and permissions

Supply every `TrackingConfig` field. A complete synthetic configuration is in `android/src/test/resources/config.json`; replace the endpoint, opaque scope/session, absolute deadline and notification icon/copy before use. Notification `smallIcon` resolves a host drawable or mipmap resource name. `stopAtEpochMs: null` disables the cutoff. The default diagnostic store before configuration is 1 MiB of accounted entries / 3 days; the supplied diagnostics configuration replaces those limits. Byte accounting includes UTF-8 records plus overhead; it is not an exact SQLite-file-size promise.

1. Call `getState()` before reconciling a cold native session, then `ready({config, authorization})`. `ready` validates/persists and does not request permission or newly start collection. A changed active configuration invalidates old callbacks and replaces the subscription. Identical configuration is idempotent; authorization revisions must still strictly increase.
2. Call `requestPermissions({background:false})` from visible UI. Coarse/fine are requested together, followed by the independent notification permission on API 33+. A coarse grant stays coarse; notification denial does not pretend location was denied.
3. If unattended restoration is part of the host's disclosed feature, the **host** declares `android.permission.ACCESS_BACKGROUND_LOCATION`, provides its own educational/decline UI, then separately calls `requestPermissions({background:true})`. The plugin rejects this call without foreground permission or a host declaration. API 29 requests background separately; API 30+ opens app settings and resolves with a fresh probe when the Activity resumes. A returned denied background state is not a grant.
4. Call `start()` while the Activity is visible. It resolves after foreground promotion and provider registration, never just after dispatching a service intent. `enabled` means an active subscription, not receipt of a fix. `requestedEnabled` is durable user intent.

The manifest declares a private location-only FGS with `stopWithTask=false`, private restoration/deadline receivers, foreground-service/type, boot, coarse/fine, notification, network and exact-alarm permissions. It deliberately does not declare background location. Exact-alarm special access is optional: if the host removes that manifest permission or the user denies access, alarms use `setAndAllowWhileIdle`. No special-access settings screen is opened automatically.

## Persistence, identity and cutoff guarantees

`TrackingController` serializes lifecycle operations, and its state mutex covers generation/session checks, clock checkpoint, queue commit and event emission. Multiple fixes are sorted before acceptance. The core already sorts vendor batches before its individual callbacks; service callbacks enter a bounded FIFO channel. Every observation gets one UUID, including repeated coordinates. UUIDs and encoded data are preserved through retries. Overflow is diagnosed and stops acquisition; no successful persistence event is fabricated.

A small `AtomicFile` under `noBackupFilesDir` stores intent, scope/session, full configuration, immutable queue revision, generation, deadline guard, last-fix time and last blocked reason. This avoids altering the core's private schema/API. There is **one point store**, the core queue. State and queue are separate crash domains, with deliberate ordering:

- Register immutable queue configuration before publishing the new tracking state. An interrupted registration can leave an unused revision; the next registration advances past it.
- Persist clock high-water before enqueue. Stop and identity changes cannot run between the generation check and commit because they take the same mutex.
- Persist disabled intent and advance generation before removing subscriptions or cancelling alarms. Committed points remain valid if death occurs before updating last-fix metadata or emitting an event. Events are not an acknowledgement ledger.

These guarantees require one default app process. They do not claim an atomic transaction across the two stores or recovery of a fix that never committed. Unknown newer/corrupt state is rejected without destructive reset. The queue and protected authorization storage are also excluded from backup by the core; SQLite payloads are not encrypted by this plugin.

A scope/session change requires the previous session to be stopped and its uploads explicitly paused. Use `stop({pauseUploads:true})` and `clearAuthorization({scopeKey})` for retirement. Default `stop()` leaves upload eligibility and points intact. `sync({scopeKey})` forwards to core manual sync, rejects an incomplete drain, and can explicitly reopen a paused scope only if core validates matching credentials. Refresh alone cannot reopen explicitly paused/blocked queues. Never reuse an opaque scope for a different identity. `destroyLocations` always requires an explicit scope; an empty UUID list deletes nothing.

Every callback and persisted one-shot checks generation and the logical deadline. The saved wall deadline is never recomputed on boot/timezone changes. A second elapsed-time alarm and monotonic guard prevent wall-clock rollback from extending a session within a boot. Boot count (API 24+) and elapsed reset detect reboot; explicit boot broadcasts also handle API 23. A rollback below the persisted wall high-water after reboot records `CLOCK_ROLLBACK` and blocks restoration. An explicit `ready` reconciles that clock and leaves collection stopped until `start`. Reboot during a gap can only detect rollback relative to the last persisted high-water, not prove the true wall time.

Boot/update restoration requires unlock, requested intent, `startOnBoot`, an unexpired deadline and actual provider/background permission. It re-arms the cutoff even when restoration is permission-blocked. Receiver work returns after dispatching the FGS; it does not hold a broadcast open while waiting for a location registration. Background-start and security exceptions become durable reasons. A sticky service restart independently checks permissions and deadline. Active-apps user Stop is recognized from new process-exit evidence on API 30+ and clears collection intent. Force-stop/uninstall/OEM kills cannot be defeated. `stopOnTerminate:false` keeps collection after ordinary recents removal; true requests a durable stop.

## Payloads, queue and diagnostics

Templates recurse through JSON objects/arrays. A leaf `{"$field":"coords.latitude"}` substitutes a typed field; `{"$literal":...}` escapes an object. Unknown fields, mixed marker leaves and excessive nesting are rejected. No expressions execute. Allowlisted fields are `uuid`, `timestamp`, `provider`, `coords`, every declared `coords.*`, `mock`, `battery`, `battery.level`, `battery.isCharging`, and opt-in compatibility `is_moving`, `activity`, `activity.type`, `activity.confidence`. Missing measurements are JSON null; zero measurements remain zero. Compatibility movement means active tracking, and activity is unknown/0. Neither is added unless requested. Odometer and proprietary timestamp metadata are not invented; host literals are explicit choices.

Each encoded record is inserted into core `JSON_RECORD_BATCH` with its pinned revision. Core adds the configured array `rootProperty` and merges `http.params` at the request root. Params cannot overwrite the array. `batchSync:false` uses singleton arrays. HTTPS and credential-free config headers are validated; credentials go only through `AuthorizationStore`. Keep secrets out of endpoint query strings, params, templates and host log messages. Custom credential header names must never overlap immutable headers.

The core owns retention, immutable routing, ordering, retry cooldowns, acknowledgement and recovery workers. `maxDaysToPersist:1` / `maxRecordsToPersist:-1` gives one-day/unlimited-count retention. Delivery is at least once within retention, so the host endpoint must deduplicate the stable UUID. `maxBatchAgeSeconds` enables periodic drain checks while the service is active; heartbeat emits diagnostics only. Upload latency never blocks callback persistence.

`getCurrentPosition` uses the selected core's bounded one-shot, defaults to `persist:false`, and never starts the continuous service. It removes its temporary subscription on completion/error/cancellation. Its acquisition timeout can be followed by the core's bounded cleanup time. Persisting requires a configured, unchanged session and unexpired cutoff.

Logs are bounded, persisted and paginated by monotonically increasing IDs. They contain operational reasons/numeric metadata, with bearer/JWT/header-value/URL/coordinate-like text redaction for bounded host messages. Native exceptions, server bodies, credentials and location payloads are never logged. Redaction is not a general-purpose secret detector; host code must not log credentials or payloads. Only the latest cold-start enabled/provider snapshots are retained for bridge listeners; no location replay is retained.

**Core diagnostics limitation:** the current core worker reconstructs its database with `Diagnostics.NONE`. Live HTTP events from this plugin's dispatcher are forwarded after core accounting, but worker-only HTTP events cannot be replayed through the bridge. Also the core omits UUID/auth-revision metadata on some retry/block outcomes; those fields are empty/0 when unavailable. `getState`, count and persisted upload gates remain authoritative. Full worker event history would need a persistent diagnostic sink extension in the core, which this task did not modify.

## Verification and remaining qualification

```sh
npm run test:web
cd android
JAVA_HOME=/home/salem/.jdks/jdk-21.0.12.1+1 \
ANDROID_HOME=/home/salem/Android/Sdk \
GRADLE_USER_HOME=/tmp/location-plugin-gradle \
JAVA_TOOL_OPTIONS=-Duser.home=/tmp/location-plugin-home \
./gradlew --project-cache-dir /tmp/location-plugin-cache --max-workers=2 \
  -Pkotlin.compiler.execution.strategy=in-process test assembleRelease --console=plain
```

The web check builds strict TypeScript, updates this README/docgen JSON, builds Rollup bundles, and tests all 19 methods through the distributed CJS bridge. JVM tests cover controller races, failed commit/no event, callback ordering/UUIDs, configuration revisions, template mapping, queue payload integration, state reopen/schema rejection, cutoff/rollback, restore eligibility, private manifest components, negative service lifecycle, alarm fallback and diagnostic bounds/redaction/pagination. Kotlin warnings are errors in this module.

No emulator is usable on this host. Real GMS/HMS fixes, eight-hour screen-off behavior, OEM kills, update/reboot, foreground/background settings UX, process death, Android Keystore, non-preloaded-HMS background mode, final host Capacitor upgrade, minified R8 reconstruction and final APK/AAB 16 KB behavior still require host/device qualification. This library AAR build is not that evidence.

## Emulator smoke test

The smoke path is built as an Android instrumentation test that must be opt-in.

Commands (on host, from this repository):

```sh
cd /home/salem/coding/bricks-soft/capacitor-location-tracking

# 1) start test HTTP endpoint
cd scripts && ./fake-upload-server.py --mode ok

# 2) optional note: if the emulator cannot route 10.0.2.2, use adb reverse; otherwise keep the default URL note in mind
# adb reverse tcp:8787 tcp:8787

# 3) install HMS Core in the API 34 x86_64 emulator when required by scenario
adb install path/to/HMSCore.apk

# 4) run the smoke test (opt-in with smoke=true)
cd ../android
JAVA_HOME=/home/salem/.jdks/jdk-21.0.12.1+1 \
ANDROID_HOME=/home/salem/Android/Sdk \
GRADLE_USER_HOME=/tmp/workerG-gradle \
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.smoke=true -Pandroid.testInstrumentationRunnerArguments.provider=hms

# Soak variant (2 h at the production 60 s cadence): add
#   -Pandroid.testInstrumentationRunnerArguments.intervalMs=60000 -Pandroid.testInstrumentationRunnerArguments.runMinutes=120
# and feed a fix every 60 s.

# 5) while running, feed the emulator location fixes
adb emu geo fix <lon> <lat>

# 6) inspect SMOKE-tagged logs from the test run
adb logcat -s SMOKE
```

If you pass a custom URL to the test, use:

```sh
cd android
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.smoke=true -Pandroid.testInstrumentationRunnerArguments.url=http://10.0.2.2:8787/locations
```

Note: `TrackingConfig.parse` requires HTTPS URLs in this checkout, so the test normalizes HTTP arguments to HTTPS before runtime config parsing.

Verified locally on **2026-09-14**: **40 plugin tests + 64 core tests per variant** (debug and release), zero failures/errors/skips. The final `test assembleRelease` invocation returned `BUILD SUCCESSFUL in 45s` (200 tasks). The release AAR is `android/build/outputs/aar/android-release.aar`. `npm run test:web` passed the full build and 19 rejection checks; the npm package-content check also passed. Full file inventory and exact commands are in `IMPLEMENTATION_REPORT.md`.

## Technical evidence

Sources retrieved or actual artifacts inspected **2026-09-14**. Versions are tested pins, not claims of newest release.

| Fact or API | Source and verification |
| --- | --- |
| Android 14 location FGS type, permissions, foreground/background distinction | [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types#location), [launch and promotion](https://developer.android.com/develop/background-work/services/fgs/launch) |
| Boot/package replacement exemptions, security/start exceptions | [Background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) |
| Android 15 boot-prohibited types exclude location | [Target-35 behavior](https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed) |
| Exact/inexact idle alarms, optional special access, re-arm | [Alarms guide](https://developer.android.com/develop/background-work/services/alarms), [AlarmManager](https://developer.android.com/reference/android/app/AlarmManager) |
| Channel requirements and notification permission independent of FGS eligibility | [Notification channels](https://developer.android.com/develop/ui/compose/notifications/channels), [notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission) |
| Foreground first, separate background request, API 30+ settings | [Background location](https://developer.android.com/develop/sensors-and-location/location/permissions/background) |
| Atomic state and backup exclusion | [AtomicFile](https://developer.android.com/reference/android/util/AtomicFile), [Context.noBackupFilesDir](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir()), [SQLiteOpenHelper](https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper) |
| Boot identity, monotonic time, lifecycle and bounded receiver work | [BOOT_COUNT](https://developer.android.com/reference/android/provider/Settings.Global#BOOT_COUNT), [SystemClock](https://developer.android.com/reference/android/os/SystemClock), [Service](https://developer.android.com/reference/android/app/Service), [BroadcastReceiver.goAsync](https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()) |
| Active-apps Stop and exit history | [User stopping](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping), [ActivityManager](https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,int,int)), [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo#REASON_USER_REQUESTED) |
| Battery, receiver, foreground and alarm signatures/API guards | [BatteryManager](https://developer.android.com/reference/android/os/BatteryManager), [Context](https://developer.android.com/reference/android/content/Context), plus installed `/home/salem/Android/Sdk/platforms/android-36/android.jar` and `android-stubs-src.jar`; compiled and selected stubs inspected |
| Capacitor bridge, annotations, listeners, permission callbacks, Java 21 target | [Android 7.6.9 artifact](https://registry.npmjs.org/@capacitor/android/-/android-7.6.9.tgz), [Core 7.6.9 artifact](https://registry.npmjs.org/@capacitor/core/-/core-7.6.9.tgz); npm-installed package source/build files and declarations inspected |
| Core queue/provider contracts | [Source repository](https://github.com/bricks-soft/android-tracking-core); actual sibling `0.1.0` README and all `android/src/main/java/com/brickssoft/tracking/**` sources read; linked dependency compiled, core tests executed |
| Coroutines 1.10.2, Robolectric 4.16, AppCompat 1.7.0 | [Coroutines POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.10.2/kotlinx-coroutines-android-1.10.2.pom), [Robolectric POM](https://repo.maven.apache.org/maven2/org/robolectric/robolectric/4.16/robolectric-4.16.pom), [AppCompat AAR](https://dl.google.com/dl/android/maven2/androidx/appcompat/appcompat/1.7.0/appcompat-1.7.0.aar); resolved artifacts/POMs inspected; Robolectric shadow signatures inspected with javap |
| Scaffold compiler/build pins | [AGP 8.7.2 POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.7.2/gradle-8.7.2.pom), [Kotlin 2.2.0 POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/2.2.0/kotlin-gradle-plugin-2.2.0.pom), [Gradle 8.11.1](https://services.gradle.org/distributions/gradle-8.11.1-all.zip); resolved local artifacts used by the commands above |

## API

<docgen-index>

* [`ready(...)`](#ready)
* [`configure(...)`](#configure)
* [`start()`](#start)
* [`stop(...)`](#stop)
* [`getState()`](#getstate)
* [`getCurrentPosition(...)`](#getcurrentposition)
* [`sync(...)`](#sync)
* [`getCount(...)`](#getcount)
* [`destroyLocations(...)`](#destroylocations)
* [`requestPermissions(...)`](#requestpermissions)
* [`getProviderState()`](#getproviderstate)
* [`setAuthorization(...)`](#setauthorization)
* [`clearAuthorization(...)`](#clearauthorization)
* [`setConfig(...)`](#setconfig)
* [`log(...)`](#log)
* [`getDiagnostics(...)`](#getdiagnostics)
* [`clearDiagnostics()`](#cleardiagnostics)
* [`addListener(E, ...)`](#addlistenere-)
* [`removeAllListeners()`](#removealllisteners)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### ready(...)

```typescript
ready(options: { config: TrackingConfig; authorization?: Authorization; }) => any
```

| Param         | Type                                                                                                                               |
| ------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| **`options`** | <code>{ config: <a href="#trackingconfig">TrackingConfig</a>; authorization?: <a href="#authorization">Authorization</a>; }</code> |

**Returns:** <code>any</code>

--------------------


### configure(...)

```typescript
configure(options: { config: TrackingConfig; authorization?: Authorization; }) => any
```

| Param         | Type                                                                                                                               |
| ------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| **`options`** | <code>{ config: <a href="#trackingconfig">TrackingConfig</a>; authorization?: <a href="#authorization">Authorization</a>; }</code> |

**Returns:** <code>any</code>

--------------------


### start()

```typescript
start() => any
```

**Returns:** <code>any</code>

--------------------


### stop(...)

```typescript
stop(options?: { pauseUploads?: boolean | undefined; } | undefined) => any
```

| Param         | Type                                     |
| ------------- | ---------------------------------------- |
| **`options`** | <code>{ pauseUploads?: boolean; }</code> |

**Returns:** <code>any</code>

--------------------


### getState()

```typescript
getState() => any
```

**Returns:** <code>any</code>

--------------------


### getCurrentPosition(...)

```typescript
getCurrentPosition(options?: { timeoutSeconds?: number | undefined; maximumAgeMs?: number | undefined; persist?: boolean | undefined; } | undefined) => any
```

| Param         | Type                                                                                |
| ------------- | ----------------------------------------------------------------------------------- |
| **`options`** | <code>{ timeoutSeconds?: number; maximumAgeMs?: number; persist?: boolean; }</code> |

**Returns:** <code>any</code>

--------------------


### sync(...)

```typescript
sync(options?: { scopeKey?: string | undefined; } | undefined) => any
```

| Param         | Type                                |
| ------------- | ----------------------------------- |
| **`options`** | <code>{ scopeKey?: string; }</code> |

**Returns:** <code>any</code>

--------------------


### getCount(...)

```typescript
getCount(options?: { scopeKey?: string | undefined; } | undefined) => any
```

| Param         | Type                                |
| ------------- | ----------------------------------- |
| **`options`** | <code>{ scopeKey?: string; }</code> |

**Returns:** <code>any</code>

--------------------


### destroyLocations(...)

```typescript
destroyLocations(options: { scopeKey: string; uuids?: string[]; }) => any
```

| Param         | Type                                           |
| ------------- | ---------------------------------------------- |
| **`options`** | <code>{ scopeKey: string; uuids?: {}; }</code> |

**Returns:** <code>any</code>

--------------------


### requestPermissions(...)

```typescript
requestPermissions(options: { background?: boolean; }) => any
```

| Param         | Type                                   |
| ------------- | -------------------------------------- |
| **`options`** | <code>{ background?: boolean; }</code> |

**Returns:** <code>any</code>

--------------------


### getProviderState()

```typescript
getProviderState() => any
```

**Returns:** <code>any</code>

--------------------


### setAuthorization(...)

```typescript
setAuthorization(options: Authorization) => any
```

| Param         | Type                                                    |
| ------------- | ------------------------------------------------------- |
| **`options`** | <code><a href="#authorization">Authorization</a></code> |

**Returns:** <code>any</code>

--------------------


### clearAuthorization(...)

```typescript
clearAuthorization(options: { scopeKey: string; }) => any
```

| Param         | Type                               |
| ------------- | ---------------------------------- |
| **`options`** | <code>{ scopeKey: string; }</code> |

**Returns:** <code>any</code>

--------------------


### setConfig(...)

```typescript
setConfig(options: { patch: Partial<TrackingConfig>; }) => any
```

| Param         | Type                         |
| ------------- | ---------------------------- |
| **`options`** | <code>{ patch: any; }</code> |

**Returns:** <code>any</code>

--------------------


### log(...)

```typescript
log(options: { level: LogLevel; message: string; }) => any
```

| Param         | Type                                                                       |
| ------------- | -------------------------------------------------------------------------- |
| **`options`** | <code>{ level: <a href="#loglevel">LogLevel</a>; message: string; }</code> |

**Returns:** <code>any</code>

--------------------


### getDiagnostics(...)

```typescript
getDiagnostics(options?: { afterId?: number | undefined; limit?: number | undefined; } | undefined) => any
```

| Param         | Type                                               |
| ------------- | -------------------------------------------------- |
| **`options`** | <code>{ afterId?: number; limit?: number; }</code> |

**Returns:** <code>any</code>

--------------------


### clearDiagnostics()

```typescript
clearDiagnostics() => any
```

**Returns:** <code>any</code>

--------------------


### addListener(E, ...)

```typescript
addListener<E extends keyof TrackingEvents>(eventName: E, listener: (event: TrackingEvents[E]) => void) => any
```

| Param           | Type                                               |
| --------------- | -------------------------------------------------- |
| **`eventName`** | <code>E</code>                                     |
| **`listener`**  | <code>(event: TrackingEvents[E]) =&gt; void</code> |

**Returns:** <code>any</code>

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => any
```

**Returns:** <code>any</code>

--------------------


### Interfaces


#### TrackingConfig

| Prop                           | Type                                                                                                                                                                                                                                                                                                                                                                                                             |
| ------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`schemaVersion`**            | <code>1</code>                                                                                                                                                                                                                                                                                                                                                                                                   |
| **`provider`**                 | <code><a href="#provider">Provider</a></code>                                                                                                                                                                                                                                                                                                                                                                    |
| **`allowPlatformFallback`**    | <code>boolean</code>                                                                                                                                                                                                                                                                                                                                                                                             |
| **`intervalMs`**               | <code>number</code>                                                                                                                                                                                                                                                                                                                                                                                              |
| **`minUpdateIntervalMs`**      | <code>number</code>                                                                                                                                                                                                                                                                                                                                                                                              |
| **`heartbeatIntervalSeconds`** | <code>number</code>                                                                                                                                                                                                                                                                                                                                                                                              |
| **`accuracy`**                 | <code>'high' \| 'balanced' \| 'low'</code>                                                                                                                                                                                                                                                                                                                                                                       |
| **`stopAtEpochMs`**            | <code>number \| null</code>                                                                                                                                                                                                                                                                                                                                                                                      |
| **`startOnBoot`**              | <code>boolean</code>                                                                                                                                                                                                                                                                                                                                                                                             |
| **`stopOnTerminate`**          | <code>boolean</code>                                                                                                                                                                                                                                                                                                                                                                                             |
| **`http`**                     | <code>{ url: string; method: 'POST' \| 'PUT' \| 'PATCH'; rootProperty: string; headers: any; params: { [key: string]: <a href="#json">Json</a>; }; template: { [key: string]: <a href="#json">Json</a>; }; authRequired: boolean; autoSync: boolean; batchSync: boolean; autoSyncThreshold: number; maxBatchSize: number; maxBatchAgeSeconds: number; timeoutSeconds: number; allowCleartext?: boolean; }</code> |
| **`retention`**                | <code>{ maxDaysToPersist: number; maxRecordsToPersist: number; }</code>                                                                                                                                                                                                                                                                                                                                          |
| **`notification`**             | <code>{ id: number; channelId: string; channelName: string; title: string; text: string; smallIcon: string; }</code>                                                                                                                                                                                                                                                                                             |
| **`diagnostics`**              | <code>{ level: <a href="#loglevel">LogLevel</a>; maxBytes: number; maxDays: number; }</code>                                                                                                                                                                                                                                                                                                                     |


#### Authorization

| Prop                   | Type                                      |
| ---------------------- | ----------------------------------------- |
| **`scopeKey`**         | <code>string</code>                       |
| **`revision`**         | <code>number</code>                       |
| **`headers`**          | <code>Record&lt;string, string&gt;</code> |
| **`expiresAtEpochMs`** | <code>number</code>                       |


#### TrackingState

| Prop                               | Type                                                                         |
| ---------------------------------- | ---------------------------------------------------------------------------- |
| **`rejectedFixes`**                | <code>number</code>                                                          |
| **`lastFixGapMs`**                 | <code>number \| null</code>                                                  |
| **`effectiveMinUpdateIntervalMs`** | <code>number \| null</code>                                                  |
| **`requestedEnabled`**             | <code>boolean</code>                                                         |
| **`enabled`**                      | <code>boolean</code>                                                         |
| **`session`**                      | <code><a href="#scope">Scope</a> \| null</code>                              |
| **`generation`**                   | <code>number</code>                                                          |
| **`configRevision`**               | <code>number</code>                                                          |
| **`uploadState`**                  | <code>'idle' \| 'uploading' \| 'offline' \| 'authPaused' \| 'blocked'</code> |
| **`lastFixAt`**                    | <code>string \| null</code>                                                  |
| **`lastStopReason`**               | <code>string \| null</code>                                                  |
| **`queueCount`**                   | <code>number</code>                                                          |
| **`provider`**                     | <code><a href="#providerstate">ProviderState</a></code>                      |


#### Scope

| Prop            | Type                |
| --------------- | ------------------- |
| **`scopeKey`**  | <code>string</code> |
| **`sessionId`** | <code>string</code> |


#### ProviderState

| Prop                       | Type                                        |
| -------------------------- | ------------------------------------------- |
| **`selected`**             | <code>any</code>                            |
| **`gmsStatus`**            | <code>number \| null</code>                 |
| **`hmsStatus`**            | <code>number \| null</code>                 |
| **`locationEnabled`**      | <code>boolean</code>                        |
| **`locationAvailable`**    | <code>boolean \| null</code>                |
| **`permission`**           | <code>'denied' \| 'coarse' \| 'fine'</code> |
| **`backgroundPermission`** | <code>boolean</code>                        |
| **`powerSave`**            | <code>boolean</code>                        |
| **`exactAlarmAllowed`**    | <code>boolean</code>                        |
| **`degradedReasons`**      | <code>{}</code>                             |


#### Position

| Prop            | Type                                                                                                                                                                                        |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`uuid`**      | <code>string</code>                                                                                                                                                                         |
| **`timestamp`** | <code>string</code>                                                                                                                                                                         |
| **`provider`**  | <code>Exclude&lt;<a href="#provider">Provider</a>, 'auto'&gt;</code>                                                                                                                        |
| **`coords`**    | <code>{ latitude: number; longitude: number; accuracy: number \| null; altitude: number \| null; altitudeAccuracy: number \| null; speed: number \| null; heading: number \| null; }</code> |
| **`mock`**      | <code>boolean</code>                                                                                                                                                                        |
| **`battery`**   | <code>{ level: number \| null; isCharging: boolean; }</code>                                                                                                                                |


#### SyncResult

| Prop            | Type                                                                           |
| --------------- | ------------------------------------------------------------------------------ |
| **`uploaded`**  | <code>number</code>                                                            |
| **`remaining`** | <code>number</code>                                                            |
| **`outcome`**   | <code>'offline' \| 'authPaused' \| 'blocked' \| 'drained' \| 'deferred'</code> |


#### TrackingEvents

| Prop                  | Type                                                                                                          |
| --------------------- | ------------------------------------------------------------------------------------------------------------- |
| **`location`**        | <code>{ position: <a href="#position">Position</a>; persisted: boolean; generation: number; }</code>          |
| **`http`**            | <code>{ status: number \| null; uuids: {}; outcome: string; remaining: number; authRevision: number; }</code> |
| **`enabledchange`**   | <code>{ enabled: boolean; requestedEnabled: boolean; reason: string; }</code>                                 |
| **`providerchange`**  | <code><a href="#providerstate">ProviderState</a></code>                                                       |
| **`powersavechange`** | <code>{ enabled: boolean; }</code>                                                                            |
| **`heartbeat`**       | <code>{ at: string; lastFixAt: string \| null; queueCount: number; }</code>                                   |


#### PluginListenerHandle

| Prop         | Type                      |
| ------------ | ------------------------- |
| **`remove`** | <code>() =&gt; any</code> |


### Type Aliases


#### Provider

<code>'auto' | 'gms' | 'hms' | 'platform'</code>


#### Json

<code>null | boolean | number | string | Json[] | { [key: string]: <a href="#json">Json</a> }</code>


#### LogLevel

<code>'error' | 'warn' | 'info' | 'debug'</code>

</docgen-api>
