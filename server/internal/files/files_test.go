package files

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// SafeJoin is the single choke point that keeps a hostile client from reading or
// writing outside the shared directory, so it gets exhaustive coverage.

func TestSafeJoinAllowsLegitPaths(t *testing.T) {
	base := t.TempDir()
	cases := map[string]string{
		"":                     base,
		"/":                    base,
		".":                    base,
		"note.txt":             filepath.Join(base, "note.txt"),
		"docs/report.txt":      filepath.Join(base, "docs", "report.txt"),
		"./docs//sub/b.txt":    filepath.Join(base, "docs", "sub", "b.txt"),
		"a b/c d.txt":          filepath.Join(base, "a b/c d.txt"),
		"گزارش نهایی ۱۴۰۳.txt": filepath.Join(base, "گزارش نهایی ۱۴۰۳.txt"),
		"dir/":                 filepath.Join(base, "dir"),
	}
	for in, want := range cases {
		got, err := SafeJoin(base, in)
		if err != nil {
			t.Errorf("SafeJoin(%q) rejected a legal path: %v", in, err)
			continue
		}
		// On case-insensitive filesystems compare with a normalised form.
		if filepath.Clean(got) != filepath.Clean(want) {
			t.Errorf("SafeJoin(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestSafeJoinBlocksTraversal(t *testing.T) {
	base := t.TempDir()
	bad := []string{
		"..",
		"../",
		"../etc/passwd",
		"../../etc/passwd",
		"docs/../../etc/passwd",
		"docs/../../../",
		"a/../../b",
		`..\..\windows\system32`,
		`docs\..\..\secret`,
		"//etc/passwd/../../..",
		".../../../x",
		"..%2fetc%2fpasswd", // literal, not URL-decoded: must stay inside as a file name
	}
	for _, in := range bad {
		got, err := SafeJoin(base, in)
		if err == nil {
			// The only acceptable non-error is a path still inside base.
			absBase, _ := filepath.Abs(base)
			absGot, _ := filepath.Abs(got)
			if !strings.HasPrefix(absGot+string(os.PathSeparator), absBase+string(os.PathSeparator)) {
				t.Errorf("SafeJoin(%q) escaped the root: %q", in, got)
			}
			continue
		}
	}
}

func TestSafeJoinNeverEscapesForOddInput(t *testing.T) {
	base := t.TempDir()
	inputs := []string{
		strings.Repeat("../", 50) + "etc/passwd",
		"./././../x",
		"a//b///c",
		"/absolute/attempt",
		"~/home/user",
		"\x00null",
		"very/" + strings.Repeat("deep/", 100) + "file.txt",
	}
	for _, in := range inputs {
		got, err := SafeJoin(base, in)
		if err != nil {
			continue
		}
		absBase, _ := filepath.Abs(base)
		absGot, _ := filepath.Abs(got)
		if !strings.HasPrefix(absGot+string(os.PathSeparator), absBase+string(os.PathSeparator)) {
			t.Errorf("SafeJoin(%q) escaped: %q", in, got)
		}
	}
}

func TestSafeJoinIsAbsoluteInputSafe(t *testing.T) {
	base := t.TempDir()
	// An absolute-looking request must be treated as relative to the root, never
	// as a real filesystem path.
	got, err := SafeJoin(base, "/etc/passwd")
	if err != nil {
		return
	}
	if got == "/etc/passwd" || strings.HasPrefix(got, "/etc") {
		t.Fatalf("absolute input leaked: %q", got)
	}
}

func TestSanitizeNameStripsDirectories(t *testing.T) {
	cases := map[string]string{
		"../../evil.txt":  "evil.txt",
		"/etc/passwd":     "passwd",
		`C:\tmp\evil.txt`: "evil.txt",
		`..\..\evil.txt`:  "evil.txt",
		"good name.txt":   "good name.txt",
		"..":              "", // placeholder name, checked below
		".":               "",
		"":                "",
		"a/b/c/deep.txt":  "deep.txt",
	}
	for in, want := range cases {
		got := sanitizeName(in)
		if want == "" {
			// Degenerate names fall back to a generated placeholder; the only
			// requirement is that it is a plain name with no path characters.
			if got == "" || strings.ContainsAny(got, `/\`) {
				t.Errorf("sanitizeName(%q) = %q, want a safe placeholder", in, got)
			}
			continue
		}
		if got != want {
			t.Errorf("sanitizeName(%q) = %q, want %q", in, got, want)
		}
	}
	// Control characters must not survive into a file name.
	if got := sanitizeName("bad\x00name\x1b.txt"); strings.ContainsAny(got, "\x00\x1b") {
		t.Errorf("control characters survived: %q", got)
	}
	// Unicode names are legitimate and must be preserved.
	if got := sanitizeName("گزارش.txt"); got != "گزارش.txt" {
		t.Errorf("unicode name mangled: %q", got)
	}
}

func TestContentType(t *testing.T) {
	cases := map[string]string{
		"a.png": "image/png",
		"a.jpg": "image/jpeg",
		"a.txt": "text/plain; charset=utf-8",
		"a.bin": "application/octet-stream",
		"noext": "application/octet-stream",
	}
	for in, want := range cases {
		if got := ContentType(in); !strings.HasPrefix(got, strings.Split(want, ";")[0]) {
			t.Errorf("ContentType(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestUniquePathDoesNotClobber(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "file.txt")
	if err := os.WriteFile(p, []byte("first"), 0o600); err != nil {
		t.Fatal(err)
	}
	next := uniquePath(p)
	if next == p {
		t.Fatalf("uniquePath returned the existing path")
	}
	if filepath.Dir(next) != dir {
		t.Errorf("uniquePath escaped the directory: %q", next)
	}
	if err := os.WriteFile(next, []byte("second"), 0o600); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(p); string(b) != "first" {
		t.Errorf("original file was overwritten: %q", b)
	}
}
