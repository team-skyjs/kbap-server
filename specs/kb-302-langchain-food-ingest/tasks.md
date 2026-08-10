---

description: "Task list — KB-302 음식 콘텐츠 파이프라인 랭체인 전환"
---

# Tasks: 음식 콘텐츠 파이프라인 랭체인 전환 — 아웃박스 적재·결과 수신·재수집

**Input**: Design documents from `specs/kb-302-langchain-food-ingest/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Test-First 는 **NON-NEGOTIABLE**(헌법 원칙 I). 각 스토리는 구현 전에 실패하는 테스트를 먼저 쓰고 Red 를 확인한다. 모든 테스트는 Kotest `BehaviorSpec`(given/`when`/then, 한국어).

**Organization**: 스토리별로 묶어 독립 구현·검증이 가능하게 한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 선행 의존 없음)
- **[Story]**: US1(관리자 일괄 재수집) / US2(스캔 미보유 음식 자동 수집) / US3(실패 원인 확인)

## Path Conventions

- 도메인·영속: `common/src/main/kotlin/com/kbap/common/domain/food/`, 테스트 `common/src/test/kotlin/com/kbap/common/domain/food/`
- API·관리자 화면: `api/src/main/kotlin/com/kbap/api/admin/`, 테스트 `api/src/test/kotlin/com/kbap/api/admin/`
- 마이그레이션: `api/src/main/resources/db/migration/`
- 템플릿: `api/src/main/resources/templates/admin/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 스키마 준비. 신규 Gradle 모듈·라이브러리 의존은 **없다**(발행은 후속 티켓).

- [X] T001 `food` 에 `content_failure_kind ENUM('NOT_FOOD','JUDGE_REJECTED','INGREDIENT_GUARD') NULL` 추가 마이그레이션 작성 — `api/src/main/resources/db/migration/V2026.08.11.<HH.mm.ss>__food_content_failure_kind.sql` (점 구분 timestamp 규칙, 생성 시각으로 채움)
- [X] T002 [P] `food_content_outbox` 테이블 생성 마이그레이션 작성 — `api/src/main/resources/db/migration/V2026.08.11.<HH.mm.ss>__food_content_outbox_table.sql` (컬럼·인덱스는 data-model.md §2, `food_id` FK 포함 — food 참조 테이블의 스키마 규약)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 세 스토리가 모두 올라타는 도메인 모델. **완료 전에는 어떤 스토리도 시작할 수 없다.**

**⚠️ 이 단계의 상태 규칙이 US1 의 "사진 재활용·서비스 무중단"을 실제로 보장하는 지점이다.**

### Tests (먼저 작성 · Red 확인) ⚠️

- [X] T003 [P] `Food.applyContent` 상태 규칙 테스트 — `common/src/test/kotlin/com/kbap/common/domain/food/model/FoodApplyContentTest.kt`: (a) READY 는 상태·`imageRef` 불변, (b) FAILED + `imageRef` 있음 → `PENDING_REVIEW`, (c) FAILED + `imageRef` 없음 → `PENDING_IMAGE`, (d) 성공 적용이 `contentFailureKind`·반려 사유를 초기화
- [X] T004 [P] `Food.recordContentFailure` 테스트 — `common/src/test/kotlin/com/kbap/common/domain/food/model/FoodContentFailureTest.kt`: (a) READY 는 상태·콘텐츠 보존하고 유형·사유만 기록, (b) 그 외는 `FAILED` 전이 + `contentReviewAttempts` 증가, (c) 사유 10줄·1000자 절단
- [X] T005 [P] `FoodContentOutbox` 생성 규칙 테스트 — `common/src/test/kotlin/com/kbap/common/domain/food/model/FoodContentOutboxTest.kt`: 생성 시 `PENDING`·`attempts=0`·`sentAt=null`, `markSent()` 가 `SENT` + `sentAt` 기록, `markFailed()` 가 `PENDING` 유지 + `attempts` 증가
- [X] T006 `FoodContentOutboxJpaRepository` 통합 테스트 — `common/src/test/kotlin/com/kbap/common/domain/food/FoodContentOutboxJpaRepositoryTest.kt`: `existsByFoodIdAndOutboxStatus(PENDING)` 참/거짓, `findByOutboxStatusOrderByIdAsc` 정렬(기존 `FoodTestApp` 재사용)

### Implementation

