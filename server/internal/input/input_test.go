package input

import "testing"

// The keyboard path is what makes non-Latin typing work: the Android IME sends
// W3C-style key names plus committed text, and the server turns those into X11
// keysyms. A regression here silently breaks Persian/Arabic/CJK input.

func TestKeysymForNamedKeys(t *testing.T) {
	cases := map[string]string{
		"Enter": "Return", "Return": "Return",
		"Backspace": "BackSpace", "Tab": "Tab",
		"Escape": "Escape", "Esc": "Escape",
		" ": "space", "Delete": "Delete", "Del": "Delete",
		"Insert": "Insert", "Home": "Home", "End": "End",
		"PageUp": "Page_Up", "PageDown": "Page_Down",
		"ArrowLeft": "Left", "Left": "Left",
		"ArrowRight": "Right", "Right": "Right",
		"ArrowUp": "Up", "Up": "Up",
		"ArrowDown": "Down", "Down": "Down",
		"CapsLock": "Caps_Lock", "Shift": "Shift_L",
		"Control": "Control_L", "Alt": "Alt_L",
		"Meta": "Super_L", "OS": "Super_L", "Super": "Super_L",
		"ContextMenu": "Menu",
	}
	for in, want := range cases {
		if got := KeysymFor(in, false); got != want {
			t.Errorf("KeysymFor(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestKeysymForFunctionKeys(t *testing.T) {
	for _, k := range []string{"F1", "F5", "F12", "F24"} {
		if got := KeysymFor(k, false); got != k {
			t.Errorf("KeysymFor(%q) = %q, want %q", k, got, k)
		}
	}
	for _, k := range []string{"F0", "F25", "F99"} {
		if got := KeysymFor(k, false); got != "" {
			t.Errorf("KeysymFor(%q) = %q, want empty", k, got)
		}
	}
}

func TestKeysymForLettersIsCaseShiftedNotDuplicated(t *testing.T) {
	// Uppercase letters map to the lowercase keysym: the caller adds Shift.
	if got := KeysymFor("A", false); got != "a" {
		t.Errorf(`KeysymFor("A") = %q, want "a"`, got)
	}
	if got := KeysymFor("a", false); got != "a" {
		t.Errorf(`KeysymFor("a") = %q, want "a"`, got)
	}
	if got := KeysymFor("Z", false); got != "z" {
		t.Errorf(`KeysymFor("Z") = %q, want "z"`, got)
	}
}

func TestKeysymForDigitsAndSymbols(t *testing.T) {
	for _, d := range []string{"0", "5", "9"} {
		if got := KeysymFor(d, false); got != d {
			t.Errorf("KeysymFor(%q) = %q, want %q", d, got, d)
		}
	}
	// Common punctuation must resolve to something, not to "".
	for _, sym := range []string{".", ",", "/", "-", "=", ";", "'", "[", "]", "\\"} {
		if got := KeysymFor(sym, false); got == "" {
			t.Errorf("KeysymFor(%q) returned empty", sym)
		}
	}
}

// Non-Latin characters cannot be expressed as a keysym by name; they must be
// reported as unmapped so the caller falls back to the Text (IME) path instead
// of injecting the wrong key.
func TestKeysymForNonLatinIsUnmapped(t *testing.T) {
	for _, s := range []string{"س", "م", "中", "文", "😀", "€"} {
		if got := KeysymFor(s, false); got != "" {
			t.Errorf("KeysymFor(%q) = %q, want empty (must use text path)", s, got)
		}
	}
}

func TestKeysymForUnknownNames(t *testing.T) {
	// "F" alone is the letter f, so it is intentionally not in this list.
	for _, s := range []string{"NotAKey", "Unidentified", "Dead", "", "Fxyz", "F1x"} {
		if got := KeysymFor(s, false); got != "" {
			t.Errorf("KeysymFor(%q) = %q, want empty", s, got)
		}
	}
}

func TestClamp01(t *testing.T) {
	cases := map[float64]float64{
		-5: -0, 0: 0, 0.5: 0.5, 1: 1, 2: 1, 1e9: 1,
	}
	for in, want := range cases {
		if got := clamp01(in); got != want {
			t.Errorf("clamp01(%v) = %v, want %v", in, got, want)
		}
	}
}

func TestModsString(t *testing.T) {
	if got := (Mods{}).String(); got != "" {
		t.Errorf("empty mods rendered as %q", got)
	}
	m := Mods{Shift: true, Ctrl: true, Alt: true, Meta: true}
	if got := m.String(); got == "" || len(got) < 4 {
		t.Errorf("full mods rendered as %q", got)
	}
}
