#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")"; pwd)"

echo "======================================================================="
echo " Building FlashForge Farm Native Dependencies (Boost, oneTBB, OCCT, GMP/MPFR)"
echo " This process will download and compile massive C++ libraries from source"
echo " using the Android NDK. This will take several hours."
echo " Boost / oneTBB / OCCT are independent and built in parallel."
echo "======================================================================="

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/home/cody/android-sdk}"
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$ANDROID_SDK_ROOT/ndk/23.1.7779620}"
export CMAKE_BIN="${CMAKE_BIN:-cmake}"
export ABI="${ABI:-arm64-v8a}"
export API_LEVEL="${API_LEVEL:-21}"
export N_CORES="${N_CORES:-$(nproc)}"

JNI_IMPORTS_DIR="$(pwd)/engine/src/main/jniImports"
OCCT_DIR="$(pwd)/engine/src/main/occt"
ROOT="$(pwd)"
mkdir -p "$JNI_IMPORTS_DIR" "$OCCT_DIR"

# Download with mirror fallback + retries: upstream hosts go down (gmplib.org
# has timed out for entire runs) and this script runs inside ||-guarded calls
# where `set -e` is suppressed, so a failed download must never silently
# cascade into configure/make garbage. Tries each mirror in order; short
# connect timeout so dead hosts fail fast instead of burning 133s per attempt.
fetch() {
    local out="$1"; shift
    local url i
    for url in "$@"; do
        for i in 1 2 3; do
            if curl -fsSL --connect-timeout 20 --max-time 300 \
                    --retry 2 --retry-all-errors -o "$out" "$url" \
               && [ -s "$out" ]; then
                return 0
            fi
            echo "--- [fetch] attempt $i/3 failed for $url ---" >&2
            sleep "$((i * 5))"
        done
        echo "--- [fetch] mirror exhausted: $url ---" >&2
        rm -f "$out"
    done
    echo "--- [fetch] ERROR: all mirrors failed for $out ---" >&2
    return 1
}

GMP_URLS="https://gmplib.org/download/gmp/gmp-6.2.1.tar.xz https://ftp.gnu.org/gnu/gmp/gmp-6.2.1.tar.xz https://ftpmirror.gnu.org/gmp/gmp-6.2.1.tar.xz"
MPFR_URLS="https://www.mpfr.org/mpfr-4.2.0/mpfr-4.2.0.tar.xz https://ftp.gnu.org/gnu/mpfr/mpfr-4.2.0.tar.xz https://ftpmirror.gnu.org/mpfr/mpfr-4.2.0.tar.xz"

WORK_DIR="${WORK_DIR:-/tmp/build_android_deps}"
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# Every dep runs in its own subdir so parallel builds never touch shared files.
# Output paths are distinct:
#   oneTBB -> jniImports/oneTBB
#   Boost  -> jniImports/boost
#   OCCT   -> occt/jniLibs + occt/include

