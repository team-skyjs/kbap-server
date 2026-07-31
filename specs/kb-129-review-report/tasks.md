# Tasks: 리뷰 신고

**Input**: Design documents from `/specs/kb-129-review-report/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/report-api.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 스토리는 실패하는 테스트를 먼저 작성하고(Red 확인) 구현한다.

**Organization**: 스토리별 독립 구현·검증. US1(신고 접수)만으로 MVP.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

없음 — 기존 프로젝트 구조·의존성 그대로. 신규 모듈·의존성 추가 없음.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 두 스토리가 공유하는 신고 영속 계층 — `report` 테이블·엔티티·리포지토리·에러 코드·경계 맵

- [X] T001 Flyway 마이그레이션 작성: `api/src/main/resources/db/migration/V2026.08.01.<현재시각>__report_table.sql` — `report` 테이블(data-model.md 스키마: reporter_member_id FK→member, target_type/reason varchar(20), detail varchar(500) NULL, status/created_at/updated_at 공통, `UNIQUE uk_report_reporter_target(reporter_member_id, target_type, target_id)`, target_id 에 FK 없음). 다른 마이그레이션과 순서 독립일 것
- [X] T002 [P] `ErrorCode` 에 REPORT 접두 3종 추가: `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt` — `REPORT_SELF_TARGET("REPORT-001", 400)`·`REPORT_DUPLICATED("REPORT-002", 409)`·`REPORT_TARGET_NOT_FOUND("REPORT-003", 404)` (형식은 `ErrorCodeStatusTest` 가 자동 검증 — 실행해 Green 확인)
- [X] T003 [Red] 신고 영속 테스트 먼저 작성: `common/src/test/kotlin/com/kbap/common/domain/report/ReportJpaRepositoryTest.kt` + `ReportTestApp.kt` (기존 `review/ReviewTestApp` 패턴 — `@SpringBootTest` + `MySqlContainerConfig`, BehaviorSpec given/when/then 한국어). 시나리오: 저장·`existsByReporterMemberIdAndTargetTypeAndTargetId`·`findTargetIdsByReporterMemberIdAndTargetType`·같은 (신고자,타입,대상) 재저장 시 `DataIntegrityViolationException`. 컴파일 실패 = Red 확인
- [X] T004 [Green] 신고 도메인 구현: `common/src/main/kotlin/com/kbap/common/domain/report/model/Report.kt`(BaseEntity 상속, `@Table(name = "report", uniqueConstraints = [...])` — common 테스트는 엔티티로 스키마 생성하므로 UNIQUE 를 엔티티에 선언, JPA 연관 없음)·`model/ReportTargetType.kt`(REVIEW)·`model/ReportReason.kt`(SPAM·ABUSE·FALSE_INFO·SEXUAL·OTHER)·`ReportJpaRepository.kt`(exists·`@Query select r.targetId` 목록). T003 테스트 Green 확인
- [X] T005 `ModuleBoundaryTest` 허용 맵에 `"report" to emptySet()` 추가: `api/src/test/kotlin/com/kbap/api/architecture/ModuleBoundaryTest.kt` — `./gradlew :api:test --tests "*.ModuleBoundaryTest"` Green 확인

**Checkpoint**: `report` 영속 계층 완성 — US1·US2 병행 가능

---

## Phase 3: User Story 1 - 부적절한 리뷰 신고 (Priority: P1) 🎯 MVP

**Goal**: `POST /api/v1/reports` 로 신고를 접수·저장한다 (성공 200 / 자기 400 / 중복 409 / 대상없음 404 / 미인증 401)

**Independent Test**: 회원 A 가 B 의 리뷰를 신고하면 200 + 저장, 예외 경로가 각 에러 코드로 거절 — MockMvc 로 단독 검증

### Tests for User Story 1 (Red 먼저) ⚠️

- [X] T006 [US1] [Red] MockMvc 테스트 작성: `api/src/test/kotlin/com/kbap/api/report/ReportControllerTest.kt` (기존 `review/ReviewControllerTest` 픽스처 패턴 재사용). 시나리오: ① 타인 리뷰 신고 200 + report 행 저장 ② detail 500자 경계(500 허용·501 거절 400) ③ 자기 리뷰 400 `REPORT-001` ④ 같은 대상 재신고 409 `REPORT-002` ⑤ 없는/삭제된 리뷰 404 `REPORT-003` ⑥ targetType·targetId·reason 누락 및 미정의 enum 값 400 ⑦ 토큰 없이 401(**필터 등록 검증** — contracts/report-api.md). 실행해 Red 확인

### Implementation for User Story 1

- [X] T007 [US1] [Green] 요청 DTO·유스케이스 구현: `api/src/main/kotlin/com/kbap/api/report/ReportCreateRequest.kt`(`@field:NotNull` targetType/targetId/reason, `@field:Size(max=500)` detail) + `ReportService.kt` — `@Transactional createReport`: REVIEW 대상 조회(없으면 `REPORT_TARGET_NOT_FOUND`) → 자기 콘텐츠 거절(`REPORT_SELF_TARGET`) → `existsBy` 선조회(`REPORT_DUPLICATED`) → save, `DataIntegrityViolationException` catch → `REPORT_DUPLICATED`(동시 경합, research R2)
- [X] T008 [US1] [Green] HTTP 경계 구현: `ReportController.kt`(`@PostMapping(ApiPaths.V1 + "/reports")`, `@AuthMemberId`, `BaseResponse<Unit>`) + `ReportApi.kt`(swagger 애너테이션만 — 파라미터 애너테이션 위치 규약) + `api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt` 인증 필터 include 에 `"${ApiPaths.V1}/reports"` 추가
- [X] T009 [US1] T006 전체 Green 확인: `./gradlew :api:test --tests "com.kbap.api.report.*"` — 통과 후 리팩터(중복 픽스처 정리 등)

**Checkpoint**: 신고 접수 단독 배포 가능 (MVP)

---

## Phase 4: User Story 2 - 신고한 리뷰는 나에게 보이지 않음 (Priority: P2)

**Goal**: 음식 리뷰 목록(`GET /api/v1/reviews?foodId=`)에서 호출 회원이 신고한 리뷰를 제외한다. 다른 회원 결과·페이지 규약 불변

**Independent Test**: A 신고 후 A 목록에 미노출·B 목록에 노출, 제외로 20개 미만 페이지여도 hasNext·nextCursor 규약 유지

### Tests for User Story 2 (Red 먼저) ⚠️

- [ ] T010 [P] [US2] [Red] 리포지토리 제외 쿼리 테스트 보강: `common/src/test/kotlin/com/kbap/common/domain/review/ReviewJpaRepositoryTest.kt` — `findFoodReviewPage` excludedIds 오버로드: 제외 id 미포함·커서/정렬 유지. 컴파일 실패 = Red
- [ ] T011 [P] [US2] [Red] 목록 숨김 MockMvc 테스트 작성: `api/src/test/kotlin/com/kbap/api/review/ReviewListControllerTest.kt` 보강 — ① A 신고 → A 목록에서 해당 리뷰 제외 ② B 목록엔 그대로 ③ 한 음식에서 여러 건 신고 시 전부 제외 ④ 제외로 페이지가 PAGE_SIZE 미만이어도 hasNext·nextCursor 는 조회 row 기준 규약 유지(재조회 없음) ⑤ 신고 이력 없는 회원 결과 불변. 실행해 Red 확인

### Implementation for User Story 2

- [ ] T012 [US2] [Green] `common/src/main/kotlin/com/kbap/common/domain/review/ReviewJpaRepository.kt` — 기존 조건 + `and r.id not in :excludedIds` 오버로드 추가(빈 목록은 받지 않는 계약 — 호출부가 분기, research R3). T010 Green 확인
- [ ] T013 [US2] [Green] 서비스·컨트롤러 연결: `api/src/main/kotlin/com/kbap/api/review/ReviewService.kt` — `getFoodReviewPage(foodId, countryCode, cursor, viewerMemberId)` 로 확장, `ReportJpaRepository.findTargetIdsByReporterMemberIdAndTargetType(viewerMemberId, REVIEW)` 조회 후 **비면 기존 쿼리·있으면 오버로드** 분기 + `ReviewController.listFoodReviews` 에 `@AuthMemberId memberId` 추가 + `ReviewApi.kt` 시그니처 동기화(애너테이션 없이 타입만)
- [ ] T014 [US2] T011 전체 Green 확인: `./gradlew :api:test --tests "com.kbap.api.review.*" :common:test --tests "com.kbap.common.domain.review.*"` — 통과 후 리팩터

**Checkpoint**: 두 스토리 모두 독립 검증 완료

---

## Phase 5: Polish & Cross-Cutting Concerns

- [ ] T015 전체 빌드·회귀 확인: `./gradlew build` (ArchUnit `ModuleBoundaryTest`·`ErrorCodeStatusTest`·기존 리뷰/시나리오 테스트 포함) — 실패 시 원인 수정
- [ ] T016 quickstart.md 수동 시나리오 검증(local 도커 MySQL): `SHOW CREATE TABLE report` 로 UNIQUE·FK 확인 + 무토큰 401 확인(5번 — 필터 등록 최종 검증)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (Phase 2)**: T001·T002 는 즉시 병행 가능. T003(Red) → T004(Green) → T005. 두 스토리 모두 Phase 2 완료가 선행 조건
- **US1 (Phase 3)**: T006(Red) → T007 → T008 → T009. T001(테이블)·T002(에러 코드)·T004(엔티티) 필요
- **US2 (Phase 4)**: T010·T011(Red, 병행) → T012 → T013 → T014. Phase 2 + (테스트 시나리오상 신고 접수가 필요하므로 실질적으로 US1 완료 후 진행이 자연스러움 — 단 T010/T012 는 US1 무관)
- **Polish (Phase 5)**: 전 스토리 완료 후

### Parallel Opportunities

- T001 ∥ T002 (다른 파일)
- T010 ∥ T011 (common 테스트 vs api 테스트)
- 팀 병행 시 Phase 2 완료 후 US1(api/report)·US2 리포지토리 파트(common/review)는 파일이 겹치지 않음

## Implementation Strategy

**MVP**: Phase 2 → US1 → 검증·배포 가능(신고 접수만으로 앱스토어 UGC 요건 충족). US2 는 그 위에 숨김 경험을 얹는다. task 단위(또는 Red·Green 논리 단위)마다 커밋한다.
