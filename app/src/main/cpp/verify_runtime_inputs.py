"""Verify the to-mnn checkout and prebuilt engine before compiling the pinned Runtime."""

import subprocess
import sys
from pathlib import Path

ARM64_BUILD = "build/android-arm64-api28"
MNN_SOURCE = "MNNRuntime/upstream-cda4a6f"
MNN_BUILD = "MNNRuntime/build-android-arm64-api28-cda4a6f"


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def verify_checkout(root, expected):
    if Path(git(root, "rev-parse", "--show-toplevel")).resolve() != root:
        raise ValueError(f"Expected a to-mnn Git checkout at {root}")
    revision = git(root, "rev-parse", "HEAD")
    if revision != expected:
        raise ValueError(f"Expected to-mnn {expected} at {root}, found {revision}")
    if git(root, "status", "--porcelain", "--untracked-files=all"):
        raise ValueError(f"Expected a clean to-mnn source tree at {root}")


def main():
    pinned, dependencies, ndk = (Path(arg).resolve() for arg in sys.argv[1:])
    record = git(pinned.parents[1], "ls-files", "--stage", "--", "third_party/to-mnn").split()
    if len(record) != 4 or record[0] != "160000" or record[2] != "0":
        raise ValueError("Cannot read the app's pinned to-mnn submodule revision")
    verify_checkout(pinned, record[1])
    if dependencies != pinned:
        verify_checkout(dependencies, record[1])

    sys.path.insert(0, str(pinned))
    from scripts.tools.verify_mnn_android import cache, verify

    identity = verify(
        dependencies / MNN_SOURCE, dependencies / MNN_BUILD,
        ndk, "arm64-v8a", 28, "c++_shared",
    )
    build = dependencies / ARM64_BUILD
    icu = build / "vcpkg_installed/arm64-android-sd15"
    config = cache(build / "CMakeCache.txt")
    for key, expected in {
        "CMAKE_HOME_DIRECTORY": dependencies / "cpp",
        "VCPKG_CHAINLOAD_TOOLCHAIN_FILE": ndk / "build/cmake/android.toolchain.cmake",
        "VCPKG_INSTALLED_DIR": build / "vcpkg_installed",
        "MNN_SOURCE_DIR": dependencies / MNN_SOURCE,
        "MNN_RUNTIME_DIR": dependencies / MNN_BUILD,
        "ICU_INCLUDE_DIR": icu / "include",
        **{f"ICU_{name.upper()}_LIBRARY_RELEASE": icu / f"lib/libicu{name}.a"
           for name in ("i18n", "uc", "data")},
    }.items():
        if Path(config[key]).resolve() != expected:
            raise ValueError(f"Unexpected to-mnn build path for {key}")
    for key, expected in {
        "CMAKE_BUILD_TYPE": "Release",
        "ANDROID_ABI": "arm64-v8a",
        "ANDROID_PLATFORM": "android-28",
        "ANDROID_STL": "c++_shared",
        "VCPKG_TARGET_TRIPLET": "arm64-android-sd15",
        "SD15_BUILD_RUNTIME": "ON",
    }.items():
        if config.get(key) != expected:
            raise ValueError(f"Expected {key}={expected} in the to-mnn build")

    readelf = ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
    mnn = dependencies / MNN_BUILD / "libMNN.so"
    headers = subprocess.check_output([str(readelf), "-lW", str(mnn)], text=True)
    alignments = [int(line.split()[-1], 16) for line in headers.splitlines() if line.lstrip().startswith("LOAD ")]
    if not alignments or min(alignments) < 16384:
        raise ValueError("libMNN.so needs 16 KiB ELF alignment. Relink as documented in README.md")
    print(f"Verified to-mnn {record[1]} and MNN {identity['library_sha256']}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        sys.exit(f"Runtime input verification failed: {error}")
