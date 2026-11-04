// Package files implements the file transfer channel between clients and the
// remote host: upload (phone -> server) and download (server -> phone).
//
// Large transfers use resumable chunked HTTP uploads; small downloads are
// streamed over WebSocket as base64 chunks by the ws layer.
package files

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	// ChunkSize is the preferred upload chunk size (1 MiB).
	ChunkSize = 1 << 20
	// MaxUploadSize caps a single file (8 GiB).
	MaxUploadSize = 8 << 30
)

// ErrNotFound is returned when a requested path is outside the sandbox or
// does not exist.
var ErrNotFound = errors.New("files: not found")

// SafeJoin joins base and rel, rejecting path traversal.
// An empty rel denotes the sandbox root itself (used for directory listing);
// traversal attempts such as "../.." are still refused.
func SafeJoin(base, rel string) (string, error) {
	rel = strings.ReplaceAll(rel, "\\", "/")
	if rel == "" || rel == "/" || rel == "." {
		return filepath.Abs(base)
	}
	for _, seg := range strings.Split(rel, "/") {
		if seg == ".." {
			return "", ErrNotFound
		}
	}
	cleaned := filepath.Clean("/" + rel)
	cleaned = strings.TrimPrefix(cleaned, "/")
	if cleaned == "" || cleaned == "." {
		return base, nil
	}
	full := filepath.Join(base, cleaned)
	absBase, err := filepath.Abs(base)
	if err != nil {
		return "", err
	}
	absFull, err := filepath.Abs(full)
	if err != nil {
		return "", err
	}
	if absFull != absBase && !strings.HasPrefix(absFull, absBase+string(os.PathSeparator)) {
		return "", ErrNotFound
	}
	return absFull, nil
}

// Entry describes a file or directory.
type Entry struct {
	Name    string    `json:"name"`
	Path    string    `json:"path"`
	Dir     bool      `json:"dir"`
	Size    int64     `json:"size"`
	ModTime time.Time `json:"mtime"`
}

// Browser lists and serves files under a root directory.
type Browser struct {
	Root string
}

// NewBrowser creates a browser rooted at dir (created if missing).
func NewBrowser(dir string) (*Browser, error) {
	abs, err := filepath.Abs(dir)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(abs, 0o755); err != nil {
		return nil, err
	}
	return &Browser{Root: abs}, nil
}

// List returns the entries of a directory.
func (b *Browser) List(rel string) ([]Entry, error) {
	full, err := SafeJoin(b.Root, rel)
	if err != nil {
		return nil, err
	}
	des, err := os.ReadDir(full)
	if err != nil {
		return nil, err
	}
	out := make([]Entry, 0, len(des))
	for _, de := range des {
		info, err := de.Info()
		if err != nil {
			continue
		}
		relPath, _ := filepath.Rel(b.Root, filepath.Join(full, de.Name()))
		out = append(out, Entry{
			Name:    de.Name(),
			Path:    filepath.ToSlash(relPath),
			Dir:     de.IsDir(),
			Size:    info.Size(),
			ModTime: info.ModTime(),
		})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Dir != out[j].Dir {
			return out[i].Dir
		}
		return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name)
	})
	return out, nil
}

// Stat returns a single entry.
func (b *Browser) Stat(rel string) (Entry, error) {
	full, err := SafeJoin(b.Root, rel)
	if err != nil {
		return Entry{}, err
	}
	info, err := os.Stat(full)
	if err != nil {
		return Entry{}, ErrNotFound
	}
	rp, _ := filepath.Rel(b.Root, full)
	return Entry{Name: info.Name(), Path: filepath.ToSlash(rp), Dir: info.IsDir(), Size: info.Size(), ModTime: info.ModTime()}, nil
}

// Abs resolves a path inside the sandbox.
func (b *Browser) Abs(rel string) (string, error) { return SafeJoin(b.Root, rel) }

// -------- resumable uploads --------

// Upload tracks an in-progress chunked upload.
type Upload struct {
	ID       string    `json:"id"`
	Name     string    `json:"name"`
	Dir      string    `json:"dir"`
	Size     int64     `json:"size"`
	Received int64     `json:"received"`
	Done     bool      `json:"done"`
	SHA256   string    `json:"sha256,omitempty"`
	Started  time.Time `json:"started"`
	tmpPath  string
	final    string
	mu       sync.Mutex
	hasher   interface{ Write([]byte) (int, error) }
}

// UploadManager coordinates resumable uploads.
type UploadManager struct {
	mu      sync.Mutex
	root    string
	uploads map[string]*Upload
}

// NewUploadManager creates a manager rooted at dir.
func NewUploadManager(root string) *UploadManager {
	return &UploadManager{root: root, uploads: map[string]*Upload{}}
}

// Begin starts (or resumes) an upload.
type BeginRequest struct {
	Name     string `json:"name"`
	Dir      string `json:"dir"`
	Size     int64  `json:"size"`
	Offset   int64  `json:"offset"`
	UploadID string `json:"uploadId,omitempty"`
}

// BeginResponse reports where to continue.
type BeginResponse struct {
	ID      string `json:"id"`
	Offset  int64  `json:"offset"`
	Resumed bool   `json:"resumed"`
}

func sanitizeName(name string) string {
	name = filepath.Base(strings.ReplaceAll(name, "\\", "/"))
	// Drop path separators and every control rune (NUL, ESC, CR/LF, DEL):
	// control bytes in a name confuse terminals, logs and some file managers.
	name = strings.Map(func(r rune) rune {
		if r == '/' || r == '\\' || r < 0x20 || r == 0x7f {
			return -1
		}
		return r
	}, name)
	if name == "" || name == "." || name == ".." {
		name = fmt.Sprintf("upload-%d.bin", time.Now().UnixNano())
	}
	return name
}

