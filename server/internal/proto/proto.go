// Package proto defines the binary wire protocol shared by the DevvioRD
// server and its clients (Android app, web UI).
//
// Design goals:
//   - Zero-copy friendly framing (1 byte type + tiny fixed header)
//   - Video path stays purely binary (no JSON per frame)
//   - Control path uses small JSON payloads for readability / forward compat
package proto

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"math"
)

// Protocol version. Clients must send the same major version in Hello.
const (
	VersionMajor = 1
	VersionMinor = 0
)

// Message types. Client -> server are < 0x80, server -> client are >= 0x80.
const (
	// --- client -> server ---
	TypeHello       byte = 0x01 // JSON HelloMsg
	TypeTouch       byte = 0x10 // fixed TouchEvent
	TypeScroll      byte = 0x11 // fixed ScrollEvent
	TypeKey         byte = 0x14 // JSON KeyEvent
	TypeText        byte = 0x15 // JSON TextEvent
	TypeClipboard   byte = 0x20 // JSON ClipboardEvent
	TypePointerMode byte = 0x21 // JSON PointerModeEvent
	TypePing        byte = 0x40 // fixed int64 nanos
	TypeQuality     byte = 0x41 // JSON QualityEvent

	// --- server -> client ---
	TypeHelloOK  byte = 0x81 // JSON HelloOKMsg
	TypeFrame    byte = 0x90 // binary frame
	TypeCursor   byte = 0x92 // JSON CursorMsg
	TypeClip     byte = 0xA0 // JSON ClipboardMsg
	TypeStatus   byte = 0xB0 // JSON StatusMsg
	TypeScreen   byte = 0xC0 // JSON ScreenMsg
	TypePong     byte = 0xC1 // fixed int64 nanos (echo)
	TypeFileMeta byte = 0xD0 // JSON FileMetaMsg (informational)
	TypeError    byte = 0xEE // JSON StatusMsg
)

// Touch actions (mirrors Android MotionEvent-ish semantics, simplified).
const (
	TouchDown   byte = 0
	TouchMove   byte = 1
	TouchUp     byte = 2
	TouchCancel byte = 3
)

// Pointer modes for the remote session.
const (
	PointerModeTouch = "touch" // direct absolute tap/drag
	PointerModeMouse = "mouse" // virtual mouse cursor (relative/trackpad)
)

// FrameHeaderSize is the size of the fixed header preceding JPEG bytes.
const FrameHeaderSize = 1 + 4 + 2 + 2 + 8

// TouchEventSize is the fixed on-wire size of a touch event.
const TouchEventSize = 1 + 1 + 2 + 4 + 4 + 4 + 4 + 4

var (
	ErrShortMessage = errors.New("proto: short message")
	ErrBadType      = errors.New("proto: unknown message type")
)

// HelloMsg is the first message a client sends.
type HelloMsg struct {
	Version int    `json:"version"`
	Client  string `json:"client"`           // e.g. "android/1.0.0"
	Device  string `json:"device,omitempty"` // stable device id
	Caps    Caps   `json:"caps"`
}

// Caps describes optional client features.
type Caps struct {
	Touch       bool `json:"touch"`
	Mouse       bool `json:"mouse"`
	Clipboard   bool `json:"clipboard"`
	Files       bool `json:"files"`
	MultiTouch  bool `json:"multitouch"`
	Keyboard    bool `json:"keyboard"`
	MaxWidth    int  `json:"maxWidth,omitempty"`
	MaxHeight   int  `json:"maxHeight,omitempty"`
	JPEGQuality int  `json:"jpegQuality,omitempty"`
}

// HelloOKMsg is sent by the server after a successful handshake.
type HelloOKMsg struct {
	Version     int         `json:"version"`
	Server      string      `json:"server"`
	Hostname    string      `json:"hostname"`
	OS          string      `json:"os"`
	SessionID   string      `json:"sessionId"`
	Width       int         `json:"width"`
	Height      int         `json:"height"`
	Scale       float64     `json:"scale"`
	FPS         int         `json:"fps"`
	Quality     int         `json:"quality"`
	PointerMode string      `json:"pointerMode"`
	Backend     BackendInfo `json:"backend"`
	NeedsAuth   bool        `json:"needsAuth"`
}

// BackendInfo describes the active capture / input / clipboard backends.
type BackendInfo struct {
	Capture   string   `json:"capture"`
	Input     string   `json:"input"`
	Clipboard string   `json:"clipboard"`
	Display   string   `json:"display,omitempty"`
	Warnings  []string `json:"warnings,omitempty"`
}

