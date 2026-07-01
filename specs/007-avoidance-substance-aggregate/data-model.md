# Phase 1 Data Model: 회피·주의 성분 어그리게이트

## 도메인 (`:core:avoidance`, ORM-free · Spring-free · 불변)

### AvoidanceSubstanceCode (식별자 enum · 신규)

```
enum AvoidanceSubstanceCode { EGG, MILK, PEANUT, ... }   // 81종, 필드·init 없음
```

- **데이터 없음**: koName·categories·translations 를 갖지 않는다(전부 어그리게이트/ DB 로 이동).
- 용도: 컴파일 타임 코드 집합·`when` 망라 매칭·타입 안전 참조(#16).
- 코드 상수 이름 = 기존 `AvoidanceSubstance` enum 상수명 그대로(= DB `code` 컬럼 값).

### AvoidanceCategory (값 enum · 유지)

```
enum AvoidanceCategory { ALLERGEN, DIETARY_RULE, PERSONAL_AVOIDANCE }
```

- 변경 없음. 성분의 분류(사유) vocabulary.

### AvoidanceSubstance (어그리게이트 · 기존 enum 대체)

| 필드 | 타입 | 설명 |
|------|------|------|
| `id` | `Long` | DB 식별자(BaseEntity.id) |
| `code` | `AvoidanceSubstanceCode` | 코드 식별자 |
| `koreanName` | `String` | 한국어 원문(DB `korean_name`) |
| `translations` | `Map<LanguageCode, String>` | 9개 대상 언어 번역(빈 값 제외) |
| `categories` | `Set<AvoidanceCategory>` | 분류 멤버십(1~3) |

**행위**
- `displayName(lang: LanguageCode): String` — `lang == KO` → `koreanName`; else `translations[lang] ?: koreanName`(ko 폴백).
- `belongsTo(category: AvoidanceCategory): Boolean` — `category in categories`.

**불변식/규약**
- 모든 필드 `val`. 데이터 클래스 public `copy` 미노출(필요 시 `private fun copy`).
- 생성은 `private constructor` + `companion object { fun reconstitute(id, code, koreanName, translations, categories): AvoidanceSubstance }`.
- JPA 를 import 하지 않는다. `@AggregateRoot`(순수 마커) 부착.
- `require(categories.isNotEmpty() && categories.size <= 3)`, `require(koreanName.isNotBlank())` — 기존 enum init 불변 계승.

### 제거 대상 (전이 유물)

- `AvoidanceCatalog`(displayName/byCategory/all — enum+in-memory) → 어그리게이트·port 로 대체.
- `AvoidanceSubstanceTranslations`(enum→lang→name 맵) → 번역 원천은 DB(+JSON 시드).

## 영속 (`:infra:persistence`, JPA — 스키마 변경 없음)

### AvoidanceSubstanceJpaEntity (변경: toDomain 어그리게이트 복원)

- 컬럼 그대로: `code`(len 40) · `korean_name`(len 100) · `name_zh_hans`…`name_es`(9개, nullable) + BaseEntity(id·status·시각).
- **`toDomain(categories: Set<AvoidanceCategory>): AvoidanceSubstance`**: 번역 컬럼을 `Map<LanguageCode,String>`(blank/null 제외)로 모으고, `code` 를 `AvoidanceSubstanceCode.valueOf(code)`, 인자로 받은 `categories` 와 함께 `AvoidanceSubstance.reconstitute(id, ...)` 호출.
  - 분류는 별도 테이블이라 엔티티 단독으로 못 채운다 → **어댑터가 배치 조회한 분류 집합을 주입**한다(D-4).
- 쓰기 경로 없음(SQL 시드) → `from(domain)` 불필요.

### AvoidanceSubstanceCategoryJpaEntity (변경: String 저장)

| 컬럼 | 변경 전 | 변경 후 |
|------|---------|---------|
| `category` | `@Enumerated(STRING) AvoidanceCategory` | `@Column(length=30) var category: String` |
| `substance_id` | `Long` | (유지) |

- 도메인 enum 직접 매핑 제거(관례 정렬). 저장 값은 동일(enum 이름 문자열)이라 **DB 데이터·마이그레이션 불변**.
- 조회: `findByCategory(name: String)` · `findBySubstanceIdIn(ids)`.

## 변환·조립 흐름 (어댑터, N+1 회피)

공통 헬퍼 `reconstituteByIds(substanceIds: Set<Long>): List<AvoidanceSubstance>`:
1. `substanceJpaRepository.findByIdIn(ids)` → 성분행(번역).
2. `categoryJpaRepository.findBySubstanceIdIn(ids)` → 분류행 전체 → `groupBy substanceId` → `Set<AvoidanceCategory>`(String→enum).
3. 각 성분행에 대해 `entity.toDomain(categoriesOf[entity.id])`.

이를 재사용:
- `byCategory(cat)`: `findByCategory(cat.name)` → substanceIds → `reconstituteByIds`.
- `findByCodes(codes: Set<AvoidanceSubstanceCode>)`: `findByCodeIn(codes.map { it.name })` → ids → 조립(또는 코드행에서 직접 조립 + 분류 배치 조회).
- `IngredientAvoidanceSubstanceRepository.findByIngredientIds`: 매핑행 → substanceIds → `reconstituteByIds` → ingredientId 로 group.

## 관계도

```
Ingredient(id) ─(N:M avoidance_substance_ingredient)─ AvoidanceSubstance(id, code)
                                                            │ 1:N
                                                  avoidance_substance_category(substance_id, category:String)

AvoidanceSubstanceCode(enum) ──식별자── AvoidanceSubstance(어그리게이트) ──분류── AvoidanceCategory(enum)
                                              └ translations: Map<LanguageCode,String>
```
