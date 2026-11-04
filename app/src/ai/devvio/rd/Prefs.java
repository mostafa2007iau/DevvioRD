package ai.devvio.rd;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted connection settings and preferences. */
public final class Prefs {

    private static final String NAME = "devviord";

    public static final String K_HOST = "host";
    public static final String K_TOKEN = "token";
    public static final String K_MODE = "pointerMode";
    public static final String K_QUALITY = "quality";
    public static final String K_FPS = "fps";
    public static final String K_SCALE = "scale";
    public static final String K_NATIVE_KB = "nativeKeyboard";
    public static final String K_AUTOCONNECT = "autoConnect";
    public static final String K_CLIP_SYNC = "clipboardSync";
    public static final String K_SHOW_MOUSE_LOCAL = "showLocalCursor";
    public static final String K_FILES_DIR = "filesDir";
    public static final String K_LAST_DIR = "lastDir";
    public static final String K_RECENT = "recent";
    public static final String K_STATS = "showStats";
    public static final String K_BARS_PINNED = "barsPinned";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public String getString(String k, String def) {
        return sp.getString(k, def);
    }

    public void putString(String k, String v) {
        sp.edit().putString(k, v).apply();
    }

    public int getInt(String k, int def) {
        return sp.getInt(k, def);
    }

    public void putInt(String k, int v) {
        sp.edit().putInt(k, v).apply();
    }

    public boolean getBool(String k, boolean def) {
        return sp.getBoolean(k, def);
    }

    public void putBool(String k, boolean v) {
        sp.edit().putBoolean(k, v).apply();
    }

    public float getFloat(String k, float def) {
        return sp.getFloat(k, def);
    }

    public void putFloat(String k, float v) {
        sp.edit().putFloat(k, v).apply();
    }

    /** Adds/serves a small most-recently-used list of hosts (newest first). */
    public void addRecent(String host) {
        if (host == null || host.trim().isEmpty()) {
            return;
        }
        putString(K_RECENT, Hosts.pushRecent(getString(K_RECENT, ""), host));
    }

    public String[] recents() {
        return Hosts.parseRecents(getString(K_RECENT, ""));
    }

    /** Forgets every stored host, leaving settings and the token untouched. */
    public void clearRecents() {
        putString(K_RECENT, "");
    }
}
