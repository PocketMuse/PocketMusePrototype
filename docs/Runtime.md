# SD 1.5 Runtime JNI

## 빌드와 호출 계약

- `-Psd15RuntimeRoot`로 Windows에서 준비한 to-mnn Android 빌드의 프로젝트 루트를 지정한다. Gradle은 별도 JNI 타깃에 `sd15_runtime`과 ICU의 i18n, uc, data 정적 라이브러리를 링크하고, `libMNN.so` 및 동일 NDK의 `libc++_shared.so`를 ARM64 APK에 넣는다. CMake는 고정 MNN 소스와 라이브러리의 빌드 영수증을 upstream 검증기로 검사한다. Runtime 아카이브와 ICU는 지정한 to-mnn 빌드에서 가져오므로 앱 서브모듈과 같은 리비전의 산출물을 준비한다. [빌드 및 실기기 명령](../README.md#실제-정적-lcm-추론-스모크) · [to-mnn 공개 API](../third_party/to-mnn/docs/cpp/public-api.md#android-앱-연동) · [Gradle 네이티브 빌드](https://developer.android.com/studio/projects/gradle-external-native-builds)
- `ModelDownloads.withStaticLcmOutput(storageKey)`는 앱의 Library에서 Downloaded·Converted·정적 LCM 상태, 템플릿 ID, 세 MNN 파일 및 기록된 크기와 SHA-256을 확인한다. 기존 변환 잠금을 빌린 경로의 수명 동안 잡는다. 삭제 요청이 들어오면 Room에 Deleting을 기록하고 호출이 반환한 뒤 삭제한다. 호출자는 콜백 안에서 생성과 취소 정리까지 기다린다. 이 잠금의 범위는 Workers와 UI가 공유하는 앱 프로세스다.
- `StaticLcmRequest`는 앱에서 읽을 수 있는 모델·Tokenizer 디렉터리의 ASCII 절대 경로와 UTF-8 프롬프트를 받는다. `generate()`는 I/O 스레드에서 JNI의 Runtime을 만들고 한 장을 생성하며 512×512 HWC RGB8과 실제 seed를 반환한다. JNI는 프로세스 내 겹치는 생성 호출을 거부한다. 공개 진행 콜백은 호출 스레드에서 실행된다. 초기화·생성 실패는 각각 `RuntimeInitializationException`과 `RuntimeGenerationException`으로 구분하고 콜백 예외는 원본을 전달한다.
- `requestCancel()`은 실행 전과 실행 중에 반복 호출할 수 있다. 코루틴이 취소되면 네이티브 토큰에 전달하고 작업 스레드가 반환할 때까지 기다린다. 네이티브가 확인한 취소는 `CancellationException`으로 전달한다. Runtime과 모델 세션은 C++ 스코프에서 정리되고 Kotlin은 JNI 반환 후 토큰을 해제한다. [to-mnn 취소·콜백 계약](../third_party/to-mnn/docs/cpp/public-api.md#콜백과-실행-종료)

## Checkpoints

- Android 12 이상에서 앱이 기기의 공개 OpenCL 드라이버를 사용할 수 있도록 `libOpenCL.so`를 선택적으로 선언한다. 확인한 Galaxy S20+는 이 라이브러리를 공개하며, Runtime은 CLIP·UNet·VAE에서 OpenCL 장치 텐서와 동기화 경계를 검사하고 CPU fallback을 거부한다. 현재 사전 빌드 MNN의 ELF LOAD 정렬은 4 KiB다. 이 스모크 APK의 16 KiB 페이지 기기 호환성은 검증 범위 밖이다. [Android 벤더 라이브러리](https://developer.android.com/guide/topics/manifest/uses-native-library-element) · [Android 페이지 크기 지원](https://developer.android.com/guide/practices/page-sizes) · [Runtime 검증 근거](../third_party/to-mnn/docs/cpp/runtime.md#checkpoints)
- 타이밍은 단조 시계를 사용한다. 공개 진행 콜백은 모델 로딩 직전과 각 denoising 스텝 완료 시점에 온다. CLIP 구간은 CLIP 진입부터 UNet 진입까지, UNet 첫 구간은 모델 로딩·튜닝과 첫 스텝을 포함한다. 마지막 스텝부터 VAE 진입까지는 UNet 정리 구간이다. 총시간에는 모델·Tokenizer 확인, Runtime 생성, RGB 전달, PNG 저장 및 디코딩이 포함된다. 앱 PSS는 전후 표본이다. [진행 이벤트 계약](../third_party/to-mnn/docs/cpp/public-api.md#콜백과-실행-종료)

## Galaxy S20+ 검증

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
