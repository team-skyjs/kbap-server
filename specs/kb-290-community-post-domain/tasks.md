# Tasks: 커뮤니티 게시글 도메인 — 작성/수정/삭제 (KB-290)

**Input**: Design documents from `/specs/kb-290-community-post-domain/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/community-post-api.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 스토리는 실패 테스트(Red) 작성·확인 후 구현(Green)한다. 테스트는 Kotest BehaviorSpec(given/when/then 한국어).

**Organization**: 스토리별 단계 구성 — US1(작성) 만으로 MVP 성립.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 스키마·에러 코드·업로드 purpose 등 전 스토리 공유 기반

- [X] T001 Flyway 마이그레이션 작성 — `api/src/main/resources/db/migration/V<생성시각 timestamp>__community_post_table.sql` (data-model.md DDL: `community_post` 단일 테이블, member FK, 명시 인덱스 없음, 소프트삭제 컬럼. 버전은 파일 생성 시점 로컬 시각 `Vyyyy.MM.dd.HH.mm.ss__`)
- [X] T002 [P] `ErrorCode` 에 COMMUNITY-001~004 추가 — `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt` (`COMMUNITY_POSTING_NOT_FOUND`(400)·`COMMUNITY_POSTING_FORBIDDEN`(403)·`COMMUNITY_IMAGE_NOT_VERIFIED`(400)·`COMMUNITY_FOOD_TAG_INVALID`(400)) — 기존 `ErrorCodeStatusTest` 통과 확인
- [X] T003 [P] `UploadPurpose` 에 `COMMUNITY("community")` 추가 — `api/src/main/kotlin/com/kbap/api/image/UploadPurpose.kt` (+`UploadUrlRequest.purpose` 의 swagger `allowableValues` 하드코딩 목록에 `COMMUNITY` 추가 — `api/src/main/kotlin/com/kbap/api/image/UploadUrlRequest.kt`)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: `community` 컨텍스트 엔티티·리포지토리 — 전 스토리가 의존

**⚠️ CRITICAL**: 이 단계 완료 전 스토리 작업 시작 금지

- [X] T004 [Red] `Posting` 엔티티 단위 테스트 작성·실패 확인 — `common/src/test/kotlin/com/kbap/common/domain/community/model/PostingTest.kt` (BehaviorSpec: 본문 1~2,000자 경계(2,000 허용·2,001 거부·빈 문자열 거부), 사진 ≤4(5장 거부·순서 보존), 음식 태그 ≤3(4개 거부·중복 거부), `update` 재검증+`editedAt` 갱신, `isOwnedBy`)
- [X] T005 [Green] `Posting` 엔티티 구현 — `common/src/main/kotlin/com/kbap/common/domain/community/model/Posting.kt` (`@Table(name="community_post")`, BaseEntity 상속, `content`·`imageRefs` JSON·`foodIds` JSON·`editedAt`, require 검증, 상수 `MAX_CONTENT_LENGTH=2000`/`MAX_IMAGE_COUNT=4`/`MAX_FOOD_TAG_COUNT=3` — T004 통과)
- [X] T006 `PostingJpaRepository` 추가 — `common/src/main/kotlin/com/kbap/common/domain/community/PostingJpaRepository.kt` (`JpaRepository<Posting, Long>` — 파생 쿼리 불필요)
- [X] T007 `ModuleBoundaryTest` 허용 맵에 `"community" to emptySet()` 추가 — `api/src/test/kotlin/com/kbap/api/architecture/ModuleBoundaryTest.kt` (맵 미등록 시 arch 스펙이 실패하므로 엔티티 추가 직후 Red→Green 확인)

**Checkpoint**: `./gradlew :common:test` + `./gradlew :api:test -Dkotest.tags="arch"` 통과 — 스토리 구현 시작 가능

---

## Phase 3: User Story 1 - 회원이 커뮤니티 글을 작성한다 (Priority: P1) 🎯 MVP

**Goal**: `POST /api/v1/community/posts` — 본문 필수(≤2,000자), 사진 ≤4장(첫 장 커버), READY 음식 태그 ≤3개, 회원 전용

**Independent Test**: 회원 토큰으로 본문만/사진 4장/태그 3개 글을 각각 등록해 저장 내용 일치 확인. 제약 위반·게스트·미검증 이미지·잘못된 태그가 각각 구분되는 오류로 거부됨 확인

### Tests for User Story 1 (Red 먼저 — 실패 확인 필수) ⚠️

- [X] T008 [US1] [Red] 작성 API 통합 테스트 작성·실패 확인 — `api/src/test/kotlin/com/kbap/api/community/CommunityControllerTest.kt` (`@SpringBootTest`+MockMvc+Testcontainers, BehaviorSpec: 성공 3형(본문만·사진4·태그3 — payload 필드 검증), 본문 누락/2,001자/사진 5장/태그 4개 → 400 공통 검증 오류, 게스트 → 401, 타인·미검증 이미지 key → COMMUNITY-003, 미등록·비READY·중복 foodId → COMMUNITY-004)

### Implementation for User Story 1

- [X] T009 [P] [US1] `CommunityCreateRequest` 작성 — `api/src/main/kotlin/com/kbap/api/community/CommunityCreateRequest.kt` (`content` `@field:NotBlank @field:Size(max=2000)`, `imagePaths` `@field:Size(max=4)`, `foodIds` `@field:Size(max=3)`)
- [X] T010 [P] [US1] `CommunityPostingResponse` 작성 — `api/src/main/kotlin/com/kbap/api/community/CommunityPostingResponse.kt` (postId·content·imageUrls(공개 base-url 조합)·foodIds·editedAt·createdAt, `from(Posting, baseUrl)` 팩토리)
- [X] T011 [US1] `CommunityService.createPosting` 구현 — `api/src/main/kotlin/com/kbap/api/community/CommunityService.kt` (`@Transactional`, `FoodService.getReadyFood` 로 foodIds 전수 검증(중복은 엔티티 require)·`UploadedImageJpaRepository` 소유 검증(ReviewService.verifyImageOwnership 패턴 — 실패 시 COMMUNITY-003/004), `PostingJpaRepository.save`)
- [X] T012 [US1] `CommunityApi` 인터페이스 + `CommunityController` POST 엔드포인트 구현 — `api/src/main/kotlin/com/kbap/api/community/CommunityApi.kt`·`CommunityController.kt` (`@RequestMapping(ApiPaths.V1)`+`@PostMapping("/community/posts")`, `@AuthMemberId`, swagger 문서는 Api 인터페이스에만) — T008 Green 확인
- [X] T013 [US1] `WebConfig` JwtAuthenticationFilter 에 보호 경로 추가 — `api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt` (`${ApiPaths.V1}/community/posts`·`${ApiPaths.V1}/community/posts/*` — 미등록 시 게스트 401 테스트가 Red 로 남는다)

**Checkpoint**: US1 단독 배포 가능 — 글 작성 MVP 완성

---

## Phase 4: User Story 2 - 작성자가 자기 글을 수정한다 (Priority: P2)

**Goal**: `PUT /api/v1/community/posts/{postId}` — 본인만, 전체 교체 의미론, `editedAt` 갱신(표시는 안 함)

**Independent Test**: 글 등록 후 본문·사진·태그 변경 저장 → 반영·`editedAt` 확인. 타인 토큰 403, 없는 글 400 확인

### Tests for User Story 2 (Red 먼저 — 실패 확인 필수) ⚠️

- [X] T014 [US2] [Red] 수정 API 통합 테스트 추가·실패 확인 — `api/src/test/kotlin/com/kbap/api/community/CommunityControllerTest.kt` (성공: 본문·사진·태그 교체+`editedAt` 채워짐·사진/태그 전부 제거 허용, 타인 글 → 403 COMMUNITY-002, 없는·삭제된 글 → COMMUNITY-001, 제약 위반 → 400, 작성과 동일한 이미지/태그 검증 적용)

### Implementation for User Story 2

- [X] T015 [US2] `CommunityService.updatePosting` 구현 — `api/src/main/kotlin/com/kbap/api/community/CommunityService.kt` (`@Transactional`, 조회 실패 COMMUNITY-001·`isOwnedBy` 불일치 COMMUNITY-002, 이미지·태그 재검증 후 `Posting.update` — dirty checking, save 호출 없음)
- [X] T016 [US2] `CommunityApi`·`CommunityController` PUT 엔드포인트 추가 — `api/src/main/kotlin/com/kbap/api/community/CommunityApi.kt`·`CommunityController.kt` (`@PutMapping("/community/posts/{postId}")`) — T014 Green 확인

**Checkpoint**: US1+US2 독립 동작

---

## Phase 5: User Story 3 - 작성자가 자기 글을 삭제한다 (Priority: P3)

**Goal**: `DELETE /api/v1/community/posts/{postId}` — 본인만, 소프트 삭제, 삭제 후 모든 조회 제외

**Independent Test**: 글 등록 후 삭제 → 재수정·재삭제 400, DB 에 DELETED row 보존 확인. 타인 토큰 403 확인

### Tests for User Story 3 (Red 먼저 — 실패 확인 필수) ⚠️

- [X] T017 [US3] [Red] 삭제 API 통합 테스트 추가·실패 확인 — `api/src/test/kotlin/com/kbap/api/community/CommunityControllerTest.kt` (성공: 200 후 같은 글 수정/삭제 시 COMMUNITY-001·리포지토리 `findById` 로 조회 불가(@SQLRestriction)·native/상태 확인으로 row 보존, 타인 글 → 403 COMMUNITY-002)

### Implementation for User Story 3

- [X] T018 [US3] `CommunityService.deletePosting` 구현 — `api/src/main/kotlin/com/kbap/api/community/CommunityService.kt` (`@Transactional`, 소유 검증 후 `BaseEntity.delete()` — dirty checking)
- [X] T019 [US3] `CommunityApi`·`CommunityController` DELETE 엔드포인트 추가 — `api/src/main/kotlin/com/kbap/api/community/CommunityApi.kt`·`CommunityController.kt` (`@DeleteMapping("/community/posts/{postId}")`, `BaseResponse<Unit>`) — T017 Green 확인

**Checkpoint**: 전 스토리 독립 동작

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T020 리팩터링(Refactor) — 검증은 이미 `verifyImageOwnership`·`verifyFoodTags`·`getMyPosting` 로 공유돼 추출할 중복 없음(2줄 호출을 감싸는 래퍼는 불필요한 간접층). 주석은 `Posting.editedAt` 의 설계 제약 1줄만 유지
- [X] T021 전체 빌드·회귀 확인 — `./gradlew build` (Flyway 마이그레이션 → `ddl-auto=validate` 엔티티↔스키마 정합 포함)
- [X] T022 [P] quickstart.md 검증 — 작성/수정/삭제 시나리오는 `CommunityControllerTest`(Testcontainers MySQL·실 Flyway 스키마) 25건이 자동 커버, Swagger 노출은 `build/openapi.json` 스냅샷에서 `POST /api/v1/community/posts`·`PUT|DELETE /api/v1/community/posts/{postId}` + "커뮤니티" 태그 확인. local 부팅 육안 확인은 배포 전 개발자 몫으로 남긴다

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)** → 즉시 시작 가능. T002·T003 은 병렬
- **Phase 2 (Foundational)** → T001(스키마) 이후 권장(T004 는 T001 과 병렬 가능 — 순수 단위 테스트). T004→T005→T006→T007 순
- **Phase 3~5 (US1~US3)** → Phase 2 완료 후. US2·US3 은 US1 의 컨트롤러·서비스 파일에 추가하는 구조라 **순차 진행**(같은 파일 — 병렬 부적합)
- **Phase 6 (Polish)** → 전 스토리 완료 후

### Within Each User Story

- [Red] 테스트 작성·실패 확인 → 구현 → Green 확인 → 커밋 (Red 커밋과 Green 커밋 분리 권장)

### Parallel Opportunities

- T002 ∥ T003 (Setup)
- T004 ∥ T001 (단위 테스트는 스키마 무관)
- T009 ∥ T010 (US1 DTO 두 파일)
- US2·US3 은 같은 파일(Controller/Service/Test) 수정이라 병렬 금지 — 이 기능 내부는 사실상 직렬, 워크트리 병렬은 KB-291~295 태스크 간에서 수행

---

## Implementation Strategy

**MVP = Phase 1 + 2 + 3 (US1 작성)** — 글 작성만으로 FE 목 스왑·피드 태스크(KB-291) 착수 데이터가 생긴다. US2(수정)·US3(삭제)는 같은 파일에 엔드포인트를 얹는 소증분이므로 한 PR 로 묶는 것을 기본으로 하되, 리뷰 부담 시 US1 까지만 먼저 draft PR 가능.
