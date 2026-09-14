# LocationTracking implementation report

Implemented in `/home/salem/coding/bricks-soft/capacitor-location-tracking` only. No git commands were run. The shared core source and app repositories were not modified; standalone dependency build products were redirected into this repo.

## Files

Created:

- `LICENSE`
- `package-lock.json`
- `src/definitions.ts`
- `src/index.ts`
- `src/web.ts`
- `scripts/test-web.cjs`
- `android/src/main/java/com/brickssoft/locationtracking/DeadlineReceiver.kt`
- `android/src/main/java/com/brickssoft/locationtracking/DeadlineScheduler.kt`
- `android/src/main/java/com/brickssoft/locationtracking/DeviceSnapshot.kt`
- `android/src/main/java/com/brickssoft/locationtracking/DiagnosticLog.kt`
- `android/src/main/java/com/brickssoft/locationtracking/LocationPayloadEncoder.kt`
- `android/src/main/java/com/brickssoft/locationtracking/LocationStore.kt`
- `android/src/main/java/com/brickssoft/locationtracking/LocationTrackingPlugin.kt`
- `android/src/main/java/com/brickssoft/locationtracking/LocationTrackingService.kt`
- `android/src/main/java/com/brickssoft/locationtracking/TrackingConfig.kt`
- `android/src/main/java/com/brickssoft/locationtracking/TrackingController.kt`
- `android/src/main/java/com/brickssoft/locationtracking/TrackingRestoreReceiver.kt`
- `android/src/main/java/com/brickssoft/locationtracking/TrackingRuntime.kt`
- `android/src/main/java/com/brickssoft/locationtracking/TrackingStateStore.kt`
- `android/src/test/java/com/brickssoft/locationtracking/SerializationAndValidationTest.kt`
- `android/src/test/java/com/brickssoft/locationtracking/ServiceAndReceiverTest.kt`
- `android/src/test/java/com/brickssoft/locationtracking/TestFixtures.kt`
- `android/src/test/java/com/brickssoft/locationtracking/TrackingControllerTest.kt`
- `android/src/test/resources/config.json`
- `IMPLEMENTATION_REPORT.md` (this report)
- Generated `dist/` declarations, ESM/CJS/IIFE bundles, sourcemaps and docgen JSON.

Changed: `package.json`, `.gitignore`, `README.md`, `android/build.gradle`, `android/settings.gradle`, `android/consumer-rules.pro`, `android/src/main/AndroidManifest.xml`.

## Public API

Preserved every accepted definition and method from architecture section 5. Registered `LocationTracking`; `configure` aliases `ready`; unsupported platforms reject `UNIMPLEMENTED`. Added optional `TrackingState.rejectedFixes`, `lastFixGapMs` and `effectiveMinUpdateIntervalMs` diagnostics. Implemented full-config/top-level-patch validation, lifecycle/session fencing, current position, queue operations, scoped authorization, permissions/provider state, events and paginated diagnostics.

Native implementation includes the location-only foreground service, immutable configuration revisions, queue commit before location event, separate atomic tracking intent store, boot/update restoration, wall/elapsed deadline alarms and checks, bounded redacted diagnostics, connectivity drain triggers and user-stop exit recognition. README explains the separate-store crash ordering, permission onboarding and host integration.

## Final verification

From the repository root:

```sh
npm run test:web
```

Result: PASS. This runs `npm run build` (docgen updates README, strict `tsc`, Rollup), then verifies all 19 methods reject `UNIMPLEMENTED` through the generated CommonJS registration.

```sh
JAVA_HOME=/home/salem/.jdks/jdk-21.0.12.1+1 \
ANDROID_HOME=/home/salem/Android/Sdk \
GRADLE_USER_HOME=/tmp/location-plugin-gradle \
JAVA_TOOL_OPTIONS=-Duser.home=/tmp/location-plugin-home \
./android/gradlew -p android --project-cache-dir /tmp/location-plugin-cache \
  --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  test assembleRelease --console=plain
```

Result: **BUILD SUCCESSFUL in 45s**, 200 actionable tasks. Plugin: 40 debug + 40 release tests. Shared core: 64 debug + 64 release tests. All 208 tests have zero failures, errors or skips. Plugin Kotlin compilation treats warnings as errors. Android release AAR assembled at `android/build/outputs/aar/android-release.aar`.

```sh
npm pack --dry-run --json --ignore-scripts --cache /tmp/location-plugin-npm
```

Result: PASS. Verified the package includes Android sources/manifest, consumer rules, declarations, bundles, README and license; excludes `node_modules` and dependency build caches. No package was published. Build/test logs remain in `/tmp/location-plugin-build.log` and `/tmp/location-plugin-web.log`; JUnit XML is under each module's build/test-results directory.

## Open questions and unverified layers

- **Core API extension:** the core recovery worker uses `Diagnostics.NONE`; it cannot supply durable worker HTTP history. Some retry/block diagnostics also omit UUID/auth-revision metadata. The plugin forwards available events and exposes persisted queue state, but full worker history needs a persistent sink in the core. The core was not changed.
- **Host upgrade:** only Capacitor 7.6.9 is qualified here, with peer `^7.6.9`. The host's parallel upgrade must validate its resulting major and final minified dependency graph. The scaffold AGP 8.7.2 warns that compile SDK 36 exceeds its tested SDK 35; no warning bypass was added.
- **Device qualification:** no usable emulator/KVM is available. Real HMS/GMS acquisition, notification/background-settings UX, Keystore recovery, force-stop/reboot/update/OEM behavior, sustained gaps/battery, non-preloaded-HMS background mode, final R8 and 16 KB APK/AAB behavior remain untested. No production endpoint or device actions were performed.
- **Host policy:** the host owns background-location disclosure/declaration, identity retirement, deadline calculation, UUID idempotency and any Transistorsoft compatibility literals. The generic plugin does not invent odometer or proprietary timestamp metadata.

Technical fact sources and artifact inspection dates are recorded in README and the relevant source comments (retrieved/inspected 2026-09-14).
