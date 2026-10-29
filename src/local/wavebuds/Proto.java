package local.wavebuds;

import android.os.ParcelUuid;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JBL Wave Buds (pid 0x2090) control protocol over BLE GATT.
 * Mapped from an HCI capture of the official app: every frame is
 * {@code AA <cmd> <len> <payload...>}, written to WRITE and answered by notifications on NOTIFY.
 */
final class Proto {
    private Proto() {}

    // ASCII "excelpoint.com" as a 128-bit UUID (BES / Excelpoint reference design).
    static final UUID SERVICE = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0000");
    static final UUID NOTIFY = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0001");
    static final UUID WRITE = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0002");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** Harman service data in the advertisement: pid (LE16), 1 byte, 2-byte token. */
    static final ParcelUuid HARMAN_SD = ParcelUuid.fromString("0000fddf-0000-1000-8000-00805f9b34fb");
    static final int PID = 0x2090;
    /** Google Fast Pair: in pairing mode the service data is exactly the 3-byte model id. */
    static final ParcelUuid FAST_PAIR = ParcelUuid.fromString("0000fe2c-0000-1000-8000-00805f9b34fb");
    static final byte[] FAST_PAIR_MODEL = {0x08, 0x62, (byte) 0xab};

    static final int ACK = 0x00;
    static final int INFO = 0x12;          // name, pid, classic MAC (sent on subscribe)
    static final int STATUS_GET = 0x21;    // -> 0x22
    static final int STATUS = 0x22;
    static final int FIND = 0x23;
    static final int BATTERY = 0x25;
    static final int AUTO_OFF = 0x33;      // -> ACK
    static final int TOKEN = 0x61;         // -> ACK; official app sends the advert token
    static final int GESTURE = 0x77;
    static final int SMART_AV_SET = 0x81;  // -> 0x83
    static final int SMART_AV_GET = 0x82;  // -> 0x83
    static final int SMART_AV = 0x83;
    static final int AMBIENT = 0x91;
    static final int PROMPT = 0x93;
    static final int SERIAL = 0x94;
    static final int VOICE_AWARE = 0x98;
    static final int SAFE_SOUND = 0xa5;

    // Ambient modes
    static final int AMB_OFF = 0, AMB_AA = 1, AMB_TT = 2;
    // Smart audio & video: three LE16 parameters, the official app's "normal/audio/video" presets
    static final int SAV_OFF = 0, SAV_AUDIO = 1, SAV_VIDEO = 2;
    private static final int[][] SAV = {{197, 46, 280}, {256, 46, 280}, {197, 46, 120}};
    // Gesture presets, values for the 4 touch events of a side in wire order
    static final int GES_NONE = 0, GES_AMBIENT = 1, GES_VOLUME = 2, GES_PLAYBACK = 3;
    private static final int[][] GES = {
            {0x00, 0x00, 0x00, 0x00},
            {0x0d, 0xa1, 0x04, 0x00},
            {0x01, 0xa1, 0x02, 0x00},
            {0x08, 0xa1, 0x05, 0x06},
    };
    private static final int[] GES_EVENTS_L = {0x06, 0x0c, 0x07, 0x08};
    private static final int[] GES_EVENTS_R = {0x09, 0x0e, 0x0a, 0x0b};

    static byte[] frame(int cmd, int... payload) {
        byte[] b = new byte[3 + payload.length];
        b[0] = (byte) 0xaa;
        b[1] = (byte) cmd;
        b[2] = (byte) payload.length;
        for (int i = 0; i < payload.length; i++) b[3 + i] = (byte) payload[i];
        return b;
    }

    /** Response command a request is answered with. */
    static int replyCmd(int cmd) {
        switch (cmd) {
            case STATUS_GET: return STATUS;
            case SMART_AV_SET: case SMART_AV_GET: return SMART_AV;
            case AUTO_OFF: case TOKEN: return ACK;
            default: return cmd;
        }
    }

    static final class Frame {
        final int cmd;
        final byte[] p;
        Frame(int cmd, byte[] p) { this.cmd = cmd; this.p = p; }
        int u8(int i) { return i < p.length ? p[i] & 0xff : -1; }
    }

    static List<Frame> parse(byte[] v) {
        List<Frame> out = new ArrayList<>();
        int i = 0;
        while (i + 3 <= v.length) {
            if ((v[i] & 0xff) != 0xaa) { i++; continue; }
            int cmd = v[i + 1] & 0xff;
            boolean wide = cmd == EQ && i + 4 <= v.length;
            int len = wide ? (v[i + 2] & 0xff) | (v[i + 3] & 0xff) << 8 : v[i + 2] & 0xff;
            int head = wide ? 4 : 3;
            if (i + head + len > v.length) break;
            byte[] p = new byte[len];
            System.arraycopy(v, i + head, p, 0, len);
            out.add(new Frame(cmd, p));
            i += head + len;
        }
        return out;
    }

    // --- equalizer (cmd 0xa2, 2-byte length, 116-byte payload) ---

