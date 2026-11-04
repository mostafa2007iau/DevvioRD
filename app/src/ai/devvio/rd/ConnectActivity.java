package ai.devvio.rd;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * Connection screen: host, token, pointer mode and quick access to settings,
 * session history and help.
 */
public class ConnectActivity extends Activity {

    private Prefs prefs;
    private EditText hostInput;
    private EditText tokenInput;
    private TextView errorText;
    private TextView probeText;
    private View connectButton;
    private LinearLayout recentsBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        setContentView(buildUi());
        prefillFromIntent();
        checkSharedClipboard();
    }

    private View buildUi() {
        FrameLayoutRoot root = new FrameLayoutRoot(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout col = Ui.column(this);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, Ui.dp(this, 40), pad, pad);

        // --- brand header ---
        LinearLayout brand = Ui.row(this);
        View dot = new View(this);
        dot.setBackground(Ui.roundRect(Ui.ACCENT, 8, this));
        brand.addView(dot, Ui.lp(Ui.dp(this, 10), Ui.dp(this, 10)));
        TextView name = Ui.text(this, "DevvioRD", 22, Ui.TEXT, true);
        name.setLetterSpacing(0.06f);
        brand.addView(name, withLeftMargin(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 10)));
        col.addView(brand);

        TextView tag = Ui.text(this, "Remote Desktop  ·  سرویس دسکتاپ راه دور", 13, Ui.TEXT_DIM, false);
        Ui.addTop(col, tag, Ui.dp(this, 6));

        // --- card: connection ---
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.roundRect(Ui.SURFACE, 18, this));
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));

        card.addView(Ui.text(this, "اتصال به سرور / Connect to host", 15, Ui.TEXT, true));

        hostInput = Ui.input(this, "192.168.1.10:5900");
        hostInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setText(prefs.getString(Prefs.K_HOST, ""));
        Ui.addTop(card, hostInput, Ui.dp(this, 12));

        tokenInput = Ui.input(this, "رمز دسترسی / Access token (اختیاری)");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setText(prefs.getString(Prefs.K_TOKEN, ""));
        Ui.addTop(card, tokenInput, Ui.dp(this, 10));

        // pointer mode selector
        LinearLayout modeRow = Ui.row(this);
        modeRow.addView(Ui.text(this, "حالت کنترل", 13, Ui.TEXT_DIM, false));
        final TextView touchBtn = pill("لمسی / Touch", true);
        final TextView mouseBtn = pill("موس / Mouse", false);
        touchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectMode(prefs, Proto.MODE_TOUCH, touchBtn, mouseBtn);
            }
        });
        mouseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectMode(prefs, Proto.MODE_MOUSE, touchBtn, mouseBtn);
            }
        });
        modeRow.addView(touchBtn, withLeftMargin(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 12)));
        modeRow.addView(mouseBtn, withLeftMargin(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 8)));
        Ui.addTop(card, modeRow, Ui.dp(this, 14));

        boolean mouse = Proto.MODE_MOUSE.equals(prefs.getString(Prefs.K_MODE, Proto.MODE_TOUCH));
        selectMode(prefs, mouse ? Proto.MODE_MOUSE : Proto.MODE_TOUCH, touchBtn, mouseBtn);

        Button connect = Ui.button(this, "اتصال  /  Connect", Ui.ACCENT, 0xFF04140C);
        connectButton = connect;
        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doConnect();
            }
        });
        Ui.addTop(card, connect, Ui.dp(this, 18));

        errorText = Ui.text(this, "", 13, Ui.DANGER, false);
        errorText.setVisibility(View.GONE);
        Ui.addTop(card, errorText, Ui.dp(this, 10));

        probeText = Ui.text(this, "", 12, Ui.TEXT_DIM, false);
        probeText.setVisibility(View.GONE);
        Ui.addTop(card, probeText, Ui.dp(this, 4));

        Ui.addTop(col, card, Ui.dp(this, 22));

        // --- recent hosts ---
        TextView recentTitle = Ui.text(this, "آدرس‌های اخیر / Recent", 13, Ui.TEXT_DIM, true);
        recentsBox = Ui.column(this);
        Ui.addTop(col, recentTitle, Ui.dp(this, 24));
        Ui.addTop(col, recentsBox, Ui.dp(this, 8));
        rebuildRecents();

        // --- secondary actions ---
        LinearLayout actions = Ui.row(this);
        Button files = Ui.button(this, "فایل‌ها", Ui.SURFACE_2, Ui.TEXT);
        files.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ConnectActivity.this, FilesActivity.class));
            }
        });
        Button settings = Ui.button(this, "تنظیمات", Ui.SURFACE_2, Ui.TEXT);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ConnectActivity.this, SettingsActivity.class));
            }
        });
        Button help = Ui.button(this, "راهنما", Ui.SURFACE_2, Ui.TEXT);
        help.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ConnectActivity.this, HelpActivity.class));
            }
        });
        actions.addView(files, Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(settings, withLeftMargin(Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1),
                Ui.dp(this, 8)));
        actions.addView(help, withLeftMargin(Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1),
                Ui.dp(this, 8)));
        Ui.addTop(col, actions, Ui.dp(this, 26));

        // --- info card ---
        LinearLayout info = Ui.column(this);
        info.setBackground(Ui.roundRect(Ui.SURFACE, 16, this));
        info.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        info.addView(Ui.text(this, "سرور را روی کامپیوتر اجرا کنید:", 12, Ui.TEXT_DIM, false));
        TextView cmd = Ui.text(this, "devviord --token 1234", 13, Ui.ACCENT, false);
        cmd.setTypeface(android.graphics.Typeface.MONOSPACE);
        Ui.addTop(info, cmd, Ui.dp(this, 6));
        Ui.addTop(info, Ui.text(this,
                "آدرس نمایش داده شده را وارد کنید. پیش‌فرض پورت 5900 است.",
                12, Ui.TEXT_DIM, false), Ui.dp(this, 8));
        Ui.addTop(col, info, Ui.dp(this, 18));

        TextView buildInfo = Ui.text(this, "v" + BuildConfig.VERSION_NAME
                        + " · " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
                11, Ui.alpha(Ui.TEXT_DIM, 0.7f), false);
        Ui.addTop(col, buildInfo, Ui.dp(this, 20));

        scroll.addView(col);
        root.addView(scroll, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    /** FrameLayout subclass kept to avoid importing FrameLayout everywhere. */
    private static class FrameLayoutRoot extends android.widget.FrameLayout {
        FrameLayoutRoot(Context c) {
            super(c);
        }
    }

    private void selectMode(Prefs p, String mode, TextView touchBtn, TextView mouseBtn) {
        if (p != null) {
            p.putString(Prefs.K_MODE, mode);
        }
        boolean mouse = Proto.MODE_MOUSE.equals(mode);
        stylePill(touchBtn, !mouse);
        stylePill(mouseBtn, mouse);
    }

    private TextView pill(String label, boolean selected) {
        TextView t = Ui.text(this, label, 13, selected ? 0xFF04140C : Ui.TEXT, true);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        stylePill(t, selected);
        return t;
    }

    private void stylePill(TextView t, boolean selected) {
        t.setBackground(Ui.roundRect(selected ? Ui.ACCENT : Ui.SURFACE_2, 20,
                selected ? Ui.ACCENT : Ui.SURFACE_3, 1, this));
        t.setTextColor(selected ? 0xFF04140C : Ui.TEXT_DIM);
    }

    private void rebuildRecents() {
        recentsBox.removeAllViews();
        String[] recents = prefs.recents();
        if (recents.length == 0) {
            TextView hint = Ui.text(this, "هنوز سروری ذخیره نشده است.", 12, Ui.TEXT_DIM, false);
            recentsBox.addView(hint);
            return;
        }
        for (final String host : recents) {
            LinearLayout row = Ui.row(this);
            TextView t = Ui.text(this, host, 14, Ui.TEXT, false);
            row.addView(t, Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView go = Ui.chip(this, "اتصال", Ui.ACCENT);
            row.addView(go);
            View item = Ui.column(this);
            ((LinearLayout) item).addView(row);
            item.setBackground(Ui.roundRect(Ui.SURFACE, 12, this));
            int p = Ui.dp(this, 12);
            item.setPadding(p, p, p, p);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hostInput.setText(host);
                    doConnect();
                }
            });
            recentsBox.addView(item);
            Ui.margin(item, 0, 0, 0, Ui.dp(this, 8));
        }
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
        if (data != null) {
            String scheme = data.getScheme();
            if ("devviord".equalsIgnoreCase(scheme)) {
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
                // devviord://connect/192.168.1.10:5900 form
                if (host == null && data.getPath() != null) {
                    String p = data.getPath().replace("/", "");
                    if (!p.isEmpty() && !p.equalsIgnoreCase("connect")) {
                        host = p;
                    }
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
     * If the app was launched from a "Clipboard" share, offer to send the text
     * straight to the host clipboard once connected.
     */
    private void checkSharedClipboard() {
        CharSequence txt = getIntent() == null ? null : getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (txt != null && txt.length() > 0) {
            errorText.setVisibility(View.VISIBLE);
            errorText.setTextColor(Ui.BLUE);
            errorText.setText("متن کلیپ‌بورد آماده ارسال است (" + txt.length() + " کاراکتر)");
        }
    }

    private void doConnect() {
        String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            showError("آدرس سرور را وارد کنید.");
            return;
        }
        if (host.contains(" ") || host.contains("/") && !host.startsWith("ws")) {
            showError("آدرس نامعتبر است. مثال: 192.168.1.10:5900");
            return;
        }
        String token = tokenInput.getText().toString().trim();
        prefs.putString(Prefs.K_HOST, host);
        prefs.putString(Prefs.K_TOKEN, token);
        prefs.addRecent(normalizeHost(host));
        hideError();

        Intent i = new Intent(this, RemoteActivity.class);
        i.putExtra("host", normalizeHost(host));
        i.putExtra("token", token);
        i.putExtra("mode", prefs.getString(Prefs.K_MODE, Proto.MODE_TOUCH));
        startActivity(i);
    }

    private void showError(String s) {
        errorText.setVisibility(View.VISIBLE);
        errorText.setTextColor(Ui.DANGER);
        errorText.setText(s);
    }

    private void hideError() {
        errorText.setVisibility(View.GONE);
    }

    /** Accepts "ip", "ip:port", "http://ip:port", "ws://ip:port". */
    static String normalizeHost(String raw) {
        String h = raw.trim();
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

    /** Finds this device's LAN address (shown as a hint for wlan setups). */
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
            // best effort
        }
        return null;
    }

    private LinearLayout.LayoutParams withTopMargin(View v, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        v.setLayoutParams(lp);
        return lp;
    }

    private LinearLayout.LayoutParams withLeftMargin(LinearLayout.LayoutParams lp, int left) {
        lp.leftMargin = left;
        return lp;
    }

    private static Prefs prefsRef;

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        rebuildRecents();
    }
}
