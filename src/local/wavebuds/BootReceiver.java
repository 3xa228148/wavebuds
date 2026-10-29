package local.wavebuds;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (BudsService.hasPermissions(c)) c.startForegroundService(new Intent(c, BudsService.class));
    }
}
