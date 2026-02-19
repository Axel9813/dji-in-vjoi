cmake_minimum_required(VERSION 3.18)
project(rc_monitor C)

set(CMAKE_C_STANDARD 11)

# Source files
set(SOURCES
    src/rc_monitor.c
)

# Include path
include_directories(include)

# --- Android JNI shared library ---
if(ANDROID)
    add_library(rc_monitor SHARED
        ${SOURCES}
        src/rc_monitor_jni.c
    )
    target_link_libraries(rc_monitor
        log    # Android logging
    )
    # Strip debug symbols for release
    if(CMAKE_BUILD_TYPE STREQUAL "Release")
        set_target_properties(rc_monitor PROPERTIES
            LINK_FLAGS "-s"
        )
    endif()

# --- Desktop static library (for testing) ---
else()
    # Compiler warnings
    add_compile_options(-Wall -Wextra -Wpedantic -Werror)

    # Optional: Address Sanitizer + Undefined Behavior Sanitizer
    option(ENABLE_SANITIZERS "Enable ASan + UBSan" OFF)
    if(ENABLE_SANITIZERS)
        add_compile_options(-fsanitize=address,undefined -fno-omit-frame-pointer)
        add_link_options(-fsanitize=address,undefined)
    endif()

    add_library(rc_monitor_static STATIC ${SOURCES})
    target_include_directories(rc_monitor_static PUBLIC include)

    # Test binary
    if(EXISTS ${CMAKE_SOURCE_DIR}/test/test_rc_monitor.c)
        add_executable(test_rc_monitor test/test_rc_monitor.c)
        target_link_libraries(test_rc_monitor rc_monitor_static)
    endif()

    # Fuzz targets (requires clang with libFuzzer support)
    option(ENABLE_FUZZING "Enable libFuzzer targets" OFF)
    if(ENABLE_FUZZING)
        add_executable(fuzz_feed test/fuzz_feed.c)
        target_link_libraries(fuzz_feed rc_monitor_static)
        target_compile_options(fuzz_feed PRIVATE -fsanitize=fuzzer,address,undefined)
        target_link_options(fuzz_feed PRIVATE -fsanitize=fuzzer,address,undefined)

        add_executable(fuzz_payload test/fuzz_payload.c)
        target_link_libraries(fuzz_payload rc_monitor_static)
        target_compile_options(fuzz_payload PRIVATE -fsanitize=fuzzer,address,undefined)
        target_link_options(fuzz_payload PRIVATE -fsanitize=fuzzer,address,undefined)
    endif()
endif()
