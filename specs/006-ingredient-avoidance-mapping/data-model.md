# Phase 1 Data Model: 회피·주의 성분 카탈로그 DB 영속화 + 재료 매핑

MySQL 3 테이블 + JPA 엔티티(`:infra:persistence`). 도메인 port 반환은 기존 enum `AvoidanceSubstance` 재사용(D-READMODEL), 데이터는 DB 에서 읽음. enum(004)은 시드 원천으로 공존.

## 타입·테이블 개요

```
:core:avoidance  (com.meogo.core.avoidance)  ── 유지 + 추가
  AvoidanceSubstance (enum, 81)              [유지] 타입 통화(code/categories/koName)
  AvoidanceCategory  (enum, 3)               [유지]
  AvoidanceSubstanceTranslations, AvoidanceCatalog [유지] 시드 원천
  AvoidanceSubstanceRepository               [신규 port]  byCategory · translatedName · findByCodes
  IngredientAvoidanceSubstanceRepository     [신규 port]  findByIngredientIds

:infra:persistence  (com.meogo.infra.persistence.avoidance)  ── 신규
  AvoidanceSubstanceJpaEntity            (code, korean_name, 9 번역 컬럼)
  AvoidanceSubstanceCategoryJpaEntity    (substance_id, category)
  IngredientAvoidanceSubstanceJpaEntity  (ingredient_id, substance_id)
  + JpaRepository 3 + Adapter 2 (port 구현, code↔AvoidanceSubstance)

:application:client
  FoodAvoidanceSubstanceResolver         음식 재료 id → 성분 합집합

DB (MySQL, Flyway owner=app:api)
  avoidance_substance · avoidance_substance_category · ingredient_avoidance_substance
```

## DB 테이블

### `avoidance_substance` (성분 카탈로그, 81행 정적)

| 컬럼 | 타입 | 제약 | 비고 |
|------|------|------|------|
| `id` | BIGINT AUTO_INCREMENT | PK | BaseEntity |
| `code` | VARCHAR(40) | NOT NULL, UNIQUE | `AvoidanceSubstance.name`(PEANUT…) |
| `korean_name` | VARCHAR(100) | NOT NULL | ko 원문 |
| `name_zh_hans` | VARCHAR(100) | NULL | 번역(비정규화, D-TRANS) |
| `name_en` | VARCHAR(100) | NULL | |
| `name_ja` | VARCHAR(100) | NULL | |
| `name_zh_hant` | VARCHAR(100) | NULL | |
| `name_vi` | VARCHAR(100) | NULL | |
| `name_id` | VARCHAR(100) | NULL | |
| `name_th` | VARCHAR(100) | NULL | |
| `name_ru` | VARCHAR(100) | NULL | |
| `name_es` | VARCHAR(100) | NULL | |
| `status`/`created_at`/`updated_at` | | | BaseEntity |

- `UNIQUE(code)` — 코드 유일(FR-004·SC-001).
- 번역 컬럼 NULL 허용 → 조회 시 `korean_name` 폴백(FR-003). ko 는 번역 컬럼에 없음(원문은 `korean_name`).
- 새 언어 추가 = 컬럼·엔티티·시드 동시 변경(정적이라 수용, D-TRANS).

### `avoidance_substance_category` (성분 ↔ 분류, 1~3개)

| 컬럼 | 타입 | 제약 | 비고 |
|------|------|------|------|
| `id` | BIGINT AUTO_INCREMENT | PK | BaseEntity |
| `substance_id` | BIGINT | NOT NULL, FK→`avoidance_substance(id)` | |
| `category` | VARCHAR(30) | NOT NULL | `AvoidanceCategory`(ALLERGEN/DIETARY_RULE/PERSONAL_AVOIDANCE) |
| `status`/`created_at`/`updated_at` | | | BaseEntity |

- `UNIQUE(substance_id, category)` — (성분,분류) 중복 금지(FR-005·SC-002). 성분당 1~3행.
- `category` 값 유효성: 어댑터 `AvoidanceCategory.valueOf` + 시드 정합 테스트.

### `ingredient_avoidance_substance` (재료 ↔ 성분 매핑)

| 컬럼 | 타입 | 제약 | 비고 |
|------|------|------|------|
| `id` | BIGINT AUTO_INCREMENT | PK | BaseEntity |
| `ingredient_id` | BIGINT | NOT NULL, FK→`ingredient(id)` | |
| `substance_id` | BIGINT | NOT NULL, FK→`avoidance_substance(id)` | D-MAP |
| `status`/`created_at`/`updated_at` | | | BaseEntity |

- `UNIQUE(ingredient_id, substance_id)` — (재료,성분) 중복·멱등 시드(FR-008·SC-005). 양방향 FK 무결성(D-MAP).
- 인덱스: `ingredient_id`(UNIQUE 선두 — forward 조회 FR-010).

## JPA 엔티티 (`:infra:persistence`, 패키지 `...avoidance`)

