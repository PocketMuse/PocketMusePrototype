# 모델 다운로드와 Library

Market → Image에서 Civitai 연결 후 체크포인트를 선택한다. 버튼을 누르면 Market에 대기열 등록을 알리고, Library의 모델 작업에 항목을 표시한다. 모델 버전 ID와 파일 ID가 같은 항목은 기존 항목을 삭제한 후 다시 요청할 수 있다. 검증을 마친 체크포인트와 공식 공용 LoRA가 준비되면 정적 LCM 변환이 자동으로 대기열에 등록된다. LoRA를 먼저 받아야 하는 모델은 **LCM LoRA 대기 중**으로 표시한다. [백그라운드 변환 계약](Converter.md#백그라운드-변환)

## Library 카드와 모달

2026-11-13 구현 기준이다. 화면에서 **최적화**는 LCM 병합과 양자화를 포함하는 모델 준비 작업을 뜻한다. 아래 상태 이름은 저장된 다운로드·변환 상태와 화면을 연결하기 위한 표기다. [화면 구현](../app/src/main/java/com/example/pocketmuseprototype/ui/LibraryScreen.kt), [화면 문구](../app/src/main/res/values/strings.xml), [상태 정의](../app/src/main/java/com/example/pocketmuseprototype/download/LibraryDatabase.kt)

### 공통 표시와 배치

- 카드 왼쪽에 썸네일, 가운데에 모델명·버전·용량을 표시한다. 상태가 있으면 용량 오른쪽에 `2.1GB · 최적화 40%`처럼 이어 붙인다.
- 카드 우측에는 진행 중인 작업의 **원형 진행 표시 겸 버튼** 또는 **텍스트 버튼**을 둔다. 텍스트 버튼은 `최적화 / 삭제` 또는 `재시도 / 삭제` 순서로 가로 배치한다. 최적화·재시도는 테마의 파란 강조색, 삭제는 오류 색상의 빨간색을 사용한다.
- 카드 높이는 내용에 맞춘다. 버튼용 하단 공간을 예약하지 않는다. 긴 모델명이나 글꼴 배율에 따른 줄바꿈은 내용 높이에 반영된다.
- 최적화 실패의 구체적인 오류는 카드 맨 아래에 빨간색으로 추가하며, 필요한 만큼 카드가 늘어난다. 다운로드 오류는 카드 정보 영역에 표시한다.
- 최적화가 완료된 일반 카드는 모델명·버전·용량과 삭제 버튼만 표시한다. 최적화 필요·취소·실패 상태의 복구 동작은 아래 표를 따른다. 현재 앱의 이미지 생성·모델 사용 연결은 후속 구현 범위다.
- 모달은 기본 Material 텍스트 버튼의 글자 크기와 안쪽 여백을 사용한다. 최적화 모달의 버튼 사이는 8dp이며, 문구에 맞는 너비로 가로 배치한다. 모든 모달의 실행 문구는 `취소하기`, `삭제하기`, 닫기 문구는 `돌아가기`다. `삭제하기`는 빨간색이다.

### 체크포인트 다운로드 상태

| 상태 | 위치와 카드 표시 | 우측 버튼 | 누른 뒤의 동작 |
| --- | --- | --- | --- |
| 대기 `Queued` | 모델 작업. 예상 용량 또는 `—` | 불확정 원형 표시 | 다운로드 취소 모달 |
| 다운로드 `Downloading` | 모델 작업. 받은 용량 / 전체 용량 | 다운로드 비율을 표시하는 원형 버튼 | 다운로드 취소 모달 |
| 전체 길이가 없는 다운로드 | 모델 작업. `받은 용량 받음` | 불확정 원형 표시 | 다운로드 취소 모달 |
| 파일 검증 `Verifying` | 모델 작업. 받은 파일 용량 · `파일 확인 중` | 불확정 원형 표시 | 다운로드 취소 모달 |
| 완료 `Downloaded` | 최적화 상태에 따라 아래 표 적용 | 최적화 상태에 따라 결정 | 검증된 원본과 공용 LoRA가 준비되면 최적화를 자동 예약 |
| 실패 `Failed` | 내 모델. 용량 · `다운로드 실패`, 원인 표시 | `삭제` | 삭제 확인 모달. 다시 받으려면 항목을 삭제하고 Market에서 다운로드 요청 |
| 취소 정리 `Cancelling` | 모델 작업. `취소 중` | 표시 없음 | 전송 종료와 파일 정리가 끝나면 항목 제거 |
| 삭제 정리 `Deleting` | 모델 작업. `삭제 중` | 정리 중에는 표시 없음 | 정리가 끝나면 항목 제거 |
| 삭제 정리 실패 | 모델 작업. `삭제 중`과 정리 오류 | `삭제` | 삭제 확인 모달을 거쳐 정리 재시도 |

현재 다운로드 실패 항목도 **내 모델**에 표시된다. 체크포인트 다운로드의 수동 재요청은 삭제 후 Market에서 수행한다. [카드 분류와 동작](../app/src/main/java/com/example/pocketmuseprototype/ui/LibraryScreen.kt), [다운로드 취소·삭제](../app/src/main/java/com/example/pocketmuseprototype/download/ModelDownloads.kt)

### 체크포인트 최적화 상태

이 표는 원본 다운로드가 완료된 항목에 적용한다.

| 상태 | 위치와 용량 옆 표시 | 우측 버튼 | 누른 뒤의 동작 |
| --- | --- | --- | --- |
| 시작 전 `NotStarted` | 내 모델. `최적화 필요` | `최적화 / 삭제` | 최적화는 즉시 준비·예약, 삭제는 확인 모달 |
| 공용 LoRA 대기 `WaitingForLcm` | 모델 작업. `LCM LoRA 대기 중` | 진행값 0의 원형 버튼 | 최적화 작업 모달 |
| 예약 대기 `Queued` | 모델 작업. `최적화 대기 중` | 불확정 원형 표시 | 최적화 작업 모달 |
| 입력 검증 `Validating` | 모델 작업. `입력 확인 중` | 불확정 원형 표시 | 최적화 작업 모달 |
| 실행 `Converting` | 모델 작업. `최적화 N%` | 같은 비율의 원형 버튼 | 최적화 작업 모달 |
| 진행값이 없는 실행 | 모델 작업. `최적화 중` | 불확정 원형 표시 | 최적화 작업 모달 |
| 출력 검증·등록 `Finalizing` | 모델 작업. `결과 확인 중` | 불확정 원형 표시 | 최적화 작업 모달 |
| 취소 정리 `Cancelling` | 모델 작업. `최적화 취소 중` | 표시 없음 | 네이티브 호출 종료와 이번 시도의 출력 정리를 기다림 |
| 취소 완료 `Cancelled` | 내 모델. `최적화 필요` | `재시도 / 삭제` | 재시도는 저장된 원본으로 처음부터 최적화, 삭제는 확인 모달 |
| 실패 `Failed` | 내 모델. `최적화 실패`, 카드 맨 아래 빨간 오류 문구 | `재시도 / 삭제` | 재시도는 저장된 원본으로 최적화 준비, 삭제는 확인 모달 |
| 완료 `Converted`, 현재 레시피 | 내 모델. 모델 정보와 용량 | `삭제` | 삭제 확인 모달 |
| 이전 Legacy HQQ 결과 | 내 모델. `최적화 필요` | `최적화 / 삭제` | 원본으로 현재 레시피를 새로 실행하거나 모델 삭제 |

최적화 진행률은 Converter가 보고하는 전체 출력 바이트의 처리 비율이다. 공용 LoRA가 없고 기다리는 모델이 있으면 모델 작업 섹션에 `LCM LoRA 다운로드` 버튼을 표시한다. 이전 결과를 교체하는 작업은 새 결과가 등록될 때까지 기존 결과를 보관하며, 카드에 `기존 변환본 보관 중`을 표시할 수 있다. [변환 수명과 출력 계약](Converter.md#백그라운드-변환)

### 모달 문구와 확정 결과

버튼 순서는 화면의 왼쪽부터 적었다.

| 여는 동작 | 제목 | 본문 | 버튼 |
| --- | --- | --- | --- |
| 다운로드 중 원형 버튼 | `모델명 다운로드 취소` | 다운로드를 취소할까요? 받던 파일은 삭제됩니다. | `돌아가기 / 취소하기` |
| 최적화 대기·진행 중 원형 버튼 | 모델명 | 취소하면 다운로드한 모델이 남습니다. 삭제하면 다시 다운로드해야 합니다. | `취소하기 / 삭제하기 / 돌아가기` |
| 카드의 삭제 버튼 | `모델명 삭제` | 저장된 파일을 삭제할까요? | `돌아가기 / 삭제하기` |
| 최적화 모달을 연 사이 완료됨 | 모델명 | 모델을 삭제하면 다시 다운로드해야 합니다. | `삭제하기 / 돌아가기` |

| 확정한 행동 | 파일과 카드의 결과 |
| --- | --- |
| 다운로드 취소하기 | 전송을 멈추고 해당 모델의 임시 파일과 작업 항목을 정리한다. 다시 받으려면 Market에서 요청한다. |
| 최적화 취소하기 | 취소 의도를 기록하고 네이티브 종료를 기다린 뒤 이번 시도의 임시·출력 파일을 정리한다. 다운로드한 원본은 보존하고 내 모델에서 재시도를 제공한다. |
| 최적화 중 삭제하기 | 진행 중인 최적화를 중단하고 해당 모델의 원본·썸네일·출력과 Library 항목을 정리한다. 다시 사용하려면 다운로드부터 시작한다. |
| 내 모델에서 삭제하기 | 해당 모델의 저장된 파일과 Library 항목을 정리한다. 공용 LoRA는 자체 카드에서 별도로 관리한다. |
| 돌아가기 | 모달을 닫는다. |

최적화 모달은 열린 작업의 식별자와 현재 상태를 대조한다. 같은 작업이 완료되면 취소 버튼을 제거하고 삭제·돌아가기만 표시한다. 항목이 사라지거나 작업이 교체되거나 실패·취소 정리 상태로 바뀌면 모달을 닫는다. [모달 구현](../app/src/main/java/com/example/pocketmuseprototype/ui/LibraryScreen.kt), [취소·삭제 처리](../app/src/main/java/com/example/pocketmuseprototype/download/ModelDownloads.kt), [동작 검증](../app/src/androidTest/java/com/example/pocketmuseprototype/AppNavigationTest.kt)

### 오류 표시

- 최적화 오류는 저장 공간 부족, 원본 구조·호환성 문제, 템플릿 문제, 시스템 중단·예약 실패, 결과 누락·손상, 이전 출력 정리 실패를 구분한다. 나머지는 `최적화를 완료하지 못했습니다. 다운로드한 원본은 남습니다.`로 표시한다.
- 다운로드 오류는 인증·접근 권한, 파일 누락, 요청 제한, 서버·네트워크, 메타데이터, 길이·해시·SafeTensors 검증, 저장소·공간·쓰기, 로컬 파일 누락, 정리·예약·중단 사유를 구분한다.
- 정리 실패 시 항목과 오류를 남겨 재시도할 수 있게 한다. 버튼을 누른 시점과 실제 파일 정리가 끝난 시점은 구분한다.

오류별 실제 문구는 문자열 리소스와 화면의 오류 매핑을 기준으로 한다. [오류 문구](../app/src/main/res/values/strings.xml), [오류 매핑](../app/src/main/java/com/example/pocketmuseprototype/ui/LibraryScreen.kt)

### 공용 LCM LoRA 카드

| 상태 | 위치와 표시 | 버튼과 모달 |
| --- | --- | --- |
| 없음 `Missing` | 카드 표시 없음 | 대기 중인 모델이 있으면 섹션의 `LCM LoRA 다운로드`로 준비 |
| 대기 `Queued` | 모델 작업. 이름·출처·전체 용량 | 불확정 원형 버튼 → 다운로드 취소 모달 |
| 다운로드 `Downloading` | 모델 작업. 받은 용량 / 전체 용량 | 진행률 원형 버튼 → 다운로드 취소 모달 |
| 검증 `Verifying` | 모델 작업. `파일 확인 중` | 불확정 원형 버튼 → 다운로드 취소 모달 |
| 사용 가능 `Ready` | 내 모델. 이름·출처·용량 | 빨간 `삭제` → 삭제 확인 모달 |
| 실패 `Failed` | 모델 작업. `다운로드 실패`와 원인 | `재시도` → 다운로드 준비·예약 |

공용 LoRA의 취소·삭제는 다른 체크포인트의 원본을 보존한다. 다운로드 취소가 최종 파일 공개보다 먼저 처리되면 임시 파일을 정리하고 카드를 제거한다. 공개가 먼저 끝났으면 사용 가능한 카드가 남는다. 사용 중인 LoRA 삭제는 해당 네이티브 호출이 파일 사용을 마칠 때까지 기다린다. 취소·삭제 자체가 실패하면 스낵바로 해당 오류를 알린다. [공용 파일 수명](../app/src/main/java/com/example/pocketmuseprototype/download/SharedLcmDownloads.kt), [스낵바 연결](../app/src/main/java/com/example/pocketmuseprototype/MainActivity.kt)

## 저장 계약

- Room v4의 `model-library.db`에 `models` 테이블을 둔다. 기본 키 `storageKey`는 양의 정수인 `{modelVersionId}_{fileId}`다. `fileSize`는 처음에는 `sizeKB`로 계산한 추정치이고 완료 후에는 검증된 실제 바이트 수다. `totalBytes`는 응답이 길이를 제공하지 않으면 전송이 끝날 때까지 null이다. v1에서 기존 다운로드를 보존하고 변환 상태를 `NotStarted`로 초기화한다. v2에서 기존 작업 ID, 출력 메타데이터와 해시를 보존하며 이미 시도한 작업을 Legacy HQQ로 식별한다. 다운로드만 완료된 모델은 Library에서 변환을 시작할 수 있다. [Room 스키마](../app/schemas/com.example.pocketmuseprototype.download.LibraryDatabase/4.json)
- `ModelStorage`는 `Context.getExternalFilesDir(null)/models/{storageKey}/`에 경로를 계산한다. `thumbnail.webp`, `source/checkpoint.safetensors` 및 전송 중 `source/checkpoint.safetensors.part`를 사용한다. 변환 출력은 같은 모델 디렉터리의 `converted/{conversionWorkId}/`에 저장한다. Room과 작업 입력은 체크포인트 URL과 인증 정보를 보관하지 않는다. 썸네일은 쿼리와 프래그먼트를 제거한 URL만 보관한다. Android의 클라우드 백업과 기기 간 전송에서 모델 DB와 `models/`를 제외한다. [앱 전용 외부 저장소](https://developer.android.com/training/data-storage/app-specific#external), [Android Auto Backup](https://developer.android.com/identity/data/autobackup)

## Checkpoints

- WorkManager의 고유 체인에 모델별 `CoroutineWorker`를 연결한다. Room의 작업 ID로 레코드와 Worker를 대조한다. 연결된 네트워크에서 앞 작업이 끝나면 다음 작업을 실행한다. 앱 프로세스의 전송 잠금은 중단된 Worker가 네트워크와 파일 핸들을 닫기 전에 다음 전송이 들어오는 것을 방지한다. Worker는 `dataSync` 포그라운드 서비스로 승격한 후 파일 전송을 시작한다. Worker는 일정과 알림을 관리하고 `CivitaiTransfer`는 스트리밍과 검증을 담당한다. [장시간 Worker](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running), [고유 작업 체인](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)
- Android 12+는 백그라운드에서 포그라운드 서비스를 처음 시작하는 경우를 제한한다. 작업이 늦게 시작되어 승격할 수 없으면 Library에 중단 오류를 표시한다. Android 13+에서는 첫 다운로드를 등록할 때 알림 권한을 요청한다. 권한 거부 시 알림 서랍의 진행률은 숨겨지고 Library에서 확인할 수 있다. Android 14+에서 `dataSync` 유형과 `FOREGROUND_SERVICE_DATA_SYNC` 권한이 필요하다. Android 15+는 앱이 백그라운드일 때 `dataSync` 서비스의 사용 시간을 24시간당 총 6시간으로 제한한다. Android 16+의 장시간 Worker는 JobScheduler 할당량도 사용한다. 사용자 시작 파일 전송은 향후 API 34+에서 UIDT로 옮길 수 있다. [백그라운드 시작 제한](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [알림 권한](https://developer.android.com/develop/ui/compose/notifications/notification-permission), [서비스 유형](https://developer.android.com/develop/background-work/services/fgs/service-types), [서비스 시간 제한](https://developer.android.com/develop/background-work/services/fgs/timeout), [UIDT](https://developer.android.com/develop/background-work/background-tasks/uidt)
- Worker는 세션 초기화를 기다리고 `AuthManager.validAccessToken()`을 사용한다. 매번 버전 메타데이터에서 동일한 모델·버전·파일 ID의 SafeTensor를 확인하고 다운로드 URL의 `fileId`를 고정한다. Bearer 토큰은 `https://civitai.com`에만 전송하고 외부 호스트로 리다이렉트하는 순간 제거한다. 401, 403, 404, 429, 전송 오류는 Library에서 구분한다. 썸네일은 별도 요청으로 제한된 크기의 JPEG·PNG·WebP를 읽어 축소한 WebP로 원자적으로 저장한다. 오류 시 Library의 자리표시자를 사용한다. [Civitai 버전 API](https://github.com/civitai/civitai/blob/main/src/pages/api/v1/model-versions/%5Bid%5D.ts), [Civitai 다운로드 라우트](https://github.com/civitai/civitai/blob/main/src/pages/api/download/models/%5BmodelVersionId%5D.ts)
- `Queued → Downloading → Verifying → Downloaded`. 임시 파일에 스트리밍하면서 응답 길이와 실제 받은 바이트를 확인하고, SHA-256이 제공되면 파일 해시를 비교한다. SD 1.5 체크포인트의 기본 형식 검사는 최대 4 MiB의 SafeTensors 헤더, 지원하는 바이트 단위 dtype, shape와 데이터 오프셋, 데이터 영역의 연속성과 길이를 확인한다. 검증 후 같은 디렉터리 안에서 원자적으로 이름을 바꾼 다음 Room을 완료 처리한다. [SafeTensors 형식](https://github.com/huggingface/safetensors/blob/main/README.md#format)
- 실패하면 `Failed`로 표시한다. 사용자가 취소하면 `Cancelling`으로 기록하고 실제 HTTP 호출을 취소한다. 진행 중인 Worker가 스트림과 파일을 닫은 뒤 모델 디렉터리를 정리하고 Room 행을 제거한다. 완료·실패 항목의 삭제는 `Deleting`을 기록한 뒤 해당 디렉터리를 지우고 Room 행을 제거한다. 파일 정리가 실패하면 행을 남겨 삭제를 다시 시도할 수 있다.
- 시스템이 Worker를 멈추면 임시 파일을 폐기하고 대기 상태로 되돌린다. WorkManager가 작업을 다시 실행하면 처음부터 전송한다. 앱 재실행 시 DB와 작업·파일 상태를 대조한다. Room 등록 후 Worker가 아직 등록되지 않은 항목은 예약한다. `Verifying`에서 검증된 최종 파일의 이름 변경까지 마쳤으면 기록된 길이를 확인하고 완료 처리한다. 누락되거나 길이가 바뀐 완료 파일은 오류를 표시한다. 중단된 삭제나 취소는 정리를 계속한다. 강제 종료 뒤의 실행 시점은 Android의 작업 제한을 따른다.

## 공용 SD1.5 LCM LoRA

Market의 다운로드 요청은 Civitai 체크포인트 예약과 공용 LoRA 준비를 각각 시작한다. Library의 모델 작업 섹션에는 공용 작업의 대기·전송·검증·실패와 취소 또는 재시도를 표시한다. 검증된 파일은 **내 모델**에서 `LR` 마크와 출처, 크기, 삭제 버튼으로 표시한다.

- 고정 출처는 Hugging Face의 `latent-consistency/lcm-lora-sdv1-5`이며 라이선스는 `openrail++`이다. 배포 revision은 `cf2fced511dbe7e26c8d1d397e728fbab875db4b`다. `pytorch_lora_weights.safetensors`의 길이 `134621556`바이트와 SHA-256 `8f90d840e075ff588a58e22c6586e2ae9a6f7922996ee6649a7f01072333afe4`를 검사한다. [모델 파일](https://huggingface.co/latent-consistency/lcm-lora-sdv1-5/blob/cf2fced511dbe7e26c8d1d397e728fbab875db4b/pytorch_lora_weights.safetensors), [Git LFS 포인터](https://huggingface.co/latent-consistency/lcm-lora-sdv1-5/raw/cf2fced511dbe7e26c8d1d397e728fbab875db4b/pytorch_lora_weights.safetensors)
- 별도 WorkManager 고유 작업 `shared-sd15-lcm-lora-v1`에 `KEEP`과 `CONNECTED` 제약을 적용한다. 기존 다운로드의 `dataSync` 서비스와 알림 채널을 공유한다. 전송에는 HTTPS 리다이렉트와 인증 정보 없는 요청을 사용한다. 일시적 네트워크 오류에는 WorkManager 백오프로 최대 두 번 재시도한다. 중단된 파일은 다음 시도에서 처음부터 받는다. 재실행 시점은 OS 정책과 네트워크 제약을 따른다. [WorkManager 고유 작업](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)
- 내부 앱 전용 `Context.noBackupFilesDir/shared-assets/sd15-lcm-lora-v1/`에 저장한다. 같은 디렉터리의 `.part`에 스트리밍한 뒤 길이와 전체 해시를 확인하고 원자적으로 최종 이름을 공개한다. 프로세스를 다시 시작하거나 파일의 식별 정보가 바뀌면 SHA-256을 다시 확인한다. 프로세스 안에서는 동일 파일의 검증 결과를 재사용한다. 취소는 공용 작업 ID에만 적용하며 Worker가 스트림과 파일을 닫은 뒤 임시 파일을 정리한다.
- 작업 상태와 진행률은 WorkManager에서 읽는다. 원자적으로 갱신하는 작은 `last-result` 파일은 WorkManager가 완료 기록을 정리한 뒤에도 실패 이유와 사용자 삭제를 복구한다. 최종 파일의 검증 결과가 사용 가능 여부를 결정한다. 성공한 작업의 파일이 사라지거나 손상되면 Library에서 재시도할 수 있다. [WorkManager 완료 기록 정리](https://developer.android.com/reference/kotlin/androidx/work/WorkManager#pruneWork())
- WorkManager 2.11.2에서 이 단일 요청을 `KEEP`으로 다시 예약하면 이전 완료 행을 제거하고 새 ID를 삽입한다. 조회 목록은 시간순 정렬을 보장하지 않는다. 활성 작업을 우선하고, 완료 행이 하나일 때 그 ID와 결과를 사용한다. 완료 행이 여러 개이거나 정리된 경우에는 검증된 파일과 `last-result`를 사용한다. [KEEP 계약](https://developer.android.com/reference/androidx/work/ExistingWorkPolicy#KEEP), [WorkManager 조회 소스](https://android.googlesource.com/platform/frameworks/support/+/db73e0b8cd586cfeb723a3d99f75e04e14db41d5/work/work-runtime/src/main/java/androidx/work/impl/model/WorkSpecDao.kt)
- 취소 기록과 최종 파일의 원자적 공개는 같은 파일 잠금에서 순서를 정한다. 작업 ID의 취소 기록이 먼저 저장되면 Worker는 공개를 건너뛴다. 공개가 먼저 끝났으면 검증된 파일은 **내 모델**에 남는다. 취소 기록만 남기고 프로세스가 종료된 경우에는 재실행 시 WorkManager 취소와 임시 파일 정리를 마친다. 재시도는 이전 Worker의 파일 정리가 끝난 뒤 새 ID를 사용한다.
- 변환은 `PocketMuseApplication.sharedLcm.withVerifiedFile { file -> … }`로 검증된 절대 경로를 얻는다. 콜백은 I/O 디스패처에서 실행된다. 누락·손상 시 `null`을 반환하며 저장소 I/O와 콜백의 예외는 호출자에게 전파한다. 네이티브 호출과 취소 후 자원 해제까지 콜백 안에서 끝낸다. 파일 삭제와 교체는 콜백 종료를 기다린다. 공용 다운로드 실패·취소 또는 사용 전 삭제 이후에도 모델은 원본을 유지하고 LoRA를 기다린다. Library에서 LoRA를 다시 준비할 수 있다.

## 검증

Windows 프로젝트 루트에서 실행한다.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

공식 LoRA 실네트워크 테스트는 공용 파일이 아직 없는 격리 패키지에서 실행한다. 실제 전송을 시작하고 Activity를 백그라운드로 보낸 뒤 파일 크기·SHA-256, 고유 작업 ID, Activity 재생성 후 Library 상태를 검사한다.

```powershell
.\gradlew.bat -PconverterBenchmark=Debug :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$pkg = 'com.example.pocketmuseprototype.converterbenchmark'
adb shell am instrument -w -e class com.example.pocketmuseprototype.SharedLcmNetworkAndroidTest -e sharedLcmNetwork true "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
```

공용 파일이 없는 같은 격리 패키지에서 실제 전송 취소와 재다운로드도 실행할 수 있다. 이 테스트는 Library의 취소 버튼을 누른 뒤 임시·최종 파일이 없고, 새 WorkManager ID로 검증된 파일을 내려받는지 확인한다.

```powershell
adb shell am instrument -w -e class 'com.example.pocketmuseprototype.SharedLcmNetworkAndroidTest#libraryCancelsLiveTransferAndRetryPublishesVerifiedAsset' -e sharedLcmCancelNetwork true "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
```

2026-11-12 Galaxy S20+ (SM-G986N, Android 13/API 33) 격리 Debug 빌드에서 공식 파일을 한 번 다운로드했다. Activity를 백그라운드로 보낸 뒤 전송을 완료했고, 중복 요청과 Ready 상태에서 고유 작업 ID가 유지됐다. 기기의 `stat`과 `sha256sum`으로 공식 크기와 해시를 확인했다. Activity 재생성과 앱 프로세스 재시작 뒤 Library에서 **사용 가능 · 135MB**를 확인했다. Library에서 삭제하면 최종 파일과 LR 카드가 사라졌고, 다시 실행한 뒤에도 빈 상태가 유지됐다. 실기기에서 전송 중 프로세스 종료·네트워크 단절과 Android 14–16의 작업 제약은 별도로 시험해야 한다.

후속 검증에서는 같은 기기에서 WorkManager 2.11.2의 성공·실패·취소 작업 교체와 기록 정리 후 복구를 합성 Worker로 확인했다. 별도의 실네트워크 계측에서 Library로 실제 전송을 취소한 뒤 임시·최종 파일이 없음을 확인했고, 재시도는 새 ID로 완료되어 공식 길이와 SHA-256이 일치했다. 취소 기록 직후 프로세스가 종료되는 경우는 저장 상태를 재구성한 JVM 테스트로 검증했다.

호스트 테스트는 합성 HTTP 응답과 임시 파일을 사용한다. Android 테스트는 Compose 상태·탭 이동·Activity 재생성, Room 재열기, 앱 전용 파일 및 포그라운드 서비스 선언과 알림 채널을 검사한다. 실계정 E2E는 별도로 Galaxy S20+에서 로그인, 진행률, 홈 화면 전환, 취소, 다시 다운로드, 프로세스 재실행 후 로컬 썸네일, 삭제를 확인한다.

- Debug 빌드, 호스트 테스트 36개, lint, Galaxy S20+ 계측 테스트 18개를 통과했다. lint 결과는 오류 0개, 경고 33개다.
- 2026-11-11 Galaxy S20+ (SM-G986N, Android 13/API 33, Debug)에서 실제 계정으로 Realistic Vision V6.0 B1의 `V6.0 B1 inpainting (VAE)` 파일을 받았다. 첫 전송에서 Library 진행률과 알림을 확인했다. Chat 탭으로 이동한 뒤 홈 화면으로 전환했고, ADB `stat`으로 4초 동안 `.part`가 429,264,567바이트에서 454,295,525바이트로 늘어난 것을 확인했다. 취소 후 모델 디렉터리가 제거됐다.
- 같은 파일을 다시 요청했고 전송 중 중복 요청을 눌러도 Library 항목 하나만 유지됐다. 최종 파일은 2,132,679,948바이트였고, 기기의 `sha256sum`이 Civitai 파일 ID 190186의 SHA-256과 일치했다. ADB로 앱 프로세스를 종료한 뒤 앱을 열어 **다운로드 완료**와 로컬 썸네일을 확인했다. Library에서 삭제한 뒤 행과 해당 모델 디렉터리가 제거됐다. Android 14–16의 실제 작업 제한과 전송 도중 강제 종료는 이 기기 시험에 포함되지 않았다. [Civitai 버전 메타데이터](https://civitai.com/api/v1/model-versions/245627)
