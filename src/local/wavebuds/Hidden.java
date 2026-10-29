package local.wavebuds;

import android.bluetooth.BluetoothDevice;
import android.util.Log;

import java.lang.reflect.Method;

/** System APIs this priv-app needs that the public SDK hides. */
final class Hidden {
    private Hidden() {}

    /** Lifts the hidden-API filter for this process (meta-reflection, works through Android 14). */
    static void exemptAll() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclared = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vmr = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclared.invoke(vmr, "getRuntime", null);
            Method setExemptions = (Method) getDeclared.invoke(vmr, "setHiddenApiExemptions", new Class[]{String[].class});
            setExemptions.invoke(getRuntime.invoke(null), new Object[]{new String[]{"L"}});
        } catch (Exception e) {
            Log.w(BudsService.TAG, "hidden api exemption failed", e);
        }
    }

    /** BluetoothDevice#connect(): connects every profile the device's policy allows (A2DP, HFP). */
    static boolean connect(BluetoothDevice d) {
        try {
            Object rc = BluetoothDevice.class.getMethod("connect").invoke(d);
            Log.i(BudsService.TAG, "connect() -> " + rc);
            return true;
        } catch (Exception e) {
            Log.w(BudsService.TAG, "connect() failed", e);
            return false;
        }
    }
}
