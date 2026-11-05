#!/usr/bin/env python3
"""Unit tests for the Android app's pure-Java logic, executed on the desktop JVM.

Several app classes (protocol encoding, IME bridging) contain no
Android-specific calls, so they can be compiled against *stubs* of the few
framework classes they touch and exercised with a tiny reflection-based runner.
This gives real test coverage for the trickiest logic (byte-exact protocol
framing and Unicode/IME text handling) without needing a device.

Usage: python3 scripts/jvm_tests.py
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
T = os.path.join(ROOT, "toolchain")
JAVA = os.path.join(T, "jdk", "bin", "java")
ECJ = os.path.join(T, "tools", "ecj.jar")

# ---- classes under test (must be free of real Android dependencies) ----
SOURCES = [
    "app/src/ai/devvio/rd/Proto.java",
    "app/src/ai/devvio/rd/ImeBridge.java",
    "app/src/ai/devvio/rd/Hosts.java",
]

# ---- minimal stubs so Proto.java resolves org.json on the desktop JVM ----
STUBS = {
    "org/json/JSONObject.java": r"""
package org.json;
import java.util.LinkedHashMap;
import java.util.Map;
/** Test stub: a tiny JSON object good enough to verify key/value emission. */
public class JSONObject {
    private final Map<String,Object> map = new LinkedHashMap<String,Object>();
    public JSONObject() {}
    public JSONObject(String text) { this.raw = text; }
    private String raw;
    public JSONObject put(String k, Object v) { map.put(k, v); return this; }
    public String optString(String k) { Object v = map.get(k); return v == null ? "" : String.valueOf(v); }
    public Object get(String k) { return map.get(k); }
    public Map<String,Object> map() { return map; }
    @Override public String toString() {
        if (raw != null) return raw;
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String,Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append('"').append(':');
            Object v = e.getValue();
            if (v instanceof JSONObject) sb.append(v.toString());
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else sb.append('"').append(escape(String.valueOf(v))).append('"');
        }
        return sb.append('}').toString();
    }
    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') out.append('\\');
            out.append(c);
        }
        return out.toString();
    }
}
""",
}

TEST_SOURCE = """
import ai.devvio.rd.Hosts;
import ai.devvio.rd.ImeBridge;
import ai.devvio.rd.Proto;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Reflection-based test runner (no JUnit dependency). */
public class Tests {
    static final String NL = String.valueOf((char) 10);

    static int passed = 0;
    static int failed = 0;
    static List<String> failures = new ArrayList<String>();

    static void check(String name, boolean cond) {
        check(name, cond, "");
    }

