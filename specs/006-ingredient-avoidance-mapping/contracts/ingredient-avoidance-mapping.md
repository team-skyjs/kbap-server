# Contract: 회피·주의 성분 카탈로그 + 재료 매핑 (내부 API)

외부 HTTP API 는 본 기능 범위 밖(읽기 전용 reference 적재·조회). 본 계약은 **모듈 간 공개 타입 시그니처**(도메인 port + application read)를 고정한다 — 후속(#16 판정, #17 음식 상세조회)이 의존할 안정 표면. 반환 통화는 기존 enum `AvoidanceSubstance`(D-READMODEL).

## Port: 성분 카탈로그 (`:core:avoidance`)

```kotlin
package com.meogo.core.avoidance

interface AvoidanceSubstanceRepository {
    fun byCategory(category: AvoidanceCategory): List<AvoidanceSubstance>
    fun translatedName(substance: AvoidanceSubstance, lang: LanguageCode): String
    fun findByCodes(codes: Set<String>): List<AvoidanceSubstance>
}
```

**계약**:
- `byCategory`: DB 멤버십 테이블 조인 → 해당 분류 성분(복수 분류 성분 포함) enum 목록.
- `translatedName`: DB 의 lang 컬럼 값. **NULL 이면 ko 원문(`korean_name`) 폴백**(FR-003). 빈 문자열 반환 0. (미지원 언어 코드 에러는 #18 후속 — 본 port 는 `LanguageCode` enum 만 받으므로 미지원 코드는 호출 전에 걸러짐.)
- `findByCodes`: 코드 → enum. 무효 코드는 결과에서 제외 또는 변환 예외(시드 정합 테스트가 선차단).
- 소프트삭제(ACTIVE) 만 반영.

## Port: 재료↔성분 매핑 (`:core:avoidance`)

```kotlin
interface IngredientAvoidanceSubstanceRepository {
    fun findByIngredientIds(ingredientIds: Set<Long>): Map<Long, Set<AvoidanceSubstance>>
}
```

**계약**:
- 입력 재료 id 집합. 빈 입력 → 빈 맵.
- 매핑 있는 재료만 키 포함 또는 미매핑 재료를 빈 `Set` 으로 — 소비 측 `result[id] ?: emptySet()` 폴백(FR-010).
- 반환 성분은 enum(분류·코드는 enum 에서). ingredient 는 Long 으로만 참조(원칙 II).

## Read 조합 (`:application:client`) — US3

```kotlin
class FoodAvoidanceSubstanceResolver(
    private val repository: IngredientAvoidanceSubstanceRepository,
) {
    fun resolve(ingredientIds: Set<Long>): Set<AvoidanceSubstance>
}
```

- 음식 구성 재료 id → 매핑 성분 **합집합**(중복 없음, FR-011). 매핑 없는 재료만이면 빈 집합.

## 영속 계약 (DB)

- `avoidance_substance(code UNIQUE, korean_name, name_<lang> ×9, +BaseEntity)`.
- `avoidance_substance_category(substance_id FK, category, UNIQUE(substance_id, category))` — 성분당 1~3.
- `ingredient_avoidance_substance(ingredient_id FK, substance_id FK, UNIQUE)`.
- 불변: DB 성분 코드 집합 == `AvoidanceSubstance.entries`, 분류·번역 == enum (시드 정합 테스트).

## 안정성 (SC-007)

- `code`(=enum name)·`AvoidanceCategory`·`ingredient.id`·`avoidance_substance.id` 는 후속 의존 식별자 — 임의 변경 금지.
- port 시그니처는 #16/#17 컴파일 표면. 변경 시 소비 모듈 동반 갱신.
