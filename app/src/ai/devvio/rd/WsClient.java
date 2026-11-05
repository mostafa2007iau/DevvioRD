package ai.devvio.rd;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal, dependency-free WebSocket client (RFC 6455).
 *
 * <p>Implemented directly on sockets so the app ships without third-party
 * libraries and works on every Android version from 5.0 up. Supports ws:// and
 * wss://, fragmentation, ping/pong, and graceful close.
 */
public final class WsClient {

    private static final String TAG = "DevvioRD/ws";
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** Listener for connection events and inbound messages. */
    public interface Listener {
        void onOpen();

        void onMessage(Proto.Message msg);

        void onClosed(String reason);

        void onError(Throwable t);
    }

    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong bytesIn = new AtomicLong();
    private final AtomicLong bytesOut = new AtomicLong();

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private Thread reader;
    private volatile boolean stopRequested;

    private final Object writeLock = new Object();

    public WsClient(Listener l) {
        this.listener = l;
    }

    public long getBytesIn() {
        return bytesIn.get();
    }

    public long getBytesOut() {
        return bytesOut.get();
    }

    /**
     * Connects and performs the handshake. Blocking; call from a worker thread.
     *
     * @param url       ws://host:port/ws or wss://...
     * @param token     optional access token
     * @param timeoutMs connect timeout
     */
    public void connect(String url, String token, int timeoutMs) throws IOException {
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "ws" : uri.getScheme().toLowerCase();
        boolean secure = scheme.equals("wss") || scheme.equals("https");
        String host = uri.getHost();
        int port = uri.getPort();
        if (port < 0) {
            port = secure ? 443 : 5900;
        }
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/ws" : uri.getPath();
        if (uri.getQuery() != null) {
            path += "?" + uri.getQuery();
        }

        Socket s;
        if (secure) {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) f.createSocket();
            ssl.connect(new InetSocketAddress(host, port), timeoutMs);
            ssl.startHandshake();
            s = ssl;
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
        }
        s.setTcpNoDelay(true);
        s.setSoTimeout(0);
        socket = s;
        in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
        out = new BufferedOutputStream(s.getOutputStream(), 32 * 1024);

