#!/usr/bin/env bash
# scripts/build_prusa30_gmp.sh — GMP 6.2.1 + MPFR 4.2.0 (SHARED) + CGAL v5.6.2
# (static) for the Android NDK.
#
# slic3r-biz-cgal-algorithms does find_package(CGAL REQUIRED); CGAL's own
# configure probes the real GMP/MPFR libraries (FindGMP/FindMPFR), so stage
# all three into one prefix (engine/prusa30/jniImports/gmpcgal): GMP+MPFR as
# Android shared libs (the exact shape the Orca pipeline in
# scripts/build_all_deps_android.sh already validates — autotools
# cross-build producing libgmp/libgmpxx/libmpfr .so + headers), CGAL itself
# static (header-heavy with a small compiled core; installs CGALConfig.cmake).
#
# Pins:
#   GMP 6.2.1:   vendored engine/vendor/gmp-6.2.1.tar.xz (SHA in SHA256SUMS),
#                mirrors as fallback (see build_all_deps_android.sh GMP_URLS).
#                Version matches upstream deps/+GMP/GMP.cmake (6.2.1).
#   MPFR 4.2.0:  vendored engine/vendor/mpfr-4.2.0.tar.xz (SHA in SHA256SUMS).
#                NOTE: upstream deps/+MPFR/MPFR.cmake at the pinned PRUSA_REF
#                has since moved to 4.2.1; we stay on the vendored 4.2.0
#                (hermetic, Orca-validated, ABI-compatible for CGAL's use).
#                Reconcile to 4.2.1 only if a consumer version-checks MPFR.
#   CGAL v5.6.2: https://github.com/CGAL/cgal/archive/refs/tags/v5.6.2.zip
#                SHA256=29acaeee5a76a95029fac23131bb1c3a4a75df9a0e7e43b465a1f32d0628f45d
#                (exact, from upstream deps/+CGAL/CGAL.cmake)
#
# Env inputs (all optional; defaults shown):
#   NDK           NDK root (required; falls back to $ANDROID_NDK_ROOT, then
#                 $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI           target ABI (default arm64-v8a; only arm64-v8a supported:
#                 GMP configure needs ABI=64)
#   API_LEVEL     Android API level (default 23)
#   N_CORES       parallelism (default nproc)
#   WORK_DIR      build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT    install prefix (default $(pwd)/engine/prusa30/jniImports/gmpcgal)
#   BOOST_STAGE   staged Boost prefix CGAL configures against
#                 (default $(pwd)/engine/prusa30/jniImports/boost)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CGAL_VER="5.6.2"
CGAL_URL="https://github.com/CGAL/cgal/archive/refs/tags/v${CGAL_VER}.zip"
CGAL_SHA="29acaeee5a76a95029fac23131bb1c3a4a75df9a0e7e43b465a1f32d0628f45d"
GMP_URLS="https://gmplib.org/download/gmp/gmp-6.2.1.tar.xz https://ftp.gnu.org/gnu/gmp/gmp-6.2.1.tar.xz https://ftpmirror.gnu.org/gmp/gmp-6.2.1.tar.xz"
MPFR_URLS="https://www.mpfr.org/mpfr-4.2.0/mpfr-4.2.0.tar.xz https://ftp.gnu.org/gnu/mpfr/mpfr-4.2.0.tar.xz https://ftpmirror.gnu.org/mpfr/mpfr-4.2.0.tar.xz"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[gmpcgal] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/gmpcgal}"
BOOST_STAGE="${BOOST_STAGE:-$ROOT/engine/prusa30/jniImports/boost}"

TOOLBIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
HOST_PREFIX="aarch64-linux-android${API_LEVEL}"
TC="$NDK/build/cmake/android.toolchain.cmake"

echo "======================================================================="
echo " [gmpcgal] Building GMP 6.2.1 + MPFR 4.2.0 (shared) + CGAL $CGAL_VER (static)"
echo " [gmpcgal] stage: $STAGE_ROOT"
echo " [gmpcgal] boost: $BOOST_STAGE"
echo "======================================================================="

