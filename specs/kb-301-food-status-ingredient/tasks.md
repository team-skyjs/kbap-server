# Tasks: food 상태 enum 간소화 및 기피성분 컬럼명 ingredient 변경

**Input**: Design documents from `/specs/kb-301-food-status-ingredient/`

**Prerequisites**: plan.md, spec.md, research.md(R1~R7), data-model.md, contracts/api-changes.md

**Tests**: Test-First NON-NEGOTIABLE (헌법 I) — 각 스토리는 실패 테스트(Red) 선행. 전면 리팩터 특성상 "Red" 는 신규/개정 테스트가 **컴파일 실패 또는 assertion 실패**함을 확인하는 것으로 본다.

**Organization**: US1(상태 수명주기)·US2(ingredient 개명)는 마이그레이션 파일을 분리해 서로 독립으로 구현·검증한다(Flyway 순서 독립 규약 — R4 의 단일 파일 결정을 스토리 독립을 위해 2파일로 조정).

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

없음 — 기존 멀티모듈 프로젝트 제자리 변경(신규 인프라·의존성 없음).

---

## Phase 2: Foundational

없음 — 두 스토리가 공유하는 선행 차단 작업 없음(마이그레이션 파일 분리로 독립).

---

## Phase 3: User Story 1 - 랭체인 결과 기반 음식 상태 수명주기 (Priority: P1) 🎯 MVP

**Goal**: FoodContentStatus 를 4값(FAILED·PENDING_IMAGE·PENDING_REVIEW·READY)으로 재정의하고 전이를 approve/reject/resubmit/attachImage 로 재배선. 배치 콘텐츠 잡은 전량 주석 처리 보존(R2). 기존 데이터는 FR-005 매핑으로 이관.

**Independent Test**: `./gradlew :common:test --tests "*Food*"` 로 전이 규칙, `:api:test --tests "*AdminFood*"` 로 승인 플로우, Testcontainers 마이그레이션 테스트로 매핑(구 상태 잔존 0건) 검증.

### Tests for User Story 1 (Red 먼저 — 작성 직후 실패 확인) ⚠️

- [X] T001 [P] [US1] 신규 전이 규칙 실패 테스트 작성 — approve(PENDING_REVIEW→READY)·reject(PENDING_REVIEW→FAILED, 사유 기록)·resubmit(FAILED→PENDING_IMAGE)·attachImage(PENDING_IMAGE→PENDING_REVIEW)·허용 외 전이 거부. `common/src/test/kotlin/com/kbap/common/domain/food/model/FoodApprovalTransitionTest.kt` (신규, BehaviorSpec 한국어 given/when/then)
- [X] T002 [P] [US1] 마이그레이션 매핑 실패 테스트 작성 — 구 6상태 시드 투입 후 신 4상태 분포·구 상태 잔존 0건 검증(Testcontainers, Flyway on). `api/src/test/kotlin/com/kbap/api/food/FoodStatusMigrationMappingTest.kt` (신규)
- [X] T003 [US1] 관리자 승인 플로우 실패 테스트 개정 — 검수 목록=PENDING_REVIEW·승인→READY·반려→FAILED·재제출 FAILED→PENDING_IMAGE·대시보드 failed 카운트. `api/src/test/kotlin/com/kbap/api/admin/AdminFoodContentReviewControllerTest.kt`·`AdminFoodListControllerTest.kt`

### Implementation for User Story 1

