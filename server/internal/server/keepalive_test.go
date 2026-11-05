package server

import "testing"

// The server sends WebSocket-level ping frames every ~30 s. Browser clients
// answer them automatically, which resets the 90 s read deadline; the Android
// client used to answer only application PINGs, so an idle phone session was
// dropped after 90 s. Guard the two sides against drift.
func TestPingTickerIsShorterThanReadDeadline(t *testing.T) {
	if pingInterval >= readDeadline {
		t.Fatalf("ping interval %s must be shorter than read deadline %s", pingInterval, readDeadline)
	}
	if readDeadline < 2*pingInterval {
		t.Fatalf("read deadline %s leaves no room for one missed ping (%s)", readDeadline, pingInterval)
	}
}
