package ai.devvio.rd;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

/**
 * DevvioRD wire protocol (client side).
 *
 * <p>Must stay byte-compatible with server/internal/proto/proto.go. Every
 * message is: one type byte followed by a binary or JSON payload.
 */
public final class Proto {

    public static final int VERSION = 1;

    // client -> server
    public static final int T_HELLO = 0x01;
    public static final int T_TOUCH = 0x10;
    public static final int T_SCROLL = 0x11;
    public static final int T_KEY = 0x14;
    public static final int T_TEXT = 0x15;
    public static final int T_CLIPBOARD = 0x20;
    public static final int T_POINTER_MODE = 0x21;
    public static final int T_PING = 0x40;
    public static final int T_QUALITY = 0x41;

    // server -> client
    public static final int T_HELLO_OK = 0x81;
    public static final int T_FRAME = 0x90;
    public static final int T_CURSOR = 0x92;
    public static final int T_CLIP = 0xA0;
    public static final int T_STATUS = 0xB0;
    public static final int T_SCREEN = 0xC0;
    public static final int T_PONG = 0xC1;
    public static final int T_ERROR = 0xEE;

    public static final int TOUCH_DOWN = 0;
    public static final int TOUCH_MOVE = 1;
    public static final int TOUCH_UP = 2;
    public static final int TOUCH_CANCEL = 3;

    public static final int FRAME_HEADER = 1 + 4 + 2 + 2 + 8;
    public static final int TOUCH_SIZE = 1 + 24;

    public static final String MODE_TOUCH = "touch";
    public static final String MODE_MOUSE = "mouse";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Proto() {
    }

    // ---------------------------------------------------------------- encode

    public static byte[] hello(String client, String device, boolean mouse, boolean clipboard,
                               boolean files, boolean multiTouch, int maxW, int maxH, int quality) {
        try {
            JSONObject caps = new JSONObject();
            caps.put("touch", true);
            caps.put("mouse", mouse);
            caps.put("clipboard", clipboard);
            caps.put("files", files);
            caps.put("multitouch", multiTouch);
            caps.put("keyboard", true);
            caps.put("maxWidth", maxW);
            caps.put("maxHeight", maxH);
            caps.put("jpegQuality", quality);
            JSONObject o = new JSONObject();
            o.put("version", VERSION);
            o.put("client", client);
            o.put("device", device);
            o.put("caps", caps);
            return withType(T_HELLO, o.toString().getBytes(UTF8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fixed-size touch event: action, pointerId, pad, x, y, pressure, radius, timestamp. */
    public static byte[] touch(int action, int pointerId, float x, float y, float pressure, int timeMs) {
        ByteBuffer b = ByteBuffer.allocate(TOUCH_SIZE).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) T_TOUCH);
        b.put((byte) action);
        b.put((byte) pointerId);
        b.putShort((short) 0);
        b.putFloat(x);
        b.putFloat(y);
        b.putFloat(pressure);
        b.putFloat(0f);
        b.putInt(timeMs);
        return b.array();
    }

    public static byte[] scroll(float dx, float dy) {
        ByteBuffer b = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) T_SCROLL);
        b.putFloat(dx);
        b.putFloat(dy);
        return b.array();
    }

    public static byte[] key(String key, String code, String action, boolean ctrl, boolean alt,
                             boolean shift, boolean meta) {
        try {
            JSONObject o = new JSONObject();
            o.put("key", key);
            if (code != null) {
                o.put("code", code);
            }
            o.put("action", action);
            JSONObject mods = new JSONObject();
            if (ctrl) mods.put("ctrl", true);
            if (alt) mods.put("alt", true);
            if (shift) mods.put("shift", true);
            if (meta) mods.put("meta", true);
            o.put("mods", mods);
            return withType(T_KEY, o.toString().getBytes(UTF8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] text(String s) {
        try {
            JSONObject o = new JSONObject();
            o.put("text", s);
            return withType(T_TEXT, o.toString().getBytes(UTF8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] clipboard(String s) {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "text");
            o.put("data", s);
            return withType(T_CLIPBOARD, o.toString().getBytes(UTF8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] pointerMode(String mode) {
        try {
            JSONObject o = new JSONObject();
            o.put("mode", mode);
            return withType(T_POINTER_MODE, o.toString().getBytes(UTF8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] ping(long millis) {
        ByteBuffer b = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) T_PING);
        b.putLong(millis);
        return b.array();
    }

    public static byte[] quality(int q, int fps, float scale) {
        try {
            JSONObject o = new JSONObject();
            if (q > 0) o.put("quality", q);
            if (fps > 0) o.put("fps", fps);
            if (scale > 0) o.put("scale", scale);
            return withType(T_QUALITY, o.toString().getBytes(UTF8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] withType(int type, byte[] payload) {
        byte[] out = new byte[payload.length + 1];
        out[0] = (byte) type;
        System.arraycopy(payload, 0, out, 1, payload.length);
        return out;
    }

    // ---------------------------------------------------------------- decode

    /** Parsed server message. */
    public static final class Message {
        public final int type;
        public final byte[] data;   // payload after the type byte
        public final int seq;
        public final int width;
        public final int height;
        public final long timestamp;
        public final int offset;    // start of JPEG payload (frames) / 1 otherwise

        Message(int type, byte[] data, int seq, int w, int h, long ts, int offset) {
            this.type = type;
            this.data = data;
            this.seq = seq;
            this.width = w;
            this.height = h;
            this.timestamp = ts;
            this.offset = offset;
        }

        public JSONObject json() throws Exception {
            return new JSONObject(new String(data, offset, data.length - offset, UTF8));
        }

        public String string() {
            return new String(data, offset, data.length - offset, UTF8);
        }

        public boolean isFrame() {
            return type == T_FRAME;
        }
    }

    /** Parses a raw websocket payload into a message. */
    public static Message parse(byte[] buf, int len) throws IOException {
        if (len < 1) {
            throw new IOException("empty message");
        }
        int type = buf[0] & 0xFF;
        if (type == T_FRAME) {
            if (len < FRAME_HEADER) {
                throw new IOException("short frame");
            }
            ByteBuffer b = ByteBuffer.wrap(buf, 0, FRAME_HEADER).order(ByteOrder.BIG_ENDIAN);
            b.position(1);
            int seq = b.getInt();
            int w = b.getShort() & 0xFFFF;
            int h = b.getShort() & 0xFFFF;
            long ts = b.getLong();
            return new Message(type, buf, seq, w, h, ts, FRAME_HEADER);
        }
        if (type == T_PONG) {
            if (len < 9) {
                throw new IOException("short pong");
            }
            long v = ByteBuffer.wrap(buf, 1, 8).order(ByteOrder.BIG_ENDIAN).getLong();
            return new Message(type, buf, 0, 0, 0, v, 1);
        }
        return new Message(type, buf, 0, 0, 0, 0, 1);
    }

    /** Extracts the JPEG bytes from a frame message. */
    public static byte[] frameBytes(Message m) {
        int n = m.data.length - m.offset;
        byte[] out = new byte[n];
        System.arraycopy(m.data, m.offset, out, 0, n);
        return out;
    }

    public static String utf8(byte[] b, int off) {
        return new String(b, off, b.length - off, UTF8);
    }

    /** Concatenates payloads (used by the websocket writer). */
    public static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(a.length + b.length);
        out.write(a, 0, a.length);
        out.write(b, 0, b.length);
        return out.toByteArray();
    }
}
