# SD 1.5 Runtime JNI

## Chat 이미지 생성

- 기존 Chat에서 최적화된 정적 LCM 모델을 선택하고 프롬프트를 전송한다. Activity 범위 `ImageGenerationViewModel`은 모델 키·이름, 스텝, 프롬프트를 확보하고 한 번에 요청 하나를 실행한다. 프롬프트와 진행·완료·실패·취소 메시지는 ViewModel에 순서대로 쌓인다. 탭 이동, 백그라운드 진입, ViewModel이 유지되는 Activity 재생성 뒤에도 세션이 유지된다. Activity가 종료되면 생성 중인 작업을 취소하고, 새 프로세스의 Chat은 빈 목록으로 시작한다. [Android ViewModel 수명](https://developer.android.com/topic/libraries/architecture/viewmodel#lifecycle)
- 스텝은 2·4·6·8이며 기본값은 4다. LCM, guidance 1.0, 512×512, OpenCL Buffer, Low 정밀도, Fast 튜닝, Low Memory와 영속 디스크 캐시를 사용한다. Negative prompt는 비우고 동적 LoRA는 비활성화한다. 검증된 공용 TAESD 파일을 준비하면 매 스텝 프리뷰를 요청한다. Chat은 seed `-1`로 Runtime에 seed 선택을 맡긴다. [to-mnn 생성 요청 계약](../third_party/to-mnn/docs/cpp/public-api.md#생성-요청)
- APK에 포함된 SD1.5 CLIP `vocab.json`과 `merges.txt`는 생성 전에 `noBackupFilesDir/sd15-tokenizer/`에 준비한다. SHA-256이 맞으면 재사용하고, 누락되거나 손상된 파일은 임시 파일에 복사하고 검증한 뒤 원자적으로 교체한다. 두 파일이 검증된 뒤 앱 전용 디렉터리 경로를 JNI에 전달한다. [고정 리비전의 Tokenizer 원본](https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-v1-5/tree/451f4fe16113bff5a5d2269ed5ad43b0592e9a14/tokenizer)
- Chat은 전송한 프롬프트를 오른쪽 말풍선에 표시하고 입력창을 비운다. 이미지와 진행 상태는 왼쪽 응답 영역에 표시한다. CLIP·UNet 준비·VAE 구간은 단계별 상태로, denoising 구간은 콜백의 `step`과 `total`로 표시한다. 우하단 전송 버튼은 생성 중 정지 버튼으로 바뀐다. 취소를 누르면 코루틴을 취소하고 JNI와 모델 대여가 정리될 때까지 버튼을 비활성화한다. 모델을 사용할 수 없으면 Library에서 상태를 확인하도록 안내한다.
- HWC RGB8을 작업 스레드에서 Bitmap으로 변환한 뒤 Gallery에 저장한다. Chat의 완료 메시지는 파일 참조를 보관하고 Coil이 표시 크기에 맞춰 디코딩한다. 저장에 실패하면 Chat에 오류와 이미지를 표시할 수 있으며 다음 요청에서 그 Bitmap 참조를 해제한다. 메시지 추가 시 최신 항목으로 이동하고, 이전 메시지를 읽는 중에는 진행률 변경이 스크롤을 이동시키지 않는다. [Compose 지연 목록](https://developer.android.com/develop/ui/compose/lists) · [Coil AsyncImage](https://coil-kt.github.io/coil/compose/)

## Gallery

- 왼쪽 메뉴를 열면 PocketMuse 제목과 구분선, 양자화 보정 설정, Gallery 순으로 표시된다. Gallery 제목 아래에는 최신순 2열 썸네일이 이어진다. 이미지를 누르면 원본과 모델명·프롬프트·스텝을 확인할 수 있다. 삭제는 확인 뒤 실행되며 현재 Chat의 해당 이미지에는 사용할 수 없다는 안내가 표시된다.
- `filesDir/generated-images/{uuid}.png`에 원본 PNG를 저장한다. 기존 Room DB의 v4→v5 마이그레이션은 `generated_images` 테이블을 추가한다. 각 행은 ID, 파일명, 생성 시각, 모델명, 프롬프트, 스텝을 보유한다. Gallery와 Library 모델은 독립적으로 삭제할 수 있다. 앱을 다시 열면 Gallery를 조회할 수 있고 Chat 메시지는 새로 시작한다. [앱 전용 파일](https://developer.android.com/training/data-storage/app-specific) · [Room 마이그레이션](https://developer.android.com/training/data-storage/room/migrating-db-versions)
- 저장소는 임시 PNG를 인코딩하고 디코딩 검증을 거쳐 최종 이름으로 이동한 뒤 Room 행을 추가한다. 등록에 실패하면 새 PNG를 정리한다. 삭제는 PNG를 임시 이름으로 옮긴 뒤 Room 행을 지운다. 행 삭제가 실패하면 PNG를 되돌린다. 재실행 시 Gallery에 처음 접근할 때 중단된 삭제를 행의 유무에 따라 복구하거나 마무리하며 등록되지 않은 Gallery 파일과 임시 파일을 정리한다. 파일이 누락된 행은 화면에서 숨긴다. 파일과 메타데이터는 함께 기기 백업과 기기 이전에서 제외한다.

2026-11-13 Galaxy S20+ (SM-G986N, Android 13)에서 기존 ToonYou 모델로 2스텝 생성 취소, Library 이동 및 Activity 재생성, 4스텝 Chat 결과와 Gallery PNG 저장을 확인했다. `am force-stop` 후 다시 실행하니 Chat 목록은 비어 있었고 Gallery에서 512×512 결과와 모델명·프롬프트·스텝을 다시 열 수 있었다.

Debug 앱·계측 APK 빌드, JVM 테스트 72개, `lintDebug`, 실기기 대상 계측 8개가 통과했다. 대상 계측에는 v4 Library 행 보존, PNG 재오픈, 파일 및 DB 오류, 삭제 롤백 및 중단 복구, Gallery 상세·삭제·빈 화면, Chat 누적과 실제 생성이 포함된다. 대량 이미지의 메모리 사용량은 별도로 계측하지 않았다.

## 기존 Chat Runtime 검증

2026-11-13 Galaxy S20+ (SM-G986N, Android 13/API 33)에서 Debug APK를 `adb install -r`로 업데이트하고 기존 ToonYou Beta 6 (`125771_90840`)와 CLIP·UNet·VAE 운영 캐시를 재사용했다. Chat 계측에서 2스텝 요청의 첫 denoising 스텝 직후 취소를 확인했다. 이어 `masterpiece, best quality, a cute cat`으로 4스텝 요청을 전송하고 Library로 이동한 상태에서 Activity를 재생성한 뒤, Chat에서 512×512 이미지 표시를 확인했다. 전송부터 이미지 표시 확인까지의 단일 Warm 표본은 45.091초였으며 탭 이동·Activity 재생성·UI 확인을 포함한다. [Chat 계측 재현 명령](../README.md#실제-정적-lcm-추론-스모크)

`:app:assembleDebug`, `:app:assembleDebugAndroidTest`, JVM 테스트 72개와 Galaxy S20+의 대상 계측 8개가 통과했다. 계측 범위에는 Chat 상호작용, Tokenizer 재사용·손상 복구, RGB 채널 순서, JNI 초기화 오류와 사전 취소, 실제 취소 후 재생성이 포함된다. 이 변경에서는 6·8스텝의 실기기 추론, Cold 캐시 재측정, 다른 기기의 GPU·메모리 안정성을 검증하지 않았다.

## 빌드와 호출 계약

- 일반 Debug·Release 빌드는 to-mnn의 `sd15_runtime` 타깃을 현재 Android 빌드에서 컴파일한다. 외부 `libsd15_runtime.a`는 링크 입력에서 제거했다. 기본 소스는 `third_party/to-mnn`이며, `-Psd15RuntimeRoot`로 같은 리비전의 체크아웃을 지정할 수 있다. 두 체크아웃의 HEAD와 깨끗한 작업 트리를 앱의 Git 서브모듈 기록에 대조한다. 입력이 누락되거나 검증에 실패하면 빌드가 중단된다. `-PconverterOnly=true`는 기존 Converter 테스트와 CI를 위한 명시적 모드다. [빌드 준비](../README.md#runtime-빌드-준비) · [to-mnn 공개 API](../third_party/to-mnn/docs/cpp/public-api.md#android-앱-연동)
- to-mnn의 기존 검증기로 MNN의 소스·빌드 영수증·SHA-256·NDK를 확인한다. Android preset의 CMake 캐시에서 API 28, arm64-v8a, `c++_shared`, NDK 및 vcpkg ICU 경로를 대조하고 ICU 78.3 헤더와 정적 아카이브를 명시적으로 링크한다. MNN에는 16 KiB ELF LOAD 정렬을 요구한다. APK에 MNN과 앱 NDK의 `libc++_shared.so`를 패키징한다. [Gradle 네이티브 빌드](https://developer.android.com/studio/projects/gradle-external-native-builds)
- `ModelDownloads.withStaticLcmOutput(storageKey)`는 앱의 Library에서 Downloaded·Converted·정적 LCM 상태, 템플릿 ID, 세 MNN 파일 및 기록된 크기와 SHA-256을 확인한다. 모델과 OpenCL 캐시 경로를 빌려주는 동안 기존 변환 잠금을 잡는다. 삭제 요청이 들어오면 Room에 Deleting을 기록하고 콜백이 반환한 뒤 삭제한다. 호출자는 콜백 안에서 생성과 취소 정리까지 기다린다. 이 잠금의 범위는 Workers와 UI가 공유하는 앱 프로세스다.
- `StaticLcmRequest`는 앱에서 읽을 수 있는 모델·Tokenizer 디렉터리와 선택적 캐시 디렉터리의 ASCII 절대 경로, UTF-8 프롬프트, 스텝 수를 받는다. `generate()`는 I/O 스레드에서 JNI의 Runtime을 만들고 한 장을 생성하며 512×512 HWC RGB8과 실제 seed를 반환한다. JNI는 프로세스 내 겹치는 생성 호출을 거부한다. 공개 진행 콜백은 호출 스레드에서 실행된다. 초기화·생성 실패는 각각 `RuntimeInitializationException`과 `RuntimeGenerationException`으로 구분하고 콜백 예외는 원본을 전달한다.
- `requestCancel()`은 실행 전과 실행 중에 반복 호출할 수 있다. 코루틴이 취소되면 네이티브 토큰에 전달하고 작업 스레드가 반환할 때까지 기다린다. 네이티브가 확인한 취소는 `CancellationException`으로 전달한다. Runtime과 모델 세션은 C++ 스코프에서 정리되고 Kotlin은 JNI 반환 후 토큰을 해제한다. 프로세스 강제 종료 시 콜백과 정리 블록의 실행은 보장되지 않는다. Room에 기록된 `Deleting`은 다음 상태 대조에서 처리한다. [to-mnn 취소·콜백 계약](../third_party/to-mnn/docs/cpp/public-api.md#콜백과-실행-종료)

## Checkpoints

- TAESD는 체크포인트와 독립적인 공용 APK 자산이다. Chat은 요청마다 `BundledTaesd.prepare(context)`로 확인한 파일의 절대 경로를 `RuntimeOptions.preview_decoder_path`에 전달한다. Runtime은 첫 프리뷰 스텝에서 모델을 로딩하므로 호출자는 `generate()`가 반환될 때까지 파일을 유지한다. [to-mnn 프리뷰 계약](../third_party/to-mnn/docs/cpp/public-api.md#설정과-배포-자원)
- `prepare()`는 I/O 디스패처에서 프로세스 내 호출을 직렬화한다. 앱 전용 `noBackupFilesDir/taesd/taesd.mnn`의 ASCII 절대 경로와 파일 크기·SHA-256을 확인한다. 누락되거나 손상된 파일은 같은 디렉터리의 `taesd.mnn.partial`에 복사하고 검증한 뒤 원자적으로 교체한다. 이전에 남은 임시 파일은 다음 호출에서 정리하며, 설치에 실패하면 `IOException`을 전달한다. 대기 중인 호출은 취소할 수 있고, 이미 시작된 복사는 검증과 정리까지 끝날 수 있다. [Android noBackupFilesDir](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir()) · [Android ATOMIC_MOVE](https://developer.android.com/reference/java/nio/file/StandardCopyOption#ATOMIC_MOVE)
- OpenCL 디스크 캐시는 기본 활성화된다. `noBackupFilesDir/opencl-cache/{storageKey}/{conversionWorkId}`를 JNI에서 `RuntimeOptions.cache_dir`로 전달한다. 같은 변환 UUID는 프로세스가 바뀌어도 같은 경로를 쓴다. `to-mnn`이 CLIP·UNet·VAE 실행 중 캐시를 열고 세션 해제까지 기록하며, 모델·엔진·실행 프로필에 따라 내부 파일을 구분한다. 캐시 경로를 사용할 수 없거나 파일 접근·잠금이 실패하면 Runtime은 디스크 캐시 없이 실행한다. [Android noBackupFilesDir](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir()) · [to-mnn 캐시 계약](../third_party/to-mnn/docs/cpp/public-api.md#설정과-배포-자원)
- 모델 삭제 시 해당 `storageKey`의 캐시를 정리한다. 변환 출력 폐기 시 해당 작업 UUID의 캐시만 정리하며, 새 변환에는 새 UUID를 사용한다. 캐시 정리는 가능한 범위에서 수행한다. 강제 종료나 I/O 오류로 남은 캐시는 해당 UUID에 격리된다.
- Android 12 이상에서 앱이 기기의 공개 OpenCL 드라이버를 사용할 수 있도록 `libOpenCL.so`를 선택적으로 선언한다. Runtime은 CLIP·UNet·VAE에서 OpenCL 장치 텐서와 동기화 경계를 검사하고 CPU fallback을 거부한다. 고정 MNN `cda4a6f`를 같은 NDK와 OpenCL 설정으로 다시 링크했다. Debug·Release·격리 APK의 ARM64 공유 라이브러리 6개 모두에서 ELF LOAD 정렬 `0x4000`과 `zipalign -c -P 16 4` 통과를 확인했다. 연결된 S20+의 페이지 크기는 4096바이트이며 16 KiB 실행 환경은 확보하지 못했다. [Android 벤더 라이브러리](https://developer.android.com/guide/topics/manifest/uses-native-library-element) · [Android 페이지 크기 지원](https://developer.android.com/guide/practices/page-sizes) · [Runtime 검증 근거](../third_party/to-mnn/docs/cpp/runtime.md#checkpoints)
- 타이밍은 단조 시계를 사용한다. 공개 진행 콜백은 모델 로딩 직전과 각 denoising 스텝 완료 시점에 온다. CLIP 구간은 CLIP 진입부터 UNet 진입까지, UNet 첫 구간은 모델 로딩·튜닝과 첫 스텝을 포함한다. 마지막 스텝부터 VAE 진입까지는 UNet 정리 구간이다. 총시간에는 모델·Tokenizer 확인, Runtime 생성, RGB 전달, PNG 저장 및 디코딩이 포함된다. 앱 PSS는 전후 표본이다. [진행 이벤트 계약](../third_party/to-mnn/docs/cpp/public-api.md#콜백과-실행-종료)

## TAESD 실시간 프리뷰

- TAESD 파일과 `GenerationPreview` 콜백을 함께 제공하면 JNI는 `preview_interval = 1`을 설정한다. Runtime은 denoising 진행 이벤트 직후 clean prediction을 TAESD로 디코딩한다. TAESD는 UNet 구간에서 로딩·재사용되고 최종 VAE 전에 해제된다. 모델별 기존 OpenCL 캐시 경로에 Runtime이 별도 `preview-*.mnnc` 파일을 관리한다. [to-mnn 실행 순서와 세션 수명](../third_party/to-mnn/cpp/runtime/src/runtime.cpp)
- 콜백은 JNI를 호출한 I/O 작업 스레드에서 순차 실행된다. Kotlin은 1부터 시작하는 스텝 번호, 전체 스텝 수, 너비·높이, 소유권이 있는 512×512 HWC RGB8 `ByteArray`와 생성 시작 기준 경과 시간을 받는다. JNI는 콜백 동안만 유효한 C++ 픽셀을 한 번 복사하고 콜백 뒤 로컬 참조를 해제한다. Kotlin은 같은 작업 스레드에서 최종 이미지와 동일한 RGB 변환을 사용한다. [Android JNI 참조와 배열](https://developer.android.com/training/articles/perf-jni)
- ViewModel은 현재 요청의 더 최신 스텝만 반영한다. 진행 상태, VAE 디코딩 및 PNG 저장 중에도 최신 프리뷰를 유지한다. Chat은 기존 1:1 이미지 영역에서 프리뷰임을 표시하고, 완료 파일을 읽는 동안 화면에 이미 표시된 프리뷰를 잠깐 유지한다. 완료 메시지는 최종 파일 경로를 보관한다. Room과 Gallery PNG에는 최종 VAE 결과 한 장을 저장한다.
- 준비 중 `IOException`이 발생하면 원인을 로그에 남기고 프리뷰 없이 생성한다. 네이티브 TAESD 로딩·실행 오류는 `RuntimeGenerationException`으로 처리하며, 콜백에서 발생한 JVM 예외는 정리 후 원본을 전달한다. 공개 Runtime 계약에는 디코딩·콜백 실패 후 같은 생성 요청을 이어가는 경로가 없다. 취소를 게시하면 늦은 프리뷰를 폐기하며, 네이티브 호출과 모델 대여가 종료된 뒤 취소 완료를 게시한다. 저장 실패 시에는 기존 최종 Bitmap과 오류를 표시한다. [to-mnn 콜백과 실행 종료](../third_party/to-mnn/docs/cpp/public-api.md#콜백과-실행-종료)

### 실기기 재현

변환된 ToonYou Beta 6가 등록된 ARM64 기기에서 [README의 빌드와 `adb install -r`](../README.md#실제-정적-lcm-추론-스모크) 절차 후 실행한다. `storageKey`와 `chatModelName`은 기기의 Library에 맞춘다. Runtime 테스트는 앱 번들 Tokenizer를 사용한다. Chat 계측은 최종 이미지 한 장을 Gallery에 남긴다.

```powershell
$pkg = 'com.example.pocketmuseprototype'
$runner = "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
$smoke = "$pkg.runtime.StaticLcmSmokeTest"
$cases = "$smoke#comparesFixedSeedWithAndWithoutFourTaesdPreviews,$smoke#cancelsFromTheFirstTaesdCallbackAndPreservesCallbackFailure"
adb shell am instrument -w -r -e class $cases -e taesdSmoke true -e storageKey 125771_90840 $runner
adb shell am instrument -w -r -e class "$pkg.runtime.ChatGenerationAndroidTest" -e chatModelName ToonYou $runner
```

### Galaxy S20+ 측정

2026-11-13 Galaxy S20+ (SM-G986N, Android 13/API 33)에서 arm64 Debug APK, ToonYou Beta 6 (`125771_90840`), 프롬프트 `masterpiece, best quality, a cute cat`, seed 42, 정적 LCM 4스텝, guidance 1.0, OpenCL Buffer Low/Fast/Low Memory로 실행했다. 비교마다 기존 CLIP·UNet·VAE 캐시를 사용했다. 첫 비교에서는 TAESD 캐시를 생성했고, 두 번째 비교에서는 같은 캐시로 끔 1회와 켬 2회를 같은 프로세스에서 순서대로 실행했다. OS·드라이버 캐시와 발열은 제어하지 않았다.

| TAESD 상태 | 요청 시작~JNI 결과 (초) | 첫 프리뷰 콜백 (초) | 스텝 1·2·3·4 프리뷰 구간 (초) | VAE 구간 (초) |
| --- | ---: | ---: | --- | ---: |
| 끔, 첫 비교 | 44.86 | | | 7.75 |
| 켬, 프리뷰 캐시 생성 | 50.16 | 17.55 | 3.03 / 0.50 / 0.50 / 0.51 | 8.00 |
| 끔, 재비교 | 45.71 | | | 8.21 |
| 켬, 프리뷰 캐시 재사용 1 | 47.63 | 14.65 | 0.76 / 0.51 / 0.51 / 0.51 | 8.25 |
| 켬, 프리뷰 캐시 재사용 2 | 47.80 | 14.90 | 0.77 / 0.51 / 0.51 / 0.51 | 8.19 |

JNI 타이밍에는 모델·번들 파일 준비와 UI 표시·저장 시간이 빠져 있다. 프리뷰 구간은 denoising 알림에서 다음 프리뷰의 JNI 복사 직전까지이며 첫 스텝에 TAESD 세션 로딩과 튜닝을 포함한다. VAE 구간은 VAE 진입부터 Runtime의 결과 반환까지다. 프리뷰는 매 실행 1~4 순서로 786,432바이트씩 전달됐다. 재비교에서 연속 프레임의 RGB 해시는 서로 달랐고, 최종 RGB8의 SHA-256은 프리뷰를 켰을 때와 껐을 때 같았다. 첫 실행에서 만든 361,220바이트 `preview-*.mnnc`가 재실행 시작 시 이미 있었고 같은 이름과 크기로 유지됐다.

| 재비교 | 앱 PSS 표본 최대 (KiB) | 앱 RSS 표본 최대 (KiB) | 종료 직후 `Debug.getPss()` (KiB) |
| --- | ---: | ---: | ---: |
| 끔 | 3,543,028 | 3,642,052 | 134,962 |
| 켬 1 | 3,631,630 | 3,744,036 | 137,127 |
| 켬 2 | 3,971,561 | 4,083,256 | 137,475 |

호스트에서 약 2.2초 간격으로 `adb shell dumpsys meminfo -s com.example.pocketmuseprototype`를 호출했다. 표본 최대값은 샘플 간 할당을 놓칠 수 있고, 관측된 실행 간 편차가 있어 프리뷰 자체의 메모리 비용으로 환산할 수 없다. 4스텝 재비교는 각 실행 종료 후 약 132~134 MiB PSS로 내려왔다. 장시간 반복 생성과 다른 기기의 GPU·메모리 안정성은 확인하지 않았다.

같은 기기에서 첫 프리뷰 콜백 안의 취소가 스텝 1에서 종료됐고 직후 PSS는 169,207 KiB였다. 콜백이 던진 JVM 예외의 동일성과 누락된 TAESD 경로의 Runtime 생성 오류를 확인했다. 디코더가 실행 도중 실패하는 GPU 오류는 주입하지 않았다. Chat 계측은 첫 프리뷰 표시 후 취소하고, 이어 4스텝 요청 중 Library 이동과 Activity 재생성을 거쳐 마지막 프리뷰와 최종 결과를 표시했다. Gallery에는 요청당 최종 512×512 PNG 한 장과 메타데이터가 추가됐다. 최종 UI 빌드의 Warm 표본은 전송부터 첫 프리뷰 표시까지 14.32초, Activity 재생성과 PNG 저장을 포함한 최종 표시까지 47.05초였다. 6·8스텝의 실제 콜백 횟수는 검증하지 않았다.

Debug 앱·계측 APK 빌드, JVM 테스트 72개, `lintDebug` 및 대상 실기기 계측 16개가 통과했다. 실기기 계측은 JNI·TAESD, Tokenizer와 RGB 변환, 프리뷰 상태 수명과 저장 실패, Chat·Gallery 동작을 포함한다.

## TAESD 프리뷰 디코더 번들

공식 `madebyollin/taesd`의 `fdbae2df989db95761f482efb8977e1182fb68d2`에서 SD1/2용 `taesd_decoder.pth`를 사용한다. APK는 `assets/taesd/taesd.mnn`과 저작권·허가 문구를 포함한 MIT `LICENSE` 원문을 배포한다. PyTorch와 ONNX 파일은 개발용이다. MNN 가중치는 단일 파일에 내장되어 있다. [공식 가중치](https://github.com/madebyollin/taesd/blob/fdbae2df989db95761f482efb8977e1182fb68d2/taesd_decoder.pth) · [MIT 원문](https://github.com/madebyollin/taesd/blob/fdbae2df989db95761f482efb8977e1182fb68d2/LICENSE)

| 산출물 | 크기(바이트) | SHA-256 |
| --- | ---: | --- |
| `taesd_decoder.pth` | 4,913,399 | `02873377c3f4659cd9f9adb2f718dcb434ba4c7bba3af3ee7bb95cbdabe2d3cf` |
| `taesd.onnx` | 4,904,099 | `46ac89f4e9daa142f55f0d2fb18966e16ead16f14a1748ef3b9717dd4b95978a` |
| `taesd.mnn` | 4,901,708 | `7f2f997fd230d9d752e741c71e79f6595384df7add2eaa8697ef11376cd0d808` |

### 변환 재현

[Runtime 빌드 환경](../README.md#runtime-빌드-준비)의 Windows Git Bash에서 `third_party/to-mnn`으로 이동해 아래 명령을 실행한다. MNNConvert는 Runtime과 같은 공식 MNN 3.6.1 커밋 `cda4a6f441b8ed58cc5c6f389359e5851e9d6322`에서 빌드한다. [고정 빌드 스크립트](../third_party/to-mnn/scripts/build-mnn-converter.sh)

```bash
uv sync --locked
bash scripts/build-mnn-converter.sh
```

앱 프로젝트 루트의 PowerShell에서 실행한다. 검증한 Python 환경은 3.12.12, PyTorch 2.14.0+cpu, ONNX 1.23.0이다. 변환은 기존 `CONVERT_OPTIONS`를 사용하고 FP32 가중치를 유지한다. 변환기가 기본으로 생성하는 임의 UUID를 고정해 동일한 MNN SHA-256을 재현했다. [ONNX 내보내기](../third_party/to-mnn/src/sd15/export_taesd.py) · [변환 옵션](../third_party/to-mnn/src/sd15/export_mnn.py) · [MNN UUID 기록](https://github.com/alibaba/MNN/blob/cda4a6f441b8ed58cc5c6f389359e5851e9d6322/tools/converter/source/common/AddUUID.cpp)

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$stage = (New-Item -ItemType Directory -Force build/taesd-reproduce).FullName
$upstream = 'https://raw.githubusercontent.com/madebyollin/taesd/fdbae2df989db95761f482efb8977e1182fb68d2'
foreach ($name in 'taesd_decoder.pth', 'LICENSE') {
    curl.exe --fail --location "$upstream/$name" --output "$stage/$name"
}
if ((Get-FileHash "$stage/taesd_decoder.pth").Hash -ne '02873377c3f4659cd9f9adb2f718dcb434ba4c7bba3af3ee7bb95cbdabe2d3cf') {
    throw 'Unexpected TAESD checkpoint hash'
}

Push-Location third_party/to-mnn
try {
    $python = '.venv/Scripts/python.exe'
    & $python -B -m scripts.tools.verify_mnn_converter
    & $python -B src/sd15/export_taesd.py --checkpoint "$stage/taesd_decoder.pth" --output "$stage/taesd.onnx"
    '{"version":"0.0.0","mnn_uuid":"105bcdc8-9887-4370-97d0-b585d7f5289f"}' |
        Set-Content -Encoding ascii "$stage/conversion.json"
    & MNNRuntime/build-converter-cda4a6f/MNNConvert.exe -f ONNX --modelFile "$stage/taesd.onnx" `
        --MNNModel "$stage/taesd.mnn" --transformerFuse --optimizePrefer 2 --optimizeLevel 1 `
        --compressionParamsFile "$stage/conversion.json"
} finally {
    Pop-Location
}
Get-FileHash "$stage/taesd.onnx", "$stage/taesd.mnn"
```

### 검증

같은 소스 기준에서 이전 APK와 패키징 중간 산출물을 옮긴 뒤 두 빌드를 각각 다시 패키징했다. APK 전체 증가량에는 모델, MIT 고지, 설치 코드와 패키징 오버헤드가 포함된다.

| APK | 적용 전(바이트) | 적용 후(바이트) | 증가(바이트) | 모델 ZIP 엔트리(바이트) |
| --- | ---: | ---: | ---: | ---: |
| Debug | 81,867,054 | 86,457,564 | 4,590,510 | 4,573,248 |
| Release (unsigned) | 70,951,438 | 75,528,948 | 4,577,510 | 4,560,260 |

- ONNX 검사기와 호스트 MNN Express CPU에서 입력 `latent_sample` NCHW FP32 `[1, 4, 64, 64]`, 출력 `sample` NCHW FP32 `[1, 3, 512, 512]`를 확인했다. NumPy PCG64 seed 42의 표준정규 FP32 latent 1개로 실제 디코딩해 ONNX Runtime CPU와 비교했다. FP32 가중치의 최대·평균 절대 오차는 각각 `7.33e-6`, `2.42e-7`이었다. 같은 입력에서 기존 헬퍼의 FP16 가중치 옵션은 최대 오차 `0.0187`로, 이번 비교 기준 `0.01`을 초과했다. [Runtime의 TAESD 입출력 검사](../third_party/to-mnn/cpp/runtime/src/mnn/express_preview_session.cpp)
- Debug·Release·Debug 계측 APK 빌드, JVM 테스트 72개, `lintDebug`가 통과했다. Lint 결과는 오류 0개, 경고 37개였다. 두 앱 APK에서 모델과 라이선스의 SHA-256이 소스 자산과 일치했고, Runtime·Converter·MNN 네이티브 라이브러리의 SHA-256도 변경 전 APK와 일치했다.
- Galaxy S20+(Android 13/API 33)에 Debug APK를 설치해 대상 계측 테스트 9개가 통과했다. TAESD 최초 설치와 동시 호출, 재사용, 동일 크기 손상과 누락 복구, 남은 임시 파일 정리, 경로 접근 및 해시를 확인했다. 기존 Tokenizer·RGB, Runtime 초기화 오류·사전 취소, Converter 계약도 검사했다. 복사 중 실제 프로세스 종료, Android OpenCL TAESD 실행, 전체 이미지 생성은 이번 검증 범위 밖이다.

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