- [X] T007 [P] `FoodContentFailureKind` enum 생성 — `common/src/main/kotlin/com/kbap/common/domain/food/model/FoodContentFailureKind.kt` (NOT_FOOD·JUDGE_REJECTED·INGREDIENT_GUARD)
- [X] T008 [P] `FoodContentOutboxStatus` enum 생성 — `common/src/main/kotlin/com/kbap/common/domain/food/model/FoodContentOutboxStatus.kt` (PENDING·SENT)
- [X] T009 `FoodContentOutbox` 엔티티 생성 — `common/src/main/kotlin/com/kbap/common/domain/food/model/FoodContentOutbox.kt` (BaseEntity 상속, `foodId: Long` 값 참조, `markSent`/`markFailed`, 컬럼 길이 MySQL 기준 명시)
- [X] T010 `FoodContentOutboxJpaRepository` 생성 — `common/src/main/kotlin/com/kbap/common/domain/food/FoodContentOutboxJpaRepository.kt` (`existsByFoodIdAndOutboxStatus`, `findByOutboxStatusOrderByIdAsc`)
- [X] T011 `Food` 에 `contentFailureKind` 필드 + `applyContent`·`recordContentFailure` 추가 — `common/src/main/kotlin/com/kbap/common/domain/food/model/Food.kt` (T003·T004 를 Green 으로. 상태 결정은 data-model.md §1 표 그대로)
- [X] T012 KB-301 이 주석으로 남긴 `Food` 의 폐기 코드 블록 정리 — `common/src/main/kotlin/com/kbap/common/domain/food/model/Food.kt`, `common/src/main/kotlin/com/kbap/common/domain/food/FoodJpaRepository.kt` (`:batch` 모듈은 건드리지 않는다 — 범위 밖)

**Checkpoint**: 상태 규칙과 아웃박스 영속이 준비됨 — 스토리 착수 가능

---

## Phase 3: User Story 1 - 서비스 중인 음식의 오타·설명 일괄 재수집 (Priority: P1) 🎯 MVP

**Goal**: 관리자가 검색어·상태 조건으로 고른 음식들의 재수집을 한 번에 요청하고, 도착한 결과가 사진을 유지한 채 반영된다.

**Independent Test**: READY + 사진 있는 음식을 조건으로 골라 재수집 요청 → 아웃박스 `PENDING` 생성 확인 → 적재 API 를 수동 호출 → 텍스트만 갱신되고 상태·`image_ref` 불변, `PENDING_IMAGE and image_ref is not null` 이 0건.

### Tests for User Story 1 (먼저 작성 · Red 확인) ⚠️

- [X] T013 [P] [US1] 적재 API 성공 경로 통합 테스트 — `api/src/test/kotlin/com/kbap/api/admin/AdminFoodContentIngestControllerTest.kt`: READY+사진 → 200·상태·`imageRef` 불변·텍스트 갱신 / FAILED+사진 → `PENDING_REVIEW` / FAILED+무사진 → `PENDING_IMAGE` / 같은 요청 두 번 → 둘 다 200
- [X] T014 [P] [US1] 적재 API 검증·실패 경로 테스트 — `api/src/test/kotlin/com/kbap/api/admin/AdminFoodContentIngestValidationTest.kt`: 번역 8키·`ingredients` 누락·`spiciness` 11·`failureKind` 3값 밖 → 400 `COMMON-002` 이고 DB 무변경 / 없는·삭제된 `foodId` → 400 `FOOD-001` / ADMIN 아닌 토큰 → 거절
- [X] T015 [P] [US1] 실패 결과 적재 테스트 — `api/src/test/kotlin/com/kbap/api/admin/AdminFoodContentIngestFailureTest.kt`: READY 대상은 상태·콘텐츠 보존하고 유형·사유만 기록 / FAILED 대상은 `FAILED` 유지 + 사유 갱신
- [X] T016 [P] [US1] 일괄 재수집 요청 테스트 — `api/src/test/kotlin/com/kbap/api/admin/AdminFoodRecollectTest.kt`: 조건에 걸린 음식 수만큼 `PENDING` 생성 / 이미 `PENDING` 인 음식은 중복 생성 안 됨 / 대상 0건이면 0건 결과 / 상한(500) 초과면 거부하고 아무 행도 만들지 않음

### Implementation for User Story 1