build_tbb() {
    echo "--- [oneTBB] Building oneTBB ---"
    if [ ! -d "openvdb-android" ]; then
        git clone https://github.com/syoyo/openvdb-android.git
    fi
    cd openvdb-android
    git submodule update --init --recursive
    rm -rf build-tbb-android
    $CMAKE_BIN \
      -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=$ABI \
      -DANDROID_NATIVE_API_LEVEL=$API_LEVEL \
      -DANDROID_STL=c++_shared \
      -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_INSTALL_PREFIX=$(pwd)/dist \
      -DTBB_BUILD_TESTS=Off \
      -DTBB_BUILD_SHARED=Off \
      -DTBB_BUILD_STATIC=On \
      -S tbb-aarch64 \
      -B build-tbb-android

    cd build-tbb-android
    make -j$N_CORES
    make install
    cd ../..

    mkdir -p "$JNI_IMPORTS_DIR/oneTBB/lib/$ABI"
    cp openvdb-android/dist/lib/libtbb_static.a "$JNI_IMPORTS_DIR/oneTBB/lib/$ABI/libtbb.a"
    cp openvdb-android/dist/lib/libtbbmalloc_static.a "$JNI_IMPORTS_DIR/oneTBB/lib/$ABI/libtbbmalloc.a"
    mkdir -p "$JNI_IMPORTS_DIR/oneTBB/include"
    cp -r openvdb-android/dist/include/* "$JNI_IMPORTS_DIR/oneTBB/include/"
    # oneTBB installs the CLASSIC include tree (include/tbb/x.h), but
    # slic3r/clipper include modern oneTBB paths (#include <oneapi/tbb/x.h>),
    # which fails the engine compile (fatal error: 'oneapi/tbb/x.h' not found).
    # Generate compat forwarders oneapi/tbb/<name>.h -> #include <tbb/<name>.h>
    # so both layouts resolve — now part of the build itself (the CI workflow's
    # post-build bridge step is the cache-hit-path equivalent).
    if [ -d "$JNI_IMPORTS_DIR/oneTBB/include/tbb" ]; then
        export ABI
        chmod +x "$SCRIPT_DIR/build_tbb_bridge.sh"
        "$SCRIPT_DIR/build_tbb_bridge.sh" "$JNI_IMPORTS_DIR/oneTBB/include"
    else
        echo "--- [oneTBB] WARNING: include/tbb not found; skipping oneapi bridge ---" >&2
    fi
    echo "--- [oneTBB] built and copied! ---"
}

build_boost() {
    echo "--- [Boost] Building Boost (delegated to scripts/build_boost.sh) ---"
    # CI runs on an x86-64 runner so the NDK host tools run natively;
    # WRAP_HOST_TOOLS=none is the only supported mode. The curated
    # --with-libraries list and all build-android.sh flags live in
    # scripts/build_boost.sh (single source of truth for the CI build).
    export WRAP_HOST_TOOLS="${WRAP_HOST_TOOLS:-none}"
    export NDK="${NDK:-$ANDROID_NDK_ROOT}"
    export JNI_IMPORTS_DIR="$JNI_IMPORTS_DIR"
    export WORK_DIR="$WORK_DIR"
    "$SCRIPT_DIR/build_boost.sh"
    echo "--- [Boost] built and copied! ---"
}

build_occt() {
    echo "--- [OCCT] Building OCCT (FULL build, all toolkits, exports on) ---"
    if [ ! -d "OCCT" ]; then
        git clone https://github.com/Open-Cascade-SAS/OCCT.git
    fi
    # The app's OCCTWrapper.cpp / STEP.cpp / TextShape.cpp target the OCCT 7.x API
    # (e.g. STEPCAFControl_Reader.hxx, STEPControl_Reader). OCCT master has
    # removed/relocated those packages, so pin to a 7.x release that still ships
    # them. Without this, even a full build omits the required headers and the
    # engine fails to compile (fatal error: 'STEPCAFControl_Reader.hxx' not found).
    # Apply the pin on EVERY run (not just the initial clone) so a stale master
    # checkout from a previous run is re-pinned too.
    git -C OCCT fetch --depth 1 origin tag V7_9_0
    git -C OCCT checkout -f V7_9_0
    cd OCCT
    mkdir -p build-android && cd build-android
    $CMAKE_BIN \
      -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=$ABI \
      -DANDROID_NATIVE_API_LEVEL=$API_LEVEL \
      -DANDROID_STL=c++_shared \
      -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_LIBRARY_TYPE=Shared \
      -DUSE_FREETYPE=OFF \
      -DBUILD_MODULE_Draw=OFF \
      -DBUILD_DOC_Overview=OFF \
      -DBUILD_MODULE_DE=ON \
      ..
    # FULL build: compile every OCCT toolkit (all libs + headers + exports).
    # The prior trimmed -j$NEEDED build omitted toolkits (e.g. TKSTEP -> the
    # STEPCAFControl package) whose headers libslic3r's OCCT wrapper needs, which
    # caused "fatal error: 'STEPCAFControl_Reader.hxx' not found" at engine build
    # time. A full build guarantees no header/lib is left out.
    make -j$N_CORES
    mkdir -p "$OCCT_DIR/jniLibs/$ABI"
    # OCCT's CMake exports shared libs into a host-dir subtree of the build dir
    # (e.g. lin64/clang/lib or lib/ depending on the generator), and the public
    # headers are generated into the build dir's inc/opencascade tree. Locate
    # them explicitly instead of guessing the exact layout.
    LIB_SRC="$(find . -path '*/lib/libTK*.so' -printf '%h\n' | sort -u | head -1)"
    if [ -n "$LIB_SRC" ]; then
        cp "$LIB_SRC"/libTK*.so "$OCCT_DIR/jniLibs/$ABI/"
        echo "--- [OCCT] copied $(ls "$OCCT_DIR/jniLibs/$ABI"/*.so | wc -l) shared libs from $LIB_SRC ---"
        ls -la "$OCCT_DIR/jniLibs/$ABI"/libTK*.so | head -20
    else
        echo "ERROR: no libTK*.so found under OCCT build dir" >&2
        exit 1
    fi
# Public headers are generated + gathered into the build dir's inc/ tree.
    HDR_SRC="$(find . -type d -name opencascade | head -1)"
    if [ -z "$HDR_SRC" ]; then
        echo "ERROR: no inc/opencascade headers found under OCCT build dir" >&2
        exit 1
    fi
    mkdir -p "$OCCT_DIR/include/$ABI"
    cp -r "$HDR_SRC"/. "$OCCT_DIR/include/$ABI/"
    echo "--- [OCCT] copied headers from $HDR_SRC to $OCCT_DIR/include/$ABI ---"
    find "$OCCT_DIR/include/$ABI" -type f -name '*.hxx' | sort | head -30
    # OCCT's generated inc/opencascade tree contains Forwarding headers (e.g.
    # STEPCAFControl_Reader.hxx) whose whole content is a single line:
    #   #include "/tmp/build_android_deps/OCCT/src/STEPCAFControl/STEPCAFControl_Reader.hxx"
    # i.e. an ABSOLUTE path baked in at OCCT configure/build time. That path does
    # not exist on the separate runner that compiles libslic3r.so (the OCCT work
    # dir is per-machine and cleaned up), so any such stub fails to resolve there
    # ("fatal error: 'STEPCAFControl_Reader.hxx' file not found at :1:10").
    # Fix: shadow every forwarding stub with the REAL header content from the
    # OCCT source tree (src/<Toolkit>/<Class>.hxx). OCCT source headers include
    # each other by flat filename via -I, so flattening them into the same dir is
    # exactly how OCCT's own inc/ directory is consumed.
    SRC_HDR_ROOT="../src"
    if [ -d "$SRC_HDR_ROOT" ]; then
        # Fold the full OCCT header preparation INTO the build (not a post-hoc
        # patch): flatten EVERY source header (.hxx/.h/.lxx/.gxx/.pxx) over the
        # staged inc/ to shadow the /tmp absolute-path forwarding stubs, then
        # fail the build if any stub survives. This is the same logic the CI
        # workflow's post-build "Stage complete OCCT headers" step runs, so the
        # build now produces the final, correct header layout natively.
        export ABI
        # build_occt_headers.sh resolves OCCT_SRC = $WORK_DIR/OCCT. From inside
        # OCCT/build-android, the scratch root is /tmp/build_android_deps.
        export WORK_DIR="${WORK_DIR:-/tmp/build_android_deps}"
        chmod +x "$SCRIPT_DIR/build_occt_headers.sh"
        "$SCRIPT_DIR/build_occt_headers.sh"
    else
        echo "--- [OCCT] WARNING: source header tree $SRC_HDR_ROOT not found ---" >&2
    fi
    cd ../..
    echo "--- [OCCT] built and copied! ---"
}

