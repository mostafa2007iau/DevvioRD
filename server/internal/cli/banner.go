// Package cli renders the startup pairing screen printed by devviord: the
// addresses a phone can use, a scannable QR code of the pairing URL and a short
// usage hint. Keeping it in its own package makes it testable.
package cli

import (
	"fmt"
	"net"
	"os"
	"sort"
	"strings"
)

// Info is everything the banner needs.
type Info struct {
	Version    string
	Hostname   string
	OS         string
	Addrs      []string // LAN addresses (http://ip:port)
	Port       int
	Token      string
	AuthOn     bool
	Capture    string
	Input      string
	Clipboard  string
	FilesDir   string
	Quality    int
	FPS        int
	Warnings   []string
	SessionID  string
	ServerMode string // "desktop" or "app"
}

// PairURL is the deep link the Android app opens when scanning the QR.
func (i Info) PairURL() string {
	host := "localhost"
	if len(i.Addrs) > 0 {
		host = strings.TrimPrefix(strings.TrimPrefix(i.Addrs[0], "http://"), "https://")
	}
	q := "devviord://connect?host=" + host
	if i.Token != "" {
		q += "&token=" + i.Token
	}
	return q
}

// LanAddrs returns the host's non-loopback IPv4 addresses sorted by usefulness
// (private LAN ranges first).
func LanAddrs() []net.IP {
	var out []net.IP
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return out
	}
	for _, a := range addrs {
		ipn, ok := a.(*net.IPNet)
		if !ok || ipn.IP.IsLoopback() || ipn.IP.To4() == nil || ipn.IP.IsLinkLocalUnicast() {
			continue
		}
		out = append(out, ipn.IP.To4())
	}
	sort.Slice(out, func(a, b int) bool {
		pa, pb := isPrivate(out[a]), isPrivate(out[b])
		if pa != pb {
			return pa
		}
		return out[a].String() < out[b].String()
	})
	return out
}

func isPrivate(ip net.IP) bool {
	return ip[0] == 192 && ip[1] == 168 ||
		ip[0] == 10 ||
		ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31
}

// Banner renders the startup screen.
func Banner(i Info) string {
	var b strings.Builder
	line := func(s string) { b.WriteString(s + "\n") }
	line("")
	line("  ╔══════════════════════════════════════════════════════════════╗")
	line(fmt.Sprintf("  ║  DevvioRD  v%-10s Remote Desktop Server                ║", i.Version))
	line("  ╚══════════════════════════════════════════════════════════════╝")
	line("")
	line(fmt.Sprintf("  Host      : %s (%s)", i.Hostname, i.OS))
	line(fmt.Sprintf("  Backends  : capture=%s  input=%s  clipboard=%s", i.Capture, i.Input, i.Clipboard))
	line(fmt.Sprintf("  Streaming : %d fps, JPEG q%d", i.FPS, i.Quality))
	line(fmt.Sprintf("  Files     : %s", i.FilesDir))
	if i.AuthOn {
		line(fmt.Sprintf("  Token     : %s", i.Token))
	} else {
		line("  Token     : (none — set --token to require authentication)")
	}
	line("")
	line("  Connect from the DevvioRD Android app or a browser:")
	for _, a := range i.Addrs {
		line("    " + a)
	}
	line(fmt.Sprintf("    http://localhost:%d   (this machine)", i.Port))
	line("")
	q := QR(i.PairURL())
	if len(q) > 0 {
		line("  Scan to pair:")
		for _, l := range q {
			line("    " + l)
		}
	}
	for _, w := range i.Warnings {
		line("  ! " + w)
	}
	line("")
	return b.String()
}

// Print writes the banner to stdout.
func Print(i Info) { fmt.Print(Banner(i)) }

// detectTerminalWidth is kept simple: QR is fixed at 33 modules which fits any
// terminal >= 40 columns.
var _ = os.Stdout

// QRMatrix renders text as a QR code scaled into an SVG path grid (used by the
// web UI pairing page). Returns the module matrix.
func QRMatrix(text string) [][]bool {
	q, err := encodeQR(text)
	if err != nil {
		return nil
	}
	return q.modules
}
