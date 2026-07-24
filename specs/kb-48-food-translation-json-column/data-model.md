# Phase 1 Data Model: 음식 번역결과 JSON 칼럼 통합 (KB-48)

저장 형태 교체 리팩터링이므로 "현행 → 목표" 델타로 기술한다. 참조 선례는 `avoidance_substance.translations`(#25).

## 1. 도메인 (`:core:food`, ORM-free)

### FoodContent (변경)

현행: `koreanName`·`description` 만 보유. 번역은 도메인 밖(포트 조회).

목표: 번역 맵과 폴백 해석을 콘텐츠에 응집한다.

```kotlin
data class FoodContent(
    val koreanName: String,
    val description: String,
    val nameTranslations: Map<LanguageCode, String> = emptyMap(),
    val descriptionTranslations: Map<LanguageCode, String> = emptyMap(),
) {
    // 기존 검증(koreanName·description blank/255) 유지
    fun name(lang: LanguageCode): String =
        if (lang == LanguageCode.KO) koreanName else nameTranslations[lang] ?: koreanName

    fun description(lang: LanguageCode): String =
        if (lang == LanguageCode.KO) description else descriptionTranslations[lang] ?: description
}
```

- 규칙: `KO` 또는 요청 언어 번역 부재 → 원문 폴백(= `AvoidanceSubstance.displayName` 규칙). `ko` 키는 맵에 없다.
- 불변: 맵은 `val`. 번역 값 길이 상한(255)은 저장/이행 전제(도메인 강제는 선택 — 최소 변경 위해 원문 검증만 유지).
- `Food` 자체는 변경 없음(생성/복원 시 `FoodContent` 가 맵을 품는다).

### FoodRepository (변경 — 포트 축소)

```kotlin
interface FoodRepository {
    fun findByKoreanName(name: String): Food?
    // 삭제: findFoodNameTranslation(foodId, lang)
    // 삭제: findFoodDescriptionTranslation(foodId, lang)
}
```

번역이 `findByKoreanName` 이 돌려주는 `Food.content` 에 이미 포함되므로 언어별 조회 포트가 불필요해진다.

## 2. 영속 (`:infra:persistence`, JPA)

### FoodJpaEntity (변경)

```kotlin
@Entity @Table(name = "food")
class FoodJpaEntity(
    @Column(name = "korean_name", nullable = false, length = 255) var koreanName: String = "",
    @Column(name = "image_ref", length = 500) var imageRef: String? = null,
    @Column(name = "description", nullable = false, length = 255) var description: String = "",
    @Column(name = "spiciness", nullable = false) var spiciness: Int = 0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "name_translations", nullable = false)
    var nameTranslations: Map<String, String> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "description_translations", nullable = false)
    var descriptionTranslations: Map<String, String> = emptyMap(),

    @OneToMany(...) var foodAvoidanceSubstances: MutableSet<FoodAvoidanceSubstanceJpaEntity> = mutableSetOf(),
) : BaseEntity() {
    fun toDomain(): Food = Food.reconstitute(
        id = id,
        content = FoodContent(
            koreanName = koreanName,
            description = description,
            nameTranslations = resolve(nameTranslations),
            descriptionTranslations = resolve(descriptionTranslations),
        ),
        imageRef = imageRef,
        spiciness = FoodSpiciness(spiciness),
        avoidanceSubstances = foodAvoidanceSubstances.map { it.toDomain() },
    )

    private fun resolve(raw: Map<String, String>): Map<LanguageCode, String> =
        raw.mapNotNull { (k, v) -> LanguageCode.entries.firstOrNull { it.code == k }?.let { it to v } }.toMap()
}
```

- `resolve(...)` 는 `AvoidanceSubstanceJpaEntity.resolveTranslations` 와 동일 로직(미지의 키 무시).
- 번역이 `food` 행 컬럼이라 `findByKoreanNameWithAvoidanceSubstances`(fetch join)만으로 함께 로드 — 추가 쿼리 없음.

### 삭제 대상

- `FoodNameTranslationJpaEntity`, `FoodNameTranslationJpaRepository`
- `FoodDescriptionTranslationJpaEntity`, `FoodDescriptionTranslationJpaRepository`

### FoodRepositoryAdapter (변경)

- 생성자에서 번역 리포지토리 2종 제거, `findByKoreanName` 만 남김. `findFoodNameTranslation`/`findFoodDescriptionTranslation` 오버라이드 삭제.

## 3. 스키마 (Flyway `V10__jsonify_food_translations.sql`)

### 목표 `food` 테이블(관련 컬럼)

| 컬럼 | 타입 | 비고 |
|------|------|------|
| `korean_name` | VARCHAR(255) NOT NULL | ko 원문(변경 없음) |
| `description` | VARCHAR(255) NOT NULL | ko 원문(변경 없음) |
| `name_translations` | JSON NOT NULL | `{lang_code: name}`, ko 제외, 없으면 `{}` |
| `description_translations` | JSON NOT NULL | `{lang_code: content}`, ko 제외, 없으면 `{}` |

### 이행 절차(요지)

```sql
ALTER TABLE food
    ADD COLUMN name_translations        JSON NULL,
    ADD COLUMN description_translations JSON NULL;

UPDATE food SET name_translations = JSON_OBJECT(), description_translations = JSON_OBJECT();

UPDATE food f
JOIN (SELECT food_id, JSON_OBJECTAGG(lang_code, name) j
      FROM food_name_translation WHERE status = 'ACTIVE' AND name <> '' GROUP BY food_id) t
  ON f.id = t.food_id
SET f.name_translations = t.j;

UPDATE food f
JOIN (SELECT food_id, JSON_OBJECTAGG(lang_code, content) j
      FROM food_description_translation WHERE status = 'ACTIVE' AND content <> '' GROUP BY food_id) t
  ON f.id = t.food_id
SET f.description_translations = t.j;

ALTER TABLE food
    MODIFY COLUMN name_translations        JSON NOT NULL,
    MODIFY COLUMN description_translations JSON NOT NULL;

DROP TABLE food_name_translation;
DROP TABLE food_description_translation;
```

- 소프트삭제 정합: `WHERE status='ACTIVE'`(앱의 `@SQLRestriction` 과 동일 관점).
- 번역 0건 음식 → 빈 객체 `{}` 유지(폴백 대상). DROP 안전(두 테이블은 inbound FK 없는 자식).

## 데이터 규칙 요약

- JSON 키 = `LanguageCode.code`(9개 대상 언어). `ko` 키 없음(FR-002).
- 값 없는 언어 = 키 부재(빈 문자열/null 저장 안 함, FR-003).
- 폴백: 요청 언어 값 or ko 원문(FR-004). 미지원 코드 → 400(FR-008, 헌법 V).
