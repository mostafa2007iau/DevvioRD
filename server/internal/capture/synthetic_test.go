package capture

import (
	"strings"
	"testing"
)

// The taskbar is laid out, not hard-coded: no element may ever overlap another.
// This guards the bug where the mode chip was drawn at a fixed x and collided
// with the "DevvioRD" brand label on the 1280x800 default surface.
func TestTaskbarLayoutNeverOverlaps(t *testing.T) {
	stat := "1280 x 800   20 fps   q70   taps=0"
	for _, width := range []int{640, 800, 1024, 1280, 1440, 1920, 2560, 3840} {
		for _, mode := range []string{"touch", "mouse"} {
			scale := float64(width) / 1280.0
			px := func(v float64) int { return int(v*scale + 0.5) }
			fs := px(2)
			if fs < 1 {
				fs = 1
			}
			l := layoutTaskbar(width, px, fs, mode, stat)

			brandEnd := l.brandX + runeWidth(l.brand, fs+1)
			if l.chipX < brandEnd {
				t.Errorf("width %d/%s: chip starts at %d, brand ends at %d (overlap)",
					width, mode, l.chipX, brandEnd)
			}
			if l.label != "TOUCH" && l.label != "MOUSE" {
				t.Errorf("width %d/%s: unexpected label %q", width, mode, l.label)
			}
			if chipEnd := l.chipX + l.chipW; chipEnd > width {
				t.Errorf("width %d/%s: chip ends at %d beyond surface", width, mode, chipEnd)
			}
			if l.showStat && l.statX < l.chipX+l.chipW {
				t.Errorf("width %d/%s: stats start inside the chip", width, mode)
			}
			if l.showStat && l.statX+runeWidth(l.stat, fs) >= l.clockX {
				t.Errorf("width %d/%s: stats would overlap the clock", width, mode)
			}
			if l.clockX+runeWidth(l.clock, l.clockScale) > width {
				t.Errorf("width %d/%s: clock overflows the surface (%d > %d)",
					width, mode, l.clockX+runeWidth(l.clock, l.clockScale), width)
			}
			if l.clockX < px(16) {
				t.Errorf("width %d/%s: clock pushed off the left edge (%d)", width, mode, l.clockX)
			}
			if len(l.clock) != 5 {
				t.Errorf("width %d/%s: clock %q is clipped", width, mode, l.clock)
			}
		}
	}
}

func TestNonLatinDisplayIsReadable(t *testing.T) {
	// Persian input must produce a human-readable on-screen summary, not "????".
	fa := []rune("سلام دوست عزیز")
	if !hasNonLatin(fa) {
		t.Fatal("Persian text not detected as non-Latin")
	}
	line := describeNonLatin(fa)
	if !strings.Contains(line, "ar/fa") {
		t.Errorf("script tag missing: %q", line)
	}
	// 12 Persian letters + 2 spaces that separate them; the count reports the
	// non-space code points.
	if !strings.Contains(line, "chars") {
		t.Errorf("character count missing: %q", line)
	}
	if strings.Contains(line, "?") {
		t.Errorf("placeholder leaked into the display line: %q", line)
	}
	// A recognisable romanisation must appear.
	if !strings.Contains(line, "s") || !strings.Contains(line, "l") {
		t.Errorf("no romanisation produced: %q", line)
	}
	// Pure ASCII must not trigger the fallback line at all.
	if hasNonLatin([]rune("hello world")) {
		t.Error("ASCII text misdetected as non-Latin")
	}
	if got := describeNonLatin([]rune("abc")); got != "" {
		t.Errorf("describeNonLatin(ASCII) = %q, want empty", got)
	}
	// Emoji and CJK get their own tags.
	for in, want := range map[string]string{
		"中文字":    "cjk",
		"😀🙂":     "emoji",
		"שלום":   "he",
		"Привет": "cy",
	} {
		if got := describeNonLatin([]rune(in)); !strings.Contains(got, want) {
			t.Errorf("describeNonLatin(%q) = %q, want tag %q", in, got, want)
		}
	}
}

func TestDescribeNonLatinIsBounded(t *testing.T) {
	// A huge paste must not produce a line that runs off the pane.
	long := make([]rune, 5000)
	for i := range long {
		long[i] = 'س'
	}
	line := describeNonLatin(long)
	if len(line) > 120 {
		t.Errorf("display line too long: %d bytes", len(line))
	}
	if !strings.Contains(line, "5000 chars") {
		t.Errorf("count wrong: %q", line[:40])
	}
}

func TestRomanizeRunesKeepsAscii(t *testing.T) {
	// ASCII must survive untouched, including punctuation and digits.
	in := []rune("DevvioRD 1.0 / ok")
	if got := string(romanizeRunes(in)); got != string(in) {
		t.Errorf("ASCII was modified: %q", got)
	}
	// Persian becomes Latin, and nothing unreadable survives.
	got := string(romanizeRunes([]rune("سلام")))
	for _, r := range got {
		if r > 126 {
			t.Fatalf("romanisation left a non-ASCII rune: %q", got)
		}
	}
	if got != "slam" {
		t.Errorf("romanizeRunes(سلام) = %q, want \"slam\"", got)
	}
}

func TestEventLogShowsNewestEntries(t *testing.T) {
	c, err := New("synthetic", Options{Quality: 72, FPS: 20, Scale: 1})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	b := c.(*syntheticBackend)
	// The backend logs its own startup lines ("session started", "backend: ...").
	b.mu.Lock()
	b.log = nil
	b.mu.Unlock()
	for _, msg := range []string{"first", "second", "third", "fourth", "fifth"} {
		b.addLog(msg)
	}
	if len(b.log) != 5 {
		t.Fatalf("log length = %d, want 5", len(b.log))
	}
	// The tail must always contain the most recent entry.
	if !strings.Contains(b.log[len(b.log)-1], "fifth") {
		t.Errorf("newest event not last: %v", b.log)
	}
	// The log is bounded so the pane can never grow without limit.
	for i := 0; i < 50; i++ {
		b.addLog("noise")
	}
	if len(b.log) > 9 {
		t.Errorf("log grew unbounded: %d entries", len(b.log))
	}
	if !strings.Contains(b.log[len(b.log)-1], "noise") {
		t.Errorf("tail lost the newest entry: %v", b.log)
	}
}
