package ai.devvio.rd;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Streaming quality, input and behaviour settings.
 *
 * <p>Presets come first because they are what most people need: one tap from
 * "sharp" to "battery saver". The individual sliders stay available underneath
 * for anyone who wants fine control, and editing a slider drops the preset
 * selection back to "custom" so the two never disagree.
 */
public class SettingsActivity extends Activity {

    private Prefs prefs;

    private SeekBar qBar;
    private SeekBar fBar;
    private SeekBar sBar;
    private SeekBar mBar;
    private TextView qVal;
    private TextView fVal;
    private TextView sVal;
    private TextView mVal;
    private LinearLayout presetRow;
    private TextView summary;

    private static final String[] PRESET_NAMES = {"حداکثر", "متعادل", "سبک", "کم‌مصرف"};
    private static final int[] P_Q = {85, 70, 55, 40};
    private static final int[] P_F = {30, 20, 15, 10};
    private static final float[] P_S = {1f, 1f, 0.8f, 0.65f};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        setContentView(build());
    }

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = Ui.column(this);
        int pad = Ui.dp(this, Ui.SP_4);
        col.setPadding(pad, Ui.dp(this, Ui.SP_3), pad, Ui.dp(this, Ui.SP_7));

        // ---- header ----
        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.toolButton(this, "‹", 20);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        head.addView(back);
        LinearLayout ht = Ui.column(this);
        ht.addView(Ui.text(this, "تنظیمات", Ui.TS_TITLE, Ui.TEXT, true));
        ht.addView(Ui.text(this, "کیفیت پخش، ورودی و رفتار برنامه", Ui.TS_MICRO, Ui.TEXT_FAINT, false));
        head.addView(ht, leftParam(Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1),
                Ui.dp(this, Ui.SP_3)));
        col.addView(head);

        // ---- live summary of the current setup ----
        summary = Ui.text(this, "", Ui.TS_CAPTION, Ui.ACCENT, false);
        summary.setBackground(Ui.roundRect(Ui.SURFACE, Ui.R_MD, Ui.LINE, 1, this));
        summary.setPadding(Ui.dp(this, Ui.SP_3), Ui.dp(this, Ui.SP_3),
                Ui.dp(this, Ui.SP_3), Ui.dp(this, Ui.SP_3));
        Ui.addTop(col, summary, Ui.dp(this, Ui.SP_4));

        // ---- presets ----
        col.addView(Ui.sectionLabel(this, "پیش‌تنظیم‌ها"));
        presetRow = Ui.row(this);
        for (int i = 0; i < PRESET_NAMES.length; i++) {
            final int idx = i;
            TextView p = Ui.pill(this, PRESET_NAMES[i], false);
            p.setTag(PRESET_NAMES[i]);
            p.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyPreset(idx);
                }
            });
            LinearLayout.LayoutParams lp = Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) {
                lp.leftMargin = Ui.dp(this, Ui.SP_2);
            }
            presetRow.addView(p, lp);
        }
        Ui.addTop(col, presetRow, Ui.dp(this, Ui.SP_2));

        // ---- image quality ----
        col.addView(Ui.sectionLabel(this, "تصویر"));
        LinearLayout qCard = card();
        qVal = valueLabel(this, prefs.getInt(Prefs.K_QUALITY, 70) + " / 95");
        titleRow(qCard, "کیفیت JPEG", qVal);
        qCard.addView(Ui.caption(this, "کیفیت بالاتر = تصویر شفاف‌تر و پهنای باند بیشتر"));
        qBar = slider(qCard, 75, Math.max(0, prefs.getInt(Prefs.K_QUALITY, 70) - 20),
                new SimpleSeek() {
                    @Override
                    public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                        prefs.putInt(Prefs.K_QUALITY, p + 20);
                        qVal.setText((p + 20) + " / 95");
                        onManualChange(fromUser);
                    }
                });
        Ui.addTop(col, qCard, Ui.dp(this, Ui.SP_2));

        LinearLayout fCard = card();
        fVal = valueLabel(this, prefs.getInt(Prefs.K_FPS, 20) + " fps");
        titleRow(fCard, "نرخ فریم", fVal);
        fCard.addView(Ui.caption(this, "نرخ بالاتر = حرکت روان‌تر، مصرف بیشتر"));
        fBar = slider(fCard, 55, Math.max(0, prefs.getInt(Prefs.K_FPS, 20) - 5),
                new SimpleSeek() {
                    @Override
                    public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                        prefs.putInt(Prefs.K_FPS, p + 5);
                        fVal.setText((p + 5) + " fps");
                        onManualChange(fromUser);
                    }
                });
        Ui.addTop(col, fCard, Ui.dp(this, Ui.SP_2));

        LinearLayout sCard = card();
        sVal = valueLabel(this, String.format("%.0f%%", prefs.getFloat(Prefs.K_SCALE, 1f) * 100));
        titleRow(sCard, "مقیاس تصویر", sVal);
        sCard.addView(Ui.caption(this, "برای شبکه‌های ضعیف، تصویر کوچک‌تر ارسال می‌شود"));
        sBar = slider(sCard, 60, scaleToProgress(prefs.getFloat(Prefs.K_SCALE, 1f)),
                new SimpleSeek() {
                    @Override
                    public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                        float v = (p + 40) / 100f;
                        prefs.putFloat(Prefs.K_SCALE, v);
                        sVal.setText(String.format("%.0f%%", v * 100));
                        onManualChange(fromUser);
                    }
                });
        Ui.addTop(col, sCard, Ui.dp(this, Ui.SP_2));

        // ---- input ----
        col.addView(Ui.sectionLabel(this, "ورودی"));
        LinearLayout mCard = card();
        mVal = valueLabel(this, String.format("%.1fx", prefs.getFloat("sensitivity", 1.7f)));
        titleRow(mCard, "حساسیت موس", mVal);
        mCard.addView(Ui.caption(this, "در حالت موس، فاصله حرکت نشانگر برای هر کشیدن انگشت"));
        mBar = slider(mCard, 28, (int) ((prefs.getFloat("sensitivity", 1.7f) - 0.6f) * 10),
                new SimpleSeek() {
                    @Override
                    public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                        float v = 0.6f + p / 10f;
                        prefs.putFloat("sensitivity", v);
                        mVal.setText(String.format("%.1fx", v));
                    }
                });
        Ui.addTop(col, mCard, Ui.dp(this, Ui.SP_2));

        // ---- behaviour ----
        col.addView(Ui.sectionLabel(this, "رفتار"));
        LinearLayout toggles = Ui.column(this);
        toggles.setBackground(Ui.cardBg(this));
        int tp = Ui.dp(this, Ui.SP_2);
        toggles.setPadding(tp, tp, tp, tp);
        toggles.addView(toggle("همگام‌سازی کلیپ‌بورد", "متن کپی‌شده بین گوشی و کامپیوتر",
                Prefs.K_CLIP_SYNC, true));
        toggles.addView(Ui.divider(this));
        toggles.addView(toggle("نمایش آمار روی تصویر", "fps، تأخیر و حجم دریافتی",
                Prefs.K_STATS, false));
        toggles.addView(Ui.divider(this));
        toggles.addView(toggle("کیبورد بومی اندروید", "تایپ فارسی، عربی، چینی و ایموجی",
                Prefs.K_NATIVE_KB, true));
        Ui.addTop(col, toggles, Ui.dp(this, Ui.SP_2));

        // ---- advanced ----
        col.addView(Ui.sectionLabel(this, "پیشرفته"));
        LinearLayout adv = Ui.column(this);
        adv.setBackground(Ui.cardBg(this));
        adv.setPadding(tp, tp, tp, tp);
        adv.addView(linkRow("سرور پیش‌فرض", prefs.getString(Prefs.K_HOST, "—"),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        editHost();
                    }
                }));
        adv.addView(Ui.divider(this));
        adv.addView(linkRow("توکن دسترسی", maskToken(prefs.getString(Prefs.K_TOKEN, "")),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        editToken();
                    }
                }));
        adv.addView(Ui.divider(this));
        adv.addView(linkRow("پاک کردن تاریخچه اتصال", prefs.recents().length + " سرور ذخیره شده",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmClearRecents();
                    }
                }));
        Ui.addTop(col, adv, Ui.dp(this, Ui.SP_2));

        Ui.addTop(col, Ui.text(this,
                "کیبورد بومی از IME اندروید استفاده می‌کند؛ بنابراین تایپ فارسی، عربی، چینی و ایموجی "
                        + "به درستی و بدون تغییر به سرور می‌رسد.", Ui.TS_CAPTION, Ui.TEXT_FAINT, false),
                Ui.dp(this, Ui.SP_5));

        TextView version = Ui.text(this, "DevvioRD · نسخه " + versionName(),
                Ui.TS_MICRO, Ui.TEXT_FAINT, false);
        version.setGravity(Gravity.CENTER);
        Ui.addTop(col, version, Ui.dp(this, Ui.SP_4));

        sv.addView(col);
        syncPresetSelection();
        return sv;
    }

    // ------------------------------------------------------------- helpers

    private void onManualChange(boolean fromUser) {
        // Only user drags clear the preset; programmatic preset application
        // must keep its own selection highlighted.
        if (fromUser) {
            syncPresetSelection();
        }
        updateSummary();
    }

    private void applyPreset(int idx) {
        prefs.putInt(Prefs.K_QUALITY, P_Q[idx]);
        prefs.putInt(Prefs.K_FPS, P_F[idx]);
        prefs.putFloat(Prefs.K_SCALE, P_S[idx]);
        qBar.setProgress(P_Q[idx] - 20);
        fBar.setProgress(P_F[idx] - 5);
        sBar.setProgress(scaleToProgress(P_S[idx]));
        qVal.setText(P_Q[idx] + " / 95");
        fVal.setText(P_F[idx] + " fps");
        sVal.setText(String.format("%.0f%%", P_S[idx] * 100));
        syncPresetSelection();
        updateSummary();
    }

    /** Highlights the preset that matches the stored values, if any. */
    private void syncPresetSelection() {
        if (presetRow == null) {
            return;
        }
        int q = prefs.getInt(Prefs.K_QUALITY, 70);
        int f = prefs.getInt(Prefs.K_FPS, 20);
        float s = prefs.getFloat(Prefs.K_SCALE, 1f);
        for (int i = 0; i < presetRow.getChildCount(); i++) {
            TextView p = (TextView) presetRow.getChildAt(i);
            boolean sel = q == P_Q[i] && f == P_F[i] && Math.abs(s - P_S[i]) < 0.01f;
            Ui.setPillSelected(this, p, sel);
        }
    }

    private void updateSummary() {
        if (summary == null) {
            return;
        }
        int q = prefs.getInt(Prefs.K_QUALITY, 70);
        int f = prefs.getInt(Prefs.K_FPS, 20);
        float s = prefs.getFloat(Prefs.K_SCALE, 1f);
        // Rough bandwidth estimate: q70/1080p JPEG is around 90 KiB per frame.
        double kib = (q / 70.0) * 90 * (s * s);
        double mbit = kib * 8 * f / 1024.0;
        summary.setText("q" + q + " · " + f + " fps · " + String.format("%.0f%%", s * 100)
                + "  →  حدود " + String.format("%.1f", mbit) + " Mbps");
    }

    private static int scaleToProgress(float scale) {
        int p = (int) Math.round(scale * 100) - 40;
        return Math.max(0, Math.min(60, p));
    }

    private TextView valueLabel(android.content.Context c, String s) {
        TextView t = Ui.text(c, s, Ui.TS_LABEL, Ui.ACCENT, true);
        t.setBackground(Ui.roundRect(Ui.alpha(Ui.ACCENT, 0.12f), Ui.R_SM, c));
        t.setPadding(Ui.dp(c, Ui.SP_2), Ui.dp(c, Ui.SP_1), Ui.dp(c, Ui.SP_2), Ui.dp(c, Ui.SP_1));
        return t;
    }

    private void titleRow(LinearLayout card, String title, TextView value) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(Ui.text(this, title, Ui.TS_BODY, Ui.TEXT, true),
                Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(value);
        card.addView(r);
    }

    private SeekBar slider(LinearLayout card, int max, int progress,
                           SeekBar.OnSeekBarChangeListener l) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(Math.max(0, Math.min(max, progress)));
        s.setOnSeekBarChangeListener(l);
        int v = Ui.dp(this, Ui.SP_1);
        s.setPadding(0, v, 0, v);
        card.addView(s);
        return s;
    }

    private LinearLayout card() {
        LinearLayout c = Ui.column(this);
        c.setBackground(Ui.cardBg(this));
        int p = Ui.dp(this, Ui.SP_4);
        c.setPadding(p, Ui.dp(this, Ui.SP_3), p, Ui.dp(this, Ui.SP_2));
        return c;
    }

    private LinearLayout.LayoutParams leftParam(LinearLayout.LayoutParams lp, int leftPx) {
        lp.leftMargin = leftPx;
        return lp;
    }

    private View toggle(String label, String sub, final String key, boolean def) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, Ui.SP_3);
        row.setPadding(p, p, p, p);
        LinearLayout txt = Ui.column(this);
        txt.addView(Ui.text(this, label, Ui.TS_LABEL + 1, Ui.TEXT, false));
        txt.addView(Ui.text(this, sub, Ui.TS_MICRO, Ui.TEXT_FAINT, false));
        row.addView(txt, Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBool(key, def));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                prefs.putBool(key, checked);
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.dp(this, Ui.SP_2);
        row.addView(sw, slp);
        row.setBackground(Ui.selectableRow(this));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Tapping the row itself toggles too - the switch is small.
                Switch s = (Switch) ((LinearLayout) v).getChildAt(1);
                s.setChecked(!s.isChecked());
            }
        });
        return row;
    }

    private View linkRow(String label, String value, View.OnClickListener l) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, Ui.SP_3);
        row.setPadding(p, p, p, p);
        row.setBackground(Ui.selectableRow(this));
        LinearLayout txt = Ui.column(this);
        txt.addView(Ui.text(this, label, Ui.TS_LABEL + 1, Ui.TEXT, false));
        TextView v = Ui.text(this, value, Ui.TS_MICRO, Ui.TEXT_FAINT, false);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        txt.addView(v);
        row.addView(txt, Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(Ui.text(this, "›", 18, Ui.TEXT_FAINT, false));
        row.setOnClickListener(l);
        return row;
    }

    private static String maskToken(String t) {
        if (t == null || t.isEmpty()) {
            return "بدون توکن";
        }
        if (t.length() <= 4) {
            return "••••";
        }
        return t.substring(0, 2) + "••••" + t.substring(t.length() - 2);
    }

    private void editHost() {
        final EditText in = Ui.input(this, "host:port");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        in.setText(prefs.getString(Prefs.K_HOST, ""));
        new AlertDialog.Builder(this)
                .setTitle("سرور پیش‌فرض")
                .setMessage("نشانی سرور DevvioRD، مثلاً 192.168.1.10:5900")
                .setView(in)
                .setPositiveButton("ذخیره", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        prefs.putString(Prefs.K_HOST, in.getText().toString().trim());
                        recreate();
                    }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void editToken() {
        final EditText in = Ui.input(this, "توکن");
        in.setText(prefs.getString(Prefs.K_TOKEN, ""));
        new AlertDialog.Builder(this)
                .setTitle("توکن دسترسی")
                .setMessage("همان مقداری که سرور با ‎--token‎ اجرا شده است.")
                .setView(in)
                .setPositiveButton("ذخیره", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        prefs.putString(Prefs.K_TOKEN, in.getText().toString().trim());
                        recreate();
                    }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void confirmClearRecents() {
        new AlertDialog.Builder(this)
                .setTitle("پاک کردن تاریخچه؟")
                .setMessage("فهرست سرورهای اخیر خالی می‌شود. تنظیمات و توکن دست‌نخورده می‌مانند.")
                .setPositiveButton("پاک کن", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        prefs.clearRecents();
                        recreate();
                    }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar s) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar s) {
        }
    }
}
