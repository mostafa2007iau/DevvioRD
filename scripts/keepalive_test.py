#!/usr/bin/env python3
"""End-to-end keepalive check against a live devviord instance.

The server sends a WebSocket ping every 15 s and closes a connection that stays
silent for 90 s. Browsers answer pings automatically; the Android client did not,
so an idle session died after a minute and a half. This probe reproduces the
client side of that contract:

  * a client that answers pings must stay connected past several ping cycles,
  * the server must actually be pinging (otherwise "staying connected" proves
    nothing).

Usage:
  python3 scripts/keepalive_test.py [--host 127.0.0.1] [--port 5900]
                                    [--token TOKEN] [--seconds 40]
"""
import base64
import os
import socket
import struct
import sys
import time

OP_CONT, OP_TEXT, OP_BIN, OP_CLOSE, OP_PING, OP_PONG = 0x0, 0x1, 0x2, 0x8, 0x9, 0xA


def handshake(host, port, path, timeout=5):
    """Opens a raw WebSocket and returns the socket once the upgrade succeeds."""
    s = socket.create_connection((host, port), timeout=timeout)
    key = base64.b64encode(os.urandom(16)).decode()
    s.sendall(
        f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\n"
        f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n\r\n".encode())
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = s.recv(1)
        if not chunk:
            raise SystemExit("FAIL: server closed during the handshake")
        buf += chunk
    status = buf.split(b"\r\n")[0]
    if b"101" not in status:
        raise SystemExit(f"FAIL: upgrade rejected ({status.decode(errors='replace')})")
    return s


def masked(op, payload):
    """Client-to-server frames must be masked."""
    mask = os.urandom(4)
    return (bytes([0x80 | op, 0x80 | len(payload)]) + mask
            + bytes(b ^ mask[i & 3] for i, b in enumerate(payload)))


def read_frame(sock):
    """Returns (opcode, payload) or None when the peer went away."""
    hdr = sock.recv(2)
    if len(hdr) < 2:
        return None
    b0, b1 = hdr[0], hdr[1]
    ln = b1 & 0x7F
    if ln == 126:
        ln = struct.unpack(">H", sock.recv(2))[0]
    elif ln == 127:
        ln = struct.unpack(">Q", sock.recv(8))[0]
    body = b""
    while len(body) < ln:
        chunk = sock.recv(ln - len(body))
        if not chunk:
            return None
        body += chunk
    return b0 & 0x0F, body


def pump(sock, seconds, answer_pings):
    """Reads for `seconds`. Returns (alive, pings_seen)."""
    sock.settimeout(1.0)
    end = time.time() + seconds
    pings = 0
    while time.time() < end:
        try:
            frame = read_frame(sock)
        except socket.timeout:
            continue
        if frame is None:
            return False, pings
        op, payload = frame
        if op == OP_PING:
            pings += 1
            if answer_pings:
                sock.sendall(masked(OP_PONG, payload))
        elif op == OP_CLOSE:
            return False, pings
    return True, pings


def main():
    args = sys.argv[1:]
    host, port, token, seconds = "127.0.0.1", 5900, "", 40
    for i, a in enumerate(args):
        if a == "--host" and i + 1 < len(args):
            host = args[i + 1]
        elif a == "--port" and i + 1 < len(args):
            port = int(args[i + 1])
        elif a == "--token" and i + 1 < len(args):
            token = args[i + 1]
        elif a == "--seconds" and i + 1 < len(args):
            seconds = int(args[i + 1])
    path = "/ws" + (("?token=" + token) if token else "")
    print(f"=== DevvioRD keepalive -> {host}:{port} ({seconds}s) ===")

    sock = handshake(host, port, path)
    sock.sendall(masked(OP_TEXT, b'{"version":1,"client":"keepalive-probe",'
                                  b'"mouse":true,"clipboard":true}'))
    alive, pings = pump(sock, seconds, answer_pings=True)
    sock.close()

    ok = True
    if alive:
        print(f"  PASS  ping-answering client stayed connected for {seconds}s")
    else:
        print("  FAIL  a well-behaved client was disconnected")
        ok = False
    # Two ping cycles at 15 s; anything less over 40 s means pings stopped.
    if pings >= 2:
        print(f"  PASS  server sent {pings} pings (keepalive is active)")
    else:
        print(f"  FAIL  expected at least 2 pings, saw {pings}")
        ok = False

    print("--- summary ---")
    print("keepalive verified" if ok else "keepalive FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
