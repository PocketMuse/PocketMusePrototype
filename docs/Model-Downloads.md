# 모델 다운로드와 Library

Market → Image에서 Civitai 연결 후 체크포인트를 선택한다. 버튼을 누르면 Market에 대기열 등록을 알리고, Library의 모델 작업에 항목을 표시한다. 모델 버전 ID와 파일 ID가 같은 항목은 기존 항목을 삭제한 후 다시 요청할 수 있다. 검증을 마친 원본은 **다운로드 완료**로 표시되며 HQQ 변환이 자동으로 대기열에 등록된다. [백그라운드 변환 계약](Converter.md#백그라운드-변환)

## 저장 계약

- Room v2의 `model-library.db`에 `models` 테이블을 둔다. 기본 키 `storageKey`는 양의 정수인 `{modelVersionId}_{fileId}`다. `fileSize`는 처음에는 `sizeKB`로 계산한 추정치이고 완료 후에는 검증된 실제 바이트 수다. `totalBytes`는 응답이 길이를 제공하지 않으면 전송이 끝날 때까지 null이다. v1에서 모델 메타데이터, 다운로드 상태, 작업 ID를 보존하고 변환 상태를 `NotStarted`로 초기화한다. 기존 완료 항목은 Library에서 변환을 시작할 수 있고, 기존 대기 항목은 다운로드가 완료되는 시점에 자동 변환된다. [Room 스키마](../app/schemas/com.example.pocketmuseprototype.download.LibraryDatabase/2.json)
- `ModelStorage`는 `Context.getExternalFilesDir(null)/models/{storageKey}/`에 경로를 계산한다. `thumbnail.webp`, `source/checkpoint.safetensors` 및 전송 중 `source/checkpoint.safetensors.part`를 사용한다. 변환 출력은 같은 모델 디렉터리의 `converted/{conversionWorkId}/`에 저장한다. Room과 작업 입력은 체크포인트 URL과 인증 정보를 보관하지 않는다. 썸네일은 쿼리와 프래그먼트를 제거한 URL만 보관한다. Android의 클라우드 백업과 기기 간 전송에서 모델 DB와 `models/`를 제외한다. [앱 전용 외부 저장소](https://developer.android.com/training/data-storage/app-specific#external), [Android Auto Backup](https://developer.android.com/identity/data/autobackup)

## Checkpoints

- WorkManager의 고유 체인에 모델별 `CoroutineWorker`를 연결한다. Room의 작업 ID로 레코드와 Worker를 대조한다. 연결된 네트워크에서 앞 작업이 끝나면 다음 작업을 실행한다. 앱 프로세스의 전송 잠금은 중단된 Worker가 네트워크와 파일 핸들을 닫기 전에 다음 전송이 들어오는 것을 방지한다. Worker는 `dataSync` 포그라운드 서비스로 승격한 후 파일 전송을 시작한다. Worker는 일정과 알림을 관리하고 `CivitaiTransfer`는 스트리밍과 검증을 담당한다. [장시간 Worker](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running), [고유 작업 체인](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)
- Android 12+는 백그라운드에서 포그라운드 서비스를 처음 시작하는 경우를 제한한다. 작업이 늦게 시작되어 승격할 수 없으면 Library에 중단 오류를 표시한다. Android 13+에서는 첫 다운로드를 등록할 때 알림 권한을 요청한다. 권한 거부 시 알림 서랍의 진행률은 숨겨지고 Library에서 확인할 수 있다. Android 14+에서 `dataSync` 유형과 `FOREGROUND_SERVICE_DATA_SYNC` 권한이 필요하다. Android 15+는 앱이 백그라운드일 때 `dataSync` 서비스의 사용 시간을 24시간당 총 6시간으로 제한한다. Android 16+의 장시간 Worker는 JobScheduler 할당량도 사용한다. 사용자 시작 파일 전송은 향후 API 34+에서 UIDT로 옮길 수 있다. [백그라운드 시작 제한](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [알림 권한](https://developer.android.com/develop/ui/compose/notifications/notification-permission), [서비스 유형](https://developer.android.com/develop/background-work/services/fgs/service-types), [서비스 시간 제한](https://developer.android.com/develop/background-work/services/fgs/timeout), [UIDT](https://developer.android.com/develop/background-work/background-tasks/uidt)
- Worker는 세션 초기화를 기다리고 `AuthManager.validAccessToken()`을 사용한다. 매번 버전 메타데이터에서 동일한 모델·버전·파일 ID의 SafeTensor를 확인하고 다운로드 URL의 `fileId`를 고정한다. Bearer 토큰은 `https://civitai.com`에만 전송하고 외부 호스트로 리다이렉트하는 순간 제거한다. 401, 403, 404, 429, 전송 오류는 Library에서 구분한다. 썸네일은 별도 요청으로 제한된 크기의 JPEG·PNG·WebP를 읽어 축소한 WebP로 원자적으로 저장한다. 오류 시 Library의 자리표시자를 사용한다. [Civitai 버전 API](https://github.com/civitai/civitai/blob/main/src/pages/api/v1/model-versions/%5Bid%5D.ts), [Civitai 다운로드 라우트](https://github.com/civitai/civitai/blob/main/src/pages/api/download/models/%5BmodelVersionId%5D.ts)
- `Queued → Downloading → Verifying → Downloaded`. 임시 파일에 스트리밍하면서 응답 길이와 실제 받은 바이트를 확인하고, SHA-256이 제공되면 파일 해시를 비교한다. SD 1.5 체크포인트의 기본 형식 검사는 최대 4 MiB의 SafeTensors 헤더, 지원하는 바이트 단위 dtype, shape와 데이터 오프셋, 데이터 영역의 연속성과 길이를 확인한다. 검증 후 같은 디렉터리 안에서 원자적으로 이름을 바꾼 다음 Room을 완료 처리한다. [SafeTensors 형식](https://github.com/huggingface/safetensors/blob/main/README.md#format)
- 실패하면 `Failed`로 표시한다. 사용자가 취소하면 `Cancelling`으로 기록하고 실제 HTTP 호출을 취소한다. 진행 중인 Worker가 스트림과 파일을 닫은 뒤 모델 디렉터리를 정리하고 Room 행을 제거한다. 완료·실패 항목의 삭제는 `Deleting`을 기록한 뒤 해당 디렉터리를 지우고 Room 행을 제거한다. 파일 정리가 실패하면 행을 남겨 삭제를 다시 시도할 수 있다.
- 시스템이 Worker를 멈추면 임시 파일을 폐기하고 대기 상태로 되돌린다. WorkManager가 작업을 다시 실행하면 처음부터 전송한다. 앱 재실행 시 DB와 작업·파일 상태를 대조한다. Room 등록 후 Worker가 아직 등록되지 않은 항목은 예약한다. `Verifying`에서 검증된 최종 파일의 이름 변경까지 마쳤으면 기록된 길이를 확인하고 완료 처리한다. 누락되거나 길이가 바뀐 완료 파일은 오류를 표시한다. 중단된 삭제나 취소는 정리를 계속한다. 강제 종료 뒤의 실행 시점은 Android의 작업 제한을 따른다.

## 검증

Windows 프로젝트 루트에서 실행한다.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

호스트 테스트는 합성 HTTP 응답과 임시 파일을 사용한다. Android 테스트는 Compose 상태·탭 이동·Activity 재생성, Room 재열기, 앱 전용 파일 및 포그라운드 서비스 선언과 알림 채널을 검사한다. 실계정 E2E는 별도로 Galaxy S20+에서 로그인, 진행률, 홈 화면 전환, 취소, 다시 다운로드, 프로세스 재실행 후 로컬 썸네일, 삭제를 확인한다.

- Debug 빌드, 호스트 테스트 36개, lint, Galaxy S20+ 계측 테스트 18개를 통과했다. lint 결과는 오류 0개, 경고 33개다.
- 2026-11-11 Galaxy S20+ (SM-G986N, Android 13/API 33, Debug)에서 실제 계정으로 Realistic Vision V6.0 B1의 `V6.0 B1 inpainting (VAE)` 파일을 받았다. 첫 전송에서 Library 진행률과 알림을 확인했다. Chat 탭으로 이동한 뒤 홈 화면으로 전환했고, ADB `stat`으로 4초 동안 `.part`가 429,264,567바이트에서 454,295,525바이트로 늘어난 것을 확인했다. 취소 후 모델 디렉터리가 제거됐다.
- 같은 파일을 다시 요청했고 전송 중 중복 요청을 눌러도 Library 항목 하나만 유지됐다. 최종 파일은 2,132,679,948바이트였고, 기기의 `sha256sum`이 Civitai 파일 ID 190186의 SHA-256과 일치했다. ADB로 앱 프로세스를 종료한 뒤 앱을 열어 **다운로드 완료**와 로컬 썸네일을 확인했다. Library에서 삭제한 뒤 행과 해당 모델 디렉터리가 제거됐다. Android 14–16의 실제 작업 제한과 전송 도중 강제 종료는 이 기기 시험에 포함되지 않았다. [Civitai 버전 메타데이터](https://civitai.com/api/v1/model-versions/245627)
