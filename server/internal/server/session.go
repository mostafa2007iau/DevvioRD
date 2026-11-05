package server

import (
	"encoding/json"
	"log"
	"net"
	"net/http"
	"runtime"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/devviord/devviord/server/internal/capture"
	"github.com/devviord/devviord/server/internal/input"
	"github.com/devviord/devviord/server/internal/proto"
)

// session is one connected client (Android app or web UI).
type session struct {
	id   uint64
	srv  *Server
	conn *wsConn
	addr string

	ctrlQ  chan []byte // control messages: never dropped while a slot is free
	frameQ chan []byte // video frames: latest-wins
	done   chan struct{}
	once   sync.Once

	helloed  atomic.Bool
	mode     atomic.Value // string
	lastSeen atomic.Int64 // unix nano of last inbound message

	framesSent atomic.Uint64
	bytesSent  atomic.Uint64
	dropped    atomic.Uint64

	realtime atomic.Bool // wants the video stream
}

var sessionCounter atomic.Uint64

func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	if !s.checkAuth(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	// Optional login endpoint: /ws?token=...
	conn, err := upgradeWS(w, r)
	if err != nil {
		return
	}
	sess := &session{
		id:     sessionCounter.Add(1),
		srv:    s,
		conn:   conn,
		addr:   r.RemoteAddr,
		ctrlQ:  make(chan []byte, 256),
		frameQ: make(chan []byte, 2),
		done:   make(chan struct{}),
	}
	sess.mode.Store(proto.PointerModeTouch)
	sess.lastSeen.Store(time.Now().UnixNano())

	s.sessMu.Lock()
	if len(s.sessions) >= s.cfg.MaxClients {
		s.sessMu.Unlock()
		_ = conn.writeFrame(opText, []byte(`{"error":"too many clients"}`))
		_ = conn.Close()
		return
	}
	s.sessions[sess] = struct{}{}
	s.sessMu.Unlock()

	if s.cfg.Verbose {
		log.Printf("ws: client #%d connected from %s", sess.id, r.RemoteAddr)
	}
	go sess.writeLoop()
	sess.readLoop()

	s.sessMu.Lock()
	delete(s.sessions, sess)
	s.sessMu.Unlock()
	sess.close()
	if s.cfg.Verbose {
		log.Printf("ws: client #%d disconnected (frames=%d bytes=%d dropped=%d)",
			sess.id, sess.framesSent.Load(), sess.bytesSent.Load(), sess.dropped.Load())
	}
}

// send enqueues a message for the writer.
//
// Video frames use latest-wins semantics: if the network cannot keep up we drop
// the oldest frame instead of queueing stale pictures. Control messages (status,
// clipboard, cursor) have their own queue so they can never be starved by video.
func (s *session) send(b []byte) {
	if len(b) == 0 {
		return
	}
	if b[0] == proto.TypeFrame {
		for {
			select {
			case s.frameQ <- b:
				return
			default:
				// Make room by discarding the oldest frame.
				select {
				case <-s.frameQ:
					s.dropped.Add(1)
				default:
				}
			}
		}
	}
	select {
	case s.ctrlQ <- b:
	case <-s.done:
	default:
		// Queue full: drop the oldest control message rather than the newest.
		select {
		case <-s.ctrlQ:
			s.dropped.Add(1)
		default:
		}
		select {
		case s.ctrlQ <- b:
		case <-s.done:
		default:
			s.dropped.Add(1)
		}
	}
}

func (s *session) writeLoop() {
	pingTicker := time.NewTicker(15 * time.Second)
	defer pingTicker.Stop()

	write := func(b []byte) bool {
		if len(b) == 0 {
			return true
		}
		if err := s.conn.writeFrame(opBinary, b); err != nil {
			s.close()
			return false
		}
		if b[0] == proto.TypeFrame {
			s.framesSent.Add(1)
			s.bytesSent.Add(uint64(len(b)))
		}
		return true
	}

	for {
		// Control messages take priority over video.
		select {
		case <-s.done:
			return
		case b := <-s.ctrlQ:
			if !write(b) {
				return
			}
			continue
		default:
		}

		select {
		case <-s.done:
			return
		case b := <-s.ctrlQ:
			if !write(b) {
				return
			}
		case b := <-s.frameQ:
			if !write(b) {
				return
			}
		case <-pingTicker.C:
			if err := s.conn.writeFrame(opPing, []byte("d")); err != nil {
				s.close()
				return
			}
		}
	}
}

