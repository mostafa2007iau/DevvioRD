package proto

import (
	"bytes"
	"encoding/json"
	"math"
	"testing"
)

// The wire format is the contract between three implementations (Go server,
// Android Java client, browser JS). These tests pin the exact byte layout so an
// accidental field reorder breaks a test instead of a user's session.

func TestTouchEventRoundTrip(t *testing.T) {
	cases := []TouchEvent{
		{Action: TouchDown, PointerID: 0, X: 0, Y: 0, Pressure: 1, Radius: 0, Timestamp: 0},
		{Action: TouchMove, PointerID: 3, X: 0.5, Y: 0.25, Pressure: 0.75, Radius: 12.5, Timestamp: 4242},
		{Action: TouchUp, PointerID: 255, X: 1, Y: 1, Pressure: 0, Radius: 999, Timestamp: math.MaxUint32},
	}
	for _, in := range cases {
		raw := in.Marshal(nil)
		if len(raw) != TouchEventSize {
			t.Fatalf("marshal produced %d bytes, want %d", len(raw), TouchEventSize)
		}
		out, err := UnmarshalTouch(raw)
		if err != nil {
			t.Fatalf("unmarshal: %v", err)
		}
		if out != in {
			t.Errorf("round trip mismatch:\n in=%+v\nout=%+v", in, out)
		}
	}
}

func TestTouchEventLayout(t *testing.T) {
	e := TouchEvent{Action: TouchDown, PointerID: 7, X: 0.5, Y: 0.25,
		Pressure: 0.5, Radius: 2, Timestamp: 0x01020304}
	raw := e.Marshal(make([]byte, TouchEventSize))

	if raw[0] != TouchDown || raw[1] != 7 {
		t.Errorf("action/pointer at wrong offset: % x", raw[:2])
	}
	// Bytes 2:4 are padding and must stay zero so the struct stays aligned.
	if raw[2] != 0 || raw[3] != 0 {
		t.Errorf("padding bytes not zero: % x", raw[2:4])
	}
	want := []byte{0x3F, 0x00, 0x00, 0x00} // float32(0.5) big-endian
	if !bytes.Equal(raw[4:8], want) {
		t.Errorf("X not big-endian float32 at offset 4: % x", raw[4:8])
	}
	if !bytes.Equal(raw[20:24], []byte{1, 2, 3, 4}) {
		t.Errorf("timestamp not big-endian at offset 20: % x", raw[20:24])
	}
	// Marshal must never write past its declared size even with a huge buffer.
	big := make([]byte, 4096)
	if got := e.Marshal(big); len(got) != TouchEventSize {
		t.Errorf("marshal into large buffer returned %d bytes, want %d", len(got), TouchEventSize)
	}
}

func TestTouchEventShortAndOversized(t *testing.T) {
	for n := 0; n < TouchEventSize; n++ {
		if _, err := UnmarshalTouch(make([]byte, n)); err != ErrShortMessage {
			t.Errorf("len %d: err = %v, want ErrShortMessage", n, err)
		}
	}
	// Extra trailing bytes are tolerated (forward compatibility).
	if _, err := UnmarshalTouch(make([]byte, TouchEventSize+16)); err != nil {
		t.Errorf("oversized message rejected: %v", err)
	}
}

func TestScrollEventRoundTrip(t *testing.T) {
	in := ScrollEvent{DX: -1.5, DY: 0.125}
	out, err := UnmarshalScroll(in.Marshal(nil))
	if err != nil {
		t.Fatal(err)
	}
	if out != in {
		t.Errorf("round trip %+v != %+v", out, in)
	}
	if _, err := UnmarshalScroll(make([]byte, 7)); err != ErrShortMessage {
		t.Errorf("short scroll accepted: %v", err)
	}
}

