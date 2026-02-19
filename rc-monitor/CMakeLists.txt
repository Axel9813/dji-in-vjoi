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
    add_library(rc_monitor_static STATIC ${SOURCES})
    target_include_directories(rc_monitor_static PUBLIC include)

    # Test binary
    if(EXISTS ${CMAKE_SOURCE_DIR}/test/test_rc_monitor.c)
        add_executable(test_rc_monitor test/test_rc_monitor.c)
        target_link_libraries(test_rc_monitor rc_monitor_static)
    endif()
endif()
