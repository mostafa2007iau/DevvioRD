package ai.devvio.rd;

/**
 * Host string handling, shared by every screen.
 *
 * <p>Before this existed, three classes each carried their own copy of
 * "turn what the user typed into a URL" logic, and they disagreed: the files
 * screen appended :5900 when no port was given, the remote session did not, and
 * the connect screen stripped "ws://" differently again. One implementation,
 * one behaviour, and it is unit-tested on the desktop JVM.
 *
 * <p>Pure Java on purpose: no Android imports.
 */
public final class Hosts {

    /** Default port the server prints on startup. */
    public static final int DEFAULT_PORT = 5900;

    /** How many recent hosts we keep. */
    public static final int MAX_RECENTS = 8;

    private Hosts() {
    }

    /**
     * Normalises anything the user (or a QR code, or a chat message) may have
     * pasted into "host:port" without a scheme.
     *
     * <pre>
     *   ws://192.168.1.10:5900/ws  ->  192.168.1.10:5900
     *   http://example.com/        ->  example.com
     *   devviord://connect?host=a  ->  unchanged (caller parses URIs first)
     * </pre>
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String h = raw.trim();
        if (h.isEmpty()) {
            return "";
        }
        // Strip a scheme, if present, in any case.
        int scheme = h.indexOf("://");
        if (scheme > 0) {
            h = h.substring(scheme + 3);
        }
        // Drop a path ("/ws", "/api", a trailing slash) but keep the authority.
        int slash = h.indexOf('/');
        if (slash >= 0) {
            h = h.substring(0, slash);
        }
        // Userinfo is never useful here.
        int at = h.indexOf('@');
        if (at >= 0) {
            h = h.substring(at + 1);
        }
        return h.trim();
    }

    /** True when the (normalised) host already carries an explicit port. */
    public static boolean hasPort(String host) {
        String h = normalize(host);
        if (h.isEmpty()) {
            return false;
        }
        int colon = h.lastIndexOf(':');
        if (colon < 0) {
            return false;
        }
        // A colon inside [...] means IPv6 without a port.
        if (h.startsWith("[") && !h.substring(0, Math.max(1, colon)).contains("]")) {
            return false;
        }
        int port = 0;
        for (int i = colon + 1; i < h.length(); i++) {
            char c = h.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            port = port * 10 + (c - '0');
            if (port > 65535) {
                return false;
            }
        }
        return h.length() > colon + 1;
    }

    /** "192.168.1.10" -> "192.168.1.10:5900"; leaves an explicit port alone. */
    public static String withPort(String host, int defaultPort) {
        String h = normalize(host);
        if (h.isEmpty()) {
            return "";
        }
        return hasPort(h) ? h : h + ":" + defaultPort;
    }

    /** The REST base for a host, e.g. {@code http://192.168.1.10:5900}. */
    public static String httpBase(String host, int defaultPort) {
        String h = normalize(host);
        if (h.isEmpty()) {
            return "";
        }
        // Both secure schemes map to https: a wss:// host also serves the REST
        // API over TLS.
        return (isSecure(host) ? "https://" : "http://") + withPort(h, defaultPort);
    }

    /** True when the scheme the user gave implies TLS (https or wss). */
    public static boolean isSecure(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase();
        return h.startsWith("https://") || h.startsWith("wss://");
    }

    /** The WebSocket URL for a host, e.g. {@code ws://192.168.1.10:5900/ws}. */
    public static String wsUrl(String host, int defaultPort) {
        String h = normalize(host);
        if (h.isEmpty()) {
            return "";
        }
        return (isSecure(host) ? "wss://" : "ws://") + withPort(h, defaultPort) + "/ws";
    }

    /**
     * Splits the stored recent-hosts blob into clean, unique, non-empty
     * entries. Blank lines are dropped rather than surfaced as empty rows -
     * the old code returned them and the connect screen rendered a blank
     * clickable row that connected to nothing.
     */
    public static String[] parseRecents(String blob) {
        if (blob == null || blob.trim().isEmpty()) {
            return new String[0];
        }
        String[] raw = blob.split("\n");
        java.util.List<String> out = new java.util.ArrayList<String>(raw.length);
        for (String line : raw) {
            if (line == null) {
                continue;
            }
            String h = line.trim();
            if (h.isEmpty()) {
                continue;
            }
            if (!out.contains(h)) {
                out.add(h);
            }
            if (out.size() >= MAX_RECENTS) {
                break;
            }
        }
        return out.toArray(new String[0]);
    }

    /** Most-recent-first list with {@code host} at the top, as a blob. */
    public static String pushRecent(String blob, String host) {
        String h = normalize(host);
        if (h.isEmpty()) {
            return blob == null ? "" : blob;
        }
        StringBuilder sb = new StringBuilder(h);
        int count = 1;
        for (String existing : parseRecents(blob)) {
            if (existing.equals(h) || count >= MAX_RECENTS) {
                continue;
            }
            sb.append('\n').append(existing);
            count++;
        }
        return sb.toString();
    }

    /**
     * Collision-free download name: "report.pdf" becomes "report (2).pdf"
     * instead of overwriting the first copy.
     */
    public static String uniqueName(String name, int attempt) {
        if (attempt <= 1 || name == null || name.isEmpty()) {
            return name == null ? "" : name;
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name + " (" + attempt + ")";
        }
        return name.substring(0, dot) + " (" + attempt + ")" + name.substring(dot);
    }
}
