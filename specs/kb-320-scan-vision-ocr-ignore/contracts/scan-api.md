# Contract: 스캔 HTTP API (불변 — 회귀 기준선)

**Owner**: `api/src/main/kotlin/com/kbap/api/scan/` (`ScanController`·`ScanRequest`·`ScanResponse`)

**Asserted by**: `ScanControllerTest` (기존 테스트가 이미 전 항목을 덮는다)

이 문서는 **바꿀 것이 아니라 깨지지 않았음을 증명할 대상**이다. FR-007(계약 불변)이 릴리스 조건이므로, 아래 표에서 하나라도 달라지면 이번 변경은 실패다.

## `POST /api/v1/scans`

### 요청

| 위치 | 필드 | 타입 | 검증 | 이번 변경 |
|------|------|------|------|-----------|
| query | `lang` | String | 필수(헌법 원칙 V — 비어 있으면 400) | 불변 |
| body | `imagePath` | String | `@NotBlank`, `@Size(max=512)`, `http(s)://` 로 시작 금지 | 불변 |
| body | `items` | Array | `@NotEmpty`, `@Size(max=100)` | 불변 — **비어 있으면 여전히 400** |
| body | `items[].idx` | Int | `@NotNull`, 요청 내 중복 금지(`AssertTrue`) | 불변 |
| body | `items[].rawMenuName` | String | `@NotBlank` | 불변 — **필수로 유지, 판독에는 미사용** |

`items` 를 선택 필드로 바꾸거나 상한을 손대지 않는다. 판독에 안 쓴다고 계약에서 빼면 배포된 앱의 매칭이 깨진다.

### 응답 (`BaseResponse<ScanResponse>`)

| 필드 | 타입 | 이번 변경 |
|------|------|-----------|
| `degraded` | Boolean | 불변 |
| `results[].idx` | Int? | **값의 산출 규칙만 강화**(중복 시 이후 항목 null) — 타입·의미 불변 |
| `results[].matched` | Boolean | 불변 |
| `results[].foodId` | Long? | 불변 |
| `results[].riskLevel` | String | 불변 |
| `results[].name` | String? | 불변 |
| `results[].koreanName` | String? | 불변 |
| `results[].price` | Int? | 불변 |

### 오류

| 상황 | 상태 | 코드 | 이번 변경 |
|------|------|------|-----------|
| 비전 판독 실패·형식 오류·타임아웃 | 503 | `SCAN-002` (`MENU_BOARD_RECOGNITION_FAILED`) | 불변 — 새 모델의 실패도 같은 경로로 흐른다 |
| 요청 검증 실패 | 400 | 기존 코드 | 불변 |
| 미인증 | 401 | 기존 코드 | 불변 |

## 회귀 판정 방법

`./gradlew :api:test --tests "com.kbap.api.scan.ScanControllerTest"` 가 **수정 없이** 통과해야 한다. 기존 테스트를 고쳐야 통과한다면 계약이 깨진 것이다 — 이번 변경에서 이 파일에 허용되는 편집은 **테스트 추가뿐**이다(기존 given/when/then 블록의 기대값 수정 금지).

예외: `ScanService.kt:34-35` 의 주석 처리된 소유권 검증과 짝인 비활성 테스트 2건(`xwhen`)은 이번 범위 밖으로 그대로 둔다.
