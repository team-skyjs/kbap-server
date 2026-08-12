# Tasks: READY 전이 벡터 아웃박스 기반 음식 벡터 동기화

**Input**: Design documents from `/specs/kb-328-food-vector-outbox/`

**Prerequisites**: plan.md, spec.md, research.md(R1~R10), data-model.md(판정 표), contracts/, quickstart.md

**Tests**: Test-First **NON-NEGOTIABLE** (헌법 I) — 각 스토리의 테스트를 먼저 작성하고 Red 확인 후 구현한다. 전 테스트 Kotest BehaviorSpec(given/when/then 한국어).

**Organization**: 유저 스토리 단위 독립 구현·검증. US1 이 MVP.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

**Purpose**: 의존성·설정 준비 (코드 변경 없음)

- [X] T001 [P] `common/build.gradle.kts` 에 mongodb-driver-sync 의존 추가(`libs` 카탈로그 좌표 재사용), `api/build.gradle.kts` 에서 해당 의존 제거 — `:api` 는 `:common` 경유로 획득
- [X] T002 [P] `batch/src/main/resources/application.yml` 에 `kbap.llm.embedding.{enabled(env 주입, 기본 false), dimension: 256}` 와 `kbap.vector.{enabled, uri, database: kbap, collection: foods}`(env 주입) 신설 — api yml 의 기존 표기와 동일 형식

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 벡터 접근 코드의 `:common` 이사(동작 무변경 리팩터링) — US1 의 store 가 이 패키지 옆에 들어가므로 선행 필수

**⚠️ CRITICAL**: 리팩터링이므로 새 Red 테스트 없음 — 기존 scan 테스트 그린 유지가 검증 기준

- [X] T003 `common/src/main/kotlin/com/kbap/common/domain/food/vector/` 신설: `FoodVectorSearcher`(기존 `api/.../scan/SimilarFoodSearcher` 이사, 반환 타입 포함)·`FoodVectorProperties`(`kbap.vector.*` 홀더 이사)·`FoodVectorDocuments`(문서 필드명 상수 — `foodId`·`embedding` 등, searcher 파이프라인이 즉시 사용)·`DocumentDbFoodVectorSearcher`(기존 `DocumentDbSimilarFoodSearcher` 구현 이사, 스테레오타입 없는 plain class)
- [X] T004 api 참조 전환: `api/src/main/kotlin/com/kbap/api/scan/SimilarFoodResolver.kt`·`ScanService.kt` 가 새 seam 을 참조, searcher·MongoClient 빈 조립을 `api/src/main/kotlin/com/kbap/api/core/config/` 로 이동(`@ConditionalOnProperty(kbap.vector.enabled)`), 구 `api/.../scan/{SimilarFoodSearcher,DocumentDbSimilarFoodSearcher}.kt` 삭제, `api/src/test/kotlin/com/kbap/api/scan/FakeSimilarFoodSearch.kt` 등 테스트 대역 갱신
- [X] T005 `./gradlew :common:build :api:test --tests "com.kbap.api.scan.*"` 그린 확인 — 이사 완료 체크포인트

**Checkpoint**: 벡터 접근 코드가 `:common` 단일 소유 — 이후 스토리 병렬 진행 가능

---

## Phase 3: User Story 1 - 승인된 음식이 벡터 검색 후보에 반영된다 (Priority: P1) 🎯 MVP

**Goal**: 승인 트랜잭션에서 UPSERT 아웃박스 생성 → `foodVectorSyncJob` 이 임베딩·DocumentDB upsert → COMPLETE. embeddingHash 멱등·실패 격리 포함.

**Independent Test**: 음식 1건 승인 → 배치 1회 실행 → 벡터 문서 생성 + 아웃박스 COMPLETE (quickstart 2~4절).

### Tests for User Story 1 (REQUIRED — 먼저 작성, Red 확인) ⚠️

