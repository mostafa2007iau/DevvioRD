#!/usr/bin/env bash
# Fetches the offline Android build toolchain used by scripts/build_apk.py.
#
# Everything here avoids Gradle and Google's Maven repository, so an APK can be
# built on a machine with only PyPI + npm + GitHub access (e.g. a sandbox).
#
# Result layout:
#   toolchain/jdk/           Temurin JDK runtime (java, keytool)
#   toolchain/tools/aapt2    Android asset packager
#   toolchain/tools/android.jar  API 34 framework stubs
#   toolchain/tools/ecj.jar      Eclipse Java compiler
#   toolchain/tools/d8.jar       R8/D8 dexer
#   toolchain/tools/apksigner.jar
#   toolchain/tools/debug.keystore
#   toolchain/go/            Go toolchain (server builds)
#
# Usage: scripts/fetch_toolchain.sh [target-dir]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
T="${1:-$ROOT/toolchain}"
DL="$T/.downloads"
mkdir -p "$T/tools" "$DL"
cd "$DL"

say() { printf '\n[fetch] %s\n' "$*"; }

# ---------------------------------------------------------------- 1. JDK
if [ ! -x "$T/jdk/bin/java" ]; then
  say "JDK runtime (PyPI: jdk4py)"
  python3 -m pip download jdk4py --no-deps -d . -q
  rm -rf jdkx && mkdir jdkx && (cd jdkx && unzip -q ../jdk4py-*.whl)
  rm -rf "$T/jdk" && cp -r jdkx/jdk4py/java-runtime "$T/jdk"
fi

# ---------------------------------------------------------------- 2. Go
if [ ! -x "$T/go/bin/go" ]; then
  say "Go toolchain (PyPI: go_bin)"
  python3 -m pip download go_bin --no-deps -d . -q
  rm -rf gox && mkdir gox && (cd gox && unzip -q ../go_bin-*.whl)
  rm -rf "$T/go" && cp -r gox/go "$T/go"
fi

# ------------------------------------------- 3. Android tools (npm bundle)
if [ ! -f "$T/tools/d8.jar" ] || [ ! -f "$T/tools/android.jar" ]; then
  say "Android build tools (npm: @drxiaozhi/minapk bundle)"
  npm pack @drxiaozhi/minapk --silent >/dev/null
  rm -rf minapk && mkdir minapk && tar xzf drxiaozhi-minapk-*.tgz -C minapk
  cp minapk/package/tools/android.jar      "$T/tools/"
  cp minapk/package/tools/d8.jar           "$T/tools/"
  cp minapk/package/tools/apksigner.jar    "$T/tools/"
  cp minapk/package/tools/ecj-3.45.0.jar   "$T/tools/ecj.jar"
  cp minapk/package/tools/debug.keystore   "$T/tools/"
fi

# ------------------------------------------------------------ 4. aapt2 (linux)
if [ ! -x "$T/tools/aapt2" ]; then
  say "aapt2 (npm: aaptjs3)"
  npm pack aaptjs3 --silent >/dev/null
  rm -rf aapt && mkdir aapt && tar xzf aaptjs3-*.tgz -C aapt
  cp aapt/package/bin/x64/linux/aapt2 "$T/tools/aapt2"
  chmod +x "$T/tools/aapt2"
fi

# ------------------------------------------------------------ 5. verify
say "versions"
"$T/jdk/bin/java" -version 2>&1 | head -2
"$T/go/bin/go" version
"$T/tools/aapt2" version
"$T/jdk/bin/java" -jar "$T/tools/ecj.jar" -version
"$T/jdk/bin/java" -cp "$T/tools/d8.jar" com.android.tools.r8.D8 --version

say "done - toolchain ready in $T"