- [X] T017 [US1] 적재 요청 DTO + 검증 작성 — `api/src/main/kotlin/com/kbap/api/admin/AdminFoodContentIngestRequest.kt` (`foodId` 필수, `passed` 분기, 9개 언어 전수·빈 값 불가, `spiciness` 0~10, `ingredients` non-null·빈 배열 허용, `failureKind` 3값. 검증은 요청 경계가 소유 — 헌법 V)
- [X] T018 [US1] 적재 서비스 작성 — `api/src/main/kotlin/com/kbap/api/admin/AdminFoodContentIngestService.kt` (`@Transactional`, foodId 조회 실패 시 `BusinessException(FOOD_NOT_FOUND)`, 성공/실패를 엔티티 메서드에 위임 — 상태 로직을 서비스에 복제하지 않는다)
- [X] T019 [US1] 적재 컨트롤러 + swagger 인터페이스 작성 — `api/src/main/kotlin/com/kbap/api/admin/AdminFoodContentIngestController.kt`, `AdminFoodContentIngestApi.kt` (`@RequestMapping(ApiPaths.ADMIN + "/foods/contents")`, 반환 `ResponseEntity<BaseResponse<Unit>>`. Spring 애너테이션은 컨트롤러에, swagger 문서는 인터페이스에)
- [X] T020 [US1] `AdminFoodService.requestRecollect(query, status)` 구현 — `api/src/main/kotlin/com/kbap/api/admin/AdminFoodService.kt` (조건 조회 → 상한 검사 → `PENDING` 없는 음식만 아웃박스 삽입. 결과 타입에 요청·생성·스킵 건수. 상한은 companion 상수)
- [X] T021 [US1] 재수집 폼 엔드포인트 추가 — `api/src/main/kotlin/com/kbap/api/admin/AdminFoodPageController.kt` (`POST /admin/foods/recollect`, 현재 검색 조건 유지한 채 리다이렉트 + 결과 메시지)
- [X] T022 [US1] 목록 화면에 재수집 버튼·확인 다이얼로그 추가 — `api/src/main/resources/templates/admin/food-list.html` (대상 건수 노출, 상한 초과 시 안내. 목록 폭·토글 버튼 폭은 기존 유지)

**Checkpoint**: US1 이 단독으로 동작 — 재수집 요청이 쌓이고, 도착한 결과가 사진을 유지한 채 반영된다

---

## Phase 4: User Story 2 - 메뉴판 스캔에서 처음 본 음식 자동 수집 (Priority: P2)

**Goal**: 스캔에서 처음 본 음식이 등록과 동시에 수집 요청으로 남는다(같은 트랜잭션).

**Independent Test**: 미보유 음식명이 든 스캔을 처리한 뒤 그 음식의 `PENDING` 아웃박스 행이 있는지 확인. 스캔 응답은 수집 상태와 무관하게 즉시 반환.

### Tests for User Story 2 (먼저 작성 · Red 확인) ⚠️

- [X] T023 [P] [US2] `FoodService.createIncomplete` 아웃박스 동반 적재 테스트 — `common/src/test/kotlin/com/kbap/common/domain/food/FoodContentOutboxEnqueueTest.kt`: 신규 음식마다 `PENDING` 1건 생성 / 이미 `PENDING` 이면 중복 없음 / 트랜잭션 롤백 시 음식·요청 모두 남지 않음
- [X] T024 [P] [US2] 스캔 경로 회귀 테스트 — 기존 `api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt` 의 DB miss 블록에 추가: 미보유 음식이 포함된 스캔 처리 후 아웃박스 `PENDING` 행 생성, 스캔 응답은 기존과 동일(상태 의존 없음)

### Implementation for User Story 2

- [X] T025 [US2] `FoodService.createIncomplete` 에 아웃박스 삽입 추가 — `common/src/main/kotlin/com/kbap/common/domain/food/FoodService.kt` (기존 `@Transactional` 경계 안에서 resolve 된 음식에 대해 `PENDING` 없는 것만 삽입)

**Checkpoint**: US1·US2 가 각각 독립 동작 — 두 진입점 모두 같은 아웃박스로 모인다

---

## Phase 5: User Story 3 - 실패한 음식의 원인 확인 (Priority: P3)

**Goal**: 관리자가 실패 음식의 유형·사유를 화면에서 바로 본다.

**Independent Test**: 실패 결과가 기록된 음식을 관리자 목록·상세에서 열어 유형과 사유가 보이는지 확인.

### Tests for User Story 3 (먼저 작성 · Red 확인) ⚠️

- [X] T026 [P] [US3] 실패 유형 노출 테스트 — `api/src/test/kotlin/com/kbap/api/admin/AdminFoodFailureViewTest.kt`: 상세 뷰가 `contentFailureKind`·사유를 담고, `INGREDIENT_GUARD` 는 목록에서 구분 가능(안전 직결)

### Implementation for User Story 3

- [X] T027 [US3] 상세·요약 뷰에 실패 유형 필드 추가 — `api/src/main/kotlin/com/kbap/api/admin/AdminFoodService.kt` (`AdminFoodDetailView`·`AdminFoodSummaryView`)
- [X] T028 [US3] 목록·상세 화면에 실패 유형·사유 표시 — `api/src/main/resources/templates/admin/food-list.html` ("재시도하면 될 수도"가 아니라 "내용에 문제가 있다"로 표현 — 계약 전제)