fetch() { # $1=out, $@=urls
    local out="$1"; shift
    local url i
    for url in "$@"; do
        for i in 1 2 3; do
            if curl -fsSL --connect-timeout 20 --max-time 300 \
                    --retry 2 --retry-all-errors -o "$out" "$url" \
               && [ -s "$out" ]; then
                return 0
            fi
            sleep "$((i * 5))"
        done
    done
    echo "[gmpcgal] ERROR: all mirrors failed for $out" >&2
    rm -f "$out"
    return 1
}

mkdir -p "$WORK_DIR" "$STAGE_ROOT"
cd "$WORK_DIR"

export CC="$TOOLBIN/$HOST_PREFIX-clang"
export CXX="$TOOLBIN/$HOST_PREFIX-clang++"
export AR="$TOOLBIN/llvm-ar"
export RANLIB="$TOOLBIN/llvm-ranlib"
export CFLAGS="-O3 -fPIC --sysroot=$SYSROOT"
export CXXFLAGS="-O3 -fPIC --sysroot=$SYSROOT"
export LDFLAGS="--sysroot=$SYSROOT"
# shellcheck disable=SC2086
$CC --version >/dev/null 2>&1 || { echo "[gmpcgal] ERROR: NDK compiler broken" >&2; exit 1; }

# --- GMP (vendored preferred) ------------------------------------------------
if [ ! -d "gmp-6.2.1" ]; then
    if [ -f "$ROOT/engine/vendor/gmp-6.2.1.tar.xz" ] \
       && grep "gmp-6.2.1.tar.xz" "$ROOT/engine/vendor/SHA256SUMS" \
          | (cd "$ROOT/engine/vendor" && sha256sum -c --status -); then
        echo "[gmpcgal] using vendored gmp-6.2.1.tar.xz (sha verified)"
        cp "$ROOT/engine/vendor/gmp-6.2.1.tar.xz" gmp.tar.xz
    else
        # shellcheck disable=SC2086
        fetch gmp.tar.xz $GMP_URLS || exit 1
    fi
    tar xf gmp.tar.xz || exit 1
fi
cd gmp-6.2.1 || exit 1
# Scope ABI=64 to this configure: callers export ABI=arm64-v8a, which GMP's
# configure misreads as a 32/64 selection. aarch64 => 64-bit, so pin ABI=64.
env ABI=64 ./configure --host=$HOST_PREFIX --prefix="$STAGE_ROOT" --disable-assembly \
    --enable-shared --disable-static --enable-cxx || exit 1
make -j"$N_CORES" || exit 1
make install || exit 1
cd ..

# --- MPFR (against staged GMP) -----------------------------------------------
if [ ! -d "mpfr-4.2.0" ]; then
    if [ -f "$ROOT/engine/vendor/mpfr-4.2.0.tar.xz" ] \
       && grep "mpfr-4.2.0.tar.xz" "$ROOT/engine/vendor/SHA256SUMS" \
          | (cd "$ROOT/engine/vendor" && sha256sum -c --status -); then
        echo "[gmpcgal] using vendored mpfr-4.2.0.tar.xz (sha verified)"
        cp "$ROOT/engine/vendor/mpfr-4.2.0.tar.xz" mpfr.tar.xz
    else
        # shellcheck disable=SC2086
        fetch mpfr.tar.xz $MPFR_URLS || exit 1
    fi
    tar xf mpfr.tar.xz || exit 1
fi
cd mpfr-4.2.0 || exit 1
env ABI=64 ./configure --host=$HOST_PREFIX --prefix="$STAGE_ROOT" \
    --enable-shared --disable-static \
    --with-gmp-include="$STAGE_ROOT/include" --with-gmp-lib="$STAGE_ROOT/lib" || exit 1
make -j"$N_CORES" || exit 1
make install || exit 1
cd ..

