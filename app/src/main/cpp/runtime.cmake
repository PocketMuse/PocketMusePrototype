set(MNN_SOURCE_DIR "${SD15_RUNTIME_ROOT}/MNNRuntime/upstream-cda4a6f")
set(MNN_RUNTIME_DIR "${SD15_RUNTIME_ROOT}/MNNRuntime/build-android-arm64-api28-cda4a6f")
set(icu_root "${SD15_RUNTIME_ROOT}/build/android-arm64-api28/vcpkg_installed/arm64-android-sd15")
set(Python3_EXECUTABLE "${SD15_RUNTIME_ROOT}/.venv/Scripts/python.exe")

if(NOT ANDROID_ABI STREQUAL "arm64-v8a" OR NOT ANDROID_STL STREQUAL "c++_shared")
    message(FATAL_ERROR "The SD1.5 Runtime requires arm64-v8a and c++_shared")
endif()
if(NOT EXISTS "${Python3_EXECUTABLE}")
    message(FATAL_ERROR "Prepare the pinned to-mnn Android dependencies as documented in README.md, or use -PconverterOnly=true")
endif()

set(ICU_INCLUDE_DIR "${icu_root}/include")
foreach(component IN ITEMS i18n uc data)
    string(TOUPPER "${component}" upper)
    set(archive "${icu_root}/lib/libicu${component}.a")
    if(NOT EXISTS "${archive}")
        message(FATAL_ERROR "Missing Android ICU archive ${archive}. See README.md")
    endif()
    set(ICU_${upper}_LIBRARY_RELEASE "${archive}")
    set(ICU_${upper}_LIBRARY_DEBUG "ICU_${upper}_LIBRARY_DEBUG-NOTFOUND")
endforeach()
if(NOT EXISTS "${ICU_INCLUDE_DIR}/unicode/utypes.h")
    message(FATAL_ERROR "Missing Android ICU headers in ${ICU_INCLUDE_DIR}")
endif()
find_package(ICU 78.3 EXACT REQUIRED COMPONENTS i18n uc data)

# The engine targets API 28. The Runtime and JNI are built from source with the app's NDK.
set(verify_runtime_inputs "${Python3_EXECUTABLE}" -B "${CMAKE_CURRENT_LIST_DIR}/verify_runtime_inputs.py"
    "${TO_MNN_ROOT}" "${SD15_RUNTIME_ROOT}" "${CMAKE_ANDROID_NDK}")
execute_process(COMMAND ${verify_runtime_inputs} COMMAND_ERROR_IS_FATAL ANY)
add_custom_target(sd15_android_mnn_identity COMMAND ${verify_runtime_inputs} VERBATIM)
add_library(sd15_mnn SHARED IMPORTED)
set_target_properties(sd15_mnn PROPERTIES
    IMPORTED_LOCATION "${MNN_RUNTIME_DIR}/libMNN.so"
    INTERFACE_INCLUDE_DIRECTORIES "${MNN_SOURCE_DIR}/include")
set(SD15_BUILD_RUNTIME ON)
add_subdirectory("${SD15_RUNTIME_ROOT}/cpp/runtime" sd15_runtime)
add_dependencies(sd15_runtime_cpu sd15_android_mnn_identity)
# Keep the upstream Release optimization used for on-device measurements in every APK.
target_compile_options(sd15_runtime_cpu PRIVATE -O3)
target_compile_options(sd15_runtime PRIVATE -O3)

add_library(pocketmuse_runtime SHARED runtime_jni.cpp)
target_compile_options(pocketmuse_runtime PRIVATE -Wall -Wextra -Wpedantic)
target_link_options(pocketmuse_runtime PRIVATE
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--exclude-libs,ALL)
target_link_libraries(pocketmuse_runtime PRIVATE sd15_runtime sd15_mnn ICU::i18n ICU::uc ICU::data)
