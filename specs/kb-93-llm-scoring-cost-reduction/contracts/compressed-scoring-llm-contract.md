# Contract: 압축 스코어링 LLM 프롬프트·응답 + fan-out 모델별 요청 seam (KB-93)

**Date**: 2026-07-07 | **Consumers**: `:app:batch` `AvoidanceScoringJob` | **Producers**: `:core:research`(프롬프트·파서), `:infra:llm`(fan-out)

> **Amendment 2026-07-07**: 실측 스모크 결과 **이름 번역(`t`)을 스코어링 응답에 번들하면 Gemini 의 `t` 직렬화 오류가 응답 전체를 malformed 로 만들어 스코어링까지 폐기**시킴이 드러났다. 이에 스코어링 호출에서 이름 번역을 완전히 제거하고 **3모델 모두 스코어링 전용(§2.1)** 으로 통일했다. 아래 **§1.3 의 "스코어링+번역" 변형·§2.2 의 `t` 응답·§4 의 GEMINI→번역 매핑은 스코어링에서 미사용**이며(번역은 KB-94 별도 배치로 이관), 관련 코드(`includeNameTranslations`·파서 `t`·`generate(requestFor)`)는 KB-94 재사용/후속 정리 대상으로만 존치한다. 배치 조율은 `generate(request)` 로 3모델에 동일 스코어링 전용 프롬프트를 보낸다.

## 1. 프롬프트 계약 (시스템 → 모델)

역할별 2개 변형. 어느 모델이 어떤 변형을 받는지는 배치 조율이 결정한다(**Amendment 2026-07-07**: 스코어링은 전 모델 "스코어링 전용" 변형만 사용 — 번역 변형은 KB-94 이관).

### 1.1 system 메시지 (정적 프리픽스 — 청크 불변, 프리픽스 캐싱 정렬)

순서 고정:

1. **축약 지시문**: 대표(표준) 레시피 기준 판단 · 포함 쌍만 응답 · score(0/1/2)·probability(정수 1~100) 의미 · raw JSON 만 출력(코드펜스·설명 금지) · `c` 에 판단 완료 음식 인덱스 전부 나열(포함 성분 없어도).
2. **응답 스키마 지시**: 아래 §2 스키마를 그대로 제시.
3. (번역 변형만) **고정 언어 순서 선언**: `zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es` — `t` 배열의 위치 의미. `ko` 없음.
4. **후보 성분 목록**: `성분인덱스:CODE` 한 줄씩 81종(한국어 라벨 없음 — FR-002).

### 1.2 user 메시지 (동적 — 청크별)

- **음식 목록**: `음식인덱스:한국어명` 한 줄씩(0-base, 청크 내 순서).

### 1.3 변형별 차이 요약

| 변형 | 대상 모델 | 판단 지시 | `t`(이름 번역) 지시 | 설명 지시 |
|------|-----------|-----------|--------------------|-----------|
| 스코어링 전용 | OpenAI, Upstage | ✅ | ❌ (스키마에 `t` 미등장) | ❌ |
| 스코어링+번역 | Gemini | ✅ | ✅ | ❌ |

**금지 불변식**: 어떤 변형에도 음식 설명(생성·번역) 지시문이 없다(FR-004/SC-002).

## 2. 응답 계약 (모델 → 시스템)

### 2.1 스코어링 전용 (OpenAI·Upstage)

```json
{
  "c": [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
  "r": [
    [0, 12, 2, 90],
    [0, 5, 1, 40],
    [3, 7, 2, 85]
  ]
}
```

### 2.2 스코어링+번역 (Gemini)

```json
{
  "c": [0, 1, 2],
  "r": [[0, 12, 2, 90]],
  "t": [
    [0, ["炒饭", "Fried rice", "チャーハン", "炒飯", "Cơm chiên", "Nasi goreng", "ข้าวผัด", "Жареный рис", "Arroz frito"]]
  ]
}
```

### 2.3 필드 정의