# --- CGAL (against staged GMP/MPFR/Boost) ------------------------------------
# CGAL's FindGMP/FindMPFR honor GMP_INCLUDE_DIR/GMP_LIBRARIES (NOT the
# plural-less variants) and MPFR_INCLUDE_DIR/MPFR_LIBRARIES: pre-seed them so
# no NDK-sysroot find-module probing is needed. Boost gets the same explicit
# treatment the headless configure uses (FindBoost needs the b2 arm64/clang
# tags under the NDK).
[ -f "cgal-$CGAL_VER.zip" ] || fetch "cgal-$CGAL_VER.zip" "$CGAL_URL"
echo "$CGAL_SHA  cgal-$CGAL_VER.zip" | sha256sum -c --status - \
    || { echo "[gmpcgal] ERROR: cgal sha256 mismatch" >&2; exit 1; }
rm -rf "cgal-$CGAL_VER" "cgal-build"
mkdir -p "cgal-$CGAL_VER" && unzip -q -o "cgal-$CGAL_VER.zip" -d "cgal-$CGAL_VER"
CSRC="$(find "cgal-$CGAL_VER" -maxdepth 2 -name CMakeLists.txt -path '*CGAL*' | head -1 | xargs dirname)"
[ -n "$CSRC" ] || CSRC="$(find "cgal-$CGAL_VER" -maxdepth 2 -name CMakeLists.txt | head -1 | xargs dirname)"
[ -n "$CSRC" ] || { echo "[gmpcgal] ERROR: no CGAL CMakeLists" >&2; exit 1; }
test -d "$BOOST_STAGE/include/boost" || { echo "[gmpcgal] ERROR: staged Boost missing at $BOOST_STAGE" >&2; exit 1; }
cmake -S "$CSRC" -B "cgal-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_PREFIX_PATH="$STAGE_ROOT;$BOOST_STAGE" \
    -DBUILD_SHARED_LIBS=OFF \
    -DCGAL_Boost_USE_STATIC_LIBS=ON \
    -DBOOST_ROOT="$BOOST_STAGE" \
    -DBoost_INCLUDE_DIR="$BOOST_STAGE/include" \
    -DBoost_LIBRARY_DIR="$BOOST_STAGE/lib/$ABI/lib" \
    -DBoost_ARCHITECTURE="-a64" \
    -DBoost_COMPILER="-clang" \
    -DGMP_INCLUDE_DIR="$STAGE_ROOT/include" \
    -DGMP_LIBRARIES="$STAGE_ROOT/lib/libgmp.so" \
    -DGMPXX_INCLUDE_DIR="$STAGE_ROOT/include" \
    -DGMPXX_LIBRARIES="$STAGE_ROOT/lib/libgmpxx.so" \
    -DMPFR_INCLUDE_DIR="$STAGE_ROOT/include" \
    -DMPFR_LIBRARIES="$STAGE_ROOT/lib/libmpfr.so" \
    -DWITH_CGAL_Qt5=OFF -DWITH_CGAL_ImageIO=OFF -DWITH_CGAL_OpenGL=OFF \
    -DBUILD_TESTING=OFF -DBUILD_DOCUMENTATION=OFF -DBUILD_BENCHMARKS=OFF -DBUILD_DEMO=OFF
cmake --build "cgal-build" -j"$N_CORES"
cmake --install "cgal-build"

test -f "$STAGE_ROOT/include/gmp.h" || { echo "[gmpcgal] ERROR: gmp.h missing" >&2; exit 1; }
test -f "$STAGE_ROOT/include/gmpxx.h" || { echo "[gmpcgal] ERROR: gmpxx.h missing" >&2; exit 1; }
test -f "$STAGE_ROOT/include/mpfr.h" || { echo "[gmpcgal] ERROR: mpfr.h missing" >&2; exit 1; }
ls "$STAGE_ROOT/lib"/libgmp.so "$STAGE_ROOT/lib"/libgmpxx.so "$STAGE_ROOT/lib"/libmpfr.so >/dev/null \
    || { echo "[gmpcgal] ERROR: gmp/mpfr .so missing" >&2; exit 1; }
find "$STAGE_ROOT" -name 'CGALConfig.cmake' -o -name 'CGAL-config.cmake' | grep -q . \
    || { echo "[gmpcgal] ERROR: no CGAL config staged" >&2; exit 1; }
echo "[gmpcgal] done -> $STAGE_ROOT"
