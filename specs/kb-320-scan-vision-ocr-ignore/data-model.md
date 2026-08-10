# Phase 1 Data Model: 스캔 v2 경로 분리 · 비전 모델 교체

**Feature**: kb-320-scan-vision-ocr-ignore | **Date**: 2026-08-11 (범위 재정의 반영)

## 스키마 변경: 없음

Flyway 마이그레이션을 추가하지 않는다. 엔티티·컬럼·인덱스·제약 어느 것도 바뀌지 않는다. 기존 `scan_history`·`llm_call_cost` 를 그대로 쓴다.

## 요청·응답 타입

| 타입 | 소유 | 필드 |
|------|------|------|
| `ScanRequest` (v1, 동결) | `api.scan` | `imagePath` + `items[]{idx, rawMenuName}` **필수** |
| `ScanV2Request` (신규) | `api.scan` | `imagePath` 만 |
| `ScanResponse` (v1, 동결) | `api.scan` | `degraded` + `results[]{ idx?, matched, foodId?, riskLevel, name?, koreanName?, price? }` |
| `ScanV2Response` (신규) | `api.scan` | `degraded` + `results[]{ matched, foodId?, riskLevel, name?, koreanName?, price?, similarFood? }` |

**두 응답이 서로의 부분집합이 아니다** — v1 에만 `idx`, v2 에만 `similarFood` 가 있다. DTO 를 공유하면 두 경로 모두에 "항상 null 인 필드"가 생겨 계약이 흐려진다.

## 내부 결과 타입 — 공유

`ScanResult.ItemRiskResult` 는 두 경로가 공유하며 `idx`·`similarFood` 를 **모두** 들고 있다. 각 응답 매퍼가 필요한 것만 꺼낸다.

- v1 경로: `similarFoodFallback = false` → `similarFood` 는 항상 null
- v2 경로: `ocrItems = emptyList()` → `validIdxes` 가 비어 `idx` 는 항상 null

즉 두 필드의 "항상 null" 은 응답 DTO 가 아니라 **호출 방식**이 보장한다.

## `idx` 불변식 (v1 전용)

| 불변식 | 강제 지점 | 상태 |
|--------|-----------|------|
| `idx ∈ 요청의 idx 집합` | `ScanService` — `takeIf { it in validIdxes }` | 기존 |
| **`idx` 는 한 응답에서 최대 1회** | `ScanService` — `usedIdxes.add(it)` | **KB-320 신규** |

중복 처리는 "먼저 나온 쪽 우선" — `map` 이 순서를 보장해 같은 입력에 같은 결과가 나오고, 어느 쪽을 살릴지 판단할 근거(신뢰도 점수 등)를 모델이 주지 않는다. 뒤 결과도 응답에는 남는다.

## `OcrItem` / `ExtractedMenu` — 타입 불변

`OcrItem(idx, rawMenuName)` · `ExtractedMenu(name, koreanName, priceKrw, matchedIdx)` 모두 정의가 바뀌지 않았다. 이 브랜치가 한때 추가했던 `MenuBoardReadingMode` 는 철회됐고 seam 시그니처는 `extract(imagePath, ocrItems)` 그대로다.

v2 에서는 `ocrItems` 가 빈 목록이라 `matchedIdx` 가 항상 null 로 온다(서버 OCR 프롬프트에 `matchedIdx` 지시 자체가 없다).

## 비용 기록 (`llm_call_cost`) — 스키마 불변, 값만 이동

| 필드 | 이전 | 이후 |
|------|------|------|
| `model_name` | `gpt-4o-mini-2024-07-18` | `gpt-5.6-luna` 계열 응답 모델명 |
| `output_tokens` | 응답 usage | 동일. **추론 토큰이 포함돼 증가한다** |
| `cost_usd` / `cost_krw` | 0.15 / 0.60 단가 | **0.2 / 1.2 단가** |

v1·v2 가 같은 vision 빈을 쓰므로 비용 기록은 경로와 무관하게 동일하다. 대시보드는 `model_name` 으로 그룹핑하므로 교체 시점을 경계로 새 행이 생긴다. 마이그레이션 불필요.

## 삭제된 타입 (LLM 단일 벤더 정리)

`:common` 의 `FoodNameTranslationClient`·`FoodDescriptionClient`·`FoodAvoidanceAssessmentClient` seam 과 값타입(`FoodDescriptionContent`·`FoodAvoidanceAssessment`·`FoodAvoidanceAssessmentResult`)이 사라졌다. 음식 콘텐츠 채움이 kbap-langchain 으로 이관돼(KB-301) 살아있는 소비처가 없었다. `port.llm` 에 남은 seam 은 `MenuBoardVisionExtractor`·`FoodImageBatchClient`·`TextEmbeddingClient` 3종이다.