- [X] T006 [P] [US1] `common/src/test/kotlin/com/kbap/common/domain/food/model/FoodTest.kt` — `approve()` 가 실제 전이 시 true, 이미 READY 면 false 반환 검증 (Red)
- [X] T007 [P] [US1] `common/src/test/kotlin/com/kbap/common/domain/food/FoodVectorOutboxJpaRepositoryTest.kt` (Testcontainers) — PENDING 커서 페이징(`findPendingAfterId`)·동일 (foodId, operation) PENDING 중복 억제·COMPLETE/실패(attempts·last_error)/FAILED/재처리 전이 쿼리 (Red)
- [X] T008 [P] [US1] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodContentReviewVectorOutboxTest.kt` — 승인(PENDING_REVIEW→READY) 시 같은 트랜잭션에서 UPSERT/PENDING 생성, 이미 READY 재승인 시 미생성 (Red)
- [X] T009 [P] [US1] `batch/src/test/kotlin/com/kbap/batch/vector/FoodVectorSyncProcessorTest.kt` (fake `TextEmbeddingClient`·fake `FoodVectorStore`) — 문서 없음→임베딩·upsert·COMPLETE / hash 동일→임베딩 0회 COMPLETE / hash 변경→재임베딩 / longDescription null·blank→실패 기록 / attempts 5 도달→FAILED (Red)

### Implementation for User Story 1

- [X] T010 [P] [US1] Flyway `api/src/main/resources/db/migration/V<생성시각 timestamp>__food_vector_outbox_table.sql` — data-model.md 1절 스키마(테이블·ENUM·인덱스 2종·FK)
- [X] T011 [P] [US1] `common/src/main/kotlin/com/kbap/common/domain/food/model/FoodVectorOutbox.kt` — BaseEntity 상속 엔티티 + `FoodVectorOutboxOperation`·`FoodVectorOutboxStatus` enum + 팩토리 `upsert(foodId)`/`delete(foodId)` + 실패 기록·FAILED 전이·재처리 도메인 메서드(MAX_ATTEMPTS=5)
- [X] T012 [US1] `common/src/main/kotlin/com/kbap/common/domain/food/FoodVectorOutboxJpaRepository.kt` — T007 이 요구하는 쿼리 전부(커서 페이징·exists 중복 억제·`countByOutboxStatus`·FAILED 목록)
- [X] T013 [US1] `common/src/main/kotlin/com/kbap/common/domain/food/model/Food.kt` — `approve()` 반환을 Boolean 으로 변경(전이 시에만 true), 기존 호출부 컴파일 갱신
- [X] T014 [US1] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodContentReviewService.kt` — 승인 전이(true) 시 UPSERT 아웃박스 생성(PENDING 중복 억제), 기존 `@Transactional` 안
- [X] T015 [US1] `common/src/main/kotlin/com/kbap/common/domain/food/vector/FoodVectorStore.kt`(seam — `findEmbeddingHash(foodId)`/`upsert(document)`/`delete(foodId)`) + `DocumentDbFoodVectorStore.kt`(replace upsert·plain class) + `FoodVectorDocuments` 에 v2 필드·`sha256:` hash 규약(contracts/vector-food-document-v2.md) 반영
- [X] T016 [US1] `batch/src/main/kotlin/com/kbap/batch/vector/FoodVectorSyncProcessor.kt` — TransactionTemplate 로 (조회)→(트랜잭션 밖 임베딩·스토어)→(결과 반영) 분리, 임베딩 원문 `koreanName\nlongDescription`·hash 계산, data-model.md 3절 UPSERT 판정 구현
- [X] T017 [US1] `batch/src/main/kotlin/com/kbap/batch/vector/FoodVectorSyncBatchConfig.kt` — `foodVectorSyncJob`/step(Tasklet·RunIdIncrementer·ResourcelessTransactionManager, 기존 `FoodContentOutboxBatchConfig` 패턴) + MongoClient·store 빈 조립(`@ConditionalOnProperty`) + `FoodVectorSyncSummary.kt` 로그
- [X] T018 [US1] T006~T009 그린 확인 후 리팩터: `./gradlew :common:test :api:test :batch:test`

