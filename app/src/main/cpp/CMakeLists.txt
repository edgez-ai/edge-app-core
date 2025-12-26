cmake_minimum_required(VERSION 3.22.1)
project(wasmtime_bridge C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(wasmtime_bridge SHARED wasmtime_bridge.c)

# Expect a prebuilt Wasmtime C API SDK. Set WASMTIME_SDK_DIR to the extracted release (contains include/ and lib/android/<abi>/libwasmtime.a).
set(WASMTIME_SDK_DIR $ENV{WASMTIME_SDK_DIR} CACHE PATH "Path to prebuilt Wasmtime C API SDK")
if(NOT WASMTIME_SDK_DIR OR NOT EXISTS "${WASMTIME_SDK_DIR}")
    message(FATAL_ERROR "Set WASMTIME_SDK_DIR to the Wasmtime C API SDK root (with include/ and lib/android/<abi>/libwasmtime.a).")
endif()

# Only arm64-v8a is expected from the official release archive; error on others unless you add matching libs.
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "WASMTIME_SDK_DIR currently supports only arm64-v8a. Add libs for ${ANDROID_ABI} or change ABI filters.")
endif()

# The upstream aarch64-android C API archive ships libs directly under lib/; older layouts used lib/android/<abi>/.
set(_wasmtime_lib_candidates
    "${WASMTIME_SDK_DIR}/lib/android/${ANDROID_ABI}/libwasmtime.a"
    "${WASMTIME_SDK_DIR}/lib/libwasmtime.a"
)
set(WASMTIME_LIB_PATH "")
foreach(path ${_wasmtime_lib_candidates})
    if(EXISTS "${path}")
        set(WASMTIME_LIB_PATH "${path}")
        break()
    endif()
endforeach()
if(NOT WASMTIME_LIB_PATH)
    message(FATAL_ERROR "Wasmtime static lib not found. Checked: ${_wasmtime_lib_candidates}")
endif()

add_library(wasmtime_native STATIC IMPORTED)
set_target_properties(wasmtime_native PROPERTIES IMPORTED_LOCATION "${WASMTIME_LIB_PATH}")

target_include_directories(
    wasmtime_bridge
    PRIVATE
    "${WASMTIME_SDK_DIR}/include"
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    wasmtime_bridge
    wasmtime_native
    ${log-lib}
    ${android-lib}
)

# Ensure segments are 16 KB aligned for Android 15+ 16K page-size compliance.
target_link_options(wasmtime_bridge PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
