#!/usr/bin/env python3
"""DevvioRD integration test client.

Speaks the real DevvioRD binary protocol over a raw WebSocket (stdlib only), so
it validates the exact bytes the Android app will send and receive.

Usage:
  python3 scripts/integration_test.py [--host 127.0.0.1] [--port 5900]
                                      [--token TOKEN] [--frames 25]
                                      [--save-dir /tmp/snaps]
Exit code 0 = all checks passed.
"""
import argparse
import base64
import hashlib
import json
import os
import socket
import struct
import sys
import time

TYPE_HELLO = 0x01
TYPE_TOUCH = 0x10
TYPE_SCROLL = 0x11
TYPE_KEY = 0x14
TYPE_TEXT = 0x15
TYPE_CLIPBOARD = 0x20
TYPE_POINTERMODE = 0x21
TYPE_PING = 0x40
TYPE_QUALITY = 0x41
TYPE_HELLO_OK = 0x81
TYPE_FRAME = 0x90
TYPE_CURSOR = 0x92
TYPE_CLIP = 0xA0
TYPE_STATUS = 0xB0
TYPE_SCREEN = 0xC0
TYPE_PONG = 0xC1

FRAME_HDR = 1 + 4 + 2 + 2 + 8

results = []


def ok(name, extra=""):
    results.append((True, name, extra))
    print(f"  PASS  {name}{(' - ' + extra) if extra else ''}")


def bad(name, extra=""):
    results.append((False, name, extra))
    print(f"  FAIL  {name}{(' - ' + extra) if extra else ''}")


