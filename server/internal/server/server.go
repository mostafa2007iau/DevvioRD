// Package server wires capture, input, clipboard and file transfer into an
// HTTP + WebSocket service and serves the embedded web client.
package server

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"log"
	"math"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/devviord/devviord/server/internal/capture"
	"github.com/devviord/devviord/server/internal/cli"
	"github.com/devviord/devviord/server/internal/clipboard"
	"github.com/devviord/devviord/server/internal/files"
	"github.com/devviord/devviord/server/internal/input"
	"github.com/devviord/devviord/server/internal/proto"
)

//go:embed web
var webFS embed.FS

// Config configures the server.
type Config struct {
	Addr           string
	Token          string // shared secret; empty = no auth (LAN/dev mode)
	CaptureBackend string
	InputBackend   string // "auto" | "x11" | "synthetic"
	Display        string
	Quality        int
	FPS            int
	Scale          float64
	FilesDir       string
	MaxClients     int
	Verbose        bool
	// PairPublic exposes the token in the /api/pair QR payload (opt-in, for
	// fully trusted home networks only).
	PairPublic bool
	// Mode is "desktop" (capturing a real screen) or "app" (built-in virtual
	// desktop). Informational, shown in the banner and /api/info.
	Mode string
	// Version overrides the reported version string.
	Version string
}

// Server is the DevvioRD host service.
type Server struct {
	cfg Config

	mu       sync.RWMutex
	capturer capture.Capturer
	injector input.Injector
	clip     *clipboard.Sync
	browser  *files.Browser
	uploads  *files.UploadManager

	sessMu    sync.Mutex
	sessions  map[*session]struct{}
	sessionID string

	// capture pump
	pumpMu      sync.Mutex
	captureMu   sync.Mutex // serialises backend Capture() calls
	pumpStop    chan struct{}
	lastJPEG    []byte
	lastW       int
	lastH       int
	lastFrameAt time.Time

	quality int
	fps     int
	scale   float64
	mode    string

	started time.Time
	frames  uint64
	bytes   uint64

	// rolling window for /api/stats
	statMu     sync.Mutex
	statFrames uint64
	statBytes  uint64
	statAt     time.Time

	srv *http.Server
}

// New creates a server instance (backends are initialized lazily).
func New(cfg Config) (*Server, error) {
	if cfg.Addr == "" {
		cfg.Addr = ":5900"
	}
	if cfg.Quality <= 0 {
		cfg.Quality = 70
	}
	if cfg.FPS <= 0 {
		cfg.FPS = 20
	}
	if cfg.Scale <= 0 || cfg.Scale > 1 {
		cfg.Scale = 1
	}
	if cfg.MaxClients <= 0 {
		cfg.MaxClients = 4
	}
	if cfg.FilesDir == "" {
		cfg.FilesDir = defaultFilesDir()
	}
	br, err := files.NewBrowser(cfg.FilesDir)
	if err != nil {
		return nil, fmt.Errorf("server: files dir: %w", err)
	}
	s := &Server{
		cfg:       cfg,
		sessions:  map[*session]struct{}{},
		browser:   br,
		uploads:   files.NewUploadManager(cfg.FilesDir),
		quality:   cfg.Quality,
		fps:       cfg.FPS,
		scale:     cfg.Scale,
		mode:      proto.PointerModeTouch,
		started:   time.Now(),
		sessionID: randHex(8),
	}
	return s, nil
}

func defaultFilesDir() string {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		home = "."
	}
	// Keep transfers in their own folder so the app's file browser opens on
	// something predictable instead of the home directory.
	return filepath.Join(home, "DevvioRD", "Downloads")
}

func randHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("%x", time.Now().UnixNano())
	}
	return hex.EncodeToString(b)
}

