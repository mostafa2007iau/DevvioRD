// Package input injects pointer and keyboard events into the remote desktop.
package input

import (
	"errors"
	"fmt"
	"os/exec"
	"strconv"
	"strings"
	"sync"
)

// Injector receives normalized input events.
type Injector interface {
	// Pointer moves / clicks a pointer. x,y are normalized 0..1.
	// action is "down", "up", "move", "cancel".
	InjectPointer(x, y float64, action string, pressure float64)
	// Scroll applies a scroll delta (~1.0 == one page).
	InjectScroll(dx, dy float64)
	// Key sends a keyboard event. key is a W3C KeyboardEvent.key value.
	// action is "down", "up" or "press".
	Key(key, action string, mods Mods)
	// Text sends committed IME text (any Unicode).
	Text(s string)
	// SetPointerMode switches the client-side pointer model.
	SetPointerMode(mode string)
	// Name identifies the backend for diagnostics.
	Name() string
	// Warnings returns non-fatal notes.
	Warnings() []string
	// Close releases resources.
	Close() error
}

// Mods is the modifier set for a key event.
type Mods struct {
	Ctrl  bool
	Alt   bool
	Shift bool
	Meta  bool
}

func (m Mods) String() string {
	var b strings.Builder
	if m.Ctrl {
		b.WriteString("ctrl+")
	}
	if m.Alt {
		b.WriteString("alt+")
	}
	if m.Shift {
		b.WriteString("shift+")
	}
	if m.Meta {
		b.WriteString("super+")
	}
	return b.String()
}

var errNoXdotool = errors.New("input: neither xdotool nor ydotool is available")

// NewX11 returns an X11 input backend. It prefers xdotool (XTEST).
func NewX11(display string) (Injector, error) {
	if p, err := exec.LookPath("xdotool"); err == nil {
		return &xdotoolInjector{bin: p, display: display}, nil
	}
	if p, err := exec.LookPath("ydotool"); err == nil {
		return &xdotoolInjector{bin: p, display: display, ydotool: true}, nil
	}
	return nil, errNoXdotool
}

// X11Available reports whether an X11 input backend exists.
func X11Available() bool {
	if _, err := exec.LookPath("xdotool"); err == nil {
		return true
	}
	if _, err := exec.LookPath("ydotool"); err == nil {
		return true
	}
	return false
}

type xdotoolInjector struct {
	mu      sync.Mutex
	bin     string
	display string
	ydotool bool
	warn    []string
	w, h    int // last known screen size for absolute moves
}

func (x *xdotoolInjector) Name() string { return "x11:" + x.bin }
func (x *xdotoolInjector) Warnings() []string {
	x.mu.Lock()
	defer x.mu.Unlock()
	return append([]string(nil), x.warn...)
}
func (x *xdotoolInjector) Close() error { return nil }

func (x *xdotoolInjector) run(args ...string) error {
	cmd := exec.Command(x.bin, args...)
	if x.display != "" {
		cmd.Env = append(cmd.Environ(), "DISPLAY="+x.display)
	}
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("input: %s %v: %w (%s)", x.bin, args, err, strings.TrimSpace(string(out)))
	}
	return nil
}

// screenSize queries geometry once (cached).
func (x *xdotoolInjector) screenSize() (int, int) {
	if x.w > 0 && x.h > 0 {
		return x.w, x.h
	}
	cmd := exec.Command(x.bin, "getdisplaygeometry")
	if x.display != "" {
		cmd.Env = append(cmd.Environ(), "DISPLAY="+x.display)
	}
	out, err := cmd.Output()
	if err == nil {
		fields := strings.Fields(string(out))
		if len(fields) == 2 {
			w, _ := strconv.Atoi(fields[0])
			h, _ := strconv.Atoi(fields[1])
			if w > 0 && h > 0 {
				x.w, x.h = w, h
				return w, h
			}
		}
	}
	return 1920, 1080
}

func (x *xdotoolInjector) InjectPointer(nx, ny float64, action string, pressure float64) {
	x.mu.Lock()
	defer x.mu.Unlock()
	w, h := x.screenSize()
	px := int(clamp01(nx)*float64(w-1) + 0.5)
	py := int(clamp01(ny)*float64(h-1) + 0.5)
	_ = x.run("mousemove", "--sync", strconv.Itoa(px), strconv.Itoa(py))
	switch action {
	case "down":
		_ = x.run("mousedown", "1")
	case "up":
		_ = x.run("mouseup", "1")
	case "cancel":
		_ = x.run("mouseup", "1")
	}
}