func (s *session) readLoop() {
	for {
		// Timeout protects against half-open connections; clients ping every 5s.
		op, payload, err := s.conn.readMessage(90 * time.Second)
		if err != nil {
			return
		}
		if op != opBinary || len(payload) == 0 {
			continue
		}
		s.lastSeen.Store(time.Now().UnixNano())
		if err := s.handleMessage(payload); err != nil {
			if s.srv.cfg.Verbose {
				log.Printf("ws #%d: %v", s.id, err)
			}
			// Protocol errors are reported but do not kill the session unless
			// no handshake has happened yet.
			if !s.helloed.Load() {
				return
			}
		}
	}
}

func (s *session) handleMessage(b []byte) error {
	typ := b[0]
	body := b[1:]
	if !s.helloed.Load() && typ != proto.TypeHello && typ != proto.TypePing {
		return errNoHello
	}
	switch typ {
	case proto.TypeHello:
		return s.handleHello(body)
	case proto.TypePing:
		// Decode the whole message (type byte included) so the echo value is
		// the client's origin timestamp, not a byte-shifted copy of it.
		ms, err := proto.DecodeInt64(b)
		if err != nil {
			return err
		}
		s.send(proto.EncodeInt64(proto.TypePong, ms))
		return nil
	case proto.TypeTouch:
		t, err := proto.UnmarshalTouch(body)
		if err != nil {
			return err
		}
		s.injectPointer(float64(t.X), float64(t.Y), t.Action, float64(t.Pressure))
		return nil
	case proto.TypeScroll:
		ev, err := proto.UnmarshalScroll(body)
		if err != nil {
			return err
		}
		s.srv.mu.RLock()
		inj := s.srv.injector
		s.srv.mu.RUnlock()
		if inj != nil {
			inj.InjectScroll(float64(ev.DX), float64(ev.DY))
		}
		return nil
	case proto.TypeKey:
		var k proto.KeyEvent
		if err := json.Unmarshal(body, &k); err != nil {
			return err
		}
		s.srv.mu.RLock()
		inj := s.srv.injector
		s.srv.mu.RUnlock()
		if inj != nil {
			mods := input.Mods{Ctrl: k.Mods.Ctrl, Alt: k.Mods.Alt, Shift: k.Mods.Shift, Meta: k.Mods.Meta}
			inj.Key(k.Key, k.Action, mods)
		}
		return nil
	case proto.TypeText:
		var t proto.TextEvent
		if err := json.Unmarshal(body, &t); err != nil {
			return err
		}
		if t.Text == "" {
			return nil
		}
		s.srv.mu.RLock()
		inj := s.srv.injector
		s.srv.mu.RUnlock()
		if inj != nil {
			inj.Text(t.Text)
		}
		return nil
	case proto.TypeClipboard:
		var c proto.ClipboardEvent
		if err := json.Unmarshal(body, &c); err != nil {
			return err
		}
		s.srv.mu.RLock()
		cl := s.srv.clip
		s.srv.mu.RUnlock()
		if cl != nil {
			if err := cl.SetFromClient(c.Data); err != nil && s.srv.cfg.Verbose {
				log.Printf("clipboard write failed: %v", err)
			}
		}
		// Mirror the text into the desktop so headless/virtual sessions show it.
		if inj := s.injectorRef(); inj != nil {
			if m, ok := inj.(interface{ ShowClipboard(string) }); ok {
				m.ShowClipboard(c.Data)
			}
		}
		// Fan out to every client (including this one) so all phones and the
		// web UI share one clipboard, regardless of host clipboard support.
		s.srv.broadcastClipboard(c.Data)
		s.sendStatus("info", "clipboard synced", "clip-in")
		return nil
	case proto.TypePointerMode:
		var p proto.PointerModeEvent
		if err := json.Unmarshal(body, &p); err != nil {
			return err
		}
		if p.Mode != proto.PointerModeTouch && p.Mode != proto.PointerModeMouse {
			return errBadMode
		}
		s.mode.Store(p.Mode)
		s.srv.mu.Lock()
		s.srv.mode = p.Mode
		inj := s.srv.injector
		s.srv.mu.Unlock()
		if inj != nil {
			inj.SetPointerMode(p.Mode)
		}
		// Echo the mode to every client: when one device switches to mouse the
		// others must follow, otherwise a phone in touch mode and a browser in
		// mouse mode fight over the same desktop.
		s.srv.broadcastPointerMode(p.Mode)
		return nil
	case proto.TypePause:
		var pe proto.PauseEvent
		if err := json.Unmarshal(body, &pe); err != nil {
			return err
		}
		// Per-session only: pausing one client must never throttle the others.
		s.realtime.Store(!pe.Paused)
		if s.srv.cfg.Verbose {
			log.Printf("ws: client #%d paused=%v", s.id, pe.Paused)
		}
		return nil
	case proto.TypeQuality:
		var q proto.QualityEvent
		if err := json.Unmarshal(body, &q); err != nil {
			return err
		}
		s.srv.applyQuality(q)
		return nil
	}
	return nil
}

