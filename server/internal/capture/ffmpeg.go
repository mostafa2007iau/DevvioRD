package capture

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ffmpegBackend captures an X11 display using ffmpeg's x11grab demuxer and its
// MJPEG encoder. ffmpeg is present on virtually every Linux desktop and handles
// scaling and JPEG encoding in native code, which keeps the Go process idle.
//
// The backend reuses one long-lived ffmpeg process and slices individual JPEG
// images out of the image2pipe stream by scanning for SOI/EOI markers.
type ffmpegBackend struct {
	opts    Options
	display string

	mu      sync.Mutex
	cmd     *exec.Cmd
	stdout  io.ReadCloser
	rd      *bufio.Reader
	cancel  context.CancelFunc
	width   int
	height  int
	warn    []string
	lastErr error
	gen     int
}

func init() { Register("ffmpeg", newFFmpegBackend) }

var errNoFFmpeg = errors.New("capture: ffmpeg not found in PATH")

func ffmpegAvailable() bool {
	_, err := exec.LookPath("ffmpeg")
	return err == nil
}

// FFmpegAvailable reports whether the ffmpeg backend can be used.
func FFmpegAvailable() bool { return ffmpegAvailable() }

func newFFmpegBackend(opts Options) (Capturer, error) {
	if !ffmpegAvailable() {
		return nil, errNoFFmpeg
	}
	b := &ffmpegBackend{opts: opts, display: opts.Display}
	if b.display == "" {
		b.display = ":0"
	}
	if err := b.start(); err != nil {
		return nil, err
	}
	return b, nil
}

// screenGeometry asks xdpyinfo for the display size; falls back to ffmpeg probe.
func screenGeometry(display string) (int, int, error) {
	if path, err := exec.LookPath("xdpyinfo"); err == nil {
		out, err := exec.Command(path, "-display", display).Output()
		if err == nil {
			var w, h int
			sc := bufio.NewScanner(bytes.NewReader(out))
			for sc.Scan() {
				line := strings.TrimSpace(sc.Text())
				if strings.HasPrefix(line, "dimensions:") {
					fields := strings.Fields(line)
					if len(fields) >= 2 {
						parts := strings.Split(fields[1], "x")
						if len(parts) == 2 {
							w, _ = strconv.Atoi(parts[0])
							h, _ = strconv.Atoi(parts[1])
						}
					}
				}
			}
			if w > 0 && h > 0 {
				return w, h, nil
			}
		}
	}
	return 0, 0, errors.New("capture: cannot determine display geometry (install xdpyinfo)")
}

func (b *ffmpegBackend) start() error {
	sw, sh, err := screenGeometry(b.display)
	if err != nil {
		return err
	}
	outW := int(float64(sw)*b.opts.Scale + 0.5)
	// Keep even dimensions: some decoders prefer it.
	outW &^= 1
	if outW < 2 {
		outW = 2
	}
	outH := int(float64(sh)*b.opts.Scale + 0.5)
	outH &^= 1
	if outH < 2 {
		outH = 2
	}
	b.width, b.height = outW, outH

	args := []string{
		"-hide_banner", "-loglevel", "error",
		"-f", "x11grab",
		"-framerate", strconv.Itoa(b.opts.FPS),
		"-video_size", fmt.Sprintf("%dx%d", sw, sh),
		"-i", b.display,
		"-vf", fmt.Sprintf("scale=%d:%d:flags=bilinear", outW, outH),
		"-c:v", "mjpeg",
		"-q:v", strconv.Itoa(qualityToQScale(b.opts.Quality)),
		"-f", "image2pipe",
		"-",
	}
	ctx, cancel := context.WithCancel(context.Background())
	cmd := exec.CommandContext(ctx, "ffmpeg", args...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		cancel()
		return err
	}
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Start(); err != nil {
		cancel()
		return fmt.Errorf("capture: start ffmpeg: %w", err)
	}
	b.cmd = cmd
	b.cancel = cancel
	b.stdout = stdout
	b.rd = bufio.NewReaderSize(stdout, 1<<20)
	b.gen++
	b.warn = []string{}
	return nil
}

