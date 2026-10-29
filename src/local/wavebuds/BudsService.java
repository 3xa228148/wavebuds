package local.wavebuds;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps the earbuds connected and configured.
 *
 * Classic side (audio): whenever the buds advertise but A2DP is down, connect the profiles; if
 * the bond is gone, re-bond the classic address the buds reported over BLE and confirm the
 * pairing ourselves (silent only as a priv-app with BLUETOOTH_PRIVILEGED).
 * BLE side (control): keep a GATT link, mirror the buds' state, and push the saved profile
 * whenever the buds come back with different settings.
 */
@SuppressLint("MissingPermission")
public class BudsService extends Service {
    static final String TAG = "WaveBuds";
    private static final String CH = "status";
    private static final long RECONNECT_GAP_MS = 12_000;
    private static final long REPAIR_RETRY_MS = 15_000;
    private static final long REPLY_TIMEOUT_MS = 1500;
    private static final long SETUP_TIMEOUT_MS = 6000;
    /** A pairing request is auto-confirmed only this long after the app itself started that bond. */
    private static final long BOND_CONFIRM_MS = 30_000;
    private static final ParcelUuid HARMAN_SD = new ParcelUuid(Proto.HARMAN_SD);
    private static final ParcelUuid FAST_PAIR = new ParcelUuid(Proto.FAST_PAIR);

    static final String[] PERMISSIONS = {
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS,
    };

    static BudsService instance;

    interface Listener { void onChanged(); }

    final State state = new State();
    final ArrayDeque<String> log = new ArrayDeque<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final Handler h = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private BluetoothAdapter adapter;
    private BluetoothA2dp a2dp;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tx, rx;
    private byte[] advertData;
    private long lastClassicAttempt;
    private long lastBondAttempt;
    private String bondingMac;
    private long bondStartedAt;

    // Nothing is read or written until the connected buds prove to be ours (their info frame).
    private boolean subscribed, verified;
    /** LE addresses of buds that turned out to be someone else's (or malformed); they rotate, so in memory only. */
    private final Set<String> rejected = new HashSet<>();
    private String silentAddr;
    private int silentCount;

    // One request on the wire at a time; the next goes after its reply (or a timeout).
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private int awaiting = -1;
    // The reply notification can arrive before Android finishes the write; a new write then fails BUSY.
    private boolean writing;
    private boolean profileApplied;

