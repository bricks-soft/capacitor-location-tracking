# @bricks-soft/capacitor-location-tracking

## What this plugin is

`@bricks-soft/capacitor-location-tracking` is an Android Capacitor plugin for fixed-interval location acquisition, durable scoped uploads, native recovery, and an absolute collection deadline. It uses `@bricks-soft/android-tracking-core` for Google fused, Huawei fused, or Android platform location and for the persistent HTTP queue.

The host supplies the endpoint, identity scope, authorization, payload template, notification text, permission UX, and deadline. Web and iOS methods reject with `UNIMPLEMENTED`.

## Public API

The TypeScript contract is in `src/definitions.ts`.

- `ready({ config, authorization? })` validates and persists a complete configuration without requesting permission or starting collection. `configure` is an exact alias.
- `start()` starts the foreground service from a visible Activity and resolves after provider registration. `stop({ pauseUploads? })` stops collection and can explicitly pause the current scope's uploads.
- `getState()` returns durable intent, active state, session, generation, upload state, queue count, last-fix data, and provider state.
- `getCurrentPosition({ timeoutSeconds?, maximumAgeMs?, persist? })` performs a bounded one-shot request without starting continuous tracking.
- `sync({ scopeKey? })`, `getCount({ scopeKey? })`, and `destroyLocations({ scopeKey, uuids? })` operate on the scoped queue.
- `requestPermissions({ background? })` handles foreground and background permission flows separately. `getProviderState()` returns a fresh capability snapshot.
- `setAuthorization` accepts a strictly newer scoped credential revision. `clearAuthorization` removes credentials and fences matching uploads.
- `setConfig({ patch })` validates a top-level configuration patch; identity changes require `ready`.
- `log`, `getDiagnostics`, and `clearDiagnostics` manage bounded operational diagnostics.
- `addListener` supports `location`, `http`, `enabledchange`, `providerchange`, `powersavechange`, and `heartbeat`. `removeAllListeners` removes bridge listeners.

`TrackingConfig` selects the provider and cadence, defines the absolute cutoff and lifecycle flags, configures HTTP encoding and retention, and supplies foreground notification and diagnostic settings. The HTTP template supports typed field placeholders for the documented position, provider, mock, battery, and optional compatibility fields.

## Guarantees and policies

- Lifecycle operations are serialized. Generation checks fence stale callbacks, configuration changes, stops, and identity changes. A location event marked `persisted` is emitted only after the queue commit.
- A small `AtomicFile` under `noBackupFilesDir` stores tracking intent and configuration. Location points live only in the core SQLite queue. The state file and queue are separate crash domains: configuration is registered before state publication, clock high-water is stored before enqueue, and disabled intent is stored before subscription removal.
- Every accepted observation receives a stable UUID, including repeated coordinates. Delivery is at least once within retention, so the server must deduplicate that UUID. Records retain their encoded payload, routing revision, scope, and capture time across retries.
- Queue ordering, batching, retry cooldowns, acknowledgement, and recovery follow the core queue policy. Default retention is one day with no count limit. Expiry continues during authorization pauses. Upload latency does not block callback persistence.
- A 401 retains records and pauses the current authorization revision. Refresh can reopen only an authentication pause. Explicitly paused or blocked scopes require a matching authorized resume or explicit host action. A new identity must use a new opaque scope.
- Changing scope or session requires the old session to be stopped and its uploads paused. Default `stop()` leaves queued points and upload eligibility intact. `destroyLocations` always requires a scope; an empty UUID list deletes nothing.
- Provider selection uses runtime capabilities, not manufacturer. Automatic selection prefers GMS, then HMS, and uses the platform provider only when `allowPlatformFallback` is enabled. Explicit selection never silently switches provider.
- `start()` requires a visible Activity and resolves only after foreground promotion and provider readiness. `enabled` means the subscription is active, not that a fix has arrived. Provider registration can complete after the first fix, so the first delivered fix also completes readiness.
- The host owns foreground permission UX. Background location is requested separately and only when the host declares `ACCESS_BACKGROUND_LOCATION`. Notification denial is reported independently from location permission.
- The saved wall deadline is never recomputed after reboot or timezone changes. Elapsed-time and boot guards prevent a rollback from extending a session. Boot or package-update restoration requires saved intent, `startOnBoot`, an unexpired deadline, and the required permission/provider state.
- `stopOnTerminate=false` preserves collection after ordinary task removal. User Stop clears collection intent when Android exposes that exit reason. Force-stop, uninstall, and OEM process restrictions are outside the plugin's lifecycle guarantees.
- Upload URLs require HTTPS by default. HTTP requires `http.allowCleartext=true` and a consuming app network-security policy that permits cleartext traffic. Credentials must stay out of URLs, params, templates, and immutable headers.
- Templates evaluate only allowlisted typed placeholders; they do not execute expressions. Missing measurements remain `null`, and valid zero values remain zero.
- Diagnostics are bounded, persisted, and paginated. They exclude queue payloads, coordinates, credentials, exception bodies, and server bodies. The core recovery worker does not forward worker-only HTTP events to the bridge; persisted queue state remains authoritative.
- The plugin supports one default app process. The host owns business-specific identity retirement, deadline calculation, endpoint idempotency, disclosure, and final device/release qualification.