// TouchEvent is a fixed-size touch sample. Coordinates are normalized 0..1.
type TouchEvent struct {
	Action    byte
	PointerID byte
	X         float32 // 0..1
	Y         float32 // 0..1
	Pressure  float32 // 0..1
	Radius    float32
	Timestamp uint32 // client millis, informational
}

// Marshal encodes the touch event into dst (must be >= TouchEventSize).
func (t *TouchEvent) Marshal(dst []byte) []byte {
	if len(dst) < TouchEventSize {
		dst = make([]byte, TouchEventSize)
	}
	dst[0] = t.Action
	dst[1] = t.PointerID
	binary.BigEndian.PutUint16(dst[2:4], 0)
	binary.BigEndian.PutUint32(dst[4:8], math.Float32bits(t.X))
	binary.BigEndian.PutUint32(dst[8:12], math.Float32bits(t.Y))
	binary.BigEndian.PutUint32(dst[12:16], math.Float32bits(t.Pressure))
	binary.BigEndian.PutUint32(dst[16:20], math.Float32bits(t.Radius))
	binary.BigEndian.PutUint32(dst[20:24], t.Timestamp)
	return dst[:TouchEventSize]
}

// UnmarshalTouch decodes a fixed-size touch payload.
func UnmarshalTouch(b []byte) (TouchEvent, error) {
	var t TouchEvent
	if len(b) < TouchEventSize {
		return t, ErrShortMessage
	}
	t.Action = b[0]
	t.PointerID = b[1]
	t.X = math.Float32frombits(binary.BigEndian.Uint32(b[4:8]))
	t.Y = math.Float32frombits(binary.BigEndian.Uint32(b[8:12]))
	t.Pressure = math.Float32frombits(binary.BigEndian.Uint32(b[12:16]))
	t.Radius = math.Float32frombits(binary.BigEndian.Uint32(b[16:20]))
	t.Timestamp = binary.BigEndian.Uint32(b[20:24])
	return t, nil
}

// ScrollEvent is a scroll gesture delta (normalized units, ~1.0 == one page).
type ScrollEvent struct {
	DX float32
	DY float32
}

// Marshal encodes the scroll event.
func (s *ScrollEvent) Marshal(dst []byte) []byte {
	if len(dst) < 8 {
		dst = make([]byte, 8)
	}
	binary.BigEndian.PutUint32(dst[0:4], math.Float32bits(s.DX))
	binary.BigEndian.PutUint32(dst[4:8], math.Float32bits(s.DY))
	return dst[:8]
}

// UnmarshalScroll decodes a scroll payload.
func UnmarshalScroll(b []byte) (ScrollEvent, error) {
	var s ScrollEvent
	if len(b) < 8 {
		return s, ErrShortMessage
	}
	s.DX = math.Float32frombits(binary.BigEndian.Uint32(b[0:4]))
	s.DY = math.Float32frombits(binary.BigEndian.Uint32(b[4:8]))
	return s, nil
}

// KeyEvent carries a keyboard event. Key uses W3C `KeyboardEvent.key` values
// ("a", "Enter", "Backspace", "ArrowLeft", ...) so that both the Android IME
// and the web UI can produce identical semantics.
type KeyEvent struct {
	Key    string `json:"key"`
	Code   string `json:"code,omitempty"` // W3C code, e.g. "KeyA"
	Action string `json:"action"`         // "down" | "up" | "press"
	Mods   Mods   `json:"mods"`
	Repeat bool   `json:"repeat,omitempty"`
}

// Mods holds modifier state.
type Mods struct {
	Ctrl  bool `json:"ctrl,omitempty"`
	Alt   bool `json:"alt,omitempty"`
	Shift bool `json:"shift,omitempty"`
	Meta  bool `json:"meta,omitempty"`
}

// TextEvent carries committed text (IME composition result). This is the path
// used for non-ASCII input (Persian, CJK, emoji) where per-key mapping fails.
type TextEvent struct {
	Text string `json:"text"`
}

// ClipboardEvent is a client-side clipboard update pushed to the server.
type ClipboardEvent struct {
	Kind string `json:"kind"` // "text"
	Data string `json:"data"`
}

// PointerModeEvent switches between touch and mouse modes at runtime.
type PointerModeEvent struct {
	Mode string `json:"mode"` // "touch" | "mouse"
}

