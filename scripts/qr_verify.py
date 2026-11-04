#!/usr/bin/env python3
"""Verify the server's QR encoder with an independent decoder (OpenCV).

Generates QR matrices with the Go encoder (via `go test`), renders them as PNG
and decodes them with OpenCV. Proves the pairing code is actually scannable.
"""
import os
import subprocess
import sys
import tempfile

import cv2
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GO = os.path.join(ROOT, "toolchain", "go", "bin", "go")

PAYLOADS = [
    "devviord://connect?host=192.168.1.10:5900&token=abc123",
    "http://10.0.0.5:5900",
    "devviord://connect?host=localhost:5900",
]


def encode_with_go(payload, out_pbm):
    env = dict(os.environ)
    env["PATH"] = os.path.join(ROOT, "toolchain", "go", "bin") + ":" + env.get("PATH", "")
    env["GOPATH"] = os.path.expanduser("~/.gopath")
    env["GOCACHE"] = os.path.expanduser("~/.gocache")
    env["GOTOOLCHAIN"] = "local"
    env["QR_PAYLOAD"] = payload
    env["QR_OUT"] = out_pbm
    helper = os.path.join(ROOT, "server", "internal", "cli", "qr_export_test.go")
    with open(helper, "w") as f:
        f.write('''package cli

import (
	"os"
	"strconv"
	"testing"
)

func TestExportQRArtifact(t *testing.T) {
	payload := os.Getenv("QR_PAYLOAD")
	out := os.Getenv("QR_OUT")
	if payload == "" || out == "" {
		t.Skip("no payload")
	}
	q, err := encodeQR(payload)
	if err != nil {
		t.Fatalf("encode: %v", err)
	}
	s := "P1\\n" + strconv.Itoa(q.size) + " " + strconv.Itoa(q.size) + "\\n"
	for y := 0; y < q.size; y++ {
		for x := 0; x < q.size; x++ {
			if q.modules[y][x] {
				s += "1 "
			} else {
				s += "0 "
			}
		}
		s += "\\n"
	}
	if err := os.WriteFile(out, []byte(s), 0o644); err != nil {
		t.Fatal(err)
	}
}
''')
    try:
        p = subprocess.run([GO, "test", "./internal/cli", "-run", "TestExportQRArtifact", "-count=1"],
                           cwd=os.path.join(ROOT, "server"), env=env, capture_output=True, text=True)
        if p.returncode != 0:
            print(p.stdout, p.stderr)
            return False
    finally:
        os.unlink(helper)
    return True


def decode_pbm(path):
    toks = open(path).read().split()
    assert toks[0] == "P1", toks[0]
    w, h = int(toks[1]), int(toks[2])
    bits = np.array([int(v) for v in toks[3:3 + w * h]], dtype=np.uint8).reshape(h, w)
    scale, quiet = 10, 4
    img = np.ones(((h + 2 * quiet) * scale, (w + 2 * quiet) * scale), dtype=np.uint8) * 255
    for y in range(h):
        for x in range(w):
            if bits[y, x]:
                img[(y + quiet) * scale:(y + quiet + 1) * scale,
                    (x + quiet) * scale:(x + quiet + 1) * scale] = 0
    data, _, _ = cv2.QRCodeDetector().detectAndDecode(img)
    return data


def main():
    print("\n=== DevvioRD pairing QR verification (independent decoder) ===\n")
    ok = 0
    for payload in PAYLOADS:
        with tempfile.NamedTemporaryFile(suffix=".pbm", delete=False) as f:
            path = f.name
        try:
            if not encode_with_go(payload, path):
                print(f"  FAIL  encoder error for {payload!r}")
                continue
            got = decode_pbm(path)
            if got == payload:
                ok += 1
                print(f"  PASS  decoded {len(payload):2d}-byte payload: {payload}")
            else:
                print(f"  FAIL  expected {payload!r} got {got!r}")
        finally:
            os.unlink(path)
    print("\n--- summary ---")
    print(f"{ok}/{len(PAYLOADS)} payloads round-tripped through a real QR decoder")
    return 0 if ok == len(PAYLOADS) else 1


if __name__ == "__main__":
    sys.exit(main())
