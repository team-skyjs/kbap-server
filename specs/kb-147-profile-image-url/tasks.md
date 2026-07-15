# Tasks: 프로필 사진 URL·맵기 선호 필드 추가 (KB-147)

**Input**: Design documents from `specs/kb-147-profile-image-url/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/profile-image-api.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 스토리는 실패 테스트(Red) 작성·확인 후 구현(Green)한다. 테스트는 Kotest BehaviorSpec, 한국어 given/when/then.

**Organization**: 스토리별 그룹핑. 단, 세 스토리 모두 같은 파일들(`MemberService`·DTO)을 수정하므로 **스토리 간 병렬 작업은 불가** — P1 → P2 → P3 순차 진행이 전제다(파일 충돌 방지).

## Format: `[ID] [P?] [Story] Description`

## Path Conventions

kbap 멀티모듈 — `:core`(`core/`), `:domain:member`(`domain/member/`), `:app:api`(`app/api/`). 신규 파일 0건, 전부 기존 파일 수정.

---

## Phase 1: Setup

없음 — 기존 모듈·빌드 구성 그대로 사용(신규 의존·프로젝트 구조 변경 0건).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 전 스토리의 검증 로직이 공유하는 에러 코드.

- [x] T001 `ErrorCode` 에 `INVALID_PROFILE_IMAGE_URL("MEMBER-008", 400, "프로필 사진 URL 형식이 올바르지 않습니다")`·`INVALID_SPICINESS_PREFERENCE("MEMBER-009", 400, "맵기 선호는 0~10 사이여야 합니다")` 추가 — `core/src/main/kotlin/com/kbap/core/error/ErrorCode.kt` (형식·유일성은 기존 `ErrorCodeStatusTest` 가 자동 검증 — 통과 확인)

**Checkpoint**: 에러 코드 준비 완료 — US1 시작 가능.

---

## Phase 3: User Story 1 - 온보딩에서 프로필 사진·맵기 등록 후 프로필에서 확인 (Priority: P1) 🎯 MVP

**Goal**: 온보딩 요청에 `profileImageUrl`·`spicinessPreference`(둘 다 선택) 수용 + 검증, 내 프로필 조회 응답에 노출. 미전송 시 사진 null·맵기 기본 5.

**Independent Test**: 온보딩에 URL·맵기 포함 호출 → 프로필 조회 응답에 동일 값. 없이 온보딩 → 조회 시 사진 null·맵기 5.

### Tests for User Story 1 (Red — 작성 직후 반드시 실패 확인) ⚠️

- [x] T002 [US1] `MemberServiceTest` 에 온보딩·조회 시나리오 추가 후 **실패(Red) 확인** — `domain/member/src/test/kotlin/com/kbap/domain/member/MemberServiceTest.kt`: (1) URL 포함 온보딩 → `getMyProfile` 에 동일 URL (2) URL 없이/빈 문자열로 온보딩 → null (3) 불합격 URL(http 스킴·URI 파싱 불가·호스트 없음·512자 초과) → `MEMBER-008` (4) 허용 호스트 목록 설정 시 목록 밖 호스트 → `MEMBER-008`, 목록 안 호스트 → 통과 (5) 허용 목록 빈 값(기본) → 형식만 검증 (6) 맵기 7 포함 온보딩 → 조회 시 7 (7) 맵기 미전송 온보딩 → 조회 시 기본 5 (8) 범위 밖 맵기(11·-1) → `MEMBER-009` + 아무것도 저장 안 됨
- [x] T003 [P] [US1] `MemberControllerTest` 에 MockMvc 시나리오 추가 후 **실패(Red) 확인** — `app/api/src/test/kotlin/com/kbap/app/api/member/MemberControllerTest.kt`: 온보딩 요청 `profileImageUrl`·`spicinessPreference` 포함 → 200 + 조회 응답에 두 값, 미포함 온보딩 → 조회 응답 `profileImageUrl: null`·`spicinessPreference: 5`, 불합격 URL → 400 `MEMBER-008`, 범위 밖 맵기 → 400 `MEMBER-009`

### Implementation for User Story 1 (Green → Refactor)

- [x] T004 [US1] `MemberProfile` 에 `profileImageUrl: String?` 추가(`of`/`empty` 반영) — `domain/member/src/main/kotlin/com/kbap/domain/member/model/MemberProfile.kt`
- [x] T005 [US1] `MemberProfileJson` 에 `profileImageUrl: String? = null` 추가 + `toDomain`/`from` 매핑 — `domain/member/src/main/kotlin/com/kbap/domain/member/model/MemberProfileJson.kt` (기본값 null — 기존 row 키 부재 호환, 마이그레이션 없음)
- [x] T006 [P] [US1] `MemberProfileInput`·`MyProfileResult` 에 `profileImageUrl`·`spicinessPreference` 필드 추가 — `domain/member/src/main/kotlin/com/kbap/domain/member/dto/MemberProfileInput.kt`, `.../dto/MyProfileResult.kt` (Input 은 둘 다 nullable 기본 null, Result 는 사진 `String?`·맵기 `Int`)
- [x] T007 [US1] `MemberService` — `@Value("\${kbap.member.profile-image-allowed-hosts:}")` 허용 호스트 목록 생성자 주입 + `validatedImageUrl`(trim → blank 는 null, 길이 ≤512, `java.net.URI` https·호스트 검증, 허용 목록 비어 있지 않으면 호스트 정확 일치) + `validatedSpiciness`(0~10 밖 → `MEMBER-009`) + `completeOnboarding` 반영(사진·맵기 — 맵기 null 은 기존 값 유지) — `domain/member/src/main/kotlin/com/kbap/domain/member/MemberService.kt`
- [x] T008 [P] [US1] `OnboardingRequest`(필드 2개+`toInput`)·`MyProfileResponse`(필드 2개+`from`) 반영 + `MemberApi` swagger 필드 문서 갱신 — `app/api/src/main/kotlin/com/kbap/app/api/member/{OnboardingRequest,MyProfileResponse,MemberApi}.kt`
- [x] T009 [US1] Green 확인: `./gradlew :domain:member:test :app:api:test` 통과 + 리팩터링(중복·이름 정리)

**Checkpoint**: 온보딩 등록 → 조회 노출이 단독 동작(MVP). 기존 클라이언트 무영향.

---

## Phase 4: User Story 2 - 프로필 수정에서 사진·맵기 변경 (Priority: P2)

**Goal**: 부분 수정에서 `profileImageUrl`·`spicinessPreference` 전송 시 검증 후 교체, 미전송 시 기존 값 유지.

**Independent Test**: 사진 있는 회원에 새 URL 만 PATCH → 교체·나머지 유지. 사진 필드 없이 PATCH → 사진 유지. 맵기만 PATCH → 맵기 변경·나머지 유지.

### Tests for User Story 2 (Red — 작성 직후 반드시 실패 확인) ⚠️

- [x] T010 [US2] `MemberServiceTest` 에 수정 시나리오 추가 후 **실패(Red) 확인** — `domain/member/src/test/kotlin/com/kbap/domain/member/MemberServiceTest.kt`: (1) 새 URL 전송 → 교체 + 다른 프로필 값 유지 (2) 필드 미전송(null) → 기존 URL 유지 (3) 불합격 URL → `MEMBER-008` + 아무 필드도 변경 안 됨 (4) 맵기 9 전송 → 교체 + 나머지 유지 (5) 맵기 미전송 → 기존 맵기 유지 (6) 범위 밖 맵기 → `MEMBER-009` + 아무 필드도 변경 안 됨
- [x] T011 [P] [US2] `MemberControllerTest` 에 MockMvc PATCH 시나리오 추가 후 **실패(Red) 확인** — `app/api/src/test/kotlin/com/kbap/app/api/member/MemberControllerTest.kt`: URL 만 담은 PATCH → 200 + 조회로 교체 확인, 닉네임만 담은 PATCH → 사진·맵기 유지, 맵기만 담은 PATCH → 200 + 조회로 변경 확인, 불합격 URL → 400 `MEMBER-008`, 범위 밖 맵기 → 400 `MEMBER-009`

### Implementation for User Story 2 (Green → Refactor)

- [x] T012 [P] [US2] `ProfileUpdateInput`·`ProfileUpdateRequest` 에 `profileImageUrl: String? = null`·`spicinessPreference: Int? = null` 추가(`toInput` 반영) — `domain/member/src/main/kotlin/com/kbap/domain/member/dto/ProfileUpdateInput.kt`, `app/api/src/main/kotlin/com/kbap/app/api/member/ProfileUpdateRequest.kt`
- [x] T013 [US2] `MemberService.updateProfile` 병합에 반영: 사진 null=유지(current)·값=`validatedImageUrl` 후 교체, 맵기 null=유지·값=`validatedSpiciness` 후 교체 — `domain/member/src/main/kotlin/com/kbap/domain/member/MemberService.kt`
- [x] T014 [US2] Green 확인: `./gradlew :domain:member:test :app:api:test` 통과 + 리팩터링

**Checkpoint**: US1·US2 독립 검증 가능 — 교체·유지 규칙 준수(사진·맵기).

---

## Phase 5: User Story 3 - 프로필 사진 제거 (Priority: P3)

**Goal**: 부분 수정에 빈 문자열 전송 = 제거(null 복귀). 미전송=유지와 명확히 구분.

**Independent Test**: 사진 있는 회원에 `profileImageUrl: ""` PATCH → 조회 시 null.

### Tests for User Story 3 (Red — 작성 직후 반드시 실패 확인) ⚠️

- [x] T015 [US3] `MemberServiceTest` 에 제거 시나리오 추가 후 **실패(Red) 확인** — `domain/member/src/test/kotlin/com/kbap/domain/member/MemberServiceTest.kt`: 빈 문자열(공백 포함) 전송 → 사진 null + 다른 프로필 값 유지
- [x] T016 [P] [US3] `MemberControllerTest` 에 MockMvc 제거 시나리오 추가 후 **실패(Red) 확인** — `app/api/src/test/kotlin/com/kbap/app/api/member/MemberControllerTest.kt`: `{"profileImageUrl":""}` PATCH → 200 + 조회 응답 null

### Implementation for User Story 3 (Green → Refactor)

- [x] T017 [US3] `MemberService.updateProfile` 에 3분법 완성: blank → null(제거) 분기 — `domain/member/src/main/kotlin/com/kbap/domain/member/MemberService.kt` (T007 의 `validatedImageUrl` blank→null 시맨틱과 정합 확인)
- [x] T018 [US3] Green 확인: `./gradlew :domain:member:test :app:api:test` 통과 + 리팩터링

**Checkpoint**: 3개 스토리 전부 독립 검증 가능.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T019 [P] prod(·staging) 프로필에 `kbap.member.profile-image-allowed-hosts: <CloudFront CDN 호스트>` 등록 — `app/api/src/main/resources/application-prod.yml`(·`application-staging.yml`) (실제 CDN 도메인 값은 사용자/인프라 확인 필요 — local·dev·테스트는 미설정 유지)
- [ ] T020 전체 회귀 + 수동 검증: `./gradlew build` 통과, `specs/kb-147-profile-image-url/quickstart.md` 시나리오 확인, Swagger UI 에서 3개 API 필드 문서 노출 확인

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 2 (T001)**: 선행 없음 — 즉시 시작, US1 의 검증 테스트가 참조하므로 US1 전 완료
- **Phase 3 (US1)**: T001 후. 내부 순서: T002·T003(Red, 병렬) → T004 → T005 → T006·T007·T008 → T009
- **Phase 4 (US2)**: US1 완료 후 (같은 파일 `MemberService`·`MemberServiceTest` 수정 — 병렬 불가). T010·T011(Red) → T012 → T013 → T014
- **Phase 5 (US3)**: US2 완료 후 (US2 의 `updateProfile` 병합 로직 위에 제거 분기). T015·T016(Red) → T017 → T018
- **Phase 6**: 전 스토리 완료 후. T019 는 언제든 가능([P])

### Parallel Opportunities

- 각 스토리 내 Red 테스트 2건(도메인·MockMvc — 다른 파일): T002∥T003, T010∥T011, T015∥T016
- US1 구현 중 T006(domain dto)∥T008(api dto) — 다른 파일. T004→T005 는 순차(JSON 이 Profile 참조)
- T019(yml)는 코드와 무관 — 아무 때나

---

## Implementation Strategy

**MVP = Phase 2 + Phase 3 (US1)**: 온보딩 등록 + 조회 노출만으로 배포 가능한 증분(수정·제거는 이후 증분). 이후 US2 → US3 순차 — 세 스토리가 같은 파일을 만지므로 한 스토리씩 Red→Green→Refactor 로 완결하고 커밋한다(작업/논리 단위 커밋 — 헌법 Development Workflow).

## Notes

- 마이그레이션 태스크 없음 — 사진은 기존 `member.profile` JSON 컬럼 필드 추가(research R1), 맵기는 저장·도메인 모델 기존재(입출력 경로만 개방, research R6)
- `MemberService` 생성자에 `@Value` 파라미터 추가 시 기존 테스트의 서비스 인스턴스화 지점(직접 생성하는 곳이 있으면) 컴파일 영향 확인 — 같은 모듈 테스트는 internal 접근 가능
- 검증 시맨틱 단일 출처는 `validatedImageUrl`(blank→null, 형식·호스트 검증)·`validatedSpiciness`(0~10) 각 하나 — 온보딩·수정 공용
- 맵기 범위 검증은 서비스에서 `MEMBER-009` 로 선행 거절 — `MemberProfile` init 의 `require`(IAE → 500)는 최후 방어선으로 유지
