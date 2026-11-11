# Checkpoints
- Market의 Image 탭은 Civitai 공개 목록에서 최신 SD 1.5 버전의 Pruned SafeTensor 파일을 변환 후보로 표시한다. 버전은 `publishedAt`으로 비교하며 SD 1.5 버전의 날짜가 누락되거나 잘못되어 최신 버전을 판별할 수 없으면 모델을 제외한다. FP16을 FP32보다 먼저 선택하고, 정밀도가 같으면 primary 파일과 낮은 파일 ID를 우선한다. 파일명은 `.safetensors` 확장자를, 다운로드 URL은 HTTPS를 요구한다. [Civitai Models API](https://github.com/civitai/civitai-developer-docs/blob/main/site/reference/models.md)

- 최신 SD 1.5 버전의 `baseModelType`이 `Standard`인 경우만 허용한다. `to-mnn`의 고정 SD 1.5 템플릿은 일반 체크포인트 구조를 전제로 하므로 Inpainting, Refiner, Pix2Pix와 타입이 누락되거나 알려지지 않은 버전은 제외한다. 최신 버전이 이 조건을 충족하지 않으면 모델 전체를 제외한다. [Civitai 버전 메타데이터](https://github.com/civitai/civitai-developer-docs/blob/main/site/reference/models.md#response)

- API는 8개씩 요청한다. 한 번의 로딩에서 최대 5페이지를 읽고, 적격 후보는 최대 8개씩 표시한다. 5페이지에서 8개를 채우지 못하면 사용자가 **더 불러오기**로 이어간다. 실패 시 커서와 이미 확보한 후보를 보관한다. 마지막 페이지의 `nextCursor`가 생략되거나 null이면 남은 후보를 표시한 뒤 조회를 종료한다. [Civitai 목록 엔드포인트](https://github.com/civitai/civitai/blob/main/src/pages/api/v1/models/index.ts)

- `CatalogCheckpoint`는 모델, 버전, 파일 ID와 파일명, 원본 다운로드 URL, 크기, 선택적 SHA256을 보존한다. `MarketScreen.onDownloadRequest`는 Civitai에 연결한 사용자가 선택한 후보를 다운로드 대기열에 전달한다. Worker는 버전과 파일 ID를 대조하고 받은 파일의 무결성을 확인한다. 실제 변환 호환성은 별도 검증이 필요하다. [모델 다운로드 계약](Model-Downloads.md), [Civitai 파일 URL 구성](https://github.com/civitai/civitai/blob/main/src/server/services/model-search.service.ts#L347-L374)

- 2026-11-11 공개 API 확인에서 첫 페이지 8개 중 7개, 다음 커서 페이지 8개 중 5개가 적격이었다. 두 페이지의 `nextCursor`는 문자열이었고 모델 ID는 중복되지 않았다. 결과와 미리보기는 Civitai 응답에 따라 바뀔 수 있다.

- 같은 날 Realistic Vision V6.0 B1의 최신 SD 1.5 버전 `V6.0 B1 inpainting (VAE)`는 API에서 `Standard`로 분류되어 후보에 포함됐다. 이 분류만으로 체크포인트의 실제 구조를 확인할 수 없다. [Civitai 모델 응답](https://civitai.com/api/v1/models/4201)
