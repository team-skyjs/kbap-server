# Phase 1 Data Model: 스캔 비전 모델 교체 및 사진 단독 판독

**Feature**: kb-320-scan-vision-ocr-ignore | **Date**: 2026-08-11

## 스키마 변경: 없음

Flyway 마이그레이션을 추가하지 않는다. 엔티티·컬럼·인덱스·제약 어느 것도 바뀌지 않는다.

근거: 이 기능은 **판독 근거를 좁히는 프롬프트 변경 + 모델/단가 교체**다. 저장하는 것의 종류가 아니라 저장할 값을 만들어내는 방법이 바뀐다. 기존 `scan_history`·`llm_call_cost` 가 그대로 쓰인다.

## 흐르는 값의 모양 (변경 없음 — 의미만 이동)

### `OcrItem` (`common.port.llm`) — **의미가 바뀌는 유일한 타입**

| 필드 | 타입 | 이전 역할 | 이번 이후 역할 |
|------|------|-----------|----------------|
| `idx` | `Int` | 클라이언트 화면 박스 식별자 | 동일 |
| `rawMenuName` | `String` | **판독 근거** — 오탈자 교정 기준선, 메뉴 후보 목록, 존재 판정 | **매칭 키** — 사진에서 이미 판독된 메뉴를 화면 박스에 잇는 참조 텍스트 |

타입 정의는 한 글자도 바뀌지 않는다. 역할 축소는 프롬프트 문장이 표현하고, 프롬프트 계약 테스트가 고정한다(contracts/vision-prompt.md).

### `ExtractedMenu` (`common.port.llm`) — 불변

`name`(사진 표기 그대로) · `koreanName`(표준 한국어, DB 조회 키) · `priceKrw`(nullable) · `matchedIdx`(nullable). 기존 `require` 불변식(name/koreanName non-blank, priceKrw ≥ 0) 유지.

**`matchedIdx` 의 산출 근거가 바뀐다**: 이전에는 "OCR 후보 중 무엇이 이 메뉴인가"였고, 이후에는 "사진에서 이미 판독한 메뉴에 대응하는 OCR 항목이 무엇인가"다. 방향이 반대다 — 후자에서는 대응 항목이 없어도 메뉴가 소실되지 않는다(FR-002).

### `ScanResult.ItemRiskResult` (`api.scan`) — 불변

`idx` · `matched` · `foodId` · `riskLevel` · `name` · `koreanName` · `price`.

**`idx` 필드에 붙는 서버측 불변식이 하나 늘어난다**:

| 불변식 | 강제 지점 | 상태 |
|--------|-----------|------|
| `idx ∈ 요청의 idx 집합` (FR-006) | `ScanService` — `takeIf { it in validIdxes }` | 기존 |
| **`idx` 는 한 응답에서 최대 1회** (FR-005) | `ScanService` — 신규 가드. 먼저 나온 결과가 갖고 이후 중복은 `null` | **신규** |
| `idx = null` 이면 클라이언트는 박스를 그리지 않음 | 클라이언트 | 기존 |

중복 처리를 "먼저 나온 쪽 우선"으로 두는 이유: 순서 안정성이 있어 같은 입력에 같은 결과가 나오고, 어느 쪽을 살릴지 판단할 추가 근거(신뢰도 점수 등)를 모델이 주지 않는다.

## 비용 기록 (`llm_call_cost`) — 스키마 불변, 값만 이동

| 필드 | 이전 값 | 이후 값 |
|------|---------|---------|
| `model_name` | `gpt-4o-mini-2024-07-18` (응답 모델명, 없으면 설정값) | `gpt-5.6-luna` 계열 응답 모델명 |
| `input_tokens` / `output_tokens` | 응답 usage 그대로 | 동일. **`output_tokens` 에 추론 토큰이 포함돼 증가한다** |
| `cost_usd` / `cost_krw` | 0.15 / 0.60 단가로 산정 | **0.2 / 1.2 단가로 산정** |

`AdminDashboardMetricsService` 의 모델별 일일 집계는 `model_name` 으로 그룹핑하므로, 교체 시점을 경계로 대시보드에 새 모델 행이 생기고 옛 행은 과거 데이터로 남는다. 마이그레이션 불필요.
