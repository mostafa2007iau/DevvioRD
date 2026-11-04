package capture

import (
	"bytes"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"math"
	"strings"
	"sync"
	"time"

	"github.com/devviord/devviord/server/internal/input"
)

// syntheticBackend paints a self-contained virtual desktop.
//
// It exists for three reasons:
//  1. It gives the app a working remote desktop even when the host has no
//     display server (servers, containers, CI) - the "app mode" desktop.
//  2. It implements Injector, so every input event a client sends is drawn on
//     screen. That makes the whole input pipeline observable and testable.
//  3. It is the reference backend for benchmarks (no external process).
type syntheticBackend struct {
	mu sync.Mutex

	opts Options
	w, h int

	fb   *image.RGBA
	base *image.RGBA // cached wallpaper

	// cursor / pointer state
	cursorX   float64
	cursorY   float64
	cursorVis bool
	mode      string

	// interaction state (rendered on screen)
	typed      []rune
	log        []string
	clicks     int
	taps       []tapMark
	clipboard  string
	lastKey    string
	scrollAcc  float64
	quality    int
	fps        int
	started    time.Time
	frames     uint64
	textScroll int
}

type tapMark struct {
	x, y float64
	at   time.Time
}

func init() { Register("synthetic", newSyntheticBackend) }

// NewSynthetic exposes the synthetic backend for tests and embedders.
func NewSynthetic(opts Options) (Capturer, error) { return newSyntheticBackend(opts) }

func newSyntheticBackend(opts Options) (Capturer, error) {
	w, h := 1280, 800
	b := &syntheticBackend{
		opts:      opts,
		w:         int(float64(w)*opts.Scale + 0.5),
		h:         int(float64(h)*opts.Scale + 0.5),
		mode:      "touch",
		quality:   opts.Quality,
		fps:       opts.FPS,
		started:   time.Now(),
		cursorX:   0.5,
		cursorY:   0.5,
		cursorVis: true,
	}
	b.w &^= 1
	b.h &^= 1
	b.fb = image.NewRGBA(image.Rect(0, 0, b.w, b.h))
	b.base = renderWallpaper(b.w, b.h)
	b.addLog("session started")
	b.addLog("backend: synthetic")
	return b, nil
}

func (b *syntheticBackend) Name() string       { return "synthetic" }
func (b *syntheticBackend) Warnings() []string { return nil }
func (b *syntheticBackend) Size() (int, int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.w, b.h, nil
}
func (b *syntheticBackend) Close() error { return nil }

// Tune applies live quality/fps/scale changes without a restart. Quality takes
// effect on the next encoded frame; scale reallocates the framebuffer only when
// it really changed, so dragging a slider does not thrash memory.
func (b *syntheticBackend) Tune(quality, fps int, scale float64) {
	if quality < 20 || quality > 95 {
		return
	}
	if fps < 1 || fps > 60 {
		fps = b.fps
	}
	if scale < 0.25 || scale > 1 {
		scale = 1
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	b.quality, b.fps = quality, fps
	b.opts.Quality, b.opts.FPS, b.opts.Scale = quality, fps, scale

	// Re-render the virtual desktop at the new size.
	w := int(float64(1280)*scale + 0.5)
	h := int(float64(800)*scale + 0.5)
	w &^= 1
	h &^= 1
	if w > 0 && h > 0 && (w != b.w || h != b.h) {
		b.w, b.h = w, h
		b.fb = image.NewRGBA(image.Rect(0, 0, w, h))
		b.base = renderWallpaper(w, h)
	}
}

func (b *syntheticBackend) Capture() (Frame, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	start := time.Now()
	b.renderLocked()
	b.frames++
	enc := time.Now()
	var buf bytes.Buffer
	buf.Grow(64 * 1024)
	if err := jpeg.Encode(&buf, b.fb, &jpeg.Options{Quality: b.quality}); err != nil {
		return Frame{}, err
	}
	return Frame{
		JPEG:      buf.Bytes(),
		Width:     b.w,
		Height:    b.h,
		CaptureMs: enc.Sub(start),
		EncodeMs:  time.Since(enc),
	}, nil
}

func (b *syntheticBackend) addLog(s string) {
	stamp := time.Now().Format("15:04:05")
	b.log = append(b.log, stamp+"  "+s)
	if len(b.log) > 9 {
		b.log = b.log[len(b.log)-9:]
	}
}

// --- Injector implementation (drives the on-screen desktop) ---

// InjectPointer applies a pointer sample in normalized coordinates.
func (b *syntheticBackend) InjectPointer(x, y float64, action string, pressure float64) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.cursorX, b.cursorY = clamp01(x), clamp01(y)
	b.cursorVis = true
	switch action {
	case "down":
		b.clicks++
		b.taps = append(b.taps, tapMark{x: b.cursorX, y: b.cursorY, at: time.Now()})
		if len(b.taps) > 6 {
			b.taps = b.taps[1:]
		}
		b.addLog(fmt.Sprintf("pointer down @ %.2f,%.2f", b.cursorX, b.cursorY))
	case "up":
	case "scroll":
	}
}

