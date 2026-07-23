# Contracts: 음식 콘텐츠 생성 seam (com.kbap.core.food)

**Date**: 2026-07-22 | **Plan**: [../plan.md](../plan.md)

외부 API 호출 4태스크 각각의 seam. 모두 외부 API 를 호출하므로 네이밍을 `Food{X}Client` + 메서드 `call` 로 통일한다.
구현 위치: `:infra:llm`(사진은 Lambda 경유 가능 — KB-184 결정). 실패는 예외로 전달(research R3).
음식 1건 단위 호출이며, 4계약은 서로 독립적으로 호출 가능하다(spec Edge Case).

## 1. 음식명 (음식명 번역) — `FoodNameTranslationClient`

```kotlin
fun interface FoodNameTranslationClient {
    fun call(koreanName: String): TargetLanguageTexts
}
```

- 입력: 음식 한국어 이름(원문 = 시드 정체성). 출력: 9개 대상 언어 전수 번역 맵(일괄 — Clarify Q1). ko 원문은 이미 존재하므로 생성하지 않는다.

## 2. 설명 (설명 번역) — `FoodDescriptionClient`

```kotlin
fun interface FoodDescriptionClient {
    fun call(koreanName: String): FoodDescriptionContent
}

data class FoodDescriptionContent(
    val description: String,
    val translations: TargetLanguageTexts,
    val spiciness: Int,
)
```

- `description`: ko 원문, non-blank, ≤255자, 플레이스홀더("설명 준비 중") 불일치.
- `spiciness`: 0..10 — 한 호출로 설명·설명 번역·맵기를 함께 산출해 맵기 센티널(-1)을 해소(Clarify Q2).

## 3. 사진 생성 — `FoodImageGenerationClient`

```kotlin
fun interface FoodImageGenerationClient {
    fun call(koreanName: String, storageKey: String): String
}
```

- `storageKey`: 호출자가 지정한 저장 위치(상대 키, kb-171 프리픽스 규약). 구현은 해당 키에 이미지 저장까지 완료한 뒤 그 키를 반환한다.
- 절대 URL 반환 금지(CDN 키 관례). 같은 키 재호출은 덮어쓰기(멱등).

## 4. 기피성분 조사 — `FoodAvoidanceAssessmentClient`

```kotlin
fun interface FoodAvoidanceAssessmentClient {
    fun call(koreanName: String, candidateCodes: Set<String>): List<FoodAvoidanceAssessment>
}

data class FoodAvoidanceAssessment(
    val code: String,
    val inclusionPercent: Int,
)
```

- `candidateCodes`: 회피·주의 성분 카탈로그 코드 집합(호출자가 `:domain:avoidance` 에서 조회해 주입). 결과 `code` 는 `candidateCodes` 에 속해야 한다(구현 책임 규약).
- 구현은 **3개 모델 API 를 호출해 응답을 종합·최종 판단**한다(안전 직결 데이터 — 기존 `LlmFanoutClient` 3모델 fan-out 관례). 이 fan-out·종합은 계약 뒤에 숨은 구현 상세이며, 호출자(배치)는 최종 판단 목록만 받는다.
- `FoodAvoidanceAssessment`: `code` non-blank, `inclusionPercent` 0..100.

## 공용 DTO — `TargetLanguageTexts`

```kotlin
data class TargetLanguageTexts(
    val texts: Map<LanguageCode, String>,
)
```

- init 불변: 키 집합 == `LanguageCode.entries - KO`(9종 전수), 모든 값 non-blank.

## 소비·구현 매핑

| 계약 | 소비(배치 스텝) | 구현 태스크 |
|------|-----------------|-------------|
| FoodNameTranslationClient | 음식명 스텝 | KB-183 |
| FoodDescriptionClient | 설명 스텝 | KB-183 |
| FoodImageGenerationClient | 사진 스텝 | KB-184 |
| FoodAvoidanceAssessmentClient | 기피성분 스텝(3-API 종합) | KB-209 |
