package ai.devvio.rd;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Two-way file transfer.
 *
 * <p>Server side: the directory exposed by {@code devviord --files}. Phone side:
 * files picked with the system picker are uploaded in 1 MiB chunks (resumable),
 * and downloads land in {@code Downloads/DevvioRD}.
 *
 * <p>The screen is built from real rows rather than a plain list adapter so each
 * entry can show an icon, a two-line name/size block and a per-row action menu.
 * Folders sort first, then files, both alphabetically - the same order every
 * file manager uses.
 */
public class FilesActivity extends Activity {

    private static final int REQ_PICK = 1001;

    private String host;
    private String token;
    private String base;
    private Prefs prefs;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<JSONObject> entries = new ArrayList<>();
    private LinearLayout listBox;
    private ScrollView listScroll;
    private TextView pathLabel;
    private TextView statusLabel;
    private TextView countLabel;
    private LinearLayout emptyBox;
    private String cwd = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        host = getIntent().getStringExtra("host");
        token = getIntent().getStringExtra("token");
        if (host == null) {
            host = prefs.getString(Prefs.K_HOST, "");
            token = prefs.getString(Prefs.K_TOKEN, "");
        }
        base = restBase(host);
        cwd = prefs.getString(Prefs.K_LAST_DIR, "");
        setContentView(buildUi());
        if (cwd == null || cwd.isEmpty()) {
            probeSharedDir();
        } else {
            refresh();
        }
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);

        addHeader(root);
        addBreadcrumb(root);
        addList(root);
        addFooter(root);
        return root;
    }

    private void addHeader(LinearLayout root) {
        LinearLayout header = Ui.row(this);
        header.setBackgroundColor(Ui.BG_ELEV);
        int h = Ui.dp(this, Ui.SP_3);
        header.setPadding(h, Ui.dp(this, Ui.SP_3), h, Ui.dp(this, Ui.SP_3));

        TextView back = Ui.toolButton(this, "‹", 20);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cwd != null && !cwd.isEmpty()) {
                    goUp();
                } else {
                    finish();
                }
            }
        });
        header.addView(back);

        LinearLayout titles = Ui.column(this);
        titles.addView(Ui.text(this, "انتقال فایل", Ui.TS_HEADING, Ui.TEXT, true));
        countLabel = Ui.text(this, "در حال خواندن…", Ui.TS_MICRO, Ui.TEXT_FAINT, false);
        titles.addView(countLabel);
        header.addView(titles, withLeft(Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1),
                Ui.dp(this, Ui.SP_3)));

        TextView refreshBtn = Ui.toolButton(this, "↻", 17);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });
        header.addView(refreshBtn);
        root.addView(header);
    }

    private void addBreadcrumb(LinearLayout root) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(Ui.BG_ELEV);
        pathLabel = Ui.text(this, "/", Ui.TS_CAPTION, Ui.TEXT_DIM, false);
        pathLabel.setTypeface(Typeface.MONOSPACE);
        int p = Ui.dp(this, Ui.SP_3);
        pathLabel.setPadding(p, 0, p, Ui.dp(this, Ui.SP_2));
        hs.addView(pathLabel);
        root.addView(hs);
    }

    private void addList(LinearLayout root) {
        FrameLayout holder = new FrameLayout(this);

        listScroll = new ScrollView(this);
        listScroll.setBackgroundColor(Ui.BG);
        listScroll.setVerticalScrollBarEnabled(false);
        listBox = Ui.column(this);
        int p = Ui.dp(this, Ui.SP_2);
        listBox.setPadding(p, p, p, Ui.dp(this, Ui.SP_2));
        listScroll.addView(listBox);
        holder.addView(listScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Empty / error state, on top of the list.
        emptyBox = Ui.column(this);
        emptyBox.setGravity(Gravity.CENTER);
        emptyBox.setVisibility(View.GONE);
        int ep = Ui.dp(this, Ui.SP_6);
        emptyBox.setPadding(ep, ep, ep, ep);
        holder.addView(emptyBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(holder, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private void addFooter(LinearLayout root) {
        statusLabel = Ui.text(this, "", Ui.TS_MICRO, Ui.TEXT_FAINT, false);
        statusLabel.setPadding(Ui.dp(this, Ui.SP_4), Ui.dp(this, Ui.SP_2),
                Ui.dp(this, Ui.SP_4), 0);
        root.addView(statusLabel);

        LinearLayout actions = Ui.row(this);
        int p = Ui.dp(this, Ui.SP_3);
        actions.setPadding(p, Ui.dp(this, Ui.SP_2), p, Ui.dp(this, Ui.SP_4));
        actions.setBackgroundColor(Ui.BG);

        TextView up = Ui.primaryButton(this, "⬆  آپلود به سرور");
        up.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        TextView mk = Ui.secondaryButton(this, "＋  پوشه");
        mk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newFolder();
            }
        });
        actions.addView(up, Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f));
        actions.addView(mk, withLeft(Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1),
                Ui.dp(this, Ui.SP_2)));
        root.addView(actions);
    }

    private LinearLayout.LayoutParams withLeft(LinearLayout.LayoutParams lp, int leftDp) {
        lp.leftMargin = leftDp;
        return lp;
    }

    // ------------------------------------------------------------- browsing

    private void refresh() {
        if (base == null || base.isEmpty()) {
            showEmpty("📍", "آدرس سرور مشخص نیست", "ابتدا از صفحه اول به یک سرور متصل شوید.");
            statusLabel.setText("");
            return;
        }
        statusLabel.setText("در حال خواندن…");
        final String dir = cwd;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String url = base + "/api/files?dir=" + Http.enc(dir);
                final String body = Http.text(url, token);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        applyListing(body, dir);
                    }
                });
            }
        }).start();
    }

    private void applyListing(String body, String dir) {
        entries.clear();
        listBox.removeAllViews();
        if (body == null) {
            countLabel.setText("آفلاین");
            showEmpty("📡", "اتصال برقرار نشد",
                    "آدرس و توکن را بررسی کنید و دوباره تلاش کنید.");
            statusLabel.setText("خطای شبکه · " + base);
            return;
        }
        try {
            JSONObject o = new JSONObject(body);
            JSONArray arr = o.optJSONArray("entries");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    entries.add(arr.getJSONObject(i));
                }
            }
        } catch (Exception e) {
            countLabel.setText("خطا");
            showEmpty("⚠", "پاسخ نامعتبر از سرور", "نسخه سرور و اپ را بررسی کنید.");
            return;
        }

        // Folders first, then files, each alphabetical (case-insensitive).
        Collections.sort(entries, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                boolean da = a.optBoolean("dir");
                boolean db = b.optBoolean("dir");
                if (da != db) {
                    return da ? -1 : 1;
                }
                return a.optString("name").compareToIgnoreCase(b.optString("name"));
            }
        });

        pathLabel.setText("/" + (dir == null ? "" : dir));
        countLabel.setText(entries.isEmpty()
                ? "خالی"
                : entries.size() + " مورد");
        statusLabel.setText(base);
        prefs.putString(Prefs.K_LAST_DIR, dir);

        if (cwd != null && !cwd.isEmpty()) {
            listBox.addView(upRow());
        }
        if (entries.isEmpty()) {
            showEmpty("📂", "این پوشه خالی است",
                    "برای فرستادن فایل از گوشی، «آپلود به سرور» را بزنید.");
            return;
        }
        hideEmpty();
        for (int i = 0; i < entries.size(); i++) {
            listBox.addView(entryRow(entries.get(i)));
        }
    }

    private void showEmpty(String icon, String title, String sub) {
        emptyBox.removeAllViews();
        TextView i = Ui.text(this, icon, 34, Ui.TEXT_FAINT, false);
        i.setGravity(Gravity.CENTER);
        emptyBox.addView(i);
        TextView t = Ui.text(this, title, Ui.TS_HEADING, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        Ui.addGap(emptyBox, t, this, Ui.SP_3);
        TextView s = Ui.text(this, sub, Ui.TS_CAPTION, Ui.TEXT_DIM, false);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(Ui.dp(this, 2), 1f);
        Ui.addGap(emptyBox, s, this, Ui.SP_2);
        emptyBox.setVisibility(View.VISIBLE);
        listScroll.setVisibility(View.GONE);
    }

    private void hideEmpty() {
        emptyBox.setVisibility(View.GONE);
        listScroll.setVisibility(View.VISIBLE);
    }

    /** ".." row that walks up one directory. */
    private View upRow() {
        LinearLayout row = fileRowShell();
        TextView icon = Ui.text(this, "⬑", 16, Ui.TEXT_DIM, false);
        icon.setGravity(Gravity.CENTER);
        row.addView(icon, Ui.lp(Ui.dp(this, 26), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView name = Ui.text(this, "پوشه بالاتر", Ui.TS_LABEL + 1, Ui.TEXT_DIM, false);
        row.addView(name, withLeft(Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1),
                Ui.dp(this, Ui.SP_3)));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goUp();
            }
        });
        return row;
    }

    private LinearLayout fileRowShell() {
        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.pressable(Ui.SURFACE, Ui.R_MD, this));
        int p = Ui.dp(this, Ui.SP_3);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, Ui.SP_1 + 2);
        row.setLayoutParams(lp);
        return row;
    }

    private View entryRow(final JSONObject e) {
        final boolean isDir = e.optBoolean("dir");
        final String name = e.optString("name");
        final String path = e.optString("path");
        final long size = e.optLong("size");

        LinearLayout row = fileRowShell();
        TextView icon = Ui.text(this, isDir ? "📁" : fileIcon(name), 17,
                isDir ? Ui.BLUE : Ui.TEXT_DIM, false);
        icon.setGravity(Gravity.CENTER);
        row.addView(icon, Ui.lp(Ui.dp(this, 26), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout txt = Ui.column(this);
        TextView n = Ui.text(this, name, Ui.TS_LABEL + 1, isDir ? Ui.BLUE : Ui.TEXT, false);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        txt.addView(n);
        TextView meta = Ui.text(this, isDir ? "پوشه" : human(size), Ui.TS_MICRO,
                Ui.TEXT_FAINT, false);
        txt.addView(meta);
        row.addView(txt, withLeft(Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1),
                Ui.dp(this, Ui.SP_3)));

        TextView menu = Ui.text(this, "⋮", 16, Ui.TEXT_DIM, false);
        menu.setGravity(Gravity.CENTER);
        menu.setPadding(Ui.dp(this, Ui.SP_3), Ui.dp(this, Ui.SP_1),
                Ui.dp(this, Ui.SP_2), Ui.dp(this, Ui.SP_1));
        menu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                entryMenu(e);
            }
        });
        row.addView(menu);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isDir) {
                    cwd = path;
                    refresh();
                } else {
                    downloadFile(path, name, size);
                }
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                entryMenu(e);
                return true;
            }
        });
        return row;
    }

    /** Pick a rough but recognisable glyph from the extension. */
    private static String fileIcon(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif")
                || n.endsWith(".webp") || n.endsWith(".bmp")) {
            return "🖼";
        }
        if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mov")) {
            return "🎬";
        }
        if (n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".ogg") || n.endsWith(".m4a")) {
            return "🎵";
        }
        if (n.endsWith(".zip") || n.endsWith(".tar") || n.endsWith(".gz") || n.endsWith(".7z")
                || n.endsWith(".rar")) {
            return "🗜";
        }
        if (n.endsWith(".pdf")) {
            return "📕";
        }
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log")) {
            return "📝";
        }
        if (n.endsWith(".apk")) {
            return "🤖";
        }
        return "📄";
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void goUp() {
        int idx = cwd == null ? -1 : cwd.lastIndexOf('/');
        cwd = idx <= 0 ? "" : cwd.substring(0, idx);
        refresh();
    }

    private void entryMenu(final JSONObject e) {
        final String path = e.optString("path");
        final String name = e.optString("name");
        final boolean isDir = e.optBoolean("dir");
        final String[] items = isDir
                ? new String[]{"باز کردن", "تغییر نام", "حذف"}
                : new String[]{"دانلود به گوشی", "تغییر نام", "حذف"};
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            if (isDir) {
                                cwd = path;
                                refresh();
                            } else {
                                downloadFile(path, name, e.optLong("size"));
                            }
                        } else if (which == 1) {
                            renameEntry(path, name);
                        } else {
                            confirmDelete(path, name);
                        }
                    }
                })
                .show();
    }

    private void renameEntry(final String path, String current) {
        final EditText input = Ui.input(this, "نام جدید");
        input.setText(current);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("تغییر نام")
                .setView(input)
                .setPositiveButton("ذخیره", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) {
                            return;
                        }
                        postAsync("/api/rename", "path", path, "newName", name);
                    }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void confirmDelete(final String path, String name) {
        new AlertDialog.Builder(this)
                .setTitle("حذف؟")
                .setMessage("«" + name + "» از سرور حذف می‌شود. این کار برگشت‌پذیر نیست.")
                .setPositiveButton("حذف", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        postAsync("/api/delete", "path", path, null, null);
                    }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    /** Fire-and-forget POST, then refresh so the UI reflects reality. */
    private void postAsync(final String endpoint, String k1, String v1, String k2, String v2) {
        try {
            final JSONObject o = new JSONObject();
            o.put(k1, v1);
            if (k2 != null) {
                o.put(k2, v2);
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String res = Http.postJson(base + endpoint, token, o.toString());
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (res == null) {
                                Toast.makeText(FilesActivity.this, "عملیات ناموفق بود",
                                        Toast.LENGTH_SHORT).show();
                            }
                            refresh();
                        }
                    });
                }
            }).start();
        } catch (Exception e) {
            Toast.makeText(this, "خطای داخلی", Toast.LENGTH_SHORT).show();
        }
    }

    private void newFolder() {
        final EditText input = Ui.input(this, "نام پوشه");
        new AlertDialog.Builder(this)
                .setTitle("پوشه جدید")
                .setView(input)
                .setPositiveButton("ساخت", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) {
                            return;
                        }
                        postAsync("/api/mkdir", "dir", cwd, "name", name);
                    }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    // -------------------------------------------------------------- uploads

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "برنامه انتخاب فایل یافت نشد", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            uploadUri(data.getData());
        }
    }

    private String displayName(Uri uri) {
        ContentResolver cr = getContentResolver();
        Cursor c = null;
        try {
            c = cr.query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && !n.isEmpty()) {
                        return n;
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to the path-based name
        } finally {
            if (c != null) {
                c.close();
            }
        }
        String p = uri.getPath();
        return p == null ? "file" : p.substring(p.lastIndexOf('/') + 1);
    }

    private long sizeOf(Uri uri) {
        ContentResolver cr = getContentResolver();
        Cursor c = null;
        try {
            c = cr.query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) {
                    return c.getLong(idx);
                }
            }
        } catch (Exception ignored) {
            // unknown size: the server accepts a streaming upload
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return -1;
    }

    private void uploadUri(final Uri uri) {
        final String name = displayName(uri);
        final long size = sizeOf(uri);
        final TextView status = statusLabel;
        status.setText("آپلود " + name + "…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String err = null;
                try {
                    JSONObject begin = new JSONObject();
                    begin.put("name", name);
                    begin.put("dir", cwd);
                    if (size > 0) {
                        begin.put("size", size);
                    }
                    String resp = Http.postJson(base + "/api/upload/begin", token, begin.toString());
                    if (resp == null) {
                        throw new IllegalStateException("begin failed");
                    }
                    JSONObject info = new JSONObject(resp);
                    String id = info.optString("id");
                    long offset = info.optLong("offset", 0);

                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) {
                        throw new IllegalStateException("cannot open file");
                    }
                    byte[] buf = new byte[1 << 20];
                    long sent = 0;
                    long total = size > 0 ? size : -1;
                    try {
                        // Skip what the server already has (resume after a drop).
                        // read() rather than skip(): a content stream may stop
                        // short on skip() and then we would resend stale bytes.
                        long skip = offset;
                        while (skip > 0) {
                            long s = in.read(buf, 0, (int) Math.min(buf.length, skip));
                            if (s <= 0) {
                                break;
                            }
                            skip -= s;
                        }
                        if (skip > 0) {
                            throw new IllegalStateException("file shrank while uploading");
                        }
                        sent = offset;
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            int code = Http.postBytes(base + "/api/upload/chunk?id=" + Http.enc(id)
                                    + "&offset=" + sent, token, buf, 0, n);
                            if (code != 200 && code != 204) {
                                throw new IllegalStateException("chunk rejected (" + code + ")");
                            }
                            sent += n;
                            final long fs = sent;
                            final long ft = total;
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    status.setText("آپلود " + name + " · " + human(fs)
                                            + (ft > 0 ? " / " + human(ft) : ""));
                                }
                            });
                        }
                    } finally {
                        in.close();
                    }
                    JSONObject fin = new JSONObject();
                    fin.put("id", id);
                    ok = Http.postJson(base + "/api/upload/finish", token, fin.toString()) != null;
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final boolean fok = ok;
                final String ferr = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fok) {
                            Toast.makeText(FilesActivity.this, "آپلود کامل شد ✓", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(FilesActivity.this,
                                    "آپلود ناموفق: " + (ferr == null ? "خطای شبکه" : ferr),
                                    Toast.LENGTH_LONG).show();
                        }
                        refresh();
                    }
                });
            }
        }, "devviord-upload").start();
    }

    // ------------------------------------------------------------ downloads

    /**
     * Destination for downloaded files.
     *
     * <p>Prefers the public Downloads/DevvioRD folder (user visible, works on all
     * versions below Android 10 with the WRITE permission). Where that is not
     * writable - Android 10+ scoped storage, or a locked-down OEM - it falls
     * back to the app-specific external folder, which needs no permission.
     */
    private File downloadsDir() {
        File appDir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "DevvioRD");
        if (!appDir.exists() && !appDir.mkdirs()) {
            appDir = getFilesDir();
        }
        File pub = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "DevvioRD");
        boolean canWritePublic;
        try {
            if (!pub.exists()) {
                canWritePublic = pub.mkdirs();
            } else {
                canWritePublic = pub.canWrite();
            }
        } catch (Throwable ignored) {
            canWritePublic = false;
        }
        return canWritePublic ? pub : appDir;
    }

    private void downloadFile(final String path, final String name, long size) {
        final TextView status = statusLabel;
        status.setText("دانلود " + name + " · 0%");
        final File dest = uniqueDest(downloadsDir(), name);
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = Http.download(base + "/api/download?path=" + Http.enc(path), token, dest,
                        new Http.Progress() {
                            @Override
                            public void onProgress(final long done, final long total) {
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        status.setText("دانلود " + name + " · "
                                                + (total > 0 ? (done * 100 / total) + "% ("
                                                + human(done) + ")" : human(done)));
                                    }
                                });
                            }
                        });
                final boolean fok = ok;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(base);
                        if (fok) {
                            String where = dest.getAbsolutePath().contains("/Android/data/")
                                    ? "ذخیره شد (پوشه اپ): " + dest.getAbsolutePath()
                                    : "ذخیره شد در Downloads/DevvioRD/" + dest.getName();
                            Toast.makeText(FilesActivity.this, where, Toast.LENGTH_LONG).show();
                        } else {
                            if (dest.exists()) {
                                //noinspection ResultOfMethodCallIgnored
                                dest.delete();
                            }
                            Toast.makeText(FilesActivity.this, "دانلود ناموفق بود",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "devviord-download").start();
    }

    /** Never overwrite an existing download: append " (2)", " (3)", ... */
    private static File uniqueDest(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) {
            return f;
        }
        String baseName = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            baseName = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            File c = new File(dir, baseName + " (" + i + ")" + ext);
            if (!c.exists()) {
                return c;
            }
        }
        return f;
    }

    // ------------------------------------------------------------- back nav

    @Override
    public void onBackPressed() {
        // Inside a folder, Back means "up one level" - the behaviour every file
        // manager has, and it stops accidental app exits while browsing.
        if (cwd != null && !cwd.isEmpty()) {
            goUp();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------- plumbing

    /**
     * Asks the server for its file root; when the root is the default
     * "DevvioRD/Downloads" folder we jump straight into it.
     */
    private void probeSharedDir() {
        statusLabel.setText("در حال اتصال…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String body = Http.text(base + "/api/files?dir=", token);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        String start = "";
                        if (body != null) {
                            try {
                                JSONObject o = new JSONObject(body);
                                String root = o.optString("root");
                                if (root != null && root.contains("DevvioRD")) {
                                    start = "Downloads";
                                }
                            } catch (Exception ignored) {
                                // stay at the root
                            }
                        }
                        cwd = start;
                        refresh();
                    }
                });
            }
        }).start();
    }

    private static String restBase(String host) {
        String h = host == null ? "" : host;
        if (h.startsWith("ws://")) {
            h = "http://" + h.substring(5);
        } else if (h.startsWith("wss://")) {
            h = "https://" + h.substring(6);
        }
        h = h.replaceAll("/ws$", "");
        if (!h.startsWith("http")) {
            h = "http://" + h;
        }
        if (h.matches("https?://[^/:]+")) {
            h = h + ":5900";
        }
        return h;
    }
}
