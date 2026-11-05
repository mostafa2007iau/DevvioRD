/*
 * DevvioRD web client.
 *
 * Mirrors the Android app: same binary protocol, same touch/mouse semantics,
 * same IME text-diffing approach, same clipboard + file transfer REST calls.
 * Written with no dependencies so it also runs from `file://` for debugging.
 *
 * Layout of this file:
 *   1. protocol encoding (byte-for-byte identical to the Android client)
 *   2. connection lifecycle
 *   3. video decoding
 *   4. pointer / keyboard input
 *   5. clipboard and file transfer
 *   6. UI glue (toolbar, stats panel, drawer, pairing, help)
 */
(function () {
  'use strict';

  // ------------------------------------------------------------ protocol
  var T = {
    HELLO: 0x01, TOUCH: 0x10, SCROLL: 0x11, KEY: 0x14, TEXT: 0x15,
    CLIPBOARD: 0x20, POINTER_MODE: 0x21, PING: 0x40, QUALITY: 0x41, PAUSE: 0x42,
    HELLO_OK: 0x81, FRAME: 0x90, CURSOR: 0x92, CLIP: 0xA0, STATUS: 0xB0,
    SCREEN: 0xC0, PONG: 0xC1, ERROR: 0xEE
  };
  var FRAME_HEADER = 17;
  var TOUCH_DOWN = 0, TOUCH_MOVE = 1, TOUCH_UP = 2, TOUCH_CANCEL = 3;
  var MODE_TOUCH = 'touch', MODE_MOUSE = 'mouse';

  var enc = new TextEncoder();
  var dec = new TextDecoder();

  function jsonMsg(type, obj) {
    var body = enc.encode(JSON.stringify(obj));
    var out = new Uint8Array(body.length + 1);
    out[0] = type;
    out.set(body, 1);
    return out;
  }

  function touchMsg(action, x, y, pressure) {
    var buf = new ArrayBuffer(25);
    var dv = new DataView(buf);
    var u8 = new Uint8Array(buf);
    u8[0] = T.TOUCH; u8[1] = action; u8[2] = 0; u8[3] = 0; u8[4] = 0;
    dv.setFloat32(5, x, false);
    dv.setFloat32(9, y, false);
    dv.setFloat32(13, pressure === undefined ? 1 : pressure, false);
    dv.setFloat32(17, 0, false);
    dv.setUint32(21, Date.now() & 0xFFFFFFFF, false);
    return new Uint8Array(buf);
  }

  function scrollMsg(dx, dy) {
    var buf = new ArrayBuffer(9);
    var dv = new DataView(buf);
    new Uint8Array(buf)[0] = T.SCROLL;
    dv.setFloat32(1, dx, false);
    dv.setFloat32(5, dy, false);
    return new Uint8Array(buf);
  }

  function pingMsg(ms) {
    var buf = new ArrayBuffer(9);
    var dv = new DataView(buf);
    new Uint8Array(buf)[0] = T.PING;
    dv.setBigInt64 ? dv.setBigInt64(1, BigInt(ms), false)
      : (dv.setUint32(1, Math.floor(ms / 4294967296), false), dv.setUint32(5, ms >>> 0, false));
    return new Uint8Array(buf);
  }

  function qualityMsg(q, fps, scale) {
    var o = {};
    if (q) o.quality = q;
    if (fps) o.fps = fps;
    if (scale) o.scale = scale;
    return jsonMsg(T.QUALITY, o);
  }

  // ------------------------------------------------------------ state
  var ws = null, connected = false, closing = false, opened = false;
  var mode = MODE_TOUCH;
  var serverW = 1280, serverH = 800;
  var token = '';
  var quality = 70, fps = 20;
  var cursorX = 0.5, cursorY = 0.5;
  var dragging = false, twoFinger = false;
  var lastX = 0, lastY = 0, downX = 0, downY = 0;
  var lastTapAt = 0, lastTapX = 0, lastTapY = 0;
  var pingTimer = null, statTimer = null;
  var frames = 0, bytes = 0, lastFpsAt = Date.now(), lastFrameAt = 0, fpsValue = 0;
  var rtt = -1;
  var pendingFrame = null, decoding = false;
  var imeSent = '', imeSuppress = false;
  var cwd = '';
  var statsOn = false;
  var initialized = false;
  var firstFramePending = true;

  var el = {};
  function $(id) { return document.getElementById(id); }

  // ------------------------------------------------------------ ime bridge
  // Same algorithm as the Android app: diff the field against what was already
  // sent, emit Backspace runs for deletions and one UTF-8 text event for
  // insertions. This is what makes Persian/emoji/IME input work on the host.
  function commonPrefix(a, b) {
    var n = Math.min(a.length, b.length), i = 0;
    while (i < n && a.charAt(i) === b.charAt(i)) i++;
    if (i > 0) {
      var prev = a.charCodeAt(i - 1);
      if (prev >= 0xD800 && prev <= 0xDBFF && i < a.length) {
        var next = a.charCodeAt(i);
        if (next >= 0xDC00 && next <= 0xDFFF) i--;
      }
    }
    return i;
  }

  function codePointLength(s) {
    var n = 0;
    for (var i = 0; i < s.length; i++) {
      var c = s.charCodeAt(i);
      if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length) {
        var d = s.charCodeAt(i + 1);
        if (d >= 0xDC00 && d <= 0xDFFF) { i++; }
      }
      n++;
    }
    return n;
  }

  function sendKey(key, action, mods) {
    if (!connected) return;
    ws.send(jsonMsg(T.KEY, { key: key, action: action || 'down', mods: mods || {} }));
  }

  function imeChanged(value) {
    if (!connected) return;
    if (imeSuppress) {
      imeSuppress = false;
      if (!value) return;
      imeSent = value;
      ws.send(jsonMsg(T.TEXT, { text: value }));
      return;
    }
    if (value === imeSent) return;
    if (!value) {
      var n = codePointLength(imeSent);
      for (var i = 0; i < n; i++) sendKey('Backspace', 'down', {});
      imeSent = '';
      return;
    }
    var common = commonPrefix(imeSent, value);
    var deleted = imeSent.substring(common);
    var added = value.substring(common);
    var del = codePointLength(deleted);
    for (var j = 0; j < del; j++) sendKey('Backspace', 'down', {});
    if (added) ws.send(jsonMsg(T.TEXT, { text: added }));
    imeSent = value;
  }


  // ------------------------------------------------------------ connection
  var HOST_KEY = 'devviord.host';
  var TOK_KEY = 'devviord.token';
  var MODE_KEY = 'devviord.mode';
  var Q_KEY = 'devviord.quality';
  var FPS_KEY = 'devviord.fps';

  function store(k, v) {
    try { window.localStorage.setItem(k, v); } catch (e) { /* private mode */ }
  }
  function recall(k, fallback) {
    try {
      var v = window.localStorage.getItem(k);
      return v === null ? fallback : v;
    } catch (e) { return fallback; }
  }

  // Where to connect. A user-provided host wins; otherwise the page's own origin
  // is used, which is the normal case because the server serves this client.
  function wsEndpoint() {
    var custom = (el.hostInput && el.hostInput.value || '').trim();
    if (custom) {
      var base = custom;
      if (!/^wss?:\/\//.test(base)) {
        base = (location.protocol === 'https:' ? 'wss://' : 'ws://') + base;
      }
      if (!/\/ws$/.test(base)) base = base.replace(/\/+$/, '') + '/ws';
      return base;
    }
    var proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    return proto + location.host + '/ws';
  }

  // REST base matching wsEndpoint(): same host, http(s) instead of ws(s).
  function restBase() {
    var custom = (el.hostInput && el.hostInput.value || '').trim();
    if (!custom) return '';
    var base = custom;
    if (/^wss?:\/\//.test(base)) {
      base = base.replace(/^ws(s?):\/\//, function (m, s) { return 'http' + (s ? 's' : '') + '://'; });
    } else if (!/^https?:\/\//.test(base)) {
      base = (location.protocol === 'https:' ? 'https://' : 'http://') + base;
    }
    return base.replace(/\/ws$/, '').replace(/\/+$/, '');
  }

  var retryTimer = null;
  var retryDelay = 1500;
  var retryCount = 0;

  function connect() {
    var url = wsEndpoint();
    if (token) url += '?token=' + encodeURIComponent(token);
    setStatus('connecting…', 'warn');
    el.overlay.style.display = 'none';
    closing = false;
    try {
      ws = new WebSocket(url);
    } catch (e) {
      showLoginError('Cannot open a websocket: ' + e.message);
      return;
    }
    ws.binaryType = 'arraybuffer';

    opened = false;
    ws.onopen = function () {
      opened = true;
      var hello = {
        version: 1,
        client: 'web/1.1.0',
        device: (navigator.userAgent || 'browser').slice(0, 60),
        caps: {
          touch: true, mouse: true, clipboard: true, files: true,
          multitouch: true, keyboard: true, maxWidth: 0, maxHeight: 0, jpegQuality: quality
        }
      };
      ws.send(jsonMsg(T.HELLO, hello));
    };

    ws.onmessage = function (ev) {
      if (typeof ev.data === 'string') return;
      handleMessage(new Uint8Array(ev.data));
    };

    ws.onclose = function (ev) {
      var wasConnected = connected;
      connected = false;
      stopTimers();
      setStatus('disconnected', 'err');
      if (closing) return;

      // 1006 on the very first attempt almost always means the upgrade was
      // refused: wrong token or nothing listening. Loop on that and the user
      // never sees why, so surface it on the login card instead.
      if (ev && ev.code === 1006 && !opened && !wasConnected) {
        showLoginCard();
        showLoginError('Connection refused — check the access token and that the server is running.');
        return;
      }
      scheduleReconnect(wasConnected ? 'The session ended.' : 'Could not reach the host.');
    };

    ws.onerror = function () {
      setStatus('connection error', 'err');
    };
  }

  function scheduleReconnect(reason) {
    if (closing) return;
    retryCount++;
    var wait = Math.min(15000, retryDelay * Math.pow(1.6, Math.min(4, retryCount - 1)));
    el.overlay.style.display = 'flex';
    $('overlayTitle').textContent = 'Reconnecting…';
    $('overlayMsg').textContent = reason + ' Attempt ' + retryCount +
      ' — retrying in ' + Math.round(wait / 1000) + 's.';
    $('overlayBack').style.display = 'inline-flex';
    if (retryTimer) clearTimeout(retryTimer);
    retryTimer = setTimeout(function () {
      retryTimer = null;
      connect();
    }, wait);
  }

  function cancelReconnect() {
    if (retryTimer) { clearTimeout(retryTimer); retryTimer = null; }
  }

  function showLoginCard() {
    el.overlay.style.display = 'none';
    $('login').style.display = 'flex';
    $('bar').style.display = 'none';
  }

  function disconnect() {
    closing = true;
    cancelReconnect();
    if (ws) { try { ws.close(1000, 'client closed'); } catch (e) { /* ignore */ } }
    ws = null;
    connected = false;
    stopTimers();
    showLoginCard();
    setStatus('disconnected', 'err');
  }

  function onHelloOk(info) {
    connected = true;
    closing = false;
    retryCount = 0;
    serverW = info.width || serverW;
    serverH = info.height || serverH;
    $('login').style.display = 'none';
    $('bar').style.display = 'flex';
    el.overlay.style.display = 'none';
    lastFrameAt = Date.now();
    setStatus('connected · ' + (info.hostname || 'host'), 'on');
    // Adopt the server's geometry before the first frame so touch mapping is
    // correct immediately.
    el.screen.style.aspectRatio = '';
    ws.send(jsonMsg(T.POINTER_MODE, { mode: mode }));
    ws.send(qualityMsg(quality, fps, 0));
    startTimers();
    if (!fileListLoaded) loadFiles('');
    if (info.needsAuth === false) el.tokenInput.placeholder = 'no token required';
  }

  function startTimers() {
    stopTimers();
    pingTimer = setInterval(function () {
      if (connected) ws.send(pingMsg(Date.now()));
    }, 5000);
    statTimer = setInterval(function () {
      var now = Date.now();
      if (now - lastFpsAt >= 1000) {
        fpsValue = Math.round(frames * 1000 / (now - lastFpsAt));
        frames = 0; lastFpsAt = now;
      }
      // Poll the server for authoritative numbers: the browser only knows what
      // it received, not what the host is actually producing or who else is
      // attached.
      if (statsOn && connected) refreshServerStats();
      if (statsOn) paintStats();
    }, 1000);
  }

  function stopTimers() {
    if (pingTimer) clearInterval(pingTimer);
    if (statTimer) clearInterval(statTimer);
    pingTimer = statTimer = null;
  }

  var serverStats = { fps: 0, clients: 0, kbps: 0, lastFrameAge: -1, streaming: false };

  // Latency history (last 20 samples) drives the connection-quality indicator.
  var rttHistory = [];
  var weakStreak = 0;
  var lastAutoDowngrade = 0;
  var paused = false;

  /**
   * Connection quality from the last few RTT samples. Returns
   * {label, cls} so the status pill can read like a signal meter:
   * "connected · 12 ms" is far more useful mid-session than "connected".
   */
  function linkQuality() {
    if (rttHistory.length === 0) return { label: '', cls: '' };
    var sum = 0, worst = 0;
    for (var i = 0; i < rttHistory.length; i++) {
      sum += rttHistory[i];
      if (rttHistory[i] > worst) worst = rttHistory[i];
    }
    var avg = sum / rttHistory.length;
    if (avg < 60 && worst < 160) return { label: Math.round(avg) + ' ms', cls: 'on' };
    if (avg < 180 && worst < 450) return { label: Math.round(avg) + ' ms', cls: 'warn' };
    return { label: Math.round(avg) + ' ms — slow', cls: 'err' };
  }

  function sampleRtt(ms) {
    if (!(ms >= 0) || ms > 60000) return;
    rttHistory.push(ms);
    if (rttHistory.length > 20) rttHistory.shift();
    maybeAutoDowngrade();
  }

  /**
   * On a genuinely poor link, step the stream down once instead of letting the
   * user stare at a slideshow. Only ever lowers quality, only when it has to,
   * at most once every 20 s, and it tells the user what it did and how to undo
   * it - a silent change in image quality looks like a bug.
   */
  function maybeAutoDowngrade() {
    if (!connected || QUALITY_ORDER.length < 2) return;
    var idx = QUALITY_ORDER.indexOf(quality);
    if (idx < 0 || idx === QUALITY_ORDER.length - 1) return;
    var now = Date.now();
    var poor = rttHistory.length >= 5 && rttHistory[rttHistory.length - 1] > 350;
    if (!poor) { weakStreak = 0; return; }
    weakStreak++;
    if (weakStreak < 3 || now - lastAutoDowngrade < 20000) return;
    weakStreak = 0;
    lastAutoDowngrade = now;
    var next = presetFor(QUALITY_ORDER[idx + 1]);
    applyQualityPreset(next.q);
    toast('Weak connection — lowered quality to ' + next.label + ' (change it in ⚙)', 'warn');
  }

  /**
   * A background tab still receives every frame and burns battery decoding
   * them, so stop asking the host for frames while hidden and resume on return.
   * The socket stays open, so coming back is instant.
   */
  function bindVisibility() {
    document.addEventListener('visibilitychange', function () {
      var hidden = document.visibilityState === 'hidden';
      if (hidden === paused) return;
      paused = hidden;
      if (!connected || !ws) return;
      ws.send(jsonMsg(T.PAUSE, { paused: hidden }));
      if (!hidden) {
        // Frames that queued while hidden are stale; drop the backlog and ask
        // for a fresh sample so the picture is current the moment we return.
        pendingFrame = null;
        ws.send(pingMsg(Date.now()));
      }
    });
  }

  function refreshServerStats() {
    api('/api/stats').then(function (r) { return r.ok ? r.json() : null; }).then(function (d) {
      if (d) serverStats = d;
    }).catch(function () { /* telemetry is best-effort */ });
  }

  function paintStats() {
    var age = lastFrameAt ? Date.now() - lastFrameAt : -1;
    // Prefer the browser's own decode rate (what the user actually sees); fall
    // back to the server's production rate until the first poll lands.
    var shown = fpsValue || (serverStats.streaming ? serverStats.fps : 0);
    var fpsText = (Math.round(shown * 10) / 10) + ' fps';
    $('stFps').textContent = fpsText;
    var target = Math.max(5, fps * 0.6);
    $('stFps').className = 'v ' + (shown >= target ? 'good' : shown > 0 ? 'warn' : '');
    $('stGeo').textContent = serverW + '×' + serverH;
    $('stRtt').textContent = rtt < 0 ? '—' : Math.round(rtt) + ' ms';
    $('stRtt').className = 'v ' + (rtt < 0 ? '' : rtt < 80 ? 'good' : rtt < 250 ? 'warn' : 'bad');
    $('stRx').textContent = human(bytes);
    $('stAge').textContent = age < 0 ? '—' : age + ' ms';
    $('stAge').className = 'v ' + (age < 0 ? '' : age > 1500 ? 'warn' : 'good');
    $('stMode').textContent = mode;
    $('statsFps').textContent = fpsText + ' fps';
  }

  // ------------------------------------------------------------ messages
  function handleMessage(buf) {
    var type = buf[0];
    switch (type) {
      case T.HELLO_OK:
        try { onHelloOk(JSON.parse(dec.decode(buf.subarray(1)))); } catch (e) { /* ignore */ }
        break;
      case T.FRAME:
        onFrame(buf);
        break;
      case T.STATUS:
        try {
          var st = JSON.parse(dec.decode(buf.subarray(1)));
          if (st.code !== 'hello') setStatus(st.message || 'status', st.level === 'error' ? 'err' : 'on');
        } catch (e) { /* ignore */ }
        break;
      case T.CLIP:
        try {
          var cb = JSON.parse(dec.decode(buf.subarray(1)));
          if (cb.data) {
            imeSent = cb.data;
            copyToClipboard(cb.data);
            toast('Host clipboard copied to this device', 'good');
          }
        } catch (e) { /* ignore */ }
        break;
      case T.CURSOR:
        try {
          var cu = JSON.parse(dec.decode(buf.subarray(1)));
          if (cu.x >= 0 && cu.y >= 0) {
            cursorX = cu.x; cursorY = cu.y;
            positionCursor();
          }
        } catch (e) { /* ignore */ }
        break;
      case T.SCREEN:
        try {
          var sc = JSON.parse(dec.decode(buf.subarray(1)));
          if (sc.width > 0) serverW = sc.width;
          if (sc.height > 0) serverH = sc.height;
        } catch (e) { /* ignore */ }
        break;
      case T.PONG:
        try {
          var dv2 = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
          var ms = Number(dv2.getBigInt64 ? dv2.getBigInt64(1, false)
            : dv2.getUint32(1, false) * 4294967296 + dv2.getUint32(5, false));
          rtt = Date.now() - ms;
          sampleRtt(rtt);
          var lq = linkQuality();
          if (lq.label) setStatus('connected · ' + lq.label, lq.cls);
          document.dispatchEvent(new CustomEvent('devviord:rtt', { detail: { rtt: rtt } }));
        } catch (e) { /* ignore */ }
        break;
      case T.POINTER_MODE:
        try {
          // Another client changed the mode: follow it so both devices agree.
          var pm = JSON.parse(dec.decode(buf.subarray(1)));
          if (pm.mode && pm.mode !== mode) setMode(pm.mode);
        } catch (e) { /* ignore */ }
        break;
      case T.QUALITY:
        try {
          var qq = JSON.parse(dec.decode(buf.subarray(1)));
          if (qq.quality) quality = qq.quality;
          if (qq.fps) fps = qq.fps;
        } catch (e) { /* ignore */ }
        break;
      case T.ERROR:
        try {
          var er = JSON.parse(dec.decode(buf.subarray(1)));
          setStatus(er.message || 'error', 'err');
          toast(er.message || 'Server error', 'bad');
        } catch (e) { /* ignore */ }
        break;
      default:
        break;
    }
  }

  // ------------------------------------------------------------ video
  function onFrame(buf) {
    frames++;
    bytes += buf.length;
    lastFrameAt = Date.now();
    // Latest-wins decoding: drop the older pending frame if a new one arrived.
    pendingFrame = buf;
    if (!decoding) drainFrames();
  }

  function drainFrames() {
    if (!pendingFrame) { decoding = false; return; }
    decoding = true;
    var buf = pendingFrame;
    pendingFrame = null;
    var jpeg = new Blob([buf.subarray(FRAME_HEADER)], { type: 'image/jpeg' });
    var url = URL.createObjectURL(jpeg);
    var img = new Image();
    img.onload = function () {
      el.screen.src = url;
      var old = el.screen.getAttribute('data-prev');
      if (old) URL.revokeObjectURL(old);
      el.screen.setAttribute('data-prev', url);
      if (firstFramePending) {
        firstFramePending = false;
        el.overlay.style.display = 'none';
      }
      drainFrames();
    };
    img.onerror = function () { URL.revokeObjectURL(url); drainFrames(); };
    img.src = url;
  }

  // ------------------------------------------------------------ input
  function stageRect() {
    var r = el.stage.getBoundingClientRect();
    // `object-fit: contain` letterboxes the image; mirror that maths here so a
    // tap maps to the same relative point on the remote desktop.
    var ar = serverW / Math.max(1, serverH);
    var w = r.width, h = r.height;
    var dw, dh, dx, dy;
    if (w / h > ar) { dh = h; dw = h * ar; dx = (w - dw) / 2; dy = 0; }
    else { dw = w; dh = w / ar; dx = 0; dy = (h - dh) / 2; }
    return { left: r.left + dx, top: r.top + dy, w: dw, h: dh };
  }

  function norm(clientX, clientY) {
    var r = stageRect();
    return {
      x: Math.min(1, Math.max(0, (clientX - r.left) / Math.max(1, r.w))),
      y: Math.min(1, Math.max(0, (clientY - r.top) / Math.max(1, r.h)))
    };
  }

  function positionCursor() {
    if (mode !== MODE_MOUSE) { el.cursor.style.display = 'none'; return; }
    var r = stageRect();
    var box = el.stage.getBoundingClientRect();
    el.cursor.style.display = 'block';
    el.cursor.style.left = (r.left - box.left + cursorX * r.w) + 'px';
    el.cursor.style.top = (r.top - box.top + cursorY * r.h) + 'px';
  }

  function sendTouch(action, x, y, pressure) {
    if (connected) ws.send(touchMsg(action, x, y, pressure));
  }

  var lastMoveSent = 0;

  function onDown(x, y) {
    downX = x; downY = y; lastX = x; lastY = y; dragging = false;
    if (mode === MODE_TOUCH) sendTouch(TOUCH_DOWN, x, y, 1);
    else sendTouch(TOUCH_MOVE, cursorX, cursorY, 1);
  }

  function onMove(x, y) {
    if (mode === MODE_TOUCH) {
      // Throttled like the Android client: 60 Hz pointer events would otherwise
      // flood a 20 fps stream with useless samples.
      var now = Date.now();
      if (now - lastMoveSent < 12) return;
      lastMoveSent = now;
      sendTouch(TOUCH_MOVE, x, y, 1);
    } else {
      // Trackpad behaviour: relative movement of the virtual cursor.
      var r = stageRect();
      cursorX = Math.min(1, Math.max(0, cursorX + (x - lastX) * (serverW / Math.max(1, r.w)) * 0.9));
      cursorY = Math.min(1, Math.max(0, cursorY + (y - lastY) * (serverH / Math.max(1, r.h)) * 0.9));
      lastX = x; lastY = y;
      sendTouch(TOUCH_MOVE, cursorX, cursorY, 1);
      positionCursor();
    }
    if (!dragging && Math.hypot(x - downX, y - downY) > 0.012) dragging = true;
  }

  function onUp(x, y) {
    var now = Date.now();
    var isDouble = now - lastTapAt < 320 && Math.hypot(x - lastTapX, y - lastTapY) < 0.04;

    if (mode === MODE_TOUCH) {
      sendTouch(TOUCH_UP, x, y, 0);
      if (isDouble) {
        sendTouch(TOUCH_DOWN, x, y, 1);
        sendTouch(TOUCH_UP, x, y, 0);
      }
    } else if (!dragging) {
      sendTouch(TOUCH_DOWN, cursorX, cursorY, 1);
      sendTouch(TOUCH_UP, cursorX, cursorY, 0);
      if (isDouble) {
        sendTouch(TOUCH_DOWN, cursorX, cursorY, 1);
        sendTouch(TOUCH_UP, cursorX, cursorY, 0);
      }
    }

    if (isDouble) { lastTapAt = 0; } else { lastTapAt = now; lastTapX = x; lastTapY = y; }
    dragging = false;
  }

  function bindPointer() {
    var active = {}, activeCount = 0, scrollState = null;

    el.stage.addEventListener('pointerdown', function (e) {
      // Panels float above the stage; never treat a tap on them as remote input.
      if (e.target.closest && e.target.closest('#drawer, #pairPanel, #help, #stats, #overlay')) return;
      if (e.button !== undefined && e.button !== 0) return;
      el.stage.setPointerCapture(e.pointerId);
      active[e.pointerId] = { x: e.clientX, y: e.clientY };
      activeCount++;
      if (activeCount === 1) {
        var n = norm(e.clientX, e.clientY);
        onDown(n.x, n.y);
      } else if (activeCount === 2) {
        twoFinger = true; dragging = false;
        var keys = Object.keys(active);
        var a = active[keys[0]], b = active[keys[1]];
        scrollState = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
        if (mode === MODE_TOUCH) sendTouch(TOUCH_CANCEL, cursorX, cursorY, 0);
      }
    });

    el.stage.addEventListener('pointermove', function (e) {
      if (!active[e.pointerId]) return;
      active[e.pointerId] = { x: e.clientX, y: e.clientY };
      if (twoFinger && activeCount >= 2) {
        var keys = Object.keys(active);
        if (keys.length < 2) return;
        var a = active[keys[0]], b = active[keys[1]];
        var cx = (a.x + b.x) / 2, cy = (a.y + b.y) / 2;
        if (scrollState) {
          var dy = -(cy - scrollState.y) / 120;
          var dx = -(cx - scrollState.x) / 120;
          if (Math.abs(dy) > 0.002 || Math.abs(dx) > 0.002) {
            if (connected) ws.send(scrollMsg(dx, dy));
            scrollState = { x: cx, y: cy };
          }
        }
        return;
      }
      var n = norm(e.clientX, e.clientY);
      onMove(n.x, n.y);
    });

    function release(e) {
      if (!active[e.pointerId]) return;
      delete active[e.pointerId];
      activeCount = Math.max(0, activeCount - 1);
      if (activeCount === 0) {
        if (twoFinger) { twoFinger = false; scrollState = null; dragging = false; return; }
        var n = norm(e.clientX, e.clientY);
        onUp(n.x, n.y);
      }
    }
    el.stage.addEventListener('pointerup', release);
    el.stage.addEventListener('pointercancel', function (e) {
      delete active[e.pointerId];
      activeCount = Math.max(0, activeCount - 1);
      twoFinger = false; scrollState = null;
    });

    // wheel = scroll
    el.stage.addEventListener('wheel', function (e) {
      if (!connected) return;
      e.preventDefault();
      ws.send(scrollMsg(e.deltaX / 300, -e.deltaY / 300));
    }, { passive: false });

    // context menu = right click (mouse mode)
    el.stage.addEventListener('contextmenu', function (e) {
      e.preventDefault();
      if (mode === MODE_MOUSE && connected) {
        ws.send(jsonMsg(T.KEY, { key: 'ContextMenu', action: 'down', mods: {} }));
      }
    });
  }

  // keyboard: physical keys + IME text
  var KEY_MAP = {
    'Enter': 'Enter', 'Backspace': 'Backspace', 'Tab': 'Tab', 'Escape': 'Escape',
    'ArrowLeft': 'ArrowLeft', 'ArrowRight': 'ArrowRight', 'ArrowUp': 'ArrowUp',
    'ArrowDown': 'ArrowDown', 'Delete': 'Delete', 'Home': 'Home', 'End': 'End',
    'PageUp': 'PageUp', 'PageDown': 'PageDown'
  };

  function focusIme() {
    if (!el.ime) return;
    try { el.ime.focus({ preventScroll: true }); } catch (e) { el.ime.focus(); }
    // Mobile browsers only open the keyboard from a real user gesture, which is
    // exactly what the toolbar button is.
    el.ime.value = '';
    imeSuppress = true;
    if (typeof el.ime.setSelectionRange === 'function') {
      try { el.ime.setSelectionRange(0, 0); } catch (e) { /* ignore */ }
    }
  }

  function bindKeyboard() {
    document.addEventListener('keydown', function (e) {
      if (!connected) return;
      if (e.target && e.target.tagName === 'INPUT' && e.target.id !== 'ime') return;

      // Single-key shortcuts, only when no modifier is held so they never
      // shadow real Ctrl/Alt combos destined for the host.
      if (!e.ctrlKey && !e.altKey && !e.metaKey && e.key && e.key.length === 1) {
        var k = e.key.toLowerCase();
        if (k === 'm') { e.preventDefault(); setMode(mode === MODE_MOUSE ? MODE_TOUCH : MODE_MOUSE); return; }
        if (k === 'k') { e.preventDefault(); focusIme(); toast('Keyboard ready'); return; }
        if (k === 'f') { e.preventDefault(); toggleDrawer(); return; }
        if (k === 'i') { e.preventDefault(); toggleStats(); return; }
        if (k === '?') { e.preventDefault(); showHelp(true); return; }
      }
      if (e.key === 'F11') { e.preventDefault(); toggleFullscreen(); return; }
      if (e.key === 'Escape') {
        // Close whatever overlay is open, otherwise release the host's keys.
        if (closeTopOverlay()) { e.preventDefault(); return; }
        if (connected) {
          e.preventDefault();
          sendKey('Escape', 'down', {});
          ws.send(jsonMsg(T.KEY, { key: 'Escape', action: 'up', mods: {} }));
        }
        return;
      }

      var mods = { ctrl: e.ctrlKey, alt: e.altKey, shift: e.shiftKey, meta: e.metaKey };
      var key = KEY_MAP[e.key];
      if (!key && e.key.length === 1 && (e.ctrlKey || e.altKey || e.metaKey)) key = e.key;
      if (key) {
        e.preventDefault();
        sendKey(key, 'down', mods);
        ws.send(jsonMsg(T.KEY, { key: key, action: 'up', mods: mods }));
      } else if (e.key.length > 1 && /^F\d{1,2}$/.test(e.key)) {
        e.preventDefault();
        sendKey(e.key, 'down', mods);
      }
      // Printable characters are handled by the IME field (see below).
      if (e.key.length === 1 && !e.ctrlKey && !e.altKey && !e.metaKey) {
        if (document.activeElement !== el.ime) {
          try { el.ime.focus({ preventScroll: true }); } catch (err) { /* ignore */ }
        }
      }
    });

    el.ime.addEventListener('input', function () {
      imeChanged(el.ime.value);
    });

    document.addEventListener('paste', function (e) {
      if (!connected) return;
      var text = (e.clipboardData || window.clipboardData).getData('text');
      if (text) {
        e.preventDefault();
        ws.send(jsonMsg(T.TEXT, { text: text }));
        imeSuppress = true;
        el.ime.value = '';
        imeSent = '';
        toast('Pasted ' + text.length + ' characters into the host', 'good');
      }
    });
  }

  function copyToClipboard(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).catch(function () { fallbackCopy(text); });
    } else {
      fallbackCopy(text);
    }
  }

  function fallbackCopy(text) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); } catch (e) { /* ignore */ }
    document.body.removeChild(ta);
  }

  // ------------------------------------------------------------ files
  function api(path, opts) {
    opts = opts || {};
    opts.headers = opts.headers || {};
    if (token) opts.headers['X-DevvioRD-Token'] = token;
    var base = restBase();
    return fetch(base + path, opts);
  }

  function esc(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function human(n) {
    if (n == null) return '';
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
    if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB';
    return (n / 1073741824).toFixed(2) + ' GB';
  }

  var fileListLoaded = false;

  function loadFiles(dir) {
    cwd = dir || '';
    var list = $('filelist');
    list.innerHTML = '<div class="empty">Loading…</div>';
    $('drawerPath').innerHTML = '📂 <b>/' + esc(cwd) + '</b>';
    api('/api/files?dir=' + encodeURIComponent(cwd)).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }).then(function (data) {
      fileListLoaded = true;
      var html = '';
      if (cwd) html += '<div class="file dir" data-dir="up"><span class="ico">⬑</span>' +
        '<span class="name">..</span><span class="size">up</span></div>';
      (data.entries || []).forEach(function (e) {
        html += '<div class="file' + (e.dir ? ' dir' : '') + '" data-path="' + esc(e.path) +
          '" data-dir="' + (e.dir ? '1' : '0') + '" data-name="' + esc(e.name) + '">' +
          '<span class="ico">' + (e.dir ? '📁' : '📄') + '</span>' +
          '<span class="name">' + esc(e.name) + '</span>' +
          '<span class="size">' + (e.dir ? '' : human(e.size)) + '</span></div>';
      });
      if (!data.entries || !data.entries.length) {
        html += '<div class="empty">This folder is empty.<br>Use <b>⬆ Upload</b> to send a file.</div>';
      }
      list.innerHTML = html;
      Array.prototype.forEach.call(list.querySelectorAll('.file'), function (node) {
        node.addEventListener('click', function () {
          if (node.getAttribute('data-dir') === 'up') {
            var i = cwd.lastIndexOf('/');
            loadFiles(i <= 0 ? '' : cwd.slice(0, i));
            return;
          }
          var path = node.getAttribute('data-path');
          if (node.getAttribute('data-dir') === '1') loadFiles(path);
          else download(path, node.getAttribute('data-name'));
        });
      });
    }).catch(function (e) {
      list.innerHTML = '<div class="empty">Could not list files.<br>' + esc(e.message) + '</div>';
    });
  }

  function download(path, name) {
    toast('Downloading ' + name + '…');
    api('/api/download?path=' + encodeURIComponent(path)).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.blob();
    }).then(function (blob) {
      var a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = name;
      document.body.appendChild(a);
      a.click();
      setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 4000);
      toast('Saved ' + name, 'good');
    }).catch(function (e) { toast('Download failed: ' + e.message, 'bad'); });
  }

  // Upload queue with a live progress line: large files over a slow link look
  // frozen otherwise, which is the most common "it does not work" report.
  var uploadQueue = [];
  var uploading = false;

  function uploadFiles(files) {
    for (var i = 0; i < files.length; i++) uploadQueue.push(files[i]);
    if (!uploading) pumpUploads();
  }

  function showUploadProgress(text, ratio) {
    var foot = document.querySelector('.drawer-foot');
    if (!foot) return;
    var box = document.getElementById('upProgress');
    if (!box) {
      box = document.createElement('div');
      box.id = 'upProgress';
      box.className = 'up-item';
      foot.parentNode.insertBefore(box, foot);
    }
    box.innerHTML = '<b>' + esc(text) + '</b>' + (ratio != null
      ? '<div class="bar-mini"><i style="width:' + Math.round(ratio * 100) + '%"></i></div>'
      : '');
  }

  function pumpUploads() {
    if (!uploadQueue.length) {
      uploading = false;
      var box = document.getElementById('upProgress');
      if (box) setTimeout(function () { if (box.parentNode) box.parentNode.removeChild(box); }, 1200);
      loadFiles(cwd);
      return;
    }
    uploading = true;
    var f = uploadQueue.shift();
    var CHUNK = 1 << 20;
    showUploadProgress('Uploading ' + f.name + ' (' + human(f.size) + ')', 0);
    api('/api/upload/begin', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: f.name, dir: cwd, size: f.size })
    }).then(function (r) {
      if (!r.ok) throw new Error('begin failed (' + r.status + ')');
      return r.json();
    }).then(function (info) {
      var offset = info.offset || 0;
      function sendChunk() {
        if (offset >= f.size) {
          return api('/api/upload/finish', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id: info.id })
          }).then(function (r) {
            if (!r.ok) throw new Error('finish failed');
            toast('Uploaded ' + f.name, 'good');
          });
        }
        var slice = f.slice(offset, offset + CHUNK);
        return slice.arrayBuffer().then(function (buf) {
          return api('/api/upload/chunk?id=' + encodeURIComponent(info.id) + '&offset=' + offset, {
            method: 'POST',
            headers: { 'Content-Type': 'application/octet-stream' },
            body: buf
          });
        }).then(function (r) {
          if (!r.ok) throw new Error('chunk failed (' + r.status + ')');
          offset = Math.min(f.size, offset + CHUNK);
          showUploadProgress('Uploading ' + f.name + ' — ' + human(offset) + ' / ' + human(f.size),
            f.size ? offset / f.size : 1);
          return sendChunk();
        });
      }
      return sendChunk();
    }).then(function () {
      pumpUploads();
    }).catch(function (e) {
      toast('Upload failed: ' + e.message, 'bad');
      pumpUploads();
    });
  }

  // ------------------------------------------------------------ ui glue
  function setStatus(text, kind) {
    var s = $('status');
    if (!s) return;
    s.textContent = text;
    s.className = 'pill' + (kind ? ' ' + kind : '');
  }

  var toastTimer = null;
  function toast(msg, kind) {
    var t = $('toast');
    if (!t) return;
    t.textContent = msg;
    t.className = 'show' + (kind ? ' ' + kind : '');
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.className = ''; }, 2200);
  }

  function setMode(m) {
    mode = m;
    store(MODE_KEY, m);
    var b = $('modeBtn');
    if (b) {
      b.textContent = m === MODE_MOUSE ? 'MOUSE' : 'TOUCH';
      b.classList.toggle('active', m === MODE_MOUSE);
    }
    el.stage.classList.toggle('mouse-mode', m === MODE_MOUSE);
    var st = $('stMode');
    if (st) st.textContent = m;
    positionCursor();
    if (connected) ws.send(jsonMsg(T.POINTER_MODE, { mode: m }));
    toast(m === MODE_MOUSE ? 'Mouse mode: drag to move, tap to click' : 'Touch mode: tap directly');
  }

  function showLoginError(msg) {
    var e = $('loginErr');
    if (e) e.textContent = msg || '';
  }

  function toggleStats() {
    statsOn = !statsOn;
    $('stats').classList.toggle('on', statsOn);
    $('statsBtn').classList.toggle('active', statsOn);
    if (statsOn) { paintStats(); if (connected) refreshServerStats(); }
  }

  function toggleDrawer(force) {
    var open = typeof force === 'boolean' ? force : !$('drawer').classList.contains('open');
    $('drawer').classList.toggle('open', open);
    $('filesBtn').classList.toggle('active', open);
    if (open) loadFiles(cwd);
  }

  function showHelp(on) {
    $('help').classList.toggle('open', !!on);
  }

  function showPair(on) {
    var panel = $('pairPanel');
    panel.classList.toggle('open', !!on);
    $('pairBtn').classList.toggle('active', !!on);
    if (!on) return;
    api('/api/pair').then(function (r) { return r.json(); }).then(function (info) {
      $('qrBox').innerHTML = renderQR(info.qr);
      $('pairUrl').textContent = info.pairUrl || '';
      $('pairHint').innerHTML = info.auth
        ? '<b>Step 1.</b> Scan the code with the DevvioRD app.<br>' +
          '<b>Step 2.</b> Enter the access token shown on the server console.<br>' +
          'The QR intentionally omits the token unless the server runs with <b>--pair-public</b>.'
        : '<b>Step 1.</b> Scan the code with the DevvioRD app.<br>' +
          'No token is configured, so the app connects as soon as you scan.';
    }).catch(function (e) {
      $('qrBox').innerHTML = '';
      $('pairHint').textContent = 'Could not load pairing info: ' + e.message;
    });
  }

  // Closes whichever floating panel is on top; returns true when it closed one.
  function closeTopOverlay() {
    if ($('help').classList.contains('open')) { showHelp(false); return true; }
    if ($('pairPanel').classList.contains('open')) { showPair(false); return true; }
    if ($('drawer').classList.contains('open')) { toggleDrawer(false); return true; }
    return false;
  }

  function renderQR(matrix) {
    if (!matrix || !matrix.length) return '';
    var n = matrix.length;
    var quiet = 4;
    var dim = n + quiet * 2;
    var path = '';
    for (var y = 0; y < n; y++) {
      for (var x = 0; x < n; x++) {
        if (matrix[y][x]) path += 'M' + (x + quiet) + ' ' + (y + quiet) + 'h1v1h-1z';
      }
    }
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + dim + ' ' + dim +
      '" shape-rendering="crispEdges"><rect width="' + dim + '" height="' + dim +
      '" fill="#fff"/><path d="' + path + '" fill="#000"/></svg>';
  }

  function toggleFullscreen() {
    var d = document;
    if (!d.fullscreenElement && !d.webkitFullscreenElement) {
      var el2 = d.documentElement;
      (el2.requestFullscreen || el2.webkitRequestFullscreen || function () { }).call(el2);
    } else {
      (d.exitFullscreen || d.webkitExitFullscreen || function () { }).call(d);
    }
  }

  // Session menu: the long tail of actions, kept out of the toolbar.
  function showMenu() {
    var panel = document.getElementById('sessionMenu');
    if (panel) { panel.parentNode.removeChild(panel); return; }
    panel = document.createElement('div');
    panel.id = 'sessionMenu';
    panel.style.cssText = 'position:absolute;right:10px;top:48px;z-index:24;background:var(--surface);' +
      'border:1px solid var(--line);border-radius:14px;padding:6px;box-shadow:var(--shadow);min-width:210px';
    var items = [
      ['⌨  Keyboard', function () { focusIme(); }],
      ['📋  Send clipboard → host', function () { pushClipboard(); }],
      ['📋  Copy host clipboard', function () { pullClipboard(); }],
      ['🔁  Reconnect', function () { closing = false; connect(); }],
      ['⛶  Fullscreen', toggleFullscreen],
      ['⌘  Pair a phone', function () { showPair(true); }],
      ['?  Shortcuts', function () { showHelp(true); }],
      ['⏻  Disconnect', disconnect]
    ];
    items.forEach(function (it) {
      var b = document.createElement('button');
      b.className = 'btn';
      b.style.cssText = 'width:100%;justify-content:flex-start;margin:2px 0;padding:10px 12px';
      b.textContent = it[0];
      b.addEventListener('click', function () {
        panel.parentNode.removeChild(panel);
        it[1]();
      });
      panel.appendChild(b);
    });
    el.stage.appendChild(panel);
    setTimeout(function () {
      document.addEventListener('pointerdown', function close(ev) {
        if (!panel.contains(ev.target) && ev.target.id !== 'menuBtn') {
          if (panel.parentNode) panel.parentNode.removeChild(panel);
          document.removeEventListener('pointerdown', close);
        }
      });
    }, 0);
  }

  function pushClipboard() {
    if (!navigator.clipboard || !navigator.clipboard.readText) {
      toast('This browser does not allow reading the clipboard — use Ctrl+V instead', 'bad');
      return;
    }
    navigator.clipboard.readText().then(function (text) {
      if (!text) { toast('Clipboard is empty'); return; }
      api('/api/clipboard', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: text })
      }).then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        if (connected) ws.send(jsonMsg(T.CLIPBOARD, { kind: 'text', data: text }));
        imeSent = text;
        toast('Clipboard sent to the host (' + text.length + ' chars)', 'good');
      }).catch(function (e) { toast('Failed: ' + e.message, 'bad'); });
    }).catch(function () {
      toast('Permission denied — press Ctrl+V in the page instead', 'bad');
    });
  }

  function pullClipboard() {
    api('/api/clipboard').then(function (r) { return r.json(); }).then(function (d) {
      if (!d.text) { toast('Host clipboard is empty'); return; }
      copyToClipboard(d.text);
      imeSent = d.text;
      toast('Host clipboard copied here (' + d.text.length + ' chars)', 'good');
    }).catch(function (e) { toast('Failed: ' + e.message, 'bad'); });
  }

  // Single source of truth for presets: the menu, the keyboard shortcut and
  // the automatic downgrade all read this, so they can never disagree.
  var PRESETS = [
    { label: 'Sharp', q: 85, fps: 30 },
    { label: 'Balanced', q: 70, fps: 20 },
    { label: 'Data saver', q: 55, fps: 15 },
    { label: 'Low bandwidth', q: 40, fps: 10 }
  ];
  var QUALITY_ORDER = PRESETS.map(function (p) { return p.q; });

  function presetFor(q) {
    for (var i = 0; i < PRESETS.length; i++) {
      if (PRESETS[i].q === q) return PRESETS[i];
    }
    return null;
  }

  /** Applies a preset (or an arbitrary slider value) and tells the host. */
  function applyQualityPreset(q) {
    var p = presetFor(q);
    quality = p ? p.q : q;
    fps = p ? p.fps : fps;
    store(Q_KEY, quality);
    store(FPS_KEY, fps);
    // Keep the connect-screen slider in step when it is on screen.
    if ($('quality')) $('quality').value = quality;
    if ($('fps')) $('fps').value = fps;
    if (typeof syncQualityLabels === 'function') syncQualityLabels();
    if (connected && ws) ws.send(qualityMsg(quality, fps, 0));
    return p;
  }

  function showQualityMenu() {
    var panel = document.getElementById('qualityMenu');
    if (panel) { panel.parentNode.removeChild(panel); return; }
    panel = document.createElement('div');
    panel.id = 'qualityMenu';
    panel.style.cssText = 'position:absolute;right:10px;top:48px;z-index:24;background:var(--surface);' +
      'border:1px solid var(--line);border-radius:14px;padding:10px;box-shadow:var(--shadow);width:250px';
    panel.innerHTML = '<div style="font-size:11px;color:var(--dim);font-weight:700;' +
      'letter-spacing:.06em;margin-bottom:4px">STREAM QUALITY</div>';
    PRESETS.forEach(function (p) {
      var b = document.createElement('button');
      b.className = 'btn' + (quality === p.q ? ' active' : '');
      b.style.cssText = 'width:100%;justify-content:space-between;margin:2px 0;padding:10px 12px';
      b.innerHTML = '<span>' + p.label + '</span><span style="color:var(--dim);font-size:11px">q' +
        p.q + ' · ' + p.fps + 'fps</span>';
      b.addEventListener('click', function () {
        applyQualityPreset(p.q);
        if (panel.parentNode) panel.parentNode.removeChild(panel);
        toast('Quality: ' + p.label + ' (q' + quality + ', ' + fps + ' fps)', 'good');
      });
      panel.appendChild(b);
    });
    el.stage.appendChild(panel);
    setTimeout(function () {
      document.addEventListener('pointerdown', function close(ev) {
        if (!panel.contains(ev.target) && ev.target.id !== 'qualityBtn') {
          if (panel.parentNode) panel.parentNode.removeChild(panel);
          document.removeEventListener('pointerdown', close);
        }
      });
    }, 0);
  }

  // Toolbar buttons collapse into the menu on narrow screens; this keeps every
  // action reachable on a phone without horizontal scrolling.
  function wireToolbar() {
    $('modeBtn').addEventListener('click', function () {
      setMode(mode === MODE_MOUSE ? MODE_TOUCH : MODE_MOUSE);
    });
    $('kbBtn').addEventListener('click', function () { focusIme(); toast('Keyboard ready — start typing'); });
    $('pasteBtn').addEventListener('click', pushClipboard);
    $('copyBtn').addEventListener('click', pullClipboard);
    $('qualityBtn').addEventListener('click', showQualityMenu);
    $('filesBtn').addEventListener('click', function () { toggleDrawer(); });
    $('statsBtn').addEventListener('click', toggleStats);
    $('pairBtn').addEventListener('click', function () { showPair(!$('pairPanel').classList.contains('open')); });
    $('helpBtn').addEventListener('click', function () { showHelp(!$('help').classList.contains('open')); });
    $('fsBtn').addEventListener('click', toggleFullscreen);
    $('menuBtn').addEventListener('click', showMenu);

    $('drawerClose').addEventListener('click', function () { toggleDrawer(false); });
    $('refreshBtn').addEventListener('click', function () { loadFiles(cwd); });
    $('uploadBtn').addEventListener('click', function () { $('uploadInput').click(); });
    $('uploadInput').addEventListener('change', function (e) {
      uploadFiles(e.target.files);
      e.target.value = '';
    });
    $('pairClose').addEventListener('click', function () { showPair(false); });
    $('helpClose').addEventListener('click', function () { showHelp(false); });
    $('pairUrl').addEventListener('click', function () {
      var url = $('pairUrl').textContent;
      copyToClipboard(url);
      toast('Pairing link copied', 'good');
    });
    $('overlayBack').addEventListener('click', function () {
      cancelReconnect();
      showLoginCard();
    });
    window.addEventListener('resize', positionCursor);
  }

  function wireLogin() {
    // Restore the last session so returning users just press Connect.
    el.hostInput.value = recall(HOST_KEY, '');
    el.tokenInput.value = recall(TOK_KEY, '');
    mode = recall(MODE_KEY, MODE_TOUCH);
    quality = parseInt(recall(Q_KEY, '70'), 10) || 70;
    fps = parseInt(recall(FPS_KEY, '20'), 10) || 20;

    var qv = recall(Q_KEY, '70');
    $('quality').value = quality;
    $('fps').value = fps;
    syncQualityLabels();

    $('loginTouch').addEventListener('click', function () {
      setLoginMode(MODE_TOUCH);
    });
    $('loginMouse').addEventListener('click', function () {
      setLoginMode(MODE_MOUSE);
    });
    $('quality').addEventListener('input', function () { quality = parseInt(this.value, 10); syncQualityLabels(); });
    $('fps').addEventListener('input', function () { fps = parseInt(this.value, 10); syncQualityLabels(); });

    $('connectBtn').addEventListener('click', function () {
      token = el.tokenInput.value.trim();
      var host = el.hostInput.value.trim();
      store(TOK_KEY, token);
      store(HOST_KEY, host);
      store(Q_KEY, quality); store(FPS_KEY, fps); store(MODE_KEY, mode);
      showLoginError('');
      var btn = $('connectBtn');
      btn.disabled = true;
      btn.textContent = 'Connecting…';
      setTimeout(function () { btn.disabled = false; btn.textContent = 'Connect'; }, 4000);
      connect();
    });
    el.tokenInput.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') $('connectBtn').click();
    });
    el.hostInput.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') $('connectBtn').click();
    });

    setLoginMode(mode);
  }

  function setLoginMode(m) {
    mode = m;
    store(MODE_KEY, m);
    $('loginTouch').classList.toggle('on', m === MODE_TOUCH);
    $('loginMouse').classList.toggle('on', m === MODE_MOUSE);
    el.stage.classList.toggle('mouse-mode', m === MODE_MOUSE);
  }

  function syncQualityLabels() {
    var q = parseInt($('quality').value, 10);
    var name = q >= 85 ? 'sharp' : q >= 70 ? 'balanced' : q >= 55 ? 'data saver' : 'low bandwidth';
    $('qualityVal').textContent = name + ' (q' + q + ')';
    $('fpsVal').textContent = $('fps').value + ' fps';
  }

  function init() {
    el.stage = $('stage');
    el.screen = $('screen');
    el.cursor = $('cursor');
    el.overlay = $('overlay');
    el.ime = $('ime');
    el.tokenInput = $('token');
    el.hostInput = $('hostField');

    bindPointer();
    bindKeyboard();
    bindVisibility();
    wireToolbar();
    wireLogin();

    // Ask the server what it is; pre-fills the token hint and detects a version
    // mismatch before the user hits a confusing failure.
    api('/api/info').then(function (r) { return r.json(); }).then(function (info) {
      if (info && info.auth === false) el.tokenInput.placeholder = 'no token required';
      if (info && info.version) {
        var note = $('hostNote');
        if (note) note.textContent = 'Server ' + info.version + ' · ' + (info.mode || 'app') +
          ' mode · leave empty to use this page\'s server.';
      }
    }).catch(function () { /* user will connect manually */ });

    initialized = true;
  }

  if (typeof document !== 'undefined' && document.getElementById) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', init);
    } else {
      init();
    }
  }

  // Exposed for the automated tests.
  if (typeof window !== 'undefined') {
    window.DevvioRD = {
      protocol: T,
      jsonMsg: jsonMsg, touchMsg: touchMsg, scrollMsg: scrollMsg, pingMsg: pingMsg,
      commonPrefix: commonPrefix, codePointLength: codePointLength,
      state: function () {
        return {
          connected: connected, mode: mode, serverW: serverW, serverH: serverH,
          fps: fpsValue, rtt: rtt, statsOn: statsOn, retryCount: retryCount
        };
      },
      imeChanged: imeChanged,
      isReady: function () { return initialized; },
      setImeSent: function (v) { imeSent = v; },
      connect: connect,
      linkQuality: linkQuality,
      // Feeds a synthetic round-trip sample through the same code path as a
      // real PONG, so the quality indicator can be exercised without a host.
      sampleRtt: sampleRtt,
      presetFps: function () { return fps; },
      presets: function () { return PRESETS.slice(); },
      paused: function () { return paused; },
      loadFiles: loadFiles,
      setMode: setMode,
      toggleStats: toggleStats,
      showPair: showPair,
      disconnect: disconnect
    };
  }
})();
