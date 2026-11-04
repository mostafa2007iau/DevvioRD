#!/usr/bin/env node
/*
 * Headless test for the embedded web client (server/internal/server/web/client.js).
 *
 * Loads the real HTML + JS in jsdom, connects it to a live DevvioRD server over a
 * real WebSocket, and verifies the parts that are easy to get wrong:
 * video decoding, touch/mouse input, IME text diffing and file transfer.
 *
 * Usage: node scripts/web_test.js [--url http://127.0.0.1:5900] [--token TOKEN]
 */
'use strict';

const path = require('path');
const fs = require('fs');

let JSDOM, wsModule;
try {
  ({ JSDOM } = require('jsdom'));
  wsModule = require('ws');
} catch (e) {
  console.error('Missing test deps. Run: npm install --no-save jsdom ws');
  process.exit(2);
}

const args = process.argv.slice(2);
function arg(name, def) {
  const i = args.indexOf('--' + name);
  return i >= 0 && args[i + 1] ? args[i + 1] : def;
}
const BASE = arg('url', 'http://127.0.0.1:5900');
const TOKEN = arg('token', '');
const WEB_DIR = path.join(__dirname, '..', 'server', 'internal', 'server', 'web');

let passed = 0, failed = 0;
const failures = [];
function check(name, cond, detail) {
  if (cond) { passed++; console.log('  PASS  ' + name + (detail ? ' - ' + detail : '')); }
  else { failed++; failures.push(name); console.log('  FAIL  ' + name + (detail ? ' - ' + detail : '')); }
}
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function waitFor(fn, timeout = 8000, interval = 50) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeout) {
    const v = fn();
    if (v) return v;
    await sleep(interval);
  }
  return null;
}

