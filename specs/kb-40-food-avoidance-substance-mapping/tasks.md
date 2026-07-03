---
description: "Task list — 음식별 81종 기피 성분 포함 여부·포함 확률 저장 (레시피/재료 제거)"
---

# Tasks: 음식별 81종 기피 성분 포함 여부·포함 확률 저장 (레시피/재료 모델 제거)

**Input**: Design documents from `specs/kb-40-food-avoidance-substance-mapping/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/food-detail-api.md, quickstart.md

**Tests**: Test-First 는 **NON-NEGOTIABLE**(헌법 원칙 I). 각 층 구현 전에 실패 테스트(Kotest `BehaviorSpec`, given/when/then 한국어)를 먼저 작성·Red 확인한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 서로 다른 파일·의존 없음 → 병렬 가능
- **[Story]**: US1(P1 저장·직접조회) · US2(P2 재료 제거) · US3(P3 상세 응답)

> **실행 순서 주의(리팩터 특성)**: `Food` 도메인 형태 변경(Foundational)이 영속·유스케이스·web 컴파일을 함께 깨므로, 단계는 **컴파일 안전 순서**로 배치한다: Setup → Foundational → **US1(영속·마이그레이션) → US3(app·web) → US2(삭제 정리)** → Polish. US2(재료 삭제)는 우선순위상 P2 지만, 삭제 대상 타입이 app 계층(US3)에서 참조를 끊은 뒤라야 안전하므로 실행상 US3 뒤에 둔다. 각 단계는 독립 검증 가능하다.

---

## Phase 1: Setup

- [X] T001 다음 Flyway 버전이 `V7` 로 비어 있는지 확인한다(`app/api/src/main/resources/db/migration/` 최신 = `V6`). 병행 브랜치 `009-avoidance-schema-refactor` 가 먼저 머지돼 `V7` 을 선점하면 본 마이그레이션을 다음 빈 번호로 재넘버링한다(내용 동일).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리가 컴파일·의존하는 `:core:food` 도메인 형태를 확정한다. 완료 시 `:core:food` 단위 테스트가 그린이 되고, 하위 모듈은 각 스토리에서 재컴파일된다.

- [X] T002 [P] `core/food/src/test/kotlin/com/meogo/core/food/FoodAvoidanceSubstanceTest.kt` 에 실패 테스트 작성(Red): `inclusionProbability` 1·100 경계 통과, 0·101·음수 → `IllegalArgumentException`, `substanceCode` blank → 예외.
- [X] T003 [P] `core/food/src/test/kotlin/com/meogo/core/food/FoodTest.kt` 수정(Red): `Food` 가 `avoidanceSubstances` 를 보유하고 `avoidanceSubstancesByProbability()` 가 확률 내림차순 정렬, 빈 목록 음식 유효를 검증(구 `ingredients`/`ingredientsByInclusion` 기대 제거).
- [X] T004 `core/food/src/main/kotlin/com/meogo/core/food/FoodAvoidanceSubstance.kt` 신규 구현: `data class FoodAvoidanceSubstance(substanceCode: String, inclusionProbability: Int)` + 불변식(1..100, not blank). avoidance enum 미import(헌법 II). (T002 Green)
- [X] T005 `core/food/src/main/kotlin/com/meogo/core/food/Food.kt` 수정: 생성자·`create`·`reconstitute` 의 `ingredients` → `avoidanceSubstances: List<FoodAvoidanceSubstance>`, `ingredientsByInclusion()` → `avoidanceSubstancesByProbability()`(확률 내림차순). 불변 유지. (T003 Green, T004 의존)
- [X] T006 [P] `core/food/src/main/kotlin/com/meogo/core/food/FoodRepository.kt` 수정: `findIngredientNameTranslations(...)` 시그니처 제거(나머지 3개 유지).

**Checkpoint**: `./gradlew :core:food:test` 그린. `:infra:persistence`·`:application:client`·`:app:api` 는 아직 미컴파일(각 스토리에서 해결).

---

## Phase 3: User Story 1 - 음식별 기피 성분 포함 여부·포함 확률 저장·직접 조회 (Priority: P1)

**Goal**: 음식이 81종 기피 성분(부분집합)을 포함 확률(1~100)과 함께 직접 보유하고, 재료 경유 없이 단일 조회로 복원된다.

**Independent Test**: H2 통합에서 음식에 (성분코드, 확률) 집합을 저장 후 `findByKoreanName` 이 fetch join 1회로 `avoidanceSubstances` 를 복원하고, (food_id, substance_code) 조합 유일이 강제됨을 확인.

### Tests (Test-First — 먼저 작성·Red 확인) ⚠️

- [X] T007 [P] [US1] `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/food/FoodRepositoryAdapterTest.kt` 수정(Red): 음식+`food_avoidance_substance` 저장/복원, fetch join 으로 포함 성분 개수 무관 상수 쿼리(N+1 없음), (food_id, substance_code) 유일 위반 거부, 도메인 `avoidanceSubstances`(코드·확률) 복원 검증. (구 재료 기대 제거)

### Implementation

- [X] T008 [US1] `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/FoodAvoidanceSubstanceJpaEntity.kt` 신규: `@Table("food_avoidance_substance", uniqueConstraints=[(food_id, substance_code)])`, `substance_code VARCHAR(40)`·`inclusion_percent INT`, BaseEntity 상속, `toDomain(): FoodAvoidanceSubstance`.
- [X] T009 [US1] `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/FoodJpaEntity.kt` 수정: `foodIngredients` → `foodAvoidanceSubstances: MutableSet<FoodAvoidanceSubstanceJpaEntity>`(OneToMany, `@JoinColumn(food_id)`, cascade ALL, orphanRemoval, LAZY), `toDomain()` 이 `avoidanceSubstances` 조립. (T008 의존)
- [X] T010 [US1] `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/FoodJpaRepository.kt` 수정: `findByKoreanNameWithAvoidanceSubstances`(fetch join `f.foodAvoidanceSubstances`). (T009 의존)
- [X] T011 [US1] `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/FoodRepositoryAdapter.kt` 수정: `ingredientNameTranslationJpaRepository` 의존·`findIngredientNameTranslations` 오버라이드 제거, `findByKoreanName` → 신규 fetch join 쿼리 사용. (T010 의존, T007 Green)
- [X] T012 [US1] `app/api/src/main/resources/db/migration/V7__replace_recipe_with_food_avoidance_substance.sql` 신규: `CREATE TABLE food_avoidance_substance`(food_id FK→food, substance_code FK→avoidance_substance(code), UNIQUE(food_id, substance_code), CHECK inclusion_percent BETWEEN 1 AND 100, BaseEntity 컬럼) + 시드 이행 `INSERT ... SELECT DISTINCT fi.food_id, s.code, 100 FROM food_ingredient fi JOIN ingredient_avoidance_substance ias ON ias.ingredient_id=fi.ingredient_id JOIN avoidance_substance s ON s.id=ias.substance_id`. (DROP 문은 US2 T023 에서 이 파일에 이어 붙임)

**Checkpoint**: `./gradlew :core:food:test :infra:persistence:test` 그린. 음식-기피성분 저장/조회가 영속 계층에서 독립 검증됨.

---

## Phase 4: User Story 3 - 음식 상세 응답(계약 동결, 원천 교체) (Priority: P3)

**Goal**: `GET /api/v1/foods/detail` 응답 JSON 구조를 동결한 채(`ingredients[].{name,iconRef,inclusionPercent,riskStatus}`), 데이터 원천을 재료 → 포함 기피 성분으로 바꾼다. `inclusionPercent` 에 포함 확률(1~100), `iconRef` null.

**Independent Test**: MockMvc 로 응답 JSON 키·타입이 변경 전과 동일하고, `payload.ingredients` 가 포함 기피 성분(확률 내림차순, ko 폴백 표시명)으로 채워지며 미지원 언어코드는 400 임을 확인.

### Tests (Test-First — 먼저 작성·Red 확인) ⚠️

- [X] T013 [US3] `application/client/src/test/kotlin/com/meogo/application/client/food/usecase/` 테스트 헬퍼 정비: `FakeFoodRepository` 를 새 `FoodRepository` 포트(재료 번역 메서드 없음)에 맞춰 수정하고, Food 스텁이 `avoidanceSubstances` 를 갖도록 조정(컴파일 회복 선행).
- [X] T014 [P] [US3] `application/client/.../food/usecase/GetFoodDetailUseCaseTest.kt` 수정(Red): Food 의 `avoidanceSubstances` → `AvoidanceSubstanceRepository.findByCodes` 로 표시명 해석, 내부 뷰(name/iconRef=null/inclusionProbability/riskStatus) 조립, ko 폴백, 확률 내림차순, 포함 0개 → 빈 목록 검증. (fake `AvoidanceSubstanceRepository` 사용)
- [X] T015 [P] [US3] `app/api/src/test/kotlin/com/meogo/app/api/food/FoodDetailControllerTest.kt` + `FoodTestSeed.kt` 수정(Red): 응답 JSON 구조 동결 확인(`payload.ingredients[].{name,iconRef,inclusionPercent,riskStatus}`), `inclusionPercent`=확률(1~100), `iconRef` null, 확률 내림차순. 시드를 재료 → 음식-기피성분으로 교체.
- [X] T016 [P] [US3] `app/api/src/test/kotlin/com/meogo/app/api/food/{FoodDetailLangTest,FoodDetailDescriptionTest,FoodDetailErrorTest,FoodDetailLanguageErrorTest}.kt` 수정(Red): 언어 폴백·설명·NOT_FOUND·미지원 언어 400 이 새 원천에서도 유지되는지(계약 회귀 없음) 검증.

### Implementation

- [X] T017 [P] [US3] `application/client/src/main/kotlin/com/meogo/application/client/food/dto/GetFoodDetailResult.kt` 수정: 내부 `ingredients: List<IngredientView>` → `avoidanceSubstances: List<AvoidanceSubstanceView>`(필드 `name`, `iconRef`, `inclusionProbability`, `riskStatus`).
- [X] T018 [P] [US3] `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/MockIngredientRiskMarker.kt` → `MockAvoidanceRiskMarker.kt` 로 개명·수정: 기피 성분 코드 목록 기준 mock 위험도 반환(구 `Ingredient` 참조 제거). 대응 테스트 `MockIngredientRiskMarkerTest.kt` → `MockAvoidanceRiskMarkerTest.kt` 개명·수정.
- [X] T019 [US3] `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/GetFoodDetailUseCase.kt` 재작성: `food.avoidanceSubstancesByProbability()` → 코드셋 → `AvoidanceSubstanceRepository.findByCodes` 표시명 해석(ko 폴백, 헌법 V) → `AvoidanceSubstanceView` 조립(iconRef=null, inclusionProbability=확률), 정렬 유지. 구 재료명 번역 조회 제거. (T017, T018 의존; T013·T014 Green)
- [X] T020 [US3] `app/api/src/main/kotlin/com/meogo/app/api/food/FoodDetailResponse.kt` 수정: 외부 필드(`ingredients[].{name,iconRef,inclusionPercent,riskStatus}`) **동결 유지**, `from()` 이 내부 `avoidanceSubstances`·`inclusionProbability` → 동결 키 `ingredients`·`inclusionPercent` 로 매핑, `@Schema` description/example 문구만 새 의미로 갱신. (T017 의존; T015·T016 Green)
- [X] T021 [US3] `app/api/src/main/kotlin/com/meogo/app/api/food/{FoodDetailController,FoodDetailApi}.kt` 무변경 확인(계약 동결) 후 `./gradlew :application:client:test :app:api:test` 그린 확인.

**Checkpoint**: 상세 조회 API 가 동결 계약으로 포함 기피 성분을 응답. `:application:client`·`:app:api` 컴파일·테스트 그린.

---

## Phase 5: User Story 2 - 레시피/재료 모델 제거 (Priority: P2)

**Goal**: 재료 관련 도메인·엔티티·포트·시드·테이블을 전부 제거해 이중 표현·죽은 데이터를 없앤다.

**Independent Test**: 전 모듈 컴파일·ArchUnit 그린 상태에서, 재료 목록·재료-성분 매핑이 어떤 조회·응답 경로에서도 산출되지 않음을 확인(레시피 모델 사용처 0건).

> **실행 전제**: US3(Phase 4) 완료 후 진행한다 — 삭제 대상(`Ingredient`·`IngredientAvoidanceSubstanceRepository` 등)이 app 계층에서 참조 해제된 뒤라야 컴파일이 유지된다.

- [X] T022 [P] [US2] `app/api/src/main/resources/db/migration/V7__replace_recipe_with_food_avoidance_substance.sql` 에 DROP 문 이어 붙임(FK 역순): `food_ingredient` → `ingredient_avoidance_substance` → `ingredient_name_translation` → `ingredient`. (T012 의 시드 뒤)
- [X] T023 [P] [US2] 도메인 삭제: `core/food/src/main/kotlin/com/meogo/core/food/FoodIngredient.kt`, `Ingredient.kt`.
- [X] T024 [P] [US2] 포트 삭제: `core/avoidance/src/main/kotlin/com/meogo/core/avoidance/IngredientAvoidanceSubstanceRepository.kt`.
- [X] T025 [US2] 영속(food) 삭제: `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/{FoodIngredientJpaEntity,IngredientJpaEntity,IngredientJpaRepository,IngredientNameTranslationJpaEntity,IngredientNameTranslationJpaRepository}.kt`.
- [X] T026 [US2] 영속(avoidance) 삭제: `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/{IngredientAvoidanceSubstanceJpaEntity,IngredientAvoidanceSubstanceJpaRepository,IngredientAvoidanceSubstanceRepositoryAdapter}.kt`.
- [X] T027 [P] [US2] application 삭제: `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/FoodAvoidanceSubstanceResolver.kt` 및 테스트 `.../FoodAvoidanceSubstanceResolverTest.kt`(재료 경유 resolver — 미사용).
- [X] T028 [P] [US2] 영속 테스트 삭제: `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/avoidance/IngredientAvoidanceSubstanceRepositoryAdapterTest.kt`.
- [X] T029 [US2] `./gradlew :app:api:test --tests "com.meogo.app.api.architecture.ModuleBoundaryTest"` + `./gradlew build` 로 잔여 참조·경계 위반 없음 확인(재료 타입 미참조).

**Checkpoint**: 재료/레시피 자산 완전 제거. 전체 빌드 그린.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T030 [P] `docs/architecture/food-detail-database-design.md` 갱신: `food_ingredient`·`ingredient*` 설명 → `food_avoidance_substance`(코드·확률) 모델·조회 흐름으로 개정.
- [X] T031 [P] `docs/architecture/api-request-flows.md` 갱신: 음식 상세 시퀀스/데이터 표를 재료 조회 → 포함 기피 성분(+avoidance 카탈로그 표시명 해석)으로 개정.
- [X] T032 [P] (선택) `docs/adr/0009-food-avoidance-direct-mapping.md` 신규 ADR: 레시피/재료 제거 + 음식↔기피성분 직접 매핑(코드 참조·확률 시드 100) 결정 기록.
- [X] T033 `specs/kb-40-food-avoidance-substance-mapping/quickstart.md` 시나리오 실행: `./gradlew build` + 로컬 프로필 flyway V7 적용(시드 이행·재료 테이블 DROP)·`curl` 상세 조회로 동결 계약·확률 응답 확인.

---

## Dependencies & Execution Order

### 권장 실행 순서 (컴파일 안전)

Setup(T001) → Foundational(T002–T006) → **US1**(T007–T012) → **US3**(T013–T021) → **US2**(T022–T029) → Polish(T030–T033).

- **Foundational** 은 모든 스토리를 블록(도메인 형태 확정). 완료 전까진 하위 모듈 미컴파일.
- **US1** 은 Foundational 후 영속·마이그레이션을 그린으로. (`:core:food`+`:infra:persistence` 독립 검증)
- **US3** 는 US1(도메인·영속) 위에서 app·web 을 그린으로 — 이 시점에 전체 앱 컴파일 회복.
- **US2**(삭제)는 US3 뒤 — app 계층이 재료 타입 참조를 끊은 뒤라야 안전. (T022 DROP 은 T012 의 시드 뒤에 붙음)

### Within a story

- 테스트(Red) → 모델/엔티티 → 어댑터/유스케이스 → 컨트롤러/매핑 → 그린 확인.
- Test-First 준수: 각 테스트가 구현 전 실패함을 확인(헌법 I).

### Parallel opportunities

- Foundational: T002, T003 병렬. T006 은 T004/T005 와 다른 파일이라 병렬 가능.
- US1: T007(테스트)는 T008–T011 앞. T008→T009→T010→T011 은 순차(동일 조립 체인).
- US3: T014, T015, T016(서로 다른 테스트 파일) 병렬. T017, T018 병렬(다른 파일).
- US2: T023, T024, T027, T028(서로 다른 파일 삭제) 병렬. T025, T026 은 각 모듈 내 순차 정리 후 T029 검증.
- Polish: T030, T031, T032 병렬.

---

## Implementation Strategy

### MVP

- Setup + Foundational + **US1** = 음식-기피성분 저장/직접조회가 영속 계층에서 성립(데이터 기반 확보). 
- **US3** 까지 하면 사용자 대상 상세 API 가 동결 계약으로 동작(데모 가능한 최소 완결).
- **US2** 는 정리(부채 제거) — 기능 가치엔 필수 아니나 이번 범위에서 함께 완결.

### Incremental delivery

1. Foundational → 도메인 형태 확정
2. US1 → 영속·마이그레이션(생성·시드) 독립 검증
3. US3 → 상세 API 동결 계약으로 원천 교체(데모)
4. US2 → 재료 자산 제거(DROP 포함) → 전체 그린
5. Polish → 문서·ADR·quickstart 검증

## Notes

- Kotlin 소스 주석 금지(규약) — self-documenting. 설명은 커밋/docs/ADR/스펙에.
- 모든 테스트는 Kotest `BehaviorSpec`(given/when/then 한국어).
- 작업/논리 단위마다 커밋.
- `V7` 번호 충돌(병행 브랜치 009) 발견 시 T001 에 따라 재넘버링.