- [X] T004 [US1] `FoodContentStatus` 4값 재정의(FAILED·PENDING_IMAGE·PENDING_REVIEW·READY). `common/src/main/kotlin/com/kbap/common/domain/food/model/FoodContentStatus.kt`
- [X] T005 [US1] `Food` 재배선 — approve/reject/resubmit 신설(passContentReview/rejectContentReview 대체), attachImage 목적지 PENDING_REVIEW 고정, `incomplete()` 팩토리→`failed()`(시작 상태 FAILED), `needs*`·`transitionByContentState`·`TERMINAL_CONTENT_STATUSES`·재시도 상수는 사유 1줄과 함께 주석 처리, `contentStatus` columnDefinition 4값 ENUM 갱신. `common/src/main/kotlin/com/kbap/common/domain/food/model/Food.kt`
- [X] T006 [P] [US1] `FoodJpaRepository` INCOMPLETE 기반 벌크 전이·카운트 쿼리 주석 처리(사유 1줄). `common/src/main/kotlin/com/kbap/common/domain/food/FoodJpaRepository.kt`
- [X] T007 [P] [US1] 배치 콘텐츠 잡 전량 주석 처리(파일 상단 사유 1줄: KB-301 랭체인 이관·KB-302 정리 예정) — `batch/src/main/kotlin/com/kbap/batch/content/{FoodContentBatchConfig,FoodContentItemProcessor,FoodContentItemWriter,IncompleteFoodItemReader,FoodContentClientNotConfiguredException}.kt` + `batch/src/test/kotlin/com/kbap/batch/content/*.kt` 전부
- [X] T008 [US1] Flyway 상태 마이그레이션 — ENUM 합집합 확장 MODIFY → 매핑 UPDATE(REVIEWED→PENDING_REVIEW, REVIEW_REJECTED·INCOMPLETE→FAILED) → 4값 축소 MODIFY. `api/src/main/resources/db/migration/V<생성시각>__food_content_status_simplify.sql`
- [X] T009 [US1] 관리자 계층 4값 반영 — 대시보드 카운트(incomplete→failed 등 4종), 상태 필터, 검수 서비스 approve/reject/resubmit 재배선, 음식 수정의 상태 변경 허용값. `api/src/main/kotlin/com/kbap/api/admin/{AdminFoodDashboardService,AdminFoodService,AdminFoodContentReviewService,AdminFoodContentReviewResponse,AdminFoodPageController,AdminFoodContentReviewApi}.kt` + 템플릿 `api/src/main/resources/templates/admin/{food-list,foods,food-seed}.html`
- [X] T010 [US1] 스캔 센티널 시작 상태 FAILED 반영 — `FoodService.upsertIncomplete` 경로·`AdminFoodService` 시드 생성 경로가 `Food.failed()` 사용. `common/src/main/kotlin/com/kbap/common/domain/food/FoodService.kt`·`api/src/main/kotlin/com/kbap/api/admin/AdminFoodService.kt`
- [X] T011 [US1] 구 상태 참조 잔존 소탕 + 전체 빌드 그린 — 테스트 시드(`api/src/test/kotlin/com/kbap/api/food/FoodTestSeed.kt`·`api/src/test/kotlin/com/kbap/api/scenario/ScenarioFoodSeed.kt`·`api/src/test/kotlin/com/kbap/api/home/HomeTestSeed.kt`)와 구 전이 테스트(`common/src/test/.../model/{FoodContentReviewTransitionTest,FoodPendingReviewTransitionTest,FoodTest}.kt` 등) 개정·정리 후 `./gradlew build` 통과

**Checkpoint**: 상태 수명주기 단독 완결 — US2 없이 배포 가능

---

## Phase 4: User Story 2 - 기피성분 명칭을 ingredient 로 변경 (Priority: P2)

**Goal**: food JSON 컬럼 `avoidance_substances`→`ingredient`, 카탈로그 테이블 `avoidance_substance`→`ingredients`(R7), 도메인 타입 `FoodAvoidanceItem`→`FoodIngredient`, 관리자 응답 필드 `ingredients` — 데이터 값 불변.

**Independent Test**: 통합 테스트(Flyway+validate)가 개명 후 스키마↔엔티티 정합·데이터 보존을 검증. 관리자 검수 응답 필드명 계약 테스트.

### Tests for User Story 2 (Red 먼저 — 작성 직후 실패 확인) ⚠️

