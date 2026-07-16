# Tasks: 맵기 선호 미설정(스킵) 허용 — -1 센티널

**Input**: Design documents from `/specs/kb-158-spiciness-skip/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/member-api.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 스토리는 실패 테스트(Red) 작성·확인 후 구현(Green)한다.

**Organization**: 스토리별 그룹. 단, 세 스토리가 같은 값 정책(`MemberProfile` 허용 집합) 위의 다른 단면이라 핵심 Green(T004)은 US1 에 있고 US2 는 대부분 그 변경으로 함께 통과된다 — US2·US3 의 Red 가 "이미 Green" 이면 그 자체가 검증이다.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup (Shared Infrastructure)

없음 — 신규 모듈·의존성·마이그레이션 0. 기존 member 도메인 위의 정책 변경.

---

## Phase 2: Foundational (Blocking Prerequisites)

없음 — 모든 전제(도메인 모델·DTO·엔드포인트·에러 체계)가 KB-147 로 이미 존재한다.

---

## Phase 3: User Story 1 - 온보딩에서 맵기 선호 건너뛰기 (Priority: P1) 🎯 MVP

**Goal**: 온보딩에서 맵기 선호 미전송·-1 명시 모두 미설정(-1)으로 저장되고, 조회 시 -1 이 반환된다.

**Independent Test**: 맵기 없이(또는 -1) 온보딩 완료 → `GET /api/v1/members/me/profile` 가 -1 반환.

### Tests for User Story 1 (Red — 작성 후 반드시 실패 확인) ⚠️

- [X] T001 [P] [US1] MemberProfileTest 보강 — `empty()` 기본값 -1, `of(spiciness=-1)` 허용, 미설정 유지 시나리오. 기존 "기본 5" 기대 테스트를 새 정책으로 갱신. `domain/member/src/test/kotlin/com/kbap/domain/member/model/MemberProfileTest.kt`
- [X] T002 [P] [US1] MemberTest 보강 — `completeOnboarding(spicinessPreference=null)` → profile -1, `-1` 명시 → -1. `domain/member/src/test/kotlin/com/kbap/domain/member/model/MemberTest.kt`
- [X] T003 [P] [US1] MemberControllerTest 보강 — 온보딩 요청에서 spicinessPreference 생략 → 조회 -1 / -1 명시 → 조회 -1 / 0~10 전송 → 그 값(기존 회귀). `app/api/src/test/kotlin/com/kbap/app/api/member/MemberControllerTest.kt`

### Implementation for User Story 1 (Green → Refactor)

- [X] T004 [US1] MemberProfile 값 정책 변경 — `DEFAULT_SPICINESS_PREFERENCE(5)` 삭제, `SPICINESS_UNSET = -1` 도입, `init` require·`validatedSpiciness` 를 `== SPICINESS_UNSET || in SPICINESS_RANGE` 로 확장, `empty()` 기본 -1. `MemberProfileJson` 기본값 참조를 `SPICINESS_UNSET` 으로 갱신. `domain/member/src/main/kotlin/com/kbap/domain/member/model/MemberProfile.kt`, `MemberProfileJson.kt`
- [X] T005 [US1] Green 확인 — `./gradlew :domain:member:test` + `:app:api:test --tests "...MemberControllerTest"` 전체 통과(구 5 기대 테스트 잔재 정리 포함)

**Checkpoint**: 온보딩 스킵이 end-to-end 로 동작 — MVP.

---

## Phase 4: User Story 2 - 프로필 수정에서 "설정 안 함"으로 되돌리기 (Priority: P2)

**Goal**: 프로필 수정에서 -1 명시 전송=미설정 복귀, 미전송=기존 값 유지(부분 수정 규약 불변).

**Independent Test**: 맵기 5 회원이 -1 전송 → 조회 -1 / 맵기 생략 수정 → 기존 값 유지.

### Tests for User Story 2 (Red — T004 로 이미 통과라면 그대로 검증 완료로 기록) ⚠️

- [X] T006 [P] [US2] MemberProfileTest `updatedWith` 보강 — `spicinessPreference=-1` 명시 → -1 로 교체(미설정 복귀), `null` → 기존 값 유지, 미설정(-1) 상태에서 0~10 전송 → 교체. `domain/member/src/test/kotlin/com/kbap/domain/member/model/MemberProfileTest.kt`
- [X] T007 [P] [US2] MemberControllerTest 보강 — 수정에서 -1 전송 → 조회 -1, 맵기 생략(다른 필드만) 수정 → 맵기 유지. `app/api/src/test/kotlin/com/kbap/app/api/member/MemberControllerTest.kt`

### Implementation for User Story 2

- [X] T008 [US2] Green 확인 — T004 의 허용 집합 확장으로 통과되는지 실행 검증, 미통과 지점만 보완(예상: 추가 구현 0)

**Checkpoint**: 설정 ↔ 미설정 왕복이 완결.

---

## Phase 5: User Story 3 - 잘못된 맵기 값 거절 (Priority: P3)

**Goal**: -1·0~10 외 값은 MEMBER-009 거절, 메시지가 -1(미설정) 허용을 반영.

**Independent Test**: -2·11 전송 → 400 + MEMBER-009 + 갱신된 메시지, 프로필 무변경.

### Tests for User Story 3 (Red) ⚠️

- [X] T009 [P] [US3] 경계 거절 테스트 보강 — MemberProfileTest: `of/updatedWith(-2·11)` → `BusinessException(MEMBER-009)` / MemberControllerTest: 온보딩·수정에 -2·11 → 400 + code MEMBER-009 + message 에 "-1(미설정)" 포함, 저장 무변경. `domain/member/src/test/kotlin/com/kbap/domain/member/model/MemberProfileTest.kt`, `app/api/src/test/kotlin/com/kbap/app/api/member/MemberControllerTest.kt`

### Implementation for User Story 3

- [X] T010 [US3] ErrorCode MEMBER-009 메시지 갱신 — "맵기 선호는 -1(미설정) 또는 0~10 사이여야 합니다". `core/src/main/kotlin/com/kbap/core/error/ErrorCode.kt`

**Checkpoint**: 값 계약(허용 집합 + 에러 메시지) 완결.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T011 [P] MemberApi Swagger 문구 갱신 — 온보딩·프로필 수정·조회의 spiciness 설명을 contracts/member-api.md 대로(-1 계약, "제거는 없다" 문구 교체, 조회 "미설정이면 -1"). `app/api/src/main/kotlin/com/kbap/app/api/member/MemberApi.kt`
- [X] T012 전체 검증 — `./gradlew build` (ArchUnit 포함) 통과 + quickstart.md 검증 명령 수행

---

## Dependencies & Execution Order

- **Setup/Foundational**: 없음 — 바로 US1 시작.
- **US1 (P1)**: T001·T002·T003 [P] 병렬 작성 → Red 확인 → T004 → T005. 핵심 Green 이 여기 있다.
- **US2 (P2)**: T006·T007 [P] — US1 과 같은 파일(MemberProfileTest·MemberControllerTest)을 만지므로 US1 뒤 순차 권장. T008 은 실행 검증.
- **US3 (P3)**: T009 → T010. T010(ErrorCode)은 독립 파일이라 T004 와 병렬 가능하나, 메시지 assert 가 있는 T009 뒤에 두어 Red 를 보전한다.
- **Polish**: T011 은 언제든 [P](문서 애너테이션만), T012 는 마지막.

### Parallel Opportunities

- T001·T002·T003 (서로 다른 테스트 파일 — MemberProfileTest / MemberTest / MemberControllerTest)
- T011 (MemberApi 문구) ∥ US2·US3 테스트 작업

## Implementation Strategy

**MVP = US1** (T001~T005): 온보딩 스킵 저장·조회가 동작하면 배포 가능한 증분. 이후 US2(T006~T008)·US3(T009~T010) 순차 — 한 사이클에 이어서 진행해도 총 변경량이 작아(main 3파일) 리뷰 단위는 하나로 묶는다.
