package capture

import (
	"testing"
	"time"
)

func BenchmarkSyntheticCapture1280(b *testing.B) {
	c, err := New("synthetic", Options{Quality: 72, FPS: 20, Scale: 1})
	if err != nil {
		b.Fatal(err)
	}
	defer c.Close()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		if _, err := c.Capture(); err != nil {
			b.Fatal(err)
		}
	}
}

func TestSyntheticCaptureThroughput(t *testing.T) {
	c, err := New("synthetic", Options{Quality: 72, FPS: 20, Scale: 1})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	const n = 40
	start := time.Now()
	var total int
	for i := 0; i < n; i++ {
		f, err := c.Capture()
		if err != nil {
			t.Fatal(err)
		}
		total += len(f.JPEG)
	}
	d := time.Since(start)
	fps := float64(n) / d.Seconds()
	t.Logf("%d frames in %v => %.1f fps, avg %.0f KiB/frame", n, d, fps, float64(total/n)/1024)
	if raceEnabled {
		// The race detector multiplies CPU cost; a real-time budget is not
		// meaningful. Keep the timing line in the log, skip the assertion.
		t.Logf("race build: skipping the fps assertion (measured %.1f fps)", fps)
		return
	}
	if fps < 20 {
		t.Errorf("capture too slow for 20 fps target: %.1f fps", fps)
	}
}