- [ ] T012 [P] [US2] 관리자 검수 응답 `ingredients` 필드 계약 실패 테스트(구 `avoidanceSubstances` 부재 포함). `api/src/test/kotlin/com/kbap/api/admin/AdminFoodContentReviewControllerTest.kt`
- [ ] T013 [P] [US2] 개명 데이터 보존 실패 테스트 — 구 컬럼·구 테이블에 시드 후 마이그레이션 적용 시 값 동일 검증(Testcontainers). `api/src/test/kotlin/com/kbap/api/food/IngredientRenameMigrationTest.kt` (신규)

### Implementation for User Story 2

- [ ] T014 [US2] Flyway 개명 마이그레이션 — `ALTER TABLE food RENAME COLUMN avoidance_substances TO ingredient` + `RENAME TABLE avoidance_substance TO ingredients`. `api/src/main/resources/db/migration/V<생성시각>__ingredient_rename.sql`
- [ ] T015 [US2] 도메인 개명 — `FoodAvoidanceItem.kt`→`FoodIngredient.kt`, `Food.avoidanceSubstances`→`ingredients`(`@Column(name = "ingredient")`), `avoidanceSubstancesByProbability()`→`ingredientsByProbability()`, `needsAvoidance*` 주석 블록 내 명칭은 보존. `common/src/main/kotlin/com/kbap/common/domain/food/model/{FoodAvoidanceItem→FoodIngredient,Food}.kt`
- [ ] T016 [P] [US2] 카탈로그 엔티티 `@Table(name = "ingredients")` 갱신. `common/src/main/kotlin/com/kbap/common/domain/avoidance/model/AvoidanceSubstance.kt`
- [ ] T017 [US2] 소비 계층 개명 전파 — `common/src/main/kotlin/com/kbap/common/domain/food/{FoodService,FoodRepositoryCustomImpl,dto/GetFoodDetailResult}.kt`, `api/src/main/kotlin/com/kbap/api/admin/{AdminFoodContentReviewApi,AdminFoodContentReviewResponse,AdminFoodPageController,AdminFoodService}.kt`, `api/src/main/kotlin/com/kbap/api/food/FoodDetailResponse.kt`(내부 참조만 — 외부 필드 기존부터 `ingredients`)
- [ ] T018 [US2] 테스트 시드·손스텁 SQL 개명 + `AvoidanceCatalogSeedSyncTest` 하드코딩 경로·테이블명 참조 점검 + 전체 빌드 그린 — `api/src/test`·`common/src/test`·`batch/src/test`(주석 블록 내부는 그대로) 전수, `./gradlew build` 통과

**Checkpoint**: 두 스토리 모두 단독 검증 가능

---

## Phase 5: Polish & Cross-Cutting

- [ ] T019 quickstart.md 검증 절차 전체 수행(`./gradlew build` + 명시된 테스트 필터) 및 스펙 SC-001~004 대조. 결과를 커밋 메시지에 요약
- [ ] T020 [P] 지식 위키 `../kbap-agenthub/wiki/food-content-pipeline.md` 의 상태 머신 섹션을 신 4상태 기준으로 갱신(구 머신은 "개편 전" 표기 유지)

---

## Dependencies & Execution Order

- Phase 1·2 없음 → US1(P3)·US2(P4)는 **서로 독립** — 병렬 가능(마이그레이션 파일 분리). 단일 작업자는 P1 우선.
- US1 내부: T001~T003(Red) → T004(enum) → T005(Food) → {T006, T007}[P] → T008~T010 → T011(빌드 게이트).
- US2 내부: T012·T013(Red) → T014(마이그레이션) → T015 → {T016}[P] → T017 → T018(빌드 게이트).
- Polish 는 두 스토리 완료 후.

## Parallel Example: User Story 1

```text
# Red 동시 착수: T001, T002 (서로 다른 신규 파일)
# T005 완료 후 동시: T006(FoodJpaRepository), T007(batch 주석 처리)
```

## Implementation Strategy

- **MVP = US1** — 상태 수명주기만으로 KB-302 착수 가능. US2(개명)는 독립 증분.
- 각 task 완료 시 커밋(작업/논리 단위 — Development Workflow). T011·T018 는 전체 `./gradlew build` 를 게이트로 삼는다(모듈 단위 통과로 완료 선언 금지 — quickstart 함정).