// Begin registers an upload and returns the resume offset.
func (m *UploadManager) Begin(req BeginRequest) (BeginResponse, error) {
	if req.Size < 0 || req.Size > MaxUploadSize {
		return BeginResponse{}, fmt.Errorf("files: invalid size %d", req.Size)
	}
	m.mu.Lock()
	defer m.mu.Unlock()

	if req.UploadID != "" {
		if u, ok := m.uploads[req.UploadID]; ok {
			info, err := os.Stat(u.tmpPath)
			if err == nil {
				u.mu.Lock()
				u.Received = info.Size()
				off := u.Received
				u.mu.Unlock()
				return BeginResponse{ID: u.ID, Offset: off, Resumed: true}, nil
			}
			delete(m.uploads, req.UploadID)
		}
	}

	dir, err := SafeJoin(m.root, req.Dir)
	if err != nil {
		return BeginResponse{}, err
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return BeginResponse{}, err
	}
	name := sanitizeName(req.Name)
	final := uniquePath(filepath.Join(dir, name))
	id := randomID()
	u := &Upload{
		ID:      id,
		Name:    filepath.Base(final),
		Dir:     req.Dir,
		Size:    req.Size,
		Started: time.Now(),
		tmpPath: final + ".part-" + id,
		final:   final,
	}
	f, err := os.OpenFile(u.tmpPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return BeginResponse{}, err
	}
	defer f.Close()
	m.uploads[id] = u
	return BeginResponse{ID: id, Offset: 0}, nil
}

func uniquePath(p string) string {
	if _, err := os.Stat(p); os.IsNotExist(err) {
		return p
	}
	ext := filepath.Ext(p)
	base := strings.TrimSuffix(p, ext)
	for i := 1; i < 1000; i++ {
		cand := fmt.Sprintf("%s (%d)%s", base, i, ext)
		if _, err := os.Stat(cand); os.IsNotExist(err) {
			return cand
		}
	}
	return fmt.Sprintf("%s-%d%s", base, time.Now().UnixNano(), ext)
}

// WriteChunk appends data to an upload.
func (m *UploadManager) WriteChunk(id string, offset int64, r io.Reader, n int64) error {
	m.mu.Lock()
	u, ok := m.uploads[id]
	m.mu.Unlock()
	if !ok {
		return ErrNotFound
	}
	u.mu.Lock()
	defer u.mu.Unlock()
	info, err := os.Stat(u.tmpPath)
	if err != nil {
		return err
	}
	if offset != info.Size() {
		return fmt.Errorf("files: offset mismatch (want %d, got %d)", info.Size(), offset)
	}
	f, err := os.OpenFile(u.tmpPath, os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return err
	}
	defer f.Close()
	written, err := io.Copy(f, io.LimitReader(r, n))
	if err != nil {
		return err
	}
	if written != n {
		return fmt.Errorf("files: short write (%d/%d)", written, n)
	}
	u.Received = info.Size() + written
	return nil
}

// Finish completes an upload, verifying size and returning the final entry.
func (m *UploadManager) Finish(id string) (Entry, error) {
	m.mu.Lock()
	u, ok := m.uploads[id]
	m.mu.Unlock()
	if !ok {
		return Entry{}, ErrNotFound
	}
	u.mu.Lock()
	defer u.mu.Unlock()
	info, err := os.Stat(u.tmpPath)
	if err != nil {
		return Entry{}, err
	}
	if u.Size > 0 && info.Size() != u.Size {
		return Entry{}, fmt.Errorf("files: incomplete upload (%d/%d bytes)", info.Size(), u.Size)
	}
	// Compute checksum for integrity reporting.
	h := sha256.New()
	f, err := os.Open(u.tmpPath)
	if err == nil {
		_, _ = io.Copy(h, f)
		f.Close()
		u.SHA256 = hex.EncodeToString(h.Sum(nil))
	}
	if err := os.Rename(u.tmpPath, u.final); err != nil {
		return Entry{}, err
	}
	u.Done = true
	finfo, err := os.Stat(u.final)
	if err != nil {
		return Entry{}, err
	}
	rel, _ := filepath.Rel(m.root, u.final)
	m.mu.Lock()
	delete(m.uploads, id)
	m.mu.Unlock()
	return Entry{Name: finfo.Name(), Path: filepath.ToSlash(rel), Size: finfo.Size(), ModTime: finfo.ModTime()}, nil
}

// Progress returns upload state (used by tests/UI).
func (m *UploadManager) Progress(id string) (Upload, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	u, ok := m.uploads[id]
	if !ok {
		return Upload{}, false
	}
	u.mu.Lock()
	defer u.mu.Unlock()
	return Upload{
		ID:       u.ID,
		Name:     u.Name,
		Dir:      u.Dir,
		Size:     u.Size,
		Received: u.Received,
		Done:     u.Done,
		SHA256:   u.SHA256,
		Started:  u.Started,
	}, true
}

func randomID() string {
	const letters = "abcdefghijklmnopqrstuvwxyz0123456789"
	b := make([]byte, 16)
	seed := time.Now().UnixNano()
	for i := range b {
		seed = seed*6364136223846793005 + 1442695040888963407
		b[i] = letters[int(seed>>59)&31%len(letters)]
	}
	return string(b)
}

// ContentType guesses a MIME type from a filename.
func ContentType(name string) string {
	if t := mime.TypeByExtension(filepath.Ext(name)); t != "" {
		return t
	}
	return "application/octet-stream"
}

// ReadManifest writes a small JSON manifest (used by the folder download).
func WriteJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
