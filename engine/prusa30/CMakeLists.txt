# FlashForge Farm — PrusaSlicer 3.0 headless engine build (integration seed).
#
# This is the entry point that replaces the Orca engine/ build. It does NOT
# vendor the upstream source: `fetch_prusaslicer.sh` (see ./fetch_prusaslicer.sh)
# must clone/pin the 3.0 tree into PRUSA30_SRC before configure, and the
# upstream tree's own `deps/` cmake resolves third-party libs (Boost/CGAL/OCCT/
# OpenVDB/Lua/fmt/spdlog/json — exact pins recorded in ./deps-manifest.json).
#
# Build now (CI-validated; there is no local NDK on the dev device):
#   scripts/... -> cmake -S engine/prusa30 -B build/prusa30 \
#       -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
#       -DANDROID_ABI=arm64-v8a -DANDROID_STL=c++_shared -DANDROID_PLATFORM=android-23 \
#       -DSLIC3R_GUI=OFF -DCMAKE_BUILD_TYPE=Release \
#       -DPRUSA30_SRC=<path from fetch_prusaslicer.sh>
#
# This is the FIRST cut. It is expected to fail CI configure/build until:
#   - deps are staged for NDK (deps-manifest.json + build_all_deps equivalent),
#   - farm_driver.cpp lands (the headless slice driver replacing slic3r-app-cli).
# Each failure is driven to green via PRs; it is intentionally out of the app's
# build path until `engine/` (Orca) is flipped.

cmake_minimum_required(VERSION 3.16)
project(farm_prusaslicer30 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Headless only: never build the GUI app shell, render, wx, platform, etc.
set(SLIC3R_GUI OFF CACHE BOOL "" FORCE)
set(SLIC3R_BUILD_TESTS OFF CACHE BOOL "" FORCE)
# SLIC3R_STATIC stays at upstream default (ON): Boost/TBB link statically into
# a single DSO, which is what we want for the Android NDK.

# Fetched upstream tree. The fetch script writes here; CI sets this explicitly.
set(PRUSA30_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../build/prusaslicer-src" CACHE PATH
    "Fetched PrusaSlicer 3.0 source tree (see fetch_prusaslicer.sh)")
if(NOT EXISTS "${PRUSA30_SRC}/src/CMakeLists.txt")
    message(FATAL_ERROR
        "PrusaSlicer 3.0 source not present at ${PRUSA30_SRC}. "
        "Run engine/prusa30/fetch_prusaslicer.sh first.")
endif()

# Stage the upstream tree. Its root CMakeLists adds bundled_deps/ + src/ modules;
# our 0001 patch makes the non-GUI branch of src/CMakeLists.txt non-fatal.
# The headless module set (slic3r-base/domain/biz-*/gcode-reader/libpgcode/
# jthread/libslic3r/arrange/lua) is added unconditionally by upstream src/.
add_subdirectory("${PRUSA30_SRC}" "${CMAKE_BINARY_DIR}/prusaslicer" EXCLUDE_FROM_ALL)

# Our engine DSO: the headless slice driver + JNI bridge.
add_library(farm_prusa30 SHARED
    farm_driver.cpp
)

# Link the 3.0 headless core. `libslic3r` is a STATIC lib upstream; link it and
# its transitive PUBLIC deps (slic3r-domain, slic3r-gcode-reader,
# slic3r-biz-cgal-algorithms, ...).
#
# NOTE: the config/model-load glue the driver uses
# (Slic3r/Biz/Config/ConfigLoad.hpp, Slic3r/Biz/FileLoadingLogic.hpp) lives in
# the GUI-gated `slic3r-shared` module upstream — headless-buildable only after
# the upstream "slic3r-shared split" lands. Until then `slic3r-shared` must be
# added to the headless module set (see PRUSASLICER30_MIGRATION.md).
target_link_libraries(farm_prusa30 PRIVATE libslic3r slic3r-shared)