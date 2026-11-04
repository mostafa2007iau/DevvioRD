// Package selfsetup gives DevvioRD a "run the app, get a working server"
// experience: when the binary is executed with no arguments on a machine
// without a display (or with --app), it starts the built-in virtual desktop
// instead of failing.
package selfsetup

import (
	"fmt"
	"os"
	"runtime"
)

// Environment describes what the host offers.
type Environment struct {
	OS           string
	HasDisplay   bool
	Display      string
	HasFFmpeg    bool
	HasInput     bool
	HasClipboard bool
	IsTerminal   bool
}

// Detect probes the current host. Cheap and side-effect free.
func Detect(display string) Environment {
	env := Environment{
		OS:         runtime.GOOS + "/" + runtime.GOARCH,
		Display:    display,
		HasDisplay: display != "",
		IsTerminal: isTerminal(),
	}
	if runtime.GOOS == "linux" {
		if _, err := os.Stat("/tmp/.X11-unix"); err == nil {
			env.HasDisplay = true
			if env.Display == "" {
				env.Display = ":0"
			}
		}
	}
	env.HasFFmpeg = lookPath("ffmpeg")
	env.HasInput = lookPath("xdotool")
	env.HasClipboard = lookPath("xclip") || lookPath("xsel") || lookPath("wl-paste")
	return env
}

// ChooseMode returns "desktop" when a real desktop can be captured and
// "app" (the built-in virtual desktop) otherwise.
func (e Environment) ChooseMode(explicit string) string {
	switch explicit {
	case "desktop", "app":
		return explicit
	}
	if e.HasDisplay && e.HasFFmpeg {
		return "desktop"
	}
	return "app"
}

// Notes returns human readable setup hints for the chosen mode.
func (e Environment) Notes(mode string) []string {
	var notes []string
	if mode == "app" {
		notes = append(notes, "running the built-in virtual desktop (no display found)")
		if !e.HasDisplay {
			notes = append(notes, "to share a real desktop, install a display and ffmpeg, then restart")
		} else if !e.HasFFmpeg {
			notes = append(notes, "install ffmpeg to capture the real desktop (apt install ffmpeg)")
		}
	} else {
		if !e.HasInput {
			notes = append(notes, "install xdotool to control the desktop (apt install xdotool)")
		}
		if !e.HasClipboard {
			notes = append(notes, "install xclip to sync the desktop clipboard (apt install xclip)")
		}
	}
	return notes
}

// DefaultFilesDir picks a sensible transfer folder for the platform.
func DefaultFilesDir() string {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		home = "."
	}
	return fmt.Sprintf("%s%cDevvioRD%cDownloads", home, os.PathSeparator, os.PathSeparator)
}

func isTerminal() bool {
	fi, err := os.Stdout.Stat()
	if err != nil {
		return false
	}
	return fi.Mode()&os.ModeCharDevice != 0
}

func lookPath(name string) bool {
	for _, dir := range []string{"/usr/bin", "/usr/local/bin", "/bin", "/opt/homebrew/bin"} {
		if _, err := os.Stat(dir + "/" + name); err == nil {
			return true
		}
	}
	return false
}
