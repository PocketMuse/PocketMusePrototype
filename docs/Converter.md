# SD 1.5 Converter JNI

## 호출 계약

- `ConversionOptions`에 앱이 읽을 수 있는 체크포인트와 템플릿의 절대 파일 경로를 전달한다. `templateDir`에는 짝이 맞는 `graph.bin`과 `manifest.json`이 있어야 한다. 앱이 쓸 수 있는 출력 부모 디렉터리를 먼저 만든다. 기본값은 청크 1 MiB, HQQ 보정 20회, 계산 스레드 1개다. 유효 범위와 호환성 검사는 to-mnn을 따른다. [to-mnn 공개 API](https://github.com/PocketMuse/to-mnn/blob/b5a06df6944cd8b6ff22048396281d04e926c7fe/docs/cpp/public-api.md#converter-contracts)
- `ConversionRequest(options).run(onProgress)`는 호출 스레드에서 동기 실행된다. 파일 I/O를 허용하는 작업 스레드에서 호출한다. 콜백은 같은 스레드에서 실행되며 단계, 컴포넌트, 바이트 진행률 및 시간 정보를 받는다. 공개 전 콜백 예외는 `CALLBACK_FAILED`로 전달되고 정리에 실패하면 `CLEANUP_FAILED`가 우선한다. `Completed`는 출력 공개 후에 전달되며 그 시점의 취소나 콜백 예외 이후에도 성공이 유지된다.
- 각 요청은 한 번 실행할 수 있다. `requestCancel()`은 실행 전이나 다른 스레드에서도 호출할 수 있다. `run()`이 `Cancelled`를 반환하면 취소 처리와 정리가 끝난 상태다. 입력과 출력 경로의 소유권은 반환까지 호출자가 유지한다. 변환과 정리는 프로세스 내에서 한 번에 하나만 실행한다. 다른 호출과 경합하거나 진행 콜백에서 새 요청을 실행하거나 정리를 호출하면 `BUSY`를 반환한다.
- `ConversionResult`는 상태, 오류 코드, 공개된 파일의 상대 이름과 크기, 소요 시간과 HQQ 계측값을 제공한다. 오류 코드는 to-mnn의 공개 식별자와 일치한다. 네이티브 진단 메시지·경로·텐서명은 Kotlin 결과에 포함하지 않는다. JVM 할당 실패나 JNI 연결 오류는 예외로 전달될 수 있다. `Succeeded`를 반환한 출력만 사용한다.
- 중단 후 잔여 출력 정리가 필요하면 해당 경로의 배타적 소유권을 확보하고 `ConversionRequest.cleanupPartial(outputDir)`를 호출한다. Converter가 소유 표식과 파일을 검사한다. 기존 최종 출력은 덮어쓰지 않으며, 소유를 확인할 수 없는 `.partial`은 보존한다. [출력 및 정리 계약](https://github.com/PocketMuse/to-mnn/blob/b5a06df6944cd8b6ff22048396281d04e926c7fe/docs/cpp/public-api.md#결과와-잔여-출력-정리)

## Checkpoints

- AGP의 NDK 도구 체인에서 to-mnn의 `cpp/converter` CMake 하위 디렉터리만 추가한다. to-mnn의 vcpkg 기준과 같은 nlohmann/json 3.12.0 소스를 해시로 고정하고, 생성된 CMake 패키지 설정을 `sd15_converter`의 `find_package()`에 연결한다. 별도 vcpkg Android 빌드나 사전 빌드한 `.a`를 가져오는 방식은 도구 체인과 ABI를 따로 맞춰야 한다. 소스 통합으로 Converter와 JNI가 앱이 고정한 NDK, API 29, C++17 및 `c++_shared`를 공유한다. [Android NDK CMake](https://developer.android.com/ndk/guides/cmake), [nlohmann/json CMake 통합](https://nlohmann.github.io/json/integration/cmake/)
- Kotlin은 파일 경로를 표준 UTF-8 바이트로 넘긴다. 동기 JNI 호출이 진행 콜백의 참조를 유지하고, 요청이 취소 토큰 접근과 해제를 동기화한다. [Android JNI 문자열과 참조](https://developer.android.com/ndk/guides/jni-tips#utf-8_and_utf-16_strings)

## WorkManager 연동

`ConversionWorker`에서 앱 소유 입력과 출력 경로를 확정하고 작업 스레드에서 요청을 실행한다. Worker 중단이 시작될 때 `requestCancel()`을 전달하고, `run()`이 반환할 때까지 파일이나 작업 소유권을 해제하지 않는다. 반환된 상태·오류로 완료와 재시도를 결정한다. 프로세스 종료 후 다시 실행할 때는 소유가 확인된 `.partial`을 정리하고 새 요청으로 처음부터 변환한다. 진행 콜백의 상태는 Worker가 알림·영속 상태에 전달한다. [장시간 Worker](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)

## 검증 기록

- Debug 빌드와 JVM 테스트 36개가 통과했다. lint는 오류 0개, 경고 34개를 보고했다. APK에서 ARM64 JNI 라이브러리와 `libc++_shared.so`의 패키징 및 16 KiB 정렬을 확인했다.
- 2026-11-11 Galaxy S20+ (SM-G986N, Android 13/API 33)에서 기존 Android 계측 18개와 합성 Converter 계측 4개가 통과했다. 합성 테스트는 정확한 출력 바이트, 공개 시점, 진행 콜백, 교차 스레드 취소, 오류 및 잔여 출력 소유권을 확인한다.
- 같은 기기의 Debug APK에서 기존 ADB 실험의 4,265,146,304바이트 SD 1.5 체크포인트와 `sd15-hqq-b128-manual` 템플릿으로 실제 변환을 1회 수행했다. 청크 1 MiB, HQQ 보정 20회, 계산 스레드 4개를 사용했고 파일 캐시 상태는 제어하지 않았다. ADB 계측 테스트와 Converter 경과 시간 428.056초를 기록했으며 `Completed`, 출력 파일 세 개의 이름·크기 및 `.partial` 부재를 확인했다. [기존 ADB 입력과 실행 조건](https://github.com/PocketMuse/to-mnn/blob/b5a06df6944cd8b6ff22048396281d04e926c7fe/docs/sd15-discussion.md#2026-11-08--s20-변환추론-검증), [재현 명령](../README.md#실제-hqq-모델-스모크)

### 네이티브 빌드 성능 재현

`converterBenchmark` Gradle 속성은 디버그 서명을 사용하는 격리 패키지 `com.example.pocketmuseprototype.converterbenchmark`를 만들고 CMake 빌드 타입을 지정한다. `Debug`, `Release`, `RelWithDebInfo`가 가능하다. 두 APK를 `-r`로 교체 설치하면 비교 실행이 같은 입력 파일을 사용한다. `app/.cxx/<CMake 타입>/<해시>/arm64-v8a/compile_commands.json`에서 `hqq.cpp`와 `converter_jni.cpp`에 적용된 옵션을 확인한다.

```powershell
$nativeConfig = 'Release'
.\gradlew.bat "-PconverterBenchmark=$nativeConfig" :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

입력 파일명과 하위 경로는 [실제 HQQ 스모크](../README.md#실제-hqq-모델-스모크)와 같으며 격리 패키지의 `/sdcard/Android/data/com.example.pocketmuseprototype.converterbenchmark/files/converter-smoke`에 한 번만 준비한다. 기준 출력이 있으면 해당 출력 디렉터리에서 `sha256sum *.mnn`으로 만든 `reference-sha256.txt`를 입력 디렉터리에 추가한다. 테스트는 변환 타이머가 끝난 후 출력 해시를 비교한다.

```powershell
adb shell am instrument -w -r -e class com.example.pocketmuseprototype.conversion.ConverterSmokeTest -e converterSmoke true com.example.pocketmuseprototype.converterbenchmark.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s ConverterSmokeTest:I
```

`hqq.cpp`와 `converter_jni.cpp`를 빌드한 실제 컴파일 명령을 비교했다. 앱은 NDK `27.1.12297006`, `aarch64-none-linux-android29`, `-std=c++17`, `c++_shared`를 사용했다. 기존 CLI의 Ninja 명령은 같은 NDK에서 `aarch64-none-linux-android28`, 컴파일러 기본 C++17, `c++_shared`를 사용했다. Converter의 `-ffp-contract=off`는 모든 구성에 있었다. [CMake 빌드 타입](https://cmake.org/cmake/help/v3.22/variable/CMAKE_BUILD_TYPE.html), [컴파일 명령 내보내기](https://cmake.org/cmake/help/v3.22/variable/CMAKE_EXPORT_COMPILE_COMMANDS.html)

| 빌드 | CMake 타입 | 실제 최적화 및 `NDEBUG` |
| --- | --- | --- |
| 기존 CLI의 로컬 Ninja 구성 | `Release` | `-O3 -DNDEBUG` |
| 기존 및 격리 Debug APK | `Debug` | `-O` 플래그 생략(Clang 기본 `-O0`), `NDEBUG` 없음 |
| 격리 Release 네이티브 APK | `Release` | `-O3 -DNDEBUG` |
| 일반 Release APK와 격리 `RelWithDebInfo` APK | `RelWithDebInfo` | `-O2 -g -DNDEBUG` |

일반 Release APK와 격리 `RelWithDebInfo` APK의 `libpocketmuse_converter.so`에서 `.text`를 추출해 같은 SHA-256을 확인했다. 기록된 11월 8일의 기기 CLI 바이너리와 현재 로컬에서 보관한 CLI 바이너리의 전체 해시는 서로 달라, CLI 플래그는 로컬 Ninja 명령을 근거로 기록한다. JNI 플래그는 측정에 사용한 APK의 빌드 명령에서 직접 확인했다.

### 실기기 성능 결과

2026-11-11 Galaxy S20+ (SM-G986N, Android 13/API 33)에서 격리 APK를 교체 설치하며 비교했다. 기존 CLI의 체크포인트와 템플릿을 격리 앱 전용 외부 저장소에 한 번 복사해 각 JNI 실행에서 재사용했다. 모든 실행에서 `sd15-hqq-b128-manual`, 청크 1 MiB, HQQ 보정 20회, 계산 스레드 4개를 사용했다. 실행마다 새 출력 디렉터리를 사용했고 파일 캐시와 CPU 클록은 제어하지 않았다. 온도는 실행 전후 `dumpsys battery`의 배터리 값이다.

| 실행 | 전체(초) | HQQ(초) | 기타(초) | 배터리 온도(°C) | Thermal Status |
| --- | ---: | ---: | ---: | --- | --- |
| 기존 CLI, `/data/local/tmp` | 72.799 | 63.903 | 8.896 | 32.2 → 33.7 | 미기록 |
| 기존 JNI Debug, 앱 전용 외부 저장소 | 428.056 | 374.198 | 53.858 | 미기록 | 미기록 |
| 격리 JNI `-O3` 1회차 | 79.210 | 65.819 | 13.391 | 30.5 → 33.0 | 0 → 1 |
| 격리 JNI Debug | 470.149 | 411.465 | 58.684 | 33.9 → 36.7 | 1 → 1 |
| 격리 JNI `-O3` 2회차 | 99.882 | 87.213 | 12.669 | 36.6 → 36.7 | 1 → 1 |
| 격리 JNI `-O2` | 99.200 | 87.014 | 12.186 | 36.6 → 36.8 | 1 → 1 |
| 기존 기기 CLI 재실행, `/data/local/tmp` | 118.040 | 93.448 | 24.592 | 36.9 → 36.8 | 1 → 1 |

기존 JNI Debug의 HQQ 시간은 당시 ADB 로그의 `374198.219 ms`다. 이번 Debug 첫 시작은 로그 수집 오류로 중단되어 표에서 제외했다. 기타 시간은 `elapsedMillis - hqqComputeMillis`로, 검증, 입력 디코딩, 파일 I/O, 최종 공개 등을 포함한다. HQQ 시간에는 배치 작업을 기다리는 시간과 배치 중 호출되는 진행 콜백이 포함될 수 있다. [Converter 타이머](../third_party/to-mnn/cpp/converter/src/conversion/converter.cpp), [HQQ 작업 대기](../third_party/to-mnn/cpp/converter/src/quantization/hqq_workers.cpp)

완료된 JNI 4회 모두 `Succeeded` / `NONE`을 반환했다. JNI 4회와 CLI 재실행의 세 출력 모두 아래 CLI 기준 SHA-256과 일치했다. 해시 계산은 변환 계측 범위 밖에서 수행했다.

| 출력 파일 | 바이트 | CLI 기준 SHA-256 |
| --- | ---: | --- |
| `text_encoder.mnn` | 246,509,140 | `1f9a7b80805b4df460e10339d4401287a2e9cd628ff247dae05a0f9e148c1cfc` |
| `unet.mnn` | 885,095,992 | `39c1978f79273e114624e8dd55b29e97756e6f3a0fe3e27d54fb9fccfac87bb8` |
| `vae_decoder.mnn` | 132,659,232 | `6b44712b4ba3e7b04ec1b3739bb99e0f5fb0325331fc4e49acf42ba84c82cc4f` |

같은 앱 전용 외부 저장소에서 Debug는 `-O3`보다 4.71~5.94배 오래 걸렸다. 시간 차이의 87.6~88.4%가 HQQ 구간에서 발생했다. 기존 428.056초를 설명하는 주원인은 Debug의 네이티브 최적화 설정이다. `-O3`의 첫 실행은 기존 CLI보다 6.411초 길었고, 두 번째 실행은 첫 실행보다 20.672초 길었다. 두 JNI `-O3` 실행의 경로와 콜백은 같았고 HQQ 구간은 21.393초 늘었다. 비슷한 배터리 온도와 Thermal Status에서 기존 CLI도 118.040초로 느려졌다. 이 결과는 실행 조건에 따른 변동을 보여준다. 파일 I/O, CPU 클록·스로틀링, 캐시와 콜백의 비용은 각각 따로 측정하지 않았다.

일반 Debug·Release APK 빌드와 JVM 테스트 36개가 통과했고 lint는 오류 0개, 경고 34개였다. 격리 `RelWithDebInfo` APK에서 인증·탐색·Library 및 합성 Converter 계측 21개가 통과했다. 기본 패키지명을 고정 비교하는 템플릿 테스트는 격리 실행에서 제외하고 기본 APK의 패키지명을 확인했다. 실험용 패키지만 제거했고 기존 앱의 설치 경로와 CLI 자산을 확인했다.

WorkManager 변환의 성능 기준은 일반 Release의 네이티브 `RelWithDebInfo` (`-O2 -g -DNDEBUG`)로 측정한다. 비슷한 배터리 온도에서 `-O2`와 `-O3`의 단일 실행 차이는 0.682초였다.