    static boolean hasPermissions(Context c) {
        for (String p : PERMISSIONS) {
            if (!p.equals(Manifest.permission.POST_NOTIFICATIONS) && c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("buds", MODE_PRIVATE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN));
        startForeground(1, notification());
        if (!hasPermissions(this)) {
            stopSelf();
            return;
        }
        instance = this;
        adapter = getSystemService(BluetoothManager.class).getAdapter();
        if (privileged()) Hidden.exemptAll();

        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(btReceiver, f);
        IntentFilter pf = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        pf.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        registerReceiver(pairingReceiver, pf);

        adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int p, BluetoothProfile proxy) { a2dp = (BluetoothA2dp) proxy; kick(R.string.why_start); }
            @Override public void onServiceDisconnected(int p) { a2dp = null; }
        }, BluetoothProfile.A2DP);

        log(getString(R.string.log_started));
        kick(R.string.why_start);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
            h.removeCallbacksAndMessages(null);
            unregisterReceiver(btReceiver);
            unregisterReceiver(pairingReceiver);
            stopScan();
            closeGatt();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------- UI API

    void addListener(Listener l) { listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Profile = what the user chose in this app; the buds are brought back to it on every connect. */
    int want(String key, int def) { return prefs.getInt(key, def); }

    void set(String key, int value) {
        prefs.edit().putInt(key, value).apply();
        log(getString(R.string.log_setting, key, String.valueOf(value)));
        if (state.ready) push(key);
        changed();
    }

    float[] wantEqGains() {
        float[] g = new float[Proto.EQ_FREQS.length];
        String s = prefs.getString(K_EQ_GAINS, null);
        if (s == null) return g;
        String[] parts = s.split(",");
        for (int i = 0; i < g.length && i < parts.length; i++) g[i] = Float.parseFloat(parts[i]);
        return g;
    }

    void setEqGains(float[] gains) {
        StringBuilder sb = new StringBuilder();
        for (float v : gains) sb.append(sb.length() == 0 ? "" : ",").append(v);
        prefs.edit().putString(K_EQ_GAINS, sb.toString()).putInt(K_EQ, Proto.EQ_CUSTOM).apply();
        if (state.ready) push(K_EQ);
        changed();
    }

    void find(int left, int right) { send(Proto.find(left, right)); }

    void reconnect() {
        lastClassicAttempt = 0;
        lastBondAttempt = 0;
        closeGatt();
        kick(R.string.why_manual);
    }

    // ---------------------------------------------------------------- orchestration

    private void kick(int why) {
        if (!adapter.isEnabled()) { stopScan(); return; }
        BluetoothDevice buds = targetDevice();
        state.audio = buds != null && classicConnected(buds);
        if (buds != null && buds.getBondState() == BluetoothDevice.BOND_NONE) {
            // Unbonded buds stop advertising and just wait to be paged, so do not wait for them.
            ensureBond(buds.getAddress());
            h.removeCallbacks(repairRetry);
            h.postDelayed(repairRetry, REPAIR_RETRY_MS);
        } else if (buds != null && advertData != null && buds.getBondState() == BluetoothDevice.BOND_BONDED
                && !classicConnected(buds)) {
            connectClassic(buds, why);
        }
        if (gatt == null) startScan();
        changed();
    }

    private final Runnable repairRetry = () -> kick(R.string.why_retry);

    /** Classic address of the buds: learned from their BLE info frame, else a bonded "JBL WAVE BUDS". */
    private BluetoothDevice targetDevice() {
        String mac = prefs.getString("mac", null);
        if (mac != null) return adapter.getRemoteDevice(mac);
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            if (d.getName() != null && d.getName().startsWith("JBL WAVE BUDS")) return d;
        }
        return null;
    }

    private Set<String> knownMacs() {
        return new HashSet<>(prefs.getStringSet("macs", Collections.emptySet()));
    }

    /**
     * Whether the classic address the connected buds reported is ours: one we used before, or bonded
     * with this phone, or (first run only) buds advertising this phone's name tag.
     */
    private boolean trusted(String mac) {
        Set<String> known = knownMacs();
        if (known.contains(mac)) return true;
        if (adapter.getRemoteDevice(mac).getBondState() == BluetoothDevice.BOND_BONDED) return true;
        return !prefs.contains("macs") && Proto.tagMatches(advertData, adapter.getName());
    }

    /** Advert tag of this phone's current name, or one our buds carried before (the phone was renamed). */
    private boolean ourTag(byte[] sd) {
        return Proto.tagMatches(sd, adapter.getName())
                || prefs.getStringSet("tags", Collections.emptySet()).contains(String.valueOf(Proto.advertTag(sd)));
    }

    /** The user unpaired the buds on purpose (auto re-pair off): stop treating them as ours. */
    private void forget(String mac) {
        Set<String> macs = knownMacs();
        macs.remove(mac);
        SharedPreferences.Editor e = prefs.edit().putStringSet("macs", macs).remove("tags");
        if (mac.equals(prefs.getString("mac", null))) e.remove("mac");
        e.apply();
        log(getString(R.string.log_forgot, mac));
    }

    private boolean privileged() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_PRIVILEGED) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean classicConnected(BluetoothDevice d) {
        return a2dp != null && a2dp.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED;
    }

    private void connectClassic(BluetoothDevice d, int why) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastClassicAttempt < RECONNECT_GAP_MS) return;
        lastClassicAttempt = now;
        if (!privileged()) return;
        log(getString(R.string.log_connect_audio, getString(why)));
        if (!Hidden.connect(d)) log(getString(R.string.log_connect_na));
    }

    /** The buds reported a classic address we are not bonded with: bond it (pairing is auto-confirmed). */
    private void ensureBond(String mac) {
        BluetoothDevice d = adapter.getRemoteDevice(mac);
        if (d.getBondState() != BluetoothDevice.BOND_NONE || want(K_AUTO_REPAIR, 1) == 0) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastBondAttempt < RECONNECT_GAP_MS) return;
        lastBondAttempt = now;
        log(getString(R.string.log_no_bond, mac));
        bondingMac = mac;
        bondStartedAt = now;
        d.createBond();
    }

    // ---------------------------------------------------------------- broadcasts

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            boolean ours = d != null && knownMacs().contains(d.getAddress());
            switch (i.getAction()) {
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    int st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                    if (st == BluetoothAdapter.STATE_ON) kick(R.string.why_start);
                    if (st == BluetoothAdapter.STATE_TURNING_OFF) { stopScan(); closeGatt(); }
                    break;
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                    int bs = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                    // Buds turned away earlier may be the ones the user has just paired.
                    if (bs == BluetoothDevice.BOND_BONDED) { rejected.clear(); silentCount = 0; }
                    if (!ours) break;
                    int prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
                    log(getString(R.string.log_bond, d.getAddress(), bond(prev), bond(bs),
                            i.getIntExtra("android.bluetooth.device.extra.REASON", -1)));
                    if (bs == BluetoothDevice.BOND_BONDED) { lastClassicAttempt = 0; kick(R.string.why_bonded); }
                    if (bs == BluetoothDevice.BOND_NONE) {
                        if (want(K_AUTO_REPAIR, 1) == 0) forget(d.getAddress());
                        else h.postDelayed(() -> kick(R.string.why_bond_lost), 3000);
                    }
                    break;
                case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
                    if (!ours) break;
                    int cs = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                    state.audio = cs == BluetoothProfile.STATE_CONNECTED;
                    if (cs == BluetoothProfile.STATE_CONNECTED || cs == BluetoothProfile.STATE_DISCONNECTED)
                        log(getString(state.audio ? R.string.log_audio_on : R.string.log_audio_off));
                    if (state.audio && gatt == null) startScan();
                    changed();
                    break;
                case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                    if (ours) log(getString(R.string.log_acl_lost, d.getAddress()));
                    break;
                default:
                    break;
            }
        }
    };

    /**
     * Confirms a pairing request before Settings shows its dialog (priv-app only), but only for the
     * bond this app itself has just started with the buds, and only while auto re-pair is on.
     */
    private final BroadcastReceiver pairingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (d == null || want(K_AUTO_REPAIR, 1) == 0 || !d.getAddress().equals(bondingMac)
                    || SystemClock.elapsedRealtime() - bondStartedAt > BOND_CONFIRM_MS) return;
            boolean ok;
            try {
                ok = d.setPairingConfirmation(true);
            } catch (SecurityException e) {
                ok = false;
            }
            log(getString(ok ? R.string.log_pair_ok : R.string.log_pair_manual, d.getAddress()));
            if (ok && isOrderedBroadcast()) abortBroadcast();
        }
    };

    private String bond(int s) {
        return getString(s == BluetoothDevice.BOND_BONDED ? R.string.bond_yes
                : s == BluetoothDevice.BOND_BONDING ? R.string.bond_progress : R.string.bond_no);
    }

    // ---------------------------------------------------------------- BLE scan

    private void startScan() {
        if (scanning || !adapter.isEnabled()) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceData(HARMAN_SD, new byte[]{(byte) 0x90, 0x20}).build());
        filters.add(new ScanFilter.Builder().setServiceData(FAST_PAIR, Proto.FAST_PAIR_MODEL).build());
        ScanSettings s = new ScanSettings.Builder()
                .setScanMode(state.audio ? ScanSettings.SCAN_MODE_BALANCED : ScanSettings.SCAN_MODE_LOW_POWER)
                .build();
        scanner.startScan(filters, s, scanCb);
        scanning = true;
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try { scanner.stopScan(scanCb); } catch (Exception ignored) { }
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            if (r.getScanRecord() == null) return;
            byte[] fp = r.getScanRecord().getServiceData(FAST_PAIR);
            if (fp != null && Arrays.equals(fp, Proto.FAST_PAIR_MODEL)) {
                // Discoverable Fast Pair advert: the buds are in pairing mode right now.
                kick(R.string.why_pairing_mode);
                return;
            }
            byte[] sd = r.getScanRecord().getServiceData(HARMAN_SD);
            if (!Proto.isBuds(sd) || rejected.contains(r.getDevice().getAddress())) return;
            // Other people's Wave Buds carry the tag of their owner's phone. Ours carry this phone's name
            // (or its old name, remembered); anything else is only tried while our buds' audio is up.
            if (!ourTag(sd) && !knownMacs().isEmpty() && !state.audio) return;
            advertData = sd;
            if (gatt != null) return;
            stopScan();
            log(getString(R.string.log_seen, r.getDevice().getAddress(), r.getRssi()));
            gatt = r.getDevice().connectGatt(BudsService.this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
            kick(R.string.why_nearby);
        }
    };

    // ---------------------------------------------------------------- GATT

    private void closeGatt() {
        if (gatt != null) {
            gatt.close();
            gatt = null;
        }
        tx = null;
        rx = null;
        subscribed = false;
        verified = false;
        h.removeCallbacks(setupTimeout);
        queue.clear();
        awaiting = -1;
        writing = false;
        state.ready = false;
        profileApplied = false;
        h.removeCallbacks(timeout);
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            h.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log(getString(R.string.log_ctrl_off, status));
                    closeGatt();
                    changed();
                    h.postDelayed(() -> kick(R.string.why_retry), 2000);
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            h.post(() -> {
                if (g != gatt) return;
                BluetoothGattService s = g.getService(Proto.SERVICE);
                if (status != BluetoothGatt.GATT_SUCCESS || s == null) {
                    log(getString(R.string.log_no_service));
                    closeGatt();
                    kick(R.string.why_retry);
                    return;
                }
                BluetoothGattCharacteristic w = s.getCharacteristic(Proto.WRITE), n = s.getCharacteristic(Proto.NOTIFY);
                if (w == null || n == null || n.getDescriptor(Proto.CCCD) == null) {
                    // Has the service but not its characteristics: not Wave Buds, do not come back.
                    log(getString(R.string.log_no_service));
                    rejected.add(g.getDevice().getAddress());
                    closeGatt();
                    kick(R.string.why_retry);
                    return;
                }
                tx = w;
                rx = n;
                h.postDelayed(setupTimeout, SETUP_TIMEOUT_MS); // MTU, subscribe and the info frame
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                g.requestMtu(512);
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            h.post(() -> {
                if (g != gatt || rx == null) return;
                g.setCharacteristicNotification(rx, true);
                g.writeDescriptor(rx.getDescriptor(Proto.CCCD), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            h.post(() -> {
                if (g != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log(getString(R.string.log_write_err, status));
                    closeGatt();
                    changed();
                    kick(R.string.why_retry);
                    return;
                }
                subscribed = true;
                if (verified) begin();
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
            h.post(() -> { if (g == gatt) onNotify(v); });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            h.post(() -> {
                if (g != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) log(getString(R.string.log_write_err, status));
                writing = false;
                pump();
            });
        }
    };

    /** Subscribed and the buds are ours: read everything, then the profile is applied. */
    private void begin() {
        h.removeCallbacks(setupTimeout);
        silentCount = 0;
        log(getString(R.string.log_ctrl_on));
        state.ready = true;
        queue.addAll(Proto.initReads());
        if (advertData != null) queue.add(Proto.token(advertData));
        pump();
        changed();
    }

    /**
     * Subscribing and the info frame (sent on subscribe; without it we cannot tell whose buds these
     * are) did not both happen in time. A device that keeps doing this is skipped.
     */
    private final Runnable setupTimeout = () -> {
        log(getString(R.string.log_no_info));
        String addr = gatt != null ? gatt.getDevice().getAddress() : null;
        silentCount = addr != null && addr.equals(silentAddr) ? silentCount + 1 : 1;
        silentAddr = addr;
        if (addr != null && silentCount >= 3) rejected.add(addr);
        closeGatt();
        advertData = null;
        changed();
        h.postDelayed(() -> kick(R.string.why_retry), REPAIR_RETRY_MS);
    };

    private void send(byte[] frame) {
        if (!state.ready) return;
        queue.add(frame);
        pump();
    }

    private void pump() {
        if (awaiting >= 0 || writing || queue.isEmpty() || gatt == null || tx == null) return;
        byte[] f = queue.poll();
        awaiting = Proto.replyCmd(f[1] & 0xff);
        int rc = gatt.writeCharacteristic(tx, f, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        writing = rc == BluetoothStatusCodes.SUCCESS;
        Log.d(TAG, "-> " + Proto.hex(f) + " rc=" + rc);
        h.postDelayed(timeout, REPLY_TIMEOUT_MS);
    }

    private final Runnable timeout = () -> {
        Log.w(TAG, "no reply for 0x" + Integer.toHexString(awaiting));
        awaiting = -1;
        pump();
    };

    private void onNotify(byte[] v) {
        Log.d(TAG, "<- " + Proto.hex(v));
        for (Proto.Frame f : Proto.parse(v)) {
            if (f.cmd == Proto.INFO) {
                if (!onInfo(f)) return;
                continue;
            }
            if (!verified) continue;
            handle(f);
            if (f.cmd == awaiting) {
                awaiting = -1;
                h.removeCallbacks(timeout);
            }
        }
        if (awaiting < 0) pump();
        if (state.ready && !profileApplied && awaiting < 0 && queue.isEmpty()) {
            profileApplied = true;
            applyProfile();
        }
        changed();
    }

    /** Info frame: adopt the buds if they are ours, else drop the link. False if the link was dropped. */
    private boolean onInfo(Proto.Frame f) {
        String mac = Proto.infoMac(f);
        if (mac == null) return true;
        if (!trusted(mac)) {
            log(getString(R.string.log_not_ours, mac));
            if (gatt != null) {
                if (rejected.size() > 64) rejected.clear();
                rejected.add(gatt.getDevice().getAddress());
            }
            closeGatt();
            advertData = null; // it was theirs: says nothing about our buds being around
            changed();
            kick(R.string.why_retry);
            return false;
        }
        state.name = Proto.infoName(f);
        state.mac = mac;
        Set<String> macs = knownMacs();
        if (macs.add(mac)) log(getString(R.string.log_addr, mac));
        SharedPreferences.Editor e = prefs.edit().putString("mac", mac).putStringSet("macs", macs);
        if (Proto.advertTag(advertData) >= 0) {
            Set<String> tags = new HashSet<>(prefs.getStringSet("tags", Collections.emptySet()));
            if (tags.size() > 8) tags.clear();
            tags.add(String.valueOf(Proto.advertTag(advertData)));
            e.putStringSet("tags", tags);
        }
        e.apply();
        BluetoothDevice d = adapter.getRemoteDevice(mac);
        if (d.getBondState() == BluetoothDevice.BOND_NONE) ensureBond(mac);
        else if (!classicConnected(d)) connectClassic(d, R.string.why_ble);
        if (!verified) {
            verified = true;
            if (subscribed) begin();
        }
        changed();
        return true;
    }

    private void handle(Proto.Frame f) {
        switch (f.cmd) {
            case Proto.BATTERY:
                // 0xff = that bud is not connected (in the case or out of range)
                state.battL = Proto.battery(f, 3);
                state.battR = Proto.battery(f, 4);
                updateNotification();
                break;
            case Proto.AMBIENT:
                if (f.p.length >= 7) state.ambient = f.u8(4) == 1 ? Proto.AMB_AA : f.u8(6) == 1 ? Proto.AMB_TT : Proto.AMB_OFF;
                break;
            case Proto.VOICE_AWARE:
                if (f.p.length >= 3) { state.vaLevel = f.u8(1); state.vaOn = f.u8(2) == 1; }
                break;
            case Proto.SMART_AV:
                state.smartAv = Proto.smartAvMode(f);
                break;
            case Proto.GESTURE:
                if (f.u8(0) == 0x02) {
                    int[] g = Proto.gestures(f);
                    if (f.p.length >= 17) { state.gesL = g[0]; state.gesR = g[1]; }
                    else if (f.u8(1) == 0x06) state.gesL = g[0];
                    else if (f.u8(1) == 0x09) state.gesR = g[1];
                }
                break;
            case Proto.PROMPT:
                if (f.u8(0) == 0x05) state.prompts = f.u8(1) == 1;
                break;
            case Proto.SAFE_SOUND:
                if (f.p.length >= 3) state.safeSound = f.u8(2) == 1;
                break;
            case Proto.SERIAL:
                if (f.p.length > 1) state.serial = new String(f.p, 1, f.p.length - 1);
                break;
            case Proto.STATUS:
                // byte 10: auto power-off enabled, bytes 11-12: minutes (LE)
                if (f.p.length >= 13) {
                    state.autoOffOn = f.u8(10) == 1;
                    state.autoOffMin = f.u8(11) | f.u8(12) << 8;
                }
                break;
            case Proto.EQ: {
                float[][] design = Proto.eqDesign(f);
                if (design != null) { state.eqDesign = design; break; }
                float[] eq = Proto.eqState(f);
                if (eq != null && (int) eq[0] != 0xca) {
                    state.eqCategory = (int) eq[0];
                    state.eqGains = Arrays.copyOfRange(eq, 1, eq.length);
                }
                break;
            }
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- profile

    static final String K_AMB = "ambient", K_VA_ON = "vaOn", K_VA_LVL = "vaLevel", K_SAV = "smartAv",
            K_GES_L = "gesL", K_GES_R = "gesR", K_PROMPT = "prompts", K_SAFE = "safeSound",
            K_AUTO_ON = "autoOffOn", K_AUTO_MIN = "autoOffMin", K_AUTO_REPAIR = "autoRepair",
            K_EQ = "eq", K_EQ_GAINS = "eqGains";

    /** First time we see the buds, their current settings become the profile. */
    private void applyProfile() {
        if (!prefs.contains(K_AMB)) {
            SharedPreferences.Editor e = prefs.edit()
                    .putInt(K_AMB, Math.max(state.ambient, 0)).putInt(K_VA_ON, state.vaOn ? 1 : 0).putInt(K_VA_LVL, state.vaLevel)
                    .putInt(K_SAV, Math.max(state.smartAv, 0)).putInt(K_GES_L, Math.max(state.gesL, 0))
                    .putInt(K_GES_R, Math.max(state.gesR, 0)).putInt(K_PROMPT, state.prompts ? 1 : 0)
                    .putInt(K_SAFE, state.safeSound ? 1 : 0).putInt(K_AUTO_ON, state.autoOffOn ? 1 : 0)
                    .putInt(K_AUTO_MIN, state.autoOffMin > 0 ? state.autoOffMin : 30);
            e.apply();
        }
        if (!prefs.contains(K_EQ)) {
            prefs.edit().putInt(K_EQ, Math.max(state.eqCategory, 0)).apply();
            if (state.eqCategory == Proto.EQ_CUSTOM) setEqGains(state.eqGains);
            log(getString(R.string.log_profile_saved));
            return;
        }
        int n = 0;
        if (state.ambient != want(K_AMB, 0)) { push(K_AMB); n++; }
        if (state.vaOn != (want(K_VA_ON, 0) == 1) || state.vaLevel != want(K_VA_LVL, 2)) { push(K_VA_ON); n++; }
        if (state.gesL != want(K_GES_L, 0)) { push(K_GES_L); n++; }
        if (state.gesR != want(K_GES_R, 0)) { push(K_GES_R); n++; }
        if (state.prompts != (want(K_PROMPT, 1) == 1)) { push(K_PROMPT); n++; }
        if (state.safeSound != (want(K_SAFE, 0) == 1)) { push(K_SAFE); n++; }
        if (state.autoOffOn != (want(K_AUTO_ON, 0) == 1) || (state.autoOffOn && state.autoOffMin != want(K_AUTO_MIN, 30))) { push(K_AUTO_ON); n++; }
        if (!eqMatches()) { push(K_EQ); n++; }
        // Last: changing it restarts the buds and drops this link.
        if (state.smartAv != want(K_SAV, 0)) { push(K_SAV); n++; }
        log(n == 0 ? getString(R.string.log_profile_same) : getString(R.string.log_profile_restored, n));
    }

    private boolean eqMatches() {
        int cat = want(K_EQ, 0);
        if (state.eqCategory != cat) return false;
        if (cat != Proto.EQ_CUSTOM) return true;
        float[] w = wantEqGains();
        for (int i = 0; i < w.length; i++) if (Math.abs(w[i] - state.eqGains[i]) > 0.05f) return false;
        return true;
    }

    private void push(String key) {
        switch (key) {
            case K_AMB: send(Proto.setAmbient(want(K_AMB, 0))); break;
            case K_VA_ON: case K_VA_LVL: send(Proto.setVoiceAware(want(K_VA_ON, 0) == 1, want(K_VA_LVL, 2))); break;
            case K_SAV: send(Proto.setSmartAv(want(K_SAV, 0))); break;
            case K_GES_L: send(Proto.setGesture(true, want(K_GES_L, 0))); break;
            case K_GES_R: send(Proto.setGesture(false, want(K_GES_R, 0))); break;
            case K_PROMPT: send(Proto.setPrompt(want(K_PROMPT, 1) == 1)); break;
            case K_SAFE: send(Proto.setSafeSound(want(K_SAFE, 0) == 1)); break;
            case K_EQ: {
                int cat = want(K_EQ, 0);
                send(cat == Proto.EQ_CUSTOM ? Proto.eqCustom(wantEqGains(), state.eqDesign) : Proto.eqPreset(cat));
                break;
            }
            case K_AUTO_ON: case K_AUTO_MIN:
                send(Proto.setAutoOff(want(K_AUTO_ON, 0) == 1, want(K_AUTO_MIN, 30)));
                state.autoOffOn = want(K_AUTO_ON, 0) == 1;
                state.autoOffMin = want(K_AUTO_MIN, 30);
                break;
            default: break;
        }
    }

    // ---------------------------------------------------------------- misc

    void log(String s) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + s;
        Log.i(TAG, s);
        log.addFirst(line);
        while (log.size() > 80) log.removeLast();
        changed();
    }

    private void changed() {
        for (Listener l : new ArrayList<>(listeners)) l.onChanged();
    }

    private Notification notification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        String text = state.battL >= 0 || state.battR >= 0
                ? battery(R.string.battery_l, state.battL) + " · " + battery(R.string.battery_r, state.battR)
                : getString(R.string.notif_waiting);
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setContentTitle(state.name != null ? "JBL Wave Buds" : getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    /** "L 80%", or "L —" when that bud is not connected. */
    String battery(int res, int pct) {
        return pct >= 0 ? getString(res, pct) : getString(res, 0).replaceAll("\\d+%", "—");
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(1, notification());
    }
}
