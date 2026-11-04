package ai.devvio.rd;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Design system: colors, spacing, and helpers for building views in code.
 * A single palette keeps every screen consistent.
 */
public final class Ui {

    public static final int BG = 0xFF0B0F14;
    public static final int SURFACE = 0xFF141A21;
    public static final int SURFACE_2 = 0xFF1D252F;
    public static final int SURFACE_3 = 0xFF283340;
    public static final int ACCENT = 0xFF35D07F;
    public static final int ACCENT_DIM = 0xFF1F7A4B;
    public static final int BLUE = 0xFF5B9DF5;
    public static final int TEXT = 0xFFE9EFF6;
    public static final int TEXT_DIM = 0xFF93A4B8;
    public static final int DANGER = 0xFFFF6B5F;
    public static final int WARN = 0xFFF5C451;

    public static final int PAD = 16;

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static GradientDrawable roundRect(int color, int radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable roundRect(int color, int radiusDp, int strokeColor, int strokeDp,
                                             Context c) {
        GradientDrawable d = roundRect(color, radiusDp, c);
        d.setStroke(dp(c, strokeDp), strokeColor);
        return d;
    }

    public static View card(Context c, int radiusDp) {
        View v = new View(c);
        v.setBackground(roundRect(SURFACE, radiusDp, c));
        return v;
    }

    /** Applies a ripple to any view (API 21+). */
    public static void ripple(View v, int color) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(color);
        content.setCornerRadius(v.getResources().getDisplayMetrics().density * 12);
        if (Build.VERSION.SDK_INT >= 21) {
            v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null));
        } else {
            v.setBackground(content);
        }
    }

    public static TextView text(Context c, String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    public static TextView title(Context c, String s) {
        TextView t = text(c, s, 26, TEXT, true);
        t.setLetterSpacing(0.01f);
        return t;
    }

    public static TextView caption(Context c, String s) {
        return text(c, s, 12, TEXT_DIM, false);
    }

    public static Button button(Context c, String label, int bg, int fg) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextColor(fg);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(roundRect(bg, 14, c));
        int p = dp(c, 14);
        b.setPadding(p, p, p, p);
        b.setMinHeight(dp(c, 52));
        b.setStateListAnimator(null);
        return b;
    }

    public static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(TEXT_DIM);
        e.setTextColor(TEXT);
        e.setTextSize(16);
        e.setSingleLine(true);
        e.setBackground(roundRect(SURFACE_2, 12, SURFACE_3, 1, c));
        int p = dp(c, 14);
        e.setPadding(p, p, p, p);
        return e;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpm(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.weight = weight;
        return p;
    }

    public static FrameLayout.LayoutParams flp(int w, int h, int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h);
        p.gravity = gravity;
        return p;
    }

    public static void margin(View v, int l, int t, int r, int b) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) lp).setMargins(l, t, r, b);
            v.setLayoutParams(lp);
        }
    }

    /**
     * Adds a child with a top margin in one call, keeping call sites readable:
     * {@code Ui.addTop(container, view, dp)}.
     */
    public static void addTop(LinearLayout parent, View child, int topPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topPx;
        parent.addView(child, lp);
    }

    /** Adds a child using the given params. */
    public static void add(LinearLayout parent, View child, LinearLayout.LayoutParams lp) {
        parent.addView(child, lp);
    }


    /** A small rounded "chip" label. */
    public static TextView chip(Context c, String s, int color) {
        TextView t = text(c, s, 11, color, true);
        t.setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5));
        t.setBackground(roundRect(0x22000000 | (color & 0xFFFFFF), 20, color & 0x55FFFFFF, 1, c));
        return t;
    }

    public static int alpha(int color, float a) {
        return (color & 0x00FFFFFF) | (((int) (a * 255)) << 24);
    }
}
