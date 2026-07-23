# Data Model: 배치 음식 콘텐츠 프로세서의 작업별 구현

**Date**: 2026-07-23 | **Plan**: [plan.md](plan.md)

**DB 스키마 변경 없음** — `food` 테이블 기존 컬럼만 사용한다(Flyway 마이그레이션 없음). 변경은 코드 계약·엔티티 도메인 메서드에 한정된다.

## Food (`:domain:food` — 기존 엔티티, 메서드 확장)

| 필드 | 컬럼 | 타입 | 미완(센티널) | 채우는 작업 |
|------|------|------|--------------|-------------|
| `koreanName` | `korean_name` | VARCHAR(255) | — (항상 존재, 원문) | — |
| `imageRef` | `image_ref` | VARCHAR(500) NULL | null/blank | (범위 제외 — 스텁) |
| `description` | `description` | VARCHAR(255) | blank 또는 `"설명 준비 중"` | ② 설명 생성+번역 |
| `spiciness` | `spiciness` | INT | `-1` (`SPICINESS_UNASSESSED`) | ③ 기피성분+맵기 |
| `nameTranslations` | `name_translations` | JSON | 9개 대상 언어 미전수 | ① 이름 번역 |
| `descriptionTranslations` | `description_translations` | JSON | 9개 대상 언어 미전수 | ② 설명 생성+번역 |
| `avoidanceSubstances` | `avoidance_substances` | JSON NULL | `null` (미조사) | ③ 기피성분+맵기 |
| `contentStatus` | `content_status` | ENUM | `INCOMPLETE` | writer 의 READY 전이 |

### 도메인 메서드 (신규·변경)

- `updateNameTranslations(translations: Map<String, String>)` — 이름 번역 전수 교체
- `updateDescription(description: String, translations: Map<String, String>)` — 설명 원문+번역을 항상 세트로 교체(부분 병합 없음)
- `assessAvoidance(substances: List<FoodAvoidanceItem>, spiciness: Int)` — **[시그니처 변경]** 성분 목록과 맵기를 원자적으로 설정
- `needsAvoidanceAssessment(): Boolean` — **[신규]** `avoidanceSubstances == null || spiciness == SPICINESS_UNASSESSED` (③ 작업 트리거)
- 기존 유지: `needsImage()` · `needsDescription()` · `needsNameTranslations()` · `needsDescriptionTranslations()` · `needsAvoidanceMapping()` · `transitionToReadyIfComplete()`

### 상태 전이

```
INCOMPLETE ──(모든 needs* 해소 + spiciness != -1, writer 의 transitionToReadyIfComplete)──▶ READY
     │
     └─ 이미지 미보유(needsImage) 음식은 텍스트 3작업 완료 후에도 INCOMPLETE 유지 (FR-008)
```

작업별 성공은 `REQUIRES_NEW` 진행 저장으로 즉시 커밋 — 뒤 작업 실패 시에도 유지되고, 재실행 시 해당 작업의 `needs*` 가 false 라 건너뛴다(FR-006·SC-002).

## FoodDescriptionContent (`:core` — 변경)

| 필드 | 타입 | 불변식 |
|------|------|--------|
| `description` | String | non-blank · ≤255자 · 플레이스홀더 금지 |
| `translations` | TargetLanguageTexts | 9개 대상 언어 전수 · blank 금지 |
| ~~`spiciness`~~ | — | **[제거]** 기피성분 계약으로 이동 (FR-002) |

## FoodAvoidanceAssessmentResult (`:core` — 신규)

`FoodAvoidanceAssessmentClient.call` 의 반환 타입.

| 필드 | 타입 | 불변식 |
|------|------|--------|
| `substances` | List\<FoodAvoidanceAssessment\> | 각 항목: code non-blank · inclusionPercent 0..100 (기존 타입 재사용) |
| `spiciness` | Int | **0..10** (`init` 강제 — 센티널 -1 은 결과에 올 수 없음) |

## TargetLanguageTexts (`:core` — 헬퍼 추가)

- `byCode(): Map<String, String>` — `LanguageCode` 키를 `code` 문자열 키로 변환(엔티티 JSON 컬럼 키 형식). 불변식(9개 전수·blank 금지)은 기존 유지.
