package ai.devvio.rd;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
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
import android.widget.Toast;

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
    private float scrollAccum;
    private float pointerX = 0.5f;
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
    private TextView statsView;
    private View reconnectOverlay;
    private boolean barsVisible = true;

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
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Ui.alpha(Color.BLACK, 0.6f));
            getWindow().setNavigationBarColor(Ui.alpha(Color.BLACK, 0.6f));
        }
        buildUi();
        if (host != null && !host.isEmpty()) {
            connect();
        } else {
            finish();
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

        // --- top bar ---
        topBar = Ui.row(this);
        topBar.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        topBar.setBackgroundColor(Ui.alpha(0xFF000000, 0.72f));

        TextView back = barButton("‹");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmExit();
            }
        });
        topBar.addView(back);

        statusPill = Ui.text(this, "در حال اتصال…", 12, Ui.WARN, true);
        statusPill.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        statusPill.setBackground(Ui.roundRect(Ui.alpha(0xFF000000, 0.4f), 12, this));
        topBar.addView(statusPill, Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView modeBtn = barButton("TOUCH");
        modeChip = modeBtn;
        modeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setPointerMode(Proto.MODE_MOUSE.equals(pointerMode) ? Proto.MODE_TOUCH : Proto.MODE_MOUSE);
            }
        });
        topBar.addView(modeBtn);

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

        // --- bottom bar (quick actions) ---
        bottomBar = Ui.row(this);
        bottomBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        bottomBar.setBackgroundColor(Ui.alpha(0xFF000000, 0.72f));
        bottomBar.addView(quickAction("⌨", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleKeyboard();
            }
        }));
        bottomBar.addView(quickAction("⎘", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                syncClipboardToHost();
            }
        }));
        bottomBar.addView(quickAction("⤓", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteFromHost();
            }
        }));
        bottomBar.addView(quickAction("⇄", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFiles();
            }
        }));
        bottomBar.addView(quickAction("⎋", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendSpecialKey("Escape");
            }
        }));
        bottomBar.addView(quickAction("⏎", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendSpecialKey("Enter");
            }
        }));
        bottomBar.addView(quickAction("⌫", new View.OnClickListener() {
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
        hud = Ui.text(this, "", 12, Ui.TEXT, false);
        hud.setBackground(Ui.roundRect(Ui.alpha(0xFF000000, 0.8f), 10, this));
        hud.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        hud.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.gravity = Gravity.CENTER;
        root.addView(hud, hlp);

        // --- stats overlay ---
        statsView = Ui.text(this, "", 11, Ui.ACCENT, false);
        statsView.setTypeface(android.graphics.Typeface.MONOSPACE);
        statsView.setBackground(Ui.roundRect(Ui.alpha(0xFF000000, 0.7f), 8, this));
        statsView.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        statsView.setVisibility(View.GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.TOP | Gravity.LEFT;
        slp.topMargin = Ui.dp(this, 60);
        slp.leftMargin = Ui.dp(this, 10);
        root.addView(statsView, slp);

        setContentView(root);
        statsView.setVisibility(statsVisible ? View.VISIBLE : View.GONE);
        updateModeUi();
    }

    private TextView barButton(String label) {
        TextView t = Ui.text(this, label, 15, Ui.TEXT, true);
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        t.setBackground(Ui.roundRect(Ui.SURFACE_2, 10, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 6);
        t.setLayoutParams(lp);
        return t;
    }

    private View quickAction(String label, View.OnClickListener l) {
        TextView t = Ui.text(this, label, 18, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.roundRect(Ui.SURFACE_2, 10, this));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1);
        lp.leftMargin = Ui.dp(this, 4);
        lp.rightMargin = Ui.dp(this, 4);
        t.setLayoutParams(lp);
        return t;
    }

    private void updateModeUi() {
        boolean mouse = Proto.MODE_MOUSE.equals(pointerMode);
        modeChip.setText(mouse ? "MOUSE" : "TOUCH");
        modeChip.setTextColor(mouse ? Ui.BLUE : Ui.ACCENT);
        remoteView.setMouseMode(mouse);
    }

    // ------------------------------------------------------------ connection

    private void connect() {
        closeConnection(true);
        closing.set(false);
        setStatus("در حال اتصال…", Ui.WARN);
        ws = new WsClient(this);
        final String url = wsUrl();
        final String tok = token;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ws.connect(url, tok, 8000);
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            onConnectFailed(t.getMessage());
                        }
                    });
                }
            }
        }, "devviord-connect").start();
        startTimers();
    }

    private String wsUrl() {
        if (host.startsWith("ws://") || host.startsWith("wss://")) {
            String h = host;
            if (!h.endsWith("/ws")) {
                h = h.replaceAll("/+$", "") + "/ws";
            }
            return h;
        }
        return host;
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
        LinearLayout box = Ui.column(this);
        box.setBackground(Ui.roundRect(Ui.alpha(0xFF10161D, 0.97f), 18, this));
        int p = Ui.dp(this, 22);
        box.setPadding(p, p, p, p);
        box.addView(Ui.text(this, "اتصال قطع شد", 18, Ui.TEXT, true));
        if (reason != null && !reason.isEmpty()) {
            TextView r = Ui.text(this, reason, 12, Ui.TEXT_DIM, false);
            box.addView(r);
        }
        Button2 retry = new Button2(this, "اتصال مجدد", Ui.ACCENT, 0xFF04140C);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideReconnect();
                connect();
            }
        });
        Button2 back = new Button2(this, "بازگشت", Ui.SURFACE_3, Ui.TEXT);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        box.addView(retry, withTop(16));
        box.addView(back, withTop(10));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        lp.leftMargin = Ui.dp(this, 28);
        lp.rightMargin = Ui.dp(this, 28);
        root.addView(box, lp);
        reconnectOverlay = box;
    }

    private void hideReconnect() {
        if (reconnectOverlay != null) {
            root.removeView(reconnectOverlay);
            reconnectOverlay = null;
        }
    }

    private LinearLayout.LayoutParams withTop(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, topDp);
        return lp;
    }

    /** Small helper button view for overlays. */
    private static class Button2 extends TextView {
        Button2(Context c, String label, int bg, int fg) {
            super(c);
            setText(label);
            setTextColor(fg);
            setTextSize(15);
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            setGravity(Gravity.CENTER);
            setPadding(Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14));
            setBackground(Ui.roundRect(bg, 14, c));
        }
    }

    private void setStatus(String s, int color) {
        statusPill.setText(s);
        statusPill.setTextColor(color);
    }

    private void showHud(String s) {
        hud.setText(s);
        hud.setVisibility(View.VISIBLE);
        hud.setAlpha(1f);
        hud.animate().alpha(0f).setStartDelay(1400).setDuration(600)
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
        timers.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
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
        String s = "fps " + remoteView.measuredFps()
                + "\n" + serverW + "x" + serverH
                + "\nrx " + (bytes / 1024) + " KiB"
                + "\nrtt " + (rttMs < 0 ? "?" : rttMs + " ms")
                + "\nframe age " + (age < 0 ? "?" : age + " ms")
                + "\nmode " + pointerMode;
        statsView.setText(s);
    }

    // ----------------------------------------------------------------- input

    void onLocalTouch(MotionEvent e) {
        if (!connected || ws == null) {
            return;
        }
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                twoFinger = false;
                downX = e.getX();
                downY = e.getY();
                lastX = downX;
                lastY = downY;
                dragging = false;
                scrollAccum = 0;
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
                    twoFinger = false;
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
        if (e.getPointerCount() < 2) {
            return;
        }
        float y0 = e.getY(0);
        float y1 = e.getY(1);
        float x0 = e.getX(0);
        float x1 = e.getX(1);
        float avgY = (y0 + y1) / 2f;
        float avgX = (x0 + x1) / 2f;
        if (lastTapAt == 0) {
            lastTapAt = System.currentTimeMillis();
            lastTapX = avgX;
            lastTapY = avgY;
            return;
        }
        float dy = avgY - lastTapY;
        float dx = avgX - lastTapX;
        lastTapX = avgX;
        lastTapY = avgY;
        if (Math.abs(dy) > 1 || Math.abs(dx) > 1) {
            float scale = Ui.dp(this, 520);
            ws.send(Proto.scroll(dx / scale, dy / scale));
        }
    }

    private void maybeDoubleTap(float x, float y) {
        long now = System.currentTimeMillis();
        if (now - lastTapAt < 320 && Math.hypot(x - lastTapX, y - lastTapY) < Ui.dp(this, 40)) {
            if (Proto.MODE_TOUCH.equals(pointerMode)) {
                sendTouch(Proto.TOUCH_DOWN, normX(x), normY(y), 1f);
                sendTouch(Proto.TOUCH_UP, normX(x), normY(y), 0f);
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
        return clamp01(x / Math.max(1, remoteView.getWidth()));
    }

    private float normY(float y) {
        return clamp01(y / Math.max(1, remoteView.getHeight()));
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
        String base = host;
        if (base.startsWith("ws://")) {
            base = "http://" + base.substring(5);
        } else if (base.startsWith("wss://")) {
            base = "https://" + base.substring(6);
        }
        base = base.replaceAll("/ws$", "");
        return base;
    }

    // ---------------------------------------------------------------- menu

    private void showMenu() {
        final String[] items = {
                (Proto.MODE_MOUSE.equals(pointerMode) ? "تغییر به حالت لمسی" : "تغییر به حالت موس"),
                "کیفیت تصویر…",
                "آمار / Stats",
                "منتقل کردن کلیپ‌بورد گوشی → سرور",
                "کپی کلیپ‌بورد سرور → گوشی",
                "ارسال Ctrl (برای ترکیب‌ها)",
                "ارسال Alt+Tab",
                "فایل‌ها / File transfer",
                "تنظیمات",
                "بستن اتصال",
        };
        new AlertDialog.Builder(this)
                .setTitle("DevvioRD")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                setPointerMode(Proto.MODE_MOUSE.equals(pointerMode)
                                        ? Proto.MODE_TOUCH : Proto.MODE_MOUSE);
                                break;
                            case 1:
                                showQualityDialog();
                                break;
                            case 2:
                                statsVisible = !statsVisible;
                                statsView.setVisibility(statsVisible ? View.VISIBLE : View.GONE);
                                if (statsVisible) {
                                    updateStats();
                                }
                                break;
                            case 3:
                                syncClipboardToHost();
                                break;
                            case 4:
                                pasteFromHost();
                                break;
                            case 5:
                                ws.send(Proto.key("Control", "ControlLeft", "down",
                                        false, false, false, false));
                                showHud("Ctrl ارسال شد");
                                break;
                            case 6:
                                ws.send(Proto.key("Alt", "AltLeft", "down", false, false, false, false));
                                ws.send(Proto.key("Tab", "Tab", "down", true, true, false, false));
                                showHud("Alt+Tab ارسال شد");
                                break;
                            case 7:
                                openFiles();
                                break;
                            case 8:
                                startActivity(new Intent(RemoteActivity.this, SettingsActivity.class));
                                break;
                            default:
                                confirmExit();
                                break;
                        }
                    }
                })
                .show();
    }

    private void showQualityDialog() {
        final String[] labels = {"عالی (q85 · 30fps)", "خوب (q70 · 20fps)", "متوسط (q55 · 15fps)",
                "کم‌مصرف (q40 · 10fps)"};
        final int[] q = {85, 70, 55, 40};
        final int[] f = {30, 20, 15, 10};
        new AlertDialog.Builder(this)
                .setTitle("کیفیت تصویر")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        quality = q[which];
                        fps = f[which];
                        prefs.putInt(Prefs.K_QUALITY, quality);
                        prefs.putInt(Prefs.K_FPS, fps);
                        if (ws != null) {
                            ws.send(Proto.quality(quality, fps, prefs.getFloat(Prefs.K_SCALE, 1f)));
                        }
                        showHud(labels[which]);
                    }
                })
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
