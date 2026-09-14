# Capacitor resolves methods/permission callbacks through reflection.
# https://registry.npmjs.org/@capacitor/android/-/android-7.6.9.tgz
-keep @com.getcapacitor.annotation.CapacitorPlugin class com.brickssoft.locationtracking.LocationTrackingPlugin { *; }
-keep class com.brickssoft.locationtracking.LocationTrackingService { public <init>(); }
-keep class com.brickssoft.locationtracking.TrackingRestoreReceiver { public <init>(); }
-keep class com.brickssoft.locationtracking.DeadlineReceiver { public <init>(); }
