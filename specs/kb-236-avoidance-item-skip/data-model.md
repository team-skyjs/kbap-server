# Data Model: 기피성분 조사 후보 밖 성분 항목 단위 스킵

**Feature**: kb-236-avoidance-item-skip | **Date**: 2026-07-24

DB 스키마·엔티티 변경 없음. 이 기능의 "데이터 모델"은 LLM 응답 파싱 계층의 인메모리 값들이며 전부 기존 타입이다.

## 기존 타입 (변경 없음)

| 타입 | 위치 | 역할 |
|------|------|------|
| `AssessmentResponse` | `SpringAiFoodAvoidanceAssessmentClient` (`:infra:llm`) | 모델 1개의 원시 응답 — `assessments: List<AssessmentItem>` + `spiciness: Int` |
| `AssessmentItem` | 〃 | 성분 항목 — `code: String` + `inclusionPercent: Int` |
| `ValidResponse` | 〃 (private) | 검증 통과 응답 — `percentByCode: Map<String, Int>` + `spiciness: Int` |
| `FoodAvoidanceAssessmentResult` | `:core` | 종합 결과 — `substances: List<FoodAvoidanceAssessment>` + `spiciness: Int` (`SPICINESS_RANGE = 0..10`) |
| `FoodAvoidanceAssessment` | `:core` | 종합 성분 1건 — `code` + `inclusionPercent` |
| `candidateCodes: Set<String>` | 호출 파라미터 | 성분 카탈로그에서 온 허용 코드 집합 — 항목 유효성 기준 |

## 검증 규칙 (변경 대상은 ① 하나)

`parseValidOrNull(raw, candidateCodes)` 의 판정표:

| # | 조건 | 현재 | 변경 후 |
|---|------|------|---------|
| ① | 항목 code ∉ candidateCodes | 응답 전체 무효 | **항목만 스킵** (percent·중복 검사 없이 제외 — 존재하지 않는 항목 취급) |
| ② | 후보 안 항목 percent ∉ 0..100 | 응답 전체 무효 | 유지 |
| ③ | 후보 안 코드 중복 | 응답 전체 무효 | 유지 |
| ④ | spiciness ∉ 0..10 (누락·null 포함 — 기본값 -1) | 응답 전체 무효 | 유지 |
| ⑤ | JSON 파싱 실패 | 응답 전체 무효 | 유지 |

파생 규칙: 스킵 후 정상 항목 0개(또는 원래 빈 배열) + 유효 spiciness → **유효 응답** (성분 없음으로 종합 참여).

## 상태 전이 영향 (변경 없음, 참고)

음식 콘텐츠 상태 모델(INCOMPLETE→TEXT_READY→PENDING_REVIEW→READY)은 불변. 이 변경의 효과는 "후보 밖 코드 혼입 시 유효 응답 0개 → 조사 실패 → INCOMPLETE 고착" 경로가 "유효 응답 인정 → 정상 저장 → 파이프라인 전진"으로 바뀌는 것뿐이다.

## 종합 의미론 (변경 없음, 기대값 영향 있음)

- `aggregateSubstances`: 후보 코드별로 **유효 응답 전체**에 대해 평균(응답에 없는 코드는 0), 평균 0 은 제외.
- 승격된 응답(후보 밖 코드만 있던 응답 포함)은 이제 분모에 들어간다 — 다중 모델 구성에서 기존 테스트 기대값이 바뀌는 원인(plan.md 핵심 설계 결정 4).