func (x *xdotoolInjector) InjectScroll(dx, dy float64) {
	x.mu.Lock()
	defer x.mu.Unlock()
	// xdotool: button 4 = wheel up, 5 = wheel down, 6/7 horizontal.
	steps := int(dy*3 + 0.5)
	btn := "4"
	if steps < 0 {
		btn = "5"
		steps = -steps
	}
	if steps > 30 {
		steps = 30
	}
	for i := 0; i < steps; i++ {
		_ = x.run("click", btn)
	}
	hSteps := int(dx*3 + 0.5)
	hbtn := "7"
	if hSteps < 0 {
		hbtn = "6"
		hSteps = -hSteps
	}
	for i := 0; i < hSteps && i < 30; i++ {
		_ = x.run("click", hbtn)
	}
}

func (x *xdotoolInjector) Key(key, action string, mods Mods) {
	x.mu.Lock()
	defer x.mu.Unlock()
	sym := KeysymFor(key, mods.Shift)
	if sym == "" {
		return
	}
	combo := mods.String() + sym
	switch action {
	case "up":
		_ = x.run("keyup", combo)
	default:
		_ = x.run("key", "--clearmodifiers", combo)
	}
}

func (x *xdotoolInjector) Text(s string) {
	if s == "" {
		return
	}
	x.mu.Lock()
	defer x.mu.Unlock()
	if !x.ydotool {
		// xdotool type handles Unicode by temporary keysym remapping.
		if err := x.run("type", "--clearmodifiers", "--delay", "6", "--", s); err == nil {
			return
		}
	}
	// Fallback: one key event per rune via key mapping.
	for _, r := range s {
		if sym := KeysymForRune(r); sym != "" {
			_ = x.run("key", "--clearmodifiers", sym)
		}
	}
}

func (x *xdotoolInjector) SetPointerMode(string) {}

func clamp01(v float64) float64 {
	if v < 0 {
		return 0
	}
	if v > 1 {
		return 1
	}
	return v
}

// KeysymFor maps a W3C `KeyboardEvent.key` value to an X11 keysym name
// understood by `xdotool key`.
func KeysymFor(key string, shift bool) string {
	if r := []rune(key); len(r) == 1 {
		return KeysymForRune(r[0])
	}
	switch key {
	case "Enter", "Return":
		return "Return"
	case "Backspace":
		return "BackSpace"
	case "Tab":
		return "Tab"
	case "Escape", "Esc":
		return "Escape"
	case " ":
		return "space"
	case "Delete", "Del":
		return "Delete"
	case "Insert":
		return "Insert"
	case "Home":
		return "Home"
	case "End":
		return "End"
	case "PageUp":
		return "Page_Up"
	case "PageDown":
		return "Page_Down"
	case "ArrowLeft", "Left":
		return "Left"
	case "ArrowRight", "Right":
		return "Right"
	case "ArrowUp", "Up":
		return "Up"
	case "ArrowDown", "Down":
		return "Down"
	case "CapsLock":
		return "Caps_Lock"
	case "Shift":
		return "Shift_L"
	case "Control":
		return "Control_L"
	case "Alt":
		return "Alt_L"
	case "Meta", "OS", "Super":
		return "Super_L"
	case "ContextMenu":
		return "Menu"
	}
	if strings.HasPrefix(key, "F") && len(key) <= 3 {
		if n, err := strconv.Atoi(key[1:]); err == nil && n >= 1 && n <= 24 {
			return key
		}
	}
	return ""
}

// KeysymForRune maps a single rune to an X11 keysym name.
func KeysymForRune(r rune) string {
	if r >= 'a' && r <= 'z' {
		return string(r)
	}
	if r >= 'A' && r <= 'Z' {
		return strings.ToLower(string(r)) // shifted by caller via mods
	}
	if r >= '0' && r <= '9' {
		return string(r)
	}
	switch r {
	case ' ':
		return "space"
	case '!':
		return "exclam"
	case '"':
		return "quotedbl"
	case '#':
		return "numbersign"
	case '$':
		return "dollar"
	case '%':
		return "percent"
	case '&':
		return "ampersand"
	case '\'':
		return "apostrophe"
	case '(':
		return "parenleft"
	case ')':
		return "parenright"
	case '*':
		return "asterisk"
	case '+':
		return "plus"
	case ',':
		return "comma"
	case '-':
		return "minus"
	case '.':
		return "period"
	case '/':
		return "slash"
	case ':':
		return "colon"
	case ';':
		return "semicolon"
	case '<':
		return "less"
	case '=':
		return "equal"
	case '>':
		return "greater"
	case '?':
		return "question"
	case '@':
		return "at"
	case '[':
		return "bracketleft"
	case '\\':
		return "backslash"
	case ']':
		return "bracketright"
	case '^':
		return "asciicircum"
	case '_':
		return "underscore"
	case '`':
		return "grave"
	case '{':
		return "braceleft"
	case '|':
		return "bar"
	case '}':
		return "braceright"
	case '~':
		return "asciitilde"
	case '\n':
		return "Return"
	case '\t':
		return "Tab"
	case '\b':
		return "BackSpace"
	}
	return ""
}
