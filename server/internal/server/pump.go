package server

import (
	"errors"
	"log"
	"sync/atomic"
	"time"

	"github.com/devviord/devviord/server/internal/proto"
)

var errNoCapturer = errors.New("server: no capture backend")

// The capture pump grabs frames at the configured rate and fans them out to
// every connected session. It runs only while at least one client is attached,
// so an idle server costs nothing.

func (s *Server) ensurePump() {
	s.pumpMu.Lock()
	defer s.pumpMu.Unlock()
	if s.pumpStop != nil {
		return
	}
	stop := make(chan struct{})
	s.pumpStop = stop
	go s.pumpLoop(stop)
}

func (s *Server) restartPump() {
	s.stopPump()
	if s.clientCount() > 0 {
		s.ensurePump()
	}
}

func (s *Server) stopPump() {
	s.pumpMu.Lock()
	stop := s.pumpStop
	s.pumpStop = nil
	s.pumpMu.Unlock()
	if stop != nil {
		close(stop)
	}
}

func (s *Server) pumpLoop(stop chan struct{}) {
	var seq uint32
	var frameBuf []byte
	lastFrameAt := time.Now()
	defer func() {
		// Let the pump be restarted later.
		s.pumpMu.Lock()
		if s.pumpStop != nil {
			select {
			case <-s.pumpStop:
				s.pumpStop = nil
			default:
			}
		}
		s.pumpMu.Unlock()
	}()

	for {
		select {
		case <-stop:
			return
		default:
		}

		if s.clientCount() == 0 {
			// Idle: sleep cheaply and stop capturing.
			select {
			case <-stop:
				return
			case <-time.After(250 * time.Millisecond):
			}
			continue
		}

		s.mu.RLock()
		cap := s.capturer
		fps := s.fps
		s.mu.RUnlock()
		if cap == nil {
			select {
			case <-stop:
				return
			case <-time.After(200 * time.Millisecond):
			}
			continue
		}
		if fps <= 0 {
			fps = 15
		}
		interval := time.Second / time.Duration(fps)
		wait := interval - time.Since(lastFrameAt)
		if wait > 0 {
			select {
			case <-stop:
				return
			case <-time.After(wait):
			}
		}
		lastFrameAt = time.Now()

		// Serialise capture: /snapshot.jpg can request a frame on demand and a
		// backend (e.g. the ffmpeg pipe) must never be read concurrently.
		s.captureMu.Lock()
		f, err := cap.Capture()
		s.captureMu.Unlock()
		if err != nil {
			// Backend hiccup: brief pause, then retry.
			s.mu.RLock()
			verbose := s.cfg.Verbose
			s.mu.RUnlock()
			if verbose {
				log.Printf("capture error: %v", err)
			}
			select {
			case <-stop:
				return
			case <-time.After(150 * time.Millisecond):
			}
			continue
		}
		if len(f.JPEG) == 0 {
			continue
		}
		seq++
		frameBuf = proto.EncodeFrame(frameBuf, seq, uint16(f.Width), uint16(f.Height),
			time.Now().UnixMilli(), f.JPEG)

		// Store a copy for newly-joined clients so they paint immediately.
		s.pumpMu.Lock()
		s.lastJPEG = f.JPEG
		s.lastW, s.lastH = f.Width, f.Height
		s.lastFrameAt = time.Now()
		s.pumpMu.Unlock()
		atomic.AddUint64(&s.frames, 1)
		atomic.AddUint64(&s.bytes, uint64(len(f.JPEG)))

		s.sessMu.Lock()
		sessions := make([]*session, 0, len(s.sessions))
		for sess := range s.sessions {
			sessions = append(sessions, sess)
		}
		s.sessMu.Unlock()
		for _, sess := range sessions {
			if sess.realtime.Load() {
				// Copy per session: sendQ is consumed asynchronously.
				buf := make([]byte, len(frameBuf))
				copy(buf, frameBuf)
				sess.send(buf)
			}
		}
	}
}

// CaptureOnce returns a frame for on-demand consumers (HTTP snapshot, tests).
// It reuses a recent frame when the pump is already producing them, otherwise
// it captures directly - so it works even when nobody is streaming.
func (s *Server) CaptureOnce(maxAge time.Duration) ([]byte, int, int, error) {
	if jpeg, w, h := s.LastFrame(); len(jpeg) > 0 {
		if maxAge <= 0 {
			return jpeg, w, h, nil
		}
		// A frame from the pump is fine as long as it is reasonably fresh.
		if time.Since(s.lastFrameTime()) < maxAge {
			return jpeg, w, h, nil
		}
	}
	s.mu.RLock()
	cap := s.capturer
	s.mu.RUnlock()
	if cap == nil {
		return nil, 0, 0, errNoCapturer
	}
	s.captureMu.Lock()
	f, err := cap.Capture()
	s.captureMu.Unlock()
	if err != nil {
		return nil, 0, 0, err
	}
	return f.JPEG, f.Width, f.Height, nil
}

func (s *Server) lastFrameTime() time.Time {
	s.pumpMu.Lock()
	defer s.pumpMu.Unlock()
	return s.lastFrameAt
}

// LastFrame returns the most recent frame (used by tests and /snapshot).
func (s *Server) LastFrame() ([]byte, int, int) {
	s.pumpMu.Lock()
	defer s.pumpMu.Unlock()
	return s.lastJPEG, s.lastW, s.lastH
}