**Checkpoint**: 승인 → 배치 → 벡터 문서 적재 전 구간 동작 (MVP)

---

## Phase 4: User Story 2 - READY 이후의 변경·삭제도 반영된다 (Priority: P2)

**Goal**: READY 수정 → UPSERT, READY 해제·삭제 → DELETE. 배치의 DELETE 처리와 UPSERT 자격 재검사.

**Independent Test**: READY 음식 설명 수정→배치→문서 갱신 / 삭제→배치→문서 제거 (quickstart 5절).

### Tests for User Story 2 (REQUIRED — 먼저 작성, Red 확인) ⚠️

- [X] T019 [P] [US2] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodServiceVectorOutboxTest.kt` — `updateFood` 결과 READY 면 UPSERT / READY→비READY 변경이면 DELETE / `deleteFood` 는 DELETE 생성 (Red)
- [X] T020 [P] [US2] `batch/src/test/kotlin/com/kbap/batch/vector/FoodVectorSyncProcessorTest.kt` 확장 — DELETE: 문서 제거·문서 부재도 COMPLETE(멱등) / UPSERT 처리 시점에 음식이 비READY·DELETED 면 적재 없이 문서 제거 후 COMPLETE (Red)

### Implementation for User Story 2

- [X] T021 [US2] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodService.kt` — `updateFood` 끝에서 변경 전/후 content_status 비교 훅(UPSERT/DELETE), `deleteFood` 에 DELETE 생성 (기존 `@Transactional` 안)
- [X] T022 [US2] `batch/src/main/kotlin/com/kbap/batch/vector/FoodVectorSyncProcessor.kt` — DELETE 처리·UPSERT 자격 재검사(soft-delete 우회 조회 포함 여부는 `@SQLRestriction` 특성 고려해 foodId 존재 판정으로 구현), T019·T020 그린 확인

**Checkpoint**: 벡터 저장소가 MySQL 과 계속 정합 (US1·US2 독립 동작)

---

## Phase 5: User Story 3 - 기존 READY 음식 백필 (Priority: P3)

**Goal**: 도입 시점의 READY·ACTIVE 음식 전건을 UPSERT/PENDING 으로 1회 적재.

**Independent Test**: READY 시드 후 백필 SQL 실행 → READY·ACTIVE 만 PENDING 생성 (quickstart 1절).

### Tests for User Story 3 (REQUIRED — 먼저 작성, Red 확인) ⚠️

- [X] T023 [US3] `common/src/test/kotlin/com/kbap/common/domain/food/FoodVectorOutboxBackfillTest.kt` — **별도 백필 마이그레이션 파일**의 SQL 리소스를 읽어 시드된 Testcontainers DB(READY·ACTIVE 2건 + PENDING_REVIEW 1건 + DELETED 1건)에 실행, READY·ACTIVE 만 PENDING 생성 검증. **주의**: 시드-동기화 테스트 함정 — 리소스 경로 하드코딩 시 파일명 변경과 함께 갱신, `given` 설명에 버전 번호 금지 (Red)

### Implementation for User Story 3

