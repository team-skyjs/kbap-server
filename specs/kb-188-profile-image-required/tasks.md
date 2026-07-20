# Tasks: 프로필 사진 필수화 — null·빈 문자열 불가, 미설정은 기본 이미지 경로 저장

**Input**: Design documents from `/specs/kb-188-profile-image-required/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/member-api.md

**Tests**: Test-First **NON-NEGOTIABLE**(헌법 I) — 각 스토리는 실패 테스트(Red) 확인 후 구현(Green)한다.

**Organization**: 스토리별 독립 구현·검증. 파일 경로는 워크트리 루트 기준.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

없음 — 기존 모듈·테스트 인프라 그대로 사용(신규 모듈·의존성·설정 0).

## Phase 2: Foundational

없음 — 스토리를 막는 공통 선행 작업 없음.

---

## Phase 3: User Story 1 - 온보딩 시 프로필 사진 필수 전송 (Priority: P1) 🎯 MVP

**Goal**: 온보딩 요청의 `profileImageUrl` 을 non-null 타입으로 강제 — 미전송·null 이면 400 `COMMON-002`, 유효 경로면 저장·CDN 조합 노출.

**Independent Test**: 온보딩 미전송 → 400 COMMON-002 / 기본 이미지 경로 전송 → 200 + 조회 시 `https://cdn.test/images/default/...` 노출.

### Tests for User Story 1 (Red 먼저) ⚠️

- [ ] T001 [US1] `app/api/src/test/kotlin/com/kbap/app/api/member/MemberControllerTest.kt` — `validBody()` 에 `profileImageUrl`(기본 이미지 경로) 추가하고, 온보딩 시나리오 추가: ① `profileImageUrl` 미전송 → 400 + `COMMON-002` ② 기본 이미지 경로 전송 → 200 + 프로필 조회 `profileImageUrl == "https://cdn.test/images/default/profile/profile-default-512.png"`. 실행해 **실패(Red) 확인** (`./gradlew :app:api:test --tests "...MemberControllerTest"`)

### Implementation for User Story 1

- [ ] T002 [US1] non-null 체인 전파 — `app/api/src/main/kotlin/com/kbap/app/api/member/OnboardingRequest.kt` `profileImageUrl: String? = null` → `String`(기본값 없음), `domain/member/src/main/kotlin/com/kbap/domain/member/dto/MemberProfileInput.kt` 동일, `domain/member/src/main/kotlin/com/kbap/domain/member/model/Member.kt` `completeOnboarding(profileImageUrl: String?)` → `String`
- [ ] T003 [P] [US1] `app/api/src/test/kotlin/com/kbap/app/api/scenario/ScenarioApiDriver.kt` — `온보딩한다()` 에 `profileImageUrl: String = "/images/default/profile/profile-default-512.png"` 파라미터 추가·요청 본문 포함 (시나리오 테스트 컴파일·통과 유지)
- [ ] T004 [P] [US1] `app/api/src/main/kotlin/com/kbap/app/api/member/MemberApi.kt` — 온보딩 문서에서 `profileImageUrl` 을 선택 필드 → 필수 필드로 이동, 기본 이미지 경로 계약(미설정 시 클라이언트가 기본 경로 명시 전송) 서술·예시 갱신
- [ ] T005 [US1] T001 테스트 **통과(Green) 확인** 후 `:app:api:test` 전체(시나리오 포함) 회귀 실행

**Checkpoint**: 온보딩 필수화 단독 배포 가능 상태.

---

## Phase 4: User Story 2 - 빈 문자열 "사진 제거" 센티널 폐기 (Priority: P1)

**Goal**: `MemberProfile.validatedImagePath` 의 빈 문자열→null(제거) 센티널을 폐기하고 400 `MEMBER-008` 로 거절 — 온보딩·수정 두 경로가 이 함수를 공유하므로 한 곳 수정으로 동시 커버. 수정 API 의 null=유지 규약은 불변.

**Independent Test**: 온보딩·프로필 수정 각각 빈 문자열/공백 전송 → 400 MEMBER-008, 수정 미전송 → 유지.

### Tests for User Story 2 (Red 먼저) ⚠️

- [ ] T006 [P] [US2] `domain/member/src/test/kotlin/com/kbap/domain/member/model/MemberProfileTest.kt` — `updatedWith(profileImageUrl = "")`·`"   "` 의 기존 "제거(null)" 기대를 `shouldThrow<BusinessException>`(MEMBER-008) 로 교체. 실행해 **실패(Red) 확인**
- [ ] T007 [P] [US2] `app/api/src/test/kotlin/com/kbap/app/api/member/MemberControllerTest.kt` — 기존 "빈 문자열/공백 → 제거(null)" 시나리오(수정 481·494행, 온보딩 561행 부근)를 400 + `MEMBER-008` 기대로 교체, "미전송 → 유지"·"전체 URL → 400" 시나리오는 유지 확인. 실행해 **실패(Red) 확인**