// initBackends selects capture/input/clipboard implementations.
func (s *Server) initBackends() error {
	opts := capture.Options{Quality: s.quality, FPS: s.fps, Scale: s.scale, Display: s.cfg.Display}
	cap, err := capture.New(s.cfg.CaptureBackend, opts)
	if err != nil {
		log.Printf("capture backend %q unavailable (%v); falling back to synthetic", s.cfg.CaptureBackend, err)
		cap, err = capture.New("synthetic", opts)
		if err != nil {
			return err
		}
	}

	var inj input.Injector
	synth, isSynth := cap.(input.Injector)
	switch {
	case s.cfg.InputBackend == "synthetic" || (s.cfg.InputBackend == "" && isSynth && cap.Name() == "synthetic"):
		if isSynth {
			inj = synth
		} else {
			inj = &noopInjector{}
		}
	case s.cfg.InputBackend == "auto" || s.cfg.InputBackend == "":
		if x, err := input.NewX11(s.cfg.Display); err == nil {
			inj = x
		} else if isSynth {
			inj = synth
		} else {
			// Last resort: a no-op injector keeps the session usable for viewing.
			inj = &noopInjector{warn: []string{err.Error()}}
		}
	case s.cfg.InputBackend == "x11":
		x, err := input.NewX11(s.cfg.Display)
		if err != nil {
			return err
		}
		inj = x
	default:
		if isSynth {
			inj = synth
		} else {
			inj = &noopInjector{}
		}
	}

	cb, warns := clipboard.Detect(s.cfg.Display)
	clipSync := clipboard.NewSync(cb, func(text string) {
		s.broadcastClipboard(text)
	})
	clipSync.Start(700 * time.Millisecond)

	s.mu.Lock()
	s.capturer = cap
	s.injector = inj
	s.clip = clipSync
	s.mu.Unlock()

	for _, w := range warns {
		log.Printf("warning: %s", w)
	}
	for _, w := range cap.Warnings() {
		log.Printf("warning: %s", w)
	}
	return nil
}

type noopInjector struct{ warn []string }

func (n *noopInjector) InjectPointer(float64, float64, string, float64) {}
func (n *noopInjector) InjectScroll(float64, float64)                   {}
func (n *noopInjector) Key(string, string, input.Mods)                  {}
func (n *noopInjector) Text(string)                                     {}
func (n *noopInjector) SetPointerMode(string)                           {}
func (n *noopInjector) Name() string                                    { return "noop" }
func (n *noopInjector) Close() error                                    { return nil }
func (n *noopInjector) Warnings() []string                              { return n.warn }

// Start initializes backends and begins serving. Blocks until the listener
// stops.
func (s *Server) Start() error {
	if err := s.initBackends(); err != nil {
		return err
	}
	mux := http.NewServeMux()
	s.routes(mux)
	s.srv = &http.Server{
		Addr:              s.cfg.Addr,
		Handler:           s.logMiddleware(mux),
		ReadHeaderTimeout: 10 * time.Second,
	}
	ln, err := net.Listen("tcp", s.cfg.Addr)
	if err != nil {
		return err
	}
	port := ln.Addr().(*net.TCPAddr).Port
	s.printBanner(port)
	return s.srv.Serve(ln)
}

// printBanner shows the pairing information on startup.
func (s *Server) printBanner(port int) {
	info := cli.Info{
		Version:    s.version(),
		ServerMode: s.cfg.Mode,
		Hostname:   hostname(),
		OS:         runtime.GOOS + "/" + runtime.GOARCH,
		Port:       port,
		Token:      s.cfg.Token,
		AuthOn:     s.cfg.Token != "",
		Quality:    s.quality,
		FPS:        s.fps,
		FilesDir:   s.cfg.FilesDir,
		SessionID:  s.sessionID,
	}
	for _, ip := range cli.LanAddrs() {
		info.Addrs = append(info.Addrs, "http://"+ip.String()+":"+strconv.Itoa(port))
	}
	s.mu.RLock()
	if s.capturer != nil {
		info.Capture = s.capturer.Name()
	}
	if s.injector != nil {
		info.Input = s.injector.Name()
		info.Warnings = append(info.Warnings, s.injector.Warnings()...)
	}
	if s.clip != nil {
		info.Clipboard = s.clip.Backend().Name()
		info.Warnings = append(info.Warnings, s.clip.Warnings()...)
	}
	s.mu.RUnlock()
	if len(info.Addrs) == 0 {
		info.Warnings = append(info.Warnings, "no LAN address found - connect via localhost or publish the port")
	}
	cli.Print(info)
}

