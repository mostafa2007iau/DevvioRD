package cli

import "testing"

func TestQRRender(t *testing.T) {
	lines := QR("devviord://connect?host=192.168.1.10:5900&token=abc123")
	if len(lines) == 0 {
		t.Fatal("no qr produced")
	}
	for _, l := range lines {
		t.Logf("%q", l)
	}
}