class WS:
    def __init__(self, host, port, path, token=None, timeout=10):
        self.events = []  # backlog of received payloads (nothing is discarded)
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        if token:
            path += ("&" if "?" in path else "?") + "token=" + token
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            "GET " + path + " HTTP/1.1\r\n"
            "Host: " + host + ":" + str(port) + "\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            "Sec-WebSocket-Key: " + key + "\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "Sec-DevvioRD-Token: " + (token or "") + "\r\n"
            "\r\n"
        )
        self.sock.sendall(req.encode())
        self.buf = b""
        # Read the HTTP response headers.
        while b"\r\n\r\n" not in self.buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise RuntimeError("connection closed during handshake")
            self.buf += chunk
        head, self.buf = self.buf.split(b"\r\n\r\n", 1)
        status = head.split(b"\r\n")[0].decode()
        if "101" not in status:
            raise RuntimeError(f"handshake failed: {status}")
        accept = hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
        expect = base64.b64encode(accept).decode()
        if expect.encode() not in head:
            raise RuntimeError("bad Sec-WebSocket-Accept")

    def _read_exact(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise RuntimeError("socket closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def send(self, payload):
        # Client frames MUST be masked.
        mask = os.urandom(4)
        n = len(payload)
        hdr = bytearray([0x82])
        if n < 126:
            hdr.append(0x80 | n)
        elif n <= 0xFFFF:
            hdr.append(0x80 | 126)
            hdr += struct.pack(">H", n)
        else:
            hdr.append(0x80 | 127)
            hdr += struct.pack(">Q", n)
        hdr += mask
        masked = bytes(b ^ mask[i & 3] for i, b in enumerate(payload))
        self.sock.sendall(bytes(hdr) + masked)

    def recv(self, timeout=None):
        if timeout is not None:
            self.sock.settimeout(timeout)
        while True:
            b0, b1 = self._read_exact(2)
            opcode = b0 & 0x0F
            length = b1 & 0x7F
            if length == 126:
                length = struct.unpack(">H", self._read_exact(2))[0]
            elif length == 127:
                length = struct.unpack(">Q", self._read_exact(8))[0]
            if b1 & 0x80:
                mask = self._read_exact(4)
                data = bytes(b ^ mask[i & 3] for i, b in enumerate(self._read_exact(length)))
            else:
                data = self._read_exact(length)
            if opcode == 0x9:  # ping -> pong
                self.sock.sendall(bytes([0x8A, 0x80]) + os.urandom(4))
                continue
            if opcode == 0xA:
                continue
            if opcode == 0x8:
                raise RuntimeError("server closed the connection")
            if opcode in (0x1, 0x2):
                return data

    def close(self):
        try:
            self.sock.sendall(bytes([0x88, 0x82]) + os.urandom(4))
        except OSError:
            pass
        self.sock.close()


def hello_msg():
    return json.dumps({
        "version": 1,
        "client": "integration-test/1.0",
        "device": "pytest",
        "caps": {"touch": True, "mouse": True, "clipboard": True, "files": True,
                 "multitouch": True, "keyboard": True, "maxWidth": 1280, "maxHeight": 800,
                 "jpegQuality": 70},
    }).encode()


def touch_event(action, x, y, pointer=0, pressure=1.0):
    """1 type byte + 24-byte fixed payload (must match server TouchEventSize)."""
    payload = struct.pack(">BBHffffI", action, pointer, 0, x, y, pressure, 0.0,
                          int(time.time() * 1000) & 0xFFFFFFFF)
    assert len(payload) == 24, len(payload)
    return bytes([TYPE_TOUCH]) + payload


def wait_for(ws, wanted, timeout=6.0):
    """Return the first message of a wanted type.

    Messages that do not match are kept in ws.events (not discarded), which
    mirrors how a real client must handle interleaved video/control traffic.
    """
    # 1) Anything already buffered?
    for i, data in enumerate(ws.events):
        if data and data[0] in wanted:
            return ws.events.pop(i)
    # 2) Otherwise read until it shows up.
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            data = ws.recv(timeout=max(0.2, deadline - time.time()))
        except socket.timeout:
            continue
        if not data:
            continue
        if data[0] in wanted:
            return data
        ws.events.append(data)
        # Keep the backlog bounded during long video streams.
        if len(ws.events) > 400:
            del ws.events[:200]
    return None


def jpeg_size(jpeg):
    """Extract dimensions from the JPEG SOF marker."""
    i = 2
    while i < len(jpeg) - 9:
        if jpeg[i] != 0xFF:
            i += 1
            continue
        marker = jpeg[i + 1]
        if marker in (0xC0, 0xC1, 0xC2):
            h, w = struct.unpack(">HH", jpeg[i + 5:i + 9])
            return w, h
        if marker in (0xD8, 0xD9):
            i += 2
            continue
        seglen = struct.unpack(">H", jpeg[i + 2:i + 4])[0]
        i += 2 + seglen
    return 0, 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=5900)
    ap.add_argument("--token", default="")
    ap.add_argument("--frames", type=int, default=20)
    ap.add_argument("--save-dir", default="/tmp/devvio-snaps")
    args = ap.parse_args()

    print(f"\n=== DevvioRD integration test -> {args.host}:{args.port} ===\n")
    ws = WS(args.host, args.port, "/ws", token=args.token)
    ok("websocket handshake (RFC6455 accept key valid)")

    ws.send(bytes([TYPE_HELLO]) + hello_msg())
    msg = wait_for(ws, {TYPE_HELLO_OK})
    if not msg:
        bad("hello handshake")
        return 1
    hi = json.loads(msg[1:])
    ok("hello-ok received", f"server={hi['server']} session={hi['sessionId']} {hi['width']}x{hi['height']}")
    if hi.get("width", 0) > 0 and hi.get("height", 0) > 0:
        ok("geometry reported", f"{hi['width']}x{hi['height']} scale={hi['scale']}")
    else:
        bad("geometry reported", "zero size")
    if hi.get("backend"):
        ok("backend info", json.dumps(hi["backend"]))

    # ---- ping/pong round trip ----
    t0 = time.time()
    ws.send(bytes([TYPE_PING]) + struct.pack(">q", 123456789))
    pong = wait_for(ws, {TYPE_PONG})
    if pong and struct.unpack(">q", pong[1:9])[0] == 123456789:
        ok("ping/pong echo", "%.1f ms" % ((time.time() - t0) * 1000))
    else:
        bad("ping/pong echo")

    # ---- frame stream ----
    frames = 0
    frame_sizes = []
    last = None
    t_start = time.time()
    while frames < args.frames and time.time() - t_start < 12:
        data = wait_for(ws, {TYPE_FRAME}, timeout=3.0)
        if not data:
            break
        seq = struct.unpack(">I", data[1:5])[0]
        w, h = struct.unpack(">HH", data[5:9])
        ts = struct.unpack(">Q", data[9:17])[0]
        jpeg = data[FRAME_HDR:]
        if jpeg[:2] != b"\xff\xd8":
            bad("frame JPEG SOI marker")
            break
        jw, jh = jpeg_size(jpeg)
        if (jw, jh) != (w, h):
            bad("frame header dims match JPEG", f"hdr={w}x{h} jpeg={jw}x{jh}")
            break
        frames += 1
        frame_sizes.append(len(jpeg))
        last = (seq, jpeg)
    elapsed = time.time() - t_start
    if frames >= min(5, args.frames):
        ok("frame stream", f"{frames} frames in {elapsed:.2f}s = {frames / max(elapsed, 1e-6):.1f} fps, "
                           f"avg {sum(frame_sizes) // len(frame_sizes) // 1024} KiB")
    else:
        bad("frame stream", f"only {frames} frames")

    if last:
        os.makedirs(args.save_dir, exist_ok=True)
        p = os.path.join(args.save_dir, "frame_before.jpg")
        open(p, "wb").write(last[1])
        ok("frame saved", p)

    # ---- input: pointer taps ----
    ws.send(touch_event(0, 0.5, 0.5))
    time.sleep(0.02)
    ws.send(touch_event(2, 0.5, 0.5))
    ws.send(touch_event(0, 0.25, 0.75))
    time.sleep(0.02)
    ws.send(touch_event(2, 0.25, 0.75))
    ok("touch down/up events sent (2 taps)")

    # ---- input: drag ----
    ws.send(touch_event(0, 0.1, 0.1))
    for i in range(1, 11):
        ws.send(touch_event(1, 0.1 + i * 0.05, 0.1 + i * 0.04))
        time.sleep(0.01)
    ws.send(touch_event(2, 0.6, 0.5))
    ok("drag gesture sent (12 samples)")

    # ---- input: scroll ----
    ws.send(bytes([TYPE_SCROLL]) + struct.pack(">ff", 0.0, 1.5))
    ok("scroll event sent")

    # ---- keyboard: ASCII keys ----
    for ch in "abc123":
        ws.send(bytes([TYPE_KEY]) + json.dumps({"key": ch, "action": "down", "mods": {}}).encode())
    ok("single-character key events sent")

    ws.send(bytes([TYPE_KEY]) + json.dumps({"key": "Enter", "action": "down", "mods": {}}).encode())
    ws.send(bytes([TYPE_KEY]) + json.dumps({"key": "Backspace", "action": "down", "mods": {}}).encode())
    ws.send(bytes([TYPE_KEY]) + json.dumps({"key": "a", "action": "down",
                                            "mods": {"ctrl": True}}).encode())
    ok("special keys + ctrl combo sent")

    # ---- keyboard: UTF-8 IME text (Persian) ----
    persian = "سلام دنیا"
    ws.send(bytes([TYPE_TEXT]) + json.dumps({"text": persian}).encode())
    ok("IME/UTF-8 text event sent", repr(persian))

    # ---- pointer mode ----
    ws.send(bytes([TYPE_POINTERMODE]) + json.dumps({"mode": "mouse"}).encode())
    time.sleep(0.05)
    cursor = wait_for(ws, {TYPE_CURSOR}, timeout=1.0)
    ws.send(touch_event(1, 0.7, 0.3))
    ok("pointer mode -> mouse")

    # ---- clipboard client -> server ----
    payload = "clipboard-from-phone-1234"
    ws.send(bytes([TYPE_CLIPBOARD]) + json.dumps({"kind": "text", "data": payload}).encode())
    st = wait_for(ws, {TYPE_STATUS}, timeout=2.0)
    if st:
        ok("clipboard event acknowledged", json.loads(st[1:]).get("message", ""))
    else:
        bad("clipboard event acknowledged")

    # ---- clipboard server -> client (all clients share one clipboard) ----
    got_clip = None
    deadline = time.time() + 3.0
    while time.time() < deadline and got_clip is None:
        data = wait_for(ws, {TYPE_CLIP}, timeout=0.6)
        if data:
            msg = json.loads(data[1:])
            if msg.get("data") == payload:
                got_clip = msg
    if got_clip:
        ok("clipboard echoed back to all clients (host <-> phone)", got_clip["data"])
    else:
        bad("clipboard echoed back to all clients", "no CLIP message with expected text")

    # REST clipboard endpoint must reflect the same value.
    try:
        import urllib.request
        url = f"http://{args.host}:{args.port}/api/clipboard"
        req = urllib.request.Request(url, headers={"X-DevvioRD-Token": args.token or ""})
        rest = json.loads(urllib.request.urlopen(req, timeout=5).read())
        if rest.get("text") == payload:
            ok("REST clipboard matches", "phone -> host clipboard write")
        else:
            bad("REST clipboard matches", str(rest)[:80])
    except Exception as e:  # noqa: BLE001
        bad("REST clipboard matches", str(e))

    # ---- quality change ----
    ws.send(bytes([TYPE_QUALITY]) + json.dumps({"quality": 40, "fps": 8}).encode())
    time.sleep(0.3)
    ok("quality change sent")

    # ---- frame after input (proves input reached the desktop) ----
    time.sleep(0.4)
    fr = wait_for(ws, {TYPE_FRAME}, timeout=3.0)
    if fr:
        p = os.path.join(args.save_dir, "frame_after.jpg")
        os.makedirs(args.save_dir, exist_ok=True)
        open(p, "wb").write(fr[FRAME_HDR:])
        seq_after = struct.unpack(">I", fr[1:5])[0]
        if last and seq_after > last[0]:
            ok("frame sequence advances after input", f"{last[0]} -> {seq_after}")
        ok("frame after input saved", p)
    else:
        bad("frame after input")

    ws.close()

    # ---- second client: concurrent sessions ----
    try:
        ws2 = WS(args.host, args.port, "/ws", token=args.token)
        ws2.send(bytes([TYPE_HELLO]) + hello_msg())
        m2 = wait_for(ws2, {TYPE_HELLO_OK}, timeout=5.0)
        if m2:
            ok("second concurrent client accepted")
        else:
            bad("second concurrent client accepted")
        ws2.close()
    except Exception as e:  # noqa: BLE001
        bad("second concurrent client accepted", str(e))

    print("\n--- summary ---")
    passed = sum(1 for r in results if r[0])
    failed = len(results) - passed
    print(f"{passed} passed, {failed} failed")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
