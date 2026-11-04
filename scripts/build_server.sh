#!/usr/bin/env bash
# Builds the devviord host binary for the current platform and, optionally,
# every supported platform into dist/.
#
# Usage:
#   scripts/build_server.sh            # current platform -> dist/devviord
#   scripts/build_server.sh --all      # all release targets into dist/
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GO="$ROOT/toolchain/go/bin/go"
[ -x "$GO" ] || GO="$(command -v go)"
[ -x "$GO" ] || { echo "Go toolchain not found. Run scripts/fetch_toolchain.sh" >&2; exit 1; }

export GOTOOLCHAIN=local
export GOPATH="${GOPATH:-$HOME/.gopath}"
export GOCACHE="${GOCACHE:-$HOME/.gocache}"
VERSION="${VERSION:-1.0.0}"
LDFLAGS="-s -w -X main.version=$VERSION"

mkdir -p "$ROOT/dist"
cd "$ROOT/server"

if [ "${1:-}" = "--all" ]; then
  build() {
    local os=$1 arch=$2 out=$3
    echo "[build] $os/$arch -> dist/$out"
    CGO_ENABLED=0 GOOS=$os GOARCH=$arch "$GO" build -trimpath -ldflags "$LDFLAGS" \
      -o "$ROOT/dist/$out" ./cmd/devviord
  }
  build linux   amd64 devviord-linux-amd64
  build linux   arm64 devviord-linux-arm64
  build linux   arm   devviord-linux-armv7
  build darwin  arm64 devviord-darwin-arm64
  build darwin  amd64 devviord-darwin-amd64
  build windows amd64 devviord-windows-amd64.exe
else
  echo "[build] current platform -> dist/devviord"
  "$GO" build -trimpath -ldflags "$LDFLAGS" -o "$ROOT/dist/devviord" ./cmd/devviord
fi

echo
ls -lh "$ROOT/dist" | sed 's/^/  /'
