# Capacitor resolves methods/permission callbacks through reflection.
# Inspected Plugin.java in https://registry.npmjs.org/@capacitor/android/-/android-7.6.9.tgz (2026-09-14).
-keep @com.getcapacitor.annotation.CapacitorPlugin class com.brickssoft.locationtracking.LocationTrackingPlugin { *; }
-keep class com.brickssoft.locationtracking.LocationTrackingService { public <init>(); }
-keep class com.brickssoft.locationtracking.TrackingRestoreReceiver { public <init>(); }
-keep class com.brickssoft.locationtracking.DeadlineReceiver { public <init>(); }