    static final int EQ = 0xa2;
    static final int EQ_OFF = 0x00, EQ_JAZZ = 0x01, EQ_VOCAL = 0x02, EQ_BASS = 0x03, EQ_EXTREME = 0x09, EQ_CUSTOM = 0xe6;
    static final int[] EQ_FREQS = {32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    static final float EQ_MAX_DB = 8f;

    /** Preset frames exactly as the official app sends them (its calibration values included). */
    private static final String[][] EQ_PRESETS = {
            {"01", "aaa27400000105ea9d4030000080c0000080c00a0100008040000000420101000040400000804201010000803f0000fa4201010000204000007a4301010000c0bf0000fa4301010000c0bf00007a440101000000000000fa4401010000803f00007a450101000040400000fa4501010000804000007a4601"},
            {"02", "aaa274000002c26f484030000060c0000060c00a010000c0bf000000420101000040c0000080420101000040c00000fa4201010000803f00007a430101000060400000fa4301010000604000007a440101000040400000fa4401010000c03f00007a450101000000000000fa4501010000c0bf00007a4601"},
            {"03", "aaa27400000362c47f4030000040c0000040c00a010000404000000042010100004040000080420101000000400000fa4201010000000000007a430101000000000000fa4301010000000000007a440101000000000000fa4401010000000000007a450101000000000000fa4501010000000000007a4601"},
            {"09", "aaa274000009eb6da64030000000c1000000c10a0100008040000000420101000040400000804201010000a0400000fa4201010000604000007a430101000080bf0000fa4301010000000000007a440101000000000000fa4401010000004000007a450101000000400000fa4501010000000000007a4601"},
    };

    /** Read request uses the short header (aa a2 02 01 xx); only replies and writes are wide. */
    static byte[] eqRead(int which) { return frame(EQ, 0x01, which); }

    static byte[] eqPreset(int category) {
        for (String[] p : EQ_PRESETS) if (Integer.parseInt(p[0], 16) == category) return unhex(p[1]);
        return eqFrame(EQ_OFF, 0f, new float[EQ_FREQS.length]);
    }

    /** Custom curve; {@code design} is the buds' factory tuning (bands of the 0xca read) or null. */
    static byte[] eqCustom(float[] gains, float[][] design) {
        return eqFrame(EQ_CUSTOM, calibration(gains, design), gains);
    }

    private static byte[] eqFrame(int category, float calib, float[] gains) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(120).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0xaa).put((byte) EQ).putShort((short) 116);
        b.put((byte) 0x00).put((byte) category).putFloat(calib).put((byte) 0x30).putFloat(0f).putFloat(0f);
        b.put((byte) EQ_FREQS.length);
        for (int i = 0; i < EQ_FREQS.length; i++) b.put((byte) 1).putFloat(gains[i]).putFloat(EQ_FREQS[i]).put((byte) 1);
        return b.array();
    }

    /**
     * Headroom the firmware applies before the EQ: peak of the summed magnitude response of the
     * factory curve and the user curve (RBJ peaking biquads, 48 kHz) plus a fixed 0.41 dB. Fitted
     * to the official app's values within 0.05 dB.
     */
    static float calibration(float[] gains, float[][] design) {
        boolean flat = true;
        for (float g : gains) flat &= g == 0f;
        if (flat) return 0f;
        double peak = -1e9;
        for (int k = 0; k <= 400; k++) {
            double f = 20 * Math.pow(1000, k / 400.0);
            double db = 0;
            for (int i = 0; i < gains.length; i++) db += peakDb(f, EQ_FREQS[i], gains[i], 1.0);
            if (design != null) for (float[] d : design) db += peakDb(f, d[1], d[0], d[2]);
            peak = Math.max(peak, db);
        }
        return (float) (peak + 0.406);
    }

    private static double peakDb(double f, double f0, double gainDb, double q) {
        if (gainDb == 0) return 0;
        double a = Math.pow(10, gainDb / 40), w0 = 2 * Math.PI * f0 / 48000, al = Math.sin(w0) / (2 * q);
        double b0 = 1 + al * a, b1 = -2 * Math.cos(w0), b2 = 1 - al * a;
        double a0 = 1 + al / a, a1 = b1, a2 = 1 - al / a;
        double w = 2 * Math.PI * f / 48000, c1 = Math.cos(w), s1 = -Math.sin(w), c2 = Math.cos(2 * w), s2 = -Math.sin(2 * w);
        double nr = b0 + b1 * c1 + b2 * c2, ni = b1 * s1 + b2 * s2, dr = a0 + a1 * c1 + a2 * c2, di = a1 * s1 + a2 * s2;
        return 10 * Math.log10((nr * nr + ni * ni) / (dr * dr + di * di));
    }

    /** EQ read/echo: {category, gains...}; null if not an EQ state frame. */
    static float[] eqState(Frame f) {
        if (f.p.length < 16 || f.u8(0) != 0x02) return null;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(f.p).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int n = Math.min(f.u8(15), EQ_FREQS.length);
        float[] out = new float[1 + EQ_FREQS.length];
        out[0] = f.u8(1);
        for (int i = 0; i < n && 16 + 10 * i + 10 <= f.p.length; i++) out[1 + i] = b.getFloat(17 + 10 * i);
        return out;
    }

    /** Factory curve from the 0xca read: {gain, freq, q} per band (q is stored x8). */
    static float[][] eqDesign(Frame f) {
        if (f.p.length < 16 || f.u8(1) != 0xca) return null;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(f.p).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int n = f.u8(15);
        if (16 + 10 * n > f.p.length) return null;
        float[][] d = new float[n][];
        for (int i = 0; i < n; i++) {
            int o = 16 + 10 * i;
            d[i] = new float[]{b.getFloat(o + 1), b.getFloat(o + 5), f.u8(o + 9) / 8f};
        }
        return d;
    }

    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    // --- requests ---

    static List<byte[]> initReads() {
        List<byte[]> l = new ArrayList<>();
        l.add(frame(STATUS_GET, 0x30));
        l.add(frame(BATTERY, 0x00));
        l.add(frame(AMBIENT, 0x11));
        l.add(frame(SMART_AV_GET));
        l.add(frame(GESTURE, 0x01, 0xff));
        l.add(frame(VOICE_AWARE, 0x01));
        l.add(frame(SAFE_SOUND, 0x01));
        l.add(frame(PROMPT, 0x04));
        l.add(frame(SERIAL, 0x01));
        l.add(eqRead(0xca));
        l.add(eqRead(0xff));
        return l;
    }

    static byte[] setAmbient(int mode) {
        if (mode == AMB_OFF) return frame(AMBIENT, 0x13);
        return frame(AMBIENT, 0x10, 0x01, 0x00, 0x02, mode == AMB_AA ? 1 : 0, 0x03, mode == AMB_TT ? 1 : 0);
    }

    static byte[] setVoiceAware(boolean on, int level) {
        return frame(VOICE_AWARE, 0x00, level, on ? 1 : 0);
    }

    static byte[] setSmartAv(int mode) {
        int[] s = SAV[mode];
        return frame(SMART_AV_SET, s[0] & 0xff, s[0] >> 8, s[1] & 0xff, s[1] >> 8, s[2] & 0xff, s[2] >> 8, 0xff, 0xff);
    }

    static byte[] setGesture(boolean left, int preset) {
        int[] ev = left ? GES_EVENTS_L : GES_EVENTS_R;
        int[] val = GES[preset];
        int[] p = new int[9];
        for (int i = 0; i < 4; i++) {
            p[1 + 2 * i] = ev[i];
            p[2 + 2 * i] = val[i];
        }
        return frame(GESTURE, p);
    }

    static byte[] setPrompt(boolean on) { return frame(PROMPT, 0x03, on ? 1 : 0); }

    static byte[] setSafeSound(boolean on) { return frame(SAFE_SOUND, 0x00, 0x01, on ? 1 : 0); }

    static byte[] setAutoOff(boolean on, int minutes) {
        return frame(AUTO_OFF, on ? 1 : 0, minutes & 0xff, minutes >> 8);
    }

    static final int BEEP_ON = 1, BEEP_OFF = 0, BEEP_KEEP = 0xff;

    static byte[] find(int left, int right) { return frame(FIND, 0x00, left, right); }

    static byte[] token(byte[] sd) { return frame(TOKEN, sd[3] & 0xff, sd[4] & 0xff); }

    // --- decoding into State ---

    static int smartAvMode(Frame f) {
        if (f.p.length < 6) return -1;
        int a = f.u8(0) | f.u8(1) << 8, b = f.u8(2) | f.u8(3) << 8, c = f.u8(4) | f.u8(5) << 8;
        for (int m = 0; m < SAV.length; m++) if (SAV[m][0] == a && SAV[m][1] == b && SAV[m][2] == c) return m;
        return -1;
    }

    /** Gesture preset per side from the 0x77 reply: {left, right}; -1 if the combination is unknown. */
    static int[] gestures(Frame f) {
        int[] vals = new int[256];
        java.util.Arrays.fill(vals, -1);
        for (int i = 1; i + 1 < f.p.length; i += 2) vals[f.u8(i)] = f.u8(i + 1);
        return new int[]{gesturePreset(vals, GES_EVENTS_L), gesturePreset(vals, GES_EVENTS_R)};
    }

    private static int gesturePreset(int[] vals, int[] ev) {
        for (int g = 0; g < GES.length; g++) {
            boolean ok = true;
            for (int i = 0; i < 4; i++) ok &= vals[ev[i]] == GES[g][i];
            if (ok) return g;
        }
        return -1;
    }

    /** Classic BR/EDR address from the 0x12 info frame, or null. */
    static String infoMac(Frame f) {
        int z = 0;
        while (z < f.p.length && f.p[z] != 0) z++;
        int o = z + 1 + 4; // NUL, pid (2), 2 unknown bytes
        if (o + 6 > f.p.length) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 5; i >= 0; i--) {
            sb.append(String.format("%02X", f.p[o + i] & 0xff));
            if (i > 0) sb.append(':');
        }
        return sb.toString();
    }

    static String infoName(Frame f) {
        int z = 0;
        while (z < f.p.length && f.p[z] != 0) z++;
        return new String(f.p, 0, z);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }
}
