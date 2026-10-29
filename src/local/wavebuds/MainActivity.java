package local.wavebuds;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Shows the service's cached state at once; every control edits the saved profile. */
public class MainActivity extends Activity implements BudsService.Listener {
    private BudsService svc;
    private LinearLayout root;
    private TextView status, battL, battR, serial, logView;
    private View permCard;
    private boolean binding, logOpen;
    private final List<Runnable> binders = new ArrayList<>();
    private int accent, cardColor, textColor;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TypedArray a = obtainStyledAttributes(new int[]{android.R.attr.colorAccent, android.R.attr.textColorPrimary});
        accent = a.getColor(0, 0xff3d7bf7);
        textColor = a.getColorStateList(1) != null ? a.getColorStateList(1).getDefaultColor() : Color.BLACK;
        a.recycle();
        if (getActionBar() != null) getActionBar().hide();
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        cardColor = night ? 0x14ffffff : 0x0d000000;

        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(24));
        sv.addView(root);
        setContentView(sv);

        header();
        permissionCard();
        ambientCard();
        eqCard();
        gestureCard();
        voiceAwareCard();
        smartAvCard();
        otherCard();
        autoOffCard();
        findCard();
        connectionCard();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean ok = BudsService.hasPermissions(this);
        permCard.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (ok) {
            startForegroundService(new Intent(this, BudsService.class));
            attach();
        } else {
            requestPermissions(BudsService.PERMISSIONS, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (BudsService.hasPermissions(this)) onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        root.removeCallbacks(this::attach);
        if (svc != null) svc.removeListener(this);
        svc = null;
    }

    /** The service may still be starting; it runs in this process, so poll briefly for it. */
    private void attach() {
        if (svc != null) return;
        svc = BudsService.instance;
        if (svc == null) { root.postDelayed(this::attach, 100); return; }
        svc.addListener(this);
        onChanged();
    }

    @Override
    public void onChanged() {
        if (svc == null) return;
        State s = svc.state;
        int st = !s.ready ? (s.audio ? R.string.status_audio_only : R.string.status_offline)
                : (s.audio ? R.string.status_connected : R.string.status_control_only);
        status.setText(st);
        boolean batt = s.ready && (s.battL >= 0 || s.battR >= 0);
        battL.setText(batt ? svc.battery(R.string.battery_l, s.battL) : "");
        battR.setText(batt ? svc.battery(R.string.battery_r, s.battR) : "");
        battL.setAlpha(s.battL >= 0 ? 1f : 0.4f);
        battR.setAlpha(s.battR >= 0 ? 1f : 0.4f);
        serial.setText(s.serial != null ? getString(R.string.serial, s.serial) : "");
        binding = true;
        for (Runnable r : binders) r.run();
        binding = false;
        if (logOpen) logView.setText(String.join("\n", svc.log));
    }

    // ---------------------------------------------------------------- sections

    private void header() {
        TextView title = text(getString(R.string.app_name), 28, true);
        title.setPadding(0, dp(12), 0, 0);
        root.addView(title);
        status = text("", 15, false);
        status.setAlpha(0.75f);
        root.addView(status);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(8), 0, dp(4));
        battL = text("", 34, true);
        battR = text("", 34, true);
        battR.setPadding(dp(24), 0, 0, 0);
        row.addView(battL);
        row.addView(battR);
        root.addView(row);
        serial = text("", 12, false);
        serial.setAlpha(0.5f);
        root.addView(serial);
    }

    private void permissionCard() {
        LinearLayout c = card(null, getString(R.string.perm_needed));
        c.addView(button(getString(R.string.perm_grant), v -> requestPermissions(BudsService.PERMISSIONS, 1)));
        permCard = c;
    }

    private void ambientCard() {
        LinearLayout c = card(getString(R.string.sec_ambient), getString(R.string.desc_ambient));
        chips(c, BudsService.K_AMB, 0, new int[]{R.string.opt_off, R.string.opt_aa, R.string.opt_tt}, new int[]{0, 1, 2});
    }

    private void eqCard() {
        LinearLayout c = card(getString(R.string.sec_eq), getString(R.string.desc_eq));
        String[] names = {getString(R.string.opt_off), "Jazz", "Vocal", "Bass", "Extreme Bass", getString(R.string.eq_custom)};
        int[] values = {Proto.EQ_OFF, Proto.EQ_JAZZ, Proto.EQ_VOCAL, Proto.EQ_BASS, Proto.EQ_EXTREME, Proto.EQ_CUSTOM};
        chips(c, BudsService.K_EQ, 0, names, values);

        LinearLayout bands = new LinearLayout(this);
        bands.setOrientation(LinearLayout.VERTICAL);
        bands.setPadding(0, dp(8), 0, 0);
        bands.setVisibility(View.GONE);
        SeekBar[] bars = new SeekBar[Proto.EQ_FREQS.length];
        TextView[] vals = new TextView[bars.length];
        boolean[] touching = {false};
        for (int i = 0; i < bars.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int f = Proto.EQ_FREQS[i];
            TextView label = text(f >= 1000 ? (f / 1000) + "k" : String.valueOf(f), 13, false);
            label.setMinWidth(dp(40));
            bars[i] = new SeekBar(this);
            bars[i].setMax((int) (Proto.EQ_MAX_DB * 4));
            vals[i] = text("", 13, false);
            vals[i].setMinWidth(dp(64));
            vals[i].setGravity(Gravity.END);
            final int idx = i;
            bars[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) { vals[idx].setText(getString(R.string.eq_db, p / 2f - Proto.EQ_MAX_DB)); }
                @Override public void onStartTrackingTouch(SeekBar s) { touching[0] = true; }
                @Override public void onStopTrackingTouch(SeekBar s) {
                    touching[0] = false;
                    float[] g = new float[bars.length];
                    for (int k = 0; k < bars.length; k++) g[k] = bars[k].getProgress() / 2f - Proto.EQ_MAX_DB;
                    if (svc != null) svc.setEqGains(g);
                }
            });
            row.addView(label);
            row.addView(bars[i], new LinearLayout.LayoutParams(0, dp(40), 1));
            row.addView(vals[i]);
            bands.addView(row);
        }
        Button flat = button(getString(R.string.eq_flatten), v -> { if (svc != null) svc.setEqGains(new float[bars.length]); });
        bands.addView(flat);
        c.addView(bands);
        binders.add(() -> {
            boolean custom = svc.want(BudsService.K_EQ, 0) == Proto.EQ_CUSTOM;
            bands.setVisibility(custom ? View.VISIBLE : View.GONE);
            if (touching[0]) return;
            float[] g = svc.wantEqGains();
            for (int k = 0; k < bars.length; k++) bars[k].setProgress(Math.round((g[k] + Proto.EQ_MAX_DB) * 2));
        });
    }

    private void gestureCard() {
        LinearLayout c = card(getString(R.string.sec_gestures), getString(R.string.desc_gestures));
        int[] labels = {R.string.ges_none, R.string.ges_ambient, R.string.ges_volume, R.string.ges_playback};
        int[] descs = {R.string.ges_desc_none, R.string.ges_desc_ambient, R.string.ges_desc_volume, R.string.ges_desc_playback};
        int[] values = {Proto.GES_NONE, Proto.GES_AMBIENT, Proto.GES_VOLUME, Proto.GES_PLAYBACK};
        for (int side = 0; side < 2; side++) {
            String key = side == 0 ? BudsService.K_GES_L : BudsService.K_GES_R;
            TextView sub = text(getString(side == 0 ? R.string.gest_left : R.string.gest_right), 15, true);
            sub.setPadding(0, dp(12), 0, 0);
            c.addView(sub);
            chips(c, key, 0, labels, values);
            TextView d = text("", 13, false);
            d.setAlpha(0.7f);
            c.addView(d);
            binders.add(() -> d.setText(descs[Math.min(Math.max(svc.want(key, 0), 0), descs.length - 1)]));
        }
    }

    private void voiceAwareCard() {
        LinearLayout c = card(getString(R.string.sec_va), getString(R.string.desc_va));
        toggle(c, BudsService.K_VA_ON, 0, getString(R.string.opt_on), null);
        chips(c, BudsService.K_VA_LVL, 2, new int[]{R.string.opt_low, R.string.opt_mid, R.string.opt_high}, new int[]{1, 2, 3});
    }

    private void smartAvCard() {
        LinearLayout c = card(getString(R.string.sec_sav), getString(R.string.desc_sav));
        chips(c, BudsService.K_SAV, 0, new int[]{R.string.opt_off, R.string.opt_audio, R.string.opt_video}, new int[]{0, 1, 2});
    }

    private void otherCard() {
        LinearLayout c = card(getString(R.string.sec_other), null);
        toggle(c, BudsService.K_PROMPT, 1, getString(R.string.prompts), getString(R.string.desc_prompts));
        toggle(c, BudsService.K_SAFE, 0, getString(R.string.limiter), getString(R.string.desc_limiter));
    }

    private void autoOffCard() {
        LinearLayout c = card(getString(R.string.sec_autooff), getString(R.string.desc_autooff));
        int[] labels = {R.string.opt_off, R.string.opt_30m, R.string.opt_1h, R.string.opt_2h};
        int[] mins = {0, 30, 60, 120};
        FlowLayout row = new FlowLayout(this);
        TextView[] chips = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int m = mins[i];
            chips[i] = chip(getString(labels[i]), v -> {
                if (svc == null) return;
                if (m > 0) svc.set(BudsService.K_AUTO_MIN, m);
                svc.set(BudsService.K_AUTO_ON, m > 0 ? 1 : 0);
            });
            row.addView(chips[i]);
        }
        c.addView(row);
        binders.add(() -> {
            int sel = svc.want(BudsService.K_AUTO_ON, 0) == 0 ? 0 : -1;
            for (int i = 1; i < mins.length && sel < 0; i++) if (mins[i] == svc.want(BudsService.K_AUTO_MIN, 30)) sel = i;
            for (int i = 0; i < chips.length; i++) styleChip(chips[i], i == sel);
        });
    }

    private void findCard() {
        LinearLayout c = card(getString(R.string.sec_find), getString(R.string.desc_find));
        FlowLayout row = new FlowLayout(this);
        TextView l = chip(getString(R.string.find_left), v -> { if (svc != null) svc.find(Proto.BEEP_ON, Proto.BEEP_KEEP); });
        TextView r = chip(getString(R.string.find_right), v -> { if (svc != null) svc.find(Proto.BEEP_KEEP, Proto.BEEP_ON); });
        TextView s = chip(getString(R.string.find_stop), v -> { if (svc != null) svc.find(Proto.BEEP_OFF, Proto.BEEP_OFF); });
        for (TextView t : new TextView[]{l, r, s}) { styleChip(t, false); row.addView(t); }
        c.addView(row);
    }

    private void connectionCard() {
        LinearLayout c = card(getString(R.string.sec_connection), null);
        toggle(c, BudsService.K_AUTO_REPAIR, 1, getString(R.string.auto_repair), getString(R.string.desc_auto_repair));
        FlowLayout row = new FlowLayout(this);
        TextView rc = chip(getString(R.string.reconnect), v -> { if (svc != null) svc.reconnect(); });
        TextView lg = chip(getString(R.string.log_show), null);
        styleChip(rc, false);
        styleChip(lg, false);
        row.addView(rc);
        row.addView(lg);
        c.addView(row);
        logView = text("", 11, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setVisibility(View.GONE);
        logView.setTextIsSelectable(true);
        c.addView(logView);
        lg.setOnClickListener(v -> {
            logOpen = !logOpen;
            lg.setText(logOpen ? R.string.log_hide : R.string.log_show);
            logView.setVisibility(logOpen ? View.VISIBLE : View.GONE);
            onChanged();
        });
    }

    // ---------------------------------------------------------------- widgets

    private LinearLayout card(String title, String desc) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardColor);
        bg.setCornerRadius(dp(18));
        c.setBackground(bg);
        if (title != null) c.addView(text(title, 18, true));
        if (desc != null) {
            TextView d = text(desc, 13, false);
            d.setAlpha(0.7f);
            d.setPadding(0, dp(2), 0, dp(6));
            c.addView(d);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        root.addView(c, lp);
        return c;
    }

    private void chips(LinearLayout parent, String key, int def, int[] labelRes, int[] values) {
        String[] labels = new String[labelRes.length];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(labelRes[i]);
        chips(parent, key, def, labels, values);
    }

    private void chips(LinearLayout parent, String key, int def, String[] labels, int[] values) {
        FlowLayout row = new FlowLayout(this);
        TextView[] chips = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int v = values[i];
            chips[i] = chip(labels[i], x -> { if (svc != null && !binding) svc.set(key, v); });
            row.addView(chips[i]);
        }
        parent.addView(row);
        binders.add(() -> {
            int w = svc.want(key, def);
            for (int i = 0; i < chips.length; i++) styleChip(chips[i], values[i] == w);
        });
    }

    private TextView chip(String label, View.OnClickListener l) {
        TextView t = text(label, 14, false);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(40));
        if (l != null) t.setOnClickListener(l);
        styleChip(t, false);
        return t;
    }

    private void styleChip(TextView t, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        if (on) bg.setColor(accent);
        else bg.setStroke(dp(1), (textColor & 0x00ffffff) | 0x40000000);
        t.setBackground(bg);
        t.setTextColor(on ? onAccent() : textColor);
    }

    private void toggle(LinearLayout parent, String key, int def, String label, String desc) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(textColor);
        sw.setTextSize(15);
        sw.setMinHeight(dp(44));
        sw.setOnCheckedChangeListener((btn, on) -> { if (!binding && svc != null) svc.set(key, on ? 1 : 0); });
        parent.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (desc != null) {
            TextView d = text(desc, 13, false);
            d.setAlpha(0.7f);
            d.setPadding(0, 0, dp(56), dp(6));
            parent.addView(d);
        }
        binders.add(() -> sw.setChecked(svc.want(key, def) == 1));
    }

    /** Black or white, whichever reads better on the accent colour. */
    private int onAccent() {
        double lum = 0.299 * Color.red(accent) + 0.587 * Color.green(accent) + 0.114 * Color.blue(accent);
        return lum > 160 ? Color.BLACK : Color.WHITE;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(textColor);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Lays children out left to right and wraps to a new line when the row is full. */
    static final class FlowLayout extends ViewGroup {
        private final int gap;

        FlowLayout(Context c) {
            super(c);
            gap = Math.round(8 * c.getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int maxW = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
                if (x > 0 && x + c.getMeasuredWidth() > maxW) { x = 0; y += rowH + gap; rowH = 0; }
                x += c.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, c.getMeasuredHeight());
            }
            setMeasuredDimension(MeasureSpec.getSize(wSpec), y + rowH + gap + getPaddingTop() + getPaddingBottom());
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l - getPaddingLeft() - getPaddingRight();
            int x = 0, y = gap / 2, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (x > 0 && x + c.getMeasuredWidth() > maxW) { x = 0; y += rowH + gap; rowH = 0; }
                c.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + c.getMeasuredWidth(), getPaddingTop() + y + c.getMeasuredHeight());
                x += c.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, c.getMeasuredHeight());
            }
        }
    }
}
