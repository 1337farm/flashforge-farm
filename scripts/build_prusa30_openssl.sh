#!/usr/bin/env bash
# scripts/build_prusa30_openssl.sh — OpenSSL 4.0.1 for the Android NDK.
#
# Pin provenance: engine/prusa30/deps-manifest.json (URL + SHA256 extracted
# from upstream deps/+OpenSSL/OpenSSL.cmake at PRUSA_REF — nothing guessed).
# Required because slic3r-biz-crypto hard-requires find_package(OpenSSL) and,
# without a staged prefix, FindOpenSSL latches onto the NDK sysroot's bogus
# 3.0.13 header tree (no libcrypto) and configure fails with
# "Could NOT find OpenSSL ... missing: OPENSSL_CRYPTO_LIBRARY".
#
# Handled with OpenSSL's own Configure using the officially-supported
# android-arm64 target (it derives the clang wrapper, sysroot, and API level
# from ANDROID_NDK_ROOT), mirroring the --cross-compile-prefix approach
# upstream's deps/+OpenSSL/OpenSSL.cmake uses for its cross toolchains.
# Static only (no-shared): SLIC3R_STATIC defaults ON and the headless build
# produces archives, never an executable. install_sw skips doc targets so no
# pod2man is needed. Stage layout is a normal prefix: include/ + lib/ with
# libcrypto.a + libssl.a, so the headless configure can pre-seed the exact
# OPENSSL_* cache vars (NDK rejects absolute-path probing, playbook §5).
#
# Env inputs (all optional; defaults shown):
#   NDK           NDK root (required; falls back to $ANDROID_NDK_ROOT, then
#                 $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI           target ABI (default arm64-v8a; script only builds arm64)
#   API_LEVEL     Android API level (default 23)
#   N_CORES       parallelism (default nproc)
#   WORK_DIR      build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT    install prefix (default $(pwd)/engine/prusa30/jniImports/openssl)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

OPENSSL_VER="4.0.1"
OPENSSL_URL="https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VER}/openssl-${OPENSSL_VER}.tar.gz"
OPENSSL_SHA="2db3f3a0d6ea4b59e1f094ace2c8cd536dffb87cdc39084c5afa1e6f7f37dd09"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[openssl] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/openssl}"

command -v perl >/dev/null 2>&1 \
    || { echo "[openssl] ERROR: perl required by OpenSSL's Configure" >&2; exit 1; }

echo "======================================================================="
echo " [openssl] Building OpenSSL $OPENSSL_VER ($ABI, api $API_LEVEL, -j$N_CORES, static)"
echo " [openssl] NDK: $NDK"
echo " [openssl] stage: $STAGE_ROOT"
echo "======================================================================="

mkdir -p "$WORK_DIR" "$STAGE_ROOT"
cd "$WORK_DIR"
if [ ! -f "openssl-$OPENSSL_VER.tar.gz" ]; then
    curl -fsSL --connect-timeout 20 --max-time 300 \
        --retry 2 --retry-all-errors -o "openssl-$OPENSSL_VER.tar.gz" "$OPENSSL_URL"
fi
echo "$OPENSSL_SHA  openssl-$OPENSSL_VER.tar.gz" | sha256sum -c --status - \
    || { echo "[openssl] ERROR: sha256 mismatch" >&2; exit 1; }

rm -rf "openssl-$OPENSSL_VER"
mkdir -p "openssl-$OPENSSL_VER"
tar xzf "openssl-$OPENSSL_VER.tar.gz" -C "openssl-$OPENSSL_VER"
SRC="$(find "openssl-$OPENSSL_VER" -maxdepth 2 -name Configure -printf '%h\n' | head -1)"
[ -n "$SRC" ] || { echo "[openssl] ERROR: no Configure in tarball" >&2; exit 1; }
cd "$SRC"

# Upstream fix (openssl/openssl#31670, fixed by PR #32132, not yet in the
# 4.0.1 tarball): the SVE2 poly1305 asm leaves poly1305_blocks_sve2
# preemptible, so ADRP/ADD against it fails when libcrypto.a is linked
# into a shared library (ld.lld's "recompile with -fPIC" is misleading —
# the rest of the archive already links PIC-clean). Upstream's one-line
# fix marks the internal entry point .hidden; apply it to the perlasm
# source (the tarball ships only .pl — make generates the .S from it).
POLY_PL="crypto/poly1305/asm/poly1305-armv9-sve2.pl"
if ! grep -q '^\.hidden.*poly1305_blocks_sve2' "$POLY_PL"; then
    sed -i 's/^\.globl\tpoly1305_blocks_sve2$/.globl\tpoly1305_blocks_sve2\n.hidden\tpoly1305_blocks_sve2/' "$POLY_PL"
    grep -q '^\.hidden.*poly1305_blocks_sve2' "$POLY_PL" \
        || { echo "[openssl] ERROR: poly1305 SVE2 visibility patch failed" >&2; exit 1; }
    echo "[openssl] applied upstream poly1305 SVE2 .hidden fix (#31670/#32132)"
fi

export ANDROID_NDK_ROOT="$NDK"
./Configure android-arm64 \
    --prefix="$STAGE_ROOT" \
    -D__ANDROID_API__="$API_LEVEL" \
    no-shared \
    -Wa,--noexecstack \
    || { echo "[openssl] ERROR: Configure failed" >&2; exit 1; }

make -j"$N_CORES" || { echo "[openssl] ERROR: make failed" >&2; exit 1; }
make install_sw || { echo "[openssl] ERROR: install_sw failed" >&2; exit 1; }

# Normalize for find_package(OpenSSL): libs must sit in $STAGE_ROOT/lib
# (some install layouts nest under lib/<triplet>).
mkdir -p "$STAGE_ROOT/lib"
for so in libcrypto.a libssl.a; do
    LIB="$(find "$STAGE_ROOT" -name "$so" | head -1)"
    if [ -n "$LIB" ]; then
        cp -f "$LIB" "$STAGE_ROOT/lib/" 2>/dev/null || true
    fi
done

echo "--- staged OpenSSL $OPENSSL_VER ---"
ls "$STAGE_ROOT/include/openssl/ssl.h" "$STAGE_ROOT/lib/libssl.a" \
   "$STAGE_ROOT/lib/libcrypto.a" >/dev/null \
    || { echo "[openssl] ERROR: staged tree incomplete" >&2; exit 1; }
grep -q "OPENSSL_VERSION_NUMBER" "$STAGE_ROOT/include/openssl/opensslv.h" \
    || { echo "[openssl] ERROR: staged headers are not OpenSSL" >&2; exit 1; }
echo "OpenSSL $OPENSSL_VER staged and verified"