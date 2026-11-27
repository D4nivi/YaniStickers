cmake_minimum_required(VERSION 3.22.1)

project(yanistickers_webp LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Forzar -O3 incluso en compilaciones Debug para que la codificación WebP no vaya en -O0
string(REPLACE "-O0" "-O3" CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG}")
string(REPLACE "-O0" "-O3" CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG}")
add_compile_options(-O3)

# Compilar únicamente las bibliotecas estáticas de libwebp necesarias (sin herramientas CLI)
set(WEBP_LINK_STATIC ON CACHE BOOL "" FORCE)
set(WEBP_BUILD_ANIM_UTILS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_CWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_DWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_GIF2WEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_IMG2WEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_VWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPINFO OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_LIBWEBPMUX ON CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPMUX OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_EXTRAS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBP_JS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_FUZZTEST OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/libwebp)

add_library(yanistickers_webp SHARED animated_webp_jni.cpp)

target_include_directories(
    yanistickers_webp
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libwebp/src
)

target_compile_options(
    yanistickers_webp
    PRIVATE
        -O3
        -Wall
        -Wextra
)

target_link_libraries(
    yanistickers_webp
    PRIVATE
        webp
        webpdemux
        libwebpmux
        jnigraphics
        log
)
