# Phase 1 Data Model: 음식 상세 조회 foodId 정합

**DB 스키마 변경 없음.** Flyway 마이그레이션 신규 없음. 조회 진입점(식별자)만 바뀐다.

## Entities (기존 재사용)

### Food (도메인, `core/food`)
- `id: Long?` — 안정적 식별자. 목록/검색이 각 항목에 내려주는 값과 동일(FoodJpaEntity PK).
- `content: FoodContent` — 한국어 이름 + 언어별 이름/설명 번역.
- `imageRef: String?`, `spiciness: FoodSpiciness`, `avoidanceSubstances: List<FoodAvoidanceSubstance>`.
- 상세 산출 메서드(`displayName`·`description`·`avoidanceSubstancesByProbability`·`overallRisk`) 그대로 사용.

### FoodJpaEntity (영속, `infra/persistence`)
- `BaseEntity` 상속 → `id`(IDENTITY PK)·`status`(ACTIVE/DELETED, `@SQLRestriction` 상시 ACTIVE 필터).
- 소프트삭제된 음식은 모든 조회에서 자동 제외 → foodId 조회 시 null.

## Port 변경 (`FoodRepository`, `core/food`)

```
interface FoodRepository {
    fun findById(id: Long): Food?        // 신규 — 활성 음식 단건(성분 포함), 없으면 null
    fun findByKoreanName(name: String): Food?   // 상세 usecase 에서 호출 제거(잔여 참조 없으면 삭제)
    fun findMenuPage(cursor: Long?, size: Int): List<Food>
}
```

- `findById` 계약: 활성(soft-delete 제외) 음식을 성분 목록까지 초기화해 반환. 미존재/삭제 시 null.

## Application DTO 변경 (`GetFoodDetailInput`)

```
data class GetFoodDetailInput(
    val foodId: Long,        // was: menuName: String
    val lang: String? = null,
)
```

## 상태 전이

없음 — 읽기 전용 조회.
