/*
 * DevvioRD web client.
 *
 * Mirrors the Android app: same binary protocol, same touch/mouse semantics,
 * same IME text-diffing approach, same clipboard + file transfer REST calls.
 * Written with no dependencies so it also runs from `file://` for debugging.
 */
(function () {
  'use strict';

  // ------------------------------------------------------------ protocol
  var T = {
    HELLO: 0x01, TOUCH: 0x10, SCROLL: 0x11, KEY: 0x14, TEXT: 0x15,
    CLIPBOARD: 0x20, POINTER_MODE: 0x21, PING: 0x40, QUALITY: 0x41,
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
  function connect() {
    var proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    var url = proto + location.host + '/ws';
    if (token) url += '?token=' + encodeURIComponent(token);
    setStatus('connecting…', 'warn');
    try {
      ws = new WebSocket(url);
    } catch (e) {
      showLoginError('Cannot open websocket: ' + e.message);
      return;
    }
    ws.binaryType = 'arraybuffer';

    opened = false;
    ws.onopen = function () {
      opened = true;
      var hello = {
        version: 1,
        client: 'web/1.0.0',
        device: navigator.userAgent.slice(0, 60),
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
      connected = false;
      stopTimers();
      setStatus('disconnected', 'err');
      // 1006 right after an attempt usually means the HTTP upgrade was refused
      // (bad or missing token). Ask for credentials instead of retry-looping.
      if (ev && ev.code === 1006 && !closing && !opened) {
        showLoginError('Connection refused — check the access token and that the server is running.');
        return;
      }
      if (!closing) {
        el.overlay.style.display = 'flex';
        $('overlayMsg').textContent = 'The session ended. Reconnecting in 3s…';
        setTimeout(function () { el.overlay.style.display = 'none'; connect(); }, 3000);
      }
    };

    ws.onerror = function () {
      setStatus('connection error', 'err');
    };
  }

  function handleMessage(buf) {
    var type = buf[0];
    switch (type) {
      case T.HELLO_OK:
        onHelloOk(JSON.parse(dec.decode(buf.subarray(1))));
        break;
      case T.FRAME:
        onFrame(buf);
        break;
      case T.STATUS:
        try {
          var st = JSON.parse(dec.decode(buf.subarray(1)));
          if (st.code !== 'hello') toast(st.message);
        } catch (e) { /* ignore */ }
        break;
      case T.CLIP:
        try {
          var cb = JSON.parse(dec.decode(buf.subarray(1)));
          if (cb.data) {
            imeSent = cb.data;
            copyToClipboard(cb.data);
            toast('Host clipboard copied to this device');
          }
        } catch (e) { /* ignore */ }
        break;
      case T.CURSOR:
        try {
          var cu = JSON.parse(dec.decode(buf.subarray(1)));
          cursorX = cu.x; cursorY = cu.y;
          positionCursor();
        } catch (e) { /* ignore */ }
        break;
      case T.SCREEN:
        try {
          var sc = JSON.parse(dec.decode(buf.subarray(1)));
          serverW = sc.width; serverH = sc.height;
        } catch (e) { /* ignore */ }
        break;
      case T.PONG:
        var dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
        var ms = Number(dv.getBigInt64 ? dv.getBigInt64(1, false) : dv.getUint32(1, false) * 4294967296 + dv.getUint32(5, false));
        rtt = Date.now() - ms;
        break;
      case T.ERROR:
        try {
          var er = JSON.parse(dec.decode(buf.subarray(1)));
          setStatus(er.message, 'err');
        } catch (e) { /* ignore */ }
        break;
    }
  }

  function onHelloOk(info) {
    connected = true;
    closing = false;
    serverW = info.width || serverW;
    serverH = info.height || serverH;
    $('login').style.display = 'none';
    $('bar').style.display = 'flex';
    el.overlay.style.display = 'none';
    setStatus('connected · ' + (info.hostname || 'host'), 'on');
    ws.send(jsonMsg(T.POINTER_MODE, { mode: mode }));
    ws.send(qualityMsg(quality, fps, 0));
    startTimers();
    loadFiles('');
    if (info.needsAuth === false) {
      el.tokenInput.placeholder = 'no token required';
    }
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
        if (statsOn) updateStats();
      }
    }, 500);
  }

  function stopTimers() {
    if (pingTimer) clearInterval(pingTimer);
    if (statTimer) clearInterval(statTimer);
    pingTimer = statTimer = null;
  }

  function updateStats() {
    setStatus('fps ' + fpsValue + ' · ' + serverW + '×' + serverH + ' · ' +
      Math.round(bytes / 1024) + ' KiB · rtt ' + (rtt < 0 ? '?' : rtt) + 'ms', 'on');
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
      drainFrames();
    };
    img.onerror = function () { URL.revokeObjectURL(url); drainFrames(); };
    img.src = url;
  }

  // ------------------------------------------------------------ input
  function stageRect() {
    var r = el.stage.getBoundingClientRect();
    // `object-fit: contain` letterboxes the image; mirror that math here so a
    // tap maps to the same relative point on the remote desktop.
    var ar = serverW / serverH;
    var w = r.width, h = r.height;
    var dw, dh, dx, dy;
    if (w / h > ar) { dh = h; dw = h * ar; dx = (w - dw) / 2; dy = 0; }
    else { dw = w; dh = w / ar; dx = 0; dy = (h - dh) / 2; }
    return { left: r.left + dx, top: r.top + dy, w: dw, h: dh };
  }

  function norm(clientX, clientY) {
    var r = stageRect();
    return {
      x: Math.min(1, Math.max(0, (clientX - r.left) / r.w)),
      y: Math.min(1, Math.max(0, (clientY - r.top) / r.h))
    };
  }

  function positionCursor() {
    if (mode !== MODE_MOUSE) { el.cursor.style.display = 'none'; return; }
    var r = stageRect();
    el.cursor.style.display = 'block';
    el.cursor.style.left = (r.left - el.stage.getBoundingClientRect().left + cursorX * r.w) + 'px';
    el.cursor.style.top = (r.top - el.stage.getBoundingClientRect().top + cursorY * r.h) + 'px';
  }

  function sendTouch(action, x, y, pressure) {
    if (connected) ws.send(touchMsg(action, x, y, pressure));
  }

  function onDown(x, y) {
    downX = x; downY = y; lastX = x; lastY = y; dragging = false;
    if (mode === MODE_TOUCH) sendTouch(TOUCH_DOWN, x, y, 1);
  }

  function onMove(x, y) {
    if (mode === MODE_TOUCH) {
      sendTouch(TOUCH_MOVE, x, y, 1);
    } else {
      // trackpad behaviour: relative movement of the virtual cursor
      var r = stageRect();
      cursorX = Math.min(1, Math.max(0, cursorX + (x - lastX) * (serverW / r.w) * 0.9));
      cursorY = Math.min(1, Math.max(0, cursorY + (y - lastY) * (serverH / r.h) * 0.9));
      lastX = x; lastY = y;
      sendTouch(TOUCH_MOVE, cursorX, cursorY, 1);
      positionCursor();
    }
    if (!dragging && Math.hypot(x * 1000 - downX * 1000, y * 1000 - downY * 1000) > 8) dragging = true;
  }

  function onUp(x, y) {
    if (mode === MODE_TOUCH) {
      sendTouch(TOUCH_UP, x, y, 0);
    } else if (!dragging) {
      sendTouch(TOUCH_DOWN, cursorX, cursorY, 1);
      sendTouch(TOUCH_UP, cursorX, cursorY, 0);
    }
    var now = Date.now();
    if (now - lastTapAt < 320 && Math.hypot(x - lastTapX, y - lastTapY) < 0.04) {
      if (mode === MODE_TOUCH) {
        sendTouch(TOUCH_DOWN, x, y, 1);
        sendTouch(TOUCH_UP, x, y, 0);
      } else {
        sendTouch(TOUCH_DOWN, cursorX, cursorY, 1);
        sendTouch(TOUCH_UP, cursorX, cursorY, 0);
        sendTouch(TOUCH_DOWN, cursorX, cursorY, 1);
        sendTouch(TOUCH_UP, cursorX, cursorY, 0);
      }
      lastTapAt = 0;
      return;
    }
    lastTapAt = now; lastTapX = x; lastTapY = y;
    dragging = false;
  }

  function bindPointer() {
    var active = {}, activeCount = 0, scrollState = null;

    el.stage.addEventListener('pointerdown', function (e) {
      if (e.target.closest && e.target.closest('#drawer')) return;
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

  function bindKeyboard() {
    document.addEventListener('keydown', function (e) {
      if (!connected) return;
      if (e.target && e.target.tagName === 'INPUT' && e.target.id !== 'ime') return;
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
        el.ime.focus({ preventScroll: true });
      }
    });

    var lastImeValue = '';
    el.ime.addEventListener('input', function () {
      imeChanged(el.ime.value);
      lastImeValue = el.ime.value;
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
        toast('Pasted ' + text.length + ' chars to host');
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
    return fetch(path, opts);
  }

  function esc(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function human(n) {
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
    if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB';
    return (n / 1073741824).toFixed(2) + ' GB';
  }

  function loadFiles(dir) {
    cwd = dir || '';
    var list = $('filelist');
    list.innerHTML = '<div class="path">loading…</div>';
    api('/api/files?dir=' + encodeURIComponent(cwd)).then(function (r) { return r.json(); }).then(function (data) {
      $('drawerPath').textContent = '/' + cwd;
      var html = '';
      if (cwd) html += '<div class="file" data-dir="up"><span>⬑</span><span class="name">..</span></div>';
      (data.entries || []).forEach(function (e) {
        html += '<div class="file" data-path="' + esc(e.path) + '" data-dir="' + (e.dir ? '1' : '0') +
          '" data-name="' + esc(e.name) + '">' +
          '<span>' + (e.dir ? '📁' : '📄') + '</span>' +
          '<span class="name">' + esc(e.name) + '</span>' +
          '<span class="size">' + (e.dir ? '' : human(e.size)) + '</span></div>';
      });
      if (!data.entries || !data.entries.length) html += '<div class="path">empty folder</div>';
      list.innerHTML = html;
      Array.prototype.forEach.call(list.querySelectorAll('.file'), function (node) {
        node.addEventListener('click', function () {
          if (node.getAttribute('data-dir') === 'up') {
            var i = cwd.lastIndexOf('/');
            loadFiles(i <= 0 ? '' : cwd.slice(0, i));
            return;
          }
          var path = node.getAttribute('data-path');
          if (node.getAttribute('data-dir') === '1') {
            loadFiles(path);
          } else {
            download(path, node.getAttribute('data-name'));
          }
        });
      });
    }).catch(function (e) {
      list.innerHTML = '<div class="path">failed to load: ' + esc(e.message) + '</div>';
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
      toast('Saved ' + name);
    }).catch(function (e) { toast('Download failed: ' + e.message); });
  }

  function uploadFiles(files) {
    var i = 0;
    function next() {
      if (i >= files.length) { loadFiles(cwd); return; }
      var f = files[i++];
      toast('Uploading ' + f.name + ' (' + human(f.size) + ')');
      api('/api/upload/begin', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: f.name, dir: cwd, size: f.size })
      }).then(function (r) { return r.json(); }).then(function (info) {
        var CHUNK = 1 << 20;
        var offset = info.offset || 0;
        function sendChunk() {
          if (offset >= f.size) {
            return api('/api/upload/finish', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ id: info.id })
            }).then(function (r) {
              if (!r.ok) throw new Error('finish failed');
              toast('Uploaded ' + f.name);
              next();
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
            offset += CHUNK;
            return sendChunk();
          });
        }
        return sendChunk();
      }).catch(function (e) {
        toast('Upload failed: ' + e.message);
        next();
      });
    }
    next();
  }

  // ------------------------------------------------------------ ui glue
  function setStatus(text, kind) {
    var s = $('status');
    s.textContent = text;
    s.className = 'pill' + (kind ? ' ' + kind : '');
  }

  var toastTimer = null;
  function toast(msg) {
    var t = $('toast');
    t.textContent = msg;
    t.classList.add('show');
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.classList.remove('show'); }, 1800);
  }

  function setMode(m) {
    mode = m;
    var b = $('modeBtn');
    b.textContent = m === MODE_MOUSE ? 'MOUSE' : 'TOUCH';
    b.classList.toggle('active', m === MODE_MOUSE);
    el.stage.classList.toggle('mouse-mode', m === MODE_MOUSE);
    positionCursor();
    if (connected) ws.send(jsonMsg(T.POINTER_MODE, { mode: m }));
    toast(m === MODE_MOUSE ? 'Mouse mode: drag to move, tap to click' : 'Touch mode: tap directly');
  }

  function showLoginError(msg) {
    $('loginErr').textContent = msg;
  }

  // ------------------------------------------------------------ pairing
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

  function showPairPanel() {
    var panel = $('pairPanel');
    panel.classList.add('open');
    api('/api/pair').then(function (r) { return r.json(); }).then(function (info) {
      $('qrBox').innerHTML = renderQR(info.qr);
      $('pairUrl').textContent = info.pairUrl || '';
      $('pairHint').textContent = info.auth
        ? 'Token required. The QR contains only the address — enter the token in the app.'
        : 'No token configured: scanning connects immediately.';
    }).catch(function (e) {
      $('qrBox').innerHTML = '';
      $('pairHint').textContent = 'Could not load pairing info: ' + e.message;
    });
  }

  function init() {
    el.stage = $('stage');
    el.screen = $('screen');
    el.cursor = $('cursor');
    el.overlay = $('overlay');
    el.ime = $('ime');
    el.tokenInput = $('token');

    bindPointer();
    bindKeyboard();

    $('connectBtn').addEventListener('click', function () {
      token = el.tokenInput.value.trim();
      quality = parseInt($('quality').value, 10) || 70;
      showLoginError('');
      connect();
    });
    el.tokenInput.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') $('connectBtn').click();
    });

    $('modeBtn').addEventListener('click', function () {
      setMode(mode === MODE_TOUCH ? MODE_MOUSE : MODE_TOUCH);
    });
    $('kbBtn').addEventListener('click', function () {
      el.ime.value = '';
      imeSuppress = true;
      imeSent = '';
      el.ime.focus();
      toast('Keyboard ready — typing goes to the host');
    });
    $('pasteBtn').addEventListener('click', function () {
      if (navigator.clipboard && navigator.clipboard.readText) {
        navigator.clipboard.readText().then(function (text) {
          if (!text) { toast('Clipboard is empty'); return; }
          if (connected) ws.send(jsonMsg(T.CLIPBOARD, { kind: 'text', data: text }));
          imeSent = text;
          toast('Sent ' + text.length + ' chars to host clipboard');
        }).catch(function () {
          toast('Clipboard permission denied — use Ctrl+V in the session');
          el.ime.focus();
        });
      } else {
        toast('Use Ctrl+V to paste into the session');
        el.ime.focus();
      }
    });
    $('copyBtn').addEventListener('click', function () {
      api('/api/clipboard').then(function (r) { return r.json(); }).then(function (d) {
        if (d.text) {
          copyToClipboard(d.text);
          imeSent = d.text;
          toast('Host clipboard copied (' + d.text.length + ' chars)');
        } else {
          toast('Host clipboard is empty');
        }
      }).catch(function (e) { toast('Failed: ' + e.message); });
    });
    $('pairBtn').addEventListener('click', showPairPanel);
    $('pairClose').addEventListener('click', function () {
      $('pairPanel').classList.remove('open');
    });
    $('filesBtn').addEventListener('click', function () {
      var d = $('drawer');
      d.classList.toggle('open');
      if (d.classList.contains('open')) loadFiles(cwd);
    });
    $('drawerClose').addEventListener('click', function () { $('drawer').classList.remove('open'); });
    $('refreshBtn').addEventListener('click', function () { loadFiles(cwd); });
    $('uploadBtn').addEventListener('click', function () { $('uploadInput').click(); });
    $('uploadInput').addEventListener('change', function (e) {
      if (e.target.files && e.target.files.length) uploadFiles(Array.prototype.slice.call(e.target.files));
      e.target.value = '';
    });
    $('statsBtn').addEventListener('click', function () {
      statsOn = !statsOn;
      this.classList.toggle('active', statsOn);
      if (statsOn) updateStats();
    });
    $('fsBtn').addEventListener('click', function () {
      if (!document.fullscreenElement) {
        (document.documentElement.requestFullscreen || function () { }).call(document.documentElement);
      } else {
        (document.exitFullscreen || function () { }).call(document);
      }
    });
    window.addEventListener('resize', positionCursor);

    // Auto-connect when the server has no token configured.
    api('/api/info').then(function (r) { return r.json(); }).then(function (info) {
      if (info && info.auth === false) {
        quality = info.quality || 70;
        connect();
      }
    }).catch(function () { /* user will connect manually */ });

    setMode(MODE_TOUCH);
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
        return { connected: connected, mode: mode, serverW: serverW, serverH: serverH, fps: fpsValue, rtt: rtt };
      },
      imeChanged: imeChanged,
      isReady: function () { return initialized; },
      setImeSent: function (v) { imeSent = v; },
      connect: connect,
      loadFiles: loadFiles,
      setMode: setMode
    };
  }
})();
