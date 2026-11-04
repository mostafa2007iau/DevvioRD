package ai.devvio.rd;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * Connection screen: host + token, pointer mode, recent hosts and shortcuts to
 * the other screens.
 *
 * <p>Layout is a hero band, one primary card, then progressively less important
 * content. Every value the user types is persisted as they type, so a failed
 * attempt never costs them their input.
 */
public class ConnectActivity extends Activity {

    private Prefs prefs;
    private EditText hostInput;
    private EditText tokenInput;
    private TextView errorText;
    private TextView connectButton;
    private LinearLayout recentsBox;
    private TextView touchPill;
    private TextView mousePill;
    private TextView modeHint;
    private TextView recentEmpty;
    private String mode = Proto.MODE_TOUCH;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        setContentView(buildUi());
        prefillFromIntent();
        checkSharedClipboard();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout col = Ui.column(this);
        int pad = Ui.dp(this, Ui.SP_5);
        col.setPadding(pad, Ui.dp(this, Ui.SP_6), pad, Ui.dp(this, Ui.SP_6));

        addHero(col);
        addConnectionCard(col);
        addRecentHosts(col);
        addShortcuts(col);
        addServerHint(col);
        addFooter(col);

        scroll.addView(col);
        root.addView(scroll, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    /** Brand block: logo, name, one-line explanation. */
    private void addHero(LinearLayout col) {
        LinearLayout brand = Ui.row(this);
        TextView logo = Ui.text(this, "D", 19, Ui.ACCENT_INK, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(Ui.verticalGradient(0xFF43E394, Ui.ACCENT, Ui.R_MD, this));
        brand.addView(logo, Ui.lp(Ui.dp(this, 36), Ui.dp(this, 36)));

        LinearLayout names = Ui.column(this);
        TextView name = Ui.text(this, "DevvioRD", 20, Ui.TEXT, true);
        name.setLetterSpacing(0.04f);
        names.addView(name);
        names.addView(Ui.caption(this, "دسکتاپ راه دور  ·  Remote Desktop"));
        brand.addView(names, withLeftMargin(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, Ui.SP_3)));
        col.addView(brand);

        // Live status line: says what the app is ready to do, not just decoration.
        LinearLayout state = Ui.row(this);
        View dot = Ui.dot(this, Ui.ACCENT, 7);
        state.addView(dot);
        state.addView(Ui.text(this, "آماده اتصال", Ui.TS_CAPTION, Ui.TEXT_DIM, false),
                withLeftMargin(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, Ui.SP_2)));
        Ui.addGap(col, state, this, Ui.SP_3);
    }

