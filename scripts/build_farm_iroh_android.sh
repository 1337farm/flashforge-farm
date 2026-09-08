#!/usr/bin/env bash
# scripts/build_farm_iroh_android.sh — build the farm-iroh UniFFI cdylib for
# Android arm64-v8a and generate the Kotlin bindings, entirely from source.
#
# Output:
#   engine/... no — p2p/output/<ABI>/libfarm_iroh.so   (packaged via jniLibs)
#   p2p/gen/                                           (generated Kotlin sources)
#
# Prereqs: Rust toolchain (installed below if missing), Android NDK.
# Env:
#   ANDROID_NDK_ROOT  NDK root (default $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI               target ABI (default arm64-v8a; only arm64 supported v1)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")"; pwd)"
ROOT="$(cd "$SCRIPT_DIR/.."; pwd)"
cd "$ROOT"

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$ANDROID_SDK_ROOT/ndk/23.1.7779620}"
ABI="${ABI:-arm64-v8a}"
[ "$ABI" = "arm64-v8a" ] || { echo "ERROR: only arm64-v8a supported in v1 (got $ABI)" >&2; exit 1; }

TOOLBIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "$TOOLBIN/aarch64-linux-android23-clang" ] || { echo "ERROR: NDK toolchain not found in $ANDROID_NDK_ROOT" >&2; exit 1; }

if ! command -v rustup >/dev/null 2>&1; then
    echo "--- [iroh] installing rustup ---"
    curl -fsSL --proto '=https' --tlsv1.2 https://sh.rustup.rs | sh -s -- -y --profile minimal --no-modify-path
fi
export PATH="$HOME/.cargo/bin:$PATH"
command -v cargo >/dev/null 2>&1 || { echo "ERROR: cargo not found after rustup" >&2; exit 1; }
# Shared compile cache across all farm Rust builds (farm-iroh here,
# native_iroh_engine in 1337farm/iroh-android-native): same $CARGO_HOME
# registry plus sccache object cache at $SCCACHE_DIR. Skip silently if
# sccache is not installed.
if command -v sccache >/dev/null 2>&1; then
    export RUSTC_WRAPPER=sccache
    export CARGO_INCREMENTAL=0
    export SCCACHE_DIR="${SCCACHE_DIR:-$HOME/.cache/sccache}"
    export SCCACHE_CACHE_SIZE="${SCCACHE_CACHE_SIZE:-10G}"
fi
rustup toolchain install stable --profile minimal --no-self-update >/dev/null 2>&1 || true
rustup target add aarch64-linux-android >/dev/null

# Target config via environment variables, NOT a p2p/.cargo/config.toml:
# cargo reads config only from the invoking directory's ancestors (or
# $CARGO_HOME), so a manifest-dir config is silently ignored when building
# with --manifest-path from the repo root — the host `cc` then links and
# fails on -llog/-lunwind. Env-var target config binds regardless of CWD.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLBIN/aarch64-linux-android23-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="$TOOLBIN/llvm-ar"

# cc-rs (ring, zstd-sys, ...) probes for a bare "aarch64-linux-android-clang"
# on PATH and on $CC_<target>, but NDK r23 ships only versioned clang
# (aarch64-linux-android23-clang). Point the target-specific env vars at the
# versioned binary and put the toolchain bin on PATH; without this the ring
# build script fails with ToolNotFound ("aarch64-linux-android-clang").
export CC_aarch64_linux_android="$TOOLBIN/aarch64-linux-android23-clang"
export CXX_aarch64_linux_android="$TOOLBIN/aarch64-linux-android23-clang++"
export AR_aarch64_linux_android="$TOOLBIN/llvm-ar"
export PATH="$TOOLBIN:$PATH"

echo "--- [iroh] building farm-iroh cdylib (arm64-v8a, release) ---"
# Build from the crate root, not --manifest-path from the repo root: uniffi's
# build.rs shells out to `cargo metadata`, which resolves against the invoking
# CWD — with CWD=$ROOT that finds no Cargo.toml ("could not find Cargo.toml").
# (Target linker/ar come from the env vars above, so the CWD change loses nothing.)
(
    cd p2p
    cargo build --release --target aarch64-linux-android
)

# With CWD=p2p, cargo places the target dir under the crate root.
SO="$ROOT/p2p/target/aarch64-linux-android/release/libfarm_iroh.so"
[ -f "$SO" ] || { echo "ERROR: $SO not built" >&2; exit 1; }

mkdir -p "p2p/output/$ABI"
cp "$SO" "p2p/output/$ABI/libfarm_iroh.so"
echo "--- [iroh] generating Kotlin bindings (uniffi) ---"
# Reinstall only when the pinned CLI is absent/stale: cargo install --force
# re-downloads and rebuilds every run (~5-10 min), defeating the CI cache.
if ! uniffi-bindgen --version 2>/dev/null | grep -q '0.32'; then
    cargo install uniffi --version 0.32.0 --features cli --locked
fi
mkdir -p p2p/gen
# Run from the crate root too: uniffi-bindgen resolve_paths runs `cargo
# metadata` against the invoking CWD (it does not pass --manifest-path), so
# from $ROOT it errors "could not find Cargo.toml" even though the build above
# succeeded. With CWD=p2p, p2p/Cargo.toml is found; $SO is absolute.
(
    cd p2p
    uniffi-bindgen generate --library "$SO" --language kotlin --out-dir gen
)
ls -la "p2p/output/$ABI/" p2p/gen | head -20
echo "--- [iroh] done ---"