func displayHost() string {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return "localhost"
	}
	for _, a := range addrs {
		if ipn, ok := a.(*net.IPNet); ok && !ipn.IP.IsLoopback() && ipn.IP.To4() != nil {
			return ipn.IP.String()
		}
	}
	return "localhost"
}

// URLs lists the addresses a client can connect to.
func (s *Server) URLs() []string {
	var out []string
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return out
	}
	port := s.cfg.Addr
	if i := strings.LastIndex(port, ":"); i >= 0 {
		port = port[i+1:]
	}
	for _, a := range addrs {
		if ipn, ok := a.(*net.IPNet); ok && !ipn.IP.IsLoopback() && ipn.IP.To4() != nil {
			out = append(out, "http://"+ipn.IP.String()+":"+port)
		}
	}
	out = append(out, "http://localhost:"+port)
	return out
}

// sessionIDValue exposes the id for the CLI.
func (s *Server) SessionID() string { return s.sessionID }

// Shutdown stops the server and all backends.
func (s *Server) Shutdown() {
	s.stopPump()
	s.mu.Lock()
	if s.clip != nil {
		s.clip.Stop()
	}
	if s.capturer != nil {
		_ = s.capturer.Close()
	}
	if s.injector != nil {
		_ = s.injector.Close()
	}
	s.mu.Unlock()
	s.sessMu.Lock()
	for sess := range s.sessions {
		sess.close()
	}
	s.sessMu.Unlock()
	if s.srv != nil {
		_ = s.srv.Close()
	}
}

func (s *Server) logMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.cfg.Verbose {
			log.Printf("%s %s %s", r.RemoteAddr, r.Method, r.URL.Path)
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) checkAuth(r *http.Request) bool {
	if s.cfg.Token == "" {
		return true
	}
	tok := r.Header.Get("X-DevvioRD-Token")
	if tok == "" {
		tok = r.URL.Query().Get("token")
	}
	if tok == "" {
		if c, err := r.Cookie("devviord_token"); err == nil {
			tok = c.Value
		}
	}
	// Constant-time comparison on equal-length digests.
	got := sha256.Sum256([]byte(tok))
	want := sha256.Sum256([]byte(s.cfg.Token))
	return subtle.ConstantTimeCompare(got[:], want[:]) == 1
}

