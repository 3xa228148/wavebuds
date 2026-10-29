package local.wavebuds;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) && BudsService.hasPermissions(c)) c.startForegroundService(new Intent(c, BudsService.class));
    }
}
