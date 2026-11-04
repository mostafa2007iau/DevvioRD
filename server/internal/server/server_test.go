package server

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// newTestServer builds a server on an ephemeral port with a temp file root and
// no capture/input side effects (the synthetic backends need no display).
func newTestServer(t *testing.T, token string) (*Server, string) {
	t.Helper()
	dir := t.TempDir()
	s, err := New(Config{
		Addr:           "127.0.0.1:0",
		Token:          token,
		CaptureBackend: "synthetic",
		InputBackend:   "synthetic",
		FilesDir:       dir,
		Quality:        70,
		FPS:            10,
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	// Start() does this in production; tests drive the mux directly.
	if err := s.initBackends(); err != nil {
		t.Fatalf("initBackends: %v", err)
	}
	t.Cleanup(s.Shutdown)
	return s, dir
}

func do(t *testing.T, s *Server, method, target string, hdr map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, target, nil)
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	rr := httptest.NewRecorder()
	s.routesForTest().ServeHTTP(rr, req)
	return rr
}

func TestHealthzNeedsNoAuth(t *testing.T) {
	s, _ := newTestServer(t, "secret-token")
	rr := do(t, s, "GET", "/healthz", nil)
	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rr.Code)
	}
	var body map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &body); err != nil {
		t.Fatalf("bad json: %v (%s)", err, rr.Body.String())
	}
	if ok, _ := body["ok"].(bool); !ok {
		t.Errorf("ok field = %v", body["ok"])
	}
}

func TestProtectedRoutesRequireToken(t *testing.T) {
	s, _ := newTestServer(t, "secret-token")
	protected := []string{
		"/api/files", "/api/download?path=a.txt", "/api/clipboard",
		"/api/upload/begin", "/api/upload/chunk", "/api/upload/finish",
		"/api/mkdir", "/api/delete", "/api/rename", "/snapshot.jpg",
	}
	for _, path := range protected {
		for _, method := range []string{"GET", "POST"} {
			rr := do(t, s, method, path, nil)
			if rr.Code != http.StatusUnauthorized {
				t.Errorf("%s %s without token = %d, want 401", method, path, rr.Code)
			}
		}
		// A wrong token must be rejected too.
		rr := do(t, s, "GET", path, map[string]string{"X-DevvioRD-Token": "wrong"})
		if rr.Code != http.StatusUnauthorized {
			t.Errorf("%s with wrong token = %d, want 401", path, rr.Code)
		}
	}
}

func TestTokenAcceptedViaHeaderQueryAndCookie(t *testing.T) {
	s, _ := newTestServer(t, "secret-token")
	cases := []struct {
		name   string
		target string
		hdr    map[string]string
	}{
		{"header", "/api/files", map[string]string{"X-DevvioRD-Token": "secret-token"}},
		{"query", "/api/files?token=secret-token", nil},
		{"cookie", "/api/files", map[string]string{"Cookie": "devviord_token=secret-token"}},
	}
	for _, c := range cases {
		rr := do(t, s, "GET", c.target, c.hdr)
		if rr.Code != http.StatusOK {
			t.Errorf("%s: status = %d (%s), want 200", c.name, rr.Code, rr.Body.String())
		}
	}
}

func TestNoTokenMeansOpenAccess(t *testing.T) {
	s, _ := newTestServer(t, "")
	if rr := do(t, s, "GET", "/api/files", nil); rr.Code != http.StatusOK {
		t.Fatalf("open server rejected request: %d", rr.Code)
	}
}

func TestPreflightIsAnswered(t *testing.T) {
	s, _ := newTestServer(t, "secret-token")
	rr := do(t, s, "OPTIONS", "/api/files", nil)
	if rr.Code != http.StatusNoContent && rr.Code != http.StatusOK {
		t.Errorf("preflight = %d, want 204", rr.Code)
	}
	if got := rr.Header().Get("Access-Control-Allow-Origin"); got != "*" {
		t.Errorf("CORS origin = %q", got)
	}
	if !strings.Contains(rr.Header().Get("Access-Control-Allow-Headers"), "X-DevvioRD-Token") {
		t.Errorf("CORS headers miss the token header: %q", rr.Header().Get("Access-Control-Allow-Headers"))
	}
}

// Traversal must be stopped at the HTTP layer, not only inside the file manager.
func TestTraversalRejectedOverHTTP(t *testing.T) {
	s, dir := newTestServer(t, "tok")
	secret := filepath.Join(filepath.Dir(dir), "outside.txt")
	if err := os.WriteFile(secret, []byte("TOP SECRET"), 0o600); err != nil {
		t.Skipf("cannot create sentinel: %v", err)
	}
	t.Cleanup(func() { os.Remove(secret) })

	for _, bad := range []string{"../outside.txt", "../../outside.txt", "/etc/passwd", "..%2foutside.txt"} {
		rr := do(t, s, "GET", "/api/download?path="+bad+"&token=tok", nil)
		if rr.Code == http.StatusOK && strings.Contains(rr.Body.String(), "TOP SECRET") {
			t.Fatalf("traversal served a file outside the root: %s", bad)
		}
	}
}

