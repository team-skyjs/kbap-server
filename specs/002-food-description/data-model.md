# Data Model: 음식 설명(간단·자세) 추가

**Feature**: 002-food-description | **Date**: 2026-06-29

기존 001 food 모델(음식·재료·이름 번역)에 **음식 설명 2종(간단·자세)** 과 그 **번역**을 더한다. 가산적 변경 — 기존 엔티티/컬럼은 그대로 둔다.

## 도메인 모델 (`:meogo-api:food`, ORM-free)

### Food (애그리거트 루트) — 확장

| 필드 | 타입 | 규칙 |
|------|------|------|
| id | Long? | 신규 null, 복원 시 존재 |
| koreanName | String | blank 불가 (기존) |
| imageRef | String? | nullable (기존) |
| **briefDescription** | **String** | **blank 불가, ≤255자** (신규) |
| **detailedDescription** | **String** | **blank 불가, ≤1024자** (신규) |
| ingredients | List\<FoodIngredient\> | 기존 |

- `create(...)`·`reconstitute(...)` 팩토리에 `briefDescription`·`detailedDescription` 매개변수를 추가한다(둘 다 non-null).
- `init { require(briefDescription.isNotBlank()); require(detailedDescription.isNotBlank()) }` 로 불변 강화. 길이 상한(255/1024)도 `require` 로 방어(저장 컬럼과 정렬).
- 불변 원칙 유지: 모든 필드 `val`, 상태 변경은 새 인스턴스 반환(이번 변경엔 상태 변경 메서드 없음).

### FoodDescriptionKind (신규 enum)

```
enum class FoodDescriptionKind { BRIEF, DETAILED }
```
- 도메인 port·번역 맵 키로 사용. 영속 `kind` 컬럼은 이 enum name(`BRIEF`/`DETAILED`) 문자열.

### FoodRepository (port) — 확장

| 메서드 | 시그니처 | 비고 |
|--------|----------|------|
| findByKoreanName | `(name): Food?` | 기존 — `toDomain()` 시 설명 컬럼 포함 |
| findFoodNameTranslation | `(foodId, lang): String?` | 기존 |
| findIngredientNameTranslations | `(ids, lang): Map<Long,String>` | 기존 |
| **findFoodDescriptionTranslations** | `(foodId, lang): Map<FoodDescriptionKind, String>` | **신규** — 한 음식의 요청 lang BRIEF·DETAILED 번역. `lang==ko` 면 빈 맵 |

## 영속 모델 (`:meogo-api:persistence`, `com.meogo.api.persistence.food`)

### FoodJpaEntity — 컬럼 추가

| 컬럼 | 타입 | 제약 |
|------|------|------|
| brief_description | VARCHAR(255) | NOT NULL |
| detailed_description | VARCHAR(1024) | NOT NULL |

- 엔티티에 `briefDescription`·`detailedDescription` 프로퍼티 추가, `@Column(name=..., nullable=false, length=255/1024)`.
- `toDomain()` 에 두 설명 전달, `from(domain)` 에 두 설명 매핑.

### FoodDescriptionTranslationJpaEntity (신규) — `food_description_translation`

| 컬럼 | 타입 | 제약 |
|------|------|------|
| id | BIGINT | PK auto-increment (BaseEntity) |
| food_id | BIGINT | NOT NULL, FK → food(id) |
| kind | VARCHAR(10) | NOT NULL, `BRIEF`\|`DETAILED` (CHECK) |
| lang_code | VARCHAR(10) | NOT NULL, 9개 대상 언어만 (CHECK) |
| content | VARCHAR(1024) | NOT NULL (간단도 자세 컬럼 길이에 수용 — 단일 컬럼 공유) |
| status / created_at / updated_at | — | BaseEntity 공통(소프트삭제 `@SQLRestriction status='ACTIVE'`) |

- `UNIQUE(food_id, kind, lang_code)`.
- ko 는 저장하지 않는다(원문은 `food.brief_description`/`detailed_description`).
- `content` 는 두 kind 공용 단일 컬럼이라 자세(1024) 길이에 맞춘다(간단도 1024 안에 수용).
- **Spring Data Repository**: `FoodDescriptionTranslationJpaRepository.findByFoodIdAndLangCode(foodId, langCode): List<FoodDescriptionTranslationJpaEntity>` — 두 kind 행 반환.

### FoodRepositoryAdapter — 구현 추가

```
override fun findFoodDescriptionTranslations(foodId, lang): Map<FoodDescriptionKind, String> {
    if (lang == LanguageCode.KO) return emptyMap()
    return repo.findByFoodIdAndLangCode(foodId, lang.code)
        .associate { FoodDescriptionKind.valueOf(it.kind) to it.content }
}
```

## 변환 규칙

- 도메인↔엔티티 변환은 `FoodJpaEntity` 안(`toDomain`/`from`). 번역 엔티티는 `RepositoryAdapter` 가 직접 매핑(번역은 도메인 객체가 아닌 read 값).
- `RiskLevel`·재료·이미지 등 기존 매핑 불변.

## V4 마이그레이션 절차 (`V4__add_food_description.sql`, 스키마 owner = presentation)

순서(단일 파일, forward-only, V1~V3 불변):

1. **컬럼 추가(nullable)**
   - `ALTER TABLE food ADD COLUMN brief_description VARCHAR(255) NULL, ADD COLUMN detailed_description VARCHAR(1024) NULL;`
2. **번역 테이블 생성**
   - `CREATE TABLE food_description_translation (... kind VARCHAR(10), lang_code VARCHAR(10), content VARCHAR(1024) ...)` + `UNIQUE(food_id, kind, lang_code)` + FK + `CHECK(kind IN ('BRIEF','DETAILED'))` + `CHECK(lang_code IN ('zh-Hans','en','ja','zh-Hant','vi','id','th','ru','es'))` + 공통 컬럼(status/created_at/updated_at).
3. **기존 seed 음식 ko 설명 채움**
   - 각 V3 seed 음식별 `UPDATE food SET brief_description=..., detailed_description=... WHERE korean_name=...;` (mock placeholder).
4. **번역 INSERT**
   - 각 음식 × {BRIEF,DETAILED} × 9개 언어 `INSERT INTO food_description_translation(...)` (mock placeholder).
5. **NOT NULL 강화**
   - `ALTER TABLE food MODIFY COLUMN brief_description VARCHAR(255) NOT NULL, MODIFY COLUMN detailed_description VARCHAR(1024) NOT NULL;`

> H2 테스트는 flyway off + `create-drop` 이라 V4 미적용 — 엔티티 매핑이 스키마를 만든다. 영속/웹 테스트 seed 는 `FoodTestSeed`/테스트 코드가 엔티티로 직접 적재(기존 방식 유지).

## 영향 받지 않는 것

- scan 컨텍스트·메뉴 스캔 API: 무관.
- 재료·이름 번역·이미지·`inclusionPercent`·mock `riskStatus`: 불변.
- 미수록 메뉴 400·`menuName` blank 400·매칭 키(ko trim exact): 불변.
