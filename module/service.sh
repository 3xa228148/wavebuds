#!/system/bin/sh
# Runtime permissions are not auto-granted to priv-apps; grant them on every boot (idempotent).
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
for p in BLUETOOTH_CONNECT BLUETOOTH_SCAN POST_NOTIFICATIONS; do
  pm grant local.wavebuds android.permission.$p 2>/dev/null
done
