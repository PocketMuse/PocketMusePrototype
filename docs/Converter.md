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
