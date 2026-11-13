set(runtime_build "${SD15_RUNTIME_ROOT}/build/android-arm64-api28")
set(mnn_source "${SD15_RUNTIME_ROOT}/MNNRuntime/upstream-cda4a6f")
set(mnn_build "${SD15_RUNTIME_ROOT}/MNNRuntime/build-android-arm64-api28-cda4a6f")
set(icu_lib "${runtime_build}/vcpkg_installed/arm64-android-sd15/lib")
set(runtime_archive "${runtime_build}/runtime/libsd15_runtime.a")
set(python "${SD15_RUNTIME_ROOT}/.venv/Scripts/python.exe")

if(NOT ANDROID_ABI STREQUAL "arm64-v8a" OR NOT ANDROID_STL STREQUAL "c++_shared")
    message(FATAL_ERROR "The SD1.5 Runtime requires arm64-v8a and c++_shared")
endif()
foreach(path IN ITEMS "${runtime_archive}" "${mnn_build}/libMNN.so" "${python}"
                      "${icu_lib}/libicui18n.a" "${icu_lib}/libicuuc.a" "${icu_lib}/libicudata.a")
    if(NOT EXISTS "${path}")
        message(FATAL_ERROR "Missing ${path}. Build the pinned to-mnn Android Runtime as documented in README.md")
    endif()
endforeach()

# The prebuilt engine targets API 28 and is loaded by the app on API 29+.
add_custom_target(verify_android_mnn
    COMMAND "${python}" -B -m scripts.tools.verify_mnn_android
        --source "${mnn_source}" --runtime "${mnn_build}" --ndk "${CMAKE_ANDROID_NDK}"
        --abi "${ANDROID_ABI}" --api 28 --stl "${ANDROID_STL}"
    WORKING_DIRECTORY "${TO_MNN_ROOT}" VERBATIM)
add_library(sd15_runtime STATIC IMPORTED)
set_target_properties(sd15_runtime PROPERTIES
    IMPORTED_LOCATION "${runtime_archive}"
    INTERFACE_INCLUDE_DIRECTORIES "${TO_MNN_ROOT}/cpp/runtime/include")
add_library(sd15_mnn SHARED IMPORTED)
set_target_properties(sd15_mnn PROPERTIES IMPORTED_LOCATION "${mnn_build}/libMNN.so")

add_library(pocketmuse_runtime SHARED runtime_jni.cpp)
add_dependencies(pocketmuse_runtime verify_android_mnn)
target_compile_options(pocketmuse_runtime PRIVATE -Wall -Wextra -Wpedantic)
target_link_options(pocketmuse_runtime PRIVATE -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL)
target_link_libraries(pocketmuse_runtime PRIVATE sd15_runtime sd15_mnn
    "${icu_lib}/libicui18n.a" "${icu_lib}/libicuuc.a" "${icu_lib}/libicudata.a")
