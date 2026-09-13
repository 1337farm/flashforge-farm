#!/usr/bin/env bash
# scripts/build_prusa30_openvdb.sh — OpenVDB 11.0.0 for the Android NDK (static).
#
# The libslic3r configure requires find_package(OpenVDB 5.0 COMPONENTS openvdb)
# (src/libslic3r/CMakeLists.txt). Built standalone (not via the prusa
# superbuild) with upstream's exact deps/+OpenVDB/OpenVDB.cmake options plus
# same-input presets for every transdep (TBB/Blosc/zstd/ZLIB/Boost/Threads),
# and the upstream openvdb.patch applied so the tree matches what the prusa
# superbuild compiles (NodeManager `eval<>` clang fix + Threads find).
#
# USE_BLOSC=ON + USE_ZLIB=ON bake OPENVDB_USE_BLOSC/OPENVDB_USE_ZLIB into the
# installed openvdb/version.h, which prusa's bundled FindOpenVDB reads back at
# libslic3r configure time — so it then requires Blosc (-> zstd config), ZLIB,
# TBB and Boost iostreams, all of which are staged. USE_IMATH_HALF stays OFF
# (default USE_EXR=OFF), so the embedded half is compiled in and Imath/OpenEXR
# are NOT needed.
#
# Pins (exact, from upstream deps/+OpenVDB/OpenVDB.cmake):
#   OpenVDB: https://github.com/AcademySoftwareFoundation/openvdb/archive/refs/tags/v11.0.0.zip
#            SHA256=db7e1aacd0a634195574b2e6a43d268d063830628501fd0a94a99bf252d01fb6
#
# Env inputs (all optional; defaults shown):
#   NDK           NDK root (required; falls back to $ANDROID_NDK_ROOT, then
#                 $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI           target ABI (default arm64-v8a)
#   API_LEVEL     Android API level (default 23)
#   N_CORES       parallelism (default nproc)
#   WORK_DIR      build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT    install prefix (default $(pwd)/engine/prusa30/jniImports/openvdb)
#   BOOST_STAGE   Boost prefix (default boost stage)
#   TBB_STAGE     oneTBB prefix (default tbb stage; needs TBBConfig.cmake)
#   BLOSC_STAGE   Blosc prefix (default blosc stage; needs blosc.h + libblosc.a)
#   ZSTD_STAGE    zstd prefix (default zstd stage; needs its zstdConfig.cmake)
#   ZLIB_STAGE    zlib prefix (default pngfmt stage; needs zlib.h + libz.a)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

OPENVDB_VER="11.0.0"
OPENVDB_URL="https://github.com/AcademySoftwareFoundation/openvdb/archive/refs/tags/v${OPENVDB_VER}.zip"
OPENVDB_SHA="db7e1aacd0a634195574b2e6a43d268d063830628501fd0a94a99bf252d01fb6"
PATCH="$ROOT/engine/prusa30/patches/openvdb-patch.patch"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[openvdb] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/openvdb}"
BOOST_STAGE="${BOOST_STAGE:-$ROOT/engine/prusa30/jniImports/boost}"
TBB_STAGE="${TBB_STAGE:-$ROOT/engine/prusa30/jniImports/tbb}"
BLOSC_STAGE="${BLOSC_STAGE:-$ROOT/engine/prusa30/jniImports/blosc}"
ZSTD_STAGE="${ZSTD_STAGE:-$ROOT/engine/prusa30/jniImports/zstd}"
ZLIB_STAGE="${ZLIB_STAGE:-$ROOT/engine/prusa30/jniImports/pngfmt}"

# Resolve exact staged paths so the find_* calls short-circuit (NDK MODE=ONLY
# re-rooting can't shadow a pre-seeded cache var).
BOOST_INC="$BOOST_STAGE/include"
BOOST_LIBDIR="$BOOST_STAGE/lib/$ABI/lib"
test -f "$BOOST_INC/boost/version.hpp" \
    || { echo "[openvdb] ERROR: staged Boost headers missing" >&2; exit 1; }
find "$BOOST_LIBDIR" -name 'libboost_iostreams*.a' | grep -q . \
    || { echo "[openvdb] ERROR: staged Boost iostreams missing" >&2; exit 1; }
