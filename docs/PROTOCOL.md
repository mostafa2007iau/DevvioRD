# DevvioRD wire protocol (v1)

The Android app, the web client and the server speak one small binary protocol
over a single WebSocket. Frames and control messages share the socket; the first
byte of every message is its type.

```
+--------+---------------------------+
| type   | payload (binary or JSON)  |
| 1 byte | variable                  |
+--------+---------------------------+
```

All integers are **big endian**. Strings inside JSON payloads are UTF-8.
WebSocket binary frames carry these messages; text frames are not used.

## Handshake

The WebSocket handshake carries authentication. Any of these work:

| Transport | How |
| --- | --- |
| `X-DevvioRD-Token` header | preferred for native clients |
| `?token=...` query parameter | used by browsers, which cannot set WS headers |
| `devviord_token` cookie | convenience for same-origin pages |

A rejected handshake returns HTTP `401`.

The first application message must be `HELLO`:

```json
{"version":1,"client":"android/1.0.0 (Android 14)",
 "device":"Pixel 7","caps":{"touch":true,"mouse":true,"clipboard":true,
 "files":true,"multitouch":true,"keyboard":true,"jpegQuality":70}}
```

The server answers `HELLO_OK` and then starts streaming frames:

```json
{"version":1,"server":"DevvioRD/1.0.0","hostname":"workstation","os":"linux/amd64",
 "sessionId":"9d4911fc","width":1920,"height":1080,"scale":1,"fps":20,"quality":70,
 "pointerMode":"touch","needsAuth":true,
 "backend":{"capture":"ffmpeg-x11grab","input":"x11:/usr/bin/xdotool","clipboard":"x11:xclip"}}
```

Any message other than `PING` before `HELLO` is ignored and drops the session.

## Message types

### Client → server

| Type | Name | Payload |
| --- | --- | --- |
| `0x01` | HELLO | JSON (see above) |
| `0x10` | TOUCH | 24-byte fixed struct |
| `0x11` | SCROLL | `float32 dx`, `float32 dy` (1.0 ≈ one page) |
| `0x14` | KEY | `{"key":"Enter","code":"Enter","action":"down","mods":{"ctrl":true}}` |
| `0x15` | TEXT | `{"text":"سلام دنیا"}` — committed IME text, any Unicode |
| `0x20` | CLIPBOARD | `{"kind":"text","data":"..."}` |
| `0x21` | POINTER_MODE | `{"mode":"touch"}` or `{"mode":"mouse"}` |
| `0x40` | PING | `int64` client millis |
| `0x41` | QUALITY | `{"quality":70,"fps":20,"scale":1.0}` (all optional, **global**) |
| `0x42` | PAUSE | `{"paused":true}` — stop/resume frames for **this session only** (background tab) |

### Server → client

| Type | Name | Payload |
| --- | --- | --- |
| `0x81` | HELLO_OK | JSON |
| `0x90` | FRAME | 16-byte header + JPEG bytes |
| `0x92` | CURSOR | `{"x":0.5,"y":0.5,"visible":true}` (normalized) |
| `0xA0` | CLIP | `{"kind":"text","data":"..."}` |
| `0xB0` | STATUS | `{"level":"info","message":"...","code":"hello"}` |
| `0xC0` | SCREEN | `{"width":1920,"height":1080,"scale":1}` when geometry changes |
| `0xC1` | PONG | `int64` echoed client millis → RTT |
| `0xEE` | ERROR | `{"level":"error","message":"...","code":"version"}` |

## TOUCH layout (fixed 24-byte payload)

```
offset size field
------ ---- ------------------------------------------
  0     1   action: 0=down 1=move 2=up 3=cancel
  1     1   pointer id
  2     2   padding (zero)
  4     4   x, float32, normalized 0..1
  8     4   y, float32, normalized 0..1
 12     4   pressure, float32 0..1
 16     4   radius, float32
 20     4   client timestamp (millis, wraps)
```

Note the offsets are relative to the payload *after* the type byte, so on the
wire `x` starts at byte 5. `scripts/jvm_tests.py` asserts this mapping so the
client and server can never drift apart silently.

## FRAME layout

```
offset size field
------ ---- ---------------------------------
  0     1   type (0x90)
  1     4   sequence number
  5     2   width (pixels)
  7     2   height (pixels)
  9     8   server timestamp (millis)
 17     n   JPEG bytes (SOI .. EOI)
```

Sequence numbers increase monotonically. Clients should render on a
latest-wins basis: dropping a stale frame is better than decoding a backlog.

## Delivery semantics

* **Video** is lossy and real-time: if a client cannot keep up the server drops
  the oldest queued frame rather than delaying newer ones.
* **Control** messages (status, clipboard, cursor, pong) use a separate queue
  and are never starved by video.
* **Heartbeat**: clients ping every 5 s; the server also sends WebSocket pings
  every 15 s. A session with no inbound traffic for 90 s is closed.

## HTTP endpoints

All file and clipboard endpoints take the same token (`X-DevvioRD-Token`
header, `?token=`, or cookie) and support CORS for the web client.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/` | embedded web client |
| `GET` | `/healthz` | liveness probe |
| `GET` | `/api/info` | server, backend and geometry information (no auth) |
| `GET` | `/api/pair` | pairing info + QR matrix (no auth) |
| `GET` | `/snapshot.jpg` | single JPEG of the current screen |
| `GET` | `/api/files?dir=` | directory listing |
| `POST` | `/api/upload/begin` | `{"name","dir","size"}` → `{"id","offset"}` |
| `POST` | `/api/upload/chunk?id=&offset=` | raw bytes (≤ 4 MiB) |
| `POST` | `/api/upload/finish` | `{"id"}` → final entry (size verified) |
| `GET` | `/api/download?path=` | file download with `Content-Disposition` |
| `GET/POST` | `/api/clipboard` | read / write the host clipboard |
| `POST` | `/api/mkdir` | `{"dir","name"}` |
| `POST` | `/api/rename` | `{"path","newName"}` |
| `POST` | `/api/delete` | `{"path"}` |

Uploads are resumable: calling `/api/upload/begin` with the same `uploadId`
returns the number of bytes already stored, so a client that lost its
connection can continue instead of restarting.

## Versioning

`HELLO.version` is the major version. The server rejects a mismatch with
`ERROR code="version"` and keeps the socket open so the client can upgrade
gracefully.
