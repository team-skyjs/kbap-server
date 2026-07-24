# Phase 1 Contracts: 도메인 port (`:core:avoidance`)

이 기능의 "인터페이스 계약"은 외부 HTTP API 가 아니라 **도메인 port**다(리팩터는 내부 계층 계약을 바꾼다). port 구현은 `:infra:persistence` 어댑터. 아래는 before/after 시그니처.

## AvoidanceSubstanceRepository

### Before

```
interface AvoidanceSubstanceRepository {
    fun byCategory(category: AvoidanceCategory): List<AvoidanceSubstance>   // enum
    fun translatedName(substance: AvoidanceSubstance, lang: LanguageCode): String
    fun findByCodes(codes: Set<String>): List<AvoidanceSubstance>          // enum
}
```

### After

```
interface AvoidanceSubstanceRepository {
    fun byCategory(category: AvoidanceCategory): List<AvoidanceSubstance>          // 어그리게이트
    fun findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance>  // 어그리게이트
}
```

- **`translatedName` 제거** — 호출자는 `substance.displayName(lang)` 사용(데이터 누수 원천 소멸, Finding ①).
- 반환 `AvoidanceSubstance` 는 이제 **어그리게이트**(id·code·koreanName·translations·categories 보유).
- `findByCodes` 파라미터 `Set<String>` → `Set<AvoidanceSubstanceCode>`(타입 안전). 현재 프로덕션 호출자 없음(향후 #16) — 자유롭게 변경 가능.

**계약 규칙**
- 없는 코드/카테고리 → 매칭분만 반환(빈 리스트 허용).
- 반환 각 항목은 `displayName(lang)`·`belongsTo(cat)` 를 스스로 답한다.
- 조회 쿼리 수는 결과 성분 수와 무관(상수 단계).

## IngredientAvoidanceSubstanceRepository

### Before

```
interface IngredientAvoidanceSubstanceRepository {
    fun findByIngredientIds(ingredientIds: Set<Long>): Map<Long, Set<AvoidanceSubstance>>   // enum
}
```

### After

```
interface IngredientAvoidanceSubstanceRepository {
    fun findByIngredientIds(ingredientIds: Set<Long>): Map<Long, Set<AvoidanceSubstance>>   // 어그리게이트
}
```

- 시그니처 형태 동일, `AvoidanceSubstance` 의미만 enum → 어그리게이트.
- 빈 입력·무매핑 → `emptyMap()`. 값이 빈 성분 집합인 키는 제외.
- 소비자 `FoodAvoidanceSubstanceResolver.resolve(ingredientIds): Set<AvoidanceSubstance>` 는 시그니처 유지(어그리게이트 집합 반환).

## 어그리게이트 공개 API (계약)

```
class AvoidanceSubstance {   // @AggregateRoot
    val id: Long
    val code: AvoidanceSubstanceCode
    val koreanName: String
    val translations: Map<LanguageCode, String>
    val categories: Set<AvoidanceCategory>

    fun displayName(lang: LanguageCode): String   // KO→koreanName, else translations[lang] ?: koreanName
    fun belongsTo(category: AvoidanceCategory): Boolean

    companion object { fun reconstitute(id, code, koreanName, translations, categories): AvoidanceSubstance }
}
```

- `displayName` 계약: KO 는 항상 `koreanName`(=DB `korean_name`, **enum 하드코딩 아님**). 번역 없음/blank → koreanName 폴백.