// InjectScroll applies a scroll delta.
func (b *syntheticBackend) InjectScroll(dx, dy float64) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.scrollAcc += dy
	b.textScroll += int(dy * 3)
	if b.textScroll < 0 {
		b.textScroll = 0
	}
	b.addLog(fmt.Sprintf("scroll %.2f", dy))
}

// Key applies a keyboard event (implements input.Injector).
func (b *syntheticBackend) Key(key, action string, mods input.Mods) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.lastKey = key
	if action == "up" {
		return
	}
	switch key {
	case "Enter":
		b.typed = append(b.typed, '\n')
	case "Backspace":
		if len(b.typed) > 0 {
			b.typed = b.typed[:len(b.typed)-1]
		}
	case "Tab":
		b.typed = append(b.typed, '\t')
	case "Space", " ":
		b.typed = append(b.typed, ' ')
	case "Escape":
		b.addLog("key: Escape")
	default:
		if len([]rune(key)) == 1 {
			b.typed = append(b.typed, []rune(key)[0])
		}
	}
	if len(b.typed) > 2000 {
		b.typed = b.typed[len(b.typed)-2000:]
	}
	b.addLog("key: " + key)
}

// Text applies committed IME text (implements input.Injector).
func (b *syntheticBackend) Text(s string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.typed = append(b.typed, []rune(s)...)
	if len(b.typed) > 2000 {
		b.typed = b.typed[len(b.typed)-2000:]
	}
	b.addLog(fmt.Sprintf("ime text delivered (%d unicode chars)", len([]rune(s))))
}

// ShowClipboard records a clipboard update from the client.
func (b *syntheticBackend) ShowClipboard(s string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.clipboard = s
	b.addLog(fmt.Sprintf("clipboard in: %d chars", len([]rune(s))))
}

// SetPointerMode switches rendering between touch and mouse presentation.
func (b *syntheticBackend) SetPointerMode(mode string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.mode = mode
	b.addLog("pointer mode: " + mode)
}

// TypedText returns the accumulated text (used by tests).
func (b *syntheticBackend) TypedText() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return string(b.typed)
}

// Clicks returns the number of pointer-down events (used by tests).
func (b *syntheticBackend) Clicks() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.clicks
}

// --- rendering ---

var (
	colDesktopTop = color.RGBA{0x0e, 0x1b, 0x2e, 0xff}
	colDesktopBot = color.RGBA{0x1c, 0x3a, 0x52, 0xff}
	colWindow     = color.RGBA{0x14, 0x18, 0x1f, 0xff}
	colTitle      = color.RGBA{0x24, 0x2c, 0x38, 0xff}
	colAccent     = color.RGBA{0x3d, 0xd5, 0x98, 0xff}
	colAccent2    = color.RGBA{0x5b, 0x9d, 0xf5, 0xff}
	colText       = color.RGBA{0xe6, 0xed, 0xf3, 0xff}
	colDim        = color.RGBA{0x8b, 0x9d, 0xb0, 0xff}
	colTaskbar    = color.RGBA{0x0b, 0x0f, 0x16, 0xee}
	colPanel      = color.RGBA{0x1b, 0x22, 0x2c, 0xff}
)

