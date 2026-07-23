# Contracts: 배치 콘텐츠 LLM 클라이언트 4종

**Date**: 2026-07-22 | **Plan**: [../plan.md](../plan.md)

외부 노출 API(web) 는 없다. 이 기능의 계약은 (A) `:core` seam 4개 — **기존 확정, 무수정**(FR-008), (B) 각 구현체가 프롬프트로 지시하고 파싱하는 **LLM 응답 JSON** 이다.

## A. Seam 계약 (`com.kbap.core.food` — 기존)

```kotlin
fun interface FoodNameTranslationClient    { fun call(koreanName: String): TargetLanguageTexts }
fun interface FoodDescriptionClient        { fun call(koreanName: String): FoodDescriptionContent }
fun interface FoodImageGenerationClient    { fun call(koreanName: String, storageKey: String): String }
fun interface FoodAvoidanceAssessmentClient { fun call(koreanName: String, candidateCodes: Set<String>): List<FoodAvoidanceAssessment> }
```

실패 계약(공통): 응답이 계약을 위반하면 예외를 전파한다 — 배치 Step 의 skip 정책이 건 단위로 격리한다. 조용한 부분 성공·기본값 대체 금지.

## B. 구현체별 LLM 응답 JSON 계약

### 1. SpringAiFoodNameTranslationClient (OpenAI caller 1건 호출)

응답 형식(프롬프트 지시):

```json
{"translations": {"zh-Hans": "...", "en": "...", "ja": "...", "zh-Hant": "...", "vi": "...", "id": "...", "th": "...", "ru": "...", "es": "..."}}
```

- 9개 대상 언어 키 **전수 필수** — 누락·추가 키·blank 값이면 `TargetLanguageTexts` init 이 예외.
- 코드펜스(```json) 허용 — 파서가 제거.

### 2. SpringAiFoodDescriptionClient (OpenAI caller 1건 호출)

```json
{"description": "≤255자 한국어 설명", "spiciness": 3, "translations": {"zh-Hans": "...", ...(9개 전수)}}
```

- `FoodDescriptionContent` init 이 255자·플레이스홀더·spiciness 0..10 강제.

### 3. SpringAiFoodAvoidanceAssessmentClient (LlmFanoutClient — 3모델)

각 모델에 동일 프롬프트(모든 candidateCodes 에 대해 0..100 판단, 미포함 = 0):

```json
{"assessments": [{"code": "PORK", "inclusionPercent": 80}, {"code": "BEEF", "inclusionPercent": 0}, ...]}
```

종합 규칙(구현체):

1. 모델 응답별 파싱·검증 — `code ∉ candidateCodes` 또는 percent 범위 밖이면 그 모델 응답을 실패로 강등.
2. 유효 응답 < 2 → 예외 전파(복수 모델 종합 불가).
3. 코드별 유효 응답 percent 평균(정수 반올림), 0 은 결과에서 제외.
4. `candidateCodes` 가 빈 집합이면 LLM 을 호출하지 않고 빈 목록 반환.

### 4. OpenAiFoodImageGenerationClient (OpenAI 이미지 모델 + StorageObjectStore)

- 입력: `koreanName`(프롬프트 소재), `storageKey`(저장 위치).
- 동작: 이미지 생성(b64) → `StorageObjectStore.put(storageKey, bytes, "image/png")` → **저장 성공 후** `storageKey` 반환.
- 저장 실패·생성 실패 → 예외 전파(키 반환 없음). 같은 키 재호출은 put 덮어쓰기로 멱등.

## C. 빈 조립 계약 (`FoodContentClientConfiguration`)

| 빈 | 활성 조건 | 의존 |
|----|-----------|------|
| `FoodNameTranslationClient` | `kbap.llm.openai.enabled=true` | `openAiModelCaller` |
| `FoodDescriptionClient` | `kbap.llm.openai.enabled=true` | `openAiModelCaller` |
| `FoodAvoidanceAssessmentClient` | 상시(fan-out 빈은 항상 존재) — 유효 응답 부족은 런타임 예외 | `llmFanoutClient` |
| `FoodImageGenerationClient` | `kbap.llm.image.enabled=true` | OpenAI 이미지 모델, `StorageObjectStore` |

미구성 환경(local·테스트)에선 조건 미충족 빈이 생성되지 않아 batch/web 부팅이 안전하다(기존 관례).