        // --- handshake ---
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        String key = Base64.encodeToString(raw, Base64.NO_WRAP);
        StringBuilder req = new StringBuilder();
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host).append(':').append(port).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        if (token != null && !token.isEmpty()) {
            req.append("X-DevvioRD-Token: ").append(token).append("\r\n");
        }
        req.append("\r\n");
        synchronized (writeLock) {
            out.write(req.toString().getBytes("UTF-8"));
            out.flush();
        }

        String headers = readHeaders();
        if (!headers.startsWith("HTTP/1.1 101") && !headers.startsWith("HTTP/1.0 101")) {
            String first = headers.split("\r\n", 2)[0];
            throw new IOException("handshake rejected: " + first);
        }
        String expected;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            expected = Base64.encodeToString(sha1.digest((key + GUID).getBytes("UTF-8")), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IOException(e);
        }
        if (!headers.toLowerCase().contains("sec-websocket-accept: " + expected.toLowerCase())) {
            throw new IOException("invalid Sec-WebSocket-Accept");
        }

        running.set(true);
        stopRequested = false;
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "devviord-ws-read");
        reader.setDaemon(true);
        reader.start();
        if (listener != null) {
            listener.onOpen();
        }
    }

    private String readHeaders() throws IOException {
        StringBuilder sb = new StringBuilder();
        int state = 0;
        while (true) {
            int c = in.read();
            if (c < 0) {
                throw new EOFException("connection closed during handshake");
            }
            sb.append((char) c);
            if ((state == 0 || state == 2) && c == '\r') {
                state++;
            } else if ((state == 1 || state == 3) && c == '\n') {
                state++;
                if (state == 4) {
                    return sb.toString();
                }
            } else {
                state = 0;
            }
            if (sb.length() > 16 * 1024) {
                throw new IOException("handshake header too large");
            }
        }
    }

    private void readLoop() {
        byte[] header = new byte[2];
        byte[] ext = new byte[8];
        byte[] mask = new byte[4];
        byte[] frag = null;
        int fragOp = 0;
        try {
            while (running.get() && !stopRequested) {
                readFully(header, 0, 2);
                int b0 = header[0] & 0xFF;
                int b1 = header[1] & 0xFF;
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    readFully(ext, 0, 2);
                    len = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
                } else if (len == 127) {
                    readFully(ext, 0, 8);
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        len = (len << 8) | (ext[i] & 0xFF);
                    }
                }
                if (len < 0 || len > 64L * 1024 * 1024) {
                    throw new IOException("frame too large: " + len);
                }
                if (masked) {
                    readFully(mask, 0, 4);
                }
                byte[] payload = new byte[(int) len];
                readFully(payload, 0, payload.length);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i & 3];
                    }
                }
                bytesIn.addAndGet(payload.length + 2);
                switch (op) {
                    case 0x9: // ping
                        // Answer with a pong carrying the same payload, as RFC
                        // 6455 requires. The server pings every 15 s and drops a
                        // connection that stays silent for 90 s, so a client that
                        // ignored ping frames lost its session whenever the user
                        // stopped touching the screen for a minute and a half.
                        try {
                            writeFrame(0xA, payload);
                        } catch (IOException e) {
                            closeInternal("pong failed", true);
                            return;
                        }
                        continue;
                    case 0xA: // pong
                        continue;
                    case 0x8: // close
                        String reason = payload.length > 2
                                ? new String(payload, 2, payload.length - 2, "UTF-8") : "server closed";
                        closeInternal(reason, true);
                        return;
                    case 0x1:
                    case 0x2:
                        if (!fin) {
                            fragOp = op;
                            frag = payload;
                            continue;
                        }
                        dispatch(payload);
                        continue;
                    case 0x0:
                        if (frag != null) {
                            byte[] merged = new byte[frag.length + payload.length];
                            System.arraycopy(frag, 0, merged, 0, frag.length);
                            System.arraycopy(payload, 0, merged, frag.length, payload.length);
                            frag = merged;
                            if (fin) {
                                byte[] done = frag;
                                frag = null;
                                fragOp = 0;
                                dispatch(done);
                            }
                        }
                        continue;
                    default:
                        continue;
                }
            }
        } catch (EOFException e) {
            closeInternal("connection closed", true);
        } catch (IOException e) {
            if (running.get() && !stopRequested) {
                closeInternal(e.getMessage() == null ? "network error" : e.getMessage(), true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "read loop failed", t);
            closeInternal(String.valueOf(t.getMessage()), true);
        }
    }

    private void dispatch(byte[] payload) {
        if (listener == null || payload.length == 0) {
            return;
        }
        try {
            Proto.Message m = Proto.parse(payload, payload.length);
            listener.onMessage(m);
        } catch (Throwable t) {
            listener.onError(t);
        }
    }

    private void readFully(byte[] buf, int off, int len) throws IOException {
        int done = 0;
        while (done < len) {
            int n = in.read(buf, off + done, len - done);
            if (n < 0) {
                throw new EOFException("connection closed");
            }
            done += n;
        }
    }

    /** Sends a binary message. Frames silently no-op once the socket is closed. */
    public void send(byte[] payload) {
        if (!running.get() || payload == null || out == null) {
            return;
        }
        try {
            writeFrame(0x2, payload);
        } catch (IOException e) {
            closeInternal("send failed: " + e.getMessage(), true);
        }
    }

    private void writeFrame(int op, byte[] payload) throws IOException {
        if (out == null) {
            throw new IOException("socket closed");
        }
        int n = payload.length;
        byte[] head;
        int hlen;
        if (n < 126) {
            head = new byte[6];
            head[0] = (byte) (0x80 | op);
            head[1] = (byte) (0x80 | n);
            hlen = 2;
        } else if (n <= 0xFFFF) {
            head = new byte[8];
            head[0] = (byte) (0x80 | op);
            head[1] = (byte) (0x80 | 126);
            head[2] = (byte) (n >>> 8);
            head[3] = (byte) n;
            hlen = 4;
        } else {
            head = new byte[14];
            head[0] = (byte) (0x80 | op);
            head[1] = (byte) (0x80 | 127);
            long L = n;
            for (int i = 0; i < 8; i++) {
                head[2 + i] = (byte) (L >>> (56 - 8 * i));
            }
            hlen = 10;
        }
        byte[] mask = new byte[4];
        MASK_RANDOM.nextBytes(mask);
        System.arraycopy(mask, 0, head, hlen, 4);
        byte[] framed = new byte[hlen + 4 + n];
        System.arraycopy(head, 0, framed, 0, hlen + 4);
        for (int i = 0; i < n; i++) {
            framed[hlen + 4 + i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        synchronized (writeLock) {
            out.write(framed);
            out.flush();
        }
        bytesOut.addAndGet(framed.length);
    }

    private static final SecureRandom MASK_RANDOM = new SecureRandom();

    /** Closes the connection. */
    public void close(String reason) {
        closeInternal(reason == null ? "client closed" : reason, true);
    }

    private void closeInternal(String reason, boolean notify) {
        boolean was = running.getAndSet(false);
        stopRequested = true;
        try {
            synchronized (writeLock) {
                if (out != null && was) {
                    byte[] r = reason == null ? new byte[0] : reason.getBytes("UTF-8");
                    byte[] p = new byte[2 + r.length];
                    p[0] = 0x03;
                    p[1] = (byte) 0xE8; // 1000 normal closure
                    System.arraycopy(r, 0, p, 2, r.length);
                    writeFrame(0x8, p);
                }
            }
        } catch (Throwable ignored) {
            // socket already gone
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        socket = null;
        in = null;
        out = null;
        if (notify && listener != null) {
            listener.onClosed(reason);
        }
    }

    public boolean isConnected() {
        return running.get();
    }
}
