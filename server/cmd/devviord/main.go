// Command devviord runs the DevvioRD remote desktop host.
//
// Run it with no arguments for a guided first start: the server picks sensible
// backends for this machine (real desktop if one is available, otherwise the
// built-in virtual desktop), generates an access token when none is given and
// prints a pairing QR code for the Android app.
package main

import (
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"runtime"
	"strconv"
	"syscall"

	"github.com/devviord/devviord/server/internal/capture"
	"github.com/devviord/devviord/server/internal/selfsetup"
	"github.com/devviord/devviord/server/internal/server"
)

// version is overridden with -ldflags at build time.
var version = "1.0.0"

func main() {
	var (
		addr     = flag.String("addr", ":5900", "listen address (host:port)")
		token    = flag.String("token", "", "shared access token (empty = auto-generate)")
		noToken  = flag.Bool("no-auth", false, "disable authentication (trusted networks only)")
		mode     = flag.String("mode", "auto", "session mode: auto | desktop | app")
		backend  = flag.String("capture", "", "capture backend: auto|ffmpeg|synthetic (default: from --mode)")
		inputB   = flag.String("input", "", "input backend: auto|x11|synthetic (default: from --mode)")
		display  = flag.String("display", "", "X11 display (default $DISPLAY or :0)")
		quality  = flag.Int("quality", 70, "JPEG quality (20-95)")
		fps      = flag.Int("fps", 20, "target frames per second (1-60)")
		scale    = flag.Float64("scale", 1.0, "scale factor (0.25-1.0)")
		filesDir = flag.String("files", "", "directory exposed for file transfer")
		maxCl    = flag.Int("max-clients", 4, "maximum simultaneous clients")
		verbose  = flag.Bool("v", false, "verbose logging")
		pairPub  = flag.Bool("pair-public", false, "include the token in the pairing QR (trusted networks only)")
		port     = flag.Int("port", 0, "shorthand for --addr :PORT")
		list     = flag.Bool("list-backends", false, "list capture backends and exit")
		showVer  = flag.Bool("version", false, "print version and exit")
		genToken = flag.Bool("gen-token", false, "generate a random access token and exit")
	)
	flag.Parse()

	if *showVer {
		fmt.Printf("devviord %s (%s/%s, %s)\n", version, runtime.GOOS, runtime.GOARCH, runtime.Version())
		return
	}
	if *list {
		fmt.Println("capture backends :", capture.Names())
		fmt.Println("default backend  :", capture.DefaultBackend())
		fmt.Println("ffmpeg available :", capture.FFmpegAvailable())
		env := selfsetup.Detect(os.Getenv("DISPLAY"))
		fmt.Println("display detected :", env.HasDisplay, env.Display)
		fmt.Println("xdotool found    :", env.HasInput)
		fmt.Println("clipboard tool   :", env.HasClipboard)
		fmt.Println("suggested mode   :", env.ChooseMode(*mode))
		return
	}
	if *genToken {
		fmt.Println(server.GenerateToken())
		return
	}

	// --- resolve the session mode (real desktop vs built-in virtual desktop) ---
	disp := *display
	if disp == "" {
		disp = os.Getenv("DISPLAY")
	}
	env := selfsetup.Detect(disp)
	sessionMode := env.ChooseMode(*mode)

	capBackend := *backend
	if capBackend == "" {
		if sessionMode == "desktop" {
			capBackend = "ffmpeg"
		} else {
			capBackend = "synthetic"
		}
	}
	inBackend := *inputB
	if inBackend == "" {
		if sessionMode == "desktop" {
			inBackend = "auto"
		} else {
			inBackend = "synthetic"
		}
	}

	// --- access token ---
	authToken := *token
	if authToken == "" {
		authToken = os.Getenv("DEVVIO_TOKEN")
	}
	generated := false
	if authToken == "" && !*noToken {
		authToken = server.GenerateToken()
		generated = true
	}

	listenAddr := *addr
	if *port > 0 {
		listenAddr = ":" + strconv.Itoa(*port)
	}
	if env := os.Getenv("DEVVIO_ADDR"); env != "" && listenAddr == ":5900" && *port == 0 {
		listenAddr = env
	}

	files := *filesDir
	if files == "" {
		files = selfsetup.DefaultFilesDir()
	}

	cfg := server.Config{
		Addr:           listenAddr,
		Token:          authToken,
		CaptureBackend: capBackend,
		InputBackend:   inBackend,
		Display:        disp,
		Quality:        *quality,
		FPS:            *fps,
		Scale:          *scale,
		FilesDir:       files,
		MaxClients:     *maxCl,
		Verbose:        *verbose,
		PairPublic:     *pairPub,
		Mode:           sessionMode,
		Version:        version,
	}

	s, err := server.New(cfg)
	if err != nil {
		log.Fatalf("devviord: %v", err)
	}
	if generated {
		cfg.Token = authToken
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sig
		fmt.Println("\nshutting down...")
		s.Shutdown()
		os.Exit(0)
	}()

	if err := s.Start(); err != nil {
		if ne, ok := err.(net.Error); ok && ne.Timeout() {
			log.Fatalf("devviord: listen timeout on %s", listenAddr)
		}
		if err.Error() == "http: Server closed" {
			return
		}
		log.Fatalf("devviord: %v", err)
	}
}
