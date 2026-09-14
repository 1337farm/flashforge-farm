cmake_minimum_required(VERSION 3.22)
project(farm_prusa30_jni)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(farm_prusa30_jni SHARED
    jni_farm_slice.cpp
    ${CMAKE_SOURCE_DIR}/engine/prusa30/farm_driver.cpp
)

target_include_directories(farm_prusa30_jni PRIVATE
    ${CMAKE_SOURCE_DIR}/engine/prusa30
    ${PRUSA_SRC}
)

target_link_libraries(farm_prusa30_jni PRIVATE libslic3r)