TBB_CMAKE_DIR="$(find "$TBB_STAGE" -name TBBConfig.cmake -printf '%h\n' | head -1)"
[ -n "$TBB_CMAKE_DIR" ] || { echo "[openvdb] ERROR: TBBConfig.cmake missing" >&2; exit 1; }
TBB_INC="$TBB_STAGE/include"
test -f "$TBB_INC/oneapi/tbb/version.h" \
    || { echo "[openvdb] ERROR: oneapi/tbb/version.h missing" >&2; exit 1; }
TBB_LIB="$(find "$TBB_STAGE" -name 'libtbb.a' | head -1)"
[ -n "$TBB_LIB" ] || { echo "[openvdb] ERROR: libtbb.a missing" >&2; exit 1; }
BOOST_IOSTREAMS_LIB="$(find "$BOOST_LIBDIR" -name 'libboost_iostreams*.a' | head -1)"
[ -n "$BOOST_IOSTREAMS_LIB" ] \
    || { echo "[openvdb] ERROR: libboost_iostreams*.a missing" >&2; exit 1; }
BOOST_REGEX_LIB="$(find "$BOOST_LIBDIR" -name 'libboost_regex*.a' | head -1)"
[ -n "$BOOST_REGEX_LIB" ] \
    || { echo "[openvdb] ERROR: libboost_regex*.a missing" >&2; exit 1; }
BLOSC_INC="$(dirname "$(find "$BLOSC_STAGE" -name blosc.h | head -1)")"
[ -n "$BLOSC_INC" ] || { echo "[openvdb] ERROR: blosc.h missing" >&2; exit 1; }
BLOSC_LIB="$(find "$BLOSC_STAGE" -name 'libblosc.a' | head -1)"
[ -n "$BLOSC_LIB" ] || { echo "[openvdb] ERROR: libblosc.a missing" >&2; exit 1; }
ZSTD_CMAKE_DIR="$(find "$ZSTD_STAGE" -name zstdConfig.cmake -printf '%h\n' | head -1)"
[ -n "$ZSTD_CMAKE_DIR" ] || { echo "[openvdb] ERROR: zstdConfig.cmake missing" >&2; exit 1; }
ZLIB_INC="$(dirname "$(find "$ZLIB_STAGE" -name zlib.h | head -1)")"
[ -n "$ZLIB_INC" ] || { echo "[openvdb] ERROR: zlib.h missing" >&2; exit 1; }
ZLIB_LIB="$(find "$ZLIB_STAGE" -name 'libz.a' | head -1)"
[ -n "$ZLIB_LIB" ] || { echo "[openvdb] ERROR: libz.a missing" >&2; exit 1; }
test -f "$PATCH" || { echo "[openvdb] ERROR: patch missing: $PATCH" >&2; exit 1; }

TC="$NDK/build/cmake/android.toolchain.cmake"
echo "======================================================================="
echo " [openvdb] Building OpenVDB $OPENVDB_VER ($ABI, api $API_LEVEL, static)"
echo " [openvdb] stage: $STAGE_ROOT"
echo " [openvdb] boost: $BOOST_STAGE | tbb: $TBB_CMAKE_DIR | blosc: $BLOSC_LIB"
echo " [openvdb] zstd config: $ZSTD_CMAKE_DIR | zlib: $ZLIB_LIB"
echo "======================================================================="

mkdir -p "$WORK_DIR" "$STAGE_ROOT"
cd "$WORK_DIR"
if [ ! -f "openvdb-$OPENVDB_VER.zip" ]; then
    curl -fsSL --connect-timeout 20 --max-time 600 \
        --retry 2 --retry-all-errors -o "openvdb-$OPENVDB_VER.zip" "$OPENVDB_URL"
fi
echo "$OPENVDB_SHA  openvdb-$OPENVDB_VER.zip" | sha256sum -c --status - \
    || { echo "[openvdb] ERROR: sha256 mismatch" >&2; exit 1; }

rm -rf "openvdb-$OPENVDB_VER" "openvdb-build"
mkdir -p "openvdb-$OPENVDB_VER"
unzip -q -o "openvdb-$OPENVDB_VER.zip" -d "openvdb-$OPENVDB_VER"
SRC="$(find "openvdb-$OPENVDB_VER" -maxdepth 2 -name CMakeLists.txt \
    -printf '%h\n' | head -1)"
[ -n "$SRC" ] || { echo "[openvdb] ERROR: no OpenVDB CMakeLists" >&2; exit 1; }

