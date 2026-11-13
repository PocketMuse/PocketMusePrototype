# SD 1.5 Runtime JNI

## Chat 이미지 생성

- 기존 Chat에서 최적화된 정적 LCM 모델을 선택하고 프롬프트를 전송한다. Activity 범위 `ImageGenerationViewModel`은 `storageKey`, 스텝, 프롬프트의 스냅샷으로 요청 하나를 실행한다. 탭을 이동하거나 Activity를 재생성해도 작업과 상태가 유지된다. Activity가 종료되면 취소를 요청하며 프로세스가 종료되면 작업과 결과가 사라진다. [Android ViewModel 수명](https://developer.android.com/topic/libraries/architecture/viewmodel#lifecycle)
- 스텝은 2·4·6·8이며 기본값은 4다. LCM, guidance 1.0, 512×512, OpenCL Buffer, Low 정밀도, Fast 튜닝, Low Memory와 영속 디스크 캐시를 사용한다. Negative prompt는 비우고 동적 LoRA와 프리뷰는 비활성화한다. `GenerateOptions`의 기본 seed `-1`로 Runtime이 매 요청의 seed를 정하며 반환한 실제 seed는 이미지와 함께 메모리에 보관한다. [to-mnn 생성 요청 계약](../third_party/to-mnn/docs/cpp/public-api.md#생성-요청)
- APK에 포함된 SD1.5 CLIP `vocab.json`과 `merges.txt`는 생성 전에 `noBackupFilesDir/sd15-tokenizer/`에 준비한다. SHA-256이 맞으면 재사용하고, 누락되거나 손상된 파일은 임시 파일에 복사하고 검증한 뒤 원자적으로 교체한다. 두 파일이 검증된 뒤 앱 전용 디렉터리 경로를 JNI에 전달한다. [고정 리비전의 Tokenizer 원본](https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-v1-5/tree/451f4fe16113bff5a5d2269ed5ad43b0592e9a14/tokenizer)
- Chat은 전송한 프롬프트를 오른쪽 말풍선에 표시하고 입력창을 비운다. 이미지와 진행 상태는 왼쪽 응답 영역에 표시한다. CLIP·UNet 준비·VAE 구간은 단계별 상태로, denoising 구간은 콜백의 `step`과 `total`로 표시한다. 우하단 전송 버튼은 생성 중 정지 버튼으로 바뀐다. 취소를 누르면 코루틴을 취소하고 JNI와 모델 대여가 정리될 때까지 버튼을 비활성화한다. 모델을 사용할 수 없으면 Library에서 상태를 확인하도록 안내한다.
- HWC RGB8은 작업 스레드에서 불투명 ARGB Bitmap으로 변환해 기존 Chat 결과에 표시한다. ViewModel은 최신 요청의 상태와 성공 이미지 한 장을 메모리에 유지하며 새 요청이 시작되면 이전 결과의 참조를 놓는다. PNG 인코딩이나 파일·Room·SavedStateHandle 저장은 거치지 않는다.

2026-11-13 Galaxy S20+ (SM-G986N, Android 13/API 33)에서 Debug APK를 `adb install -r`로 업데이트하고 기존 ToonYou Beta 6 (`125771_90840`)와 CLIP·UNet·VAE 운영 캐시를 재사용했다. Chat 계측에서 2스텝 요청의 첫 denoising 스텝 직후 취소를 확인했다. 이어 `masterpiece, best quality, a cute cat`으로 4스텝 요청을 전송하고 Library로 이동한 상태에서 Activity를 재생성한 뒤, Chat에서 512×512 이미지 표시를 확인했다. 전송부터 이미지 표시 확인까지의 단일 Warm 표본은 45.091초였으며 탭 이동·Activity 재생성·UI 확인을 포함한다. [Chat 계측 재현 명령](../README.md#실제-정적-lcm-추론-스모크)

`:app:assembleDebug`, `:app:assembleDebugAndroidTest`, JVM 테스트 72개와 Galaxy S20+의 대상 계측 8개가 통과했다. 계측 범위에는 Chat 상호작용, Tokenizer 재사용·손상 복구, RGB 채널 순서, JNI 초기화 오류와 사전 취소, 실제 취소 후 재생성이 포함된다. 이 변경에서는 6·8스텝의 실기기 추론, Cold 캐시 재측정, 다른 기기의 GPU·메모리 안정성을 검증하지 않았다.

## 빌드와 호출 계약

- 일반 Debug·Release 빌드는 to-mnn의 `sd15_runtime` 타깃을 현재 Android 빌드에서 컴파일한다. 외부 `libsd15_runtime.a`는 링크 입력에서 제거했다. 기본 소스는 `third_party/to-mnn`이며, `-Psd15RuntimeRoot`로 같은 리비전의 체크아웃을 지정할 수 있다. 두 체크아웃의 HEAD와 깨끗한 작업 트리를 앱의 Git 서브모듈 기록에 대조한다. 입력이 누락되거나 검증에 실패하면 빌드가 중단된다. `-PconverterOnly=true`는 기존 Converter 테스트와 CI를 위한 명시적 모드다. [빌드 준비](../README.md#runtime-빌드-준비) · [to-mnn 공개 API](../third_party/to-mnn/docs/cpp/public-api.md#android-앱-연동)
- to-mnn의 기존 검증기로 MNN의 소스·빌드 영수증·SHA-256·NDK를 확인한다. Android preset의 CMake 캐시에서 API 28, arm64-v8a, `c++_shared`, NDK 및 vcpkg ICU 경로를 대조하고 ICU 78.3 헤더와 정적 아카이브를 명시적으로 링크한다. MNN에는 16 KiB ELF LOAD 정렬을 요구한다. APK에 MNN과 앱 NDK의 `libc++_shared.so`를 패키징한다. [Gradle 네이티브 빌드](https://developer.android.com/studio/projects/gradle-external-native-builds)
- `ModelDownloads.withStaticLcmOutput(storageKey)`는 앱의 Library에서 Downloaded·Converted·정적 LCM 상태, 템플릿 ID, 세 MNN 파일 및 기록된 크기와 SHA-256을 확인한다. 모델과 OpenCL 캐시 경로를 빌려주는 동안 기존 변환 잠금을 잡는다. 삭제 요청이 들어오면 Room에 Deleting을 기록하고 콜백이 반환한 뒤 삭제한다. 호출자는 콜백 안에서 생성과 취소 정리까지 기다린다. 이 잠금의 범위는 Workers와 UI가 공유하는 앱 프로세스다.
- `StaticLcmRequest`는 앱에서 읽을 수 있는 모델·Tokenizer 디렉터리와 선택적 캐시 디렉터리의 ASCII 절대 경로, UTF-8 프롬프트, 스텝 수를 받는다. `generate()`는 I/O 스레드에서 JNI의 Runtime을 만들고 한 장을 생성하며 512×512 HWC RGB8과 실제 seed를 반환한다. JNI는 프로세스 내 겹치는 생성 호출을 거부한다. 공개 진행 콜백은 호출 스레드에서 실행된다. 초기화·생성 실패는 각각 `RuntimeInitializationException`과 `RuntimeGenerationException`으로 구분하고 콜백 예외는 원본을 전달한다.
- `requestCancel()`은 실행 전과 실행 중에 반복 호출할 수 있다. 코루틴이 취소되면 네이티브 토큰에 전달하고 작업 스레드가 반환할 때까지 기다린다. 네이티브가 확인한 취소는 `CancellationException`으로 전달한다. Runtime과 모델 세션은 C++ 스코프에서 정리되고 Kotlin은 JNI 반환 후 토큰을 해제한다. 프로세스 강제 종료 시 콜백과 정리 블록의 실행은 보장되지 않는다. Room에 기록된 `Deleting`은 다음 상태 대조에서 처리한다. [to-mnn 취소·콜백 계약](../third_party/to-mnn/docs/cpp/public-api.md#콜백과-실행-종료)

## Checkpoints

- OpenCL 디스크 캐시는 기본 활성화된다. `noBackupFilesDir/opencl-cache/{storageKey}/{conversionWorkId}`를 JNI에서 `RuntimeOptions.cache_dir`로 전달한다. 같은 변환 UUID는 프로세스가 바뀌어도 같은 경로를 쓴다. `to-mnn`이 CLIP·UNet·VAE 실행 중 캐시를 열고 세션 해제까지 기록하며, 모델·엔진·실행 프로필에 따라 내부 파일을 구분한다. 캐시 경로를 사용할 수 없거나 파일 접근·잠금이 실패하면 Runtime은 디스크 캐시 없이 실행한다. [Android noBackupFilesDir](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir()) · [to-mnn 캐시 계약](../third_party/to-mnn/docs/cpp/public-api.md#설정과-배포-자원)
- 모델 삭제 시 해당 `storageKey`의 캐시를 정리한다. 변환 출력 폐기 시 해당 작업 UUID의 캐시만 정리하며, 새 변환에는 새 UUID를 사용한다. 캐시 정리는 가능한 범위에서 수행한다. 강제 종료나 I/O 오류로 남은 캐시는 해당 UUID에 격리된다.
- Android 12 이상에서 앱이 기기의 공개 OpenCL 드라이버를 사용할 수 있도록 `libOpenCL.so`를 선택적으로 선언한다. Runtime은 CLIP·UNet·VAE에서 OpenCL 장치 텐서와 동기화 경계를 검사하고 CPU fallback을 거부한다. 고정 MNN `cda4a6f`를 같은 NDK와 OpenCL 설정으로 다시 링크했다. Debug·Release·격리 APK의 ARM64 공유 라이브러리 6개 모두에서 ELF LOAD 정렬 `0x4000`과 `zipalign -c -P 16 4` 통과를 확인했다. 연결된 S20+의 페이지 크기는 4096바이트이며 16 KiB 실행 환경은 확보하지 못했다. [Android 벤더 라이브러리](https://developer.android.com/guide/topics/manifest/uses-native-library-element) · [Android 페이지 크기 지원](https://developer.android.com/guide/practices/page-sizes) · [Runtime 검증 근거](../third_party/to-mnn/docs/cpp/runtime.md#checkpoints)
- 타이밍은 단조 시계를 사용한다. 공개 진행 콜백은 모델 로딩 직전과 각 denoising 스텝 완료 시점에 온다. CLIP 구간은 CLIP 진입부터 UNet 진입까지, UNet 첫 구간은 모델 로딩·튜닝과 첫 스텝을 포함한다. 마지막 스텝부터 VAE 진입까지는 UNet 정리 구간이다. 총시간에는 모델·Tokenizer 확인, Runtime 생성, RGB 전달, PNG 저장 및 디코딩이 포함된다. 앱 PSS는 전후 표본이다. [진행 이벤트 계약](../third_party/to-mnn/docs/cpp/public-api.md#콜백과-실행-종료)

## Galaxy S20+ 최초 검증

2026-11-13 Galaxy S20+ (SM-G986N, Android 13/API 33, arm64-v8a)에서 Library에 등록된 ToonYou Beta 6 (`125771_90840`)을 사용했다. Room v4가 포함된 `17975d2`에 Runtime 변경을 적용한 Debug APK를 `adb install -r`로 설치했다. `main`의 Room v3 APK는 이 기기의 기존 DB를 열 수 없었다. 모델과 Room 스키마를 보존하기 위해 호환 빌드를 사용했다.

네이티브 Runtime은 Android API 28 Release, NDK `27.1.12297006`, `c++_shared`, 정적 ICU 78.3 조합이다. 로컬 to-mnn 체크아웃의 Runtime 및 Android 빌드 입력 57개를 앱의 고정 서브모듈과 비교했다. 아카이브 SHA-256은 `576340bef87343890d3780269c811fa6dba42f99b2c9761e39efabb09e281578`이며 검증한 MNN `cda4a6f` 라이브러리 SHA-256은 `284d1c4b3455a614b7cc03849193564d5a5a3e0d9f8e60c9c559a3e19a49ddaa`다.

OpenCL Buffer, Low/Fast/Low Memory, 정적 LCM 4스텝, guidance 1.0, seed 42, 프롬프트 `masterpiece, best quality, a cute cat`으로 실행했다. Negative prompt는 비웠으며 디스크 캐시·프리뷰·동적 LoRA를 껐다. OS 및 드라이버 캐시와 발열 상태는 제어하지 않았다. 두 APK는 같은 MNN과 Runtime 산출물을 사용했다. 최종 APK에는 JNI 동시 실행 검사도 포함했다. 기존 CLI 기록은 모델·프롬프트와 측정 시작점이 다르다. [to-mnn CLI 측정 조건](../third_party/to-mnn/docs/sd15-discussion.md#2026-11-08--s20-변환추론-검증)

| 구간 | 첫 실행 (초) | 최종 APK (초) |
| --- | ---: | ---: |
| Runtime 생성 | 0.069 | 0.066 |
| CLIP | 5.772 | 5.288 |
| UNet 진입 시점 (generate 시작 기준) | 5.773 | 5.289 |
| UNet 로딩·튜닝 및 스텝 1 | 114.697 | 114.141 |
| 스텝 2 / 3 / 4 | 7.621 / 7.613 / 7.597 | 7.606 / 7.575 / 7.637 |
| 마지막 스텝 이후 UNet 정리 | 0.163 | 0.165 |
| VAE | 19.009 | 18.860 |
| `generate()` | 162.473 | 161.273 |
| PNG 저장·디코딩 | 0.281 | 0.278 |
| 전체 | 163.921 | 162.833 |

두 실행 모두 786,432바이트의 RGB8을 반환했고 디코딩한 PNG가 RGB 픽셀과 일치했다. 최종 결과는 앱 내부 `files/runtime-smoke/results/static-lcm-125771_90840-5959751605821458452.png`에 저장했다. ADB로 회수한 파일의 SHA-256은 `7ab02e4d5275a7467d3f6755c0270ac5a495646fb80fff7cd874a0721c3d16aa`다. 최종 실행의 전후 PSS 표본은 83,996 / 137,787 KiB였다.

동일 기기에서 초기화 오류·실행 전 취소·콜백 예외 전달과 첫 denoising 스텝 이후 취소를 확인했다. 취소 테스트에서 JNI가 반환한 뒤 최종 생성 테스트를 통과했다. GPU 피크 메모리와 누수는 별도로 측정하지 않았다. Converter 계측 테스트 5개를 통과했고, 실계정 Civitai 재다운로드 및 실모델 재변환은 실행하지 않았다.

## Room v4 통합 후 재검증

Room v4와 HQQ 설정의 원본 브랜치(`17975d2`)를 Runtime 브랜치에 병합했다. Room은 v1→v2→v3→v4를 지원하며, v3의 정적 LCM 행과 기존 출력 메타데이터를 보존하는 계측 테스트를 추가했다. 기기의 기존 Library를 같은 서명의 APK로 업데이트했다.

같은 S20+와 ToonYou 출력에서 기존 LCM 설정을 그대로 사용해 PNG 저장·디코딩을 다시 통과했다. 새 MNN의 SHA-256은 `5cb34b31c4210c908ec759134bf6841cf7e600b9331b98ba30138585437f3ea6`이다. CLIP 5.268초, UNet 로딩·튜닝과 첫 스텝 113.035초, 스텝 2–4 각각 7.599 / 7.614 / 7.651초, VAE 20.333초, `generate()` 161.665초, 전체 163.147초였다. OS·드라이버 캐시와 발열은 제어하지 않았다. 출력은 `files/runtime-smoke/results/static-lcm-125771_90840-3680609198582002374.png`이고 ADB로 회수한 SHA-256은 `3a329a12cbaa17f4aecc624c330fb667fb39c4743b90b2f6820914b9292f98cc`다.

별도 `.runtimefixture` 설치에서 ToonYou 변환 파일 사본으로 첫 UNet denoising 스텝을 완료했다. 진행 콜백에서 UNet 세션을 잡은 상태로 중복 JNI 호출 거부를 확인하고, 생성 코루틴의 `Job.cancel()`과 모델 삭제를 요청했다. Room이 `Deleting`인 동안과 JNI 호출이 반환한 직후 파일이 남아 있었고, 이후 코루틴이 종료되며 사본과 Room 행이 삭제됐다. 다음 요청은 초기화 오류를 정상 반환했다. 이 테스트의 취소 지점은 GPU 스텝 사이의 공개 콜백이다. 실행 중인 개별 GPU 커널을 선점하는 동작이나 프로세스 강제 종료는 검증하지 않았다. [격리 테스트 재현](../README.md#실제-정적-lcm-추론-스모크)

## Galaxy S20+ OpenCL 캐시 검증

2026-11-13 Galaxy S20+ (SM-G986N, Android 13/API 33, arm64-v8a)의 기존 ToonYou Beta 6 (`125771_90840`) 변환 결과를 사용했다. Debug APK, `-O3` Runtime, 위와 동일한 MNN `5cb34b31…` 조합에서 OpenCL Buffer, Low/Fast/Low Memory, LCM 4스텝, 512×512, guidance 1.0, seed 42, `masterpiece, best quality, a cute cat`으로 실행했다. Negative prompt는 비웠고 프리뷰와 동적 LoRA는 껐다.

새 `cacheRun` UUID로 `noBackupFilesDir/runtime-smoke/opencl-cache/` 아래에 빈 테스트 캐시를 지정했다. 세 번의 계측 호출은 앱 프로세스를 각각 새로 시작했고(PID 6868 / 7791 / 8199), 두 Warm 실행은 Cold가 채운 디렉터리를 사용했다. [재현 명령](../README.md#실제-정적-lcm-추론-스모크)

| 구간 (초) | 기존 캐시 OFF | Cold | Warm 1 | Warm 2 |
| --- | ---: | ---: | ---: | ---: |
| 전체 | 163.147 | 162.518 | 46.425 | 46.990 |
| Runtime 생성 | 0.067 | 0.070 | 0.067 | 0.075 |
| `generate()` | 161.665 | 160.932 | 45.007 | 45.257 |
| CLIP | 5.268 | 5.379 | 2.065 | 2.126 |
| UNet 로딩·튜닝 및 첫 스텝 | 113.035 | 113.055 | 11.935 | 11.973 |
| 스텝 2 / 3 / 4 | 7.599 / 7.614 / 7.651 | 7.598 / 7.569 / 7.582 | 7.615 / 7.610 / 7.630 | 7.611 / 7.608 / 7.588 |
| 마지막 스텝 이후 UNet 정리 | 0.163 | 0.162 | 0.255 | 0.237 |
| VAE | 20.333 | 19.585 | 7.893 | 8.112 |
| PNG 저장·디코딩 | 0.282 | 0.279 | 0.279 | 0.285 |

Cold가 기록한 CLIP 849,960 B, UNet 983,460 B, VAE 536,244 B는 총 2,369,664 B였다. 잠금 파일은 크기 집계에서 제외했다. 두 Warm 실행 전후에 같은 이름과 크기를 확인했고, 실행 후 세 파일의 수정 시각은 Cold 실행 시간대에 있었다. 프로세스가 바뀐 뒤에도 UNet 로딩·튜닝 및 첫 스텝이 약 101초 줄었고, 이후 스텝은 모두 약 7.6초였다. 이 단계별 차이는 디스크 캐시 재사용의 근거다. 커널별 적중률은 계측하지 않았으며 OS·드라이버 캐시와 발열은 제어하지 않았다.

세 실행 모두 786,432바이트의 RGB8을 반환했고 PNG를 디코딩해 각 RGB 픽셀의 일치를 확인했다. 회수한 세 PNG와 이전 캐시 OFF PNG의 SHA-256은 `3a329a12cbaa17f4aecc624c330fb667fb39c4743b90b2f6820914b9292f98cc`로 같았다.

별도 계측 호출에서 앱의 기본 캐시 경로로도 PNG 생성과 디코딩을 확인했다. 이 호출은 취소 스모크가 남긴 CLIP·UNet 캐시를 읽고 첫 VAE 캐시를 기록했으며 전체 58.076초가 걸렸다. 격리 앱에서는 변환 사본과 캐시가 취소 후 JNI 반환까지 유지되고 이후 함께 삭제됐다. 이전 변환 출력과 캐시의 정리 범위는 JVM 테스트에서 검증했다.

| 앱 메모리 표본 (KiB) | Cold | Warm 1 | Warm 2 |
| --- | ---: | ---: | ---: |
| PSS 표본 최대 | 2,784,857 | 3,809,740 | 3,639,685 |
| RSS 표본 최대 | 2,902,480 | 3,909,032 | 3,749,780 |
| 표본 수 | 70 | 42 | 42 |

두 ADB 세션에서 각각 약 2초 간격으로 `adb shell dumpsys meminfo <pid>`를 조회한 표본을 합쳤다. Cold는 UNet 로딩·튜닝 및 첫 스텝 구간 도중 표본 수집을 시작했다. 표본 최대값은 관측 구간의 앱 프로세스 수치이며 실제 피크와 GPU 전용 메모리는 계측하지 않았다. [Android dumpsys 메모리 지표](https://developer.android.com/tools/dumpsys#meminfo)
