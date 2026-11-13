# SD 1.5 Converter JNI

## 백그라운드 변환

새 체크포인트의 검증과 원자적 공개가 끝나면 Room이 다운로드 완료와 정적 LCM 변환 의도를 함께 기록한다. 원본과 공용 LoRA가 모두 검증되면 `model-conversions` 고유 WorkManager 체인이 네트워크 제약 없이 변환을 순차 실행한다. `model-downloads`는 독립적으로 전송을 계속한다. [WorkManager 작업 연결](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/chain-work)

- 신규 변환은 `WaitingForLcm → Queued → Validating → Converting → Finalizing → Converted`로 진행한다. 대기 상태는 Room에 기록하고 LoRA가 검증될 때 WorkManager에 등록한다. 앱의 프로세스 스코프가 공용 LoRA의 Ready와 WorkManager 상태 변경을 관찰한다. 앱 시작·복귀에도 상태를 대조한다. 예약에 실패하거나 예약 전 프로세스가 사라지면 저장된 UUID로 다시 예약한다. 사용자가 변환을 취소하거나 모델을 삭제하면 Ready 이벤트 이후에도 작업을 시작하지 않는다.
- 변환 전에 `withVerifiedFile`에서 파일을 다시 확인한다. 사라졌으면 새 UUID로 대기 상태를 기록한다. LoRA 사용 잠금 안에서 `ConversionRequest`를 만들고 네이티브 실행, 진행 콜백, 취소 전달과 종료 대기를 완료한다. 출력 해시 계산은 잠금을 놓은 뒤 진행한다.
- Room v3은 v2의 기존 작업을 `sd15-hqq-b128-v1`로 식별하며 작업 ID와 출력 정보를 보존한다. Library에서 **최적화**를 선택하면 원본을 재사용해 새 UUID로 변환한다. 이전 출력은 대기·실패·취소 중에도 메타데이터와 함께 보존한다. 새 출력을 Room에 완료로 등록한 뒤에만 이전 출력을 삭제하며, 중단된 정리는 다음 상태 대조에서 이어간다. [카드와 모달 상태](Model-Downloads.md#library-카드와-모달), [Room 마이그레이션](https://developer.android.com/training/data-storage/room/migrating-db-versions)
- C++ 호출은 작업 스레드에서 실행한다. JNI 콜백은 단계 변경 때와 진행 중 0.5초 이상의 간격으로 이벤트를 전달하며 별도 코루틴에서 Room과 알림을 갱신한다. 사용자 취소와 삭제는 의도를 먼저 기록하고 `requestCancel()`을 전달한다. OS가 Worker를 멈춰도 새 변환과 파일 삭제는 C++ 호출이 반환하고 파일 핸들이 닫힐 때까지 기다린다. 다시 실행할 때 Converter가 소유권을 확인한 `.partial`만 정리한다. 각 변환 시도에는 별도의 UUID 경로를 할당한다. [CoroutineWorker 중단 계약](https://developer.android.com/develop/background-work/background-tasks/persistent/threading/coroutineworker)
- 변환은 HQQ W8 B128, 청크 1 MiB, 스레드 4개를 사용한다. PocketMuse 메뉴의 **양자화 보정 횟수**는 0·5·10·15·20회 중에서 선택하며 기본값은 0회다. 0회에서는 초기 min/max로 W8 양자화를 수행한다. 저장소의 사용 가능 공간을 manifest의 출력 크기 합과 비교한다. 공간은 예약되지 않으므로 Converter의 쓰기·공간 오류도 기록한다. 변환 실패나 취소 뒤 원본은 Library에서 다시 사용할 수 있다. [to-mnn 실행 옵션](../third_party/to-mnn/docs/cpp/public-api.md#입력과-실행-수명)
- Preferences DataStore의 기본값은 다운로드 검증 직후 또는 Library에서 사용자가 최적화·재시도를 요청할 때 읽는다. Room v4의 `conversionHqqIterations`에 작업 UUID와 함께 저장한다. LoRA 대기와 자동 복구는 이미 기록된 값을 사용한다. v3에서 넘어온 행은 보정 횟수가 비어 있으며, 이전 작업을 이어서 실행할 때는 기존 앱 기본값인 0회를 사용한다. 이미 공개된 결과의 과거 보정 횟수는 추정하지 않는다.
- Runtime이 사용할 출력 경로는 앱 전용 외부 저장소의 `models/{storageKey}/converted/{conversionWorkId}/`다. `Converted` 행의 `conversionRecipeId`, `conversionTemplateId`, `conversionFiles`(이름 → 바이트 수), `conversionHashes`(이름 → SHA-256)가 출력 계약이다. 템플릿 ID는 두 레시피에서 `sd15-hqq-b128-manual-v1`이다. 정적 LCM 레시피 `sd15-static-lcm-v1`은 아래 Checkpoints에 기록한 공식 LoRA를 강도 1.0으로 병합한 HQQ 모델을 가리킨다. 네이티브 호출 전에 시도 ID와 레시피에 템플릿 및 기대 파일을 연결하므로 공개 뒤 Room 해시 기록이 중단돼도 같은 시도와 레시피로 결과를 복구한다. 앱 재시작 후 해시를 대조하고 같은 프로세스에서는 파일 크기 및 변경 시각을 확인해 필요한 경우 다시 해시한다. 교체 중에는 `legacyOutput`에 이전 Legacy 결과의 UUID, 템플릿, 파일 크기와 해시를 유지한다.
- 이전 UUID의 staging 정리가 실패하면 해당 오류 코드를 기록하고 새 변환을 시작하기 전에 멈춘다. Converter가 소유권을 확인할 수 없는 파일과 원본은 보존한다. 변환 재시도에서 정리를 다시 시도한다. 삭제 중 정리가 실패하면 `Deleting` 행과 원본을 유지하며, 이후 reconcile에서 정리와 삭제를 다시 시도한다.
- 저장된 출력 메타데이터는 하나 이상의 안전한 `.mnn` 파일 이름과 양의 정수 바이트 수로 구성된 JSON 객체여야 한다. 잘못된 메타데이터는 해당 모델의 `OUTPUT_INVALID`로 기록한다. 다른 모델의 복구는 계속되며 해당 모델은 보존된 원본으로 다시 변환할 수 있다.

### Checkpoints

- `sd15-hqq-b128-manual-v1`은 2026-11-08 Galaxy S20+ CLI 및 후속 APK 실험에 사용한 `sd15-hqq-b128-manual`을 기기 `/data/local/tmp/to-mnn-converter-20261108T113613Z-2379/template/`에서 회수한 것이다. 당시 입력 `v1-5-pruned-emaonly.safetensors`의 SHA-256 `6ce0161689b3853acaa03779ec93eafe75a02f4ced659bee03f50797806fa2fa`는 SD1.5 공개 체크포인트와 일치한다. 템플릿은 해당 모델의 파생 자산으로 CreativeML Open RAIL-M 사용 제한을 적용하며 원문을 APK 자산에 포함한다. [기존 기기 실험](https://github.com/PocketMuse/to-mnn/blob/b5a06df6944cd8b6ff22048396281d04e926c7fe/docs/sd15-discussion.md#2026-11-08--s20-변환추론-검증) · [체크포인트 해시](https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-v1-5/raw/main/v1-5-pruned-emaonly.safetensors) · [배포 라이선스](../app/src/main/assets/sd15-hqq-b128-manual-v1/LICENSE)
- 기기와 APK 입력의 SHA-256은 `graph.bin` `ea5fe7499768394097d627beb2ce29ec2b96f10ab76c4bd9bdf1fc425a3933ed`, `manifest.json` `5ba4e454c0345ad5cb138af374d34af6b0199b41c7b176e5bb70387640f04b80`이다. manifest v2/MNN 3.6.1은 `text_encoder.mnn` 246,509,140 B, `unet.mnn` 885,095,992 B, `vae_decoder.mnn` 132,659,232 B를 지정한다. 최초 변환 시 내부 `files/conversion-templates/sd15-hqq-b128-manual-v1/`에 두 파일을 해시 확인 후 원자적으로 공개한다. 다음 변환은 검증된 사본을 공유한다.
- `dataSync`에는 로컬 파일 처리가 포함되어 기존 WorkManager 포그라운드 서비스와 권한을 공유한다. API 31+ 백그라운드 승격 제한은 앱에서 재시도할 수 있는 중단으로 처리한다. API 33+ 알림 권한은 알림 서랍의 표시를 제어한다. API 34+ 유형별 권한, API 35+ 백그라운드에서 공유하는 24시간당 6시간 `dataSync` 한도, API 36+ JobScheduler 할당량이 적용된다. 강제 종료 후에는 OS가 허용하는 시점에 상태를 대조한다. [서비스 유형](https://developer.android.com/develop/background-work/services/fgs/service-types) · [백그라운드 시작 제한](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) · [서비스 시간 제한](https://developer.android.com/develop/background-work/services/fgs/timeout) · [장시간 Worker](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)

### Galaxy S20+ 검증

HQQ 보정 0회 적용 후 Galaxy S20+ (SM-G986N, Android 13/API 33)의 격리 `RelWithDebInfo` APK에서 동일한 SD 1.5 체크포인트, 공식 LoRA와 내장 템플릿으로 백그라운드 스모크를 1회 실행했다. 스레드 4개, 청크 1 MiB를 사용했다. 변환 취소·정리 후 재시도에서 LoRA 상태 대조부터 Library 등록까지 33.476초, 앱 로그 기준 Converter 내부 31.369초, HQQ 계산 4.245초였다. 로그에서 0회를 확인했고 Room의 파일 크기·SHA-256이 실제 출력과 일치했다. 파일 캐시와 클록은 제어하지 않았고 생성 이미지 품질은 측정하지 않았다.

2026-11-12 Galaxy S20+ (SM-G986N, Android 13/API 33)에서 격리 `RelWithDebInfo` APK로 정적 LCM 백그라운드 변환을 검증했다. 기존 CLI와 동일한 SD 1.5 원본, 공식 LoRA와 내장 템플릿을 사용했다. 스레드 4개, 청크 1 MiB, HQQ 보정 20회를 사용했고 파일 캐시와 클록은 제어하지 않았다. 원본 공개 후 LoRA가 없을 때 WorkManager 미등록을 확인했다. 변환 중 취소와 공용 LoRA 삭제를 요청하고, staging 정리 및 원본 보존을 확인했다. LoRA를 다시 준비하자 새 작업이 예약됐고, 홈 화면에서 변환을 완료했다. [재현 명령](../README.md#실제-백그라운드-변환-스모크)

WorkManager의 성공 상태, Room에 등록한 해시 및 실제 출력의 이름·크기·SHA-256을 확인했다. 출력 세 파일은 같은 입력을 사용한 4스레드 CLI 기준과 일치했다. 프로세스를 종료하고 Library를 다시 열어 정적 LCM 완료 상태를 확인했다. Library에서 테스트 모델을 삭제한 뒤에도 공용 LoRA가 유지됐다.

| 파일 | SHA-256 |
| --- | --- |
| `text_encoder.mnn` | `1f9a7b80805b4df460e10339d4401287a2e9cd628ff247dae05a0f9e148c1cfc` |
| `unet.mnn` | `3f06996145268ccd462f1d9e3b7707c5cc71773aadcb009f11bcc5b852c70763` |
| `vae_decoder.mnn` | `6b44712b4ba3e7b04ec1b3739bb99e0f5fb0325331fc4e49acf42ba84c82cc4f` |

정적 LCM 완료 실행 2회의 LoRA Ready 알림부터 Room 등록까지 걸린 시간은 각각 121.759초와 130.834초였다. Converter 내부 시간은 120.007초와 128.642초, HQQ 계산은 83.105초와 87.064초였다. 실제 Civitai·Hugging Face 전송, 변환 중 프로세스 강제 종료, Android 14–16의 작업 제한은 이 정적 LCM 실험에 포함하지 않았다.

#### 기존 일반 HQQ 검증

2026-11-11 Galaxy S20+ (SM-G986N, Android 13/API 33)의 격리 `RelWithDebInfo` (`-O2`) APK에서 기존 4,265,146,304바이트 체크포인트를 재사용했다. 검증·공개 직후 상태에서 자동 예약, 변환 중 취소 후 staging 정리, 원본을 사용한 재시도, 홈 화면에서 변환 완료, 앱 재실행 뒤 Library 표시와 삭제를 확인했다. 변환된 파일 세 개의 SHA-256이 기존 4스레드 CLI 결과와 일치했고 원본 해시도 유지됐다.

백그라운드 실행 1회에서 재시도 요청부터 Room 등록까지 81.813초, Converter 내부 79.767초, HQQ 계산 66.226초를 기록했다. 캐시·클록은 제어하지 않았고 배터리 온도는 31.3 → 33.1 °C였다. 변환 후반부 `dumpsys meminfo` 표본의 최대 앱 PSS는 100,256 KiB였고 출력 공개 시점 표본은 97,231 KiB였다.

별도 복구 실행 1회에서는 변환 중 앱을 강제 종료해 소유권 마커가 있는 `.partial`을 확인했다. 앱을 열자 같은 WorkManager ID로 staging을 정리하고 처음부터 변환했으며, 알림과 Library 진행률이 다시 표시됐다. 결과 세 개의 SHA-256은 기존 CLI 결과와 일치했다. Converter 내부 77.808초, HQQ 66.674초였다. 화면이 열린 복구 실행에서 약 4초 간격으로 관측한 최대 앱 PSS는 145,768 KiB였고 완료 확인 뒤 134,885 KiB였다. 실계정 Civitai 전송, Android 14–16의 실제 스케줄링 제한, 디스크가 가득 찬 기기에서의 동작은 이 실험에서 측정하지 않았다.

## 호출 계약

- `ConversionOptions`에 앱이 읽을 수 있는 체크포인트와 템플릿의 절대 파일 경로를 전달한다. `templateDir`에는 짝이 맞는 `graph.bin`과 `manifest.json`이 있어야 한다. 앱이 쓸 수 있는 출력 부모 디렉터리를 먼저 만든다. 기본값은 청크 1 MiB, HQQ 보정 0회, 계산 스레드 1개다. 보정 횟수는 실행 옵션이며 템플릿 manifest에는 원래 레시피 값인 20을 유지한다. 유효 범위와 호환성 검사는 to-mnn을 따른다. [to-mnn 공개 API](https://github.com/PocketMuse/to-mnn/blob/a8bfba3389fa6fdac060cb17b3ee7f39669e5e9e/docs/cpp/public-api.md#converter-contracts)
- `lcmLora: File? = null`은 선택적인 입력이다. 생략하면 일반 HQQ 변환을 수행하며 마이그레이션으로 보존된 Legacy 시도에 이 경로를 사용한다. 신규 변환은 공용 LoRA의 검증된 절대 경로를 전달하고 `run()`이 반환할 때까지 파일을 유지한다. Converter가 크기·SHA-256·safetensors 구조와 체크포인트·HQQ B128 템플릿의 호환성을 확인한 뒤 강도 1.0으로 병합한다. 파일을 열 수 없으면 `READ_FAILED`, 공식 파일의 크기나 해시와 다르면 `INVALID_INPUT`을 반환한다. 성공한 출력에는 병합된 UNet과 CLIP·VAE가 포함된다. [정적 병합 계약](https://github.com/PocketMuse/to-mnn/blob/a8bfba3389fa6fdac060cb17b3ee7f39669e5e9e/docs/cpp/converter.md)
- `ConversionRequest(options).run(onProgress)`는 호출 스레드에서 동기 실행된다. 파일 I/O를 허용하는 작업 스레드에서 호출한다. 콜백은 같은 스레드에서 실행되며 단계, 컴포넌트, 바이트 진행률 및 시간 정보를 받는다. 공개 전 콜백 예외는 `CALLBACK_FAILED`로 전달되고 정리에 실패하면 `CLEANUP_FAILED`가 우선한다. `Completed`는 출력 공개 후에 전달되며 그 시점의 취소나 콜백 예외 이후에도 성공이 유지된다.
- 각 요청은 한 번 실행할 수 있다. `requestCancel()`은 실행 전이나 다른 스레드에서도 호출할 수 있다. `run()`이 `Cancelled`를 반환하면 취소 처리와 정리가 끝난 상태다. 입력과 출력 경로의 소유권은 반환까지 호출자가 유지한다. 변환과 정리는 프로세스 내에서 한 번에 하나만 실행한다. 다른 호출과 경합하거나 진행 콜백에서 새 요청을 실행하거나 정리를 호출하면 `BUSY`를 반환한다.
- `ConversionResult`는 상태, 오류 코드, 공개된 파일의 상대 이름과 크기, 소요 시간과 HQQ 계측값을 제공한다. 오류 코드는 to-mnn의 공개 식별자와 일치한다. 네이티브 진단 메시지·경로·텐서명은 Kotlin 결과에 포함하지 않는다. JVM 할당 실패나 JNI 연결 오류는 예외로 전달될 수 있다. `Succeeded`를 반환한 출력만 사용한다.
- 중단 후 잔여 출력 정리가 필요하면 해당 경로의 배타적 소유권을 확보하고 `ConversionRequest.cleanupPartial(outputDir)`를 호출한다. Converter가 소유 표식과 파일을 검사한다. 기존 최종 출력은 덮어쓰지 않으며, 소유를 확인할 수 없는 `.partial`은 보존한다. [출력 및 정리 계약](https://github.com/PocketMuse/to-mnn/blob/a8bfba3389fa6fdac060cb17b3ee7f39669e5e9e/docs/cpp/public-api.md#결과와-잔여-출력-정리)

## Checkpoints

- AGP의 NDK 도구 체인에서 to-mnn의 `cpp/converter` CMake 하위 디렉터리만 추가한다. to-mnn의 vcpkg 기준과 같은 nlohmann/json 3.12.0 소스를 해시로 고정하고, 생성된 CMake 패키지 설정을 `sd15_converter`의 `find_package()`에 연결한다. 별도 vcpkg Android 빌드나 사전 빌드한 `.a`를 가져오는 방식은 도구 체인과 ABI를 따로 맞춰야 한다. 소스 통합으로 Converter와 JNI가 앱이 고정한 NDK, API 29, C++17 및 `c++_shared`를 공유한다. [Android NDK CMake](https://developer.android.com/ndk/guides/cmake), [nlohmann/json CMake 통합](https://nlohmann.github.io/json/integration/cmake/)
- to-mnn `a8bfba3389fa6fdac060cb17b3ee7f39669e5e9e`의 정적 LCM 변환은 공식 어댑터 134,621,556바이트와 SHA-256 `8f90d840e075ff588a58e22c6586e2ae9a6f7922996ee6649a7f01072333afe4`를 요구한다. CMake는 to-mnn의 vcpkg 기준과 같은 PicoSHA2 1.0.1 아카이브를 SHA-512로 확인하고 헤더 경로만 `PICOSHA2_INCLUDE_DIR`로 전달한다. [어댑터 검증 소스](https://github.com/PocketMuse/to-mnn/blob/a8bfba3389fa6fdac060cb17b3ee7f39669e5e9e/cpp/converter/src/conversion/converter.cpp#L35), [PicoSHA2 vcpkg 포트](https://github.com/microsoft/vcpkg/tree/e456309491fd875487bf02b8a0dca76c1b1b00cc/ports/picosha2)
- Kotlin은 파일 경로를 표준 UTF-8 바이트로 넘긴다. 동기 JNI 호출이 진행 콜백의 참조를 유지하고, 요청이 취소 토큰 접근과 해제를 동기화한다. [Android JNI 문자열과 참조](https://developer.android.com/ndk/guides/jni-tips#utf-8_and_utf-16_strings)

## WorkManager 연동

`ConversionWorker`에서 앱 소유 입력과 출력 경로를 확정하고 작업 스레드에서 요청을 실행한다. Worker 중단이 시작될 때 `requestCancel()`을 전달하고, `run()`이 반환할 때까지 파일이나 작업 소유권을 해제하지 않는다. 반환된 상태·오류로 완료와 재시도를 결정한다. 프로세스 종료 후 다시 실행할 때는 소유가 확인된 `.partial`을 정리하고 새 요청으로 처음부터 변환한다. 진행 콜백의 상태는 Worker가 알림·영속 상태에 전달한다. [장시간 Worker](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)

현재 `setForeground()`의 `IllegalStateException`과 `SecurityException`은 `INTERRUPTED`로 기록한다. 향후에는 포그라운드 시작 거부 예외의 유형과 API 31+에서 제공되는 WorkManager 중단 사유를 기록해 일시적인 OS 실행 제한을 식별할 수 있다. 권한·서비스 설정 오류는 해당 설정에서 확인하고, 텐서·템플릿 불일치는 Converter의 구조화된 오류로 진단한다. [포그라운드 시작 제한](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) · [WorkManager 중단 사유](https://developer.android.com/reference/androidx/work/ListenableWorker#getStopReason())

## 검증 기록

현재 앱 계측은 JNI의 경로·옵션·결과 전달, Kotlin 콜백 예외와 재진입, 교차 스레드 취소 및 네이티브 반환 뒤 자원 해제를 검증한다. Converter의 진행률 계산, 기존 출력 보존, 소유권 마커와 staging 정리 규칙은 [to-mnn Converter 테스트](../third_party/to-mnn/cpp/tests/converter/converter_test.cpp)에서 검증한다. 앱의 `cleanupPartial` 계측은 대표 성공·실패 코드 전달을 확인한다. WorkManager·Room·파일 상태 대조와 실제 APK 스모크는 앱 통합 검증으로 유지한다.

2026-11-13 중복 검증 정리 후 Debug·계측 APK 빌드와 JVM 테스트 72개가 통과했다. Galaxy S20+ (SM-G986N, Android 13)의 `ConverterAndroidTest` 5개와 `ChatRuntimeAssetsTest` 2개가 통과했다.

- Debug 빌드와 JVM 테스트 36개가 통과했다. lint는 오류 0개, 경고 34개를 보고했다. APK에서 ARM64 JNI 라이브러리와 `libc++_shared.so`의 패키징 및 16 KiB 정렬을 확인했다.
- 2026-11-11 Galaxy S20+ (SM-G986N, Android 13/API 33)에서 기존 Android 계측 18개와 합성 Converter 계측 4개가 통과했다. 합성 테스트는 정확한 출력 바이트, 공개 시점, 진행 콜백, 교차 스레드 취소, 오류 및 잔여 출력 소유권을 확인한다.
- 같은 기기의 Debug APK에서 기존 ADB 실험의 4,265,146,304바이트 SD 1.5 체크포인트와 `sd15-hqq-b128-manual` 템플릿으로 실제 변환을 1회 수행했다. 청크 1 MiB, HQQ 보정 20회, 계산 스레드 4개를 사용했고 파일 캐시 상태는 제어하지 않았다. ADB 계측 테스트와 Converter 경과 시간 428.056초를 기록했으며 `Completed`, 출력 파일 세 개의 이름·크기 및 `.partial` 부재를 확인했다. [기존 ADB 입력과 실행 조건](https://github.com/PocketMuse/to-mnn/blob/b5a06df6944cd8b6ff22048396281d04e926c7fe/docs/sd15-discussion.md#2026-11-08--s20-변환추론-검증), [재현 명령](../README.md#실제-hqq-모델-스모크)

### 네이티브 빌드 성능 재현

`converterBenchmark` Gradle 속성은 디버그 서명을 사용하는 격리 패키지 `com.example.pocketmuseprototype.converterbenchmark`를 만들고 CMake 빌드 타입을 지정한다. `Debug`, `Release`, `RelWithDebInfo`가 가능하다. 두 APK를 `-r`로 교체 설치하면 비교 실행이 같은 입력 파일을 사용한다. `app/.cxx/<CMake 타입>/<해시>/arm64-v8a/compile_commands.json`에서 `hqq.cpp`와 `converter_jni.cpp`에 적용된 옵션을 확인한다.

```powershell
$nativeConfig = 'Release'
.\gradlew.bat -PconverterOnly=true "-PconverterBenchmark=$nativeConfig" :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

입력 파일명과 하위 경로는 [실제 HQQ 스모크](../README.md#실제-hqq-모델-스모크)와 같으며 격리 패키지의 `/sdcard/Android/data/com.example.pocketmuseprototype.converterbenchmark/files/converter-smoke`에 한 번만 준비한다. 스모크는 변환 완료, 출력 이름과 크기, staging 정리를 확인한다. 양자화 출력의 기준값 검증은 [to-mnn의 Converter 테스트](../third_party/to-mnn/cpp/tests/converter/converter_test.cpp)에서 다룬다.

```powershell
adb shell am instrument -w -r -e class com.example.pocketmuseprototype.conversion.ConverterSmokeTest -e converterSmoke true com.example.pocketmuseprototype.converterbenchmark.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s ConverterSmokeTest:I
```

격리 APK에서 정적 LCM을 실행하려면 같은 입력 디렉터리에 `lcm-lora.safetensors`를 두고 위 실행 인수를 `-e converterLcmSmoke true`로 바꾼다.

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

### 정적 LCM JNI 통합 검증

2026-11-12 Debug·Release APK 빌드와 JVM 테스트 45개가 통과했다. Debug lint는 오류 0개, 경고 35개를 보고했다. 두 APK의 ARM64 공유 라이브러리는 ZIP과 ELF에서 16 KiB 정렬을 확인했다. `RelWithDebInfo`의 Converter 컴파일 명령에는 `-O2 -g -DNDEBUG`가 적용됐다.

Galaxy S20+ (SM-G986N, Android 13/API 33)의 격리 `RelWithDebInfo` APK에서 Android 회귀 26개가 통과했다. 합성 JNI 테스트 5개는 유니코드 LoRA 경로, 없는 파일과 잘못된 파일의 오류 코드, 입력·기존 출력 보존, 진행 콜백, 교차 스레드 취소와 `.partial` 정리를 검증했다. 기본 패키지명을 고정 비교하는 템플릿 테스트는 격리 실행에서 제외했다. 실제 백그라운드 변환 스모크는 이번 실행 범위에서 제외했다.

같은 기기의 앱 전용 외부 저장소에 기존 CLI의 4,265,146,304바이트 SD 1.5 체크포인트, 공식 LoRA와 APK 내장본과 해시가 같은 HQQ B128 템플릿을 준비했다. 1 MiB 청크, HQQ 보정 20회, 계산 스레드 4개로 정적 LCM과 일반 HQQ를 순서대로 각 1회 실행했다. 파일 캐시는 제어하지 않았다. Converter 경과 시간은 정적 LCM 136.423초, 일반 HQQ 86.270초였다. 두 실행 모두 `Completed`와 `.partial` 부재를 확인했으며, 출력 세 파일의 이름·크기·SHA-256이 각각 기존 CLI 기준과 일치했다. 정적 LCM의 CLIP·VAE 해시는 위 일반 HQQ 기준값과 같았고 병합된 `unet.mnn`의 해시는 `3f06996145268ccd462f1d9e3b7707c5cc71773aadcb009f11bcc5b852c70763`이었다. [정적 LCM CLI 기준](https://github.com/PocketMuse/to-mnn/blob/a8bfba3389fa6fdac060cb17b3ee7f39669e5e9e/docs/sd15-discussion.md#2026-11-12--s20-정적-lcm-변환과-추론)
