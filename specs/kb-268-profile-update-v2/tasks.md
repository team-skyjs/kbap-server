# Tasks: 프로필 수정 국가 코드 변경 불가 — v2 프로필 수정 API 신설

**Input**: Design documents from `/specs/kb-268-profile-update-v2/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/profile-update-v2.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 각 스토리는 실패 테스트 작성·Red 확인 후 구현한다.

**Organization**: 스토리별 그룹. 파일 경로는 워크트리 루트(`.claude/worktrees/kb-268-profile-update-v2/`) 기준 상대 경로.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

없음 — 기존 `:api` 모듈에 파일만 추가하는 작업이라 프로젝트 초기화·의존성 추가가 없다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: v2 경로의 단일 출처 상수. US1 의 모든 코드가 참조한다.

- [X] T001 `ApiPaths` 에 `const val V2 = "/api/v2"` 상수 추가 — api/src/main/kotlin/com/kbap/api/core/ApiPaths.kt

**Checkpoint**: `ApiPaths.V2` 참조 가능 — US1 착수 가능

---

## Phase 3: User Story 1 - 신버전 앱 사용자는 프로필을 수정해도 국적이 바뀌지 않는다 (Priority: P1) 🎯 MVP

**Goal**: `PATCH /api/v2/members/me/profile` 신설 — countryCode 입력 수단이 없고 어떤 요청으로도 국적 불변.

**Independent Test**: 온보딩 마친 회원이 v2 로 닉네임 수정 → 닉네임 반영·국적 온보딩 값 그대로. countryCode 를 JSON 에 끼워 보내도 무시.

### Tests for User Story 1 (REQUIRED — Test-First: write FIRST, ensure FAIL) ⚠️

- [X] T002 [US1] `MemberV2ControllerTest` 작성(BehaviorSpec + `@SpringBootTest`/MockMvc + Testcontainers, given/when/then 한국어) 후 **Red 확인** — api/src/test/kotlin/com/kbap/api/member/MemberV2ControllerTest.kt. 시나리오: (1) 닉네임 수정 → 200·닉네임 변경·국적 불변, (2) 요청 JSON 에 `countryCode` 포함 → 200·무시되어 국적 불변, (3) 기피성분·프로필이미지·맵기 수정 → 각 반영·국적 불변, (4) 토큰 없이 호출 → 401 (JWT 필터 등록 검증)

### Implementation for User Story 1

- [X] T003 [P] [US1] `ProfileUpdateV2Request` 생성 — countryCode 없는 4필드(nickname·avoidanceSubstanceCodes·profileImageUrl·spicinessPreference) + `toInput(memberId)` 에서 `ProfileUpdateInput(countryCode = null, ...)` 고정 — api/src/main/kotlin/com/kbap/api/member/ProfileUpdateV2Request.kt
- [X] T004 [P] [US1] `MemberV2Api` swagger 문서 인터페이스 생성(`@Tag`·`@Operation`·`@SecurityRequirement` 만 — 파라미터 애너테이션 규약 준수) — api/src/main/kotlin/com/kbap/api/member/MemberV2Api.kt
- [X] T005 [US1] `MemberV2Controller` 구현 — `@RequestMapping(ApiPaths.V2 + "/members")` + `@PatchMapping("/me/profile")`, 기존 `MemberService.updateProfile` 재사용, `ResponseEntity<BaseResponse<Unit>>` 반환 — api/src/main/kotlin/com/kbap/api/member/MemberV2Controller.kt
- [X] T006 [US1] `WebConfig` JWT 인증 필터에 `"${ApiPaths.V2}/members/*"` 패턴 등록 — api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt
- [X] T007 [US1] T002 테스트 **Green 확인**(`./gradlew :api:test --tests "com.kbap.api.member.MemberV2ControllerTest"`) 후 리팩터링

**Checkpoint**: v2 단독 동작 — MVP 완성

---

## Phase 4: User Story 2 - 1.0.0 앱 사용자는 기존과 동일하게 프로필을 수정할 수 있다 (Priority: P2)

**Goal**: v1 계약·동작 불변 보장 (1.0.0 앱 호환 회귀 방어).

**Independent Test**: v1 로 기존 요청 형태 그대로 수정 → 기존과 동일 동작(국적 변경 포함).

### Tests for User Story 2 (REQUIRED — Test-First) ⚠️

- [X] T008 [US2] 기존 `MemberControllerTest` 의 v1 프로필 수정 커버리지 확인, **v1 으로 countryCode 변경이 여전히 가능함**을 고정하는 시나리오가 없으면 추가 후 Red→Green — api/src/test/kotlin/com/kbap/api/member/MemberControllerTest.kt

### Implementation for User Story 2

- 구현 없음 — v1 소스는 파일 단위 불변(`MemberController`·`ProfileUpdateRequest`·`MemberApi` 수정 금지)이 곧 구현이다. T008 테스트가 이를 고정한다.

**Checkpoint**: v1·v2 공존, 양쪽 독립 검증 완료

---

## Phase 5: Polish & Cross-Cutting Concerns

- [X] T009 전체 빌드·ArchUnit 포함 검증 `./gradlew build` (컨트롤러 `/api/v` 경로 검사 통과 확인) + quickstart.md 수동 시나리오 점검, Swagger UI 에 v2 노출 확인

---

## Dependencies & Execution Order

- **T001 (Foundational)** → US1 전부가 의존 (`ApiPaths.V2` 참조)
- **US1 내부**: T002(Red) → T003·T004[P] → T005(T003·T004 의존) → T006 → T007(Green)
- **US2 (T008)**: T001 과 무관, 언제든 독립 수행 가능 — v1 파일만 다룸
- **T009**: 전체 완료 후

### Parallel Opportunities

- T003 ∥ T004 (서로 다른 신규 파일, 의존 없음)
- T008 은 US1 전체와 병렬 가능 (다른 파일)

---

## Implementation Strategy

**MVP = US1** (T001→T002→…→T007). US2(T008)는 회귀 방어 테스트 한 건이라 이어서 바로 처리하고, T009 로 마감한다. 단일 세션에서 순차 진행이 가장 저렴하다 — 총 9개 태스크, 신규 소스 3파일 + 기존 2파일 한 줄씩.
