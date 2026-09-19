#!/usr/bin/env bash
# Stage Eigen 3.4.0 headers for the native engine build.
#
# The vendored Eigen 3.3 (src/main/jni/eigen) was deleted in #210: it uses
# std::result_of, removed in C++20, and the 3.0 toolchain (NDK r26d, C++20)
# cannot compile it. Pin mirrors scripts/build_prusa30_deps.sh, which tracks
# upstream deps/*.cmake. Idempotent: reuses an existing stage.
#
# Usage: scripts/stage_eigen.sh <dest-dir>
#   Produces <dest>/include/Eigen/** + <dest>/include/unsupported/**
set -euo pipefail

DEST="${1:?usage: stage_eigen.sh <dest-dir>}"
URL="https://gitlab.com/libeigen/eigen/-/archive/3.4.0/eigen-3.4.0.zip"
SHA="eba3f3d414d2f8cba2919c78ec6daab08fc71ba2ba4ae502b7e5d4d99fc02cda"

if [ -f "$DEST/include/Eigen/Dense" ]; then
    echo "eigen 3.4.0 already staged at $DEST"
    exit 0
fi
rm -rf "$DEST"
mkdir -p "$DEST"
curl -sSL -o "$DEST/eigen.zip" "$URL"
echo "$SHA  $DEST/eigen.zip" | sha256sum -c -
unzip -q -o "$DEST/eigen.zip" -d "$DEST"
mkdir -p "$DEST/include"
cp -r "$DEST"/eigen-3.4.0/Eigen "$DEST"/eigen-3.4.0/unsupported "$DEST/include/"
rm -rf "$DEST/eigen-3.4.0" "$DEST/eigen.zip"
test -f "$DEST/include/Eigen/Dense" && echo "eigen 3.4.0 staged at $DEST/include"
