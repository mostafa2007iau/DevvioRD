package selfsetup

import "testing"

func TestChooseMode(t *testing.T) {
	cases := []struct {
		name     string
		env      Environment
		explicit string
		want     string
	}{
		{"explicit app wins", Environment{HasDisplay: true, HasFFmpeg: true}, "app", "app"},
		{"explicit desktop wins", Environment{}, "desktop", "desktop"},
		{"headless falls back to app", Environment{HasDisplay: false, HasFFmpeg: true}, "", "app"},
		{"no ffmpeg falls back to app", Environment{HasDisplay: true, HasFFmpeg: false}, "", "app"},
		{"full desktop", Environment{HasDisplay: true, HasFFmpeg: true}, "", "desktop"},
	}
	for _, c := range cases {
		if got := c.env.ChooseMode(c.explicit); got != c.want {
			t.Errorf("%s: got %q want %q", c.name, got, c.want)
		}
	}
}

func TestNotesMentionMissingTools(t *testing.T) {
	e := Environment{HasDisplay: true, HasFFmpeg: true, HasInput: false, HasClipboard: false}
	notes := e.Notes("desktop")
	if len(notes) != 2 {
		t.Fatalf("expected 2 notes, got %v", notes)
	}
}

func TestDefaultFilesDirIsInsideHome(t *testing.T) {
	dir := DefaultFilesDir()
	if dir == "" || dir == "DevvioRD" {
		t.Fatalf("unexpected dir %q", dir)
	}
}
