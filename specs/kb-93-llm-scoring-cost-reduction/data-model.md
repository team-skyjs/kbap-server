# Data Model: LLM 스코어링 호출 비용 절감 (KB-93)

**Date**: 2026-07-07 | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)

DB 스키마 변경 없음(영속은 KB-54 범위 밖). 아래는 도메인 값타입·파싱 중간 구조·설정 프로퍼티의 모델이다.

## 1. 압축 스코어링 프롬프트 (`ScoringPrompt` — 형태 유지, 내용 교체)

| 필드 | 타입 | 설명 |
|------|------|------|
| `system` | `String?` | **정적 프리픽스** — 축약 지시문 + 응답 스키마 + (번역 변형만) 고정 언어 순서 선언 + 후보 81종 인덱스 목록(`i:CODE`, 라벨 없음). 청크와 무관하게 동일(프리픽스 캐싱 정렬, R5) |
| `prompt` | `String` | **동적 부분** — 음식 청크 인덱스 목록(`i:한국어명`)만 |

생성: `ScoringPromptFactory.build(foods, candidates, includeNameTranslations: Boolean)`.

- `includeNameTranslations = false` (OpenAI·Upstage): 스코어링 판단 지시만. 이름 번역·설명 지시문 없음(FR-004·FR-005).
- `includeNameTranslations = true` (Gemini — 담당 모델 지정은 배치 조율만 인지): 스코어링 판단 + 이름 번역(`t`) 지시. 설명 지시문 없음.
- 검증 규칙: `foods` 비어 있으면 예외(기존 유지). 인덱스는 리스트 순서(0-base)로 부여 — 프롬프트와 파서가 같은 리스트를 공유하므로 별도 저장 불요.

## 2. 압축 응답 (모델 → 시스템, 계약: [contracts/compressed-scoring-llm-contract.md](contracts/compressed-scoring-llm-contract.md))

```json
{"c":[0,1,...],"r":[[fi,si,score,prob],...],"t":[[fi,["...",...9]],...]}
```

| 필드 | 존재 | 의미 |
|------|------|------|
| `c` | 필수 | 판단 완료 음식 인덱스 목록(커버리지 attest, R1) |
| `r` | 필수 | 포함 판단 — `[음식인덱스, 성분인덱스, score(0/1/2), probability(1~100)]` |
| `t` | 번역 변형만 | `[음식인덱스, [9개 번역 — 고정 언어 순서]]` (R2) |

## 3. 인덱스 기반 판단 항목 (파서 내부 규칙 — 신규 클래스 없음)

`ScoringResponseParser.parse(content, foods, candidates): ModelScoring` 이 배열 항목을 인덱스로 되짚는다.

| 규칙 | 처리 (KB-53 의미 보존, FR-003) |
|------|------|
| 음식/성분 인덱스가 `foods`/`candidates` 범위 밖 | 해당 항목만 스킵 |
| score ∉ {0,1,2} 또는 probability ∉ 1..100 또는 비정수 | 해당 항목만 스킵 |
| 항목이 4-원소 정수 배열 형식이 아님 | 해당 항목만 스킵 |
| 같은 (음식,성분) 중복 | 첫 항목만 채택, 이후 스킵 |
| 응답에 없는 (음식,성분) 조합 | 미포함 해석(앙상블 정규화 score0/prob1 — KB-53 유지) |
| 루트가 유효 JSON 객체가 아님 / `r` 부재·비배열 | `ScoringResponseParseException` → 모델 실패 |
| `coveredFoodIds` | `c` 유효 인덱스 ∪ `r` 유효 항목의 음식 인덱스 (R1) |
| `t` 항목 | 위치→언어 복원(고정 순서), blank/부족분은 그 언어 누락, 음식당 첫 항목 채택 — best-effort |

## 4. `ModelScoring` (유지)

| 필드 | 변화 |
|------|------|
| `included: Map<Long, List<SubstanceJudgement>>` | 불변 — 압축 파서가 동일 형태 산출 |
| `nameTranslations: Map<Long, Map<LanguageCode, String>>` | 불변 — 번역 담당 모델 결과에만 채워짐 |
| `descriptions: Map<Long, LocalizedText>` | **항상 empty** — 프롬프트에서 설명 제거(FR-004). 필드는 유지(콘텐츠 슬롯 — 설명 별도 티켓이 채울 경로) |
| `coveredFoodIds: Set<Long>` | 불변 — 확정 게이트 입력 |

## 5. `FoodContent` (유지) / `FoodContentSelector` (무변경 목표)

- `nameTranslations`: 단일 소스(Gemini) 병합 — 기존 `putIfAbsent` 순서 병합이 소스 1개에서도 동일 동작.
- `description`: 입력 descriptions 가 항상 비므로 기존 로직상 **항상 `null`** — null 안전 산출(FR-007). 후속 조립·저장(KB-54)은 null 허용 전제.

## 6. LLM 설정 프로퍼티 (`LlmModelProperties.ModelProps` 확장)

| 프로퍼티 | 타입 | 기본 | 벤더 매핑 (R4) |
|----------|------|------|----------------|
| `max-output-tokens` | `Int?` | null(미적용) | OpenAI→`maxCompletionTokens`, Upstage→`maxTokens`, Gemini→`maxOutputTokens` |
| `reasoning-effort` | `String?` | null(미적용) | OpenAI→`reasoningEffort`(값 `minimal`), 그 외 무시 대신 **미설정 유지** |

yml 기본값(배치): openai `reasoning-effort: minimal`·`max-output-tokens: 2048`, upstage `max-output-tokens: 2048`, gemini `max-output-tokens: 4096`(번역 포함 응답, R4 산정).

## 7. 호출 비용 로그 (기존 — 신규 모델 없음)

`SpringAiModelCaller` 로그 필드: `model, promptTokens, completionTokens, totalTokens, costUsd, costKrw`. 판정 단위(청크당 모델별 ₩1, SC-001)와 1:1.

## 상태 전이 (변화 없음)

청크 확정: `fanout 실패 0 ∧ 파싱 성공 3모델 ∧ 3모델 모두 coveredFoodIds ⊇ 청크 음식` → 확정(앙상블), 아니면 전 음식 `FAILED`(재조사). 이름 번역 누락은 게이트 무관(FR-008).