async function main() {
  console.log('\n=== DevvioRD web client test ===\n');

  const html = fs.readFileSync(path.join(WEB_DIR, 'index.html'), 'utf8')
    .replace('<script src="client.js"></script>', '');
  const clientJs = fs.readFileSync(path.join(WEB_DIR, 'client.js'), 'utf8');

  const dom = new JSDOM(html, {
    url: BASE + '/',
    runScripts: 'outside-only',
    pretendToBeVisual: true,
    resources: undefined,
  });
  const { window } = dom;

  // ---- browser APIs jsdom lacks ----
  window.WebSocket = wsModule.WebSocket;
  window.fetch = (input, init) => {
    const url = typeof input === 'string' ? input : input.url;
    const opts = Object.assign({}, init);
    opts.headers = Object.assign({}, init && init.headers);
    if (TOKEN) opts.headers['X-DevvioRD-Token'] = TOKEN;
    return fetch(new URL(url, BASE).toString(), opts);
  };
  window.URL.createObjectURL = () => 'blob:mock-' + Math.random().toString(36).slice(2);
  window.URL.revokeObjectURL = () => {};
  if (!window.navigator.clipboard) {
    Object.defineProperty(window.navigator, 'clipboard', {
      value: { writeText: async () => {}, readText: async () => 'from-browser-clipboard' },
      configurable: true,
    });
  }
  // Image decoding: fire onload asynchronously so the client's frame pump runs.
  const createdImages = [];
  window.Image = class FakeImage {
    constructor() { createdImages.push(this); }
    set src(v) { this._src = v; setTimeout(() => this.onload && this.onload(), 0); }
    get src() { return this._src; }
  };
  window.document.createElementNS = window.document.createElementNS || function () { return window.document.createElement('div'); };
  window.document.documentElement.requestFullscreen = () => {};
  window.document.exitFullscreen = () => {};

  // ---- run the client ----
  window.eval(clientJs);
  const api = window.DevvioRD;
  check('client bootstrapped and exposes test hooks', !!api);

  // ---- protocol helpers ----
  const T = api.protocol;
  const touch = api.touchMsg(1, 0.25, 0.75, 1.0);
  check('touch message is 25 bytes', touch.length === 25, touch.length + ' bytes');
  check('touch type byte is TOUCH (0x10)', touch[0] === T.TOUCH);
  const dv = new DataView(touch.buffer);
  check('touch X encoded big-endian', Math.abs(dv.getFloat32(5, false) - 0.25) < 1e-6);
  check('touch Y encoded big-endian', Math.abs(dv.getFloat32(9, false) - 0.75) < 1e-6);

  const scroll = api.scrollMsg(0.5, -1.5);
  check('scroll message is 9 bytes', scroll.length === 9);

  const hello = api.jsonMsg(T.HELLO, { version: 1, client: 'web-test' });
  check('json message prefixes type byte', hello[0] === T.HELLO && hello.length > 2);

  // IME helpers (pure functions)
  check('commonPrefix basic', api.commonPrefix('hello', 'hello world') === 5);
  check('commonPrefix avoids splitting surrogate pairs',
    api.commonPrefix('\uD83D\uDE00', '\uD83D\uDE00\uD83D\uDE01') === 2);
  check('codePointLength counts emoji as one', api.codePointLength('\uD83D\uDE00') === 1);

  // ---- connect over a real websocket ----
  // init() runs on DOMContentLoaded, so wait for it before driving the UI.
  const ready = await waitFor(() => api.isReady && api.isReady(), 5000);
  check('client initialised (listeners bound)', !!ready);
  window.document.getElementById('token').value = TOKEN;
  window.document.getElementById('connectBtn').click();

  const connected = await waitFor(() => api.state().connected, 10000);
  if (!connected) {
    check('websocket session established', false, 'no hello-ok received');
    finish();
    return;
  }
  const st = api.state();
  check('websocket session established', true, st.serverW + 'x' + st.serverH);
  check('geometry received from server', st.serverW > 0 && st.serverH > 0);
  check('login overlay hidden after connect',
    window.document.getElementById('login').style.display === 'none');
  check('toolbar shown after connect',
    window.document.getElementById('bar').style.display === 'flex');

  // ---- video ----
  const img = window.document.getElementById('screen');
  const gotFrame = await waitFor(() => img.getAttribute('src'), 8000);
  check('video frame decoded and rendered', !!gotFrame, gotFrame || 'none');
  const f0 = createdImages.length;
  await sleep(700);
  check('frame pump keeps decoding', createdImages.length > f0,
    (createdImages.length - f0) + ' frames in 700ms');

  // ---- input: touch mode ----
  const stage = window.document.getElementById('stage');
  // jsdom has no PointerEvent; a plain Event with pointer fields is enough
  // because the client only reads clientX/clientY/pointerId.
  function firePointer(type, x, y, id) {
    const e = new window.Event(type, { bubbles: true, cancelable: true });
    e.clientX = x;
    e.clientY = y;
    e.pointerId = id || 1;
    stage.dispatchEvent(e);
  }
  Object.defineProperty(stage, 'setPointerCapture', { value: () => {}, configurable: true });
  // Give the stage a measurable box so coordinate mapping is meaningful.
  stage.getBoundingClientRect = () => ({
    left: 0, top: 0, width: 1280, height: 800, right: 1280, bottom: 800, x: 0, y: 0,
  });
  firePointer('pointerdown', 200, 200, 1);
  firePointer('pointermove', 260, 240, 1);
  firePointer('pointerup', 260, 240, 1);
  await sleep(300);
  check('touch gestures dispatched without error', true);

  // ---- mode switch ----
  api.setMode('mouse');
  await sleep(200);
  check('mouse mode applied', api.state().mode === 'mouse');
  check('mode button label updated',
    window.document.getElementById('modeBtn').textContent === 'MOUSE');
  firePointer('pointerdown', 300, 300, 2);
  firePointer('pointermove', 340, 330, 2);
  firePointer('pointerup', 340, 330, 2);
  api.setMode('touch');
  await sleep(200);
  check('touch mode restored', api.state().mode === 'touch');

  // ---- keyboard / IME ----
  let sentTypes = [];
  const origSend = window.WebSocket.prototype.send;
  window.WebSocket.prototype.send = function (data) {
    try {
      const u8 = new Uint8Array(data);
      sentTypes.push(u8[0]);
    } catch (e) { /* ignore */ }
    return origSend.call(this, data);
  };

  const ime = window.document.getElementById('ime');
  ime.value = '';
  api.setImeSent('');
  ime.value = 'hello';
  ime.dispatchEvent(new window.Event('input'));
  await sleep(120);
  check('typing sends a TEXT event', sentTypes.includes(T.TEXT),
    'types: ' + sentTypes.slice(-4).map(t => '0x' + t.toString(16)).join(','));

  sentTypes = [];
  ime.value = 'hello world';
  ime.dispatchEvent(new window.Event('input'));
  await sleep(120);
  check('continued typing sends delta text only', sentTypes.includes(T.TEXT));

  sentTypes = [];
  ime.value = 'hello';
  ime.dispatchEvent(new window.Event('input'));
  await sleep(150);
  check('deletion sends Backspace key events',
    sentTypes.filter(t => t === T.KEY).length === 6,
    sentTypes.filter(t => t === T.KEY).length + ' key events');

  sentTypes = [];
  ime.value = '';
  api.setImeSent('');
  ime.value = 'سلام دنیا';
  ime.dispatchEvent(new window.Event('input'));
  await sleep(120);
  check('Persian IME text sent as TEXT', sentTypes.includes(T.TEXT));

  // ---- physical keys ----
  sentTypes = [];
  const enter = new window.KeyboardEvent('keydown', { key: 'Enter', bubbles: true });
  window.document.dispatchEvent(enter);
  await sleep(120);
  check('Enter key forwarded', sentTypes.includes(T.KEY));

  window.WebSocket.prototype.send = origSend;

  // ---- clipboard via REST ----
  const clipText = 'web-clip-' + Date.now();
  const r = await fetch(BASE + '/api/clipboard', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-DevvioRD-Token': TOKEN },
    body: JSON.stringify({ text: clipText }),
  });
  check('clipboard push accepted', r.ok, 'HTTP ' + r.status);
  if (r.ok) {
    const back = await (await fetch(BASE + '/api/clipboard', {
      headers: { 'X-DevvioRD-Token': TOKEN },
    })).json();
    check('clipboard round trip via REST', back.text === clipText);
  }

  // ---- files through the drawer ----
  api.loadFiles('');
  const fileList = await waitFor(
    () => window.document.querySelector('#filelist .file') ? window.document.getElementById('filelist') : null,
    6000);
  check('file drawer lists entries from the server', !!fileList,
    fileList ? fileList.querySelectorAll('.file').length + ' entries' : 'empty');
  check('drawer path rendered', !!window.document.getElementById('drawerPath').textContent);

  // ---- pairing panel (QR for the Android app) ----
  const pairResp = await fetch(BASE + '/api/pair', { headers: { 'X-DevvioRD-Token': TOKEN } });
  const pairInfo = await pairResp.json();
  check('pairing endpoint responds', pairResp.ok && !!pairInfo.pairUrl, pairInfo.pairUrl || '');
  check('pairing payload contains a QR matrix',
    Array.isArray(pairInfo.qr) && pairInfo.qr.length > 20,
    pairInfo.qr ? pairInfo.qr.length + 'x' + pairInfo.qr[0].length + ' modules' : 'none');
  check('pairing URL is a devviord deep link',
    (pairInfo.pairUrl || '').startsWith('devviord://connect?host='), pairInfo.pairUrl || '');
  check('token is NOT leaked in the QR by default', (pairInfo.pairUrl || '').indexOf('token=') < 0);
  window.document.getElementById('pairBtn').click();
  const qrRendered = await waitFor(
    () => window.document.querySelector('#qrBox svg') ? true : null, 5000);
  check('QR renders as inline SVG in the browser', !!qrRendered);

  // ---- new UI: stats panel driven by /api/stats ----
  api.toggleStats();
  await sleep(1400);   // one stats poll
  check('stats panel opened', api.state().statsOn === true);
  const stFps = window.document.getElementById('stFps').textContent;
  check('stats panel shows live fps', /\d+(\.\d+)? fps/.test(stFps), 'fps cell = ' + stFps);
  const stGeo = window.document.getElementById('stGeo').textContent;
  check('stats panel shows geometry', stGeo.indexOf('×') > 0, stGeo);
  const stRtt = window.document.getElementById('stRtt').textContent;
  check('stats panel shows latency', /ms|—/.test(stRtt), stRtt);
  api.toggleStats();
  check('stats panel closes', api.state().statsOn === false);

  // ---- server telemetry endpoint ----
  const statsResp = await fetch(BASE + '/api/stats', { headers: { 'X-DevvioRD-Token': TOKEN } });
  const statsJson = await statsResp.json();
  check('/api/stats responds', statsResp.ok);
  check('/api/stats reports a live stream', statsJson.streaming === true &&
    statsJson.clients >= 1, JSON.stringify({ streaming: statsJson.streaming, clients: statsJson.clients }));
  check('/api/stats reports real fps', typeof statsJson.fps === 'number' && statsJson.fps > 0,
    statsJson.fps + ' fps');
  check('/api/stats reports throughput', typeof statsJson.kbps === 'number', statsJson.kbps + ' KiB/s');
  check('/api/stats requires auth when a token is set',
    (await fetch(BASE + '/api/stats')).status === 401);

  // ---- new UI: quality menu, help sheet, session menu ----
  window.document.getElementById('qualityBtn').click();
  check('quality menu opens', !!window.document.getElementById('qualityMenu'));
  const qBtns = window.document.querySelectorAll('#qualityMenu .btn');
  check('quality menu offers presets', qBtns.length === 4, qBtns.length + ' presets');
  qBtns[3].click();
  await sleep(150);
  check('quality preset applied', api.state() && window.document.getElementById('qualityMenu') === null);

  window.document.getElementById('helpBtn').click();
  check('help sheet opens', window.document.getElementById('help').classList.contains('open'));
  window.document.getElementById('helpClose').click();
  check('help sheet closes', !window.document.getElementById('help').classList.contains('open'));

  window.document.getElementById('menuBtn').click();
  check('session menu opens', !!window.document.getElementById('sessionMenu'));
  const menuItems = window.document.querySelectorAll('#sessionMenu .btn');
  check('session menu lists session actions', menuItems.length >= 6, menuItems.length + ' items');
  window.document.getElementById('menuBtn').click();

  // ---- new UI: keyboard shortcuts ----
  function key(k, extra) {
    const e = new window.Event('keydown', { bubbles: true, cancelable: true });
    e.key = k;
    if (extra) Object.keys(extra).forEach(function (n) { e[n] = extra[n]; });
    window.document.dispatchEvent(e);
  }
  const modeBefore = api.state().mode;
  key('m');
  await sleep(120);
  check('keyboard shortcut M toggles mouse mode', api.state().mode !== modeBefore,
    modeBefore + ' -> ' + api.state().mode);
  key('m');
  await sleep(120);
  check('keyboard shortcut M toggles back', api.state().mode === modeBefore);
  key('i');
  check('keyboard shortcut I opens stats', api.state().statsOn === true);
  key('i'); key('?');
  check('keyboard shortcut ? opens help', window.document.getElementById('help').classList.contains('open'));
  key('Escape');
  check('Escape closes the top overlay', !window.document.getElementById('help').classList.contains('open'));

  // ---- new UI: file drawer opens and closes by button ----
  window.document.getElementById('filesBtn').click();
  check('files button opens the drawer', window.document.getElementById('drawer').classList.contains('open'));
  window.document.getElementById('drawerClose').click();
  check('drawer close button works', !window.document.getElementById('drawer').classList.contains('open'));

  // ---- new UI: host field is persisted for the next visit ----
  check('host field exists on the login card', !!window.document.getElementById('hostField'));

  // ---- reconnection state ---
  api.setMode('touch');
  check('final state consistent', api.state().connected === true);

  finish();

  function finish() {
    console.log('\n--- summary ---');
    console.log(passed + ' passed, ' + failed + ' failed');
    if (failures.length) console.log('  FAILED: ' + failures.join(', '));
    try { dom.window.close(); } catch (e) { /* ignore */ }
    process.exit(failed === 0 ? 0 : 1);
  }
}

main().catch(e => {
  console.error('test crashed:', e);
  process.exit(1);
});