// QualityEvent lets clients request new quality settings.
type QualityEvent struct {
	Quality int     `json:"quality,omitempty"` // 20..90 JPEG quality
	FPS     int     `json:"fps,omitempty"`     // 1..60
	Scale   float64 `json:"scale,omitempty"`   // 0.25..1.0
}

// CursorMsg reports the server cursor position (normalized) in mouse mode.
type CursorMsg struct {
	X       float32 `json:"x"`
	Y       float32 `json:"y"`
	Visible bool    `json:"visible"`
}

// ClipboardMsg is a server-side clipboard update pushed to clients.
type ClipboardMsg struct {
	Kind string `json:"kind"`
	Data string `json:"data"`
}

// StatusMsg is a human readable server notice.
type StatusMsg struct {
	Level   string `json:"level"` // "info" | "warn" | "error"
	Message string `json:"message"`
	Code    string `json:"code,omitempty"`
}

// ScreenMsg notifies clients that the session geometry changed.
type ScreenMsg struct {
	Width  int     `json:"width"`
	Height int     `json:"height"`
	Scale  float64 `json:"scale"`
}

// FileMetaMsg announces a server -> client file transfer over WS (small files).
type FileMetaMsg struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Size  int64  `json:"size"`
	Error string `json:"error,omitempty"`
}

// EncodeFrame builds a complete frame message.
func EncodeFrame(dst []byte, seq uint32, width, height uint16, tsMillis int64, jpeg []byte) []byte {
	need := FrameHeaderSize + len(jpeg)
	if cap(dst) < need {
		dst = make([]byte, need)
	}
	dst = dst[:need]
	dst[0] = TypeFrame
	binary.BigEndian.PutUint32(dst[1:5], seq)
	binary.BigEndian.PutUint16(dst[5:7], width)
	binary.BigEndian.PutUint16(dst[7:9], height)
	binary.BigEndian.PutUint64(dst[9:17], uint64(tsMillis))
	copy(dst[FrameHeaderSize:], jpeg)
	return dst
}

// ParseFrame decodes a frame header.
func ParseFrame(b []byte) (seq uint32, width, height uint16, ts int64, jpeg []byte, err error) {
	if len(b) < FrameHeaderSize {
		return 0, 0, 0, 0, nil, ErrShortMessage
	}
	seq = binary.BigEndian.Uint32(b[1:5])
	width = binary.BigEndian.Uint16(b[5:7])
	height = binary.BigEndian.Uint16(b[7:9])
	ts = int64(binary.BigEndian.Uint64(b[9:17]))
	jpeg = b[FrameHeaderSize:]
	return
}

// EncodeJSON builds a message of the given type with a JSON body.
func EncodeJSON(t byte, v any) ([]byte, error) {
	body, err := json.Marshal(v)
	if err != nil {
		return nil, fmt.Errorf("proto: marshal: %w", err)
	}
	out := make([]byte, 1+len(body))
	out[0] = t
	copy(out[1:], body)
	return out, nil
}

// EncodeInt64 builds a complete ping/pong message: the type byte followed by a
// big-endian int64. Clients (Android, browser) put the value at offset 1, so the
// decoder must read from there too.
func EncodeInt64(t byte, v int64) []byte {
	out := make([]byte, 1+8)
	out[0] = t
	binary.BigEndian.PutUint64(out[1:], uint64(v))
	return out
}

// DecodeInt64 decodes a complete int64 message (type byte + 8 value bytes).
func DecodeInt64(b []byte) (int64, error) {
	if len(b) < 1+8 {
		return 0, ErrShortMessage
	}
	return int64(binary.BigEndian.Uint64(b[1:9])), nil
}

// TypeName is used for logging.
func TypeName(t byte) string {
	switch t {
	case TypeHello:
		return "hello"
	case TypeTouch:
		return "touch"
	case TypeScroll:
		return "scroll"
	case TypeKey:
		return "key"
	case TypeText:
		return "text"
	case TypeClipboard:
		return "clipboard"
	case TypePointerMode:
		return "pointer-mode"
	case TypePing:
		return "ping"
	case TypeQuality:
		return "quality"
	case TypeHelloOK:
		return "hello-ok"
	case TypeFrame:
		return "frame"
	case TypeCursor:
		return "cursor"
	case TypeClip:
		return "clip"
	case TypeStatus:
		return "status"
	case TypeScreen:
		return "screen"
	case TypePong:
		return "pong"
	case TypeError:
		return "error"
	}
	return fmt.Sprintf("unknown(0x%02x)", t)
}
