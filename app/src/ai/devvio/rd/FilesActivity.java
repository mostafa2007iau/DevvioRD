package ai.devvio.rd;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Two-way file transfer.
 *
 * <p>Server side: the directory exposed by {@code devviord --files}, browsable
 * and downloadable. Phone side: files picked with the system picker are uploaded
 * in 1 MiB chunks (resumable) and downloaded files land in the app's public
 * Downloads folder.
 */
public class FilesActivity extends Activity {

    private static final int REQ_PICK = 1001;

    private String host;
    private String token;
    private String base;
    private Prefs prefs;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<JSONObject> entries = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ListView list;
    private TextView pathLabel;
    private TextView statusLabel;
    private String cwd = "";

    private static final DecimalFormat SIZE = new DecimalFormat("#,###");

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
        // Start where the server puts transferred files, not in an empty path.
        if (cwd == null || cwd.isEmpty()) {
            probeSharedDir();
        } else {
            refresh();
        }
    }

    /**
     * Asks the server for its file root; when the root is the default
     * "DevvioRD/Downloads" folder we jump straight into it, otherwise we stay at
     * the root the server reported.
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

    private View buildUi() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);

        // header
        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 10));
        TextView back = Ui.text(this, "‹", 26, Ui.TEXT, true);
        back.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 14), 0);
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
        titles.addView(Ui.text(this, "انتقال فایل / Files", 17, Ui.TEXT, true));
        pathLabel = Ui.text(this, "/", 11, Ui.TEXT_DIM, false);
        pathLabel.setText("/" + cwd);
        titles.addView(pathLabel);
        header.addView(titles, Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView refreshBtn = Ui.chip(this, "↻", Ui.ACCENT);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });
        header.addView(refreshBtn);
        root.addView(header);

        // path breadcrumb
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        pathLabel = Ui.text(this, "/", 11, Ui.TEXT_DIM, false);
        hs.addView(pathLabel);
        root.addView(hs);

        // list
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(Ui.TEXT);
                v.setTextSize(14);
                v.setPadding(Ui.dp(FilesActivity.this, 14), Ui.dp(FilesActivity.this, 12),
                        Ui.dp(FilesActivity.this, 14), Ui.dp(FilesActivity.this, 12));
                return v;
            }
        };
        list = new ListView(this);
        list.setDividerHeight(1);
        list.setDivider(android.graphics.drawable.Drawable.createFromPath("#1D252F"));
        list.setBackgroundColor(Ui.BG);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                onEntryClick(pos);
            }
        });
        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                showEntryMenu(pos);
                return true;
            }
        });
        root.addView(list, Ui.lpm(0, 0, 1));

        statusLabel = Ui.text(this, "", 12, Ui.TEXT_DIM, false);
        statusLabel.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        root.addView(statusLabel);

        // actions
        LinearLayout actions = Ui.row(this);
        actions.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 14));
        TextView up = Ui.button(this, "↑ آپلود به سرور", Ui.ACCENT, 0xFF04140C);
        up.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        TextView mk = Ui.button(this, "پوشه جدید", Ui.SURFACE_2, Ui.TEXT);
        mk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newFolder();
            }
        });
        actions.addView(up, Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        actions.addView(mk, withLeft(Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1), 8));
        root.addView(actions);

        return root;
    }

    private LinearLayout.LayoutParams withLeft(LinearLayout.LayoutParams lp, int leftDp) {
        lp.leftMargin = Ui.dp(this, leftDp);
        return lp;
    }

    // ------------------------------------------------------------- browsing

    private void refresh() {
        if (base == null || base.isEmpty()) {
            statusLabel.setText("آدرس سرور نامشخص است. ابتدا متصل شوید.");
            return;
        }
        statusLabel.setText("در حال خواندن…");
        list.setVisibility(View.VISIBLE);
        statusLabel.setVisibility(View.VISIBLE);
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
        adapter.clear();
        if (body == null) {
            statusLabel.setText("اتصال به سرور برقرار نشد. آدرس/توکن را بررسی کنید.");
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
            statusLabel.setText("پاسخ نامعتبر از سرور");
            return;
        }
        if (cwd != null && !cwd.isEmpty()) {
            adapter.add("⬑  .. (بازگشت)");
        }
        int offset = (cwd != null && !cwd.isEmpty()) ? 1 : 0;
        for (JSONObject e : entries) {
            boolean isDir = e.optBoolean("dir");
            String name = e.optString("name");
            long size = e.optLong("size");
            adapter.add((isDir ? "📁  " : "📄  ") + name + (isDir ? "" : "   ·   " + human(size)));
        }
        pathLabel.setText("/" + (dir == null ? "" : dir));
        statusLabel.setText(entries.size() + " مورد · " + base);
        prefs.putString(Prefs.K_LAST_DIR, dir);
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
        int idx = cwd.lastIndexOf('/');
        cwd = idx <= 0 ? "" : cwd.substring(0, idx);
        refresh();
    }

    private void onEntryClick(int pos) {
        int offset = (cwd != null && !cwd.isEmpty()) ? 1 : 0;
        if (offset == 1 && pos == 0) {
            goUp();
            return;
        }
        int idx = pos - offset;
        if (idx < 0 || idx >= entries.size()) {
            return;
        }
        JSONObject e = entries.get(idx);
        String path = e.optString("path");
        if (e.optBoolean("dir")) {
            cwd = path;
            refresh();
        } else {
            downloadFile(path, e.optString("name"), e.optLong("size"));
        }
    }

    private void showEntryMenu(final int pos) {
        int offset = (cwd != null && !cwd.isEmpty()) ? 1 : 0;
        int idx = pos - offset;
        if (idx < 0 || idx >= entries.size()) {
            return;
        }
        final JSONObject e = entries.get(idx);
        final String path = e.optString("path");
        final String[] items = {"دانلود به گوشی", "تغییر نام", "حذف"};
        new AlertDialog.Builder(this)
                .setTitle(e.optString("name"))
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            downloadFile(path, e.optString("name"), e.optLong("size"));
                        } else if (which == 1) {
                            renameEntry(path);
                        } else {
                            confirmDelete(path, e.optString("name"));
                        }
                    }
                })
                .show();
    }

    private void renameEntry(final String path) {
        final EditText input = Ui.input(this, "نام جدید");
        input.setText(new File(path).getName());
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
                        try {
                            JSONObject o = new JSONObject();
                            o.put("path", path);
                            o.put("newName", name);
                            Http.postJson(base + "/api/rename", token, o.toString());
                        } catch (Exception ignored) {
                        }
                        refresh();
                    }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void confirmDelete(final String path, String name) {
        new AlertDialog.Builder(this)
                .setTitle("حذف " + name + " ?")
                .setPositiveButton("حذف", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("path", path);
                            Http.postJson(base + "/api/delete", token, o.toString());
                        } catch (Exception ignored) {
                        }
                        refresh();
                    }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void newFolder() {
        final EditText input = Ui.input(this, "نام پوشه");
        new AlertDialog.Builder(this)
                .setTitle("پوشه جدید")
                .setView(input)
                .setPositiveButton("ایجاد", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) {
                            return;
                        }
                        try {
                            JSONObject o = new JSONObject();
                            o.put("dir", cwd);
                            o.put("name", name);
                            Http.postJson(base + "/api/mkdir", token, o.toString());
                        } catch (Exception ignored) {
                        }
                        refresh();
                    }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    // ------------------------------------------------------------- transfer

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "انتخاب فایل برای آپلود"), REQ_PICK);
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

    private String queryName(Uri uri) {
        String name = null;
        try {
            ContentResolver cr = getContentResolver();
            try (Cursor c = cr.query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        name = c.getString(idx);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (name == null) {
            name = uri.getLastPathSegment();
        }
        if (name == null) {
            name = "upload-" + System.currentTimeMillis();
        }
        return new File(name).getName();
    }

    private long querySize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) {
                    return c.getLong(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** Uploads a content:// URI to the server in resumable 1 MiB chunks. */
    private void uploadUri(final Uri uri) {
        final String name = queryName(uri);
        final long size = querySize(uri);
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("آپلود " + name);
        pd.setMessage("آماده‌سازی…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(1000);
        pd.setCancelable(false);
        pd.show();

        final String dir = cwd;
        new Thread(new Runnable() {
            @Override
            public void run() {
                InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    if (in == null) {
                        throw new Exception("cannot open stream");
                    }
                    JSONObject begin = new JSONObject();
                    begin.put("name", name);
                    begin.put("dir", dir);
                    begin.put("size", size);
                    String resp = Http.postJson(base + "/api/upload/begin", token, begin.toString());
                    if (resp == null) {
                        throw new Exception("server rejected upload");
                    }
                    JSONObject r = new JSONObject(resp);
                    final String id = r.getString("id");
                    long offset = r.optLong("offset", 0);
                    if (offset > 0) {
                        long skipped = in.skip(offset);
                        if (skipped != offset) {
                            throw new Exception("cannot resume upload");
                        }
                    }
                    byte[] buf = new byte[1 << 20];
                    long sent = offset;
                    int n;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            pd.setMessage("در حال ارسال…");
                        }
                    });
                    while ((n = in.read(buf)) > 0) {
                        int code = Http.postBytes(base + "/api/upload/chunk?id=" + id
                                + "&offset=" + sent, token, buf, 0, n);
                        if (code != 200) {
                            throw new Exception("chunk rejected (" + code + ")");
                        }
                        sent += n;
                        final long fSent = sent;
                        final long fSize = size;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (fSize > 0) {
                                    pd.setProgress((int) (fSent * 1000 / fSize));
                                    pd.setMessage(human(fSent) + " / " + human(fSize));
                                } else {
                                    pd.setMessage(human(fSent) + " ارسال شد");
                                }
                            }
                        });
                    }
                    JSONObject fin = new JSONObject();
                    fin.put("id", id);
                    String finResp = Http.postJson(base + "/api/upload/finish", token, fin.toString());
                    final boolean ok = finResp != null && finResp.contains("\"name\"");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            Toast.makeText(FilesActivity.this, ok ? "آپلود کامل شد ✓" : "آپلود ناتمام",
                                    Toast.LENGTH_LONG).show();
                            refresh();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            Toast.makeText(FilesActivity.this, "خطا در آپلود: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    try {
                        if (in != null) {
                            in.close();
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "devviord-upload").start();
    }

    /**
     * Destination for downloaded files.
     *
     * Prefers the public Downloads/DevvioRD folder (user visible, works on all
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
        boolean canWritePublic = false;
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
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("دانلود " + name);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(1000);
        pd.setCancelable(true);
        pd.show();
        final File dest = new File(downloadsDir(), name);
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
                                        if (total > 0) {
                                            pd.setProgress((int) (done * 1000 / total));
                                            pd.setMessage(human(done) + " / " + human(total));
                                        } else {
                                            pd.setMessage(human(done));
                                        }
                                    }
                                });
                            }
                        });
                final boolean fok = ok;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        pd.dismiss();
                        if (fok) {
                            boolean isPublic = dest.getAbsolutePath()
                                    .startsWith(Environment.getExternalStorageDirectory().getAbsolutePath());
                            String where = isPublic
                                    ? "ذخیره شد در Downloads/DevvioRD/" + dest.getName()
                                    : "ذخیره شد (پوشه اپ): " + dest.getAbsolutePath();
                            Toast.makeText(FilesActivity.this, where, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(FilesActivity.this, "دانلود ناموفق بود",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "devviord-download").start();
    }
}