func (s *Server) routes(mux *http.ServeMux) {
	sub, err := fs.Sub(webFS, "web")
	if err != nil {
		panic(err)
	}
	webHandler := http.FileServer(http.FS(sub))

	mux.HandleFunc("/live", s.handleLive)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"ok":true,"uptime":%q}`, time.Since(s.started).String())
	})
	mux.HandleFunc("/api/info", s.handleInfo)
	mux.HandleFunc("/api/pair", s.handlePair)
	mux.HandleFunc("/api/stats", s.auth(s.handleStats))
	mux.HandleFunc("/snapshot.jpg", s.auth(s.handleSnapshot))
	mux.HandleFunc("/ws", s.handleWS)
	mux.HandleFunc("/api/files", s.auth(s.handleList))
	mux.HandleFunc("/api/upload/begin", s.auth(s.handleUploadBegin))
	mux.HandleFunc("/api/upload/chunk", s.auth(s.handleUploadChunk))
	mux.HandleFunc("/api/upload/finish", s.auth(s.handleUploadFinish))
	mux.HandleFunc("/api/download", s.auth(s.handleDownload))
	mux.HandleFunc("/api/clipboard", s.auth(s.handleClipboard))
	mux.HandleFunc("/api/mkdir", s.auth(s.handleMkdir))
	mux.HandleFunc("/api/delete", s.auth(s.handleDelete))
	mux.HandleFunc("/api/rename", s.auth(s.handleRename))

	mux.Handle("/", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/" {
			w.Header().Set("Cache-Control", "no-store")
		}
		webHandler.ServeHTTP(w, r)
	}))
}

func (s *Server) auth(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// Allow CORS preflight for the web UI served from another origin.
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Headers", "X-DevvioRD-Token,Content-Type")
		w.Header().Set("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		if !s.checkAuth(r) {
			files.WriteJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
			return
		}
		h(w, r)
	}
}

// handlePair returns the information the mobile app needs to connect: host
// addresses, port and (optionally) the token. Served without auth so the QR
// pairing flow works, but the token is only included when the server was
// started with --pair-public or when auth is disabled entirely.
func (s *Server) handlePair(w http.ResponseWriter, r *http.Request) {
	addrs := cli.LanAddrs()
	port := s.cfg.Addr
	if i := strings.LastIndex(port, ":"); i >= 0 {
		port = port[i+1:]
	}
	urls := make([]string, 0, len(addrs))
	for _, ip := range addrs {
		urls = append(urls, "http://"+ip.String()+":"+port)
	}
	pairURL := "devviord://connect?host="
	if len(urls) > 0 {
		pairURL += strings.TrimPrefix(urls[0], "http://")
	} else {
		pairURL += "localhost:" + port
	}
	if s.cfg.PairPublic && s.cfg.Token != "" {
		pairURL += "&token=" + s.cfg.Token
	}
	files.WriteJSON(w, http.StatusOK, map[string]any{
		"name":      "DevvioRD",
		"version":   Version,
		"hostname":  hostname(),
		"port":      port,
		"urls":      urls,
		"pairUrl":   pairURL,
		"qr":        cli.QRMatrix(pairURL),
		"auth":      s.cfg.Token != "",
		"sessionId": s.sessionID,
	})
}

// handleSnapshot returns the most recent frame as a JPEG. It is used by the
// app's "connection test" screen and by monitoring tools.
func (s *Server) handleSnapshot(w http.ResponseWriter, r *http.Request) {
	// On-demand capture: works even when no streaming client is connected, so
	// the endpoint is usable for monitoring and for the app's connection test.
	jpeg, _, _, err := s.CaptureOnce(1500 * time.Millisecond)
	if err != nil || len(jpeg) == 0 {
		s.mu.RLock()
		caps := s.capturer
		s.mu.RUnlock()
		if caps == nil {
			http.Error(w, "no capture backend", http.StatusServiceUnavailable)
			return
		}
		// The pump may already be capturing; briefly retry to pick that frame up.
		deadline := time.Now().Add(2 * time.Second)
		for time.Now().Before(deadline) && len(jpeg) == 0 {
			time.Sleep(50 * time.Millisecond)
			jpeg, _, _, _ = s.CaptureOnce(0)
		}
	}
	if len(jpeg) == 0 {
		http.Error(w, "no frame available", http.StatusServiceUnavailable)
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "no-store, no-cache, must-revalidate")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	_, _ = w.Write(jpeg)
}

// handleLive upgrades to the websocket used by the web UI.
func (s *Server) handleLive(w http.ResponseWriter, r *http.Request) { s.handleWS(w, r) }

func (s *Server) handleInfo(w http.ResponseWriter, r *http.Request) {
	s.mu.RLock()
	capName, injName, clipName := "none", "none", "none"
	var warns []string
	if s.capturer != nil {
		capName = s.capturer.Name()
		warns = append(warns, s.capturer.Warnings()...)
	}
	if s.injector != nil {
		injName = s.injector.Name()
		warns = append(warns, s.injector.Warnings()...)
	}
	if s.clip != nil {
		clipName = s.clip.Backend().Name()
		warns = append(warns, s.clip.Warnings()...)
	}
	cs := s.capturer
	s.mu.RUnlock()
	wid, hei, _ := 0, 0, error(nil)
	if cs != nil {
		wid, hei, _ = cs.Size()
	}
	out := map[string]any{
		"name":        "DevvioRD",
		"version":     s.version(),
		"mode":        s.cfg.Mode,
		"sessionId":   s.sessionID,
		"hostname":    hostname(),
		"os":          runtime.GOOS + "/" + runtime.GOARCH,
		"width":       wid,
		"height":      hei,
		"quality":     s.quality,
		"fps":         s.fps,
		"scale":       s.scale,
		"pointerMode": s.mode,
		"auth":        s.cfg.Token != "",
		"capture":     capName,
		"input":       injName,
		"clipboard":   clipName,
		"warnings":    warns,
		"filesDir":    s.cfg.FilesDir,
		"uptime":      time.Since(s.started).String(),
		"frames":      s.frames,
		"backendList": capture.Names(),
	}
	files.WriteJSON(w, http.StatusOK, out)
}

// handleStats reports live telemetry for the UI: real streaming rate, client
// count, throughput and frame age. /api/info is static configuration; this is
// the dynamic half, which is what a stats panel needs.
func (s *Server) handleStats(w http.ResponseWriter, r *http.Request) {
	now := time.Now()
	frames := atomic.LoadUint64(&s.frames)
	totalBytes := atomic.LoadUint64(&s.bytes)

	s.pumpMu.Lock()
	lastAt := s.lastFrameAt
	lastSize := len(s.lastJPEG)
	s.pumpMu.Unlock()

	// Frames per second over the window since the previous observation.
	s.statMu.Lock()
	prevFrames, prevBytes, prevAt := s.statFrames, s.statBytes, s.statAt
	var fps, kbps float64
	if !prevAt.IsZero() {
		dt := now.Sub(prevAt).Seconds()
		if dt > 0.05 {
			fps = float64(frames-prevFrames) / dt
			kbps = float64(totalBytes-prevBytes) / dt / 1024
		}
	}
	s.statFrames, s.statBytes, s.statAt = frames, totalBytes, now
	s.statMu.Unlock()

	uptime := now.Sub(s.started)
	ageMs := int64(-1)
	if !lastAt.IsZero() {
		ageMs = now.Sub(lastAt).Milliseconds()
	}
	s.mu.RLock()
	quality, fpsTarget, scale := s.quality, s.fps, s.scale
	mode := s.mode
	s.mu.RUnlock()

	files.WriteJSON(w, http.StatusOK, map[string]any{
		"streaming":     s.clientCount() > 0,
		"clients":       s.clientCount(),
		"fps":           math.Round(fps*10) / 10,
		"fpsTarget":     fpsTarget,
		"quality":       quality,
		"scale":         scale,
		"pointerMode":   mode,
		"frames":        frames,
		"bytes":         totalBytes,
		"kbps":          math.Round(kbps*10) / 10,
		"lastFrameAge":  ageMs,
		"lastFrameSize": lastSize,
		"uptimeSec":     int64(uptime.Seconds()),
		"avgFrameKiB":   avgFrameKiB(totalBytes, frames),
	})
}

func avgFrameKiB(total uint64, frames uint64) float64 {
	if frames == 0 {
		return 0
	}
	return math.Round(float64(total)/float64(frames)/1024*10) / 10
}

func hostname() string {
	h, err := os.Hostname()
	if err != nil {
		return "unknown"
	}
	return h
}

// ---------- file REST handlers ----------

func (s *Server) handleList(w http.ResponseWriter, r *http.Request) {
	dir := r.URL.Query().Get("dir")
	entries, err := s.browser.List(dir)
	if err != nil {
		files.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	files.WriteJSON(w, http.StatusOK, map[string]any{
		"root":    s.browser.Root,
		"dir":     dir,
		"entries": entries,
	})
}

func (s *Server) handleUploadBegin(w http.ResponseWriter, r *http.Request) {
	var req files.BeginRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&req); err != nil {
		files.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	resp, err := s.uploads.Begin(req)
	if err != nil {
		files.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	files.WriteJSON(w, http.StatusOK, resp)
}

func (s *Server) handleUploadChunk(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	offset, _ := strconv.ParseInt(r.URL.Query().Get("offset"), 10, 64)
	n := r.ContentLength
	if n <= 0 || n > files.ChunkSize*4 {
		// Consume the body (bounded) before replying; otherwise the client sees
		// a connection reset instead of our status code.
		_, _ = io.CopyN(io.Discard, r.Body, 8<<20)
		files.WriteJSON(w, http.StatusRequestEntityTooLarge,
			map[string]string{"error": "invalid chunk size"})
		return
	}
	if err := s.uploads.WriteChunk(id, offset, r.Body, n); err != nil {
		files.WriteJSON(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	if u, ok := s.uploads.Progress(id); ok {
		files.WriteJSON(w, http.StatusOK, map[string]any{"received": u.Received})
		return
	}
	files.WriteJSON(w, http.StatusOK, map[string]any{"received": offset + n})
}

func (s *Server) handleUploadFinish(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ID string `json:"id"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&req); err != nil {
		files.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	entry, err := s.uploads.Finish(req.ID)
	if err != nil {
		files.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	s.notifyClients(proto.StatusMsg{Level: "info", Message: "file received: " + entry.Name, Code: "file-in"})
	files.WriteJSON(w, http.StatusOK, entry)
}

func (s *Server) handleDownload(w http.ResponseWriter, r *http.Request) {
	rel := r.URL.Query().Get("path")
	full, err := s.browser.Abs(rel)
	if err != nil {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	info, err := os.Stat(full)
	if err != nil || info.IsDir() {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	f, err := os.Open(full)
	if err != nil {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", files.ContentType(info.Name()))
	w.Header().Set("Content-Disposition", "attachment; filename="+strconv.Quote(info.Name()))
	w.Header().Set("Access-Control-Allow-Origin", "*")
	http.ServeContent(w, r, info.Name(), info.ModTime(), f)
}

func (s *Server) handleClipboard(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost {
		var body struct {
			Text string `json:"text"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 8<<20)).Decode(&body); err != nil {
			files.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		s.mu.RLock()
		cl := s.clip
		s.mu.RUnlock()
		if cl != nil {
			_ = cl.SetFromClient(body.Text)
		}
		files.WriteJSON(w, http.StatusOK, map[string]any{"ok": true, "length": len(body.Text)})
		return
	}
	s.mu.RLock()
	cl := s.clip
	s.mu.RUnlock()
	text := ""
	if cl != nil {
		text = cl.Get()
	}
	files.WriteJSON(w, http.StatusOK, map[string]any{"text": text})
}

func (s *Server) handleMkdir(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Dir  string `json:"dir"`
		Name string `json:"name"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&req); err != nil {
		files.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	full, err := s.browser.Abs(filepath.Join(req.Dir, req.Name))
	if err != nil {
		files.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	if err := os.MkdirAll(full, 0o755); err != nil {
		files.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	files.WriteJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) handleDelete(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Path string `json:"path"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&req); err != nil {
		files.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	full, err := s.browser.Abs(req.Path)
	if err != nil || full == s.browser.Root {
		files.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid path"})
		return
	}
	if err := os.RemoveAll(full); err != nil {
		files.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	files.WriteJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) handleRename(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Path    string `json:"path"`
		NewName string `json:"newName"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&req); err != nil {
		files.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	full, err := s.browser.Abs(req.Path)
	if err != nil {
		files.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	dst, err := s.browser.Abs(filepath.Join(filepath.Dir(req.Path), req.NewName))
	if err != nil {
		files.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	if err := os.Rename(full, dst); err != nil {
		files.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	files.WriteJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// ---------- clipboard broadcast ----------

func (s *Server) broadcastClipboard(text string) {
	b, err := proto.EncodeJSON(proto.TypeClip, proto.ClipboardMsg{Kind: "text", Data: text})
	if err != nil {
		return
	}
	s.sessMu.Lock()
	defer s.sessMu.Unlock()
	for sess := range s.sessions {
		sess.send(b)
	}
}

func (s *Server) notifyClients(st proto.StatusMsg) {
	b, err := proto.EncodeJSON(proto.TypeStatus, st)
	if err != nil {
		return
	}
	s.sessMu.Lock()
	defer s.sessMu.Unlock()
	for sess := range s.sessions {
		sess.send(b)
	}
}

// Version of the server (the CLI may override the reported value).
const Version = "1.0.0"

// version is what this instance reports.
func (s *Server) version() string {
	if s.cfg.Version != "" {
		return s.cfg.Version
	}
	return Version
}

// base64 encoding helper used by the ws file channel.
func b64(b []byte) string { return base64.StdEncoding.EncodeToString(b) }

var _ = exec.Command

// GenerateToken creates a URL-safe random token for --gen-token / --token.
func GenerateToken() string {
	const alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	b := make([]byte, 20)
	if _, err := rand.Read(b); err != nil {
		return randHex(10)
	}
	out := make([]byte, len(b))
	for i, v := range b {
		out[i] = alphabet[int(v)%len(alphabet)]
	}
	return string(out)
}
