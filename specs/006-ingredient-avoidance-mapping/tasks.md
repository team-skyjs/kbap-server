---
description: "Task list for 회피·주의 성분 카탈로그 DB 영속화 + 재료 매핑 (이슈 #15)"
---

# Tasks: 회피·주의 성분 카탈로그 DB 영속화 + 재료 매핑

**Input**: Design documents from `/specs/006-ingredient-avoidance-mapping/`

**Prerequisites**: plan.md ✓, spec.md ✓, research.md ✓, data-model.md ✓, contracts/ingredient-avoidance-mapping.md ✓

**Tests**: Test-First is **NON-NEGOTIABLE** (헌법 원칙 I). 각 user story 는 구현 전에 실패하는 Kotest `BehaviorSpec`(given/when/then 한국어)을 먼저 작성하고 Red 를 확인한다.

**Organization**: 작업은 user story 단위로 묶어 독립 구현·검증한다. 우선순위 P1(US1) → P2(US2) → P3(US3).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능 (다른 파일, 미완료 작업에 의존 없음)
- **[Story]**: 해당 user story (US1/US2/US3). Setup·Foundational·Polish 는 라벨 없음.
- 모든 작업에 정확한 파일 경로 포함.

## Path / 컨벤션 메모

- 도메인 port(순수, Spring/JPA 없음) → `:core:avoidance` (`com.meogo.core.avoidance`)
- JPA 엔티티·Spring Data 리포지토리·어댑터 → `:infra:persistence` (`com.meogo.infra.persistence.avoidance`), 전부 `BaseEntity` 상속·소프트삭제(`@SQLRestriction` 상속), 컬럼은 MySQL 기준 `@Column(length=N)`
- 컨텍스트 조합(음식→성분) → `:application:client`
- Flyway 시드(스키마 owner) + enum↔DB 정합 → `:app:api`
- 반환 통화는 기존 enum `AvoidanceSubstance`(D-READMODEL) — 004 enum 은 유지·시드 원천. 어댑터가 DB `code` → `AvoidanceSubstance.valueOf(code)` 브리지
- 모든 테스트는 Kotest `BehaviorSpec`, H2 어댑터 테스트는 Flyway off(테스트 규약) — 테스트가 JPA 로 직접 시드

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 빌드 배선 — `:infra:persistence` 가 `:core:avoidance` port 를 구현할 수 있게 의존 추가

- [X] T001 `infra/persistence/build.gradle.kts` 에 `"implementation"(project(":core:avoidance"))` 추가 (food 의존은 기존 — avoidance 신규). 추가 후 `./gradlew :infra:persistence:compileKotlin` 로 배선 확인

**Checkpoint**: persistence 가 avoidance 도메인 타입(enum·port)을 컴파일 클래스패스에서 봄

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 어댑터·조합이 의존하는 도메인 port 인터페이스(안정 표면, contracts/ 고정). 순수 인터페이스라 별도 테스트 없음 — 어댑터 테스트가 행위를 검증.

**⚠️ CRITICAL**: 이 phase 완료 전 어떤 user story 어댑터도 구현 불가

- [X] T002 [P] `AvoidanceSubstanceRepository` port 작성 — `core/avoidance/src/main/kotlin/com/meogo/core/avoidance/AvoidanceSubstanceRepository.kt` (`byCategory(category: AvoidanceCategory): List<AvoidanceSubstance>`, `translatedName(substance: AvoidanceSubstance, lang: LanguageCode): String`, `findByCodes(codes: Set<String>): List<AvoidanceSubstance>`)
- [X] T003 [P] `IngredientAvoidanceSubstanceRepository` port 작성 — `core/avoidance/src/main/kotlin/com/meogo/core/avoidance/IngredientAvoidanceSubstanceRepository.kt` (`findByIngredientIds(ingredientIds: Set<Long>): Map<Long, Set<AvoidanceSubstance>>`, ingredient 은 Long 으로만 참조 — food 타입 미import)

**Checkpoint**: port 2종 확정 — US1/US2/US3 병렬 착수 가능

---

## Phase 3: User Story 1 - 회피·주의 성분 카탈로그를 DB로 보유하고 다국어로 조회 (Priority: P1) 🎯 MVP

**Goal**: 81종 성분을 `avoidance_substance`(code·ko·9 번역 컬럼) + `avoidance_substance_category`(1~3 분류 멤버십)에 영속화하고, 코드 조회·분류별 조회·요청 언어 번역(없으면 ko 폴백)을 port 로 제공한다.

**Independent Test**: 어댑터 H2 테스트로 코드 조회 시 분류·번역(미보유 시 ko 폴백) 반환, 분류별 조회 시 해당 분류 성분(복수 분류 포함) 반환을 검증. enum↔DB 시드 정합 테스트로 81종·코드 유일·분류·번역 드리프트 0 검증.

