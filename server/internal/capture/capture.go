// Package capture provides screen capture backends for DevvioRD.
//
// A backend turns the remote desktop into a stream of JPEG frames. Backends are
// pluggable so the same server core runs on Linux/X11, a headless CI box, or
// (with an extra backend) Windows/macOS.
package capture

import (
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"
)

// Frame is one captured, encoded frame.
type Frame struct {
	JPEG      []byte
	Width     int
	Height    int
	CaptureMs time.Duration
	EncodeMs  time.Duration
}

// Options configure a backend.
type Options struct {
	Quality int     // JPEG quality 20..90
	FPS     int     // capture rate 1..60
	Scale   float64 // 0.25..1.0 downscale factor
	Display string  // e.g. ":0.0"
}

// Capturer captures the desktop.
type Capturer interface {
	// Size returns the source (virtual desktop) size in pixels.
	Size() (width, height int, err error)
	// Capture returns the next frame as JPEG.
	Capture() (Frame, error)
	// Name identifies the backend for diagnostics.
	Name() string
	// Warnings returns non-fatal configuration notes for the UI.
	Warnings() []string
	// Close releases resources.
	Close() error
}

type ctor func(Options) (Capturer, error)

var (
	mu       sync.RWMutex
	backends = map[string]ctor{}
)

// Register adds a backend constructor. Called from init() in each backend file.
func Register(name string, c ctor) {
	mu.Lock()
	defer mu.Unlock()
	backends[name] = c
}

// Names lists registered backends sorted by name.
func Names() []string {
	mu.RLock()
	defer mu.RUnlock()
	out := make([]string, 0, len(backends))
	for n := range backends {
		out = append(out, n)
	}
	sort.Strings(out)
	return out
}

// DefaultBackend returns the backend to use when none is specified.
// "auto" is resolved here by probing in priority order.
func DefaultBackend() string {
	for _, candidate := range []string{"ffmpeg", "synthetic"} {
		mu.RLock()
		_, ok := backends[candidate]
		mu.RUnlock()
		if ok && candidate == "ffmpeg" && !ffmpegAvailable() {
			continue
		}
		if ok {
			return candidate
		}
	}
	return "synthetic"
}

// New creates a backend by name ("auto" resolves automatically).
func New(name string, opts Options) (Capturer, error) {
	if opts.Quality <= 0 {
		opts.Quality = 70
	}
	if opts.FPS <= 0 {
		opts.FPS = 20
	}
	if opts.Scale <= 0 || opts.Scale > 1 {
		opts.Scale = 1
	}
	if name == "" || name == "auto" {
		name = DefaultBackend()
	}
	mu.RLock()
	c, ok := backends[name]
	mu.RUnlock()
	if !ok {
		return nil, fmt.Errorf("capture: unknown backend %q (available: %s)", name, strings.Join(Names(), ", "))
	}
	return c(opts)
}
