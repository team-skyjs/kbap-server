# Data Model: 배치 콘텐츠 4작업 LLM 호출 인터페이스

**Date**: 2026-07-22 | **Plan**: [plan.md](plan.md)

이번 범위에 신규 영속 모델은 없다. 아래는 계약 DTO 와 기존 `food` 적재 필드의 대응이다.

## DTO ↔ `Food` 적재 대응

| 계약 | 출력 DTO | 적재 대상 필드 | 적재 방법 |
|------|----------|----------------|-----------|
| 이름 번역 | `TargetLanguageTexts` | `Food.nameTranslations: Map<String,String>` | `texts.mapKeys { it.key.code }` |
| 설명 생성 | `FoodDescriptionContent` | `Food.description`(≤255) · `Food.descriptionTranslations` · `Food.spiciness` | 필드별 대입(번역 맵은 키 문자열화) |
| 사진 생성 | `String`(저장 완료 키) | `Food.imageRef`(≤500) | 대입 |
| 기피성분 매핑 | `List<FoodAvoidanceAssessment>` | `Food.avoidanceSubstances: List<FoodAvoidanceItem>` | `map { FoodAvoidanceItem(it.code, it.inclusionPercent) }` |

적재 후 `Food.transitionToReadyIfComplete()` 의 완비 판정(`needs*` 5종)이 각 DTO 불변과 맞물린다 —
불변을 통과한 DTO 를 적재하면 해당 작업의 `needs*` 는 반드시 false 가 된다.

## DTO 정의

### TargetLanguageTexts (이름·설명 번역 공용)

| 필드 | 타입 | 불변(init require) |
|------|------|--------------------|
| `texts` | `Map<LanguageCode, String>` | 키 집합 == 9개 대상 언어 전수(`LanguageCode.entries - KO`) · 모든 값 non-blank |

- 근거: Clarify Q1(9종 일괄) · 헌법 V(사전 번역 정책) · `Food.needsNameTranslations()` 완비 판정과 정합.

### FoodDescriptionContent

| 필드 | 타입 | 불변(init require) |
|------|------|--------------------|
| `description` | `String` | non-blank · ≤255자(`food.description` 컬럼 길이) · `Food.PLACEHOLDER_DESCRIPTION`("설명 준비 중")과 불일치 |
| `translations` | `TargetLanguageTexts` | (자체 불변 위임) |
| `spiciness` | `Int` | 0..10 (회원 선호 스케일 정합, 센티널 -1 배제 — research R6) |

### FoodAvoidanceAssessment

| 필드 | 타입 | 불변(init require) |
|------|------|--------------------|
| `code` | `String` | non-blank |
| `inclusionPercent` | `Int` | 0..100 |

- `code ⊆ candidateCodes`(호출 입력) 준수는 DTO 가 아니라 **구현 책임**(계약 문서 규약) — DTO 는 입력 컨텍스트를 모른다. 최종 방어는 KB-209 적재 시점.
- 기존 `FoodAvoidanceItem`(domain:food)과 구조 동일 — `:core` 가 도메인 모듈을 참조할 수 없어 쌍둥이로 둔다(헌법 II).

## 상태 전이

이 기능은 상태를 소유하지 않는다. READY 전이 규칙은 KB-182 골격(`Food.transitionToReadyIfComplete`) 소유이며 변경하지 않는다.
