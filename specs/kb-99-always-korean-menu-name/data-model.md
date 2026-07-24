# Phase 1 Data Model: 언어 무관 메뉴명 한국어 항상 포함

신규 엔티티·테이블·컬럼 없음. 기존 도메인이 보유한 값에서 파생 필드를 노출할 뿐이다.

## 기존 구조 (변경 없음)

- `Food.content: FoodContent`
- `FoodContent.name: LocalizedText`
- `LocalizedText { korean: String, translations: Map<LanguageCode, String> }`
  - `korean`: 언어 무관 한국어 원문 (DB `foods.korean_name`, NOT NULL)
  - `resolve(lang)`: `lang==KO` 면 `korean`, 아니면 `translations[lang] ?: korean` (ko 폴백)

## 파생 필드: 한국어 메뉴명

| 항목 | 값 |
|------|-----|
| 지역화명 (기존 `name`) | `food.displayName(lang)` = `content.name.resolve(lang)` |
| 한국어 원문 | `food.koreanName()` = `content.name.korean` (신규 seam) |
| 노출 `koreanName` | `koreanName_원문.takeIf { it != 지역화명 }` → 동일하면 `null` |

**규칙 (FR-003)**: `지역화명 == 한국어원문` ⇔ `koreanName = null`. 이는 `lang=ko` 및 모든 ko 폴백(지원 언어 번역 부재)에서 성립한다.

## Result / Response 필드 델타

| 계층 | 타입 | 추가 필드 |
|------|------|-----------|
| application | `GetFoodDetailResult` | `koreanName: String?` |
| application | `BrowseMenusResult.MenuSummaryView` | `koreanName: String?` |
| web | `FoodDetailResponse` | `koreanName: String?` (+ `@Schema`) |
| web | `MenuSummaryResponse` | `koreanName: String?` (+ `@Schema`) |

## 상태 전이

없음(읽기 전용 조회).

## 검증 규칙

- `LocalizedText.korean` 은 blank 불가(기존 invariant) → 한국어 원문은 항상 유효값 존재.
- 파생 `koreanName` 은 nullable, blank 아님(원문이 blank 아니므로) 또는 null.
