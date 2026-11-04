// Package clipboard synchronizes text between the remote desktop clipboard and
// connected clients.
package clipboard

import (
	"bytes"
	"context"
	"errors"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// Backend reads and writes the host clipboard.
type Backend interface {
	Read() (string, error)
	Write(s string) error
	Name() string
}

// Memory is an in-memory clipboard used for headless sessions and tests.
type Memory struct {
	mu   sync.RWMutex
	data string
}

func NewMemory() *Memory       { return &Memory{} }
func (m *Memory) Name() string { return "memory" }
func (m *Memory) Read() (string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.data, nil
}
func (m *Memory) Write(s string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.data = s
	return nil
}

// commandBackend shells out to xclip / xsel / wl-paste+wl-copy.
type commandBackend struct {
	readCmd  []string
	writeCmd []string
	name     string
	env      []string
}

func (c *commandBackend) Name() string { return c.name }

func (c *commandBackend) Read() (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, c.readCmd[0], c.readCmd[1:]...)
	cmd.Env = append(cmd.Environ(), c.env...)
	var out, errb bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &errb
	if err := cmd.Run(); err != nil {
		return "", err
	}
	return out.String(), nil
}

func (c *commandBackend) Write(s string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, c.writeCmd[0], c.writeCmd[1:]...)
	cmd.Env = append(cmd.Environ(), c.env...)
	cmd.Stdin = strings.NewReader(s)
	var errb bytes.Buffer
	cmd.Stderr = &errb
	return cmd.Run()
}

// Detect returns the best available clipboard backend for the environment.
func Detect(display string) (Backend, []string) {
	var env []string
	if display != "" {
		env = append(env, "DISPLAY="+display)
	}
	if _, err := exec.LookPath("wl-paste"); err == nil {
		if _, err := exec.LookPath("wl-copy"); err == nil {
			return &commandBackend{
				readCmd:  []string{"wl-paste", "--no-newline", "--type", "text"},
				writeCmd: []string{"wl-copy", "--type", "text/plain;charset=utf-8"},
				name:     "wayland:wl-clipboard",
			}, nil
		}
	}
	if _, err := exec.LookPath("xclip"); err == nil {
		return &commandBackend{
			readCmd:  []string{"xclip", "-selection", "clipboard", "-o"},
			writeCmd: []string{"xclip", "-selection", "clipboard", "-quiet", "-i"},
			name:     "x11:xclip",
			env:      env,
		}, nil
	}
	if _, err := exec.LookPath("xsel"); err == nil {
		return &commandBackend{
			readCmd:  []string{"xsel", "--clipboard", "--output"},
			writeCmd: []string{"xsel", "--clipboard", "--input"},
			name:     "x11:xsel",
			env:      env,
		}, nil
	}
	return NewMemory(), []string{"clipboard: no xclip/xsel/wl-clipboard found; using in-memory clipboard (file and text transfer still work)"}
}

var ErrNoBackend = errors.New("clipboard: no backend")

// Sync bridges a Backend with a callback that receives remote changes.
type Sync struct {
	backend  Backend
	onRemote func(text string)

	mu             sync.Mutex
	last           string
	lastFromClient string
	polling        bool
	stop           chan struct{}
	stopOnce       sync.Once
	warn           []string
}

// NewSync creates a clipboard bridge. onRemote is called when the host
// clipboard changes (so it can be pushed to clients).
func NewSync(b Backend, onRemote func(string)) *Sync {
	return &Sync{backend: b, onRemote: onRemote, stop: make(chan struct{})}
}

// Backend returns the underlying backend.
func (s *Sync) Backend() Backend { return s.backend }

// Warnings exposes clipboard notes.
func (s *Sync) Warnings() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.warn...)
}

// Start begins polling the host clipboard.
func (s *Sync) Start(interval time.Duration) {
	s.mu.Lock()
	if s.polling {
		s.mu.Unlock()
		return
	}
	s.polling = true
	if cur, err := s.backend.Read(); err == nil {
		s.last = cur
	}
	s.mu.Unlock()
	go func() {
		t := time.NewTicker(interval)
		defer t.Stop()
		for {
			select {
			case <-s.stop:
				return
			case <-t.C:
				cur, err := s.backend.Read()
				if err != nil {
					continue
				}
				s.mu.Lock()
				changed := cur != s.last
				if changed {
					s.last = cur
				}
				cb := s.onRemote
				s.mu.Unlock()
				if changed && cb != nil {
					cb(cur)
				}
			}
		}
	}()
}

// Stop halts polling.
func (s *Sync) Stop() {
	s.stopOnce.Do(func() { close(s.stop) })
}

// SetFromClient writes client clipboard content to the host clipboard.
func (s *Sync) SetFromClient(text string) error {
	s.mu.Lock()
	s.last = text
	s.mu.Unlock()
	return s.backend.Write(text)
}

// Get returns the current host clipboard.
func (s *Sync) Get() string {
	v, err := s.backend.Read()
	if err != nil {
		s.mu.Lock()
		v = s.last
		s.mu.Unlock()
	}
	return v
}
