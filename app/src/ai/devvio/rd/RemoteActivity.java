package ai.devvio.rd;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live remote session: video rendering, touch/mouse input, keyboard/IME bridge,
 * clipboard sync and the on-screen control bars.
 */
public class RemoteActivity extends Activity implements WsClient.Listener {

    private static final String TAG = "DevvioRD/Remote";

    // ---- connection ----
    private WsClient ws;
    private String host;
    private String token;
    private Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService timers;
    private ScheduledFuture<?> pingTask;
    private final AtomicBoolean closing = new AtomicBoolean(false);
    // Every connect() gets its own generation. Callbacks from an older socket
    // (a retry closes the previous one) are ignored, otherwise the stale
    // socket's onClosed pops the reconnect dialog right after a retry.
    private int wsGen;

    // ---- state ----
    private String pointerMode = Proto.MODE_TOUCH;
    private int serverW = 1280;
    private int serverH = 800;
    private int quality = 70;
    private int fps = 20;
    private boolean connected;
    private long rttMs = -1;
    private long lastFrameAt;
    private long frames;
    private long bytes;
    private boolean statsVisible;

    // ---- input state ----
    private boolean dragging;
    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private float sensitivity = 1.7f;
    private long lastMoveSent;
    private long lastTapAt;
    private float lastTapX;
    private float lastTapY;
    private boolean twoFinger;
    // Two-finger scrolling keeps its own last position; sharing lastTapX/lastTapY
    // with the double-tap detector made the first scroll jump and could turn a
    // scroll into a stray double click on the host.
    private float scrollLastX = -1;
    private float scrollLastY = -1;
    private float pointerX = 0.5f;
    private boolean ignoredPress;
    private float pointerY = 0.5f;

    // IME bridge
    private ImeBridge ime;
    private String lastNonEmptyText = "";
    private boolean nativeKeyboard;