build_gmp_mpfr() {
    echo "--- [GMP/MPFR] Building GMP + MPFR from source (Android shared libs) ---"
    # libslic3r.so links libgmp/libgmpxx/libmpfr as dynamic DT_NEEDED deps, so
    # they MUST be real Android shared libraries packaged into the APK. They can
    # no longer be vendored as stale prebuilts; compile them here. GMP first
    # (MPFR is built against GMP's headers/libs).
    TOOLBIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
    SYSROOT="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
    HOST_PREFIX="aarch64-linux-android${API_LEVEL}"
    # CMake imports these as SHARED from engine/src/main/jniLibs/${ABI}
    # and pulls headers from engine/src/main/jniImports/gmp/include/${ABI}.
    JNI_SO_DIR="$ROOT/engine/src/main/jniLibs/$ABI"
    GMP_HDR_DIR="$JNI_IMPORTS_DIR/gmp/include/$ABI"
    STAGE="$(pwd)/gmp-mpfr-stage"
    rm -rf "$STAGE"
    mkdir -p "$STAGE"

    export CC="$TOOLBIN/$HOST_PREFIX-clang"
    export CXX="$TOOLBIN/$HOST_PREFIX-clang++"
    export AR="$TOOLBIN/llvm-ar"
    export RANLIB="$TOOLBIN/llvm-ranlib"
    export NM="$TOOLBIN/llvm-nm"
    export STRIP="$TOOLBIN/llvm-strip"
    export CFLAGS="-O3 -fPIC --sysroot=$SYSROOT"
    export CXXFLAGS="-O3 -fPIC --sysroot=$SYSROOT"
    export LDFLAGS="--sysroot=$SYSROOT"

    # --- GMP -----------------------------------------------------------------
    # Hermetic source: tarballs are vendored in engine/vendor/ (sha256-pinned
    # in SHA256SUMS, corroborated across independent mirrors). No network
    # needed; mirrors below are a fallback only if vendor/ is absent.
    if [ ! -d "gmp-6.2.1" ]; then
        if [ -f "$ROOT/engine/vendor/gmp-6.2.1.tar.xz" ] \
           && grep "gmp-6.2.1.tar.xz" "$ROOT/engine/vendor/SHA256SUMS" \
              | (cd "$ROOT/engine/vendor" && sha256sum -c --status -); then
            echo "--- [GMP/MPFR] using vendored gmp-6.2.1.tar.xz (sha verified) ---"
            cp "$ROOT/engine/vendor/gmp-6.2.1.tar.xz" gmp.tar.xz
        else
            # shellcheck disable=SC2086
            fetch gmp.tar.xz $GMP_URLS || return 1
        fi
        tar xf gmp.tar.xz || return 1
    fi
    cd gmp-6.2.1 || return 1
    # Scope ABI=64 to this configure: the outer script exports ABI=arm64-v8a,
    # which GMP's configure misreads as a 32/64 selection ("arm64-v8a is not
    # among 64 32"). aarch64 => 64-bit, so pin ABI=64 for the child process.
    env ABI=64 ./configure --host=$HOST_PREFIX --prefix="$STAGE" --disable-assembly \
        --enable-shared --disable-static --enable-cxx || return 1
    make -j"$N_CORES" || return 1
    make install || return 1
    cd ..

    # --- MPFR (against the just-installed GMP) -------------------------------
    if [ ! -d "mpfr-4.2.0" ]; then
        if [ -f "$ROOT/engine/vendor/mpfr-4.2.0.tar.xz" ] \
           && grep "mpfr-4.2.0.tar.xz" "$ROOT/engine/vendor/SHA256SUMS" \
              | (cd "$ROOT/engine/vendor" && sha256sum -c --status -); then
            echo "--- [GMP/MPFR] using vendored mpfr-4.2.0.tar.xz (sha verified) ---"
            cp "$ROOT/engine/vendor/mpfr-4.2.0.tar.xz" mpfr.tar.xz
        else
            # shellcheck disable=SC2086
            fetch mpfr.tar.xz $MPFR_URLS || return 1
        fi
        tar xf mpfr.tar.xz || return 1
    fi
    cd mpfr-4.2.0 || return 1
    env ABI=64 ./configure --host=$HOST_PREFIX --prefix="$STAGE" \
        --enable-shared --disable-static \
        --with-gmp-include="$STAGE/include" --with-gmp-lib="$STAGE/lib" || return 1
    make -j"$N_CORES" || return 1
    make install || return 1
    cd ..

    # --- copy into the Android source tree ------------------------------------
    mkdir -p "$JNI_SO_DIR" "$GMP_HDR_DIR"
    cp "$STAGE/lib/libgmp.so" "$STAGE/lib/libgmpxx.so" "$STAGE/lib/libmpfr.so" "$JNI_SO_DIR/" || return 1
    cp "$STAGE/include/gmp.h"  "$STAGE/include/gmpxx.h"  "$GMP_HDR_DIR/"
    # mpfr.h is REQUIRED (CGAL includes it); copy it unconditionally so a
    # failure breaks the build loudly. mpf2mpfr.h only exists in older MPFR
    # releases, so its copy stays best-effort — it must never share a cp
    # invocation with mpfr.h (one missing source voids the whole copy).
    cp "$STAGE/include/mpfr.h" "$GMP_HDR_DIR/"
    cp "$STAGE/include/mpf2mpfr.h" "$GMP_HDR_DIR/" 2>/dev/null || true
    echo "--- [GMP/MPFR] copied .so -> $JNI_SO_DIR; headers -> $GMP_HDR_DIR ---"
}