### Implementation for User Story 2

- [ ] T008 [US2] `domain/member/src/main/kotlin/com/kbap/domain/member/model/MemberProfile.kt` — `validatedImagePath` 의 `if (trimmed.isEmpty()) return null` 을 `throw BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_URL)` 로 교체, 반환 타입 `String?` → `String`, `updatedWith` 사진 3분법 주석을 2분법(null=유지·값=검증 후 교체)으로 갱신. T006·T007 **통과(Green) 확인**
- [ ] T009 [P] [US2] `app/api/src/main/kotlin/com/kbap/app/api/member/MemberApi.kt` — 수정 API 문서의 3분법 문구·빈 문자열 예시(220행 부근)를 2분법 + "빈 문자열=400 MEMBER-008" 로 교체

**Checkpoint**: 신규 null 유입 경로 전부 차단.

---

## Phase 5: User Story 3 - 기존 미설정 회원 백필 (Priority: P2)

**Goal**: Flyway 마이그레이션 1건으로 기존 null 행(키 부재·JSON null, 소프트 삭제 포함)에 기본 이미지 경로 백필 — 멱등·순서 독립.

**Independent Test**: 사진 null 회원 생성 → 마이그레이션 SQL 적용 → 조회 시 기본 이미지 완전 URL 노출.

### Tests for User Story 3 (Red 먼저) ⚠️

- [ ] T010 [US3] `app/api/src/test/kotlin/com/kbap/app/api/member/MemberControllerTest.kt` — 백필 시나리오 추가: 온보딩 완료 회원의 `profile` JSON 에서 raw SQL 로 `profileImageUrl` 을 JSON null 화 → 백필 마이그레이션 파일을 **클래스패스 리소스로 로드**(부재 시 명시 실패 assert — 빈 문자열 오진 방지)해 SQL 직접 실행 → 조회 응답 `https://cdn.test/images/default/profile/profile-default-512.png` + 기존 값 보유 행 무변경 검증. given 설명에 버전 번호 금지(research.md R4). 실행해 **실패(Red — 리소스 부재) 확인**

### Implementation for User Story 3

- [ ] T011 [US3] `app/api/src/main/resources/db/migration/V<생성시각 timestamp>__backfill_default_profile_image.sql` 신규 — `UPDATE member SET profile = JSON_SET(profile, '$.profileImageUrl', '/images/default/profile/profile-default-512.png') WHERE JSON_UNQUOTE(JSON_EXTRACT(profile, '$.profileImageUrl')) IS NULL;` (파일명은 파일 생성 시점 로컬 시각, 각 파트 두 자리 zero-pad). T010 의 리소스 경로를 실제 파일명으로 맞추고 **통과(Green) 확인**

**Checkpoint**: 전 스토리 완료 — 저장값 non-null 계약 완성.

---

## Phase 6: Polish & Cross-Cutting

- [ ] T012 전체 회귀 `./gradlew build` (ArchUnit·시나리오 포함) + quickstart.md §1 명령 통과 확인
- [ ] T013 [P] 커밋 정리 — 스토리/논리 단위 커밋 확인(작업 규약), spec 디렉터리 산출물 포함

---

## Dependencies & Execution Order

- **US1 (Phase 3)**: 선행 없음 — 즉시 시작 가능. T001(Red) → T002 → T003·T004[P] → T005(Green)
- **US2 (Phase 4)**: US1 과 파일이 겹치나(`MemberControllerTest`·`MemberApi`) 논리적으로 독립 — 순차 권장(P1 순서대로). T006·T007[P](Red) → T008(Green) → T009[P]
- **US3 (Phase 5)**: US1·US2 와 독립(마이그레이션+테스트만). 단 `MemberControllerTest` 공유로 순차 권장. T010(Red) → T011(Green)
- **Polish (Phase 6)**: 전 스토리 완료 후

### Parallel Opportunities

- US1 내: T003·T004 (서로 다른 파일, T002 이후)
- US2 내: T006·T007 동시 작성(Red), T009 는 T008 과 병렬 가능
- 스토리 간 병렬은 `MemberControllerTest.kt` 공유 편집 충돌 때문에 비권장 — 단일 세션 순차가 최단 경로

## Implementation Strategy

**MVP**: US1 만으로 배포 가능(신규 온보딩 non-null 보장). US2 로 제거 센티널 폐기, US3 로 기존 행 백필까지 끝나야 "전 행 non-null" 계약 완성. 세 스토리 모두 작아 단일 PR 로 진행하되, 스토리 단위 커밋으로 각 체크포인트를 남긴다.