    // ---- views ----
    private FrameLayout root;
    private RemoteView remoteView;
    private EditText keyInput;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private TextView statusPill;
    private TextView hud;
    private TextView modeChip;
    private View statusDot;
    private TextView statsView;
    private View reconnectOverlay;
    private TextView retryNote;
    private Runnable autoRetry;
    private int retryCount;
    private boolean barsVisible = true;
    private boolean barsPinned;
    private long lastFpsTickAt;
    private long lastTickFrames;
    private double fpsNow;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        host = getIntent().getStringExtra("host");
        token = getIntent().getStringExtra("token");
        pointerMode = getIntent().getStringExtra("mode");
        if (pointerMode == null) {
            pointerMode = prefs.getString(Prefs.K_MODE, Proto.MODE_TOUCH);
        }
        quality = prefs.getInt(Prefs.K_QUALITY, 70);
        fps = prefs.getInt(Prefs.K_FPS, 20);
        nativeKeyboard = prefs.getBool(Prefs.K_NATIVE_KB, true);
        sensitivity = prefs.getFloat("sensitivity", 1.7f);
        statsVisible = prefs.getBool(Prefs.K_STATS, false);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        barsPinned = prefs.getBool(Prefs.K_BARS_PINNED, false);
        applyImmersive();
        buildUi();
        if (host != null && !host.isEmpty()) {
            connect();
        } else {
            finish();
        }
    }

    /**
     * Listener indirection so callbacks from a replaced socket can be dropped.
     * Without it, tapping "reconnect" after a drop immediately re-opened the
     * reconnect overlay: the old socket's close callback arrived a moment
     * later and looked like a fresh failure.
     */
    private final class Conn implements WsClient.Listener {
        private final int gen;

        Conn(int gen) {
            this.gen = gen;
        }

        private boolean stale() {
            return gen != wsGen || closing.get();
        }

        @Override
        public void onOpen() {
            if (!stale()) {
                RemoteActivity.this.onOpen();
            }
        }

        @Override
        public void onMessage(Proto.Message msg) {
            if (!stale()) {
                RemoteActivity.this.onMessage(msg);
            }
        }

        @Override
        public void onClosed(String reason) {
            if (!stale()) {
                RemoteActivity.this.onClosed(reason);
            }
        }

        @Override
        public void onError(Throwable t) {
            if (!stale()) {
                RemoteActivity.this.onError(t);
            }
        }
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        remoteView = new RemoteView(this);
        remoteView.setSession(this);
        root.addView(remoteView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Invisible 1x1 EditText: the bridge between the Android IME and the
        // remote host. Positioning it off-screen avoids visual artifacts while
        // still receiving every IME event (including Persian composition).
        keyInput = new EditText(this);
        keyInput.setBackgroundColor(Color.TRANSPARENT);
        keyInput.setTextColor(Color.TRANSPARENT);
        keyInput.setCursorVisible(false);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        keyInput.setSingleLine(false);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(Ui.dp(this, 2), Ui.dp(this, 2));
        klp.gravity = Gravity.TOP | Gravity.LEFT;
        klp.leftMargin = -Ui.dp(this, 10);
        root.addView(keyInput, klp);
        keyInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (ime != null) {
                    // Incremental form: the engine only touches the edited
                    // region, so long texts and heavy IME input stay cheap.
                    ime.onTextChanged(s == null ? "" : s.toString(), start, before, count);
                }
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
        ime = new ImeBridge(new ImeBridge.Sink() {
            @Override
            public void backspace() {
                if (ws != null && connected) {
                    ws.send(Proto.key("Backspace", "Backspace", "down", false, false, false, false));
                }
            }

            @Override
            public void text(String s) {
                if (ws != null && connected) {
                    ws.send(Proto.text(s));
                }
            }
        });
        keyInput.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                return handleHardwareKey(keyCode, event);
            }
        });

        // --- top bar: connection state, mode, keyboard, overflow ---
        topBar = Ui.row(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackground(Ui.verticalGradient(Ui.alpha(0xFF05080C, 0.88f),
                Ui.alpha(0xFF05080C, 0.45f), 0, this));
        int tp = Ui.dp(this, Ui.SP_2);
        topBar.setPadding(tp, Ui.dp(this, Ui.SP_2), tp, Ui.dp(this, Ui.SP_3));

        TextView back = barButton("‹");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmExit();
            }
        });
        topBar.addView(back);

        // status: dot + label inside a translucent pill
        LinearLayout statusBox = Ui.row(this);
        statusBox.setGravity(Gravity.CENTER_VERTICAL);
        statusBox.setBackground(Ui.roundRect(Ui.alpha(0xFF000000, 0.45f), Ui.R_XL, this));
        int sp = Ui.dp(this, Ui.SP_3);
        statusBox.setPadding(sp, Ui.dp(this, Ui.SP_1 + 2), sp, Ui.dp(this, Ui.SP_1 + 2));
        statusDot = Ui.dot(this, Ui.WARN, 7);
        statusBox.addView(statusDot);
        statusPill = Ui.text(this, "در حال اتصال…", Ui.TS_MICRO + 1, Ui.WARN, true);
        statusPill.setSingleLine(true);
        statusPill.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams stlp = Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        stlp.leftMargin = Ui.dp(this, Ui.SP_2);
        statusBox.addView(statusPill, stlp);
        LinearLayout.LayoutParams sbLp = Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        sbLp.leftMargin = Ui.dp(this, Ui.SP_2);
        sbLp.rightMargin = Ui.dp(this, Ui.SP_2);
        topBar.addView(statusBox, sbLp);

        modeChip = barButton(modeLabel());
        modeChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setPointerMode(Proto.MODE_MOUSE.equals(pointerMode) ? Proto.MODE_TOUCH : Proto.MODE_MOUSE);
            }
        });
        topBar.addView(modeChip);

        TextView kb = barButton("⌨");
        kb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleKeyboard();
            }
        });
        topBar.addView(kb);

        TextView more = barButton("⋮");
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu();
            }
        });
        topBar.addView(more);

        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.TOP;
        root.addView(topBar, tlp);

        // --- bottom bar: the shortcuts people actually press mid-session ---
        bottomBar = Ui.row(this);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackground(Ui.verticalGradient(Ui.alpha(0xFF05080C, 0.45f),
                Ui.alpha(0xFF05080C, 0.9f), 0, this));
        int bp = Ui.dp(this, Ui.SP_2);
        bottomBar.setPadding(bp, Ui.dp(this, Ui.SP_3), bp, Ui.dp(this, Ui.SP_2));
        bottomBar.addView(quickAction("⌨", "کیبورد", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleKeyboard();
            }
        }));
        bottomBar.addView(quickAction("⎘", "کپی→سرور", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                syncClipboardToHost();
            }
        }));
        bottomBar.addView(quickAction("⤓", "آوردن", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteFromHost();
            }
        }));
        bottomBar.addView(quickAction("⇄", "فایل‌ها", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFiles();
            }
        }));
        bottomBar.addView(quickAction("⎋", "Esc", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendSpecialKey("Escape");
            }
        }));
        bottomBar.addView(quickAction("⌫", "Back", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendSpecialKey("Backspace");
            }
        }));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        root.addView(bottomBar, blp);

        // --- HUD toast ---
        hud = Ui.text(this, "", Ui.TS_CAPTION + 1, Ui.TEXT, false);
        hud.setGravity(Gravity.CENTER);
        hud.setBackground(Ui.roundRect(Ui.alpha(0xFF05080C, 0.88f), Ui.R_XL, this));
        hud.setPadding(Ui.dp(this, Ui.SP_4), Ui.dp(this, Ui.SP_2 + 2),
                Ui.dp(this, Ui.SP_4), Ui.dp(this, Ui.SP_2 + 2));
        hud.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        hlp.bottomMargin = Ui.dp(this, 78);
        root.addView(hud, hlp);

        // --- stats overlay ---
        statsView = Ui.text(this, "", Ui.TS_MICRO, Ui.ACCENT, false);
        statsView.setTypeface(android.graphics.Typeface.MONOSPACE);
        statsView.setBackground(Ui.roundRect(Ui.alpha(0xFF05080C, 0.82f), Ui.R_MD,
                Ui.alpha(Ui.ACCENT, 0.35f), 1, this));
        statsView.setPadding(Ui.dp(this, Ui.SP_3), Ui.dp(this, Ui.SP_2),
                Ui.dp(this, Ui.SP_3), Ui.dp(this, Ui.SP_2));
        statsView.setLineSpacing(Ui.dp(this, 2), 1f);
        statsView.setVisibility(View.GONE);
        statsView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                statsVisible = false;
                statsView.setVisibility(View.GONE);
                prefs.putBool(Prefs.K_STATS, false);
            }
        });
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.TOP | Gravity.LEFT;
        slp.topMargin = Ui.dp(this, 64);
        slp.leftMargin = Ui.dp(this, Ui.SP_3);
        root.addView(statsView, slp);

        setContentView(root);
        statsView.setVisibility(statsVisible ? View.VISIBLE : View.GONE);
        updateModeUi();
        applyBarsVisibility(false);
    }

    /** "لمسی" / "موس" - short enough to sit in the toolbar. */
    private String modeLabel() {
        return Proto.MODE_MOUSE.equals(pointerMode) ? "موس" : "لمسی";
    }

    /**
     * Hides or shows the system bars for a truly full-screen session.
     *
     * <p>Kept in one place because it is called from three directions: on
     * create, from the overflow menu, and after the window regains focus (the
     * OS restores the bars itself whenever the keyboard opens or the app is
     * brought back to the front).
     */
    private void applyImmersive() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (barsVisible) {
                    c.show(android.view.WindowInsets.Type.systemBars());
                } else {
                    c.hide(android.view.WindowInsets.Type.systemBars());
                }
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            if (!barsVisible) {
                flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            }
            decor.setSystemUiVisibility(flags);
            if (Build.VERSION.SDK_INT >= 21) {
                getWindow().setStatusBarColor(Ui.alpha(0xFF05080C, barsVisible ? 0.85f : 0f));
                getWindow().setNavigationBarColor(Ui.alpha(0xFF05080C, barsVisible ? 0.85f : 0f));
            }
        }
    }

    /** Shows/hides our own toolbars without touching the system bars. */
    private void applyBarsVisibility(boolean animate) {
        applyImmersive();
        float target = barsVisible ? 1f : 0f;
        if (!animate) {
            topBar.setAlpha(target);
            bottomBar.setAlpha(target);
            topBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
            bottomBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
            return;
        }
        topBar.animate().alpha(target).setDuration(160).start();
        bottomBar.animate().alpha(target).setDuration(160).start();
        if (barsVisible) {
            topBar.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.VISIBLE);
        } else {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!barsVisible) {
                        topBar.setVisibility(View.GONE);
                        bottomBar.setVisibility(View.GONE);
                    }
                }
            }, 170);
        }
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        applyBarsVisibility(true);
        showHud(barsVisible ? "نوارها نمایش داده شدند" : "حالت تمام‌صفحه · برای بازگشت دو ضربه بزنید");
    }

    /** Compact translucent button for the in-session toolbars. */
    private TextView barButton(String label) {
        TextView t = Ui.text(this, label, Ui.TS_LABEL + 2, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(this, 38));
        t.setMinHeight(Ui.dp(this, 34));
        t.setPadding(Ui.dp(this, Ui.SP_2), Ui.dp(this, Ui.SP_1),
                Ui.dp(this, Ui.SP_2), Ui.dp(this, Ui.SP_1));
        t.setBackground(Ui.pressable(Ui.alpha(0xFF131A22, 0.92f), Ui.R_MD,
                Ui.alpha(0xFFFFFFFF, 0.10f), this));
        t.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, Ui.SP_1 + 2);
        t.setLayoutParams(lp);
        return t;
    }

    /**
     * Bottom-bar shortcut: glyph on top, tiny label underneath. The label is
     * what makes the icons learnable - an unlabelled "⎘" means nothing until
     * you have used the app for a week.
     */
    private View quickAction(String glyph, String label, View.OnClickListener l) {
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER);
        box.setBackground(Ui.pressable(Ui.alpha(0xFF131A22, 0.92f), Ui.R_MD,
                Ui.alpha(0xFFFFFFFF, 0.08f), this));
        int v = Ui.dp(this, Ui.SP_1 + 2);
        box.setPadding(0, v, 0, v);
        TextView g = Ui.text(this, glyph, 16, Ui.TEXT, true);
        g.setGravity(Gravity.CENTER);
        box.addView(g);
        TextView t = Ui.text(this, label, 8.5f, Ui.TEXT_DIM, false);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        box.addView(t);
        box.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = Ui.dp(this, 3);
        lp.rightMargin = Ui.dp(this, 3);
        box.setLayoutParams(lp);
        return box;
    }

    private void updateModeUi() {
        boolean mouse = Proto.MODE_MOUSE.equals(pointerMode);
        modeChip.setText(modeLabel());
        modeChip.setTextColor(mouse ? Ui.BLUE : Ui.ACCENT);
        remoteView.setMouseMode(mouse);
    }

    // ------------------------------------------------------------ connection

    private void connect() {
        closeConnection(true);
        closing.set(false);
        setStatus("در حال اتصال…", Ui.WARN);
        final int gen = ++wsGen;
        final WsClient client = new WsClient(new Conn(gen));
        ws = client;
        final String url = wsUrl();
        final String tok = token;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    client.connect(url, tok, 8000);
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (gen == wsGen) {
                                onConnectFailed(t.getMessage());
                            }
                        }
                    });
                }
            }
        }, "devviord-connect").start();
        startTimers();
    }

    private String wsUrl() {
        // One implementation of "host the user typed" -> URL, shared with the
        // other screens (see Hosts). Previously a host without a port worked in
        // the connect screen but not here.
        return Hosts.wsUrl(host, Hosts.DEFAULT_PORT);
    }

    @Override
    public void onOpen() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                setStatus("دست‌دادن…", Ui.WARN);
            }
        });
        String device = Build.MANUFACTURER + " " + Build.MODEL;
        ws.send(Proto.hello("android/" + BuildConfig.VERSION_NAME + " (Android " + Build.VERSION.RELEASE + ")",
                device, true, true, true, true, 0, 0, quality));
    }

    @Override
    public void onMessage(Proto.Message msg) {
        switch (msg.type) {
            case Proto.T_HELLO_OK:
                handleHelloOk(msg);
                break;
            case Proto.T_FRAME:
                bytes += (msg.data.length - msg.offset);
                frames++;
                lastFrameAt = System.currentTimeMillis();
                remoteView.onFrame(msg.data, msg.offset, msg.data.length - msg.offset, msg.width, msg.height);
                break;
            case Proto.T_STATUS:
                try {
                    JSONObject o = msg.json();
                    final String m = o.optString("message");
                    final String code = o.optString("code");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if ("hello".equals(code)) {
                                return;
                            }
                            showHud(m);
                        }
                    });
                } catch (Exception ignored) {
                }
                break;
            case Proto.T_CLIP:
                handleServerClipboard(msg);
                break;
            case Proto.T_CURSOR:
                try {
                    JSONObject o = msg.json();
                    remoteView.serverCursor((float) o.optDouble("x", 0.5),
                            (float) o.optDouble("y", 0.5), o.optBoolean("visible", true));
                } catch (Exception ignored) {
                }
                break;
            case Proto.T_SCREEN:
                try {
                    JSONObject o = msg.json();
                    serverW = o.optInt("width", serverW);
                    serverH = o.optInt("height", serverH);
                    remoteView.setServerSize(serverW, serverH);
                } catch (Exception ignored) {
                }
                break;
            case Proto.T_PONG:
                rttMs = System.currentTimeMillis() - msg.timestamp;
                break;
            case Proto.T_ERROR:
                try {
                    final String m = msg.json().optString("message");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("خطا: " + m, Ui.DANGER);
                            showHud(m);
                        }
                    });
                } catch (Exception ignored) {
                }
                break;
            default:
                break;
        }
    }

    private void handleHelloOk(Proto.Message msg) {
        final JSONObject o;
        try {
            o = msg.json();
        } catch (Exception e) {
            return;
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                connected = true;
                serverW = o.optInt("width", serverW);
                serverH = o.optInt("height", serverH);
                remoteView.setServerSize(serverW, serverH);
                String hostname = o.optString("hostname", "");
                String server = o.optString("server", "DevvioRD");
                setStatus("متصل · " + hostname, Ui.ACCENT);
                hideReconnect();
                retryCount = 0;
                ws.send(Proto.pointerMode(pointerMode));
                ws.send(Proto.quality(quality, fps, prefs.getFloat(Prefs.K_SCALE, 1f)));
                showHud("متصل شد: " + hostname);
                // If launched via clipboard share, push it now.
                CharSequence shared = getIntent() == null ? null
                        : getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (shared != null && shared.length() > 0) {
                    ws.send(Proto.clipboard(shared.toString()));
                }
                Log.i(TAG, "session established with " + server);
            }
        });
    }

    private void handleServerClipboard(Proto.Message msg) {
        try {
            final JSONObject o = msg.json();
            final String data = o.optString("data");
            if (!prefs.getBool(Prefs.K_CLIP_SYNC, true) || data.isEmpty()) {
                return;
            }
            ui.post(new Runnable() {
                @Override
                public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("DevvioRD", data));
                        // Avoid echoing the same text straight back.
                        rememberPushedText(data);
                        showHud("کلیپ‌بورد سرور کپی شد ✓");
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onClosed(final String reason) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                connected = false;
                setStatus("قطع شد", Ui.DANGER);
                if (!closing.get()) {
                    showReconnect(reason);
                }
            }
        });
    }

    @Override
    public void onError(final Throwable t) {
        Log.w(TAG, "ws error", t);
    }

    private void onConnectFailed(String msg) {
        connected = false;
        setStatus("اتصال برقرار نشد", Ui.DANGER);
        showReconnect(msg == null ? "اتصال برقرار نشد" : msg);
    }

    private void showReconnect(String reason) {
        if (reconnectOverlay != null) {
            return;
        }
        // Scrim swallows touches so a stray tap cannot hit the remote desktop.
        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(Ui.alpha(0xFF000000, 0.6f));
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // intentionally inert
            }
        });

        LinearLayout box = Ui.column(this);
        box.setBackground(Ui.roundRect(Ui.SURFACE, Ui.R_XL, Ui.LINE, 1, this));
        int p = Ui.dp(this, Ui.SP_5);
        box.setPadding(p, p, p, p);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView icon = Ui.text(this, "⚡", 30, Ui.DANGER, false);
        icon.setGravity(Gravity.CENTER);
        box.addView(icon);
        TextView t = Ui.text(this, "اتصال قطع شد", Ui.TS_HEADING, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        Ui.addGap(box, t, this, Ui.SP_3);
        if (reason != null && !reason.isEmpty()) {
            TextView r = Ui.text(this, reason, Ui.TS_CAPTION, Ui.TEXT_DIM, false);
            r.setGravity(Gravity.CENTER);
            r.setLineSpacing(Ui.dp(this, 2), 1f);
            Ui.addGap(box, r, this, Ui.SP_2);
        }
        TextView host2 = Ui.mono(this, host == null ? "" : host, Ui.TS_MICRO, Ui.TEXT_FAINT);
        host2.setGravity(Gravity.CENTER);
        Ui.addGap(box, host2, this, Ui.SP_2);

        retryNote = Ui.text(this, "", Ui.TS_MICRO, Ui.TEXT_FAINT, false);
        retryNote.setGravity(Gravity.CENTER);
        Ui.addGap(box, retryNote, this, Ui.SP_3);

        TextView retry = Ui.primaryButton(this, "اتصال مجدد");
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideReconnect();
                connect();
            }
        });
        Ui.addGap(box, retry, this, Ui.SP_2);

        TextView back = Ui.secondaryButton(this, "بازگشت به فهرست سرورها");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        Ui.addGap(box, back, this, Ui.SP_2);

        TextView settings = Ui.text(this, "تنظیمات اتصال", Ui.TS_CAPTION, Ui.ACCENT, true);
        settings.setGravity(Gravity.CENTER);
        int sp = Ui.dp(this, Ui.SP_2);
        settings.setPadding(sp, sp, sp, sp);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(RemoteActivity.this, SettingsActivity.class));
            }
        });
        Ui.addGap(box, settings, this, Ui.SP_2);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        lp.leftMargin = Ui.dp(this, Ui.SP_6);
        lp.rightMargin = Ui.dp(this, Ui.SP_6);
        scrim.addView(box, lp);
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        reconnectOverlay = scrim;
        // Slide in instead of appearing abruptly.
        box.setTranslationY(Ui.dp(this, 16));
        box.setAlpha(0f);
        box.animate().translationY(0).alpha(1f).setDuration(180).start();
        scheduleAutoRetry();
    }

    /**
     * Retries the connection on its own after a drop: a phone that loses Wi-Fi
     * for a second should come back without the user having to do anything.
     * The countdown is visible and the attempt backs off, so a host that is
     * genuinely gone does not get hammered.
     */
    private void scheduleAutoRetry() {
        cancelAutoRetry();
        if (closing.get() || reconnectOverlay == null) {
            return;
        }
        retryCount++;
        // 3s, 6s, 12s ... capped at 30s.
        final int delay = Math.min(30, 3 * (1 << Math.min(3, retryCount - 1)));
        autoRetry = new Runnable() {
            private int left = delay;

            @Override
            public void run() {
                if (reconnectOverlay == null || closing.get()) {
                    return;
                }
                if (retryNote != null) {
                    retryNote.setText(left > 0
                            ? "تلاش خودکار بعدی در " + left + " ثانیه"
                            : "در حال اتصال…");
                }
                if (left <= 0) {
                    autoRetry = null;
                    hideReconnect();
                    connect();
                    return;
                }
                left--;
                ui.postDelayed(this, 1000);
            }
        };
        ui.post(autoRetry);
    }

    private void cancelAutoRetry() {
        if (autoRetry != null) {
            ui.removeCallbacks(autoRetry);
            autoRetry = null;
        }
    }

    private void hideReconnect() {
        cancelAutoRetry();
        retryNote = null;
        if (reconnectOverlay != null) {
            root.removeView(reconnectOverlay);
            reconnectOverlay = null;
        }
    }

    private void setStatus(String s, int color) {
        statusPill.setText(s);
        statusPill.setTextColor(color);
        if (statusDot != null) {
            statusDot.setBackground(Ui.roundRect(color, 12, this));
            statusDot.setAlpha(1f);
            if (color == Ui.WARN || color == Ui.DANGER) {
                // Pulse while connecting / after a drop: at a glance you can
                // see whether the session is healthy or still retrying.
                statusDot.animate().alpha(0.35f).setDuration(650)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                if (statusDot != null) {
                                    statusDot.animate().alpha(1f).setDuration(650).start();
                                }
                            }
                        }).start();
            } else {
                statusDot.animate().cancel();
            }
        }
    }

    private void showHud(String s) {
        hud.setText(s);
        hud.setVisibility(View.VISIBLE);
        hud.setAlpha(1f);
        hud.setTranslationY(0);
        hud.animate().alpha(0f).setStartDelay(1600).setDuration(600)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        hud.setVisibility(View.GONE);
                    }
                }).start();
    }

    // ---------------------------------------------------------------- timers

    private void startTimers() {
        if (timers != null) {
            timers.shutdownNow();
        }
        timers = Executors.newSingleThreadScheduledExecutor();
        pingTask = timers.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (ws != null && ws.isConnected()) {
                    ws.send(Proto.ping(System.currentTimeMillis()));
                }
            }
        }, 2, 5, TimeUnit.SECONDS);
        lastFpsTickAt = System.currentTimeMillis();
        lastTickFrames = frames;
        timers.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        long now = System.currentTimeMillis();
                        long dt = now - lastFpsTickAt;
                        if (dt >= 500) {
                            long delta = frames - lastTickFrames;
                            // Smooth the number: a single slow second should not
                            // make the readout jump around while troubleshooting.
                            double inst = delta * 1000.0 / dt;
                            fpsNow = fpsNow == 0 ? inst : fpsNow * 0.6 + inst * 0.4;
                            lastFpsTickAt = now;
                            lastTickFrames = frames;
                        }
                        if (statsVisible) {
                            updateStats();
                        }
                    }
                });
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void updateStats() {
        long now = System.currentTimeMillis();
        long age = lastFrameAt == 0 ? -1 : now - lastFrameAt;
        String s = "fps        " + String.format("%.1f", fpsNow)
                + "\ngeometry   " + serverW + "x" + serverH
                + "\nframes     " + frames
                + "\nreceived   " + humanBytes(bytes)
                + "\nrtt        " + (rttMs < 0 ? "—" : rttMs + " ms")
                + "\nframe age  " + (age < 0 ? "—" : age + " ms")
                + "\nquality    q" + quality + " · " + fps + " fps"
                + "\nmode       " + (Proto.MODE_MOUSE.equals(pointerMode) ? "mouse" : "touch");
        statsView.setText(s);
    }

    private static String humanBytes(long b) {
        if (b < 1024) {
            return b + " B";
        }
        if (b < 1024 * 1024) {
            return String.format("%.1f KiB", b / 1024.0);
        }
        return String.format("%.1f MiB", b / (1024.0 * 1024));
    }

    // ----------------------------------------------------------------- input

    void onLocalTouch(MotionEvent e) {
        if (!connected || ws == null) {
            return;
        }
        int action = e.getActionMasked();
        // A press in the letterbox margin is not a press on the desktop. Sending
        // it anyway (clamped to the nearest edge) clicked whatever happened to be
        // at that edge of the remote screen.
        if (action == MotionEvent.ACTION_DOWN && remoteView.isOutsideImage(e.getX(), e.getY())) {
            ignoredPress = true;
            return;
        }
        if (ignoredPress) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                ignoredPress = false;
            }
            return;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                twoFinger = false;
                downX = e.getX();
                downY = e.getY();
                lastX = downX;
                lastY = downY;
                dragging = false;
                if (Proto.MODE_TOUCH.equals(pointerMode)) {
                    sendTouch(Proto.TOUCH_DOWN, normX(downX), normY(downY), 1f);
                } else {
                    sendMouseMove(0, 0);
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() >= 2) {
                    twoFinger = true;
                    dragging = false;
                    scrollLastX = -1;
                    scrollLastY = -1;
                    if (Proto.MODE_TOUCH.equals(pointerMode)) {
                        sendTouch(Proto.TOUCH_CANCEL, normX(lastX), normY(lastY), 0f);
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (twoFinger) {
                    handleTwoFingerScroll(e);
                    break;
                }
                if (Proto.MODE_TOUCH.equals(pointerMode)) {
                    long now = System.currentTimeMillis();
                    if (now - lastMoveSent >= 12) {
                        lastMoveSent = now;
                        sendTouch(Proto.TOUCH_MOVE, normX(e.getX()), normY(e.getY()), 1f);
                    }
                } else {
                    sendMouseMove(e.getX() - lastX, e.getY() - lastY);
                    lastX = e.getX();
                    lastY = e.getY();
                }
                if (!dragging && Math.hypot(e.getX() - downX, e.getY() - downY) > Ui.dp(this, 12)) {
                    dragging = true;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (twoFinger) {
                    // A scroll gesture is not a tap: forget any pending
                    // double-tap candidate so the next tap starts clean.
                    twoFinger = false;
                    scrollLastX = -1;
                    scrollLastY = -1;
                    lastTapAt = 0;
                    break;
                }
                if (Proto.MODE_TOUCH.equals(pointerMode)) {
                    sendTouch(Proto.TOUCH_UP, normX(e.getX()), normY(e.getY()), 0f);
                } else if (!dragging) {
                    // Tap in mouse mode = left click at the cursor.
                    sendTouch(Proto.TOUCH_DOWN, pointerX, pointerY, 1f);
                    sendTouch(Proto.TOUCH_UP, pointerX, pointerY, 0f);
                    vibrate();
                }
                maybeDoubleTap(e.getX(), e.getY());
                dragging = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                if (Proto.MODE_TOUCH.equals(pointerMode)) {
                    sendTouch(Proto.TOUCH_CANCEL, normX(e.getX()), normY(e.getY()), 0f);
                }
                dragging = false;
                twoFinger = false;
                break;
            default:
                break;
        }
    }

    private void handleTwoFingerScroll(MotionEvent e) {
        if (e.getPointerCount() < 2 || ws == null || !connected) {
            return;
        }
        float avgX = (e.getX(0) + e.getX(1)) / 2f;
        float avgY = (e.getY(0) + e.getY(1)) / 2f;
        if (scrollLastX < 0) {
            scrollLastX = avgX;
            scrollLastY = avgY;
            return;
        }
        float dx = avgX - scrollLastX;
        float dy = avgY - scrollLastY;
        scrollLastX = avgX;
        scrollLastY = avgY;
        if (Math.abs(dx) < 1.5f && Math.abs(dy) < 1.5f) {
            return;
        }
        // Send the delta as-is: the threshold above already filters jitter, and
        // a dead "accumulator" here used to be incremented and never read.
        float scale = Ui.dp(this, 480);
        ws.send(Proto.scroll(dx / scale, dy / scale));
    }

    private void maybeDoubleTap(float x, float y) {
        long now = System.currentTimeMillis();
        if (now - lastTapAt < 320 && Math.hypot(x - lastTapX, y - lastTapY) < Ui.dp(this, 40)) {
            if (Proto.MODE_TOUCH.equals(pointerMode)) {
                sendTouch(Proto.TOUCH_DOWN, normX(x), normY(y), 1f);
                sendTouch(Proto.TOUCH_UP, normX(x), normY(y), 0f);
                // A double tap near the top or bottom edge brings the toolbars
                // back (and puts them away again): with the bars hidden there
                // would otherwise be no way in or out of full screen.
                if (!barsPinned) {
                    float h = remoteView.getHeight();
                    if (y < Ui.dp(this, 48) || y > h - Ui.dp(this, 48)) {
                        toggleBars();
                    }
                }
            } else {
                // Double tap in mouse mode = double click.
                sendTouch(Proto.TOUCH_DOWN, pointerX, pointerY, 1f);
                sendTouch(Proto.TOUCH_UP, pointerX, pointerY, 0f);
                sendTouch(Proto.TOUCH_DOWN, pointerX, pointerY, 1f);
                sendTouch(Proto.TOUCH_UP, pointerX, pointerY, 0f);
            }
            lastTapAt = 0;
            return;
        }
        lastTapAt = now;
        lastTapX = x;
        lastTapY = y;
    }

    private void sendMouseMove(float dx, float dy) {
        if (dx == 0 && dy == 0) {
            return;
        }
        remoteView.moveLocalCursor(dx * sensitivity, dy * sensitivity);
        pointerX = remoteView.cursorX();
        pointerY = remoteView.cursorY();
        ws.send(Proto.touch(Proto.TOUCH_MOVE, 0, pointerX, pointerY, 1f,
                (int) (System.currentTimeMillis() & 0xFFFFFFFFL)));
    }

    private void sendTouch(int action, float nx, float ny, float pressure) {
        if (ws == null || !ws.isConnected()) {
            return;
        }
        ws.send(Proto.touch(action, 0, clamp01(nx), clamp01(ny), pressure,
                (int) (System.currentTimeMillis() & 0xFFFFFFFFL)));
    }

    private float normX(float x) {
        return remoteView.toRemote(x, 0)[0];
    }

    private float normY(float y) {
        return remoteView.toRemote(0, y)[1];
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(12);
            }
        } catch (Throwable ignored) {
        }
    }

    // -------------------------------------------------------------- keyboard

    void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) {
            return;
        }
        if (imm.isAcceptingText() && keyInput.hasFocus()) {
            imm.hideSoftInputFromWindow(keyInput.getWindowToken(), 0);
            keyInput.clearFocus();
        } else {
            if (ime != null) {
                ime.resetField();
            }
            keyInput.setText("");
            keyInput.requestFocus();
            imm.showSoftInput(keyInput, InputMethodManager.SHOW_IMPLICIT);
            showHud("صفحه‌کلید آماده است — تایپ به سرور ارسال می‌شود");
        }
    }

    /** Keeps the clipboard mirror in sync when we push text ourselves. */
    private void rememberPushedText(String text) {
        lastNonEmptyText = text;
        if (ime != null) {
            ime.syncSilently(text);
        }
    }

    /** Handles hardware/Bluetooth keyboards. */
    boolean handleHardwareKey(int keyCode, KeyEvent event) {
        if (!connected || ws == null) {
            return false;
        }
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean ctrl = event.isCtrlPressed();
        boolean alt = event.isAltPressed();
        boolean shift = event.isShiftPressed();
        boolean meta = event.isMetaPressed();
        String key = null;
        String code = null;
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
                key = "Enter";
                code = "Enter";
                break;
            case KeyEvent.KEYCODE_DEL:
                key = "Backspace";
                code = "Backspace";
                break;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                key = "Delete";
                code = "Delete";
                break;
            case KeyEvent.KEYCODE_TAB:
                key = "Tab";
                code = "Tab";
                break;
            case KeyEvent.KEYCODE_ESCAPE:
                key = "Escape";
                code = "Escape";
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                key = "ArrowUp";
                code = "ArrowUp";
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                key = "ArrowDown";
                code = "ArrowDown";
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                key = "ArrowLeft";
                code = "ArrowLeft";
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                key = "ArrowRight";
                code = "ArrowRight";
                break;
            case KeyEvent.KEYCODE_MOVE_HOME:
                key = "Home";
                code = "Home";
                break;
            case KeyEvent.KEYCODE_MOVE_END:
                key = "End";
                code = "End";
                break;
            case KeyEvent.KEYCODE_PAGE_UP:
                key = "PageUp";
                code = "PageUp";
                break;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                key = "PageDown";
                code = "PageDown";
                break;
            case KeyEvent.KEYCODE_SPACE:
                key = " ";
                code = "Space";
                break;
            default:
                if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
                    char c = (char) ('a' + (keyCode - KeyEvent.KEYCODE_A));
                    key = shift ? Character.toString(Character.toUpperCase(c)) : String.valueOf(c);
                    code = "Key" + Character.toUpperCase(c);
                } else if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                    key = String.valueOf((char) ('0' + (keyCode - KeyEvent.KEYCODE_0)));
                    code = "Digit" + key;
                } else if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
                    int n = keyCode - KeyEvent.KEYCODE_F1 + 1;
                    key = "F" + n;
                    code = key;
                }
                break;
        }
        if (key == null) {
            return false;
        }
        // Printable characters come from the IME text watcher, which already
        // emits them; sending a key event too would double them.
        if (key.length() == 1 && !ctrl && !alt && !meta) {
            return true;
        }
        ws.send(Proto.key(key, code, down ? "down" : "up", ctrl, alt, shift, meta));
        return true;
    }

    private void sendSpecialKey(String key) {
        if (ws != null && connected) {
            ws.send(Proto.key(key, key, "down", false, false, false, false));
        }
    }

    // ------------------------------------------------------------- clipboard

    void syncClipboardToHost() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        CharSequence text = cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                && cm.getPrimaryClip().getItemCount() > 0
                ? cm.getPrimaryClip().getItemAt(0).coerceToText(this) : "";
        if (text == null || text.length() == 0) {
            showHud("کلیپ‌بورد گوشی خالی است");
            return;
        }
        ws.send(Proto.clipboard(text.toString()));
        rememberPushedText(text.toString());
        showHud("متن کلیپ‌بورد به سرور ارسال شد ✓ (" + text.length() + ")");
    }

    private void pasteFromHost() {
        if (ws == null || !connected) {
            return;
        }
        // Ask the server through the REST endpoint: it is not part of the live
        // stream so this works even while frames are flowing.
        final String base = restBase();
        showHud("دریافت کلیپ‌بورد سرور…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text = Http.text(base + "/api/clipboard", token);
                try {
                    if (text != null) {
                        String v = new JSONObject(text).optString("text");
                        if (!v.isEmpty()) {
                            final String fv = v;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    ClipboardManager cm =
                                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                    if (cm != null) {
                                        cm.setPrimaryClip(ClipData.newPlainText("DevvioRD", fv));
                                        rememberPushedText(fv);
                                    }
                                    showHud("کلیپ‌بورد سرور روی گوشی کپی شد ✓");
                                }
                            });
                            return;
                        }
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showHud("کلیپ‌بورد سرور خالی است");
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showHud("خطا در دریافت کلیپ‌بورد");
                        }
                    });
                }
            }
        }).start();
    }

    private String restBase() {
        return Hosts.httpBase(host, Hosts.DEFAULT_PORT);
    }

    // ---------------------------------------------------------------- menu

    private void showMenu() {
        final boolean mouse = Proto.MODE_MOUSE.equals(pointerMode);
        final String[] items = {
                mouse ? "حالت لمسی (لمس مستقیم)" : "حالت موس (تاچ‌پد)",
                "کیفیت تصویر…",
                statsVisible ? "پنهان کردن آمار" : "نمایش آمار (fps / تأخیر)",
                barsVisible ? "تمام‌صفحه (پنهان کردن نوارها)" : "نمایش نوارها",
                "فرستادن کلیپ‌بورد گوشی به سرور",
                "آوردن کلیپ‌بورد سرور به گوشی",
                "ارسال Ctrl",
                "ارسال Alt+Tab",
                "ارسال Ctrl+Alt+Del",
                "انتقال فایل",
                "تنظیمات",
                "راهنما",
                "قطع اتصال",
        };
        new AlertDialog.Builder(this)
                .setTitle("DevvioRD")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                setPointerMode(mouse ? Proto.MODE_TOUCH : Proto.MODE_MOUSE);
                                break;
                            case 1:
                                showQualityDialog();
                                break;
                            case 2:
                                statsVisible = !statsVisible;
                                prefs.putBool(Prefs.K_STATS, statsVisible);
                                statsView.setVisibility(statsVisible ? View.VISIBLE : View.GONE);
                                if (statsVisible) {
                                    updateStats();
                                    showHud("آمار روشن شد · روی کادر بزنید تا بسته شود");
                                }
                                break;
                            case 3:
                                toggleBars();
                                break;
                            case 4:
                                syncClipboardToHost();
                                break;
                            case 5:
                                pasteFromHost();
                                break;
                            case 6:
                                sendChord("Control", "ControlLeft");
                                break;
                            case 7:
                                sendAltTab();
                                break;
                            case 8:
                                sendCtrlAltDel();
                                break;
                            case 9:
                                openFiles();
                                break;
                            case 10:
                                startActivity(new Intent(RemoteActivity.this, SettingsActivity.class));
                                break;
                            case 11:
                                startActivity(new Intent(RemoteActivity.this, HelpActivity.class));
                                break;
                            default:
                                confirmExit();
                                break;
                        }
                    }
                })
                .show();
    }

    private void sendChord(String key, String code) {
        if (ws == null || !connected) {
            return;
        }
        ws.send(Proto.key(key, code, "down", false, false, false, false));
        showHud(key + " ارسال شد");
    }

    private void sendAltTab() {
        if (ws == null || !connected) {
            return;
        }
        ws.send(Proto.key("Alt", "AltLeft", "down", false, true, false, false));
        ws.send(Proto.key("Tab", "Tab", "down", false, true, false, false));
        ws.send(Proto.key("Tab", "Tab", "up", false, true, false, false));
        ws.send(Proto.key("Alt", "AltLeft", "up", false, false, false, false));
        showHud("Alt+Tab ارسال شد");
    }

    private void sendCtrlAltDel() {
        if (ws == null || !connected) {
            return;
        }
        ws.send(Proto.key("Control", "ControlLeft", "down", true, false, false, false));
        ws.send(Proto.key("Alt", "AltLeft", "down", true, true, false, false));
        ws.send(Proto.key("Delete", "Delete", "down", true, true, false, false));
        ws.send(Proto.key("Delete", "Delete", "up", true, true, false, false));
        ws.send(Proto.key("Alt", "AltLeft", "up", true, false, false, false));
        ws.send(Proto.key("Control", "ControlLeft", "up", false, false, false, false));
        showHud("Ctrl+Alt+Del ارسال شد");
    }

    private void showQualityDialog() {
        final String[] labels = {
                "عالی — q85 · 30 fps · 100%",
                "خوب — q70 · 20 fps · 100%",
                "متوسط — q55 · 15 fps · 80%",
                "کم‌مصرف — q40 · 10 fps · 65%",
        };
        final int[] q = {85, 70, 55, 40};
        final int[] f = {30, 20, 15, 10};
        final float[] sc = {1f, 1f, 0.8f, 0.65f};
        int current = 1;
        for (int i = 0; i < q.length; i++) {
            if (quality == q[i] && fps == f[i]) {
                current = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("کیفیت تصویر")
                .setSingleChoiceItems(labels, current,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                quality = q[which];
                                fps = f[which];
                                prefs.putInt(Prefs.K_QUALITY, quality);
                                prefs.putInt(Prefs.K_FPS, fps);
                                prefs.putFloat(Prefs.K_SCALE, sc[which]);
                                if (ws != null && ws.isConnected()) {
                                    ws.send(Proto.quality(quality, fps, sc[which]));
                                }
                                showHud(labels[which]);
                                d.dismiss();
                            }
                        })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void setPointerMode(String mode) {
        pointerMode = mode;
        prefs.putString(Prefs.K_MODE, mode);
        updateModeUi();
        if (ws != null && ws.isConnected()) {
            ws.send(Proto.pointerMode(mode));
        }
        showHud(Proto.MODE_MOUSE.equals(mode)
                ? "حالت موس: کشیدن = حرکت، ضربه = کلیک"
                : "حالت لمسی: لمس مستقیم صفحه");
    }

    private void openFiles() {
        Intent i = new Intent(this, FilesActivity.class);
        i.putExtra("host", host);
        i.putExtra("token", token);
        startActivity(i);
    }

    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle("قطع اتصال؟")
                .setPositiveButton("بله", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        finish();
                    }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from the keyboard or another activity: the system restores
        // the bars, so re-assert whatever the user had chosen.
        applyImmersive();
        nativeKeyboard = prefs.getBool(Prefs.K_NATIVE_KB, true);
        sensitivity = prefs.getFloat("sensitivity", 1.7f);
        quality = prefs.getInt(Prefs.K_QUALITY, quality);
        fps = prefs.getInt(Prefs.K_FPS, fps);
        if (ws != null && ws.isConnected()) {
            ws.send(Proto.quality(quality, fps, prefs.getFloat(Prefs.K_SCALE, 1f)));
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        remoteView.requestLayout();
        applyImmersive();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (keyInput.hasFocus()) {
                keyInput.clearFocus();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        closing.set(true);
        cancelAutoRetry();
        closeConnection(false);
        releaseFrame();
        super.onDestroy();
    }

    /** Drops the last decoded frame so the bitmap can be collected. */
    private void releaseFrame() {
        if (remoteView != null) {
            remoteView.clearFrame();
        }
    }

    private void closeConnection(boolean notify) {
        wsGen++;
        if (timers != null) {
            timers.shutdownNow();
            timers = null;
        }
        if (ws != null) {
            ws.close("activity closed");
            ws = null;
        }
        connected = false;
    }
}