func clamp01(v float64) float64 {
	if v < 0 {
		return 0
	}
	if v > 1 {
		return 1
	}
	return v
}

func renderWallpaper(w, h int) *image.RGBA {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		t := float64(y) / float64(h)
		for x := 0; x < w; x++ {
			// Diagonal sheen for a less flat look.
			d := float64(x)/float64(w) - t
			f := 0.06 * math.Sin(d*3.1)
			img.SetRGBA(x, y, color.RGBA{
				R: uint8(float64(colDesktopTop.R)*(1-t) + float64(colDesktopBot.R)*t + f*40),
				G: uint8(float64(colDesktopTop.G)*(1-t) + float64(colDesktopBot.G)*t + f*50),
				B: uint8(float64(colDesktopTop.B)*(1-t) + float64(colDesktopBot.B)*t + f*60),
				A: 0xff,
			})
		}
	}
	return img
}

func (b *syntheticBackend) renderLocked() {
	copy(b.fb.Pix, b.base.Pix)
	W, H := b.w, b.h
	scale := float64(W) / 1280.0
	if scale <= 0 {
		scale = 1
	}
	px := func(v float64) int { return int(v*scale + 0.5) }

	taskbarH := px(52)
	// --- desktop icons (top-left) ---
	iconSize := px(58)
	for i := 0; i < 4; i++ {
		x := px(24)
		y := px(28) + i*(iconSize+px(26))
		fillRoundRect(b.fb, x, y, iconSize, iconSize, px(12), color.RGBA{0x2e, 0x4a, 0x6b, 0xff})
		fillRoundRect(b.fb, x+px(14), y+px(14), iconSize-px(28), iconSize-px(28), px(6),
			color.RGBA{0x6f, 0xd0, 0xff, 0xff})
	}

	// --- main window ---
	wx, wy := px(180), px(70)
	ww, wh := W-px(260), H-px(210)
	fillRoundRect(b.fb, wx+px(6), wy+px(8), ww, wh, px(14), color.RGBA{0, 0, 0, 0x50})
	fillRoundRect(b.fb, wx, wy, ww, wh, px(14), colWindow)
	fillRoundRect(b.fb, wx, wy, ww, px(46), px(14), colTitle)
	fillRoundRect(b.fb, wx, wy+px(30), ww, px(16), 0, colTitle)

	fs := px(2) // font scale
	if fs < 1 {
		fs = 1
	}
	drawText(b.fb, wx+px(18), wy+px(14), "DevvioRD Virtual Desktop", colText, fs)
	// window buttons
	btnY := wy + px(13)
	for i, c := range []color.RGBA{{0xff, 0x5f, 0x57, 0xff}, {0xfe, 0xbc, 0x2e, 0xff}, {0x28, 0xc8, 0x40, 0xff}} {
		bx := wx + ww - px(30) - i*px(26)
		fillRoundRect(b.fb, bx, btnY, px(14), px(14), px(7), c)
	}

	// --- editor pane ---
	ex, ey := wx+px(20), wy+px(66)
	ew, eh := ww-px(40), wh-px(66)-px(12)-px(96)-px(14)
	if eh < px(120) {
		eh = wh - px(66) - px(20)
	}
	fillRoundRect(b.fb, ex, ey, ew, eh, px(8), color.RGBA{0x0d, 0x11, 0x17, 0xff})
	strokeRect(b.fb, ex, ey, ew, eh, px(8), color.RGBA{0x2b, 0x35, 0x42, 0xff})

	lineH := px(18)
	line := 0
	drawText(b.fb, ex+px(14), ey+px(12), "> keyboard / IME input appears below", colAccent, fs)
	line++
	// wrap typed text
	maxCols := (ew - px(28)) / (6 * fs)
	if maxCols < 8 {
		maxCols = 8
	}
	// If the text contains characters the bitmap font cannot draw, display a
	// romanised copy so the desktop stays readable; the script tag below says
	// what actually arrived.
	display := b.typed
	if hasNonLatin(b.typed) {
		display = romanizeRunes(b.typed)
	}
	lines := wrapRunes(display, maxCols)
	if len(lines) > 1 {
		start := len(lines) - 1 - b.textScroll
		if start < 0 {
			start = 0
		}
		lines = lines[start:]
	}
	maxLines := (eh - px(46)) / lineH
	if maxLines < 1 {
		maxLines = 1
	}
	// Whenever the typed text contains characters the built-in 5x7 font cannot
	// draw (Persian/Arabic/CJK/emoji), show a readable transliteration line so a
	// human watching the virtual desktop still sees that their input arrived.
	// The exact code points are reported in the event log below.
	note := ""
	if hasNonLatin(b.typed) {
		note = describeNonLatin(b.typed)
		if i := strings.Index(note, ": "); i >= 0 {
			note = note[:i] // the text itself is already displayed above
		}
	}
	noteLine := -1
	if note != "" {
		noteLine = line + len(lines)
	}
	for i, l := range lines {
		if i >= maxLines {
			break
		}
		drawText(b.fb, ex+px(14), ey+px(12)+(line+i)*lineH, l, colText, fs)
	}
	if note != "" && noteLine-line < maxLines {
		drawText(b.fb, ex+px(14), ey+px(12)+noteLine*lineH, note, colAccent2, fs)
	}
	// caret
	if int(time.Now().UnixNano()/int64(time.Millisecond))%1000 < 620 {
		cy := ey + px(12) + (line+len(lines)-1)*lineH
		if len(lines) == 0 {
			cy = ey + px(12) + line*lineH
		}
		cx := ex + px(14)
		if len(lines) > 0 {
			cx += runeWidth(lines[len(lines)-1], fs)
		}
		fb := b.fb
		for yy := cy; yy < cy+7*fs && yy < ey+eh; yy++ {
			for xx := cx; xx < cx+2*fs && xx < ex+ew; xx++ {
				fb.SetRGBA(xx, yy, colAccent)
			}
		}
	}

	// --- event log pane ---
	ly := ey + eh + px(10)
	lh := wh - (ly - wy) - px(12)
	if lh > px(96) {
		lh = px(96)
	}
	if lh > 0 {
		fillRoundRect(b.fb, ex, ly, ew, lh, px(8), colPanel)
		drawText(b.fb, ex+px(12), ly+px(10), "EVENTS", colDim, fs)
		// Show the newest events that fit; b.log already holds a bounded tail.
		rows := (lh - px(30)) / lineH
		if rows < 1 {
			rows = 1
		}
		start := len(b.log) - rows
		if start < 0 {
			start = 0
		}
		for i, l := range b.log[start:] {
			drawText(b.fb, ex+px(12), ly+px(30)+i*lineH, l, colText, fs)
		}
	}

	// --- taskbar ---
	ty := H - taskbarH
	fillRoundRect(b.fb, 0, ty, W, taskbarH, 0, colTaskbar)
	// Uppercase on purpose: the 5x7 pixel font has no proper descenders, so
	// lowercase p/q/y read as garbage at this size.
	stat := fmt.Sprintf("%d x %d   %d FPS   Q%d   TAPS %d", b.w, b.h, b.fps, b.quality, b.clicks)
	lay := layoutTaskbar(b.w, px, fs, b.mode, stat)

	drawText(b.fb, lay.brandX, ty+px(18), lay.brand, colAccent, fs+1)
	// Chip background first, label on top: the badge reads as a solid pill.
	chipH := taskbarH - px(24)
	if chipH > 0 {
		fillRoundRect(b.fb, lay.chipX, ty+px(24)-chipH/2, lay.chipW, chipH, px(6), colTaskbar)
		strokeRect(b.fb, lay.chipX, ty+px(24)-chipH/2, lay.chipW, chipH, px(6), lay.modeCol)
	}
	drawText(b.fb, lay.chipX+px(12), ty+px(18), lay.label, lay.modeCol, fs)
	if lay.showStat {
		drawText(b.fb, lay.statX, ty+px(18), lay.stat, colDim, fs)
	}
	drawText(b.fb, lay.clockX, ty+px(16), lay.clock, colText, lay.clockScale)

	// --- clipboard badge ---
	if b.clipboard != "" {
		preview := []rune(b.clipboard)
		if len(preview) > 28 {
			preview = append(preview[:28], []rune("...")...)
		}
		// Same rule as the editor: romanise non-Latin text so the badge is
		// readable, instead of a row of question marks.
		shown := sanitizeForFont(string(preview))
		if hasNonLatin(preview) {
			shown = describeNonLatin(preview)
		}
		label := "CLIP: " + shown
		bw := runeWidth(label, fs) + px(24)
		fillRoundRect(b.fb, px(24), H-taskbarH-px(46), bw, px(32), px(8), color.RGBA{0x25, 0x3a, 0x2f, 0xff})
		drawText(b.fb, px(36), H-taskbarH-px(38), label, colAccent, fs)
	}

	// --- pointer ---
	if b.cursorVis {
		cx := int(b.cursorX * float64(W))
		cy := int(b.cursorY * float64(H))
		if b.mode == "mouse" {
			drawArrowCursor(b.fb, cx, cy, px(3))
		}
		// Touch ripples fade out over 350ms.
		now := time.Now()
		kept := b.taps[:0]
		for _, t := range b.taps {
			age := now.Sub(t.at)
			if age > 400*time.Millisecond {
				continue
			}
			kept = append(kept, t)
			f := float64(age) / float64(400*time.Millisecond)
			r := px(10) + int(f*float64(px(46)))
			alpha := uint8(0xff * (1 - f))
			ringCircle(b.fb, int(t.x*float64(W)), int(t.y*float64(H)), r, px(3), color.RGBA{0xff, 0xff, 0xff, alpha})
		}
		b.taps = kept
	}

	// watermark
	drawText(b.fb, W-px(300), wy+px(14), "v1.0", colDim, fs)
	_ = H
}