- `AvoidanceSubstanceJpaEntity` — `@Table("avoidance_substance")`, BaseEntity 상속. `code`·`koreanName`·9 번역 프로퍼티(`@Column`). 연관(분류)은 `@ManyToOne`/그래프 대신 어댑터가 별도 조회(LAZY·N+1 원천 제거). `toDomain()` 대신 어댑터가 `code`→enum 브리지.
- `AvoidanceSubstanceCategoryJpaEntity` — `@Table("avoidance_substance_category")`, `substanceId: Long`·`category: String` 스칼라.
- `IngredientAvoidanceSubstanceJpaEntity` — `@Table("ingredient_avoidance_substance")`, `ingredientId: Long`·`substanceId: Long` 스칼라.
- Spring Data 리포지토리 3종: `findByCodeIn`, `findBySubstanceIdIn`, 매핑 `findByIngredientIdIn`(+성분 join). 소프트삭제 ACTIVE 자동(BaseEntity `@SQLRestriction`).

## 도메인 Port (`:core:avoidance`, 순수)

```kotlin
interface AvoidanceSubstanceRepository {
    fun byCategory(category: AvoidanceCategory): List<AvoidanceSubstance>
    fun translatedName(substance: AvoidanceSubstance, lang: LanguageCode): String   // DB 번역 컬럼, NULL→ko
    fun findByCodes(codes: Set<String>): List<AvoidanceSubstance>
}

interface IngredientAvoidanceSubstanceRepository {
    fun findByIngredientIds(ingredientIds: Set<Long>): Map<Long, Set<AvoidanceSubstance>>
}
```

- 반환은 enum `AvoidanceSubstance`(D-READMODEL) — 어댑터가 DB `code` → `valueOf`. food 타입 미참조(ingredient 는 Long).
- `translatedName`: 어댑터가 DB 행의 해당 언어 컬럼 읽음 → NULL 이면 `korean_name`(=ko 원문). (enum `AvoidanceCatalog` 와 동일 결과 — 단 데이터 출처는 DB.)

## Adapter (`:infra:persistence`)

- `AvoidanceSubstanceRepositoryAdapter` — port 구현. `byCategory`: 멤버십 테이블에서 `category` 로 `substance_id` → 성분 `code` → enum. `translatedName`: 성분 행의 lang 컬럼 → NULL 시 `korean_name`. `findByCodes`: `code` → enum.
- `IngredientAvoidanceSubstanceRepositoryAdapter` — `findByIngredientIdIn` 결과를 `ingredientId` 로 group → 성분 `substance_id`→`code`→`valueOf` → `Map<Long, Set<AvoidanceSubstance>>`. 미매핑 재료는 키 생략/빈집합.

## 조합 (`:application:client`) — US3

- `FoodAvoidanceSubstanceResolver.resolve(ingredientIds: Set<Long>): Set<AvoidanceSubstance>` = `findByIngredientIds(ids).values.flatten().toSet()`. food 구성 + avoidance port 조합(원칙 II).

## 시드 (Flyway V5, owner=app:api)

- `app/api/src/main/resources/db/migration/V5__create_avoidance_catalog_and_mapping.sql`:
  - 3 테이블 생성.
  - `avoidance_substance` 81행(code·ko·9번역) — **enum + `AvoidanceSubstanceTranslations` 값과 일치**.
  - `avoidance_substance_category` 멤버십 — enum `categories` 와 일치.
  - `ingredient_avoidance_substance` **mock** 매핑(`SELECT id FROM ingredient WHERE korean_name=...` × 대표 성분). 확정 콘텐츠 수령 시 교체.
- 멱등: UNIQUE 제약 + 재실행 안전 INSERT.

## enum ↔ DB 정합 테스트 (D-SEED)

- 검증: DB 시드의 성분 코드 집합 == `AvoidanceSubstance.entries` 의 name 집합 / 각 성분 분류 == enum `categories` / 번역 == `AvoidanceSubstanceTranslations`. 드리프트 0(SC-001·SC-002).
- 위치·방식(SQL 파싱 vs enum→기대시드 생성): tasks 확정. H2 는 Flyway off라 시드 미적재 → SQL 소스 대조 또는 app 컨텍스트 검증.

## 요구사항 ↔ 모델 매핑

| FR/SC | 반영 |
|-------|------|
| FR-001 81종 DB | `avoidance_substance` + 시드 |
| FR-002 코드·ko·9번역 | 컬럼 + 시드 |
| FR-003 ko 폴백 | `translatedName` NULL→korean_name |
| FR-004 코드 유일 | `UNIQUE(code)` |
| FR-005 1~3분류 다대다 | `avoidance_substance_category` + UNIQUE |
| FR-006 코드/분류 조회 | port `findByCodes`·`byCategory` |
| FR-007 재료 FK 매핑 | `ingredient_avoidance_substance` FK |
| FR-008 (재료,성분) 유일 | `UNIQUE(ingredient_id, substance_id)` |
| FR-009 재료·성분 복제 안 함 | 양방향 FK |
| FR-010 재료ids→성분 | `findByIngredientIds` |
| FR-011 음식→합집합 | `FoodAvoidanceSubstanceResolver` |
| FR-012 읽기전용·시드 | Flyway, CRUD 없음 |
| FR-013 소프트삭제 | BaseEntity `@SQLRestriction` |
| SC-001~003 카탈로그 정합·폴백 | 시드 + 정합 테스트 + 어댑터 |
| SC-004~006 매핑·합집합·멱등 | UNIQUE + 어댑터/조합 테스트 |
| SC-007 안정 식별자 | code·category·ingredient.id |
