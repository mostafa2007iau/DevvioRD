package ai.devvio.rd;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Design system: palette, spacing scale, type scale and view factories.
 *
 * <p>Everything the app draws comes from here so the four screens look like one
 * product. Two rules keep it consistent:
 * <ul>
 *   <li>spacing uses the {@code SP_*} scale (4 dp grid), never ad-hoc pixels;</li>
 *   <li>text uses the {@code TS_*} type scale and the three text colours, so
 *       contrast stays predictable on the dark background.</li>
 * </ul>
 */
public final class Ui {

    // ---- surface palette (dark, neutral, one saturated accent) -------------
    public static final int BG = 0xFF0A0E13;
    public static final int BG_ELEV = 0xFF0D1219;
    public static final int SURFACE = 0xFF141A21;
    public static final int SURFACE_2 = 0xFF1C242E;
    public static final int SURFACE_3 = 0xFF26313D;
    public static final int LINE = 0xFF1E2731;

    // ---- semantic colours -------------------------------------------------
    public static final int ACCENT = 0xFF35D07F;       // primary action, "connected"
    public static final int ACCENT_DIM = 0xFF1F7A4B;
    public static final int ACCENT_INK = 0xFF04140C;   // text on accent
    public static final int BLUE = 0xFF5B9DF5;         // secondary/informational
    public static final int TEXT = 0xFFE9EFF6;
    public static final int TEXT_DIM = 0xFF93A4B8;
    public static final int TEXT_FAINT = 0xFF6B7A8C;
    public static final int DANGER = 0xFFFF6B5F;
    public static final int WARN = 0xFFF5C451;

    // ---- spacing scale (dp) -----------------------------------------------
    public static final int SP_1 = 4;
    public static final int SP_2 = 8;
    public static final int SP_3 = 12;
    public static final int SP_4 = 16;
    public static final int SP_5 = 20;
    public static final int SP_6 = 24;
    public static final int SP_7 = 32;
    public static final int PAD = SP_4;

    // ---- type scale (sp) ---------------------------------------------------
    public static final float TS_DISPLAY = 30;
    public static final float TS_TITLE = 22;
    public static final float TS_HEADING = 17;
    public static final float TS_BODY = 15;
    public static final float TS_LABEL = 13;
    public static final float TS_CAPTION = 12;
    public static final float TS_MICRO = 10.5f;

