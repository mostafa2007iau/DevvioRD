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

// Tuner is implemented by backends whose encoding parameters can change while
// running. The server calls it when a client changes quality/fps/scale so the
// new settings take effect immediately instead of at the next restart.
type Tuner interface {
	Tune(quality int, fps int, scale float64)
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

// Tunable holds live encoding settings shared by a backend and its pump.
//
// It exists because two different things need the same numbers: the capture
// backend (to encode at the right quality) and the server (to pace frames).
// Without it, a quality change needed a full restart, or the two would drift
// apart and the actual frame rate would never match what the client asked for.
type Tunable struct {
	mu      sync.RWMutex
	quality int
	fps     int
	scale   float64
}

// NewTunable creates a holder with sane defaults.
func NewTunable(quality, fps int, scale float64) *Tunable {
	t := &Tunable{}
	t.Set(quality, fps, scale)
	return t
}

// Set updates all three values, clamping them to the supported ranges.
func (t *Tunable) Set(quality, fps int, scale float64) {
	if quality < 20 || quality > 95 {
		quality = 70
	}
	if fps < 1 || fps > 60 {
		fps = 20
	}
	if scale < 0.25 || scale > 1 {
		scale = 1
	}
	t.mu.Lock()
	t.quality, t.fps, t.scale = quality, fps, scale
	t.mu.Unlock()
}

// Quality returns the current JPEG quality.
func (t *Tunable) Quality() int {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.quality
}

// FPS returns the current target frame rate.
func (t *Tunable) FPS() int {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.fps
}

// Scale returns the current resolution scale.
func (t *Tunable) Scale() float64 {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.scale
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

// Apply pushes live settings into a backend that supports tuning. Backends that
// cannot retune (or lack the interface) are left untouched, so callers never
// need to type-assert.
func Apply(c Capturer, quality, fps int, scale float64) bool {
	if t, ok := c.(Tuner); ok {
		t.Tune(quality, fps, scale)
		return true
	}
	return false
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