# Launch the requested builds. Each dep can be built alone (so a CI job can
# run it on its own dedicated runner, eliminating CPU contention) or all three
# together (backward-compatible default).
TARGET="${1:-all}"

STATUS=0
case "$TARGET" in
  all)
    build_tbb   > /tmp/build_android_deps_tbb.log   2>&1 &
    PID_TBB=$!
    build_boost > /tmp/build_android_deps_boost.log 2>&1 &
    PID_BOOST=$!
    build_occt  > /tmp/build_android_deps_occt.log  2>&1 &
    PID_OCCT=$!
    build_gmp_mpfr > /tmp/build_android_deps_gmp.log  2>&1 &
    PID_GMP=$!

    for SPEC in "TBB:$PID_TBB:/tmp/build_android_deps_tbb.log" \
                "Boost:$PID_BOOST:/tmp/build_android_deps_boost.log" \
                "OCCT:$PID_OCCT:/tmp/build_android_deps_occt.log" \
                "GMP/MPFR:$PID_GMP:/tmp/build_android_deps_gmp.log"; do
        NAME="${SPEC%%:*}"; REST="${SPEC#*:}"
        PID="${REST%%:*}"; LOG="${REST#*:}"
        if wait "$PID"; then
            echo "=== $NAME succeeded ==="
        else
            echo "=== $NAME FAILED (exit $?) - see $LOG ==="
            STATUS=1
        fi
    done
    ;;
  tbb)   build_tbb   || { echo "=== TBB FAILED ==="; STATUS=1; } ;;
  boost) build_boost || { echo "=== Boost FAILED ==="; STATUS=1; } ;;
  occt)  build_occt  || { echo "=== OCCT FAILED ==="; STATUS=1; } ;;
  gmp)   build_gmp_mpfr || { echo "=== GMP/MPFR FAILED ==="; STATUS=1; } ;;
  *)
    echo "ERROR: unknown target '$TARGET' (expected all|tbb|boost|occt|gmp)" >&2
    exit 2
    ;;