    /** The one card that matters: where to connect and how. */
    private void addConnectionCard(LinearLayout col) {
        LinearLayout card = Ui.panel(this);
        card.setBackground(Ui.cardBg(this));

        TextView head = Ui.heading(this, "اتصال به سرور");
        card.addView(head);
        card.addView(Ui.caption(this, "آدرس و پورت نمایش‌داده‌شده در کنسول سرور را وارد کنید."));

        hostInput = Ui.input(this, "192.168.1.10:5900");
        hostInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setText(prefs.getString(Prefs.K_HOST, ""));
        hostInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        hostInput.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void after(String s) {
                prefs.putString(Prefs.K_HOST, s);
                hideError();
            }
        });
        Ui.addGap(card, label("آدرس سرور / Host"), this, Ui.SP_4);
        Ui.addGap(card, hostInput, this, Ui.SP_2);

        tokenInput = Ui.input(this, "اختیاری — اگر سرور توکن دارد");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        tokenInput.setText(prefs.getString(Prefs.K_TOKEN, ""));
        tokenInput.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void after(String s) {
                prefs.putString(Prefs.K_TOKEN, s);
            }
        });
        Ui.addGap(card, label("رمز دسترسی / Token"), this, Ui.SP_3);
        Ui.addGap(card, tokenInput, this, Ui.SP_2);

        // Pointer mode as a two-option segmented control with a live explanation.
        Ui.addGap(card, label("حالت کنترل / Control mode"), this, Ui.SP_4);
        LinearLayout seg = Ui.row(this);
        mode = prefs.getString(Prefs.K_MODE, Proto.MODE_TOUCH);
        touchPill = Ui.pill(this, "👆 لمسی", true);
        mousePill = Ui.pill(this, "🖱 موس", false);
        touchPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectMode(Proto.MODE_TOUCH);
            }
        });
        mousePill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectMode(Proto.MODE_MOUSE);
            }
        });
        seg.addView(touchPill, Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        seg.addView(mousePill, withLeftMargin(
                Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1), Ui.dp(this, Ui.SP_2)));
        Ui.addGap(card, seg, this, Ui.SP_2);

        modeHint = Ui.text(this, "", Ui.TS_CAPTION, Ui.TEXT_FAINT, false);
        modeHint.setLineSpacing(Ui.dp(this, 2), 1f);
        Ui.addGap(card, modeHint, this, Ui.SP_2);

        connectButton = Ui.primaryButton(this, "اتصال  /  Connect");
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doConnect();
            }
        });
        Ui.addGap(card, connectButton, this, Ui.SP_5);

        errorText = Ui.text(this, "", Ui.TS_LABEL, Ui.DANGER, false);
        errorText.setVisibility(View.GONE);
        errorText.setLineSpacing(Ui.dp(this, 2), 1f);
        Ui.addGap(card, errorText, this, Ui.SP_3);

        selectMode(Proto.MODE_MOUSE.equals(mode) ? Proto.MODE_MOUSE : Proto.MODE_TOUCH);
        Ui.addGap(col, card, this, Ui.SP_4);
    }

    private void addRecentHosts(LinearLayout col) {
        LinearLayout head = Ui.row(this);
        head.addView(Ui.sectionLabel(this, "آدرس‌های اخیر"), Ui.lpm(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView clear = Ui.text(this, "پاک کردن", Ui.TS_CAPTION, Ui.TEXT_FAINT, false);
        clear.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClearRecents();
            }
        });
        head.addView(clear);
        Ui.addGap(col, head, this, Ui.SP_5);

        recentsBox = Ui.column(this);
        Ui.addGap(col, recentsBox, this, Ui.SP_2);
        rebuildRecents();
    }

    private void addShortcuts(LinearLayout col) {
        LinearLayout actions = Ui.row(this);
        actions.addView(shortcut("📁", "فایل‌ها", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ConnectActivity.this, FilesActivity.class));
            }
        }), Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(shortcut("⚙", "تنظیمات", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ConnectActivity.this, SettingsActivity.class));
            }
        }), withLeftMargin(Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1),
                Ui.dp(this, Ui.SP_2)));
        actions.addView(shortcut("?", "راهنما", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ConnectActivity.this, HelpActivity.class));
            }
        }), withLeftMargin(Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1),
                Ui.dp(this, Ui.SP_2)));
        Ui.addGap(col, actions, this, Ui.SP_6);
    }

    private View shortcut(String icon, String label, View.OnClickListener l) {
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER);
        box.setBackground(Ui.pressable(Ui.SURFACE, Ui.R_MD, Ui.LINE, this));
        int p = Ui.dp(this, Ui.SP_3);
        box.setPadding(p, p, p, p);
        TextView i = Ui.text(this, icon, 18, Ui.TEXT, false);
        i.setGravity(Gravity.CENTER);
        box.addView(i);
        TextView t = Ui.text(this, label, Ui.TS_CAPTION, Ui.TEXT_DIM, false);
        t.setGravity(Gravity.CENTER);
        Ui.addGap(box, t, this, Ui.SP_1);
        box.setOnClickListener(l);
        box.setClickable(true);
        return box;
    }

    private void addServerHint(LinearLayout col) {
        LinearLayout info = Ui.column(this);
        info.setBackground(Ui.roundRect(Ui.BG_ELEV, Ui.R_MD, Ui.LINE, 1, this));
        int p = Ui.dp(this, Ui.SP_4);
        info.setPadding(p, p, p, p);

        info.addView(Ui.text(this, "روی کامپیوتر مقصد اجرا کنید", Ui.TS_CAPTION, Ui.TEXT_DIM, false));
        TextView cmd = Ui.mono(this, "devviord --token 1234", Ui.TS_CAPTION, Ui.ACCENT);
        cmd.setPadding(0, Ui.dp(this, Ui.SP_1), 0, 0);
        info.addView(cmd);
        TextView note = Ui.text(this,
                "آدرس چاپ‌شده (و QR) را همین‌جا وارد کنید. پورت پیش‌فرض ۵۹۰۰ است.",
                Ui.TS_CAPTION, Ui.TEXT_FAINT, false);
        note.setLineSpacing(Ui.dp(this, 2), 1f);
        Ui.addGap(info, note, this, Ui.SP_2);

        // Handy for setting up the server: show this phone's own LAN address so
        // the user can compare subnets when the connection fails.
        String local = localAddress();
        if (local != null) {
            Ui.addGap(info, Ui.mono(this, "IP این گوشی: " + local, Ui.TS_MICRO, Ui.TEXT_FAINT),
                    this, Ui.SP_2);
        }
        Ui.addGap(col, info, this, Ui.SP_5);
    }

    private void addFooter(LinearLayout col) {
        TextView build = Ui.text(this, "نسخه " + BuildConfig.VERSION_NAME
                        + "  ·  " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
                Ui.TS_MICRO, Ui.TEXT_FAINT, false);
        build.setGravity(Gravity.CENTER);
        Ui.addGap(col, build, this, Ui.SP_5);
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, Ui.TS_CAPTION, Ui.TEXT_DIM, true);
        return t;
    }

    // -------------------------------------------------------------- recents

    private void rebuildRecents() {
        if (recentsBox == null) {
            return;
        }
        recentsBox.removeAllViews();
        String[] recents = prefs.recents();
        if (recents.length == 0) {
            recentEmpty = Ui.text(this, "هنوز سروری ذخیره نشده — اولین اتصال اینجا ظاهر می‌شود.",
                    Ui.TS_CAPTION, Ui.TEXT_FAINT, false);
            recentEmpty.setBackground(Ui.roundRect(Ui.BG_ELEV, Ui.R_MD, this));
            int p = Ui.dp(this, Ui.SP_3);
            recentEmpty.setPadding(p, p, p, p);
            recentsBox.addView(recentEmpty);
            return;
        }
        for (final String host : recents) {
            LinearLayout row = Ui.row(this);
            row.setBackground(Ui.pressable(Ui.SURFACE, Ui.R_MD, this));
            int p = Ui.dp(this, Ui.SP_3);
            row.setPadding(p, p, p, p);

            TextView icon = Ui.text(this, "🖥", 16, Ui.TEXT_DIM, false);
            row.addView(icon);

            LinearLayout txt = Ui.column(this);
            TextView name = Ui.text(this, host, Ui.TS_LABEL + 1, Ui.TEXT, false);
            txt.addView(name);
            TextView meta = Ui.text(this, "لمس بزنید تا وصل شود", Ui.TS_MICRO, Ui.TEXT_FAINT, false);
            txt.addView(meta);
            row.addView(txt, withLeftMargin(Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1),
                    Ui.dp(this, Ui.SP_3)));

            TextView go = Ui.pill(this, "اتصال", false);
            go.setTextSize(Ui.TS_CAPTION);
            row.addView(go);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hostInput.setText(host);
                    doConnect();
                }
            });
            recentsBox.addView(row);
            Ui.margin(row, 0, 0, 0, Ui.dp(this, Ui.SP_2));
        }
    }

    private void confirmClearRecents() {
        new AlertDialog.Builder(this)
                .setTitle("پاک کردن آدرس‌های اخیر؟")
                .setMessage("فهرست سرورهای ذخیره‌شده حذف می‌شود. توکن و تنظیمات دست‌نخورده می‌مانند.")
                .setPositiveButton("پاک کن", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        prefs.clearRecents();
                        rebuildRecents();
                    }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    // ---------------------------------------------------------------- state

    private void selectMode(String m) {
        mode = m;
        prefs.putString(Prefs.K_MODE, m);
        boolean mouse = Proto.MODE_MOUSE.equals(m);
        Ui.setPillSelected(this, touchPill, !mouse);
        Ui.setPillSelected(this, mousePill, mouse);
        modeHint.setText(mouse
                ? "موس: کشیدن انگشت مکان‌نما را حرکت می‌دهد، ضربه کلیک است، دو انگشت اسکرول."
                : "لمسی: هر لمس مستقیم روی همان نقطه دسکتاپ فرود می‌آید.");
        hideError();
    }

    /**
     * Handles both internal navigation and devviord:// deep links, which is what
     * the pairing QR code encodes:
     *   devviord://connect?host=192.168.1.10:5900&token=abc&auto=1
     */
    private void prefillFromIntent() {
        Intent it = getIntent();
        if (it == null) {
            return;
        }
        String host = it.getStringExtra("host");
        String token = it.getStringExtra("token");
        boolean auto = it.getBooleanExtra("autoConnect", false);

        Uri data = it.getData();
        if (data != null && "devviord".equalsIgnoreCase(data.getScheme())) {
            String h = data.getQueryParameter("host");
            String t = data.getQueryParameter("token");
            if (h != null) {
                host = h;
            }
            if (t != null) {
                token = t;
            }
            String a = data.getQueryParameter("auto");
            if (a == null || "1".equals(a) || "true".equalsIgnoreCase(a)) {
                auto = true;
            }
            if (host == null && data.getPath() != null) {
                String p = data.getPath().replace("/", "");
                if (!p.isEmpty() && !p.equalsIgnoreCase("connect")) {
                    host = p;
                }
            }
        }
        if (host != null && !host.isEmpty()) {
            hostInput.setText(normalizeDisplayHost(host));
        }
        if (token != null) {
            tokenInput.setText(token);
        }
        if (auto && host != null && !host.isEmpty()) {
            doConnect();
        }
    }

    /** Shows a friendly host:port in the field even if a full URL was received. */
    private static String normalizeDisplayHost(String raw) {
        String h = raw.trim();
        h = h.replaceFirst("^wss?://", "").replaceFirst("^https?://", "");
        h = h.replaceAll("/ws$", "").replaceAll("/+$", "");
        return h;
    }

    /**
     * If the app was launched from a "share text" action, offer to send it to
     * the host clipboard once connected.
     */
    private void checkSharedClipboard() {
        CharSequence txt = getIntent() == null ? null : getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (txt != null && txt.length() > 0) {
            showInfo("📋 متنی از اشتراک‌گذاری آماده است (" + txt.length()
                    + " کاراکتر) — پس از اتصال خودکار ارسال می‌شود.");
        }
    }

    // ------------------------------------------------------------- connect

    private void doConnect() {
        String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            showError("آدرس سرور را وارد کنید. مثلاً 192.168.1.10:5900");
            hostInput.requestFocus();
            return;
        }
        if (host.contains(" ") || (host.contains("/") && !host.startsWith("ws"))) {
            showError("آدرس نامعتبر است. نمونه درست: 192.168.1.10:5900");
            hostInput.requestFocus();
            return;
        }
        String token = tokenInput.getText().toString().trim();
        prefs.putString(Prefs.K_HOST, host);
        prefs.putString(Prefs.K_TOKEN, token);
        prefs.putString(Prefs.K_MODE, mode);
        prefs.addRecent(normalizeHost(host));
        hideError();

        Intent i = new Intent(this, RemoteActivity.class);
        i.putExtra("host", normalizeHost(host));
        i.putExtra("token", token);
        i.putExtra("mode", mode);
        CharSequence shared = getIntent() == null ? null : getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (shared != null && shared.length() > 0) {
            i.putExtra("sharedText", shared.toString());
        }
        startActivity(i);
    }

    private void showError(String s) {
        errorText.setVisibility(View.VISIBLE);
        errorText.setTextColor(Ui.DANGER);
        errorText.setText("⚠  " + s);
    }

    private void showInfo(String s) {
        errorText.setVisibility(View.VISIBLE);
        errorText.setTextColor(Ui.BLUE);
        errorText.setText(s);
    }

    private void hideError() {
        if (errorText != null) {
            errorText.setVisibility(View.GONE);
        }
    }

    /** Accepts "ip", "ip:port", "http://ip:port", "ws://ip:port". */
    static String normalizeHost(String raw) {
        String h = raw.trim();
        if (h.isEmpty()) {
            return h;
        }
        if (h.startsWith("ws://") || h.startsWith("wss://") || h.startsWith("http://")
                || h.startsWith("https://")) {
            return h;
        }
        if (h.contains(":") && h.indexOf(':') == h.lastIndexOf(':')) {
            return "ws://" + h;
        }
        if (!h.contains(":")) {
            return "ws://" + h + ":5900";
        }
        return "ws://" + h;
    }

    /** Finds this device's LAN address (shown as a hint for Wi-Fi setups). */
    static String localAddress() {
        try {
            List<NetworkInterface> ifs = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : ifs) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!a.isLoopbackAddress() && a.getHostAddress().indexOf(':') < 0) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // best effort: this is only a hint
        }
        return null;
    }

    private LinearLayout.LayoutParams withLeftMargin(LinearLayout.LayoutParams lp, int left) {
        lp.leftMargin = left;
        return lp;
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuildRecents();
        // Coming back from Settings: reflect a mode change made there.
        String saved = prefs.getString(Prefs.K_MODE, Proto.MODE_TOUCH);
        if (touchPill != null && !saved.equals(mode)) {
            selectMode(Proto.MODE_MOUSE.equals(saved) ? Proto.MODE_MOUSE : Proto.MODE_TOUCH);
        }
    }

    /** Minimal TextWatcher: only the "after" callback is ever needed here. */
    private abstract static class SimpleWatcher implements android.text.TextWatcher {
        abstract void after(String s);

        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
            after(s == null ? "" : s.toString());
        }
    }
}