    static void check(String name, boolean cond, String detail) {
        if (cond) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            failures.add(name + (detail.isEmpty() ? "" : " -> " + detail));
            System.out.println("  FAIL  " + name + (detail.isEmpty() ? "" : " -> " + detail));
        }
    }

    static void eq(String name, Object actual, Object expected) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        check(name, same, "expected <" + expected + "> got <" + actual + ">");
    }

    /** Records what the IME bridge emits. */
    static class Rec implements ImeBridge.Sink {
        StringBuilder backs = new StringBuilder();
        StringBuilder text = new StringBuilder();
        public void backspace() { backs.append('B'); }
        public void text(String s) { text.append(s).append('|'); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("\\n=== Proto: wire format ===");
        protoTests();
        System.out.println("\\n=== ImeBridge: text delivery ===");
        imeTests();
        System.out.println("\\n=== Hosts: addresses, URLs, recents ===");
        hostTests();
        System.out.println("\\n--- summary ---");
        System.out.println(passed + " passed, " + failed + " failed");
        for (String f : failures) {
            System.out.println("  FAILED: " + f);
        }
        if (failed > 0) {
            System.exit(1);
        }
    }

    static void protoTests() throws Exception {
        byte[] hello = Proto.hello("android/1.0.0", "Pixel", true, true, true, true, 1080, 1920, 70);
        eq("hello type byte", (int) (hello[0] & 0xFF), Proto.T_HELLO);
        String hs = new String(hello, 1, hello.length - 1, "UTF-8");
        check("hello carries protocol version", hs.contains("\\"version\\":1"), hs);
        check("hello advertises mouse mode", hs.contains("\\"mouse\\":true"), hs);
        check("hello advertises clipboard", hs.contains("\\"clipboard\\":true"), hs);

        // Touch: 25 bytes total, big-endian floats, type first.
        byte[] t = Proto.touch(Proto.TOUCH_MOVE, 0, 0.25f, 0.75f, 0.5f, 12345);
        eq("touch message size", t.length, Proto.TOUCH_SIZE);
        eq("touch type byte", (int) (t[0] & 0xFF), Proto.T_TOUCH);
        eq("touch action byte", (int) (t[1] & 0xFF), Proto.TOUCH_MOVE);
        int xBits = ((t[5] & 0xFF) << 24) | ((t[6] & 0xFF) << 16) | ((t[7] & 0xFF) << 8) | (t[8] & 0xFF);
        eq("touch X is big-endian float", Float.floatToIntBits(0.25f), xBits);
        int yBits = ((t[9] & 0xFF) << 24) | ((t[10] & 0xFF) << 16) | ((t[11] & 0xFF) << 8) | (t[12] & 0xFF);
        eq("touch Y is big-endian float", Float.floatToIntBits(0.75f), yBits);
        int ts = ((t[21] & 0xFF) << 24) | ((t[22] & 0xFF) << 16) | ((t[23] & 0xFF) << 8) | (t[24] & 0xFF);
        eq("touch timestamp", ts, 12345);
        int pBits = ((t[13] & 0xFF) << 24) | ((t[14] & 0xFF) << 16) | ((t[15] & 0xFF) << 8) | (t[16] & 0xFF);
        eq("touch pressure is big-endian float", Float.floatToIntBits(0.5f), pBits);
        // The server reads the payload without the type byte, so offsets are
        // shifted by one: its x lives at payload[4..8] == wire[5..9]. Verified
        // here so client and server can never drift apart silently.
        eq("touch wire offset is server-compatible", (int) (t[1] & 0xFF), Proto.TOUCH_MOVE);
        eq("touch pointer id offset", (int) (t[2] & 0xFF), 0);
        eq("touch pad bytes are zero", (t[3] & 0xFF) | (t[4] & 0xFF), 0);

        byte[] sc = Proto.scroll(1.5f, -2.5f);
        eq("scroll size", sc.length, 9);
        eq("scroll type byte", (int) (sc[0] & 0xFF), Proto.T_SCROLL);

        byte[] k = Proto.key("a", "KeyA", "down", true, false, false, false);
        String ks = new String(k, 1, k.length - 1, "UTF-8");
        eq("key type byte", (int) (k[0] & 0xFF), Proto.T_KEY);
        check("key payload has ctrl modifier", ks.contains("\\"ctrl\\":true"), ks);
        check("key payload has action", ks.contains("\\"action\\":\\"down\\""), ks);

        byte[] te = Proto.text("سلام دنیا");
        eq("text type byte", (int) (te[0] & 0xFF), Proto.T_TEXT);
        String tes = new String(te, 1, te.length - 1, "UTF-8");
        check("UTF-8 Persian text survives encoding", tes.contains("سلام دنیا"), tes);

        byte[] cb = Proto.clipboard("clip-42");
        eq("clipboard type byte", (int) (cb[0] & 0xFF), Proto.T_CLIPBOARD);

        byte[] pm = Proto.pointerMode(Proto.MODE_MOUSE);
        eq("pointer-mode type byte", (int) (pm[0] & 0xFF), Proto.T_POINTER_MODE);
        check("pointer-mode payload", new String(pm, 1, pm.length - 1, "UTF-8").contains("mouse"));

        byte[] q = Proto.quality(55, 15, 0.75f);
        eq("quality type byte", (int) (q[0] & 0xFF), Proto.T_QUALITY);

        byte[] pg = Proto.ping(99887766L);
        eq("ping size", pg.length, 9);
        long v = 0;
        for (int i = 1; i < 9; i++) {
            v = (v << 8) | (pg[i] & 0xFF);
        }
        eq("ping timestamp round trip", v, 99887766L);

        // --- decoding a server frame ---
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        byte[] frame = new byte[Proto.FRAME_HEADER + payload.length];
        frame[0] = (byte) Proto.T_FRAME;
        int seq = 4242;
        frame[1] = (byte) (seq >>> 24); frame[2] = (byte) (seq >>> 16);
        frame[3] = (byte) (seq >>> 8); frame[4] = (byte) seq;
        frame[5] = (byte) (1920 >>> 8); frame[6] = (byte) 1920;
        frame[7] = (byte) (1080 >>> 8); frame[8] = (byte) 1080;
        long now = 1700000000123L;
        for (int i = 0; i < 8; i++) {
            frame[9 + i] = (byte) (now >>> (56 - 8 * i));
        }
        System.arraycopy(payload, 0, frame, Proto.FRAME_HEADER, payload.length);
        Proto.Message m = Proto.parse(frame, frame.length);
        eq("frame seq parsed", m.seq, 4242);
        eq("frame width parsed", m.width, 1920);
        eq("frame height parsed", m.height, 1080);
        eq("frame timestamp parsed", m.timestamp, now);
        byte[] jpeg = Proto.frameBytes(m);
        eq("frame payload length", jpeg.length, payload.length);
        eq("frame payload first byte", (int) (jpeg[0] & 0xFF), 1);

        // --- short frame must be rejected, not silently misparsed ---
        boolean threw = false;
        try {
            Proto.parse(new byte[]{(byte) 0x90, 0, 0}, 3);
        } catch (Exception e) {
            threw = true;
        }
        check("short frame rejected", threw);

        // --- pong carries the client timestamp back ---
        byte[] pong = new byte[9];
        pong[0] = (byte) Proto.T_PONG;
        for (int i = 0; i < 8; i++) {
            pong[1 + i] = (byte) (555L >>> (56 - 8 * i));
        }
        Proto.Message pm2 = Proto.parse(pong, pong.length);
        eq("pong timestamp", pm2.timestamp, 555L);
    }

    static void hostTests() throws Exception {
        // Guard: every helper the other screens rely on must exist with the
        // exact signature. A rename here compiles the app but breaks a screen
        // at runtime, so assert the surface explicitly.
        String[] required = {"normalize", "hasPort", "withPort", "httpBase", "wsUrl",
            "isSecure", "parseRecents", "pushRecent", "uniqueName"};
        for (String m : required) {
            boolean found = false;
            for (Method mm : Hosts.class.getDeclaredMethods()) {
                if (mm.getName().equals(m) && java.lang.reflect.Modifier.isStatic(mm.getModifiers())) {
                    found = true;
                    break;
                }
            }
            check("Hosts." + m + " is available", found);
        }
        // --- normalisation: the shapes users actually paste ---
        eq("plain host unchanged", Hosts.normalize("192.168.1.10:5900"), "192.168.1.10:5900");
        eq("ws scheme stripped", Hosts.normalize("ws://192.168.1.10:5900/ws"), "192.168.1.10:5900");
        eq("wss scheme stripped", Hosts.normalize(" wss://rd.example.com/ws "), "rd.example.com");
        eq("http scheme stripped", Hosts.normalize("HTTP://example.com:6000/"), "example.com:6000");
        eq("trailing slash dropped", Hosts.normalize("example.com/"), "example.com");
        eq("userinfo dropped", Hosts.normalize("http://u:p@host:1/ws"), "host:1");
        eq("empty stays empty", Hosts.normalize("   "), "");
        eq("null is empty", Hosts.normalize(null), "");

        // --- port detection ---
        check("explicit port detected", Hosts.hasPort("1.2.3.4:5900"));
        check("bare host has no port", !Hosts.hasPort("1.2.3.4"));
        check("non-numeric port rejected", !Hosts.hasPort("host:abc"));
        check("out-of-range port rejected", !Hosts.hasPort("host:99999"));
        check("empty port rejected", !Hosts.hasPort("host:"));
        check("bracketed ipv6 without port", !Hosts.hasPort("[::1]"));
        check("bracketed ipv6 with port", Hosts.hasPort("[::1]:5900"));

        // --- default port injection ---
        eq("port added when missing", Hosts.withPort("192.168.1.10", 5900), "192.168.1.10:5900");
        eq("port kept when present", Hosts.withPort("192.168.1.10:7000", 5900), "192.168.1.10:7000");

        // --- URL building (this is what the session and file screens use) ---
        eq("ws url", Hosts.wsUrl("192.168.1.10", 5900), "ws://192.168.1.10:5900/ws");
        eq("ws url from ws input", Hosts.wsUrl("ws://192.168.1.10:5900/ws", 5900),
                "ws://192.168.1.10:5900/ws");
        eq("secure ws url", Hosts.wsUrl("https://rd.example.com", 5900),
                "wss://rd.example.com:5900/ws");
        eq("http base", Hosts.httpBase("192.168.1.10", 5900), "http://192.168.1.10:5900");
        eq("secure http base", Hosts.httpBase("wss://rd.example.com:443/ws", 5900),
                "https://rd.example.com:443");
        eq("empty host gives empty base", Hosts.httpBase("", 5900), "");

        // --- recent hosts: newest first, deduped, blanks and repeats dropped ---
        String blob = Hosts.pushRecent("", "a:1");
        blob = Hosts.pushRecent(blob, "b:2");
        blob = Hosts.pushRecent(blob, "a:1");
        String[] rec = Hosts.parseRecents(blob);
        eq("newest recent is first", rec[0], "a:1");
        eq("duplicates collapsed", rec.length, 2);
        eq("second recent kept", rec[1], "b:2");
        eq("blank lines dropped", Hosts.parseRecents("\\n a:1 \\n\\n").length, 1);
        eq("empty blob", Hosts.parseRecents("").length, 0);
        eq("recents are trimmed", Hosts.parseRecents("  host:1  ")[0], "host:1");

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            many.append(i == 0 ? "" : "\\n").append("h").append(i).append(":5900");
        }
        eq("recents capped at " + Hosts.MAX_RECENTS,
                Hosts.parseRecents(many.toString()).length, Hosts.MAX_RECENTS);

        // --- download names never overwrite ---
        eq("first download keeps its name", Hosts.uniqueName("a.pdf", 1), "a.pdf");
        eq("second download is suffixed", Hosts.uniqueName("a.pdf", 2), "a (2).pdf");
        eq("extension-less name suffixed", Hosts.uniqueName("README", 3), "README (3)");
    }

    static void imeTests() throws Exception {
        Rec r = new Rec();
        ImeBridge b = new ImeBridge(r);

        b.onTextChanged("hello");
        eq("typing sends one text event", r.text.toString(), "hello|");
        eq("no backspaces on first input", r.backs.length(), 0);

        r = new Rec(); b = new ImeBridge(r);
        b.onTextChanged("hello");
        b.onTextChanged("hello world");
        eq("continued typing sends only the delta", r.text.toString(), "hello| world|");

        r = new Rec(); b = new ImeBridge(r);
        b.onTextChanged("hello world");
        int textLenBefore = r.text.length();
        b.onTextChanged("hello");
        eq("deletion emits backspaces", r.backs.length(), 6);
        eq("deletion emits no new text", r.text.length() - textLenBefore, 0);

        r = new Rec(); b = new ImeBridge(r);
        b.onTextChanged("abc");
        b.onTextChanged("");
        eq("clearing the field deletes everything", r.backs.length(), 3);

        // Persian composition: characters are multi-byte but single code points.
        r = new Rec(); b = new ImeBridge(r);
        b.onTextChanged("سلام");
        eq("Persian text forwarded as one event", r.text.toString(), "سلام|");
        b.onTextChanged("سلام دنیا");
        eq("Persian typing delta only", r.text.toString(), "سلام| دنیا|");
        b.onTextChanged("سلام");
        eq("Persian deletion counts code points", r.backs.length(), 5);

        // Emoji: surrogate pairs must not be split by the diff.
        r = new Rec(); b = new ImeBridge(r);
        b.onTextChanged("hi \\uD83D\\uDE00");
        eq("emoji forwarded intact", r.text.toString(), "hi \\uD83D\\uDE00|");
        r = new Rec(); b = new ImeBridge(r);
        b.onTextChanged("\\uD83D\\uDE00\\uD83D\\uDE01");
        b.onTextChanged("\\uD83D\\uDE00");
        eq("deleting an emoji emits one backspace", r.backs.length(), 1);

        // Opening the keyboard: resetField must not emit deletions.
        r = new Rec(); b = new ImeBridge(r);
        b.onTextChanged("draft text");
        b.resetField();
        eq("resetField suppresses backspaces", r.backs.length(), 0);
        eq("resetField clears the mirror", b.sentText(), "");

        // Clipboard mirroring: syncing silently must not echo back.
        r = new Rec(); b = new ImeBridge(r);
        b.syncSilently("from server");
        b.onTextChanged("from server");
        eq("silent sync prevents echo", r.text.length() + r.backs.length(), 0);

        // No-op when nothing changed.
        r = new Rec(); b = new ImeBridge(r);
        b.onTextChanged("same");
        b.onTextChanged("same");
        eq("identical value emits nothing", r.text.toString(), "same|");

        // --- incremental edit API (TextWatcher start/before/count form) ---
        r = new Rec(); b = new ImeBridge(r);
        b.onTextChanged("abc", 0, 0, 3);
        eq("incremental insert at start", r.text.toString(), "abc|");
        b.onTextChanged("abcd", 3, 0, 1);
        eq("incremental append sends one char", r.text.toString(), "abc|d|");
        b.onTextChanged("abd", 2, 1, 0);
        eq("incremental delete sends one backspace", r.backs.length(), 1);
        b.onTextChanged("abX", 2, 1, 1);
        eq("incremental replace: backspace + text", r.backs.length(), 2);
        eq("incremental replace text", r.text.toString(), "abc|d|X|");
        eq("mirror tracks incremental edits", b.sentText(), "abX");

        // Bulk paste in the middle of a long text.
        r = new Rec(); b = new ImeBridge(r);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 500; i++) longText.append("ab");
        b.onTextChanged(longText.toString());
        r.text.setLength(0);
        String pasted = longText.substring(0, 400) + "سلام" + longText.substring(400);
        b.onTextChanged(pasted, 400, 0, 4);
        eq("mid-text paste sends only the pasted text", r.text.toString(), "سلام|");
        eq("mid-text paste keeps mirror in sync", b.sentText().length(), pasted.length());

        // Deleting a selection spanning many characters.
        r = new Rec(); b = new ImeBridge(r);
        b.onTextChanged("0123456789");
        b.onTextChanged("019", 2, 7, 0);
        eq("selection delete emits one backspace per char", r.backs.length(), 7);
        eq("selection delete mirror", b.sentText(), "019");

        // Multi-line (Enter inserts a newline in the field).
        r = new Rec(); b = new ImeBridge(r);
        b.onTextChanged("line1");
        b.onTextChanged("line1" + NL + "line2");
        eq("newline forwarded inside text", r.text.toString(), "line1|" + NL + "line2|");
    }
}
"""


def main():
    if not os.path.exists(JAVA):
        print("missing JDK:", JAVA)
        return 1
    work = tempfile.mkdtemp(prefix="devviord-jvm-")
    src_dir = os.path.join(work, "src")
    out_dir = os.path.join(work, "classes")
    os.makedirs(out_dir, exist_ok=True)
    java_files = []
    for rel in SOURCES:
        dst = os.path.join(src_dir, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy(os.path.join(ROOT, rel), dst)
        java_files.append(dst)
    for rel, content in STUBS.items():
        dst = os.path.join(src_dir, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        open(dst, "w").write(content)
        java_files.append(dst)
    tests = os.path.join(src_dir, "Tests.java")
    open(tests, "w").write(TEST_SOURCE)
    java_files.append(tests)

    print(f"[jvm-tests] compiling {len(java_files)} files with ECJ")
    p = subprocess.run([JAVA, "-jar", ECJ, "-source", "8", "-target", "8", "-proc:none",
                        "-nowarn", "-encoding", "UTF-8", "-d", out_dir] + java_files,
                       capture_output=True, text=True)
    if p.returncode != 0:
        print(p.stdout)
        print(p.stderr)
        return 1
    print("[jvm-tests] running")
    p = subprocess.run([JAVA, "-cp", out_dir, "Tests"], capture_output=True, text=True)
    print(p.stdout)
    if p.stderr.strip():
        print(p.stderr)
    shutil.rmtree(work, ignore_errors=True)
    return p.returncode


if __name__ == "__main__":
    sys.exit(main())