### Tests for User Story 1 (REQUIRED — Test-First: 먼저 작성하고 FAIL 확인) ⚠️

- [X] T004 [P] [US1] `AvoidanceSubstanceRepositoryAdapterTest` 작성(실패 확인) — `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/avoidance/AvoidanceSubstanceRepositoryAdapterTest.kt`. given/when/then: 코드 집합으로 조회 시 enum 반환, `byCategory` 가 복수 분류 성분 포함 반환, `translatedName` 이 해당 언어 컬럼 반환·NULL 시 `korean_name`(ko) 폴백·빈 문자열 0, 소프트삭제(DELETED) 성분 제외 (H2, JPA 로 직접 시드)
- [X] T005 [P] [US1] `AvoidanceCatalogSeedSyncTest` 작성(실패 확인) — `app/api/src/test/kotlin/com/meogo/app/api/avoidance/AvoidanceCatalogSeedSyncTest.kt`. V5 시드 SQL 을 파싱해 성분 코드 집합 == `AvoidanceSubstance.entries` name 집합, 각 성분 분류 == enum `categories`, 번역 == `AvoidanceSubstanceTranslations` 임을 검증(드리프트 0, SC-001·SC-002·SC-003)

### Implementation for User Story 1

- [X] T006 [P] [US1] `AvoidanceSubstanceJpaEntity` 작성 — `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/AvoidanceSubstanceJpaEntity.kt`. `@Table("avoidance_substance")`, BaseEntity 상속, `code`(`@Column(length=40)`)·`koreanName`(`length=100`)·9 번역 프로퍼티(`nameZhHans`·`nameEn`·`nameJa`·`nameZhHant`·`nameVi`·`nameId`·`nameTh`·`nameRu`·`nameEs`, 각 `@Column(length=100)` nullable). 연관 매핑 없음(스칼라만 — 어댑터 분리 조회)
- [X] T007 [P] [US1] `AvoidanceSubstanceCategoryJpaEntity` 작성 — `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/AvoidanceSubstanceCategoryJpaEntity.kt`. `@Table("avoidance_substance_category")`, BaseEntity 상속, `substanceId: Long`·`category: String`(`@Column(length=30)`) 스칼라
- [X] T008 [P] [US1] `AvoidanceSubstanceJpaRepository` 작성 — `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/AvoidanceSubstanceJpaRepository.kt`. `findByCodeIn(codes: Set<String>)`, `findByIdIn(ids: Set<Long>)`
- [X] T009 [P] [US1] `AvoidanceSubstanceCategoryJpaRepository` 작성 — `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/AvoidanceSubstanceCategoryJpaRepository.kt`. `findByCategory(category: String)`, `findBySubstanceIdIn(substanceIds: Set<Long>)`
- [X] T010 [US1] `AvoidanceSubstanceRepositoryAdapter` 작성(T004 통과) — `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/AvoidanceSubstanceRepositoryAdapter.kt`. port 구현(`@Repository`/`@Component`). `byCategory`: 멤버십 테이블 `category` → `substanceId` → 성분 `code` → `AvoidanceSubstance.valueOf`. `translatedName`: 성분 행의 lang 컬럼 → NULL 시 `korean_name`. `findByCodes`: `code` → enum (무효 코드 제외). 의존: T006·T007·T008·T009
- [X] T011 [US1] Flyway 마이그레이션 `V5__create_avoidance_catalog_and_mapping.sql` 작성 — `app/api/src/main/resources/db/migration/V5__create_avoidance_catalog_and_mapping.sql`. (a) 3 테이블 DDL 생성: `avoidance_substance`(`UNIQUE(code)`, korean_name NOT NULL, 9 번역 컬럼 NULL, +BaseEntity 컬럼), `avoidance_substance_category`(`substance_id` FK→avoidance_substance, `UNIQUE(substance_id, category)`), `ingredient_avoidance_substance`(`ingredient_id` FK→ingredient, `substance_id` FK→avoidance_substance, `UNIQUE(ingredient_id, substance_id)`). (b) `avoidance_substance` 81행 시드(code·ko·9번역 = enum/`AvoidanceSubstanceTranslations` 값과 일치). (c) `avoidance_substance_category` 멤버십 시드(enum `categories` 와 일치). 멱등(UNIQUE + 재실행 안전 INSERT). T005 통과

**Checkpoint**: US1 독립 동작 — 성분이 DB 에서 코드·번역(ko 폴백)·분류와 함께 조회되고 enum↔DB 정합 0 드리프트. MVP 완료

---

## Phase 4: User Story 2 - 재료를 회피·주의 성분에 매핑하고 재료로 조회 (Priority: P2)

**Goal**: 재료(`ingredient`)↔성분을 `ingredient_avoidance_substance` FK 조인 테이블로 다대다 연결하고, 재료 id 집합으로 매핑 성분(재료별 구분, 미매핑은 빈 집합)을 조회한다.

