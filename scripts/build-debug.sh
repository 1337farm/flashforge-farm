#!/usr/bin/env bash
# build-debug.sh — build the Debug APK on-device from downloaded CI deps.
#
# Native dependencies (Boost/OCCT/oneTBB/GMP-MPFR + libslic3r.so) are NOT
# compiled locally anymore; they are fetched from the CI "Build Native Engine
# (from source)" workflow via scripts/fetch-native-deps.sh and staged into the
# source tree. Then Gradle assembles the APK.
#
# Usage:
#   scripts/build-debug.sh [--no-fetch] [--fetch-only] [--run-id=<id>]
#
#   --no-fetch   skip the dependency fetch/stage step (assume already staged)
#   --fetch-only fetch+stage deps then stop (no Gradle build)
#   --run-id     fetch from a specific CI workflow run id (otherwise auto)
#
# Env (optional):
#   ANDROID_SDK_ROOT   default ~/android-sdk
#   JAVA_HOME          default resolved from `java` on PATH (OpenJDK 21)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")"; pwd)"
ROOT="$(cd "$SCRIPT_DIR/.."; pwd)"

# --- flags ---------------------------------------------------------------
FETCH=1
BUILD=1
RUN_ID_OPT=""
for a in "$@"; do
  case "$a" in
    --no-fetch)   FETCH=0 ;;
    --fetch-only) BUILD=0 ;;
    --run-id=*)   RUN_ID_OPT="${a#--run-id=}" ;;
    *) echo "unknown arg: $a" >&2; exit 2 ;;
  esac
done

cd "$ROOT"

# --- Android toolchain ----------------------------------------------------
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
export ANDROID_HOME="${ANDROID_SDK_ROOT}"
if [ -z "${JAVA_HOME:-}" ]; then
  J="$(command -v java || true)"
  if [ -n "$J" ]; then
    # Derive JAVA_HOME from the java binary (…/bin/java -> …)
    export JAVA_HOME="$(cd "$(dirname "$(dirname "$J")")"; pwd)"
  else
    # Fall back to the CI-worker convention if no java on PATH.
    export JAVA_HOME="${JAVA_HOME:-/home/cody/.sdkman/candidates/java/17.0.11-tem}"
  fi
fi
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
command -v java >/dev/null 2>&1 || { echo "ERROR: java not found on PATH" >&2; exit 1; }
[ -d "$ANDROID_SDK_ROOT" ] || { echo "ERROR: ANDROID_SDK_ROOT '$ANDROID_SDK_ROOT' not found" >&2; exit 1; }

# --- fetch/stage native deps from CI --------------------------------------
if [ "$FETCH" -eq 1 ]; then
  echo "=== fetching native deps from CI ==="
  if [ -n "$RUN_ID_OPT" ]; then
    RUN_ID="$RUN_ID_OPT" scripts/fetch-native-deps.sh
  else
    scripts/fetch-native-deps.sh
  fi
fi

# --- sanity checks before Gradle -----------------------------------------
check_file() { [ -f "$1" ] || { echo "ERROR: missing $1 — run fetch step" >&2; exit 1; }; }
check_dir()  { [ -d "$1" ] || { echo "ERROR: missing dir $1 — run fetch step" >&2; exit 1; }; }
check_file engine/output/arm64-v8a/libslic3r.so
check_dir  engine/src/main/jniImports/boost/lib
check_dir  engine/src/main/jniImports/oneTBB
check_dir  engine/src/main/occt/include
check_dir  engine/src/main/jniLibs

if [ "$BUILD" -eq 0 ]; then
  echo "=== deps staged (--fetch-only); not running Gradle ==="
  exit 0
fi

echo "=== building Debug APK (this can take a long time) ==="
./gradlew :app:assembleDebug --no-daemon --stacktrace