    // ---- corner radii (dp) -------------------------------------------------
    public static final int R_SM = 8;
    public static final int R_MD = 12;
    public static final int R_LG = 16;
    public static final int R_XL = 20;

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /** True for languages written right-to-left, used to mirror layouts. */
    public static boolean isRtl(Context c) {
        return c.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    // ---- backgrounds ------------------------------------------------------

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

    /** A card: surface fill, hairline border, rounded corners. */
    public static GradientDrawable cardBg(Context c) {
        return roundRect(SURFACE, R_LG, LINE, 1, c);
    }

    /** A vertical gradient, used for hero areas and primary buttons. */
    public static GradientDrawable verticalGradient(int top, int bottom, int radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, bottom});
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /**
     * Pressable background with a ripple. Replaces the old {@code ripple}
     * helper, which clobbered borders because it drew its own shape.
     */
    public static Drawable pressable(int fill, int radiusDp, int strokeColor, Context c) {
        GradientDrawable content = roundRect(fill, radiusDp, strokeColor, 1, c);
        if (Build.VERSION.SDK_INT >= 21) {
            return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null);
        }
        return content;
    }

    /** Solid pressable fill without a border. */
    public static Drawable pressable(int fill, int radiusDp, Context c) {
        return pressable(fill, radiusDp, fill, c);
    }

    // ---- text -------------------------------------------------------------

    public static TextView text(Context c, String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }
        return t;
    }

    public static TextView display(Context c, String s) {
        TextView t = text(c, s, TS_DISPLAY, TEXT, true);
        t.setLetterSpacing(-0.01f);
        return t;
    }

    public static TextView title(Context c, String s) {
        TextView t = text(c, s, TS_TITLE, TEXT, true);
        t.setLetterSpacing(0.01f);
        return t;
    }

    public static TextView heading(Context c, String s) {
        return text(c, s, TS_HEADING, TEXT, true);
    }

    public static TextView body(Context c, String s) {
        return text(c, s, TS_BODY, TEXT, false);
    }

    public static TextView label(Context c, String s) {
        return text(c, s, TS_LABEL, TEXT_DIM, false);
    }

    public static TextView caption(Context c, String s) {
        return text(c, s, TS_CAPTION, TEXT_DIM, false);
    }

    /** Small all-caps section label, used above groups of controls. */
    public static TextView sectionLabel(Context c, String s) {
        TextView t = text(c, s, TS_MICRO + 0.5f, TEXT_FAINT, true);
        t.setLetterSpacing(0.12f);
        t.setAllCaps(true);
        return t;
    }

    public static TextView mono(Context c, String s, float sizeSp, int color) {
        TextView t = text(c, s, sizeSp, color, false);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    // ---- interactive ------------------------------------------------------

    /** Primary button: accent gradient, dark ink, full width by default. */
    public static TextView primaryButton(Context c, String label) {
        TextView t = text(c, label, TS_BODY, ACCENT_INK, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(pressable(ACCENT, R_MD, ACCENT, c));
        int v = dp(c, 15);
        int h = dp(c, 16);
        t.setPadding(h, v, h, v);
        t.setMinHeight(dp(c, 50));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** Secondary button: flat surface with a border. */
    public static TextView secondaryButton(Context c, String label) {
        TextView t = text(c, label, TS_LABEL + 1, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(pressable(SURFACE_2, R_MD, SURFACE_3, c));
        int v = dp(c, 13);
        int h = dp(c, 14);
        t.setPadding(h, v, h, v);
        t.setMinHeight(dp(c, 46));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** Compact icon/label button used in the in-session toolbars. */
    public static TextView toolButton(Context c, String label, float sizeSp) {
        TextView t = text(c, label, sizeSp, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(pressable(SURFACE_2, R_MD, SURFACE_3, c));
        int v = dp(c, 8);
        int h = dp(c, 10);
        t.setPadding(h, v, h, v);
        t.setMinWidth(dp(c, 40));
        t.setMinHeight(dp(c, 38));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** A pill/chip, optionally in its selected state. */
    public static TextView pill(Context c, String label, boolean selected) {
        TextView t = text(c, label, TS_LABEL, selected ? ACCENT_INK : TEXT_DIM, true);
        t.setGravity(Gravity.CENTER);
        if (selected) {
            t.setBackground(pressable(ACCENT, R_XL, ACCENT, c));
        } else {
            t.setBackground(pressable(SURFACE_2, R_XL, SURFACE_3, c));
        }
        int v = dp(c, 9);
        int h = dp(c, 15);
        t.setPadding(h, v, h, v);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** Restyles a pill in place (used by the mode selector). */
    public static void setPillSelected(Context c, TextView t, boolean selected) {
        t.setTextColor(selected ? ACCENT_INK : TEXT_DIM);
        if (selected) {
            t.setBackground(pressable(ACCENT, R_XL, ACCENT, c));
        } else {
            t.setBackground(pressable(SURFACE_2, R_XL, SURFACE_3, c));
        }
    }

    public static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(TEXT_FAINT);
        e.setTextColor(TEXT);
        e.setTextSize(TS_BODY);
        e.setSingleLine(true);
        e.setBackground(roundRect(SURFACE_2, R_MD, SURFACE_3, 1, c));
        int p = dp(c, 14);
        e.setPadding(p, p, p, p);
        e.setSelectAllOnFocus(false);
        return e;
    }

    /** Small status dot, e.g. the connection indicator. */
    public static View dot(Context c, int color, int sizeDp) {
        View v = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        v.setBackground(d);
        int s = dp(c, sizeDp);
        v.setLayoutParams(new ViewGroup.LayoutParams(s, s));
        return v;
    }

    // ---- layout helpers ---------------------------------------------------

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

    /** Adds a full-width child with equal spacing of {@code gapDp} above it. */
    public static void addGap(LinearLayout parent, View child, Context c, int gapDp) {
        addTop(parent, child, dp(c, gapDp));
    }

    /** Adds a child using the given params. */
    public static void add(LinearLayout parent, View child, LinearLayout.LayoutParams lp) {
        parent.addView(child, lp);
    }

    /** A panel: surface fill, rounded, consistent inner padding. */
    public static LinearLayout panel(Context c) {
        LinearLayout l = column(c);
        l.setBackground(cardBg(c));
        int p = dp(c, SP_4);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** Adds a hairline divider. */
    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(c, 0.7f))));
        return v;
    }

    /** Horizontal spacing filler with a weight. */
    public static View spacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    public static int alpha(int color, float a) {
        int aa = (int) (a * 255);
        if (aa < 0) {
            aa = 0;
        }
        if (aa > 255) {
            aa = 255;
        }
        return (color & 0x00FFFFFF) | (aa << 24);
    }

    /**
     * Blends {@code color} toward {@code toward} by {@code amount} (0..1). Used
     * to derive hover/pressed tints without hard-coding more hex values.
     */
    public static int blend(int color, int toward, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int a = (int) (((color >>> 24) & 0xFF) * (1 - t) + ((toward >>> 24) & 0xFF) * t);
        int r = (int) (((color >>> 16) & 0xFF) * (1 - t) + ((toward >>> 16) & 0xFF) * t);
        int g = (int) (((color >>> 8) & 0xFF) * (1 - t) + ((toward >>> 8) & 0xFF) * t);
        int b = (int) ((color & 0xFF) * (1 - t) + (toward & 0xFF) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Selectable background for list rows. */
    public static StateListDrawable selectableRow(Context c) {
        StateListDrawable d = new StateListDrawable();
        GradientDrawable pressed = roundRect(SURFACE_2, R_MD, c);
        d.addState(new int[]{android.R.attr.state_pressed}, pressed);
        d.addState(new int[]{}, roundRect(0x00000000, R_MD, c));
        return d;
    }
}
