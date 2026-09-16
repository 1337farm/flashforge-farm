include(CheckIncludeFiles)
include(CheckFunctionExists)
include(CheckTypeSize)
include(CheckCCompilerFlag)
include(CheckCXXSymbolExists)
include(CheckCXXCompilerFlag)
include(CheckLibraryExists)
include(GenerateExportHeader)
include(CheckCSourceCompiles)

cmake_minimum_required(VERSION 3.4.1)
project(libslic3r-engine C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wno-old-style-cast -Wno-reserved-id-macro -Wno-c++98-compat-pedantic -funwind-tables -ffunction-sections -fdata-sections -Wl,--no-merge-exidx-entries")
add_compile_options(-fsigned-char)

# Suppress all warnings
add_definitions(-w)
add_definitions(-DNDEBUG)
# Use staged fmt 12.1.0 everywhere: without this spdlog falls back to its
# bundled (older) fmt and the two clash (locale_ref).
add_definitions(-DSPDLOG_FMT_EXTERNAL)
# Mirror upstream json build (deps/+json: JSON_ImplicitConversions=OFF):
# v3.12.0's optional support resolves only with implicit conversions off.
add_definitions(-DJSON_USE_IMPLICIT_CONVERSIONS=0)
# Mirror upstream default SLIC3R_YAML=ryml (Yaml.hpp #errors without one).
add_definitions(-DSLIC3R_YAML_RYML)
add_definitions(-DSLIC3R_VERSION=${SLIC3R_VERSION})
add_definitions(-DSLIC3R_BUILD_ID=${SLIC3R_BUILD_ID})

set(jni_imports ${CMAKE_SOURCE_DIR}/src/main/jniImports)
set(jni_libs ${CMAKE_SOURCE_DIR}/src/main/jniLibs)

# Boost
set(BOOST_VERSION "1_85")
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(BOOST_ARCH "a64")
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(BOOST_ARCH "a32")
elseif (${ANDROID_ABI} STREQUAL "x86_64")
    set(BOOST_ARCH "x64")
elseif (${ANDROID_ABI} STREQUAL "x86")
    set(BOOST_ARCH "x32")
endif()

set(BOOST_LIBS atomic charconv chrono container context contract coroutine date_time exception fiber
        filesystem graph iostreams json log log_setup math_c99 math_c99f math_c99l math_tr1 math_tr1f
        math_tr1l nowide prg_exec_monitor program_options random regex serialization stacktrace_basic
        stacktrace_noop system test_exec_monitor thread timer type_erasure unit_test_framework url wave
        wserialization)

foreach (NAME IN LISTS BOOST_LIBS)
    add_library(boost_${NAME} STATIC IMPORTED)
    set_target_properties(boost_${NAME} PROPERTIES IMPORTED_LOCATION
            ${jni_imports}/boost/lib/${ANDROID_ABI}/lib/libboost_${NAME}-clang-mt-${BOOST_ARCH}-${BOOST_VERSION}.a)
endforeach()

# OCCT
set(OCCT_JNI_LIBS ${CMAKE_SOURCE_DIR}/src/main/occt/jniLibs)
# Toolkits libslic3r's OCCT wrapper actually calls into. The full OCCT dep build
# produces every toolkit; these are the ones whose symbols the engine references
# at link time (STEPCAFControl_Reader / STEPControl_* -> TKDESTEP, Font_* /
# Message_* -> TKService, Interface_Static -> TKXSBase). We pin OCCT to V7_9_0
# where the DataExchange STEP toolkits were consolidated: TKSTEP/TKXDE* were
# merged into TKDESTEP, so TKSTEP must NOT be listed here (it no longer exists).
set(OCCT_LIBS TKDESTEP TKXCAF TKVCAF TKCAF TKLCAF TKCDF TKV3d TKMesh TKXMesh TKXSBase TKService TKBO TKPrim TKHLR TKShHealing TKTopAlgo TKGeomAlgo TKGeomBase TKBRep TKG3d TKG2d TKMath TKernel)

# STATIC OCCT toolkits, resolved lazily at link end (see comment above):
# OCCT is built with -ffunction-sections/-fdata-sections (see
# scripts/build_all_deps_android.sh), so linking against those archives with
# --gc-sections dead-code-eliminates every toolkit bit the engine never calls.
# The .a are link-time-only inputs: all reachable OCCT code becomes part of
# libslic3r.so — NO libTK*.so is packaged or loaded by the app anymore.
foreach (NAME IN LISTS OCCT_LIBS)
    add_library(occt_${NAME} STATIC IMPORTED)
    set_target_properties(occt_${NAME} PROPERTIES IMPORTED_LOCATION
            ${OCCT_JNI_LIBS}/${ANDROID_ABI}/lib${NAME}.a)
endforeach()

list(TRANSFORM OCCT_LIBS PREPEND "occt_")
# All OCCT toolkits are mutual PRIVATE/interface deps (TKTopAlgo -> TKGeomAlgo
# -> TKGeomBase etc.). Declare no direct PRIVATE link ordering (the archives are
# resolved lazily at the final link of libslic3r.so by the --start-group flag
# added to target_link_options), so multi-symbol dependency chains between
# toolkits can never be missed by libslic3r's own link line.


add_library(tbb STATIC IMPORTED)
set_target_properties(tbb PROPERTIES IMPORTED_LOCATION
        ${jni_imports}/oneTBB/lib/${ANDROID_ABI}/libtbb.a)

add_library(tbbmalloc STATIC IMPORTED)
set_target_properties(tbbmalloc PROPERTIES IMPORTED_LOCATION
        ${jni_imports}/oneTBB/lib/${ANDROID_ABI}/libtbbmalloc.a)

# oneTBB 2021 for the 3.0 core: upstream uses tbb::filter<T, U> (a template),
# which classic 2019 TBB lacks (non-template base there). CI restores the
# headless oneTBB v2021.12 staging at prusa30/jniImports/tbb; prefer it when
# present so headers and archives stay in lockstep, else keep the dep release.
file(GLOB TBB_2021_INCLUDE ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/tbb/include)
file(GLOB TBB_2021_LIB ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/tbb/lib*/libtbb.a)
file(GLOB TBBMALLOC_2021_LIB ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/tbb/lib*/libtbbmalloc.a)
if(TBB_2021_INCLUDE AND TBB_2021_LIB)
    set_target_properties(tbb PROPERTIES IMPORTED_LOCATION ${TBB_2021_LIB})
endif()
if(TBBMALLOC_2021_LIB)
    set_target_properties(tbbmalloc PROPERTIES IMPORTED_LOCATION ${TBBMALLOC_2021_LIB})
endif()

add_library(gmp SHARED IMPORTED)
set_target_properties(gmp PROPERTIES IMPORTED_LOCATION
        ${jni_libs}/${ANDROID_ABI}/libgmp.so)

add_library(gmpxx SHARED IMPORTED)
set_target_properties(gmpxx PROPERTIES IMPORTED_LOCATION
        ${jni_libs}/${ANDROID_ABI}/libgmpxx.so)

add_library(mpfr SHARED IMPORTED)
set_target_properties(mpfr PROPERTIES IMPORTED_LOCATION
        ${jni_libs}/${ANDROID_ABI}/libmpfr.so)

include_directories(build/eigen-3.4.0/include)
include_directories(src/main/jni/libigl)
include_directories(src/main/occt/include/${ANDROID_ABI})

# Expat config
set(EXPAT_CONTEXT_BYTES 1024)
set(EXPAT_DTD ON)
set(XML_DEV_URANDOM ON)
configure_file(${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/expat/expat_config.h.cmake ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/expat/expat_config.h)

# NanoSVG config
configure_file(src/main/jni/nanosvg/nanosvg.h ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nanosvg/nanosvg.c)
configure_file(src/main/jni/nanosvg/nanosvgrast.h ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nanosvg/nanosvgrast.c)

# NLOpt config
set (NLOPT_MAJOR_VERSION "2")
set (NLOPT_MINOR_VERSION "5")
set (NLOPT_BUGFIX_VERSION "0")
set (NLOPT_VERSION_STRING ${NLOPT_MAJOR_VERSION}.${NLOPT_MINOR_VERSION}.${NLOPT_BUGFIX_VERSION})

check_include_file(dlfcn.h HAVE_DLFCN_H)
check_include_file(getopt.h HAVE_GETOPT_H)
check_include_file(unistd.h HAVE_UNISTD_H)
check_include_file(string.h HAVE_STRING_H)
check_include_file(strings.h HAVE_STRINGS_H)
check_include_file(inttypes.h HAVE_INTTYPES_H)
check_include_file(memory.h HAVE_MEMORY_H)
check_include_file(stdlib.h HAVE_STDLIB_H)
check_include_file(stdint.h HAVE_STDINT_H)
check_include_file(time.h HAVE_TIME_H)
check_include_file(sys/types.h HAVE_SYS_TYPES_H)
check_include_file(sys/stat.h HAVE_SYS_STAT_H)
check_include_file(sys/time.h HAVE_SYS_TIME_H)
if(HAVE_TIME_H AND HAVE_SYS_TIME_H)
    set(TIME_WITH_SYS_TIME TRUE)
endif()
check_function_exists(getpid HAVE_GETPID)
check_function_exists(syscall HAVE_GETTID_SYSCALL)
check_function_exists(isinf HAVE_ISINF)
check_function_exists(isnan HAVE_ISNAN)
check_function_exists(gettimeofday HAVE_GETTIMEOFDAY)
check_function_exists(qsort_r HAVE_QSORT_R)
check_function_exists(time HAVE_TIME)
check_function_exists(copysign HAVE_COPYSIGN)
check_type_size("uint32_t" SIZEOF_UINT32_T)
set(HAVE_UINT32_T ${SIZEOF_UINT32_T})
check_type_size("unsigned int" SIZEOF_UNSIGNED_INT)
check_type_size("unsigned long" SIZEOF_UNSIGNED_LONG)

check_library_exists("m" sqrt "" HAVE_LIBM)
if (HAVE_LIBM)
    set(M_LIBRARY m)
endif()
configure_file(${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/nlopt_config.h.in ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/nlopt_config.h IMMEDIATE)

if (NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/api/nlopt.f)
    file (WRITE ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/api/nlopt.f "")
    file (STRINGS ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/api/nlopt.h NLOPT_H_LINES REGEX "    NLOPT_[A-Z0-9_]+")
    set (i 0)
    foreach (NLOPT_H_LINE ${NLOPT_H_LINES})
        if (NOT NLOPT_H_LINE MATCHES "NLOPT_NUM_")
            string (REGEX REPLACE ".*NLOPT_([A-Z0-9_]+).*" "\\1" ENUM_STRING ${NLOPT_H_LINE})
            string (REGEX REPLACE ".*NLOPT_[A-Z0-9_]+ = (-?[0-9]+).*" "\\1" ENUM_VAL ${NLOPT_H_LINE})
            if (ENUM_VAL MATCHES "^-?[0-9]+$")
                set (i ${ENUM_VAL})
            endif ()
            set (ENUM_LINE "      integer NLOPT_${ENUM_STRING}\n      parameter (NLOPT_${ENUM_STRING}=${i})\n")
            file (APPEND ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/api/nlopt.f "${ENUM_LINE}")

            # https://public.kitware.com/Bug/print_bug_page.php?bug_id=8996
            if (i MATCHES "^-")
                math (EXPR i "1 ${i}")
            else ()
                math (EXPR i "${i} + 1")
            endif ()
        endif ()
    endforeach ()
endif ()

if (NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/api/nlopt.hpp)
    file (WRITE ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/api/nlopt.hpp "")
    file (STRINGS ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/api/nlopt-in.hpp NLOPT_HPP_LINES)
    foreach (NLOPT_HPP_LINE ${NLOPT_HPP_LINES})
        list(LENGTH NLOPT_HPP_LINE line_len)
        # handling trailing backlashes in "file (STRINGS" is a little tricky
        if (line_len VERSION_LESS 8)
            file (APPEND ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/api/nlopt.hpp "${NLOPT_HPP_LINE}\n")
        else ()
            set (prev_inst FALSE)
            foreach(NLOPT_HPP_SUBLINE ${NLOPT_HPP_LINE})
                # test is we need to add the eaten semicolon
                if (NLOPT_HPP_SUBLINE MATCHES "\\)$" OR NLOPT_HPP_SUBLINE MATCHES "return")
                    set (new_inst TRUE)
                else ()
                    set (new_inst FALSE)
                endif ()
                if (NOT prev_inst)
                    file (APPEND ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/api/nlopt.hpp "${NLOPT_HPP_SUBLINE}")
                    if (new_inst)
                        file (APPEND ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/api/nlopt.hpp ";")
                    endif ()
                    list (FIND NLOPT_HPP_LINE "${NLOPT_HPP_SUBLINE}" index)
                    math (EXPR index "${index} + 1")
                    list (LENGTH NLOPT_HPP_LINE total)
                    if (NOT index STREQUAL total)
                        file (APPEND ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/api/nlopt.hpp " \\")
                    endif ()
                    file (APPEND ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/api/nlopt.hpp "\n")
                endif ()
                set (prev_inst ${new_inst})
            endforeach ()
        endif ()
        if (NLOPT_HPP_LINE MATCHES "GEN_ENUMS_HERE")
            file (APPEND ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/api/nlopt.hpp "  enum algorithm {\n")
            file (STRINGS ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/api/nlopt.h NLOPT_H_LINES REGEX "    NLOPT_[A-Z0-9_]+")
            foreach (NLOPT_H_LINE ${NLOPT_H_LINES})
                string (REGEX REPLACE "NLOPT_" "" ENUM_LINE ${NLOPT_H_LINE})
                file (APPEND ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/api/nlopt.hpp "${ENUM_LINE}\n")
                if (NLOPT_H_LINE MATCHES "NLOPT_NUM_ALGORITHMS")
                    file (APPEND ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/api/nlopt.hpp "  };\n  enum result {\n")
                elseif (NLOPT_H_LINE MATCHES "NLOPT_MAXTIME_REACHED")
                    file (APPEND ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/nlopt/api/nlopt.hpp "  };\n")
                endif ()
            endforeach ()
        endif ()
    endforeach ()
endif ()

add_library(zlib
        STATIC
        src/main/jni/zlib/adler32.c
        src/main/jni/zlib/compress.c
        src/main/jni/zlib/crc32.c
        src/main/jni/zlib/deflate.c
        src/main/jni/zlib/gzclose.c
        src/main/jni/zlib/gzlib.c
        src/main/jni/zlib/gzread.c
        src/main/jni/zlib/gzwrite.c
        src/main/jni/zlib/inflate.c
        src/main/jni/zlib/infback.c
        src/main/jni/zlib/inftrees.c
        src/main/jni/zlib/inffast.c
        src/main/jni/zlib/trees.c
        src/main/jni/zlib/uncompr.c
        src/main/jni/zlib/zutil.c
)

add_library(libpng
        STATIC
        src/main/jni/libpng/png.c
        src/main/jni/libpng/pngerror.c
        src/main/jni/libpng/pngget.c
        src/main/jni/libpng/pngmem.c
        src/main/jni/libpng/pngpread.c
        src/main/jni/libpng/pngread.c
        src/main/jni/libpng/pngrio.c
        src/main/jni/libpng/pngrtran.c
        src/main/jni/libpng/pngrutil.c
        src/main/jni/libpng/pngset.c
        src/main/jni/libpng/pngtrans.c
        src/main/jni/libpng/pngwio.c
        src/main/jni/libpng/pngwrite.c
        src/main/jni/libpng/pngwtran.c
        src/main/jni/libpng/pngwutil.c
        src/main/jni/libpng/arm/arm_init.c
        src/main/jni/libpng/arm/filter_neon.S
        src/main/jni/libpng/arm/filter_neon_intrinsics.c
)

add_library(heatshrink
        STATIC
        src/main/jni/heatshrink/heatshrink_encoder.c
        src/main/jni/heatshrink/heatshrink_decoder.c
)

add_library(OCCTWrapper
        STATIC
        src/main/jni/occt_wrapper/OCCTWrapper.cpp)
target_include_directories(OCCTWrapper PRIVATE src/main/occt/include/${ANDROID_ABI})
generate_export_header(OCCTWrapper
        EXPORT_FILE_NAME ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/occt_wrapper/occtwrapper_export.h)

add_library(BGCode_core
        STATIC
        src/main/jni/LibBGCode/core/core.cpp
        src/main/jni/LibBGCode/core/core.hpp
        src/main/jni/LibBGCode/core/core_impl.hpp)
target_include_directories(BGCode_core PRIVATE src/main/jni/LibBGCode)
generate_export_header(BGCode_core
        EXPORT_FILE_NAME ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/LibBGCode/core/export.h)
target_link_libraries(BGCode_core heatshrink zlib)

add_library(BGCode_binarize
        STATIC
        src/main/jni/LibBGCode/binarize/binarize.cpp
        src/main/jni/LibBGCode/binarize/binarize.hpp
        src/main/jni/LibBGCode/binarize/meatpack.cpp
        src/main/jni/LibBGCode/binarize/meatpack.hpp)
target_include_directories(BGCode_binarize PRIVATE
        src/main/jni/LibBGCode
        src/main/jni)
generate_export_header(BGCode_binarize
        EXPORT_FILE_NAME ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/LibBGCode/binarize/export.h)
target_link_libraries(BGCode_binarize BGCode_core)

add_library(BGCode_convert
        STATIC
        src/main/jni/LibBGCode/convert/convert.cpp
        src/main/jni/LibBGCode/convert/convert.hpp)
target_include_directories(BGCode_convert PRIVATE
        src/main/jni/LibBGCode
        src/main/jni
        src/main/jniImports/boost/include)
generate_export_header(BGCode_convert
        EXPORT_FILE_NAME ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/LibBGCode/convert/export.h)
target_link_libraries(BGCode_convert BGCode_core boost_charconv boost_system)

# LibJPEG-Turbo
set(JPEG_LIB_VERSION 80)
set(SO_AGE 3)
set(DEFAULT_SO_MAJOR_VERSION 8)
set(DEFAULT_SO_MINOR_VERSION 2)
set(JPEG_LIB_VERSION_DECIMAL "8.0")

check_type_size("size_t" SIZE_T)
check_type_size("unsigned long" UNSIGNED_LONG)

# TODO: Build simd extensions
#macro(boolean_number var)
#    if(${var})
#        set(${var} 1 ${ARGN})
#    else()
#        set(${var} 0 ${ARGN})
#    endif()
#endmacro()
#
#if (${ANDROID_ABI} STREQUAL "arm64-v8a")
#    set(CPU_TYPE "arm64")
#    set(BITS 64)
#elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
#    set(CPU_TYPE "arm")
#    set(BITS 32)
#elseif (${ANDROID_ABI} STREQUAL "x86_64")
#    set(CPU_TYPE "x86_64")
#    set(BITS 64)
#elseif (${ANDROID_ABI} STREQUAL "x86")
#    set(CPU_TYPE "i386")
#    set(BITS 32)
#endif()
#
#add_subdirectory(src/main/jni/libjpeg-turbo/simd)
#
#if(NEON_INTRINSICS)
#    add_definitions(-DNEON_INTRINSICS)
#endif()

configure_file(${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/libjpeg-turbo/jconfig.h.in ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/libjpeg-turbo/jconfig.h IMMEDIATE)
configure_file(${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/libjpeg-turbo/jconfigint.h.in ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/libjpeg-turbo/jconfigint.h IMMEDIATE)
configure_file(${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/libjpeg-turbo/jversion.h.in ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/libjpeg-turbo/jversion.h IMMEDIATE)

set(JPEG16_SOURCES
        src/main/jni/libjpeg-turbo/jcapistd.c
        src/main/jni/libjpeg-turbo/jccolor.c
        src/main/jni/libjpeg-turbo/jcdiffct.c
        src/main/jni/libjpeg-turbo/jclossls.c
        src/main/jni/libjpeg-turbo/jcmainct.c
        src/main/jni/libjpeg-turbo/jcprepct.c
        src/main/jni/libjpeg-turbo/jcsample.c
        src/main/jni/libjpeg-turbo/jdapistd.c
        src/main/jni/libjpeg-turbo/jdcolor.c
        src/main/jni/libjpeg-turbo/jddiffct.c
        src/main/jni/libjpeg-turbo/jdlossls.c
        src/main/jni/libjpeg-turbo/jdmainct.c
        src/main/jni/libjpeg-turbo/jdpostct.c
        src/main/jni/libjpeg-turbo/jdsample.c
        src/main/jni/libjpeg-turbo/jutils.c)
set(JPEG12_SOURCES
        ${JPEG16_SOURCES}
        src/main/jni/libjpeg-turbo/jccoefct.c
        src/main/jni/libjpeg-turbo/jcdctmgr.c
        src/main/jni/libjpeg-turbo/jdcoefct.c
        src/main/jni/libjpeg-turbo/jddctmgr.c
        src/main/jni/libjpeg-turbo/jdmerge.c
        src/main/jni/libjpeg-turbo/jfdctfst.c
        src/main/jni/libjpeg-turbo/jfdctint.c
        src/main/jni/libjpeg-turbo/jidctflt.c
        src/main/jni/libjpeg-turbo/jidctfst.c
        src/main/jni/libjpeg-turbo/jidctint.c
        src/main/jni/libjpeg-turbo/jidctred.c
        src/main/jni/libjpeg-turbo/jquant1.c
        src/main/jni/libjpeg-turbo/jquant2.c)
set(JPEG_SOURCES
        ${JPEG12_SOURCES}
        src/main/jni/libjpeg-turbo/jcapimin.c
        src/main/jni/libjpeg-turbo/jchuff.c
        src/main/jni/libjpeg-turbo/jcicc.c
        src/main/jni/libjpeg-turbo/jcinit.c
        src/main/jni/libjpeg-turbo/jclhuff.c
        src/main/jni/libjpeg-turbo/jcmarker.c
        src/main/jni/libjpeg-turbo/jcmaster.c
        src/main/jni/libjpeg-turbo/jcomapi.c
        src/main/jni/libjpeg-turbo/jcparam.c
        src/main/jni/libjpeg-turbo/jcphuff.c
        src/main/jni/libjpeg-turbo/jctrans.c
        src/main/jni/libjpeg-turbo/jdapimin.c
        src/main/jni/libjpeg-turbo/jdatadst.c
        src/main/jni/libjpeg-turbo/jdatasrc.c
        src/main/jni/libjpeg-turbo/jdhuff.c
        src/main/jni/libjpeg-turbo/jdicc.c
        src/main/jni/libjpeg-turbo/jdinput.c
        src/main/jni/libjpeg-turbo/jdlhuff.c
        src/main/jni/libjpeg-turbo/jdmarker.c
        src/main/jni/libjpeg-turbo/jdmaster.c
        src/main/jni/libjpeg-turbo/jdphuff.c
        src/main/jni/libjpeg-turbo/jdtrans.c
        src/main/jni/libjpeg-turbo/jerror.c
        src/main/jni/libjpeg-turbo/jfdctflt.c
        src/main/jni/libjpeg-turbo/jmemmgr.c
        src/main/jni/libjpeg-turbo/jmemnobs.c)
add_library(jpeg12-static OBJECT ${JPEG12_SOURCES})
set_property(TARGET jpeg12-static PROPERTY COMPILE_FLAGS
        "-DBITS_IN_JSAMPLE=12")
add_library(jpeg16-static OBJECT ${JPEG16_SOURCES})
set_property(TARGET jpeg16-static PROPERTY COMPILE_FLAGS
        "-DBITS_IN_JSAMPLE=16")
add_library(jpeg-static STATIC ${JPEG_SOURCES} ${SIMD_TARGET_OBJECTS}
        ${SIMD_OBJS} $<TARGET_OBJECTS:jpeg12-static>
        $<TARGET_OBJECTS:jpeg16-static>)

# OpenVDB
set(OpenVDB_MAJOR_VERSION 8)
set(OpenVDB_MINOR_VERSION 2)
set(OpenVDB_PATCH_VERSION 0)
set(OpenVDB_VERSION "${OpenVDB_MAJOR_VERSION}.${OpenVDB_MINOR_VERSION}.${OpenVDB_PATCH_VERSION}")
set(OpenVDB_ABI ${OpenVDB_MAJOR_VERSION})
set(OPENVDB_ABI_VERSION_NUMBER ${OpenVDB_ABI} CACHE STRING [=[
Build for compatibility with version N of the OpenVDB Grid ABI, where N is 6, 7, 8 etc. (some newer features
will be disabled). If OPENVDB_BUILD_CORE is OFF, CMake attempts to query the installed vdb_print binary to
determine the ABI number. You may set this to force a given ABI number.]=] FORCE)
set(OPENVDB_USE_ZLIB 1)
math(EXPR OPENVDB_PACKED_VERSION "${OpenVDB_MAJOR_VERSION} << 24")
math(EXPR OPENVDB_PACKED_VERSION "${OPENVDB_PACKED_VERSION} | ((${OpenVDB_MINOR_VERSION} & 0xFF) << 16)")
math(EXPR OPENVDB_PACKED_VERSION "${OPENVDB_PACKED_VERSION} | (${OpenVDB_PATCH_VERSION} & 0xFFFF)")
configure_file(${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/openvdb/version.h.in ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/openvdb/version.h IMMEDIATE)
add_library(openvdb
        STATIC
        src/main/jni/openvdb/Grid.cc
        src/main/jni/openvdb/io/Archive.cc
        src/main/jni/openvdb/io/Compression.cc
        src/main/jni/openvdb/io/DelayedLoadMetadata.cc
        src/main/jni/openvdb/io/File.cc
        src/main/jni/openvdb/io/GridDescriptor.cc
        src/main/jni/openvdb/io/Queue.cc
        src/main/jni/openvdb/io/Stream.cc
        src/main/jni/openvdb/io/TempFile.cc
        src/main/jni/openvdb/math/Half.cc
        src/main/jni/openvdb/math/Maps.cc
        src/main/jni/openvdb/math/Proximity.cc
        src/main/jni/openvdb/math/QuantizedUnitVec.cc
        src/main/jni/openvdb/math/Transform.cc
        src/main/jni/openvdb/Metadata.cc
        src/main/jni/openvdb/MetaMap.cc
        src/main/jni/openvdb/openvdb.cc
        src/main/jni/openvdb/Platform.cc
        src/main/jni/openvdb/points/AttributeArray.cc
        src/main/jni/openvdb/points/AttributeArrayString.cc
        src/main/jni/openvdb/points/AttributeGroup.cc
        src/main/jni/openvdb/points/AttributeSet.cc
        src/main/jni/openvdb/points/StreamCompression.cc
        src/main/jni/openvdb/points/points.cc
        src/main/jni/openvdb/util/Formats.cc
        src/main/jni/openvdb/util/Util.cc)
target_include_directories(openvdb PRIVATE src/main/jni src/main/jniImports/boost/include ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/tbb/include src/main/jniImports/oneTBB/include)
set(MCUT_BUILD_AS_SHARED_LIB OFF)
set(MCUT_BUILD_TESTS OFF)
set(MCUT_BUILD_TUTORIALS OFF)
add_subdirectory(src/main/jni/mcut)

# Fetched PrusaSlicer 3.0 tree (engine/prusa30/fetch_prusaslicer.sh, PRUSA_REF pin).
# Defined before first use: add_library() below references ${PRUSA_SRC} sources.
set(PRUSA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/build/prusaslicer-src" CACHE PATH "Fetched 3.0 upstream tree")
# Generated 3.0 version header: upstream generates Slic3r/Version.hpp in its
# own build; our standalone target needs it too (fetched libslic3r.h).
set(PRUSA_GEN "${CMAKE_CURRENT_BINARY_DIR}/prusa-generated")
configure_file(${CMAKE_CURRENT_SOURCE_DIR}/cmake/Version.hpp.in ${PRUSA_GEN}/Slic3r/Version.hpp COPYONLY)

add_library(slic3r
        SHARED
        src/main/jni/semver/semver.c

        # Upstream 3.0 bundled admesh (2.x copy deleted; same TU set).
        ${PRUSA_SRC}/bundled_deps/admesh/admesh/connect.cpp
        ${PRUSA_SRC}/bundled_deps/admesh/admesh/normals.cpp
        ${PRUSA_SRC}/bundled_deps/admesh/admesh/shared.cpp
        ${PRUSA_SRC}/bundled_deps/admesh/admesh/stl_io.cpp
        ${PRUSA_SRC}/bundled_deps/admesh/admesh/stlinit.cpp
        ${PRUSA_SRC}/bundled_deps/admesh/admesh/util.cpp

        # Upstream 3.0 TU for CNumericLocalesSetter (used by admesh).
        ${PRUSA_SRC}/bundled_deps/localesutils/LocalesUtils.cpp

        src/main/jni/clipper/clipper_z.cpp
        src/main/jni/clipper/clipper_z.hpp

        src/main/jni/expat/xmlparse.c
        src/main/jni/expat/xmlrole.c
        src/main/jni/expat/xmltok.c

        src/main/jni/nanosvg/nanosvg.c

        src/main/jni/miniz/miniz.c

        src/main/jni/imgui/imconfig.h
        src/main/jni/imgui/imgui.cpp
        src/main/jni/imgui/imgui.h
        src/main/jni/imgui/imgui_demo.cpp
        src/main/jni/imgui/imgui_draw.cpp
        src/main/jni/imgui/imgui_internal.h
        src/main/jni/imgui/imgui_stdlib.cpp
        src/main/jni/imgui/imgui_stdlib.h
        src/main/jni/imgui/imgui_tables.cpp
        src/main/jni/imgui/imgui_widgets.cpp
        src/main/jni/imgui/imstb_rectpack.h
        src/main/jni/imgui/imstb_textedit.h
        src/main/jni/imgui/imstb_truetype.h

        src/main/jni/qoi/qoi.h
        src/main/jni/qoi/qoilib.c

        src/main/jni/qhull/libqhullcpp/Coordinates.cpp
        src/main/jni/qhull/libqhullcpp/PointCoordinates.cpp
        src/main/jni/qhull/libqhullcpp/Qhull.cpp
        src/main/jni/qhull/libqhullcpp/QhullFacet.cpp
        src/main/jni/qhull/libqhullcpp/QhullFacetList.cpp
        src/main/jni/qhull/libqhullcpp/QhullFacetSet.cpp
        src/main/jni/qhull/libqhullcpp/QhullHyperplane.cpp
        src/main/jni/qhull/libqhullcpp/QhullPoint.cpp
        src/main/jni/qhull/libqhullcpp/QhullPointSet.cpp
        src/main/jni/qhull/libqhullcpp/QhullPoints.cpp
        src/main/jni/qhull/libqhullcpp/QhullQh.cpp
        src/main/jni/qhull/libqhullcpp/QhullRidge.cpp
        src/main/jni/qhull/libqhullcpp/QhullSet.cpp
        src/main/jni/qhull/libqhullcpp/QhullStat.cpp
        src/main/jni/qhull/libqhullcpp/QhullUser.cpp
        src/main/jni/qhull/libqhullcpp/QhullVertex.cpp
        src/main/jni/qhull/libqhullcpp/QhullVertexSet.cpp
        src/main/jni/qhull/libqhullcpp/RboxPoints.cpp
        src/main/jni/qhull/libqhullcpp/RoadError.cpp
        src/main/jni/qhull/libqhullcpp/RoadLogEvent.cpp
        src/main/jni/qhull/libqhullcpp/Coordinates.h
        src/main/jni/qhull/libqhullcpp/functionObjects.h
        src/main/jni/qhull/libqhullcpp/PointCoordinates.h
        src/main/jni/qhull/libqhullcpp/Qhull.h
        src/main/jni/qhull/libqhullcpp/QhullError.h
        src/main/jni/qhull/libqhullcpp/QhullFacet.h
        src/main/jni/qhull/libqhullcpp/QhullFacetList.h
        src/main/jni/qhull/libqhullcpp/QhullFacetSet.h
        src/main/jni/qhull/libqhullcpp/QhullHyperplane.h
        src/main/jni/qhull/libqhullcpp/QhullIterator.h
        src/main/jni/qhull/libqhullcpp/QhullLinkedList.h
        src/main/jni/qhull/libqhullcpp/QhullPoint.h
        src/main/jni/qhull/libqhullcpp/QhullPoints.h
        src/main/jni/qhull/libqhullcpp/QhullPointSet.h
        src/main/jni/qhull/libqhullcpp/QhullQh.h
        src/main/jni/qhull/libqhullcpp/QhullRidge.h
        src/main/jni/qhull/libqhullcpp/QhullSet.h
        src/main/jni/qhull/libqhullcpp/QhullSets.h
        src/main/jni/qhull/libqhullcpp/QhullStat.h
        src/main/jni/qhull/libqhullcpp/QhullUser.h
        src/main/jni/qhull/libqhullcpp/QhullVertex.h
        src/main/jni/qhull/libqhullcpp/QhullVertexSet.h
        src/main/jni/qhull/libqhullcpp/RboxPoints.h
        src/main/jni/qhull/libqhullcpp/RoadError.h
        src/main/jni/qhull/libqhullcpp/RoadLogEvent.h

        src/main/jni/qhull/libqhull_r/global_r.c
        src/main/jni/qhull/libqhull_r/stat_r.c
        src/main/jni/qhull/libqhull_r/geom2_r.c
        src/main/jni/qhull/libqhull_r/poly2_r.c
        src/main/jni/qhull/libqhull_r/merge_r.c
        src/main/jni/qhull/libqhull_r/libqhull_r.c
        src/main/jni/qhull/libqhull_r/geom_r.c
        src/main/jni/qhull/libqhull_r/poly_r.c
        src/main/jni/qhull/libqhull_r/qset_r.c
        src/main/jni/qhull/libqhull_r/mem_r.c
        src/main/jni/qhull/libqhull_r/random_r.c
        src/main/jni/qhull/libqhull_r/usermem_r.c
#        src/main/jni/qhull/libqhull_r/userprintf_r.c
        src/main/jni/qhull/libqhull_r/io_r.c
        src/main/jni/qhull/libqhull_r/user_r.c
        src/main/jni/qhull/libqhull_r/accessors_r.c
        src/main/jni/qhull/libqhull_r/rboxlib_r.c
#        src/main/jni/qhull/libqhull_r/userprintf_rbox_r.c
        src/main/jni/qhull/libqhull_r/libqhull_r.h
        src/main/jni/qhull/libqhull_r/geom_r.h
        src/main/jni/qhull/libqhull_r/io_r.h
        src/main/jni/qhull/libqhull_r/mem_r.h
        src/main/jni/qhull/libqhull_r/merge_r.h
        src/main/jni/qhull/libqhull_r/poly_r.h
        src/main/jni/qhull/libqhull_r/qhull_ra.h
        src/main/jni/qhull/libqhull_r/qset_r.h
        src/main/jni/qhull/libqhull_r/random_r.h
        src/main/jni/qhull/libqhull_r/stat_r.h
        src/main/jni/qhull/libqhull_r/user_r.h

        src/main/jni/nlopt/algs/direct/DIRect.c
        src/main/jni/nlopt/algs/direct/direct_wrap.c
        src/main/jni/nlopt/algs/direct/DIRserial.c
        src/main/jni/nlopt/algs/direct/DIRsubrout.c
        src/main/jni/nlopt/algs/direct/direct-internal.h
        src/main/jni/nlopt/algs/direct/direct.h
        src/main/jni/nlopt/algs/cdirect/cdirect.c
        src/main/jni/nlopt/algs/cdirect/hybrid.c
        src/main/jni/nlopt/algs/cdirect/cdirect.h
        src/main/jni/nlopt/algs/praxis/praxis.c
        src/main/jni/nlopt/algs/praxis/praxis.h
        src/main/jni/nlopt/algs/luksan/plis.c
        src/main/jni/nlopt/algs/luksan/plip.c
        src/main/jni/nlopt/algs/luksan/pnet.c
        src/main/jni/nlopt/algs/luksan/mssubs.c
        src/main/jni/nlopt/algs/luksan/pssubs.c
        src/main/jni/nlopt/algs/luksan/luksan.h
        src/main/jni/nlopt/algs/crs/crs.c
        src/main/jni/nlopt/algs/crs/crs.h
        src/main/jni/nlopt/algs/mlsl/mlsl.c
        src/main/jni/nlopt/algs/mlsl/mlsl.h
        src/main/jni/nlopt/algs/mma/mma.c
        src/main/jni/nlopt/algs/mma/mma.h
        src/main/jni/nlopt/algs/mma/ccsa_quadratic.c
        src/main/jni/nlopt/algs/cobyla/cobyla.c
        src/main/jni/nlopt/algs/cobyla/cobyla.h
        src/main/jni/nlopt/algs/newuoa/newuoa.c
        src/main/jni/nlopt/algs/newuoa/newuoa.h
        src/main/jni/nlopt/algs/neldermead/nldrmd.c
        src/main/jni/nlopt/algs/neldermead/neldermead.h
        src/main/jni/nlopt/algs/neldermead/sbplx.c
        src/main/jni/nlopt/algs/auglag/auglag.c
        src/main/jni/nlopt/algs/auglag/auglag.h
        src/main/jni/nlopt/algs/bobyqa/bobyqa.c
        src/main/jni/nlopt/algs/bobyqa/bobyqa.h
        src/main/jni/nlopt/algs/isres/isres.c
        src/main/jni/nlopt/algs/isres/isres.h
        src/main/jni/nlopt/algs/slsqp/slsqp.c
        src/main/jni/nlopt/algs/slsqp/slsqp.h
        src/main/jni/nlopt/algs/esch/esch.c
        src/main/jni/nlopt/algs/esch/esch.h
        src/main/jni/nlopt/api/general.c
        src/main/jni/nlopt/api/options.c
        src/main/jni/nlopt/api/optimize.c
        src/main/jni/nlopt/api/deprecated.c
        src/main/jni/nlopt/api/nlopt-internal.h
        src/main/jni/nlopt/api/nlopt.h
        src/main/jni/nlopt/api/nlopt.f
        src/main/jni/nlopt/api/f77api.c
        src/main/jni/nlopt/api/f77funcs.h
        src/main/jni/nlopt/api/f77funcs_.h
        src/main/jni/nlopt/api/nlopt-in.hpp
        src/main/jni/nlopt/util/mt19937ar.c
        src/main/jni/nlopt/util/sobolseq.c
        src/main/jni/nlopt/util/soboldata.h
        src/main/jni/nlopt/util/timer.c
        src/main/jni/nlopt/util/stop.c
        src/main/jni/nlopt/util/nlopt-util.h
        src/main/jni/nlopt/util/redblack.c
        src/main/jni/nlopt/util/redblack.h
        src/main/jni/nlopt/util/qsort_r.c
        src/main/jni/nlopt/util/rescale.c
        # NLOpt CXX
        src/main/jni/nlopt/algs/stogo/global.cc
        src/main/jni/nlopt/algs/stogo/linalg.cc
        src/main/jni/nlopt/algs/stogo/local.cc
        src/main/jni/nlopt/algs/stogo/stogo.cc
        src/main/jni/nlopt/algs/stogo/tools.cc
        src/main/jni/nlopt/algs/stogo/global.h
        src/main/jni/nlopt/algs/stogo/linalg.h
        src/main/jni/nlopt/algs/stogo/local.h
        src/main/jni/nlopt/algs/stogo/stogo_config.h
        src/main/jni/nlopt/algs/stogo/stogo.h
        src/main/jni/nlopt/algs/stogo/tools.h

        src/main/jni/glu-libtess/src/dict-list.h
        src/main/jni/glu-libtess/src/dict.c
        src/main/jni/glu-libtess/src/dict.h
        src/main/jni/glu-libtess/src/geom.c
        src/main/jni/glu-libtess/src/geom.h
        src/main/jni/glu-libtess/src/gluos.h
        src/main/jni/glu-libtess/src/memalloc.c
        src/main/jni/glu-libtess/src/memalloc.h
        src/main/jni/glu-libtess/src/mesh.c
        src/main/jni/glu-libtess/src/mesh.h
        src/main/jni/glu-libtess/src/normal.c
        src/main/jni/glu-libtess/src/normal.h
        src/main/jni/glu-libtess/src/priorityq.c
        src/main/jni/glu-libtess/src/priorityq.h
        src/main/jni/glu-libtess/src/priorityq-heap.h
        src/main/jni/glu-libtess/src/priorityq-sort.h
        src/main/jni/glu-libtess/src/render.c
        src/main/jni/glu-libtess/src/render.h
        src/main/jni/glu-libtess/src/sweep.c
        src/main/jni/glu-libtess/src/sweep.h
        src/main/jni/glu-libtess/src/tess.c
        src/main/jni/glu-libtess/src/tess.h
        src/main/jni/glu-libtess/src/tessmono.c
        src/main/jni/glu-libtess/src/tessmono.h
        src/main/jni/glu-libtess/include/glu-libtess.h

        src/main/jni/libvgcode/include/ColorPrint.hpp
        src/main/jni/libvgcode/include/ColorRange.hpp
        src/main/jni/libvgcode/include/GCodeInputData.hpp
        src/main/jni/libvgcode/include/PathVertex.hpp
        src/main/jni/libvgcode/include/Types.hpp
        src/main/jni/libvgcode/include/Viewer.hpp
        src/main/jni/libvgcode/src/Bitset.hpp
        src/main/jni/libvgcode/src/Bitset.cpp
        src/main/jni/libvgcode/src/CogMarker.hpp
        src/main/jni/libvgcode/src/CogMarker.cpp
        src/main/jni/libvgcode/src/ColorPrint.cpp
        src/main/jni/libvgcode/src/ColorRange.cpp
        src/main/jni/libvgcode/src/ExtrusionRoles.hpp
        src/main/jni/libvgcode/src/ExtrusionRoles.cpp
        src/main/jni/libvgcode/src/GCodeInputData.cpp
        src/main/jni/libvgcode/src/Layers.hpp
        src/main/jni/libvgcode/src/Layers.cpp
        src/main/jni/libvgcode/src/OpenGLUtils.hpp
        src/main/jni/libvgcode/src/OpenGLUtils.cpp
        src/main/jni/libvgcode/src/OptionTemplate.hpp
        src/main/jni/libvgcode/src/OptionTemplate.cpp
        src/main/jni/libvgcode/src/PathVertex.cpp
        src/main/jni/libvgcode/src/Range.hpp
        src/main/jni/libvgcode/src/Range.cpp
        src/main/jni/libvgcode/src/SegmentTemplate.hpp
        src/main/jni/libvgcode/src/SegmentTemplate.cpp
        src/main/jni/libvgcode/src/Settings.hpp
        src/main/jni/libvgcode/src/Settings.cpp
        src/main/jni/libvgcode/src/Shaders.hpp
        src/main/jni/libvgcode/src/ShadersES.hpp
        src/main/jni/libvgcode/src/ToolMarker.hpp
        src/main/jni/libvgcode/src/ToolMarker.cpp
        src/main/jni/libvgcode/src/Types.cpp
        src/main/jni/libvgcode/src/Utils.hpp
        src/main/jni/libvgcode/src/Utils.cpp
        src/main/jni/libvgcode/src/Viewer.cpp
        src/main/jni/libvgcode/src/ViewerImpl.hpp
        src/main/jni/libvgcode/src/ViewerImpl.cpp
        src/main/jni/libvgcode/src/ViewRange.hpp
        src/main/jni/libvgcode/src/ViewRange.cpp

        # ISSUE-210 (PrusaSlicer 3.0 build swap): the vendored 2.x tree
        # src/main/jni/libslic3r/ was deleted (git rm). The 3.0 core is fetched
        # at build time by engine/prusa30/fetch_prusaslicer.sh (pinned PRUSA_REF)
        # into engine/build/prusaslicer-src and configured headless, mirroring
        # .github/workflows/prusa30-headless.yml:
        #   cmake -S engine/build/prusaslicer-src -B build/prusa30 -DSLIC3R_GUI=OFF
        # This target consumes the fetched tree's headless `libslic3r` CMake
        # target next (CI compile-fix loop); 2.x-only sources with no 3.0
        # counterpart stay listed as breaks in the issue — no shims invented.

        # libnest2d is NOT built here: its only in-tree user was the deleted
        # 2.x Arrange (its libslic3r backend needs 2.x ExPolygon). Arrange
        # returns via upstream slic3r-biz-arrange in #211.

        # compat/Orient.cpp is NOT built here: it serves the farm/app JNI
        # (emboss orientation) and needs the 3.0 Domain/Biz port (#211).
        # It compiles in the app farm target once ported.

        # engine_compat.cpp provides the single nanosvg implementation and
        # stub definitions for unvendored backends (draco/mcut).
        src/main/jni/engine_compat.cpp

#        $<TARGET_OBJECTS:simd>
)
target_compile_definitions(slic3r PUBLIC -DUSE_TBB -DTBB_USE_CAPTURED_EXCEPTION=0 -DLIBNEST2D_GEOMETRIES_libslic3r
        -DLIBNEST2D_OPTIMIZER_nlopt -DLIBNEST2D_THREADING_tbb -DLIBNEST2D_STATIC -DENABLE_OPENGL_ES -DSLIC3R_OPENGL_ES
        -DUSE_CPP11_REGEX)

# Bring in the fetched 3.0 headers' source-only implementation unit: symbol
# definitions for Domain::Model/ModelObject/ModelVolume et al are compiled in
# upstream `libslic3r`, not as headers, so our app needs the actual TU set.
# We add it unconditionally; CMake dedupes duplicate source entries by path.
file(GLOB SLIC3R_IMPL_CPP ${PRUSA_SRC}/src/libslic3r/src/libslic3r/*.cpp)
# Mirror upstream: src/libslic3r/CMakeLists.txt comments ArrangeHelper out
# (it includes the nonexistent libslic3r/MultipleBeds.hpp and nothing else
# references it), and GCodeSender is dead serial-port code nothing references
# (needs boost::noncopyable transitively + clashes with Android termios2);
# drop both so the glob builds exactly what upstream builds.
list(REMOVE_ITEM SLIC3R_IMPL_CPP
    ${PRUSA_SRC}/src/libslic3r/src/libslic3r/ArrangeHelper.cpp
    ${PRUSA_SRC}/src/libslic3r/src/libslic3r/GCodeSender.cpp)
# Upstream module implementations (Domain::Model, FileLoadingLogic, ...): the
# legacy glob covers only src/libslic3r. Glob the module trees too — none
# contain GUI code. Exclusions: other-OS platform files, the render test
# module, the standalone seqarrange tool, and the openssl-needing network TUs
# (nothing in the slice path references them; re-add with OpenSSL staging if
# the link ever demands them).
file(GLOB_RECURSE SLIC3R_MODULE_SRC
    ${PRUSA_SRC}/src/slic3r-domain/src/*.cpp
    ${PRUSA_SRC}/src/slic3r-shared/src/*.cpp
    ${PRUSA_SRC}/src/slic3r-base/src/*.cpp
    ${PRUSA_SRC}/src/slic3r-biz-algorithms/src/*.cpp
    ${PRUSA_SRC}/src/slic3r-biz-arrange/src/*.cpp
    ${PRUSA_SRC}/src/slic3r-biz-cgal-algorithms/src/*.cpp
    ${PRUSA_SRC}/src/slic3r-biz-parser/src/*.cpp
    ${PRUSA_SRC}/src/slic3r-gcode-reader/src/*.cpp
    ${PRUSA_SRC}/src/slic3r-platform/src/*.cpp
    ${PRUSA_SRC}/src/libpgcode/src/*.cpp)
# NOTE: libseqarrange excluded (needs z3++.h, absent); its only user is the
# excluded ArrangeHelper. biz-crypto excluded (needs OpenSSL headers).
# YamlAdapterLibfyaml.cpp + YamlAdapterYamlCpp.cpp excluded:
# SLIC3R_YAML_RYML is the selected backend (add_definitions above);
# upstream's ryml file set compiles only YamlAdapterRyml — the other
# adapters (libfyaml.h absent / yaml-cpp lib not built) are dead TUs the
# whole-archive link would otherwise force-resolve.
# PlatformInfo_emscripten.cpp excluded: unguarded duplicate definition of
# Slic3r::PlatformInfo (same as _unix.cpp); whole-archive link surfaces it —
# Android/bionic wants the _unix.cpp uname variant.
# EmbossJob.cpp re-added (was batch-excluded with the Plater-needing Emboss
# UI TUs): upstream compiles it in the unconditional headless file set and
# Biz/Format/SVG's load_svg calls Emboss::create_projection, defined there.
# slic3r-platform/src globbed for the same reason (upstream builds it
# headless): EmbossJob and other Biz TUs call PlatformServices, whose
# implementation lives there; the Slic3r/App/ regex still drops its
# canvas/animation half.
list(FILTER SLIC3R_MODULE_SRC EXCLUDE REGEX "Win32|win32|Mac\\.cpp|Slic3r/App/|sequential_decimator|HttpCurl\\.cpp|HttpCurlFactory\\.cpp|SHA256Linux\\.cpp|AppInstanceMessageHandlerLinux\\.cpp|AppInstanceMessageHandlerFactoryLinux\\.cpp|RemovableDriveMonitorLinux\\.cpp|RemovableDriveServiceLinux\\.cpp|TryCatchSignalSEH\\.cpp|Emboss/TextShapeProvider\\.cpp|Emboss/TextLines\\.cpp|Emboss/SurfaceDrag\\.cpp|Format/3mf/BuildTicket\\.cpp|Preset/Bundle\\.cpp|Preset/AbstractConfigManipulation\\.cpp|YamlAdapterLibfyaml\\.cpp|YamlAdapterYamlCpp\\.cpp|PlatformInfo_emscripten\\.cpp")
# CustomParametersHandling.cpp captures structured bindings in OpenMP
# regions, which NDK Clang rejects. Compile just this TU without OpenMP
# (pragmas ignored -> serial execution, same results).
set_source_files_properties(
    ${PRUSA_SRC}/src/libslic3r/src/libslic3r/CustomParametersHandling.cpp
    PROPERTIES COMPILE_OPTIONS -fno-openmp)
add_library(libslic3r_core STATIC ${SLIC3R_IMPL_CPP} ${SLIC3R_MODULE_SRC}
    # Vendored pugixml (see engine/prusa30/vendor/pugixml/README): upstream
    # 3mf/BuildTicket.cpp needs its symbols, not just headers.
    ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/vendor/pugixml/pugixml.cpp)
# Order the BGCode export headers (generate_export_header) before any core TU:
# upstream GCode.cpp pulls <binarize/binarize.hpp> -> <binarize/export.h>,
# which only exists once its target's prebuild step runs.
add_dependencies(libslic3r_core BGCode_core BGCode_binarize BGCode_convert)
target_include_directories(libslic3r_core PRIVATE
    ${PRUSA_SRC}/src/libslic3r/include
    ${PRUSA_SRC}/src/libslic3r/src
    ${PRUSA_SRC}/src/libseqarrange/include
    ${PRUSA_SRC}/bundled_deps
    ${PRUSA_SRC}/bundled_deps/admesh
    ${PRUSA_SRC}/bundled_deps/clipper/include
    ${PRUSA_SRC}/bundled_deps/semver
    ${PRUSA_SRC}/bundled_deps/localesutils
    ${PRUSA_SRC}/src/slic3r-domain/include
    ${PRUSA_SRC}/src/slic3r-base/include
    ${PRUSA_SRC}/src/slic3r-biz-algorithms/include
    ${PRUSA_SRC}/src/slic3r-biz-arrange/include
    ${PRUSA_SRC}/src/slic3r-shared/include
    ${PRUSA_SRC}/src/slic3r-render/include
    ${PRUSA_SRC}/src/slic3r-biz-cgal-algorithms/include
    ${PRUSA_SRC}/src/slic3r-biz-parser/include
    ${PRUSA_SRC}/src/slic3r-gcode-reader/include
    ${PRUSA_SRC}/src/slic3r-jthread/include
    ${PRUSA_SRC}/src/libpgcode/include
    ${PRUSA_SRC}/src/slic3r-platform/include
    ${PRUSA_SRC}/src/slic3r-domain/src
    ${PRUSA_SRC}/src/slic3r-shared/src
    ${PRUSA_SRC}/src/slic3r-base/src
    ${PRUSA_SRC}/src/slic3r-biz-algorithms/src
    ${PRUSA_SRC}/src/slic3r-biz-arrange/src
    ${PRUSA_SRC}/src/slic3r-biz-cgal-algorithms/src
    ${PRUSA_SRC}/src/slic3r-biz-parser/src
    ${PRUSA_SRC}/src/slic3r-gcode-reader/src
    ${PRUSA_SRC}/src/libpgcode/src
    ${PRUSA_SRC}/bundled_deps/slic3r-domain-types/include
    ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/tbb/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jniImports/oneTBB/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jniImports/boost/include
    ${PRUSA_GEN}
    ${CMAKE_CURRENT_SOURCE_DIR}/build/headers/eigen/include
    ${CMAKE_CURRENT_SOURCE_DIR}/build/headers/spdlog/include
    ${CMAKE_CURRENT_SOURCE_DIR}/build/headers/fmt/include
    ${CMAKE_CURRENT_SOURCE_DIR}/build/headers/cereal/include
    ${CMAKE_CURRENT_SOURCE_DIR}/build/headers/nlohmann_json/include
    ${CMAKE_CURRENT_SOURCE_DIR}/build/headers/sol2/include
    ${CMAKE_CURRENT_SOURCE_DIR}/build/headers/expected/include
    ${CMAKE_CURRENT_SOURCE_DIR}/build/headers/magic_enum/include
    ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/assert/include
    ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/nanosvg/include
    ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/rangev3/include
    ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/tracy
    ${PRUSA_SRC}/bundled_deps/ankerl
    ${PRUSA_SRC}/bundled_deps/stb_image
    ${PRUSA_SRC}/bundled_deps/stb_dxt
    ${PRUSA_SRC}/bundled_deps/libnest2d/include
    src/main/jni/libnest2d/include
    src/main/jni/LibBGCode
    src/main/jni/qhull
    src/main/jni/libigl
    ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/mixer/include
    ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/ryml/include
    ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/yamlcpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/vendor/pugixml
    ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/libdeflate/include
    ${PRUSA_SRC}/bundled_deps/int128
    ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jniImports/gmp/include/${ANDROID_ABI}
    src/main/jni/miniz
    src/main/jni/libpng
    src/main/jni/nlopt/api
    src/main/jni/expat
    src/main/jni/fast_float
    src/main/jni/glu-libtess/include
    src/main/jni/semver
    src/main/jni)

# libassert (staged fresh by CI into prusa30/jniImports/assert): upstream 3.0
# Assert.hpp needs its symbols at link time (slic3r-base links
# libassert::assert upstream). Glob the staged archives (libassert.a +
# libcpptrace.a) so the final .so is self-contained.
file(GLOB ASSERT_STATIC_LIBS ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/assert/lib/*.a)
file(GLOB MIXER_STATIC_LIB ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/mixer/lib*/libprusa_fdm_mixer.a)
file(GLOB LIBDEFLATE_STATIC_LIB ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/libdeflate/lib*/libdeflate.a)
# fmt 12.1.0 (SPDLOG_FMT_EXTERNAL): the staged headers only compile the
# fmt::v12 calls; the definitions live in the freshly built libfmt.a.
file(GLOB FMT_STATIC_LIB ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/fmtlib/lib*/libfmt.a)
# ryml 0.10.0 (SLIC3R_YAML_RYML): YamlAdapterRyml's c4::yml calls need the
# compiled rapidyaml + vendored c4core archives; c4core must link after
# libryml.a (archive order: it resolves ryml's own references).
file(GLOB RYML_STATIC_LIB ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/ryml/lib/libryml.a)
file(GLOB C4CORE_STATIC_LIB ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/ryml/lib/libc4core.a)
# --whole-archive around libslic3r_core: the farm JNI bridge (libfarm.so)
# links against libslic3r.so and resolves the 3.0 Slic3r::Domain/Biz API at
# link time. A plain archive pull would drag in only the members something
# inside the .so already references — every module only the app uses
# (Domain::Model, FileLoadingLogic, Biz::Cut, ...) never enters the link and
# the bridge fails with undefined symbols. The deleted 2.x build compiled
# its sources straight into the shared target, exporting everything;
# whole-archive restores exactly that.
target_link_libraries(slic3r PRIVATE
        -Wl,--whole-archive
        libslic3r_core
        -Wl,--no-whole-archive
        ${ASSERT_STATIC_LIBS} ${MIXER_STATIC_LIB} ${LIBDEFLATE_STATIC_LIB} ${FMT_STATIC_LIB}
        ${RYML_STATIC_LIB} ${C4CORE_STATIC_LIB})

target_include_directories(slic3r PRIVATE
        src/main/jni
        ${PRUSA_SRC}/src/libslic3r/include
        ${PRUSA_SRC}/src/libslic3r/src
        ${PRUSA_SRC}/bundled_deps
        ${PRUSA_SRC}/bundled_deps/admesh
        ${PRUSA_SRC}/bundled_deps/clipper/include
        ${PRUSA_SRC}/bundled_deps/localesutils
        ${PRUSA_SRC}/bundled_deps/semver
        ${PRUSA_SRC}/bundled_deps/semver
        ${PRUSA_SRC}/src/slic3r-domain/include
        ${PRUSA_SRC}/src/slic3r-base/include
        ${PRUSA_SRC}/src/slic3r-biz-algorithms/include
        ${PRUSA_SRC}/src/slic3r-biz-arrange/include
        ${PRUSA_SRC}/src/slic3r-shared/include
        ${PRUSA_SRC}/src/slic3r-render/include
        ${PRUSA_SRC}/src/slic3r-biz-cgal-algorithms/include
        ${PRUSA_SRC}/src/slic3r-biz-parser/include
        ${PRUSA_SRC}/src/slic3r-gcode-reader/include
        ${PRUSA_SRC}/src/slic3r-jthread/include
        ${PRUSA_SRC}/src/libpgcode/include
        ${PRUSA_SRC}/bundled_deps/slic3r-domain-types/include
        ${CMAKE_CURRENT_SOURCE_DIR}/build/headers/eigen/include
        ${CMAKE_CURRENT_SOURCE_DIR}/build/headers/spdlog/include
        ${CMAKE_CURRENT_SOURCE_DIR}/build/headers/fmt/include
        ${CMAKE_CURRENT_SOURCE_DIR}/build/headers/cereal/include
        ${CMAKE_CURRENT_SOURCE_DIR}/build/headers/nlohmann_json/include
        ${CMAKE_CURRENT_SOURCE_DIR}/build/headers/sol2/include

        ${CMAKE_CURRENT_SOURCE_DIR}/build/headers/expected/include
        ${CMAKE_CURRENT_SOURCE_DIR}/build/headers/magic_enum/include
        ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/assert/include
        ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/nanosvg/include
        ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/tracy
        ${PRUSA_SRC}/bundled_deps/ankerl
        ${PRUSA_SRC}/bundled_deps/stb_image
        ${PRUSA_SRC}/bundled_deps/stb_dxt
        src/main/jni/libnest2d/include
        ${PRUSA_GEN}
        src/main/jni/openvdb
        src/main/jni/mpfr
        src/main/jni/zlib
        src/main/jni/libpng
        src/main/jni/libjpeg-turbo
        src/main/jni/semver
        build/eigen-3.4.0/include
        src/main/jni/fast_float
        src/main/jni/expat
        src/main/jni/heatshrink
        src/main/jni/miniz
        src/main/jni/LibBGCode
        src/main/jni/glu-libtess/include
        src/main/jni/qhull
        src/main/jni/nlopt
        src/main/jni/nlopt/algs/stogo
        src/main/jni/nlopt/algs/ags
        src/main/jni/nlopt/util
        src/main/jni/nlopt/algs/direct
        src/main/jni/nlopt/algs/cdirect
        src/main/jni/nlopt/algs/praxis
        src/main/jni/nlopt/algs/luksan
        src/main/jni/nlopt/algs/crs
        src/main/jni/nlopt/algs/mlsl
        src/main/jni/nlopt/algs/mma
        src/main/jni/nlopt/algs/cobyla
        src/main/jni/nlopt/algs/newuoa
        src/main/jni/nlopt/algs/neldermead
        src/main/jni/nlopt/algs/auglag
        src/main/jni/nlopt/algs/bobyqa
        src/main/jni/nlopt/algs/isres
        src/main/jni/nlopt/algs/slsqp
        src/main/jni/nlopt/algs/esch
        src/main/jni/nlopt/api
        src/main/jni/libnest2d/include
        src/main/jni/libvgcode/include
        ${CMAKE_CURRENT_SOURCE_DIR}/prusa30/jniImports/tbb/include
        src/main/jniImports/oneTBB/include
        src/main/jniImports/boost/include
        src/main/jniImports/gmp/include/${ANDROID_ABI}
)
find_library(log-lib log)

target_link_libraries(slic3r PRIVATE
        m
        dl
        mpfr
        gmp
        gmpxx
        libpng
        zlib
        tbb
        log
        EGL
        GLESv3
        openvdb
        tbbmalloc
        heatshrink
        jpeg-static
        OCCTWrapper
        mcut
        BGCode_core
        BGCode_binarize
        BGCode_convert
        boost_atomic
        boost_charconv
        boost_chrono
        boost_container
        boost_context
        boost_contract
        boost_coroutine
        boost_date_time
        boost_exception
        boost_fiber
        boost_filesystem
        boost_graph
        boost_iostreams
        boost_json
        boost_log
        boost_log_setup
        boost_math_c99
        boost_math_c99f
        boost_math_c99l
        boost_math_tr1
        boost_math_tr1f
        boost_math_tr1l
        boost_nowide
        boost_prg_exec_monitor
        boost_random
        boost_regex
        boost_serialization
        boost_stacktrace_basic
        boost_system
        boost_thread
        boost_timer
        boost_type_erasure
        boost_url
        boost_wave
        boost_wserialization
        -Wl,--start-group
        ${OCCT_LIBS}
        -Wl,--end-group
)

# Dead-code-eliminate every function/data section that is not reachable from
# libslic3r.so's roots (the engine sources and libfarm's JNI/render glue resolve
# OCCT through the exported symbols of this .so). Static OCCT + gc-sections is
# the reason the APK no longer ships 42MB of libTK*.so runtime.
target_link_options(slic3r PRIVATE -Wl,--gc-sections)