esac

if [ "$STATUS" -ne 0 ]; then
    echo "======================================================================="
    echo " ERROR: dependency build failed (target='$TARGET')."
    echo "======================================================================="
    if [ "$TARGET" = "all" ]; then
        # Surface the failing build's log (and any "Error" lines) to stdout so
        # the GitHub Actions log and uploaded artifacts capture the root cause.
        for LOG in /tmp/build_android_deps_tbb.log \
                   /tmp/build_android_deps_boost.log \
                   /tmp/build_android_deps_occt.log \
                   /tmp/build_android_deps_gmp.log; do
            [ -f "$LOG" ] || continue
            if grep -qiE 'error:|fatal|Error [0-9]|No rule to make|undefined reference' "$LOG"; then
                echo "----- last 60 lines of $LOG -----"
                tail -60 "$LOG"
            fi
        done
    fi
    exit 1
fi

echo "======================================================================="
echo " DONE!"
echo " All native libraries have been compiled and staged under engine/src/main/"
echo " (jniImports/boost, jniImports/oneTBB, jniImports/gmp, occt/, jniLibs/)."
echo " You can now compile the C++ libslic3r engine using:"
echo " cmake -S engine -B engine/build <android-toolchain-flags> && cmake --build engine/build --target slic3r"
echo " then assemble the APK with:"
echo " ./gradlew assembleDebug -PskipNativeRebuild=true"
echo "======================================================================="