# Upstream superbuild applies deps/+OpenVDB/openvdb.patch; do the same so the
# standalone tree matches (NodeManager eval<> clang fix, Threads find, MSVC).
( cd "$SRC" && patch -p1 < "$PATCH" ) \
    || { echo "[openvdb] ERROR: openvdb.patch did not apply cleanly" >&2; exit 1; }

cmake -S "$SRC" -B "openvdb-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DOPENVDB_BUILD_PYTHON_MODULE=OFF -DOPENVDB_BUILD_BINARIES=OFF \
    -DUSE_BLOSC=ON \
    -DOPENVDB_CORE_SHARED=OFF -DOPENVDB_CORE_STATIC=ON \
    -DOPENVDB_ENABLE_RPATH=OFF -DUSE_STATIC_DEPENDENCIES=ON \
    -DUSE_CCACHE=OFF -DOPENVDB_ABI_VERSION_NUMBER=11 \
    -DOPENVDB_INSTALL_CMAKE_MODULES=OFF -DUSE_EXPLICIT_INSTANTIATION=OFF \
    -DOPENVDB_BUILD_VDB_PRINT=OFF \
    -DTBB_ROOT="$TBB_STAGE" -DTBB_INCLUDEDIR="$TBB_INC" \
    -DTBB_LIBRARYDIR="$(dirname "$TBB_LIB")" \
    -DTbb_INCLUDE_DIR="$TBB_INC" \
    -DTbb_tbb_LIBRARY_RELEASE="$TBB_LIB" -DTbb_tbb_LIBRARY_DEBUG="$TBB_LIB" \
    -DBlosc_INCLUDE_DIR="$BLOSC_INC" -DBlosc_LIBRARY="$BLOSC_LIB" \
    -DBlosc_LIBRARY_RELEASE="$BLOSC_LIB" -DBLOSC_USE_STATIC_LIBS=ON \
    -Dzstd_DIR="$ZSTD_CMAKE_DIR" \
    -DZLIB_INCLUDE_DIR="$ZLIB_INC" -DZLIB_LIBRARY="$ZLIB_LIB" \
    -DBOOST_ROOT="$BOOST_STAGE" -DBoost_ROOT="$BOOST_STAGE" \
    -DBoost_INCLUDE_DIR="$BOOST_INC" -DBoost_LIBRARY_DIR="$BOOST_LIBDIR" \
    -DBoost_NO_SYSTEM_PATHS=ON -DBoost_USE_STATIC_LIBS=ON \
    -DBoost_IOSTREAMS_LIBRARY_RELEASE="$BOOST_IOSTREAMS_LIB" \
    -DBoost_IOSTREAMS_LIBRARY_DEBUG="$BOOST_IOSTREAMS_LIB" \
    -DBoost_REGEX_LIBRARY_RELEASE="$BOOST_REGEX_LIB" \
    -DBoost_REGEX_LIBRARY_DEBUG="$BOOST_REGEX_LIB" \
    -DBoost_COMPILER="-clang" -DBoost_ARCHITECTURE="-a64" \
    -DBOOST_INCLUDEDIR="$BOOST_INC" -DBOOST_LIBRARYDIR="$BOOST_LIBDIR"
cmake --build "openvdb-build" --target openvdb_static -j"$N_CORES" \
    || { echo "[openvdb] ERROR: openvdb_static build failed" >&2; exit 1; }
cmake --install "openvdb-build"

test -f "$STAGE_ROOT/include/openvdb/version.h" \
    || { echo "[openvdb] ERROR: openvdb/version.h missing" >&2; exit 1; }
find "$STAGE_ROOT" -name 'libopenvdb.a' | grep -q . \
    || { echo "[openvdb] ERROR: no libopenvdb.a" >&2; exit 1; }
grep -q "OPENVDB_USE_BLOSC" "$STAGE_ROOT/include/openvdb/version.h" \
    || { echo "[openvdb] ERROR: version.h lacks OPENVDB_USE_BLOSC (USE_BLOSC not baked)" >&2; exit 1; }
grep -q "OPENVDB_USE_ZLIB" "$STAGE_ROOT/include/openvdb/version.h" \
    || { echo "[openvdb] ERROR: version.h lacks OPENVDB_USE_ZLIB" >&2; exit 1; }
echo "[openvdb] done -> $STAGE_ROOT"