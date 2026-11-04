package ai.devvio.rd;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Streaming quality and behaviour settings. */
public class SettingsActivity extends Activity {

    private Prefs prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        setContentView(build());
    }

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout col = Ui.column(this);
        int pad = Ui.dp(this, 18);
        col.setPadding(pad, Ui.dp(this, 24), pad, pad);

        TextView back = Ui.text(this, "‹ بازگشت", 14, Ui.ACCENT, true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        col.addView(back);
        Ui.addTop(col, Ui.title(this, "تنظیمات"), 12);

        // ---- image quality ----
        LinearLayout qCard = card("کیفیت تصویر / JPEG quality");
        final TextView qVal = Ui.text(this, prefs.getInt(Prefs.K_QUALITY, 70) + "", 13, Ui.ACCENT, true);
        qCard.addView(qVal);
        SeekBar qBar = new SeekBar(this);
        qBar.setMax(75);
        qBar.setProgress(Math.max(0, prefs.getInt(Prefs.K_QUALITY, 70) - 20));
        qBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                qVal.setText((p + 20) + "");
                prefs.putInt(Prefs.K_QUALITY, p + 20);
            }
        });
        qCard.addView(qBar);
        Ui.addTop(col, qCard, Ui.dp(this, 12));

        // ---- fps ----
        LinearLayout fCard = card("نرخ فریم / FPS");
        final TextView fVal = Ui.text(this, prefs.getInt(Prefs.K_FPS, 20) + "", 13, Ui.ACCENT, true);
        fCard.addView(fVal);
        SeekBar fBar = new SeekBar(this);
        fBar.setMax(55);
        fBar.setProgress(Math.max(0, prefs.getInt(Prefs.K_FPS, 20) - 5));
        fBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                fVal.setText((p + 5) + "");
                prefs.putInt(Prefs.K_FPS, p + 5);
            }
        });
        fCard.addView(fBar);
        Ui.addTop(col, fCard, Ui.dp(this, 10));

        // ---- scale ----
        LinearLayout sCard = card("مقیاس تصویر / Resolution scale");
        final TextView sVal = Ui.text(this, String.format("%.0f%%", prefs.getFloat(Prefs.K_SCALE, 1f) * 100),
                13, Ui.ACCENT, true);
        sCard.addView(sVal);
        SeekBar sBar = new SeekBar(this);
        sBar.setMax(60); // 40% .. 100%
        sBar.setProgress((int) (prefs.getFloat(Prefs.K_SCALE, 1f) * 100) - 40);
        sBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                float v = (p + 40) / 100f;
                sVal.setText(String.format("%.0f%%", v * 100));
                prefs.putFloat(Prefs.K_SCALE, v);
            }
        });
        sCard.addView(sBar);
        Ui.addTop(col, sCard, Ui.dp(this, 10));

        // ---- mouse sensitivity ----
        LinearLayout mCard = card("حساسیت موس / Mouse sensitivity");
        final TextView mVal = Ui.text(this, String.format("%.1fx", prefs.getFloat("sensitivity", 1.7f)),
                13, Ui.ACCENT, true);
        mCard.addView(mVal);
        SeekBar mBar = new SeekBar(this);
        mBar.setMax(28); // 0.6 .. 3.4
        mBar.setProgress((int) ((prefs.getFloat("sensitivity", 1.7f) - 0.6f) * 10));
        mBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                float v = 0.6f + p / 10f;
                mVal.setText(String.format("%.1fx", v));
                prefs.putFloat("sensitivity", v);
            }
        });
        mCard.addView(mBar);
        Ui.addTop(col, mCard, Ui.dp(this, 10));

        // ---- toggles ----
        Ui.addTop(col, toggle("همگام‌سازی کلیپ‌بورد / Clipboard sync", Prefs.K_CLIP_SYNC, true), 14);
        Ui.addTop(col, toggle("نمایش آمار روی تصویر / Show stats", Prefs.K_STATS, false), 8);
        Ui.addTop(col, toggle("کیبورد بومی اندروید / Native keyboard", Prefs.K_NATIVE_KB, true), 8);

        Ui.addTop(col, Ui.text(this,
                "کیبورد بومی از IME اندروید استفاده می‌کند؛ بنابراین تایپ فارسی، عربی، چینی و ایموجی "
                        + "به درستی به سرور می‌رسد.", 12, Ui.TEXT_DIM, false), 18);

        sv.addView(col);
        return sv;
    }

    private View toggle(String label, final String key, boolean def) {
        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.roundRect(Ui.SURFACE, 14, this));
        int p = Ui.dp(this, 14);
        row.setPadding(p, p, p, p);
        TextView t = Ui.text(this, label, 14, Ui.TEXT, false);
        row.addView(t, Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBool(key, def));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                prefs.putBool(key, checked);
            }
        });
        row.addView(sw);
        return row;
    }

    /** A titled settings card; spacing is added by the caller via Ui.addTop. */
    private LinearLayout card(String title) {
        LinearLayout c = Ui.column(this);
        c.setBackground(Ui.roundRect(Ui.SURFACE, 16, this));
        int p = Ui.dp(this, 14);
        c.setPadding(p, p, p, p);
        c.addView(Ui.text(this, title, 13, Ui.TEXT_DIM, false));
        return c;
    }

    private LinearLayout.LayoutParams withTop(View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, topDp);
        v.setLayoutParams(lp);
        return lp;
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
