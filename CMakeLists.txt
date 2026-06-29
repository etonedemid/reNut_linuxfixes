
# renut - ReXGlue Recompiled Project
#
# This file is yours to edit. 'rexglue migrate' will NOT overwrite it.
# SDK boilerplate lives in generated/rexglue.cmake.

cmake_minimum_required(VERSION 3.25)
project(renut LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Auto-detect the SDK source tree as a sibling directory. Prefer the active
# fork (achievements + linux + android) and fall back to a plain rexglue-sdk.
if(NOT REXSDK_DIR)
    foreach(_sdk_dir "rexglue-sdk-achievementslinuxandroid" "rexglue-sdk")
        if(EXISTS "${CMAKE_SOURCE_DIR}/../${_sdk_dir}/CMakeLists.txt")
            set(REXSDK_DIR "${CMAKE_SOURCE_DIR}/../${_sdk_dir}" CACHE PATH "Path to rexglue-sdk source tree" FORCE)
            break()
        endif()
    endforeach()
endif()

if(REXSDK_DIR AND CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|AMD64)$")
    add_compile_options(-mssse3)
endif()

if(REXSDK_DIR AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
    add_compile_options(-include ${REXSDK_DIR}/src/core/glibc_compat_force.h)
endif()

include(generated/rexglue.cmake)

# Sources
set(RENUT_SOURCES
    src/main.cpp
    src/renut_engine/hooks.cpp
    src/renut_engine/StopNSwap.cpp
    src/renut_engine/fixes/mullhwucrash.cpp
    src/renut_engine/fixes/nullmemcpy.cpp
)

if(WIN32)
list(APPEND RENUT_SOURCES icon/app.rc)
    add_executable(renut WIN32 ${RENUT_SOURCES})
    target_link_options(renut PRIVATE -municode)
elseif(ANDROID)
    # On Android, build as a shared library loaded by the Java Activity
    add_library(renut SHARED ${RENUT_SOURCES})
else()
    add_executable(renut ${RENUT_SOURCES})
endif()

rexglue_setup_target(renut)

target_include_directories(renut PRIVATE "${REXSDK_DIR}/thirdparty/imgui")
target_include_directories(renut PRIVATE "${CMAKE_BINARY_DIR}/rexglue-sdk/include")
# Symlink the source assets folder into the build output directory so the
# runtime VFS can find game data regardless of which build config is active.
add_custom_command(TARGET renut POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E rm -rf "$<TARGET_FILE_DIR:renut>/assets"
    COMMAND ${CMAKE_COMMAND} -E create_symlink
        "${CMAKE_SOURCE_DIR}/assets"
        "$<TARGET_FILE_DIR:renut>/assets"
    COMMAND ${CMAKE_COMMAND} -E rm -f "$<TARGET_FILE_DIR:renut>/renut.toml"
    COMMAND ${CMAKE_COMMAND} -E create_symlink
        "${CMAKE_SOURCE_DIR}/renut.toml"
        "$<TARGET_FILE_DIR:renut>/renut.toml"
    COMMENT "Symlinking assets and renut.toml -> build output"
)   

# On Linux: copy launch.sh, extract-xiso and renut.toml into the build dir
# so the binary directory is self-contained and ready to package.
if(UNIX AND NOT APPLE)
    foreach(_launch_file launch.sh extract-xiso renut.toml)
        if(EXISTS "${CMAKE_SOURCE_DIR}/${_launch_file}")
            add_custom_command(TARGET renut POST_BUILD
                COMMAND ${CMAKE_COMMAND} -E copy_if_different
                    "${CMAKE_SOURCE_DIR}/${_launch_file}"
                    "$<TARGET_FILE_DIR:renut>/${_launch_file}"
                COMMENT "Copying ${_launch_file} -> build output"
            )
        endif()
    endforeach()
endif()