// taskbarLayout holds the computed positions for one taskbar row. It is a pure
// function of the surface size so that no element can overlap another at any
// resolution - the brand, the mode chip, the stats line and the clock each get
// the space their own text needs.
type taskbarLayout struct {
	brand, label, stat, clock string
	brandX, chipX, chipW      int
	statX                     int
	clockX                    int
	clockScale                int
	showStat                  bool
	modeCol                   color.RGBA
}

// layoutTaskbar computes the taskbar geometry. px converts reference pixels
// (designed at 1280 wide) to real pixels.
func layoutTaskbar(width int, px func(float64) int, fs int, mode, stat string) taskbarLayout {
	l := taskbarLayout{brand: "DevvioRD", clock: time.Now().Format("15:04")}
	l.modeCol = colAccent2
	l.label = "TOUCH"
	if mode == "mouse" {
		l.modeCol = colAccent
		l.label = "MOUSE"
	}
	// Clock first: it is anchored to the right edge and is never dropped.
	l.clockScale = fs + 1
	l.clockX = width - px(18) - runeWidth(l.clock, l.clockScale)
	if l.clockX < width/2 {
		l.clockScale = fs
		l.clockX = width - px(18) - runeWidth(l.clock, l.clockScale)
	}

	l.brandX = px(18)
	l.chipX = l.brandX + runeWidth(l.brand, fs+1) + px(18)
	l.chipW = runeWidth(l.label, fs) + px(24)

	l.stat = stat
	l.statX = l.chipX + l.chipW + px(20)
	// Only show stats when they genuinely fit before the clock.
	l.showStat = l.statX+runeWidth(l.stat, fs) < l.clockX-px(16)
	return l
}