**Independent Test**: 매핑 어댑터 H2 테스트로 재료 id 집합 조회 시 연결 성분(코드·분류 포함) 정확 반환, 미매핑 재료 빈 집합, 다대다(한 재료 다성분·한 성분 다재료) 반영, (재료,성분) 유일, 소프트삭제 제외를 검증.

**Dependency**: US1 의 `AvoidanceSubstanceJpaEntity`·`AvoidanceSubstanceJpaRepository`(substance_id→code 해석) 와 V5 테이블에 의존(spec: US1 이 US2 전제).

### Tests for User Story 2 (REQUIRED — Test-First: 먼저 작성하고 FAIL 확인) ⚠️

- [X] T012 [P] [US2] `IngredientAvoidanceSubstanceRepositoryAdapterTest` 작성(실패 확인) — `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/avoidance/IngredientAvoidanceSubstanceRepositoryAdapterTest.kt`. given/when/then: 재료 id 집합 → `Map<Long, Set<AvoidanceSubstance>>` 정확 반환, 미매핑 재료 키 생략/빈집합, 다대다 모두 반영, 빈 입력 → 빈 맵, 소프트삭제 매핑 제외 (H2, JPA 직접 시드)

### Implementation for User Story 2

- [X] T013 [US2] `IngredientAvoidanceSubstanceJpaEntity` 작성 — `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/IngredientAvoidanceSubstanceJpaEntity.kt`. `@Table("ingredient_avoidance_substance")`, BaseEntity 상속, `ingredientId: Long`·`substanceId: Long` 스칼라
- [X] T014 [US2] `IngredientAvoidanceSubstanceJpaRepository` 작성 — `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/IngredientAvoidanceSubstanceJpaRepository.kt`. `findByIngredientIdIn(ingredientIds: Set<Long>)`
- [X] T015 [US2] `IngredientAvoidanceSubstanceRepositoryAdapter` 작성(T012 통과) — `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/IngredientAvoidanceSubstanceRepositoryAdapter.kt`. port 구현. `findByIngredientIdIn` 결과를 `ingredientId` 로 group → `substanceId` 집합 → 성분 `code` → `AvoidanceSubstance.valueOf` → `Map<Long, Set<AvoidanceSubstance>>`. 의존: T013·T014, US1 T008
- [X] T016 [US2] V5 마이그레이션에 `ingredient_avoidance_substance` mock 매핑 시드 추가 — `app/api/src/main/resources/db/migration/V5__create_avoidance_catalog_and_mapping.sql`(T011 파일에 INSERT 추가). `SELECT id FROM ingredient WHERE korean_name=...` × 대표 성분 매핑(확정 콘텐츠 수령 시 교체). 멱등(UNIQUE)

**Checkpoint**: US1·US2 모두 독립 동작 — 재료 id → 매핑 성분 조회 성립

---

## Phase 5: User Story 3 - 음식 단위로 포함된 회피·주의 성분 집합 도출 (Priority: P3)

**Goal**: 음식 구성 재료 id 집합에 매핑된 성분을 합집합(중복 없음)으로 도출한다 — #17 음식 상세조회가 소비할 형태.

**Independent Test**: resolver 단위 테스트로 재료 구성 → 합집합 일치, 같은 성분 매핑 재료 복수 시 1회만, 매핑 없는 재료만이면 빈 집합 검증.

**Dependency**: US2 의 `IngredientAvoidanceSubstanceRepository` port(테스트는 fake/mock port 로 격리 가능 — 어댑터 무의존).

### Tests for User Story 3 (REQUIRED — Test-First: 먼저 작성하고 FAIL 확인) ⚠️

- [X] T017 [P] [US3] `FoodAvoidanceSubstanceResolverTest` 작성(실패 확인) — `application/client/src/test/kotlin/com/meogo/application/client/food/usecase/FoodAvoidanceSubstanceResolverTest.kt`. given/when/then: 재료 구성 → 매핑 성분 합집합(중복 없음), 동일 성분 매핑 재료 복수 → 1회 포함, 매핑 없는 재료만 → 빈 집합 (port 는 fake 로 주입)

### Implementation for User Story 3

- [X] T018 [US3] `FoodAvoidanceSubstanceResolver` 작성(T017 통과) — `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/FoodAvoidanceSubstanceResolver.kt`. `resolve(ingredientIds: Set<Long>): Set<AvoidanceSubstance>` = `repository.findByIngredientIds(ids).values.flatten().toSet()`. 의존: US2 T003(port)

**Checkpoint**: 3 user story 모두 독립 동작 — 음식→성분 합집합 도출 성립

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 모듈 경계 회귀와 전체 검증