- [X] T024 [US3] **별도 Flyway 파일** `api/src/main/resources/db/migration/V<생성시각 timestamp>__food_vector_outbox_backfill.sql` 에 `INSERT INTO food_vector_outbox … SELECT id, 'UPSERT', 'PENDING' … FROM food WHERE content_status='READY' AND status='ACTIVE'` 작성 — T010 파일은 수정하지 않는다(US1 이 먼저 배포되면 checksum 파손 — DB 리뷰 Major#2, R9 개정), T023 그린 확인

**Checkpoint**: 배포 시 기존 READY 전량이 첫 배치에서 적재됨

---

## Phase 6: User Story 4 - 관리자가 실패 건을 보고 재처리한다 (Priority: P4)

**Goal**: FAILED 아웃박스 대시보드 노출(원인·시도 횟수) + 재처리(PENDING 복귀).

**Independent Test**: FAILED 행 시드 → 대시보드 조회·재처리 POST → PENDING 복귀 (contracts/admin-vector-outbox.md).

### Tests for User Story 4 (REQUIRED — 먼저 작성, Red 확인) ⚠️

- [X] T025 [P] [US4] `api/src/test/kotlin/com/kbap/api/admin/AdminVectorOutboxPageTest.kt` — 대시보드 모델에 상태별 카운트·FAILED 목록(최신순 상한 20, last_error 포함), `POST /admin/foods/vector-outboxes/{id}/retry` 가 FAILED→PENDING(attempts=0)·비FAILED no-op·리다이렉트 (Red)

### Implementation for User Story 4

- [X] T026 [US4] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodDashboardService.kt` — 벡터 아웃박스 카운트·FAILED 목록 조회 + 재처리 메서드(`@Transactional`)
- [X] T027 [US4] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodPageController.kt` — retry POST 매핑 + 음식 대시보드 Thymeleaf 템플릿(해당 컨트롤러가 렌더하는 뷰, `api/src/main/resources/templates/`)에 벡터 아웃박스 섹션 추가, T025 그린 확인

**Checkpoint**: 전 스토리 독립 동작

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T028 `./gradlew build` 전체 그린(ArchUnit `ModuleBoundaryTest` 포함 — 신규 vector 패키지가 도메인 방향 맵 위반 없는지 확인)
- [ ] T029 [P] quickstart.md 3~5절 dev 수동 검증 — 배치 실행·DocumentDB 문서 v2 필드·hash 스킵 로그·삭제 반영 (DocumentDB 는 Testcontainers 불가라 수동 필수)
- [X] T030 [P] 지식 위키 `../kbap-agenthub/wiki/food-content-pipeline.md` 에 READY 이후 벡터 동기화 단계(아웃박스·hash 멱등·FAILED 재처리) 추가 + INDEX.md 갱신

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (P1)**: 즉시 시작 가능. T001·T002 병렬
- **Foundational (P2)**: T001 이후 (T003 은 common 에 driver 필요). T003→T004→T005 순차. **모든 스토리를 블로킹**
- **US1 (P3)**: Foundational 완료 후. **US2·US4 가 US1 산출물(엔티티·리포지토리·processor)에 의존**하므로 US1 먼저
- **US2 (P4)**: US1 완료 후 (processor·훅 확장)
- **US3 (P5)**: US1 의 T010(마이그레이션 파일) 완료 후 — US2 와 병렬 가능
- **US4 (P6)**: US1 의 T011·T012 완료 후 — US2·US3 과 병렬 가능
- **Polish (P7)**: 전 스토리 완료 후

### Within Each User Story

- 테스트 먼저 작성·Red 확인 → 구현 → 그린 → 리팩터 (헌법 I)
- 엔티티(T011) → 리포지토리(T012) → 서비스 훅(T014) → 배치(T016~T017)

### Parallel Opportunities

- T001 ∥ T002 / T006 ∥ T007 ∥ T008 ∥ T009 (Red 테스트 4건 동시 작성) / T010 ∥ T011 / US1 완료 후 US2 ∥ US3 ∥ US4 / T029 ∥ T030

---

## Implementation Strategy

**MVP = Phase 1~3 (US1)**: 승인 → 아웃박스 → 배치 적재까지 완결. 여기서 멈춰도 신규 승인 건부터 벡터 검색 후보가 늘어난다.

**Incremental**: US2(정합 유지) → US3(백필 — 마이그레이션 한 줄이라 짧음) → US4(운영 편의) 순으로 각 스토리 검증 후 진행. 전부 같은 PR 로 나가되 스토리 단위 커밋.
