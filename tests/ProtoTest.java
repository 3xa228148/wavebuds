package local.wavebuds;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Plain-JVM tests for Proto (no Android needed): ./test.sh
 * Frames are real ones from the captures (addresses replaced, as in docs/PROTOCOL.md).
 */
public final class ProtoTest {
    private static int failed, passed;

    public static void main(String[] args) {
        parsing();
        fuzz();
        info();
        battery();
        hostTag();
        gestures();
        smartAv();
        equalizer();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void parsing() {
        List<Proto.Frame> fs = Proto.parse(Proto.unhex("aa250d0100ff46ffffff460fffffffff"));
        check("one frame", fs.size() == 1 && fs.get(0).cmd == 0x25 && fs.get(0).p.length == 13);
        fs = Proto.parse(Proto.unhex("aa00022300" + "aa23030200ff"));
        check("two frames in one notification", fs.size() == 2 && fs.get(1).cmd == 0x23);
        fs = Proto.parse(Proto.unhex("0102aa000223" + "00"));
        check("junk before a frame is skipped", fs.size() == 1 && fs.get(0).cmd == 0);
        check("truncated frame is dropped", Proto.parse(Proto.unhex("aa250d01")).isEmpty());
        check("empty input", Proto.parse(new byte[0]).isEmpty());
        fs = Proto.parse(Proto.eqPreset(Proto.EQ_JAZZ));
        check("EQ frame uses the two-byte length", fs.size() == 1 && fs.get(0).p.length == 116);
    }

    /** Random and truncated input must never throw: frames come from whatever device is connected. */
    static void fuzz() {
        Random rnd = new Random(1);
        byte[][] seeds = {Proto.eqPreset(Proto.EQ_BASS), Proto.unhex(INFO), Proto.unhex(FACTORY), Proto.unhex(GESTURES)};
        String err = null;
        for (int n = 0; n < 200_000 && err == null; n++) {
            byte[] v;
            if (n % 2 == 0) {
                v = new byte[rnd.nextInt(130)];
                rnd.nextBytes(v);
                if (v.length > 3) { v[0] = (byte) 0xaa; v[1] = (byte) new int[]{0x12, 0x25, 0x77, 0x83, 0xa2, 0x94}[rnd.nextInt(6)]; }
            } else {
                byte[] s = seeds[rnd.nextInt(seeds.length)];
                v = Arrays.copyOf(s, rnd.nextInt(s.length + 1));
                if (v.length > 4) v[2 + rnd.nextInt(v.length - 2)] = (byte) rnd.nextInt(256);
            }
            try {
                for (Proto.Frame f : Proto.parse(v)) {
                    Proto.infoMac(f);
                    Proto.infoName(f);
                    Proto.battery(f, 3);
                    Proto.gestures(f);
                    Proto.smartAvMode(f);
                    Proto.eqState(f);
                    Proto.eqDesign(f);
                }
            } catch (RuntimeException e) {
                err = Proto.hex(v) + ": " + e;
            }
        }
        check("200k random frames decode without exceptions" + (err == null ? "" : " (" + err + ")"), err == null);
    }

    /** "JBL WAVE BUDS-L", pid, ?, classic address 66:55:44:33:22:11 reversed, ? */
    static final String INFO = "aa1224" + "4a424c20574156452042554453" + "2d4c00" + "9020" + "0064"
            + "112233445566" + "05090064646400a11001";

    static void info() {
        Proto.Frame f = Proto.parse(Proto.unhex(INFO)).get(0);
        check("info: classic address (reversed)", "66:55:44:33:22:11".equals(Proto.infoMac(f)));
        check("info: name", "JBL WAVE BUDS-L".equals(Proto.infoName(f)));
        Proto.Frame cut = Proto.parse(Proto.unhex("aa0a" + "0a" + "4a424c2057415645200090")).get(0);
        check("info: too short for an address", Proto.infoMac(cut) == null);
    }

    static void battery() {
        Proto.Frame f = Proto.parse(Proto.unhex("aa250d0100ff46ffffff460fffffffff")).get(0);
        check("battery: left 70%", Proto.battery(f, 3) == 70);
        check("battery: right not connected (0xff)", Proto.battery(f, 4) == -1);
        check("battery: missing byte", Proto.battery(Proto.parse(Proto.unhex("aa250101")).get(0), 4) == -1);
    }

    static void hostTag() {
        // Advert of buds paired with a phone named "My Phone": service data 90 20 01 2a 34 …
        byte[] sd = Proto.unhex("9020012a34");
        check("host tag: CRC-16/ARC of the phone name", Proto.hostTag("My Phone") == 0x342a);
        check("host tag: matches this phone", Proto.tagMatches(sd, "My Phone"));
        check("host tag: other phone", !Proto.tagMatches(sd, "Other Phone"));
        check("host tag: unknown phone name", !Proto.tagMatches(sd, null));
        check("host tag: not Wave Buds", !Proto.tagMatches(Proto.unhex("9120012a34"), "My Phone"));
        check("host tag: short service data", !Proto.tagMatches(Proto.unhex("902001"), "My Phone") && !Proto.isBuds(null));
        check("host tag: read from the advert", Proto.advertTag(sd) == 0x342a && Proto.advertTag(Proto.unhex("902001")) == -1);
    }

    static final String GESTURES = "aa7711020600070008000c0009000a000b000e00";

    static void gestures() {
        int[] g = Proto.gestures(Proto.parse(Proto.unhex(GESTURES)).get(0));
        check("gestures: both none", g[0] == Proto.GES_NONE && g[1] == Proto.GES_NONE);
        check("gestures: left playback frame", Proto.hex(Proto.setGesture(true, Proto.GES_PLAYBACK)).equals("aa77090006080ca107050806"));
        Proto.Frame echo = Proto.parse(Proto.unhex("aa77090206080ca107050806")).get(0);
        check("gestures: echo of left playback", Proto.gestures(echo)[0] == Proto.GES_PLAYBACK);
    }

    static void smartAv() {
        String[] modes = {"c5002e001801ffff", "00012e001801ffff", "c5002e007800ffff"};
        for (int m = 0; m < 3; m++) {
            Proto.Frame f = Proto.parse(Proto.unhex("aa8308" + modes[m])).get(0);
            check("smart audio/video mode " + m, Proto.smartAvMode(f) == m && Proto.hex(Proto.setSmartAv(m)).equals("aa8108" + modes[m]));
        }
    }

    /** Factory curve read from the buds (aa a2 02 01 ca). */
    static final String FACTORY = "aaa2740004ca00000000009a9999bf9a9999bf09010000003f0000a0410a01000000c00000c8420501000030c100007a4304010000c0bf00007a440401000028c100e0dd451b01000000c000007a451701000020c0002820461901000000c000f052461e01cdcc4c3f000020420a0a0cee3d0f3ce2470f3c";
    /** Custom curves written by the official app, with its calibration value. */
    static final String[] CUSTOM = {
            "aaa2740000e6c6a21d413000000000000000000a013333e3400000004201016666d640000080420101cdcccc400000fa4201019a99b94000007a430101cdccac400000fa4301013333a34000007a440101666696400000fa4401013333834000007a450101333373400000fa4501019a99594000007a4601",
            "aaa2740000e6f5f319413000000000000000000a013333e340000000420101cdcccc400000804201016666b6400000fa4201013333934000007a4301019a9979400000fa430101cdcc4c4000007a440101000020400000fa4401013333b33f00007a4501013333333f0000fa4501010000000000007a4601",
    };

    static void equalizer() {
        float[][] design = Proto.eqDesign(Proto.parse(Proto.unhex(FACTORY)).get(0));
        check("factory curve: 9 bands", design != null && design.length == 9);
        int[] presets = {Proto.EQ_JAZZ, Proto.EQ_VOCAL, Proto.EQ_BASS, Proto.EQ_EXTREME};
        for (int c : presets) {
            byte[] w = Proto.eqPreset(c);
            check("preset " + c + " category", (w[5] & 0xff) == c);
            calibration("preset " + c, w, design, 0.06f); // presets are sent as captured, not computed
        }
        for (String h : CUSTOM) calibration("custom curve", Proto.unhex(h), design, 0.02f);

        float[] gains = {7.5f, 6, 4, 2, 0, -1, -2.5f, 0, 3, 8};
        byte[] w = Proto.eqCustom(gains, design);
        w[4] = 0x02; // read back as the buds echo it
        float[] st = Proto.eqState(Proto.parse(w).get(0));
        check("custom curve round trip", st != null && st[0] == Proto.EQ_CUSTOM && Arrays.equals(Arrays.copyOfRange(st, 1, 11), gains));
        check("flat curve needs no headroom", Proto.calibration(new float[10], design) == 0f);
    }

    /** Our calibration must match the one the official app put in this frame. */
    private static void calibration(String name, byte[] write, float[][] design, float tolerance) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(write).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float theirs = b.getFloat(6);
        float[] gains = new float[10];
        for (int i = 0; i < 10; i++) gains[i] = b.getFloat(21 + 10 * i);
        float ours = Proto.calibration(gains, design);
        check(String.format(java.util.Locale.ROOT, "%s calibration %.3f vs app %.3f", name, ours, theirs), Math.abs(ours - theirs) <= tolerance);
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "ok   " : "FAIL ") + name);
    }
}