// hasNonLatin reports whether s contains a rune the bitmap font cannot render.
func hasNonLatin(rs []rune) bool {
	for _, r := range rs {
		if r == '\n' || r == '\t' {
			continue
		}
		if r < 32 || r > 126 {
			return true
		}
	}
	return false
}

// romanizeRunes converts non-Latin text to its Latin approximation so the
// bitmap font can draw it. ASCII passes through unchanged.
func romanizeRunes(rs []rune) []rune {
	out := make([]rune, 0, len(rs))
	for _, r := range rs {
		if r < 128 {
			out = append(out, r)
			continue
		}
		for _, c := range latinFallback(r) {
			out = append(out, c)
		}
	}
	return out
}

// describeNonLatin reports what kind of non-Latin text arrived.
func describeNonLatin(rs []rune) string {
	var b strings.Builder
	count := 0
	script := ""
	for _, r := range rs {
		if r == '\n' || r == '\t' || (r >= 32 && r <= 126) {
			continue
		}
		count++
		if script == "" {
			script = scriptTag(r)
		}
	}
	if count == 0 {
		return ""
	}
	fmt.Fprintf(&b, "[%s] %d chars: ", script, count)
	// Romanise a bounded prefix so the line stays inside the pane.
	n := 0
	for _, r := range rs {
		if n >= 40 {
			b.WriteString("...")
			break
		}
		if r < 128 {
			if r >= 32 {
				b.WriteRune(r)
				n++
			}
			continue
		}
		if lat := latinFallback(r); lat != "" {
			b.WriteString(lat)
			n += len(lat)
		}
	}
	return b.String()
}

