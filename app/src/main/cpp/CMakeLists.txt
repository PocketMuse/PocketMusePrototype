cmake_minimum_required(VERSION 3.22.1)
project(pocketmuse_converter LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

set(TO_MNN_CONVERTER "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/to-mnn/cpp/converter")
if(NOT EXISTS "${TO_MNN_CONVERTER}/CMakeLists.txt")
    message(FATAL_ERROR "Initialize to-mnn with git submodule update --init third_party/to-mnn")
endif()

include(FetchContent)
set(CMAKE_TLS_VERIFY ON)
FetchContent_Declare(nlohmann_json
    URL https://codeload.github.com/nlohmann/json/tar.gz/refs/tags/v3.12.0
    URL_HASH SHA512=6cc1e86261f8fac21cc17a33da3b6b3c3cd5c116755651642af3c9e99bb3538fd42c1bd50397a77c8fb6821bc62d90e6b91bcdde77a78f58f2416c62fc53b97d
)
FetchContent_MakeAvailable(nlohmann_json)
# The Converter's find_package can use the config generated in the JSON build tree.
set(nlohmann_json_DIR "${nlohmann_json_BINARY_DIR}")
add_subdirectory("${TO_MNN_CONVERTER}" sd15_converter)

add_library(pocketmuse_converter SHARED converter_jni.cpp)
target_compile_options(pocketmuse_converter PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(pocketmuse_converter PRIVATE sd15_converter)
target_link_options(pocketmuse_converter PRIVATE -Wl,-z,max-page-size=16384)
