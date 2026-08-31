# Data Model: 회원 프로필 diet 카테고리 복수 선택

## Member (`member`) — 컬럼 1개 추가

| 필드 (Kotlin) | 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|---|
| `dietCategories` | `diet_categories` | `json` | `NOT NULL DEFAULT (JSON_ARRAY())` | 선택한 diet 카테고리 코드 배열(예: `["VEGAN","GLUTEN_FREE"]`) — 빈 배열 = 미선택 |

- 엔티티: `@JdbcTypeCode(SqlTypes.JSON) var dietCategories: List<String> = emptyList()` — `avoidanceSubstanceCodes` 컬럼과 동형.
- `profile` getter 는 `Set<DietCategory>` 로 변환해 노출, `updateProfile(profile)` 이 이름 목록으로 되써넣기.

## MemberProfile (값 객체) — 필드 1개 추가

- `dietCategories: Set<DietCategory>` — 타입이 곧 유효성(저장 후에는 항상 유효한 enum).
- `updatedWith(dietCategories: List<String>? = null)` — **null = 유지, 빈 목록 = 전체 해제**(기존 필드 규칙 동일).
- `validatedDiets(raw: List<String>)` — 미지원 값 `BusinessException(INVALID_DIET_CATEGORY)`(신규 MEMBER-011), 중복은 Set 정규화. `empty()` 는 `emptySet()`.

## DietCategory — 위치 이동만 (내용 무변경)

- `com.kbap.api.ingredient.DietCategory` → `com.kbap.common.domain.ingredient.model.DietCategory`.
- 참조 갱신: `api.ingredient` diets 조회 API·`DietCategoryMappingSyncTest` import, 신규 참조는 `MemberProfile`.

## Flyway

```sql
ALTER TABLE member
    ADD COLUMN diet_categories json NOT NULL DEFAULT (JSON_ARRAY());
```

- 파일명 `V<생성시각 timestamp>__member_diet_categories.sql`. 기존 행은 DEFAULT 로 충족(백필 불필요), additive 라 블루/그린 공존 안전, 순서 독립.

## 무변경 확인

- `avoidance_substance_codes`·위험도 판정(`avoidedCodes`·`getAvoidedCodes`)·스캔·diet 매핑 조회 API — 전부 무변경.