func TestFrameRoundTrip(t *testing.T) {
	jpeg := []byte{0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0xFF, 0xD9}
	buf := EncodeFrame(nil, 42, 1280, 800, 1700000000123, jpeg)
	if len(buf) != FrameHeaderSize+len(jpeg) {
		t.Fatalf("frame length %d, want %d", len(buf), FrameHeaderSize+len(jpeg))
	}
	seq, w, h, ts, gotJPEG, err := ParseFrame(buf)
	if err != nil {
		t.Fatal(err)
	}
	if seq != 42 || w != 1280 || h != 800 || ts != 1700000000123 {
		t.Errorf("header mismatch: seq=%d w=%d h=%d ts=%d", seq, w, h, ts)
	}
	if !bytes.Equal(gotJPEG, jpeg) {
		t.Errorf("payload mismatch: % x", gotJPEG)
	}
}

// A truncated or garbage frame must never panic - the server parses these from
// untrusted sockets.
func TestParseFrameRejectsGarbage(t *testing.T) {
	for n := 0; n < FrameHeaderSize; n++ {
		if _, _, _, _, _, err := ParseFrame(make([]byte, n)); err == nil {
			t.Errorf("len %d: expected error", n)
		}
	}
	if _, _, _, _, _, err := ParseFrame(nil); err == nil {
		t.Error("nil frame accepted")
	}
	// An empty payload is legal (no JPEG bytes yet).
	if _, _, _, _, payload, err := ParseFrame(make([]byte, FrameHeaderSize)); err != nil || len(payload) != 0 {
		t.Errorf("header-only frame: payload=%d err=%v", len(payload), err)
	}
}

func TestEncodeInt64RoundTrip(t *testing.T) {
	for _, v := range []int64{0, 1, -1, math.MaxInt64, math.MinInt64} {
		b := EncodeInt64(TypePing, v)
		if len(b) != 9 || b[0] != TypePing {
			t.Fatalf("bad encoding for %d: % x", v, b)
		}
		got, err := DecodeInt64(b)
		if err != nil || got != v {
			t.Errorf("round trip %d -> %d (err %v)", v, got, err)
		}
	}
	if _, err := DecodeInt64(make([]byte, 8)); err != ErrShortMessage {
		t.Errorf("short int64 accepted: %v", err)
	}
}

func TestEncodeJSONPrefixesType(t *testing.T) {
	b, err := EncodeJSON(TypeClipboard, ClipboardEvent{Kind: "text", Data: "سلام"})
	if err != nil {
		t.Fatal(err)
	}
	if b[0] != TypeClipboard {
		t.Fatalf("first byte = 0x%02x, want 0x%02x", b[0], TypeClipboard)
	}
	var got ClipboardEvent
	if err := json.Unmarshal(b[1:], &got); err != nil {
		t.Fatal(err)
	}
	if got.Data != "سلام" || got.Kind != "text" {
		t.Errorf("unicode payload lost: %+v", got)
	}
}

func TestMessageTypeSpaceIsDisjoint(t *testing.T) {
	// The protocol relies on <0x80 = client->server and >=0x80 = server->client
	// so that a stray mirrored message is detectable.
	client := []byte{TypeHello, TypeTouch, TypeScroll, TypeKey, TypeText,
		TypeClipboard, TypePointerMode, TypePing, TypeQuality}
	server := []byte{TypeHelloOK, TypeFrame, TypeCursor, TypeClip, TypeStatus,
		TypeScreen, TypePong, TypeFileMeta, TypeError}
	seen := map[byte]string{}
	for _, t2 := range client {
		if t2 >= 0x80 {
			t.Errorf("client type 0x%02x is not < 0x80", t2)
		}
		if prev, dup := seen[t2]; dup {
			t.Errorf("type 0x%02x used by both %s and client", t2, prev)
		}
		seen[t2] = "client"
	}
	for _, t2 := range server {
		if t2 < 0x80 {
			t.Errorf("server type 0x%02x is not >= 0x80", t2)
		}
		if prev, dup := seen[t2]; dup {
			t.Errorf("type 0x%02x used by both %s and server", t2, prev)
		}
		seen[t2] = "server"
	}
	for _, t2 := range append(append([]byte{}, client...), server...) {
		if name := TypeName(t2); name == "" || name == "unknown" {
			t.Errorf("type 0x%02x has no name", t2)
		}
	}
}

func TestPointerModes(t *testing.T) {
	if PointerModeTouch != "touch" || PointerModeMouse != "mouse" {
		t.Fatalf("pointer mode constants changed: %q %q", PointerModeTouch, PointerModeMouse)
	}
}