- [X] T019 [P] ArchUnit 경계 회귀 확인 — `app/api/src/test/kotlin/com/meogo/app/api/architecture/ModuleBoundaryTest.kt`. 신규 avoidance JPA 엔티티·어댑터가 `:infra:persistence` 에만 있고 `:core:avoidance` 도메인은 JPA/Spring import 0, ingredient 를 Long 으로만 참조(food 타입 미import)임을 기존 규칙이 커버하는지 확인(필요 시 규칙 보강)
- [X] T020 전체 빌드·정합 검증 — `./gradlew :infra:persistence:test :application:client:test :app:api:test build` 그린 확인 + `specs/006-ingredient-avoidance-mapping/quickstart.md` 시나리오 대조

---

## Dependencies & Execution Order

### Phase 의존

- **Setup (Phase 1)**: 의존 없음 — 즉시 시작. 모든 어댑터의 컴파일 전제
- **Foundational (Phase 2)**: Setup 완료 후 — port 2종. 모든 user story 를 BLOCK
- **User Stories (Phase 3~5)**: Foundational 완료 후. 우선순위 P1→P2→P3 순차 권장(US2 가 US1 엔티티 재사용, spec 상 US1 전제). 인력 충분 시 US3 는 fake port 로 US2 와 병렬 가능
- **Polish (Phase 6)**: 원하는 user story 완료 후

### User Story 의존

- **US1 (P1)**: Foundational 후 시작 — 타 스토리 무의존. MVP
- **US2 (P2)**: US1 의 `AvoidanceSubstanceJpaEntity`/`AvoidanceSubstanceJpaRepository`·V5 테이블에 의존(substance_id→code)
- **US3 (P3)**: US2 의 port(`IngredientAvoidanceSubstanceRepository`)에 의존하나 테스트는 fake port 로 격리 — 어댑터 무의존

### Story 내부

- 테스트가 구현보다 먼저 작성·FAIL(헌법 I)
- 엔티티/리포지토리 → 어댑터 → (시드)
- US1: T004·T005(테스트) → T006~T009(엔티티·리포지토리 [P]) → T010(어댑터) → T011(시드)
- US2: T012(테스트) → T013·T014 → T015(어댑터) → T016(시드)
- US3: T017(테스트) → T018(resolver)

### 병렬 기회

- T002·T003 (port 2종) 병렬
- US1 내: T004·T005(테스트) 병렬, T006·T007·T008·T009(엔티티·리포지토리, 다른 파일) 병렬
- Foundational 완료 후 US1·US2(엔티티 준비 시)·US3(fake) 병렬 가능

---

## Parallel Example: User Story 1

```bash
# 테스트 먼저(반드시 FAIL):
Task: "AvoidanceSubstanceRepositoryAdapterTest — infra/persistence/src/test/.../AvoidanceSubstanceRepositoryAdapterTest.kt"
Task: "AvoidanceCatalogSeedSyncTest — app/api/src/test/.../AvoidanceCatalogSeedSyncTest.kt"

# 엔티티·리포지토리 병렬:
Task: "AvoidanceSubstanceJpaEntity — infra/persistence/.../AvoidanceSubstanceJpaEntity.kt"
Task: "AvoidanceSubstanceCategoryJpaEntity — infra/persistence/.../AvoidanceSubstanceCategoryJpaEntity.kt"
Task: "AvoidanceSubstanceJpaRepository — infra/persistence/.../AvoidanceSubstanceJpaRepository.kt"
Task: "AvoidanceSubstanceCategoryJpaRepository — infra/persistence/.../AvoidanceSubstanceCategoryJpaRepository.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 Setup → 2. Phase 2 Foundational(port) → 3. Phase 3 US1
4. **STOP & VALIDATE**: US1 독립 검증(어댑터 H2 + enum↔DB 정합)
5. MVP 데모 가능 — "성분이 DB 에서 코드·번역·분류로 조회됨"

### Incremental Delivery

1. Setup + Foundational → 토대
2. US1 → 독립 검증 → MVP
3. US2(매핑) → 독립 검증
4. US3(음식 합집합) → 독립 검증
5. 각 스토리가 이전을 깨지 않고 가치 추가

---

## Notes

- [P] = 다른 파일·미완료 의존 없음
- Kotlin `.kt` 주석 금지(고정), 테스트는 Kotest `BehaviorSpec`(한국어 given/when/then)
- 도메인 불변·`BaseEntity` 상속·소프트삭제·`@Column(length)` MySQL 기준·LAZY(본 기능은 스칼라 id 라 연관 없음)
- enum 은 유지(시드 원천·타입 통화) — 제거 아님. enum↔DB 드리프트는 T005 가 차단
- 미지원 언어 strict 에러는 본 기능 밖(#18). 본 기능은 ko 폴백만
- 각 task 또는 논리 묶음 후 커밋