| 필드 | 타입 | 제약 |
|------|------|------|
| `c` | `int[]` | 판단 완료 음식 인덱스. 필수 |
| `r` | `int[4][]` | `[음식인덱스, 성분인덱스, score, probability]`. score ∈ {0,1,2}, probability ∈ 1..100. 미포함 쌍은 생략. 필수(빈 배열 허용) |
| `t` | `[int, string[9]][]` | 번역 변형만. 두 번째 원소는 고정 언어 순서의 번역 문자열 배열 |

## 3. 파싱 규칙 (`ScoringResponseParser` — KB-53 의미 보존)

| # | 입력 상황 | 처리 |
|---|-----------|------|
| P1 | 코드펜스(```) 래핑 | strip 후 파싱(기존 유지) |
| P2 | 루트 비객체 / 유효 JSON 아님(절단 포함) / `r` 부재·비배열 | `ScoringResponseParseException` → **모델 실패**(청크 미확정 + 로깅) |
| P3 | `r` 항목이 4-원소 정수 배열 아님 | 항목 스킵 |
| P4 | 음식/성분 인덱스 범위 이탈 | 항목 스킵 |
| P5 | score/probability 범위 이탈·비정수 | 항목 스킵 |
| P6 | (음식,성분) 중복 | 첫 항목 채택, 이후 스킵 |
| P7 | `coveredFoodIds` 산출 | `c` 유효 인덱스 ∪ `r` 유효 항목 음식 인덱스 |
| P8 | 커버리지 < 청크 전체 | 파싱은 성공 — 확정 게이트(배치)에서 그 모델 미취합 |
| P9 | `t` 배열 길이 < 9 / blank 원소 | 존재 위치만 채택·blank 는 언어 누락(best-effort) |
| P10 | `t` 음식 인덱스 중복·범위 이탈 | 첫 항목 채택·이탈 스킵 |
| P11 | 구 KB-53 key-value 응답 등 스키마 밖 구조 | 유효 `r` 항목만 취함 — 유효 판단 없고 커버리지 미달이면 결과적으로 미취합, 루트 자체가 부적합하면 P2 |

## 4. fan-out 모델별 요청 seam (`:infra:llm` 공개 API)

```kotlin
class LlmFanoutClient(...) {
    fun generate(request: LlmChatRequest): LlmFanoutResult            // 기존 — 전 모델 동일 요청
    fun generate(requestFor: (LlmModelId) -> LlmChatRequest): LlmFanoutResult  // 신규 — 모델별 요청
}
```

- 활성 caller 각각에 대해 `requestFor(caller.modelId)` 로 요청을 얻어 **모델별 정확히 1회** 호출(FR-006).
- 타임아웃·부분 실패 수집(`successes`/`failures`) 의미는 기존과 동일.
- `:infra:llm` 은 요청 내용(번역/스코어링)의 의미를 모른다 — 벤더 중립 유지.

### 배치 조율 매핑 (소비자 계약)

```kotlin
val scoringOnly = promptFactory.build(foods, candidates, includeNameTranslations = false)
val withTranslation = promptFactory.build(foods, candidates, includeNameTranslations = true)
llmFanoutClient.generate { modelId ->
    if (modelId == LlmModelId.GEMINI) withTranslation.toChatRequest() else scoringOnly.toChatRequest()
}
```

## 5. 모델 옵션 계약 (`meogo.llm.*` 프로퍼티)

| 프로퍼티 | openai | upstage | gemini |
|----------|--------|---------|--------|
| `max-output-tokens` | 2048 → `maxCompletionTokens` | 2048 → `maxTokens` | 4096 → `maxOutputTokens` |
| `reasoning-effort` | `minimal` → `reasoningEffort` | (미설정) | (미설정) |

null(미설정) 프로퍼티는 벤더 옵션에 싣지 않는다(KB-49 boot-safety·기존 모델 무영향).

## 6. 확정 게이트 (불변 — 재확인)

청크 확정 ⇔ fan-out 실패 0 ∧ 3모델 파싱 성공 ∧ 3모델 모두 `coveredFoodIds ⊇ 청크 음식 전체`. `t`(이름 번역) 누락·부재는 게이트에 불참(FR-008).