## Consuming from an app

Install both packages and sync Capacitor:

```sh
npm install @bricks-soft/capacitor-location-tracking @bricks-soft/android-tracking-core
npx cap sync android
```

Include the core from the host's maintained `android/settings.gradle`:

```groovy
include ':bricks-soft-android-tracking-core'
project(':bricks-soft-android-tracking-core').projectDir =
    new File(settingsDir, '../node_modules/@bricks-soft/android-tracking-core/android')
```

The host's dependency repositories must include Google Maven, Maven Central, and Huawei:

```groovy
repositories {
    google()
    mavenCentral()
    maven { url 'https://developer.huawei.com/repo/' }
}
```

Import the plugin and provide a complete `TrackingConfig`; `android/src/test/resources/config.json` is a synthetic example. Replace its scope, session, endpoint, deadline, payload template, and notification values before use.

```ts
import { LocationTracking } from '@bricks-soft/capacitor-location-tracking';

await LocationTracking.ready({ config, authorization });
await LocationTracking.requestPermissions({ background: false });
await LocationTracking.start();
```

The plugin manifest declares its private location foreground service and receivers plus foreground-service, boot, coarse/fine location, notification, and network permissions. It deliberately does not declare background location or exact-alarm access; the consuming app must declare either permission when that feature is needed and disclosed.

## Testing

Run the Android unit tests with the repository's standalone module:

```sh
cd android
JAVA_HOME=/home/salem/.jdks/jdk-21.0.12.1+1 \
ANDROID_HOME=/home/salem/Android/Sdk \
GRADLE_USER_HOME=/tmp/workerH-gradle \
./gradlew --project-cache-dir /tmp/workerH-cache -q testDebugUnitTest
```

Build the TypeScript declarations, generated API metadata, and bundles from the repository root:

```sh
npm run build
```

The opt-in emulator test exercises provider selection, continuous persistence, explicit sync, queue state, and diagnostics against a local fake server:

1. Start the fake endpoint from the repository root with `./scripts/fake-upload-server.py --mode ok`. Modes `401`, `500`, and `flaky` reproduce authentication pause and retry paths. Requests are printed and written to `/tmp/fake-upload-server.log`.
2. Boot the target emulator, install any provider service required by the scenario, and ensure location is enabled. The instrumentation test grants coarse, fine, and notification permissions.
3. From `android`, run:
   ```sh
   JAVA_HOME=/home/salem/.jdks/jdk-21.0.12.1+1 \
   ANDROID_HOME=/home/salem/Android/Sdk \
   GRADLE_USER_HOME=/tmp/workerH-gradle \
   ./gradlew connectedDebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.smoke=true \
     -Pandroid.testInstrumentationRunnerArguments.provider=hms
   ```
   Replace `hms` with `gms`, `platform`, or `auto` as needed.
4. While the test runs, inject fixes with `adb emu geo fix <longitude> <latitude>` and inspect `adb logcat -s SMOKE`.
5. For a two-hour soak at a 60-second cadence, add `-Pandroid.testInstrumentationRunnerArguments.intervalMs=60000 -Pandroid.testInstrumentationRunnerArguments.runMinutes=120` and inject a fix every 60 seconds.

The default emulator URL is `http://10.0.2.2:8787/locations`. If the emulator cannot reach that host alias, run `adb reverse tcp:8787 tcp:8787` and add `-Pandroid.testInstrumentationRunnerArguments.url=http://127.0.0.1:8787/locations`.

## References

- https://developer.android.com/develop/background-work/services/fgs/service-types#location
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/develop/sensors-and-location/location/permissions/background
- https://developer.android.com/develop/background-work/services/alarms
- https://developer.android.com/reference/android/util/AtomicFile
- https://github.com/bricks-soft/android-tracking-core