func (s *session) injectorRef() input.Injector {
	s.srv.mu.RLock()
	defer s.srv.mu.RUnlock()
	return s.srv.injector
}

func (s *session) injectPointer(x, y float64, action byte, pressure float64) {
	act := "move"
	switch action {
	case proto.TouchDown:
		act = "down"
	case proto.TouchUp:
		act = "up"
	case proto.TouchCancel:
		act = "cancel"
	}
	s.srv.mu.RLock()
	inj := s.srv.injector
	s.srv.mu.RUnlock()
	if inj != nil {
		inj.InjectPointer(x, y, act, pressure)
	}
	// Echo the cursor back so clients can render it in mouse mode.
	if s.realtime.Load() {
		b, err := proto.EncodeJSON(proto.TypeCursor, proto.CursorMsg{X: float32(x), Y: float32(y), Visible: true})
		if err == nil && act != "up" {
			s.send(b)
		}
	}
}

func (s *session) handleHello(body []byte) error {
	var h proto.HelloMsg
	if err := json.Unmarshal(body, &h); err != nil {
		return err
	}
	if h.Version != 0 && h.Version != proto.VersionMajor {
		b, _ := proto.EncodeJSON(proto.TypeError, proto.StatusMsg{
			Level: "error", Code: "version",
			Message: "protocol version mismatch: server " + strconv.Itoa(proto.VersionMajor) + ", client " + strconv.Itoa(h.Version),
		})
		s.send(b)
		return errVersion
	}
	s.srv.mu.RLock()
	cap := s.srv.capturer
	inj := s.srv.injector
	cl := s.srv.clip
	q, fps, scale, mode := s.srv.quality, s.srv.fps, s.srv.scale, s.srv.mode
	auth := s.srv.cfg.Token != ""
	s.srv.mu.RUnlock()

	var bi proto.BackendInfo
	var warns []string
	w, hgt := 0, 0
	if cap != nil {
		bi.Capture = cap.Name()
		warns = append(warns, cap.Warnings()...)
		w, hgt, _ = cap.Size()
	}
	if inj != nil {
		bi.Input = inj.Name()
		warns = append(warns, inj.Warnings()...)
	}
	if cl != nil {
		bi.Clipboard = cl.Backend().Name()
		warns = append(warns, cl.Warnings()...)
	}
	bi.Warnings = warns
	bi.Display = s.srv.cfg.Display

	ok := proto.HelloOKMsg{
		Version:     proto.VersionMajor,
		Server:      "DevvioRD/" + Version,
		Hostname:    hostname(),
		OS:          runtime.GOOS + "/" + runtime.GOARCH,
		SessionID:   s.srv.sessionID,
		Width:       w,
		Height:      hgt,
		Scale:       scale,
		FPS:         fps,
		Quality:     q,
		PointerMode: mode,
		Backend:     bi,
		NeedsAuth:   auth,
	}
	b, err := proto.EncodeJSON(proto.TypeHelloOK, ok)
	if err != nil {
		return err
	}
	s.helloed.Store(true)
	s.realtime.Store(true)
	s.send(b)

	// Prime the client with the last frame so it paints immediately instead of
	// waiting up to a full pump interval (or forever, if the screen is static).
	if jpeg, w, h := s.srv.LastFrame(); len(jpeg) > 0 {
		frame := make([]byte, 0, proto.FrameHeaderSize+len(jpeg))
		frame = proto.EncodeFrame(frame, 0, uint16(w), uint16(h), time.Now().UnixMilli(), jpeg)
		s.send(frame)
	}

	// Push current clipboard so the phone can paste immediately.
	if cl != nil {
		if txt := cl.Get(); txt != "" {
			if cb, err := proto.EncodeJSON(proto.TypeClip, proto.ClipboardMsg{Kind: "text", Data: txt}); err == nil {
				s.send(cb)
			}
		}
	}
	s.sendStatus("info", "connected to "+hostname(), "hello")
	if s.srv.cfg.Verbose {
		log.Printf("ws #%d: hello client=%q caps=%+v", s.id, h.Client, h.Caps)
	}
	s.srv.ensurePump()
	return nil
}

