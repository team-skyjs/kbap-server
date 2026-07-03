# Phase 1 Data Model: 음식별 기피 성분 직접 매핑

## 도메인 모델 (`:core:food`, ORM-free·Spring-free)

### Food (Aggregate Root, 변경)

| 필드 | 타입 | 규칙 |
|------|------|------|
| id | Long? | 영속 후 부여 |
| koreanName | String | not blank |
| imageRef | String? | nullable |
| briefDescription | String | not blank, ≤255 |
| detailedDescription | String | not blank, ≤1024 |
| **avoidanceSubstances** | List\<FoodAvoidanceSubstance\> | **신규** — 재료(`ingredients`) 대체 |

- 제거: `ingredients: List<FoodIngredient>`, `ingredientsByInclusion()`.
- 추가: `avoidanceSubstancesByProbability(): List<FoodAvoidanceSubstance>` — `inclusionProbability` 내림차순 정렬.
- 팩토리 `create(...)`·`reconstitute(...)` 시그니처의 `ingredients` 파라미터를 `avoidanceSubstances` 로 교체. 불변(모든 `val`) 유지.

### FoodAvoidanceSubstance (신규 값 객체)

```
data class FoodAvoidanceSubstance(
    val substanceCode: String,       // AvoidanceSubstanceCode 이름 문자열(예 "EGG") — enum 미import(헌법 II)
    val inclusionProbability: Int,   // 1..100
)
```

- 불변식: `require(substanceCode.isNotBlank())`, `require(inclusionProbability in 1..100)`.
- 헌법 II 준수: `:core:avoidance` 의 `AvoidanceSubstanceCode` 를 import 하지 않고 코드 문자열로만 참조.

### 삭제 도메인 타입

- `FoodIngredient`(`:core:food`) — 삭제.
- `Ingredient`(`:core:food`, Aggregate) — 삭제.
- `IngredientAvoidanceSubstanceRepository`(`:core:avoidance`, port) — 삭제.

### FoodRepository (port, 변경)

```
interface FoodRepository {
    fun findByKoreanName(name: String): Food?                                  // 유지(원천만 교체)
    fun findFoodNameTranslation(foodId: Long, lang: LanguageCode): String?      // 유지
    fun findFoodDescriptionTranslations(foodId: Long, lang: LanguageCode): Map<FoodDescriptionKind, String>  // 유지
    // 제거: fun findIngredientNameTranslations(...)
}
```

## 영속 모델 (`:infra:persistence`)

### food_avoidance_substance ↔ FoodAvoidanceSubstanceJpaEntity (신규)

| 컬럼 | 타입 | 제약 |
|------|------|------|
| id | BIGINT | PK, IDENTITY (BaseEntity) |
| food_id | BIGINT | NOT NULL, FK→food(id) |
| substance_code | VARCHAR(40) | NOT NULL, FK→avoidance_substance(code) |
| inclusion_percent | INT | NOT NULL, CHECK 1..100 |
| status | VARCHAR | ACTIVE/DELETED (BaseEntity, `@SQLRestriction status='ACTIVE'`) |
| created_at / updated_at | TIMESTAMP | BaseEntity |

- `@Table(name="food_avoidance_substance", uniqueConstraints=[UniqueConstraint(columnNames=["food_id","substance_code"])])` — H2 테스트도 조합 유일 강제.
- BaseEntity 상속(id·status·created/updated). JPA 애너테이션 use-site 타깃 없이(field-only).
- `toDomain(): FoodAvoidanceSubstance` = `(substance_code, inclusion_percent)`.

### FoodJpaEntity (변경)

- `foodIngredients: MutableSet<FoodIngredientJpaEntity>` (OneToMany, `@JoinColumn(food_id)`, cascade ALL, orphanRemoval) → **`foodAvoidanceSubstances: MutableSet<FoodAvoidanceSubstanceJpaEntity>`** 로 교체(동일 매핑 전략, LAZY).
- `toDomain()` 이 `avoidanceSubstances = foodAvoidanceSubstances.map { it.toDomain() }` 로 조립.

### FoodJpaRepository (변경)

```
@Query("""
  select distinct f from FoodJpaEntity f
  left join fetch f.foodAvoidanceSubstances
  where f.koreanName = :koreanName
""")
fun findByKoreanNameWithAvoidanceSubstances(@Param("koreanName") koreanName: String): FoodJpaEntity?
```

- junction 이 `substance_code` 를 직접 보유 → avoidance_substance 추가 조인 불필요(N+1 없음, SC-005).

### FoodRepositoryAdapter (변경)

- 생성자에서 `ingredientNameTranslationJpaRepository` 의존 제거.
- `findByKoreanName` → `findByKoreanNameWithAvoidanceSubstances(...).toDomain()`.
- `findIngredientNameTranslations(...)` 오버라이드 제거.

### 삭제 영속 타입

- `FoodIngredientJpaEntity`, `IngredientJpaEntity`, `IngredientJpaRepository`.
- `IngredientNameTranslationJpaEntity`, `IngredientNameTranslationJpaRepository`.
- `IngredientAvoidanceSubstanceJpaEntity`, `IngredientAvoidanceSubstanceJpaRepository`, `IngredientAvoidanceSubstanceRepositoryAdapter`.

## 유지되는 모델(불변)

- `avoidance_substance`(81종 카탈로그: code unique·korean_name·translations JSON) + `AvoidanceSubstanceJpaEntity` + `AvoidanceSubstanceRepository.findByCodes` + `AvoidanceSubstanceCode` enum + `AvoidanceSubstance` 도메인.
- `food_name_translation`, `food_description_translation` + 각 엔티티/리포지토리.
- `food` 기준 테이블(재료 연결만 빠짐).

## 마이그레이션 (V7__replace_recipe_with_food_avoidance_substance.sql)

순서:

1. `CREATE TABLE food_avoidance_substance (...)` — 위 컬럼·제약(unique(food_id,substance_code), FK 2개, CHECK 1..100).
2. **시드 이행**(DROP 전 실행):
   ```sql
   INSERT INTO food_avoidance_substance (food_id, substance_code, inclusion_percent, status, created_at, updated_at)
   SELECT DISTINCT fi.food_id, s.code, 100, 'ACTIVE', NOW(), NOW()
   FROM food_ingredient fi
   JOIN ingredient_avoidance_substance ias ON ias.ingredient_id = fi.ingredient_id
   JOIN avoidance_substance s ON s.id = ias.substance_id;
   ```
   (음식별 distinct 포함 성분, 확률 100 — D4.)
3. `DROP TABLE` (FK 역순): `food_ingredient`, `ingredient_avoidance_substance`, `ingredient_name_translation`, `ingredient`.

- 스키마 owner=`:app:api`(batch flyway off). H2 테스트는 엔티티 create-drop 이 스키마 제공(Flyway off) — CHECK/FK 는 Flyway(MySQL)만, unique 는 엔티티 `uniqueConstraints` 로 H2 도 강제.

## 상태 전이

- 해당 없음(단순 값 저장). 소프트삭제는 BaseEntity `status` 로 표준 처리.
