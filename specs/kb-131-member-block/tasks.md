# Tasks: 사용자 차단 (Member Block)

**Input**: Design documents from `/specs/kb-131-member-block/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/member-block-api.md, quickstart.md

**Tests**: Test-First **NON-NEGOTIABLE** (헌법 원칙 I) — 모든 스토리는 실패 테스트(Red) 작성·확인 후 구현(Green)한다. 테스트는 전부 Kotest `BehaviorSpec`(given/when/then 한국어).

**Organization**: 스토리별 독립 구현·검증 가능하도록 그룹화. 경로는 워크트리 루트 기준.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup

**Purpose**: 스키마·에러 코드 등 코드 그래프와 독립인 선행물

- [X] T001 [P] Flyway 마이그레이션 작성 — `api/src/main/resources/db/migration/V<생성시점 로컬시각>__member_block_table.sql` (data-model.md 의 SQL 그대로: `member_block` 테이블, UNIQUE(blocker_member_id, blocked_member_id), FK 2개, 파일명 timestamp 는 생성 시점에 채번)
- [X] T002 [P] `ErrorCode` 에 `SELF_BLOCK_FORBIDDEN("BLOCK-001", 400)`·`BLOCK_TARGET_NOT_FOUND("BLOCK-002", 404)` 추가 — `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt` (형식·유일성은 기존 `ErrorCodeStatusTest` 가 자동 검증)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리가 딛는 엔티티·리포지토리·ArchUnit 경계. **⚠️ 이 단계 완료 전 스토리 작업 시작 금지**

- [X] T003 `ModuleBoundaryTest` 의 `allowedDomainDeps` 에 `"block" to setOf("member")` 추가 — `api/src/test/kotlin/com/kbap/api/architecture/ModuleBoundaryTest.kt` (⚠️ `common.domain.block` 패키지 생성과 같은 커밋 — foundContexts 일치 검사가 패키지 등장 즉시 깨짐, quickstart 참조)
- [X] T004 [Red] `MemberBlockJpaRepository` Testcontainers 테스트 작성 후 실패 확인 — `common/src/test/kotlin/com/kbap/common/domain/block/MemberBlockJpaRepositoryTest.kt`: 저장 후 `findBlockedMemberIds` 로 ACTIVE 만 조회, `delete()` 후 일반 조회 미노출, `findAnyByPair`(native)는 DELETED 행도 반환, `findByBlockerMemberIdAndBlockedMemberId` 는 ACTIVE 만
- [X] T005 [Green] `MemberBlock` 엔티티(`@Table("member_block")`, `blockerMemberId`·`blockedMemberId`, BaseEntity 상속) + `MemberBlockJpaRepository`(JPQL projection `findBlockedMemberIds`, native `findAnyByPair` LIMIT 1, 파생 `findByBlockerMemberIdAndBlockedMemberId`) 구현으로 T004 통과 — `common/src/main/kotlin/com/kbap/common/domain/block/model/MemberBlock.kt`, `common/src/main/kotlin/com/kbap/common/domain/block/MemberBlockJpaRepository.kt`

**Checkpoint**: `./gradlew :common:test :api:test --tests "*ModuleBoundaryTest"` 통과 — 스토리 구현 시작 가능

---

## Phase 3: User Story 1 - 차단 후 그 회원의 리뷰가 안 보인다 (Priority: P1) 🎯 MVP

**Goal**: 차단 등록(자기 차단 400·대상 없음 404·중복 멱등) + 음식 리뷰 목록에서 차단 회원 리뷰 제외 + 집계 불변

**Independent Test**: A 가 B 차단 → B 리뷰가 있는 음식의 `GET /api/v1/reviews` 에 B 리뷰 0건, 집계·B 의 화면은 불변

### Tests for User Story 1 (Red — 먼저 작성, 실패 확인) ⚠️

- [X] T006 [P] [US1] [Red] `MemberBlockService.block()` 테스트 — `common/src/test/kotlin/com/kbap/common/domain/block/MemberBlockServiceTest.kt` (Testcontainers): 신규 차단 저장, 자기 차단 → `BLOCK-001`, 미존재·탈퇴 회원 → `BLOCK-002`, 이미 차단 중 재호출 → 예외 없이 성공(행 1개 유지), `getBlockedMemberIds` 반환
- [X] T007 [P] [US1] [Red] 차단 등록 API MockMvc 테스트 — `api/src/test/kotlin/com/kbap/api/block/MemberBlockControllerTest.kt`: POST `/api/v1/members/me/blocks` 200(신규·중복 동일), 400 `BLOCK-001`(자기), 404 `BLOCK-002`(없는 회원), 400 `COMMON-002`(memberId 누락), 401(무토큰)
- [X] T008 [P] [US1] [Red] 리뷰 목록 차단 필터 MockMvc 테스트 — `api/src/test/kotlin/com/kbap/api/review/ReviewBlockFilterTest.kt`: 차단 후 `GET /api/v1/reviews` 에 차단 회원 리뷰 미노출, 차단당한 쪽(B)이 조회하면 A 리뷰 그대로(단방향), 음식 상세 평균 별점·리뷰 수 차단 전후 동일

### Implementation for User Story 1 (Green → Refactor)

- [X] T009 [US1] `MemberBlockService` 구현(T006 통과) — `common/src/main/kotlin/com/kbap/common/domain/block/MemberBlockService.kt`: `block(blockerMemberId, targetMemberId)`(self → BLOCK-001, `MemberService.getMemberOrNull` null → BLOCK-002, `findAnyByPair` 로 부활·no-op·save 분기, `DataIntegrityViolationException` 멱등 폴백 — research R1), `getBlockedMemberIds(memberId)`, 명시적 `@Transactional`
- [X] T010 [US1] `ReviewJpaRepository.findFoodReviewPage` 에 `excludedMemberIds: List<Long>` 파라미터·`and r.memberId not in :excludedMemberIds` 조건 추가 — `common/src/main/kotlin/com/kbap/common/domain/review/ReviewJpaRepository.kt` (`findMemberReviewPage`·`aggregateRating` 무변경 — research R4·R5)
- [X] T011 [US1] `ReviewService.getFoodReviewPage` 에 `viewerMemberId: Long` 추가, `MemberBlockService.getBlockedMemberIds` 조회 후 빈 목록이면 `-1` 센티널로 전달(짧은 라인 주석으로 사유 명시 — research R2) — `api/src/main/kotlin/com/kbap/api/review/ReviewService.kt`
- [X] T012 [US1] `ReviewController.listFoodReviews` 에 `@AuthMemberId memberId` 추가 + `ReviewApi` 인터페이스 파라미터 타입만 동기화(애너테이션 중복 금지) — `api/src/main/kotlin/com/kbap/api/review/ReviewController.kt`, `api/src/main/kotlin/com/kbap/api/review/ReviewApi.kt`
- [X] T013 [US1] 차단 등록 엔드포인트 구현(T007·T008 통과) — `api/src/main/kotlin/com/kbap/api/block/MemberBlockController.kt`(POST `/api/v1/members/me/blocks`, `ApiPaths.V1` 상수, `BaseResponse.ok(Unit)`), `api/src/main/kotlin/com/kbap/api/block/MemberBlockApi.kt`(swagger 문서 애너테이션만), `api/src/main/kotlin/com/kbap/api/block/MemberBlockRequest.kt`(`@field:NotNull memberId`)

**Checkpoint**: US1 단독 검증 — 차단 → 리뷰 미노출 → 집계 불변. MVP 배포 가능

---

## Phase 4: User Story 2 - 차단 해제 후 다시 보인다 (Priority: P2)

**Goal**: 해제(멱등 소프트삭제) + 재차단(DELETED 행 부활, UNIQUE 위반 없음) + 해제 후 리뷰 재노출

**Independent Test**: 차단 → 해제 → 리뷰 재노출, 재차단 → 다시 미노출(같은 행 재사용)

### Tests for User Story 2 (Red — 먼저 작성, 실패 확인) ⚠️

- [X] T014 [P] [US2] [Red] `unblock`·재차단 부활 테스트 추가 — `common/src/test/kotlin/com/kbap/common/domain/block/MemberBlockServiceTest.kt`: `unblock` 후 `getBlockedMemberIds` 에서 제외, 차단한 적 없는 대상 `unblock` 예외 없음(멱등), 차단→해제→재차단 시 UNIQUE 위반 없이 동일 행이 ACTIVE 로 부활(총 행 수 1)
- [X] T015 [P] [US2] [Red] 해제 API·재노출 MockMvc 테스트 추가 — `api/src/test/kotlin/com/kbap/api/block/MemberBlockControllerTest.kt`(DELETE `/api/v1/members/me/blocks/{memberId}` 200, 미차단 대상도 200, 401), `api/src/test/kotlin/com/kbap/api/review/ReviewBlockFilterTest.kt`(해제 후 리뷰 재노출, 재차단 후 다시 미노출)

### Implementation for User Story 2 (Green → Refactor)

- [X] T016 [US2] `MemberBlockService.unblock(blockerMemberId, targetMemberId)` 구현(T014 통과) — `common/src/main/kotlin/com/kbap/common/domain/block/MemberBlockService.kt`: ACTIVE 행 조회 후 `delete()`(dirty checking), 없으면 no-op, `@Transactional`
- [X] T017 [US2] DELETE 엔드포인트 추가(T015 통과) — `api/src/main/kotlin/com/kbap/api/block/MemberBlockController.kt`, `api/src/main/kotlin/com/kbap/api/block/MemberBlockApi.kt`

**Checkpoint**: US1+US2 — 차단·해제·재차단 전 주기 동작

---

## Phase 5: User Story 3 - 내가 차단한 회원 목록 확인 (Priority: P3)

**Goal**: 차단 목록 조회 — memberId·최신 닉네임·해석된 프로필 이미지 URL, 페이징 없음, 탈퇴 회원 제외

**Independent Test**: B·C 차단 후 `GET /api/v1/members/me/blocks` 가 두 명을 최신 프로필로 반환, 해제·탈퇴 회원 미포함

### Tests for User Story 3 (Red — 먼저 작성, 실패 확인) ⚠️

- [ ] T018 [US3] [Red] 차단 목록 MockMvc 테스트 추가 — `api/src/test/kotlin/com/kbap/api/block/MemberBlockControllerTest.kt`: 차단한 회원 전원 반환(닉네임·profileImageUrl 포함), 빈 목록, 닉네임 변경 후 최신 값 반영, 해제한 회원 미포함, 탈퇴한 차단 대상 미포함, 401

### Implementation for User Story 3 (Green → Refactor)

- [ ] T019 [US3] 목록 엔드포인트 구현(T018 통과) — `api/src/main/kotlin/com/kbap/api/block/BlockedMemberResponse.kt`(memberId·nickname·profileImageUrl), `api/src/main/kotlin/com/kbap/api/block/MemberBlockController.kt`(GET: `getBlockedMemberIds` → `MemberJpaRepository.findAllById` → `ImageUrls.resolve` 조립 — 별도 창구 서비스 금지, research R6), `api/src/main/kotlin/com/kbap/api/block/MemberBlockApi.kt`

**Checkpoint**: 전 스토리 독립 동작

---

## Phase 6: Polish & Cross-Cutting

- [ ] T020 전체 빌드·전 테스트 통과 확인(ArchUnit 포함, Docker 필요) — `./gradlew build` (quickstart.md 검증 명령)
- [ ] T021 quickstart.md 수동 시나리오(1~6) 확인 후 리팩터링 여지 정리(중복 픽스처 추출 등) — 커밋은 작업 단위별로 이미 완료됐는지 점검

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: 의존 없음 — T001·T002 병렬 가능
- **Phase 2 (Foundational)**: T001 완료 후(테이블이 있어야 Testcontainers 통과). T003 은 T005 와 같은 커밋. T004(Red) → T005(Green) 순서 강제
- **Phase 3 (US1)**: Phase 2 완료 후. T006·T007·T008 병렬(Red) → T009 → T010 → T011 → T012 → T013(Green)
- **Phase 4 (US2)**: US1 완료 후(컨트롤러·필터 테스트가 US1 산출물 위에 쌓임)
- **Phase 5 (US3)**: Phase 2 이후 가능하나 컨트롤러 파일 공유로 US2 뒤 순차 권장
- **Phase 6 (Polish)**: 전 스토리 완료 후

### Within Each User Story

- Red(테스트 작성·실패 확인) → Green(최소 구현) → Refactor. 테스트 없는 구현 금지(헌법 I)
- 같은 파일을 만지는 task 는 순차(T009→T011, T013→T17→T019 의 컨트롤러 누적)

### Parallel Opportunities

- T001 ∥ T002 (Setup)
- T006 ∥ T007 ∥ T008 (US1 Red — 서로 다른 테스트 파일)
- T014 ∥ T015 (US2 Red)

---

## Implementation Strategy

**MVP = Phase 1~3 (US1)**: 차단 + 리뷰 미노출만으로 앱스토어 심사 요건의 핵심이 선다. 완료 시점에 단독 검증·배포 가능.

이후 US2(해제) → US3(목록) 순차 증분. 각 checkpoint 에서 `./gradlew :common:test :api:test` 로 검증하고 작업 단위별 커밋. `/speckit-implement` 또는 `tdd-harness-orchestrator` 로 실행하며, test-writer 가 Red 를 실제 실행으로 확인한 뒤 implementer 가 Green 을 진행한다.