func TestPairEndpointHidesTokenByDefault(t *testing.T) {
	s, _ := newTestServer(t, "secret-token")
	rr := do(t, s, "GET", "/api/pair", nil)
	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d", rr.Code)
	}
	if strings.Contains(rr.Body.String(), "secret-token") {
		t.Fatalf("pair endpoint leaked the token: %s", rr.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &body); err != nil {
		t.Fatalf("bad json: %v (%s)", err, rr.Body.String())
	}
	pairURL, _ := body["pairUrl"].(string)
	if !strings.HasPrefix(pairURL, "devviord://connect?") {
		t.Errorf("pairUrl = %q, want a devviord:// deep link", pairURL)
	}
	if qr, ok := body["qr"].([]any); !ok || len(qr) < 21 {
		t.Errorf("qr matrix missing or too small: %T", body["qr"])
	}
}

func TestPairEndpointPublishesTokenWhenAsked(t *testing.T) {
	dir := t.TempDir()
	s, err := New(Config{Addr: "127.0.0.1:0", Token: "secret-token",
		CaptureBackend: "synthetic", InputBackend: "synthetic",
		FilesDir: dir, PairPublic: true})
	if err != nil {
		t.Fatal(err)
	}
	if err := s.initBackends(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Shutdown)
	rr := do(t, s, "GET", "/api/pair", nil)
	if !strings.Contains(rr.Body.String(), "secret-token") {
		t.Fatalf("--pair-public did not expose the token: %s", rr.Body.String())
	}
}

func TestInfoEndpoint(t *testing.T) {
	s, _ := newTestServer(t, "secret-token")
	rr := do(t, s, "GET", "/api/info", nil)
	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d", rr.Code)
	}
	var info map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &info); err != nil {
		t.Fatalf("bad json: %v", err)
	}
	for _, k := range []string{"version", "mode", "auth", "width", "height"} {
		if _, ok := info[k]; !ok {
			t.Errorf("/api/info missing %q (got %v)", k, info)
		}
	}
	if strings.Contains(rr.Body.String(), "secret-token") {
		t.Error("/api/info leaked the token")
	}
	// /api/info is what the app shows; the version must not be blank.
	if v, _ := info["version"].(string); v == "" || v == "dev" && s.cfg.Version != "" {
		t.Errorf("version = %q", info["version"])
	}
}

func TestSnapshotServedWithoutStreamingClient(t *testing.T) {
	s, _ := newTestServer(t, "")
	rr := do(t, s, "GET", "/snapshot.jpg", nil)
	if rr.Code != http.StatusOK {
		t.Fatalf("snapshot status = %d (%s)", rr.Code, rr.Body.String())
	}
	b := rr.Body.Bytes()
	if len(b) < 1000 {
		t.Fatalf("snapshot too small: %d bytes", len(b))
	}
	// JPEG magic + EOI marker.
	if b[0] != 0xFF || b[1] != 0xD8 {
		t.Errorf("not a JPEG: % x", b[:4])
	}
	if b[len(b)-2] != 0xFF || b[len(b)-1] != 0xD9 {
		t.Errorf("JPEG is truncated (no EOI): % x", b[len(b)-4:])
	}
	if ct := rr.Header().Get("Content-Type"); !strings.Contains(ct, "jpeg") {
		t.Errorf("content type = %q", ct)
	}
}

func TestWebClientIsServed(t *testing.T) {
	s, _ := newTestServer(t, "")
	rr := do(t, s, "GET", "/", nil)
	if rr.Code != http.StatusOK {
		t.Fatalf("index status = %d", rr.Code)
	}
	body := rr.Body.String()
	for _, needle := range []string{"DevvioRD", "client.js"} {
		if !strings.Contains(body, needle) {
			t.Errorf("index.html missing %q", needle)
		}
	}
	if cc := rr.Header().Get("Cache-Control"); cc != "no-store" {
		t.Errorf("index Cache-Control = %q, want no-store", cc)
	}
	rr = do(t, s, "GET", "/client.js", nil)
	if rr.Code != http.StatusOK || !strings.Contains(rr.Body.String(), "WebSocket") {
		t.Errorf("client.js not served correctly: %d", rr.Code)
	}
}

func TestClipboardRoundTripOverHTTP(t *testing.T) {
	s, _ := newTestServer(t, "")
	body := strings.NewReader(`{"text":"سلام دنیا"}`)
	req := httptest.NewRequest("POST", "/api/clipboard", body)
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	s.routesForTest().ServeHTTP(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("set clipboard = %d (%s)", rr.Code, rr.Body.String())
	}
	rr2 := do(t, s, "GET", "/api/clipboard", nil)
	if !strings.Contains(rr2.Body.String(), "سلام دنیا") {
		t.Errorf("clipboard did not round trip: %s", rr2.Body.String())
	}
}