// scriptTag names the writing system of a rune, for the on-screen badge.
func scriptTag(r rune) string {
	switch {
	case r >= 0x0600 && r <= 0x06FF, r >= 0xFB50 && r <= 0xFEFF:
		return "ar/fa"
	case r >= 0x0590 && r <= 0x05FF:
		return "he"
	case r >= 0x0400 && r <= 0x04FF:
		return "cy"
	case r >= 0x0370 && r <= 0x03FF:
		return "el"
	case r >= 0x0900 && r <= 0x097F:
		return "hi"
	case r >= 0x4E00 && r <= 0x9FFF, r >= 0x3040 && r <= 0x30FF:
		return "cjk"
	case r >= 0x1F000 && r <= 0x1FAFF, r >= 0x2600 && r <= 0x27BF:
		return "emoji"
	case r >= 0x2000 && r <= 0x206F:
		return "punct"
	}
	return "unicode"
}

// latinFallback maps the Persian/Arabic letters used most in chat to a short
// Latin approximation, so the virtual desktop stays readable without a real
// shaping engine. Unmapped characters are skipped.
func latinFallback(r rune) string {
	if lat, ok := persianLatin[r]; ok {
		return lat
	}
	if r == ' ' {
		return " "
	}
	return ""
}

var persianLatin = map[rune]string{
	'\u0627': "a", '\u0622': "a", '\u0623': "a", '\u0625': "e", '\u0628': "b",
	'\u067E': "p", '\u062A': "t", '\u062B': "s", '\u062C': "j", '\u0686': "ch",
	'\u062D': "h", '\u062E': "kh", '\u062F': "d", '\u0630': "z", '\u0631': "r",
	'\u0632': "z", '\u0698': "zh", '\u0633': "s", '\u0634': "sh", '\u0635': "s",
	'\u0636': "z", '\u0637': "t", '\u0638': "z", '\u0639': "a", '\u063A': "gh",
	'\u0641': "f", '\u0642': "gh", '\u06A9': "k", '\u0643': "k", '\u06AF': "g",
	'\u0644': "l", '\u0645': "m", '\u0646': "n", '\u0648': "v", '\u0647': "h",
	'\u06CC': "y", '\u064A': "y", '\u0621': "'", '\u0626': "y", '\u0624': "v",
	'\u064B': "", '\u064C': "", '\u064D': "", '\u064E': "a", '\u064F': "o",
	'\u0650': "e", '\u0651': "", '\u0652': "", '\u0640': "",
	'\u060C': ",", '\u061B': ";", '\u061F': "?", '\u06D4': ".",
	'\u06F0': "0", '\u06F1': "1", '\u06F2': "2", '\u06F3': "3", '\u06F4': "4",
	'\u06F5': "5", '\u06F6': "6", '\u06F7': "7", '\u06F8': "8", '\u06F9': "9",
}

