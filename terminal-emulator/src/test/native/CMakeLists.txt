cmake_minimum_required(VERSION 3.22)
project(terminal_font_fit_tests LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

get_filename_component(MDTERM_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(FREETYPE_SRC "${MDTERM_ROOT}/build/native-sources/freetype" CACHE PATH
    "Pinned FreeType checkout prepared by Gradle")
set(HARFBUZZ_SRC "${MDTERM_ROOT}/build/native-sources/harfbuzz" CACHE PATH
    "Pinned HarfBuzz checkout prepared by Gradle")
foreach(dependency FREETYPE_SRC HARFBUZZ_SRC)
    if(NOT EXISTS "${${dependency}}/CMakeLists.txt")
        message(FATAL_ERROR "${dependency} must point to the prepared native sources")
    endif()
endforeach()

# Match the Android renderer's font libraries, without its PNG/Vulkan backend.
set(FT_DISABLE_ZLIB ON CACHE BOOL "" FORCE)
set(FT_DISABLE_BZIP2 ON CACHE BOOL "" FORCE)
set(FT_DISABLE_PNG ON CACHE BOOL "" FORCE)
set(FT_DISABLE_HARFBUZZ ON CACHE BOOL "" FORCE)
set(FT_DISABLE_BROTLI ON CACHE BOOL "" FORCE)
add_subdirectory("${FREETYPE_SRC}" "${CMAKE_CURRENT_BINARY_DIR}/freetype" EXCLUDE_FROM_ALL)

set(HB_BUILD_UTILS OFF CACHE BOOL "" FORCE)
set(HB_BUILD_SUBSET OFF CACHE BOOL "" FORCE)
set(HB_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(HB_BUILD_RASTER OFF CACHE BOOL "" FORCE)
set(HB_BUILD_VECTOR OFF CACHE BOOL "" FORCE)
set(HB_BUILD_GPU OFF CACHE BOOL "" FORCE)
set(HB_HAVE_FREETYPE ON CACHE BOOL "" FORCE)
set(HB_HAVE_GLIB OFF CACHE BOOL "" FORCE)
set(HB_HAVE_ICU OFF CACHE BOOL "" FORCE)
set(HB_HAVE_GRAPHITE2 OFF CACHE BOOL "" FORCE)
set(HB_HAVE_CAIRO OFF CACHE BOOL "" FORCE)
set(HB_HAVE_GOBJECT OFF CACHE BOOL "" FORCE)
set(HB_HAVE_INTROSPECTION OFF CACHE BOOL "" FORCE)
add_subdirectory("${HARFBUZZ_SRC}" "${CMAKE_CURRENT_BINARY_DIR}/harfbuzz" EXCLUDE_FROM_ALL)

add_executable(terminal_font_fit_test terminal_font_fit_test.cpp)
target_include_directories(terminal_font_fit_test PRIVATE "../../main/jni")
target_compile_options(terminal_font_fit_test PRIVATE -Wall -Wextra -Werror)
target_link_libraries(terminal_font_fit_test PRIVATE freetype harfbuzz)

# These fixtures already ship in the repository and pinned HarfBuzz checkout.
enable_testing()
add_test(NAME terminal_font_fit COMMAND terminal_font_fit_test
    "${MDTERM_ROOT}/app/src/main/res/font/terminal_default.ttf"
    "${HARFBUZZ_SRC}/test/api/fonts/SourceHanSans-Regular.41,3041,4C2E.otf"
    "${HARFBUZZ_SRC}/test/subset/data/fonts/Mplus1p-Regular.ttf")
