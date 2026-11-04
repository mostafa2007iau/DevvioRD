package capture

import (
	"testing"

	"github.com/devviord/devviord/server/internal/input"
)

func TestSyntheticRecordsPointerDown(t *testing.T) {
	c, err := New("synthetic", Options{Quality: 72, FPS: 20, Scale: 1})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	inj, ok := c.(input.Injector)
	if !ok {
		t.Fatal("synthetic backend is not an injector")
	}
	inj.InjectPointer(0.4, 0.5, "down", 1)
	inj.InjectPointer(0.4, 0.5, "up", 0)
	if got := c.(*syntheticBackend).Clicks(); got != 1 {
		t.Errorf("clicks = %d, want 1", got)
	}
}
