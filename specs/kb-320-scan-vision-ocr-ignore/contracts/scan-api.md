# Contract: 스캔 API — v1 동결 · v2 신규

**Owner**: `api/src/main/kotlin/com/kbap/api/scan/`

**Asserted by**: `ScanControllerTest`

계약은 **URL 경로가 결정한다**. `X-API-Version` 헤더는 스캔에서 동작을 가르지 않는다(온보딩은 계속 사용).

## 한눈에

| | `POST /api/v1/scans` (동결) | `POST /api/v2/scans` (신규) |
|---|---|---|
| 요청 | `imagePath` + `items` **필수**(1~100) | `imagePath` 만 |
| 판독 근거 | 사진 + 클라이언트 OCR 병용 | 사진 단독(서버 OCR) |
| 응답 `idx` | 있음 | **없음** |
| 응답 `similarFood` | **없음** | 있음 |
| 헤더 분기 | 없음 | 없음 |

---

## v1 — `POST /api/v1/scans` (동결)

**이 표는 바꿀 것이 아니라 깨지지 않았음을 증명할 대상이다.** KB-319 이전 계약과 동일해야 한다.

### 요청

| 위치 | 필드 | 타입 | 검증 |
|------|------|------|------|
| query | `lang` | String | 필수(헌법 원칙 V — 비어 있으면 400) |
| body | `imagePath` | String | `@NotBlank`, `@Size(max=512)`, `http(s)://` 로 시작 금지 |
| body | `items` | Array | **`@NotEmpty`**, `@Size(max=100)` |
| body | `items[].idx` | Int | `@NotNull`, 요청 내 중복 금지(`AssertTrue`) |
| body | `items[].rawMenuName` | String | `@NotBlank` |

### 응답 (`BaseResponse<ScanResponse>`)

`degraded` · `results[]{ idx?, matched, foodId?, riskLevel, name?, koreanName?, price? }`

`similarFood` 필드는 **존재하지 않는다**(v2 전용).

### `idx` 불변식

| 불변식 | 강제 지점 |
|--------|-----------|
| `idx ∈ 요청의 idx 집합` | `ScanService` — `takeIf { it in validIdxes }` |
| **`idx` 는 한 응답에서 최대 1회** | `ScanService` — `usedIdxes.add(it)` (KB-320 신규) |
| 대응 OCR 없으면 `null` | 모델이 `matchedIdx = null` |

중복 시 **먼저 나온 결과가 갖는다** — `map` 이 순서를 보장하므로 같은 입력에 같은 결과가 나온다. 뒤 결과도 응답에는 남는다(메뉴 소실 금지).

### 회귀 판정

`./gradlew :api:test --tests "com.kbap.api.scan.ScanControllerTest"` 가 **기존 v1 시나리오를 수정 없이** 통과해야 한다. 이 파일에 허용되는 편집은 **테스트 추가뿐**이다.

---

## v2 — `POST /api/v2/scans` (신규)

### 요청

| 위치 | 필드 | 타입 | 검증 |
|------|------|------|------|
| query | `lang` | String | 필수 |
| body | `imagePath` | String | `@NotBlank`, `@Size(max=512)`, `http(s)://` 금지 |

`items` 는 **계약에 없다**. 본문에 섞여 들어와도 무시한다(400 아님).

### 응답 (`BaseResponse<ScanV2Response>`)

`degraded` · `results[]{ matched, foodId?, riskLevel, name?, koreanName?, price?, similarFood? }`

`idx` 는 **없다** — 서버가 클라이언트 화면의 박스를 알지 못한다.

`similarFood{ foodId, name, koreanName?, description, imageRef? }` — 미등록(`matched=false`) 메뉴의 유사 음식 대체. `foodId` 는 항상 조회 가능한 등록 음식이라 음식 상세 API 와 연동된다. 임계 미달·검색 장애·미구성이면 해당 항목만 `null` 이고 스캔 자체는 성공한다(부분 성공).

### 인증

`/api/v2/scans` 는 `WebConfig` 의 보호 경로 목록에 등록돼 있어야 한다. **누락하면 전 시나리오가 401 로 실패한다** — 이번 작업에서 실제로 밟은 함정.

---

## 오류 (두 경로 공통)

| 상황 | 상태 | 코드 |
|------|------|------|
| 비전 판독 실패·형식 오류·타임아웃 | 503 | `SCAN-002` |
| 요청 검증 실패 | 400 | `COMMON-002` |
| 미인증 | 401 | — |
