# Quickstart: 회피·주의 성분 카탈로그 DB + 재료 매핑

후속 기능이 카탈로그·매핑을 소비하는 방법.

## 1. 성분 다국어 명칭 / 분류 조회 (US1)

```kotlin
val name = avoidanceSubstanceRepository.translatedName(AvoidanceSubstance.PEANUT, LanguageCode.EN)  // "Peanut", 없으면 ko
val allergens = avoidanceSubstanceRepository.byCategory(AvoidanceCategory.ALLERGEN)                 // List<AvoidanceSubstance>
```

## 2. 재료 id 집합으로 성분 조회 (#16 판정 입력)

```kotlin
val byIngredient: Map<Long, Set<AvoidanceSubstance>> =
    ingredientAvoidanceSubstanceRepository.findByIngredientIds(setOf(11L, 12L, 99L))
val substances = byIngredient[11L] ?: emptySet()                 // 미매핑 → 빈 집합
val categories = substances.flatMap { it.categories }.toSet()    // 분류로 등급 산출(#16)
```

## 3. 음식 단위 성분 합집합 (#17 음식 상세조회)

```kotlin
val ids = food.ingredientsByInclusion().mapNotNull { it.ingredient.id }.toSet()
val foodSubstances: Set<AvoidanceSubstance> = foodAvoidanceSubstanceResolver.resolve(ids)
```

## 4. 빌드·테스트

```bash
./gradlew :infra:persistence:test     # 성분·매핑 어댑터(H2)
./gradlew :application:client:test    # 음식→성분 합집합
./gradlew :app:api:test               # enum↔DB 시드 정합 + ArchUnit
./gradlew build
```

## 5. 시드 교체 (확정 콘텐츠 수령 시)

- `V5__create_avoidance_catalog_and_mapping.sql` 의 성분/번역/분류/매핑 INSERT 를 확정 값으로 교체.
- enum(`AvoidanceSubstance`·`AvoidanceSubstanceTranslations`)도 함께 갱신 — **enum↔DB 정합 테스트가 일치를 강제**.

## 경계 (하지 말 것)

- enum 을 **제거하지 말 것**(본 기능은 공존 — 시드 원천·타입 통화). 제거는 후속 판단.
- 미지원 언어 코드 에러 처리를 여기서 하지 말 것 — **#18 후속**(공유 `LanguageCode`).
- food 도메인에 성분을 매달지 말 것 — 매핑은 avoidance 소유, 조합은 application.
- 매핑을 Mongo 로 옮기지 말 것 — MySQL.
