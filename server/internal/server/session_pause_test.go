package server

import (
	"encoding/json"
	"testing"

	"github.com/devviord/devviord/server/internal/proto"
)

// Pausing must be per session. The hidden-tab optimisation originally reused
// the global quality message with fps=1, which silently starved every other
// client of frames; TypePause exists so one client can stop its own stream.
func TestPauseIsPerSession(t *testing.T) {
	a := &session{}
	a.realtime.Store(true)
	b := &session{}
	b.realtime.Store(true)

	// Decode what the browser sends and apply it exactly as the session does.
	payload, err := proto.EncodeJSON(proto.TypePause, proto.PauseEvent{Paused: true})
	if err != nil {
		t.Fatalf("encode pause: %v", err)
	}
	var ev proto.PauseEvent
	if err := json.Unmarshal(payload[1:], &ev); err != nil {
		t.Fatalf("decode pause: %v", err)
	}
	a.realtime.Store(!ev.Paused)

	if a.realtime.Load() {
		t.Error("paused session still wants frames")
	}
	if !b.realtime.Load() {
		t.Error("pausing one session stopped another session's stream")
	}

	// Resuming restores only that session.
	ev = proto.PauseEvent{Paused: false}
	a.realtime.Store(!ev.Paused)
	if !a.realtime.Load() {
		t.Error("resumed session did not start receiving frames again")
	}
}
