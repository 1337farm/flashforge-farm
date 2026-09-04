# FarmApp — thin JNI bridge around the shared OrcaSlicer engine.
#
# The engine (libslic3r C++ source + its full header tree) lives in the
# top-level engine/ folder of this repository, cleanly separated from the
# Android app. Native deps (Boost/oneTBB/OCCT/GMP-MPFR) are built from source
# (see scripts/build_all_deps_android.sh and the native-engine-build CI
# workflow) and staged into engine/src/main/ before the engine compiles.
# There is no dependency on a separate engine or deps release repo.
#
# This build compiles this app's JNI bridge (farm_native.cpp) as libfarm.so,
# linked against the engine's libslic3r.so built from engine/ in the same
# repo. All Slic3r / Boost / TBB / OCCT / GMP-MPFR symbols that
# farm_native.cpp needs are resolved at runtime by libslic3r.so and the libs
# it loads (which is why libfarm links against libslic3r.so + JNI log only).
#
# Engine layout (top-level engine/ folder):
#   src/main/jni/           <- engine C++ source + headers (compiled from here;
#                              the app resolves headers against this same tree,
#                              so bridge and .so are always in ABI lockstep)
#   src/main/jniImports/    <- staged dep headers (boost, oneTBB, gmp)
#   src/main/occt/          <- staged OCCT headers
#   output/<ABI>/libslic3r.so <- engine build output (imported below)
#
# Native direct dependencies for COMPILATION only (headers, under engine/):
#   engine/src/main/jniImports/boost/include, .../oneTBB/include
#   engine/src/main/jniImports/gmp/include/${ABI}
#   engine/src/main/occt/include/${ABI}

cmake_minimum_required(VERSION 3.10)
project(FarmApp)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ENGINE_ROOT ${CMAKE_SOURCE_DIR}/../engine)
set(ENGINE_JNI ${ENGINE_ROOT}/src/main/jni)
set(ENGINE_OUTPUT ${ENGINE_ROOT}/output)
set(SRC ${CMAKE_SOURCE_DIR}/src/main)
set(JNI_IMPORTS ${ENGINE_ROOT}/src/main/jniImports)
set(OCCT_ROOT ${ENGINE_ROOT}/src/main/occt)

# --- the OrcaSlicer engine (libslic3r.so built from engine/ in this repo) --
add_library(slic3r SHARED IMPORTED)
set_target_properties(slic3r PROPERTIES IMPORTED_LOCATION
        ${ENGINE_OUTPUT}/${ANDROID_ABI}/libslic3r.so)

# --- this app's JNI bridge -------------------------------------------------------
# libfarm compiles the app's own JNI bridge (farm_native.cpp) PLUS the generic
# render-layer sources that the engine publishes in its source tree but does
# NOT bake into libslic3r.so (GLModel / GLShader). These render symbols
# (GLModel::*, GLShaderProgram::*, stilized_arrow) are referenced by
# farm_native.cpp and must therefore be compiled into libfarm itself.
add_library(farm SHARED
        src/main/jni/farm/farm_native.cpp
        src/main/jni/farm/farm_crashdump.cpp
        ${ENGINE_JNI}/render/GLModel.cpp
        ${ENGINE_JNI}/render/GLShader.cpp)

# Compile against the engine's own source tree (ABI-lockstep) + staged deps.
# The engine keeps its src/main/jni tree as the single header source, so
# generic angle-bracket includes used across the engine headers (e.g. <expat.h>,
# <nlopt.h>, <miniz.h>, <clipper/clipper.hpp>, <agg/...>) resolve via the base
# jni root AND via each library's own subdir. Collect every subdir so
# flattened header includes (<expat.h> -> jni/expat/expat.h) are satisfied.
file(GLOB ENGINE_INCLUDE_SUBDIRS "${ENGINE_JNI}/*/")
# CGAL ships a stddef.h shim that hard-errors if <CGAL> is itself placed on the
# include path; it must be reached only as <CGAL/...> from the base root. Drop it
# (and any sibling the engine excludes) so the flattened roots stay safe.
list(FILTER ENGINE_INCLUDE_SUBDIRS EXCLUDE REGEX "/CGAL/?$")
target_include_directories(farm PRIVATE
        ${ENGINE_JNI}                       # libslic3r/, bbl/, render/, expat/, clipper/, cereal/, ...
        ${ENGINE_INCLUDE_SUBDIRS}               # flattened sys-style includes (<expat.h>, <nlopt.h>, ...)
        ${ENGINE_JNI}/render                # flat render headers: GLModel.hpp, GLShader.hpp, bed_utils.hpp
        ${ENGINE_JNI}/libvgcode/include     # vgcode flat include: Viewer.hpp
        ${ENGINE_JNI}/libigl                # <igl/unproject.h>, <igl/copyleft/cgal/...>
        ${ENGINE_JNI}/libnest2d/include     # <libnest2d/...>
        ${JNI_IMPORTS}/oneTBB/include           # <tbb/...>, <oneapi/tbb/...>
        ${JNI_IMPORTS}/boost/include            # <boost/...>
        ${JNI_IMPORTS}/gmp/include/${ANDROID_ABI}  # <gmp.h>, <gmpxx.h>, <mpfr.h>
        ${OCCT_ROOT}/include/${ANDROID_ABI}     # OCCT headers
)

target_compile_options(farm PRIVATE -fsigned-char)
target_compile_definitions(farm PRIVATE
        -DUSE_TBB -DTBB_USE_CAPTURED_EXCEPTION=0 -DLIBNEST2D_GEOMETRIES_libslic3r
        -DLIBNEST2D_OPTIMIZER_nlopt -DLIBNEST2D_THREADING_tbb -DLIBNEST2D_STATIC
        -DENABLE_OPENGL_ES -DSLIC3R_OPENGL_ES -DUSE_CPP11_REGEX
        -DSLIC3R_VERSION=${SLIC3R_VERSION} -DSLIC3R_BUILD_ID=${SLIC3R_BUILD_ID})

# libfarm is a shared lib: any Boost/TBB/OCCT/GMP symbol it references resolves
# from libslic3r.so and the dependencies it loads. Link the engine + JNI log.
find_library(log-lib log)
find_library(GLESv2-lib GLESv2)
find_library(GLESv3-lib GLESv3)
find_library(EGL-lib EGL)
# libunwind.a provides _Unwind_Backtrace, used by the native crash handler in
# farm_crashdump.cpp to dump a stack trace to a file on SIGSEGV/SIGABRT/... .
# It ships with the NDK clang toolchain under lib64/clang/<ver>/lib/linux/<abi>/.
file(GLOB UNWIND_CANDIDATES
        ${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/*/lib64/clang/*/lib/linux/${ANDROID_ABI}/libunwind.a)
list(LENGTH UNWIND_CANDIDATES UNWIND_COUNT)
if(UNWIND_COUNT GREATER 0)
    list(GET UNWIND_CANDIDATES 0 UNWIND_ARCHIVE)
    message(STATUS "Using libunwind: ${UNWIND_ARCHIVE}")
else()
    set(UNWIND_ARCHIVE "")
    message(WARNING "libunwind.a not found; native crash backtrace will be disabled")
endif()
target_link_libraries(farm PRIVATE
        slic3r
        ${log-lib}
        ${GLESv2-lib}
        ${GLESv3-lib}
        ${EGL-lib}
        m
        dl
        ${UNWIND_ARCHIVE}
)
