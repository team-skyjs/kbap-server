# Tasks: 리뷰 목록 조회 API 비회원 공개 (KB-348)

**Input**: Design documents from `/specs/kb-348-guest-review-list/`

**Prerequisites**: plan.md, spec.md, research.md, contracts/review-list-access.md

**Tests**: Test-First NON-NEGOTIABLE (헌법 원칙 I) — 스토리마다 실패 테스트를 먼저 쓰고(Red 확인) 구현한다.

**Organization**: 스토리별 그룹. Setup·Foundational 없음 — 접근 제어 완화 3파일 변경뿐.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: User Story 1 - 비회원이 리뷰 목록을 본다 (Priority: P1) 🎯 MVP

**Goal**: 인증 없이 `GET /api/reviews`(전체·foodId·countryCode·커서)가 200 으로 동작하고, `likedByMe` 는 전부 false 다.

**Independent Test**: 인증 없이 목록 호출 → 회원과 동일한 목록. 회원 호출 → 기존과 완전 동일.

### Tests (Red — 먼저 작성하고 실패 확인) ⚠️

- [ ] T001 [US1] `api/src/test/kotlin/com/kbap/api/review/ReviewListControllerTest.kt` — 비회원 시나리오 추가: 인증 없이 `GET /api/reviews?foodId={id}&lang=ko` 호출 시 200 + 회원과 동일한 목록(최신순), 각 항목 `likedByMe=false`. 회원의 차단 제외·likedByMe 는 기존 시나리오로 불변 확인. 작성 후 Red(401) 확인 (`./gradlew :api:test --tests "*ReviewListControllerTest"`)
- [ ] T002 [P] [US1] `api/src/test/kotlin/com/kbap/api/review/GlobalReviewListControllerTest.kt` — 비회원 전체 피드 시나리오 추가: `foodId` 없이 인증 없이 호출 시 200 + 전체 피드, `countryCode` 필터·커서 페이징도 비회원에서 동작. 작성 후 Red 확인

### Implementation (Green)

- [ ] T003 [US1] `api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt` — `guestExemptions` 에 `GuestExemption("GET", Regex("^${ApiPaths.API}/reviews$"))` 추가
- [ ] T004 [US1] `api/src/main/kotlin/com/kbap/api/review/ReviewController.kt` — `listReviews` 바인딩 `@AuthMemberId memberId: Long` → `@AuthMemberIdOrNull memberId: Long?`
- [ ] T005 [US1] `api/src/main/kotlin/com/kbap/api/review/ReviewService.kt` — `getReviewPage(viewerMemberId: Long?, ...)` 완화: exclusion 은 `viewerMemberId?.let(::excludedMemberIds) ?: listOf(-1L)` 관용구, `toPage` viewer 파라미터 nullable 로. T001·T002 Green 확인
- [ ] T006 [US1] `api/src/main/kotlin/com/kbap/api/review/ReviewApi.kt` — `listReviews` swagger 문서에 비회원 조회 가능·likedByMe false·차단/신고 제외 미적용 명시

**Checkpoint**: 비회원 목록 열람 동작 — MVP 완성

---

## Phase 2: User Story 2 - 회원 전용 동작 401 유지 (Priority: P1)

**Goal**: 같은 경로의 쓰기 계열(POST /api/reviews)·수정·삭제·좋아요·`GET /api/reviews/me` 는 여전히 미인증 401 이다(FR-003).

**Independent Test**: 인증 없이 각 엔드포인트 호출 → 전부 401.

### Tests (Red 불필요 — 보호 유지 회귀이므로 구현과 무관하게 Green 이어야 함) ⚠️

- [ ] T007 [US2] `api/src/test/kotlin/com/kbap/api/review/ReviewControllerTest.kt` — 미인증 401 회귀 시나리오 보강: `POST /api/reviews`·`GET /api/reviews/me` 를 인증 없이 호출하면 401 (기존 401 시나리오와 합쳐 쓰기 계열 전체 커버 확인 — 부족분만 추가). US1 구현 후에도 Green 유지 확인

**Checkpoint**: GET 개방이 다른 보호를 약화시키지 않음이 테스트로 고정됨

---

## Phase 3: Polish & Cross-Cutting

- [ ] T008 [P] 리뷰 스위트 전체 회귀 (`./gradlew :api:test --tests "com.kbap.api.review.*"`) — 회원 동작 불변(SC-003) 확인
- [ ] T009 전체 빌드·테스트 그린 (`./gradlew test`) 후 quickstart.md 수동 검증 절차로 계약 문서와 대조

---

## Dependencies & Execution Order

- Setup/Foundational 없음. US1 → US2 순서 권장(US2 는 US1 구현 이후에도 401 이 유지됨을 고정하는 회귀 장치).
- T001·T002 는 다른 파일이라 병렬 [P]. T003~T005 는 Red 확인 후 순차(같은 요청 경로의 세 층).
- 각 스토리 안에서 Red → Green 절대 순서 (US2 는 예외 — 보호 유지 확인이라 항상 Green 이어야 정상).

## Implementation Strategy

- **MVP = Phase 1** — 이것만으로 기능 완성. Phase 2 는 안전망 고정.
- 단일 세션 직접 구현(CLAUDE.md — 멀티에이전트 금지). 스토리(논리 단위)마다 커밋.
- 총 작업량 반나절 미만 — GuestExemption 선례·KB-334 공용화 덕에 실변경은 3파일 몇 줄.
