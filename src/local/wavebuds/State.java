package local.wavebuds;

/** What the buds currently report (not what the user wants: that is the profile in prefs). */
final class State {
    boolean ready;      // GATT control link up and subscribed
    boolean audio;      // A2DP connected
    String name, mac, serial;
    int battL = -1, battR = -1;
    int ambient = -1;
    boolean vaOn;
    int vaLevel = 2;
    int smartAv = -1;
    int gesL = -1, gesR = -1;
    boolean prompts, safeSound;
    boolean autoOffOn;
    int autoOffMin = -1;
    int eqCategory = -1;
    float[] eqGains = new float[Proto.EQ_FREQS.length];
    float[][] eqDesign;  // factory tuning curve, needed for the custom EQ calibration
}