**Checkpoint**: 세 스토리 모두 독립 동작

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T029 [P] 지식 위키 계약 문서 갱신 — `../kbap-agenthub/wiki/langchain-food-ingest-contract.md` (foodId 매칭·READY 스킵 폐기·상태 결정 표를 `contracts/ingest-api.md` 기준으로 반영) + `../kbap-agenthub/wiki/food-content-pipeline.md` 의 아웃박스·발행 범위 갱신, `INDEX.md` 확인
- [X] T030 [P] 랭체인 쪽 변경 사항 공유 메모 — 요청에서 받은 `foodId` 를 결과 호출에 그대로 echo 하는 한 줄이 전부임을 `contracts/ingest-api.md` 로 전달
- [X] T031 `./gradlew build` 전체 통과 확인 (ArchUnit 경계·`ErrorCodeStatusTest`·마이그레이션 정합 포함)
- [X] T032 quickstart.md 절차 수동 검증 — 특히 `select id from food where content_status='PENDING_IMAGE' and image_ref is not null` 이 0건

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1(Setup)**: 즉시 시작 가능. T001·T002 는 서로 독립.
- **Phase 2(Foundational)**: Phase 1 완료 후. **모든 스토리를 막는다.**
- **Phase 3~5(User Stories)**: Phase 2 완료 후. 우선순위 순(P1 → P2 → P3) 또는 병렬.
- **Phase 6(Polish)**: 원하는 스토리 완료 후.

### User Story Dependencies

- **US1(P1)**: Foundational 만 의존. 단독으로 MVP.
- **US2(P2)**: Foundational 만 의존. US1 과 파일이 겹치지 않는다(`FoodService` vs `AdminFoodService`).
- **US3(P3)**: Foundational 의 `contentFailureKind` 에 의존. 화면 파일(`food-list.html`)이 US1 의 T022 와 겹치므로 **T022 와 T028 은 순차 처리**.

### Within Each User Story

- 테스트를 먼저 쓰고 **실패(Red)를 확인**한 뒤 구현한다(헌법 I).
- 엔티티 → 리포지토리 → 서비스 → 컨트롤러 → 화면 순.
- 상태 결정 로직은 엔티티에만 둔다(서비스 복제 금지).

### Parallel Opportunities

- T003·T004·T005 (서로 다른 테스트 파일)
- T007·T008 (서로 다른 enum 파일)
- T013·T014·T015·T016 (서로 다른 테스트 파일)
- T023·T024 (common / api 각각)
- Foundational 완료 후 US1 과 US2 를 다른 사람이 병렬 진행 가능

---

## Parallel Example: Foundational

```bash
# 실패 테스트 먼저 (병렬):
Task: "Food.applyContent 상태 규칙 테스트 (FoodApplyContentTest.kt)"
Task: "Food.recordContentFailure 테스트 (FoodContentFailureTest.kt)"
Task: "FoodContentOutbox 생성 규칙 테스트 (FoodContentOutboxTest.kt)"

# Green 단계 enum (병렬):
Task: "FoodContentFailureKind enum 생성"
Task: "FoodContentOutboxStatus enum 생성"
```

---

## Implementation Strategy

### MVP (US1 만)

1. Phase 1 Setup → 2. Phase 2 Foundational → 3. Phase 3 US1 → 4. **정지·검증**(quickstart 3번 표) → 배포 가능

이 시점에 관리자는 재수집을 요청할 수 있고, 결과가 도착하면 사진을 유지한 채 반영된다. 단 **발행이 후속 티켓이라 실제 수집은 아직 돌지 않는다** — 적재 API 는 수동 호출로 검증한다.

### Incremental Delivery

1. Setup + Foundational → 기반 완료
2. US1 → 독립 검증 → 배포(MVP)
3. US2 → 스캔 경로가 같은 아웃박스로 합류
4. US3 → 실패 트리아지 화면 완성

---

## Notes

- 모든 테스트는 Kotest `BehaviorSpec`, given/`when`/then 한국어. Spring 통합 테스트는 `SpringExtension` + `@SpringBootTest`.
- 마이그레이션 파일명은 **생성 시각 기준 점 구분 timestamp**. 이미 적용된 마이그레이션 파일은 수정하지 않는다.
- Kotlin 주석은 "코드로 표현 불가능한 제약"만. 설계 근거는 커밋 메시지·`docs/`·이 spec 에 남긴다.
- 작업/논리 단위마다 커밋한다.
- **범위 밖**: 큐 발행(`common.port.mq` seam·`:infra:mq`·발행 배치 잡), `:batch` 모듈 정리.