func sanitizeForFont(s string) string {
	out := make([]rune, 0, len(s))
	for _, r := range s {
		if r < 32 || r > 126 {
			out = append(out, '?')
			continue
		}
		out = append(out, r)
	}
	return string(out)
}

func wrapRunes(rs []rune, cols int) []string {
	if cols < 1 {
		cols = 1
	}
	var lines []string
	cur := make([]rune, 0, cols)
	for _, r := range rs {
		if r == '\n' {
			lines = append(lines, string(cur))
			cur = cur[:0]
			continue
		}
		if r == '\t' {
			for i := 0; i < 4; i++ {
				cur = append(cur, ' ')
				if len(cur) >= cols {
					lines = append(lines, string(cur))
					cur = cur[:0]
				}
			}
			continue
		}
		if r < 32 || r > 126 {
			// Non-Latin text cannot be drawn with the built-in bitmap font;
			// keep a visible marker instead of a wall of "?".
			cur = append(cur, '\u00b7', '?')
			continue
		}
		cur = append(cur, r)
		if len(cur) >= cols {
			lines = append(lines, string(cur))
			cur = cur[:0]
		}
	}
	lines = append(lines, string(cur))
	return lines
}

// ---- tiny raster helpers ----

func fillRoundRect(img *image.RGBA, x, y, w, h, r int, c color.RGBA) {
	if w <= 0 || h <= 0 {
		return
	}
	b := img.Rect
	// Clamp to the framebuffer once, then draw with fast scanlines.
	x0, y0 := x, y
	x1, y1 := x+w, y+h
	if x0 < b.Min.X {
		x0 = b.Min.X
	}
	if y0 < b.Min.Y {
		y0 = b.Min.Y
	}
	if x1 > b.Max.X {
		x1 = b.Max.X
	}
	if y1 > b.Max.Y {
		y1 = b.Max.Y
	}
	if x0 >= x1 || y0 >= y1 {
		return
	}
	rr := float64(r)
	if rr > float64(w)/2 {
		rr = float64(w) / 2
	}
	if rr > float64(h)/2 {
		rr = float64(h) / 2
	}
	opaque := c.A == 0xff
	for yy := 0; yy < h; yy++ {
		py := y + yy
		if py < y0 || py >= y1 {
			continue
		}
		inset := 0
		if rr > 0 {
			// Distance from the vertical centre of the nearest corner arc.
			fy := float64(yy)
			var dy float64
			if fy < rr {
				dy = rr - fy - 0.5
			} else if fy > float64(h-1)-rr {
				dy = fy - (float64(h-1) - rr) - 0.5
			}
			if dy > 0 {
				if dy >= rr {
					continue
				}
				inset = int(rr - math.Sqrt(rr*rr-dy*dy) + 0.5)
			}
		}
		sx := x + inset
		ex := x + w - inset
		if sx < x0 {
			sx = x0
		}
		if ex > x1 {
			ex = x1
		}
		if sx >= ex {
			continue
		}
		row := img.PixOffset(sx, py)
		if opaque {
			for i := row; i < row+(ex-sx)*4; i += 4 {
				img.Pix[i] = c.R
				img.Pix[i+1] = c.G
				img.Pix[i+2] = c.B
				img.Pix[i+3] = 0xff
			}
			continue
		}
		a := float64(c.A) / 255.0
		ia := 1 - a
		for i := row; i < row+(ex-sx)*4; i += 4 {
			img.Pix[i] = uint8(float64(c.R)*a + float64(img.Pix[i])*ia)
			img.Pix[i+1] = uint8(float64(c.G)*a + float64(img.Pix[i+1])*ia)
			img.Pix[i+2] = uint8(float64(c.B)*a + float64(img.Pix[i+2])*ia)
			img.Pix[i+3] = 0xff
		}
	}
}

