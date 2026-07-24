# Tasks: 음식 기피성분 매핑을 food 테이블 JSON 컬럼으로 이관

**Input**: Design documents from `/specs/kb-210-food-avoidance-json/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). Every user story MUST include failing tests written BEFORE its implementation (Red → Green → Refactor).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2)

## Path Conventions

Gradle 멀티모듈 — `domain/food/`(도메인), `app/api/`(마이그레이션·통합 테스트). `domain:food` 테스트는 순수 단위(스프링·DB 무관)라 코드만으로 독립 검증되지만, **api 테스트는 Flyway enabled + Hibernate `ddl-auto=validate`**(Testcontainers MySQL, `app/api/src/test/resources/application.yml`)라 엔티티에 새 컬럼을 매핑하면 그 컬럼을 만드는 Flyway 마이그레이션(US2/T009)이 반드시 함께 있어야 `validate` 가 통과한다 — 즉 **US1(엔티티)과 US2(마이그레이션)는 스키마에서 결합**돼 구현·배포가 한 묶음이다.

---

## Phase 1: Setup (Shared Infrastructure)

없음 — 기존 모듈·빌드 구성 그대로 사용한다(신규 의존성·모듈 0건).

---

## Phase 2: Foundational (Blocking Prerequisites)

없음 — 두 스토리가 공유하는 선행 인프라 없음. US1 이 만드는 엔티티 매핑을 US2 가 역직렬화 검증에 사용하므로 스토리 순서(US1 → US2)가 곧 의존 순서다.

---

## Phase 3: User Story 1 - 음식 상세 조회 동작 무변화 (Priority: P1) 🎯 MVP

**Goal**: `Food` 의 기피성분을 `@OneToMany` 연관에서 JSON 컬럼 매핑(`List<FoodAvoidanceItem>`)으로 교체하되, 상세·목록·스캔의 응답 계약(확률 내림차순 정렬·위험도 판정)을 그대로 유지한다. 정렬·유효성은 애플리케이션 레이어 전담.

**Independent Test**: `./gradlew :domain:food:test :app:api:test` — 저장 순서를 뒤섞은 JSON 시드로 상세 조회 응답이 확률 내림차순인지, 빈 목록·위험도 판정이 이관 전과 동일한지 확인.

### Tests for User Story 1 (Red — 작성 후 실패 확인 필수)

- [X] T001 [P] [US1] `FoodAvoidanceItem` 기반으로 도메인 단위 테스트 갱신 — 정렬(`avoidanceSubstancesByProbability`, 저장 순서 무관)·`riskLevel()`·`overallRisk`(빈 목록 포함) BehaviorSpec 작성, `domain/food/src/test/kotlin/com/kbap/domain/food/model/FoodTest.kt` · `FoodOverallRiskTest.kt` — Red 확인
- [X] T002 [P] [US1] `FoodServiceTest` 의 getDetail 시나리오를 JSON 컬럼 시드 기반으로 갱신 — 확률 내림차순 응답·사용자 기피 필터·빈 목록 무오류, `domain/food/src/test/kotlin/com/kbap/domain/food/FoodServiceTest.kt` (구 `FoodAvoidanceSubstance` 시드 제거) — Red 확인
- [X] T003 [US1] api 통합 테스트 시드를 JSON 컬럼으로 전환 — food INSERT 에 `avoidance_substances` 컬럼 추가(저장 순서 뒤섞은 시드 포함), `food_avoidance_substance` INSERT 시드는 JSON 시드로 대체: `app/api/src/test/kotlin/com/kbap/app/api/food/FoodTestSeed.kt` · `home/HomeTestSeed.kt` · `scenario/ScenarioFoodSeed.kt` 및 food INSERT 를 쓰는 나머지 시드 전수 — 관련 컨트롤러 테스트(`FoodListControllerTest`·`FoodSearchControllerTest` 등) Red 확인

### Implementation for User Story 1 (Green → Refactor)

- [X] T004 [US1] `FoodAvoidanceItem` 값 객체 생성 — `code: String` + `@JsonProperty("inclusion_percent") inclusionPercent: Int` + `riskLevel()`, `domain/food/src/main/kotlin/com/kbap/domain/food/model/FoodAvoidanceItem.kt`
- [X] T005 [US1] `Food` 엔티티 매핑 교체 — `@OneToMany` 연관 제거, `@JdbcTypeCode(SqlTypes.JSON) @Column(name = "avoidance_substances", nullable = false) var avoidanceSubstances: List<FoodAvoidanceItem> = emptyList()` 추가, `avoidanceSubstancesByProbability()`·`overallRisk()` 원소 타입 교체(시그니처 유지), `domain/food/src/main/kotlin/com/kbap/domain/food/model/Food.kt` (구 엔티티 `FoodAvoidanceSubstance.kt`·리포지토리는 무수정 보존)
- [X] T006 [US1] `FoodService` 소비 코드 전환 — `getDetail` 의 `substanceCode`→`code` 참조 교체, `upsertIncomplete` 네이티브 INSERT 에 `avoidance_substances`(`'[]'`) 컬럼 추가, `domain/food/src/main/kotlin/com/kbap/domain/food/FoodService.kt`
- [X] T007 [US1] Green 확인 — `./gradlew :domain:food:test :app:api:test :app:batch:compileKotlin` 통과(배치 소스 diff 0 유지), 필요 시 Refactor

**Checkpoint**: 조회 전 경로가 JSON 컬럼 기반으로 동작 — MVP 완성

---

## Phase 4: User Story 2 - 기존 데이터 백필 (Priority: P2)

**Goal**: Flyway 마이그레이션으로 `food.avoidance_substances` 컬럼을 추가하고 `food_avoidance_substance` ACTIVE 행을 JSON 으로 백필한다(원본 무변경, NOT NULL 3단계, 값 제약 없음).

**Independent Test**: 마이그레이션 SQL 을 Testcontainers MySQL 에 실행해 (음식, 성분, 확률) 집합 일치·빈 목록 `[]`·경계값(1·100) 보존·원본 행 무변화·엔티티 역직렬화 왕복을 확인.

### Tests for User Story 2 (Red — 작성 후 실패 확인 필수)

- [X] T008 [US2] 백필 마이그레이션 검증 통합 테스트 작성 — 마이그레이션 SQL 을 리소스로 로드·실행(`AvoidanceCatalogSeedSyncTest` 선례)해 ① ACTIVE 행만 집계 일치 ② 매핑 0건 음식 `[]` ③ 경계값 1·100 보존 ④ 원본 테이블 무변화 ⑤ `Food` 엔티티로 역직렬화 시 `FoodAvoidanceItem` 값 일치(키 `inclusion_percent` 왕복) 검증, `app/api/src/test/kotlin/com/kbap/app/api/food/FoodAvoidanceBackfillMigrationTest.kt` — given 설명에 버전 번호 금지(버전 비의존 문구) — Red 확인

### Implementation for User Story 2 (Green → Refactor)

- [X] T009 [US2] Flyway 마이그레이션 작성 — 파일 생성 시각 timestamp 로 `app/api/src/main/resources/db/migration/V2026.MM.dd.HH.mm.ss__add_food_avoidance_substances_json.sql`: ① `ADD COLUMN avoidance_substances JSON NULL` ② ACTIVE 행 `JSON_ARRAYAGG(JSON_OBJECT('code',…,'inclusion_percent',…))` 백필 + 무매핑 `JSON_ARRAY()` ③ `MODIFY … NOT NULL` — CHECK·UNIQUE·DROP 금지, T008 테스트의 리소스 경로와 파일명 일치 확인
- [X] T010 [US2] Green 확인 — `./gradlew :app:api:test` 통과, 필요 시 Refactor

**Checkpoint**: 기존 데이터가 JSON 컬럼으로 복사되고 원본 보존 — 배포 가능

---

## Phase 5: Polish & Cross-Cutting Concerns

- [X] T011 전체 검증 — `./gradlew build` (전 모듈 테스트 + ArchUnit `ModuleBoundaryTest` 포함), `git diff --stat app/batch/src/main` 이 0건인지 확인, quickstart.md 확인 포인트 전수 점검

---

## Dependencies & Execution Order

- **US1 → US2**: T008(역직렬화 왕복 검증)이 US1 의 `FoodAvoidanceItem`·`Food` 매핑을 사용한다. US1 완료 후 US2 진행.
- US1 내부: T001·T002 는 [P](서로 다른 파일), T003 은 T001·T002 와 병행 가능하나 파일 수가 많아 순차 권장. 구현은 T004 → T005 → T006 → T007 순(값 객체 → 엔티티 → 서비스).
- Polish(T011)는 두 스토리 완료 후.

## Parallel Execution Examples

- US1 Red: T001 ∥ T002 (도메인 단위 vs 서비스 테스트 — 다른 파일)
- US2 는 태스크 3개 순차(Red → 구현 → Green).

## Implementation Strategy

**US1+US2 는 한 묶음**: api 테스트가 Flyway+`validate` 라 엔티티 컬럼 추가(US1)는 마이그레이션(US2)이 있어야 검증·기동된다 — 구현·배포 단위가 결합돼 있다(컬럼 없이 US1 코드만 배포하면 `validate` 실패로 기동 불가). `domain:food` 순수 단위 테스트만 US1 코드로 독립 검증된다. 배치 전환·구 구조 제거는 후속 Jira 로 분리(스펙 Out of Scope).
