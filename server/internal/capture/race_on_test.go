//go:build race

package capture

// raceEnabled is true when the binary was built with -race. The throughput test
// asserts a real-time budget, which the race detector's instrumentation breaks
// by design, so the assertion is skipped rather than reported as a failure.
const raceEnabled = true