func strokeRect(img *image.RGBA, x, y, w, h, r int, c color.RGBA) {
	for xx := x; xx < x+w; xx++ {
		setPx(img, xx, y, c)
		setPx(img, xx, y+h-1, c)
	}
	for yy := y; yy < y+h; yy++ {
		setPx(img, x, yy, c)
		setPx(img, x+w-1, yy, c)
	}
}

func ringCircle(img *image.RGBA, cx, cy, r, thick int, c color.RGBA) {
	if r <= 0 {
		return
	}
	inner := r - thick
	if inner < 0 {
		inner = 0
	}
	for yy := cy - r; yy <= cy+r; yy++ {
		for xx := cx - r; xx <= cx+r; xx++ {
			dx := float64(xx - cx)
			dy := float64(yy - cy)
			d2 := dx*dx + dy*dy
			if d2 <= float64(r*r) && d2 >= float64(inner*inner) {
				setPx(img, xx, yy, c)
			}
		}
	}
}

func drawArrowCursor(img *image.RGBA, x, y, s int) {
	// Simple filled arrow, s pixels per unit.
	shape := []string{
		"1",
		"11",
		"101",
		"1001",
		"10001",
		"100001",
		"1111111",
		"1000110",
		"1000011",
		"10000110",
		"10000011",
		"1100011",
		"01100110",
		"00111100",
		"00011000",
	}
	for row, line := range shape {
		for col, ch := range line {
			if ch != '1' {
				continue
			}
			for dy := 0; dy < s; dy++ {
				for dx := 0; dx < s; dx++ {
					px := x + col*s + dx
					py := y + row*s + dy
					setPx(img, px+1, py+1, color.RGBA{0, 0, 0, 0x90})
					setPx(img, px, py, color.RGBA{0xff, 0xff, 0xff, 0xff})
				}
			}
		}
	}
}

func setPx(img *image.RGBA, x, y int, c color.RGBA) {
	b := img.Rect
	if x < b.Min.X || y < b.Min.Y || x >= b.Max.X || y >= b.Max.Y {
		return
	}
	i := img.PixOffset(x, y)
	if c.A == 0xff {
		img.Pix[i] = c.R
		img.Pix[i+1] = c.G
		img.Pix[i+2] = c.B
		img.Pix[i+3] = 0xff
		return
	}
	// alpha blend
	a := float64(c.A) / 255.0
	img.Pix[i] = uint8(float64(c.R)*a + float64(img.Pix[i])*(1-a))
	img.Pix[i+1] = uint8(float64(c.G)*a + float64(img.Pix[i+1])*(1-a))
	img.Pix[i+2] = uint8(float64(c.B)*a + float64(img.Pix[i+2])*(1-a))
	img.Pix[i+3] = 0xff
}

// drawText renders ASCII text with the built-in 5x7 font.
func drawText(img *image.RGBA, x, y int, s string, c color.RGBA, scale int) {
	if scale < 1 {
		scale = 1
	}
	cx := x
	for _, r := range s {
		if r == ' ' {
			cx += 3 * scale
			continue
		}
		if r < 0x20 || r > 0x7e {
			r = '?'
		}
		g := font5x7[r-0x20]
		for col := 0; col < 5; col++ {
			bits := g[col]
			for row := 0; row < 7; row++ {
				if bits&(1<<uint(row)) == 0 {
					continue
				}
				for sy := 0; sy < scale; sy++ {
					for sx := 0; sx < scale; sx++ {
						setPx(img, cx+col*scale+sx, y+row*scale+sy, c)
					}
				}
			}
		}
		cx += 6 * scale
	}
}

func runeWidth(s string, scale int) int {
	n := 0
	for _, r := range s {
		if r == ' ' {
			n += 3
			continue
		}
		n += 6
	}
	return n * scale
}

// SanitizeASCII is exported for tests.
func SanitizeASCII(s string) string { return sanitizeForFont(s) }

// Compile-time check: the synthetic desktop is a valid input injector.
var _ input.Injector = (*syntheticBackend)(nil)