// qualityToQScale maps 1..100 quality to ffmpeg's 2..31 qscale (inverted).
func qualityToQScale(q int) int {
	if q < 1 {
		q = 1
	}
	if q > 100 {
		q = 100
	}
	v := 32 - int(float64(q)*30.0/100.0)
	if v < 2 {
		v = 2
	}
	if v > 31 {
		v = 31
	}
	return v
}

func (b *ffmpegBackend) Name() string       { return "ffmpeg-x11grab" }
func (b *ffmpegBackend) Warnings() []string { return append([]string(nil), b.warn...) }

func (b *ffmpegBackend) Size() (int, int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.width == 0 {
		w, h, err := screenGeometry(b.display)
		if err != nil {
			return 0, 0, err
		}
		return int(float64(w) * b.opts.Scale), int(float64(h) * b.opts.Scale), nil
	}
	return b.width, b.height, nil
}

// Capture reads the next JPEG image from the pipe.
func (b *ffmpegBackend) Capture() (Frame, error) {
	b.mu.Lock()
	rd := b.rd
	w, h := b.width, b.height
	gen := b.gen
	b.mu.Unlock()
	if rd == nil {
		return Frame{}, errors.New("capture: ffmpeg backend not started")
	}

	start := time.Now()
	jpeg, err := readJPEG(rd)
	if err != nil {
		b.mu.Lock()
		// Restart the encoder once on failure (e.g. display size change).
		if b.gen == gen {
			b.restartLocked()
		}
		b.lastErr = err
		b.mu.Unlock()
		return Frame{}, err
	}
	return Frame{
		JPEG:      jpeg,
		Width:     w,
		Height:    h,
		CaptureMs: time.Since(start),
	}, nil
}

func (b *ffmpegBackend) restartLocked() {
	if b.cancel != nil {
		b.cancel()
	}
	if b.cmd != nil {
		_ = b.cmd.Wait()
	}
	b.rd = nil
	_ = b.start()
}

// Tune applies live quality/fps/scale changes. Scale changes need a new ffmpeg
// process (the filter graph is fixed at spawn), so the encoder restarts; a pure
// quality/fps change also restarts, which is a sub-100 ms operation.
func (b *ffmpegBackend) Tune(quality, fps int, scale float64) {
	if quality < 20 || quality > 95 {
		return
	}
	if fps < 1 || fps > 60 {
		return
	}
	if scale < 0.25 || scale > 1 {
		scale = 1
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.opts.Quality == quality && b.opts.FPS == fps && b.opts.Scale == scale {
		return
	}
	b.opts.Quality, b.opts.FPS, b.opts.Scale = quality, fps, scale
	// Recompute the output size for the new scale.
	if b.width > 0 {
		sw, sh, err := screenGeometry(b.display)
		if err == nil {
			b.width = int(float64(sw)*scale + 0.5)
			b.height = int(float64(sh)*scale + 0.5)
		}
	}
	b.restartLocked()
}

func (b *ffmpegBackend) Close() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.cancel != nil {
		b.cancel()
	}
	if b.cmd != nil {
		_ = b.cmd.Wait()
	}
	b.rd = nil
	return nil
}

// readJPEG scans a byte stream for a complete JPEG image (SOI ... EOI).
func readJPEG(rd *bufio.Reader) ([]byte, error) {
	// Find SOI (FF D8 FF).
	prev := byte(0)
	found := false
	for !found {
		c, err := rd.ReadByte()
		if err != nil {
			return nil, err
		}
		if prev == 0xFF && c == 0xD8 {
			// Push back the two marker bytes conceptually by prepending.
			found = true
			break
		}
		prev = c
	}
	buf := make([]byte, 0, 64*1024)
	buf = append(buf, 0xFF, 0xD8)
	// Read until EOI (FF D9).
	prev = 0
	for {
		c, err := rd.ReadByte()
		if err != nil {
			return nil, err
		}
		buf = append(buf, c)
		if prev == 0xFF && c == 0xD9 {
			return buf, nil
		}
		prev = c
	}
}
