# Contracts: 음식 콘텐츠 LLM 클라이언트 seam (`:core` → `:infra:llm`)

**Date**: 2026-07-23 | **Plan**: [../plan.md](../plan.md)

web API 변경 없음 — 이 기능의 외부 인터페이스는 `:core` 의 클라이언트 seam 3개다(구현 `:infra:llm`, 소비 `:app:batch`).

## ① FoodNameTranslationClient — 변경 없음

```kotlin
fun interface FoodNameTranslationClient {
    fun call(koreanName: String): TargetLanguageTexts
}
```

- 단일 모델(OpenAI) 호출. 9개 대상 언어 전수·blank 금지는 `TargetLanguageTexts` 불변식이 강제 — 위반 시 예외(해당 음식 처리 실패).
- 조립: `@ConditionalOnProperty(kbap.llm.openai.enabled)` — 미구성 시 빈 부재, 배치 프로세서가 작업 필요 시점에 명시 예외.

## ② FoodDescriptionClient — 반환 계약 변경

```kotlin
fun interface FoodDescriptionClient {
    fun call(koreanName: String): FoodDescriptionContent
}

data class FoodDescriptionContent(
    val description: String,           // non-blank · ≤255자 · 플레이스홀더 금지
    val translations: TargetLanguageTexts,
)   // spiciness 제거 (FR-002 — 기피성분 계약으로 이동)
```

- 단일 모델 호출 1회로 한국어 설명 원문 + 설명 번역 9개 언어를 함께 반환(원문-번역 정합 보장).
- 구현 프롬프트·응답 JSON 에서 spiciness 항목 제거. 계약 위반 응답(빈 설명·언어 누락·플레이스홀더)은 예외 → 해당 음식 실패·기존 값 무훼손(FR-007).

## ③ FoodAvoidanceAssessmentClient — 반환 계약 변경 (맵기 편입)

```kotlin
fun interface FoodAvoidanceAssessmentClient {
    // 구현은 3개 모델 fan-out 응답을 종합해 최종 판단한다(안전 직결 — 단일 모델 판단 금지).
    fun call(koreanName: String, candidateCodes: Set<String>): FoodAvoidanceAssessmentResult
}

data class FoodAvoidanceAssessmentResult(
    val substances: List<FoodAvoidanceAssessment>,  // code ∈ candidateCodes · inclusionPercent 1..100(0 은 종합 시 제외)
    val spiciness: Int,                             // 0..10 (init 강제)
)
```

- 호출 전제: `candidateCodes` 비어 있지 않음 — 비어 있으면 소비자(프로세서)가 작업 자체를 수행하지 않는다(스펙 Edge Case).
- 모델별 응답 형식: `{"assessments": [{"code": "...", "inclusionPercent": N}, ...], "spiciness": M}`
- 모델 응답 무효 조건(응답 전체 무효 — FR-004): code 가 후보 밖 · inclusionPercent 0..100 밖 · **spiciness 0..10 밖 또는 누락** · JSON 파싱 실패
- 종합(FR-003): 유효 응답 **2개 미만이면 예외**(판정 거부 → 해당 음식 실패, 다음 실행 재시도). 성분 = 코드별 평균 반올림(0 제외), 맵기 = 유효 응답 평균 반올림.

## 소비 계약 (`FoodContentItemProcessor`)

| 순서 | 트리거 | 호출 | 반영(Food 도메인 메서드) |
|------|--------|------|--------------------------|
| ① | `needsNameTranslations()` | FoodNameTranslationClient | `updateNameTranslations(texts.byCode())` |
| ② | `needsDescription() ∨ needsDescriptionTranslations()` | FoodDescriptionClient | `updateDescription(description, translations.byCode())` |
| ③ | `needsAvoidanceAssessment()` ∧ 후보 코드 비어 있지 않음 | FoodAvoidanceAssessmentClient | `assessAvoidance(substances, spiciness)` |

- 각 작업 성공 직후 `saveProgress`(REQUIRES_NEW) 즉시 커밋(FR-006). 예외는 전파 → 스텝 skip+로그(SC-004). 이미지 작업은 스텁 유지(FR-008).