func (s *session) sendStatus(level, msg, code string) {
	if b, err := proto.EncodeJSON(proto.TypeStatus, proto.StatusMsg{Level: level, Message: msg, Code: code}); err == nil {
		s.send(b)
	}
}

func (s *session) close() {
	s.once.Do(func() {
		close(s.done)
		_ = s.conn.Close()
	})
}

// applyQuality updates streaming parameters at runtime.
// broadcastPointerMode tells every session which pointer mode is now active.
func (s *Server) broadcastPointerMode(mode string) {
	b, err := proto.EncodeJSON(proto.TypePointerMode, proto.PointerModeEvent{Mode: mode})
	if err != nil {
		return
	}
	s.sessMu.Lock()
	defer s.sessMu.Unlock()
	for sess := range s.sessions {
		sess.send(b)
	}
}

// broadcastQuality reports the settings now in effect so all attached clients
// display the same numbers (echo == the client's own request is harmless).
func (s *Server) broadcastQuality(quality, fps int, scale float64) {
	b, err := proto.EncodeJSON(proto.TypeQuality, proto.QualityEvent{
		Quality: quality, FPS: fps, Scale: scale,
	})
	if err != nil {
		return
	}
	s.sessMu.Lock()
	defer s.sessMu.Unlock()
	for sess := range s.sessions {
		sess.send(b)
	}
}

func (s *Server) applyQuality(q proto.QualityEvent) {
	s.mu.Lock()
	if q.Quality >= 20 && q.Quality <= 95 {
		s.quality = q.Quality
	}
	if q.FPS >= 1 && q.FPS <= 60 {
		s.fps = q.FPS
	}
	if q.Scale >= 0.25 && q.Scale <= 1 {
		s.scale = q.Scale
	}
	quality, fps, scale := s.quality, s.fps, s.scale
	cap := s.capturer
	s.mu.Unlock()

	// Push the settings into the backend too. Without this the pump paced frames
	// at the new rate while the backend kept encoding at the old quality, so a
	// client's quality slider appeared to do nothing.
	if cap != nil {
		capture.Apply(cap, quality, fps, scale)
	}
	s.restartPump()
	// Report what is now in effect so every attached client shows the same
	// values instead of drifting apart.
	s.broadcastQuality(quality, fps, scale)
}

func (s *Server) clientCount() int {
	s.sessMu.Lock()
	defer s.sessMu.Unlock()
	return len(s.sessions)
}

var (
	errNoHello = &protoError{"hello required before other messages"}
	errBadMode = &protoError{"invalid pointer mode"}
	errVersion = &protoError{"protocol version mismatch"}
)

type protoError struct{ msg string }

func (e *protoError) Error() string { return e.msg }

var _ = net.Dial
