# Tasks: 프로필 수정 API 부분 수정 전환 — 미전송 필드는 기존 값 유지

**Input**: Design documents from `/specs/kb-124-partial-profile-update/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/profile-update-api.md](./contracts/profile-update-api.md)

**Tests**: Test-First 는 **NON-NEGOTIABLE**(헌법 원칙 I). 각 스토리는 구현 전에 실패하는 테스트를 먼저 쓰고 Red 를 확인한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(서로 다른 파일, 선행 의존 없음)
- **[Story]**: 소속 사용자 스토리(US1·US2·US3)

## Path Conventions

축약: `app/api/…/member/` = `app/api/src/main/kotlin/com/meogo/app/api/member/`, `application/client/…/member/` = `application/client/src/main/kotlin/com/meogo/application/client/member/`. 테스트는 각 모듈의 `src/test/kotlin/…` 미러.

> **US1·US2 는 같은 코드 변경으로 함께 풀린다.** 요청·입력 타입을 nullable 로 가르고 유스케이스가 병합하면 두 스토리가 동시에 통과한다. 그래서 구현은 US1 단계에 모아 두고, US2 는 **빈 배열 vs 미전송 구분**이라는 US1 이 검증하지 않는 축을 테스트로 추가한다(구현이 옳다면 프로덕션 코드 변경 없이 green 이어야 한다).

---

## Phase 1: Setup

- [X] T001 `./gradlew build` 로 워크트리 기준선이 green 인지 확인한다(이후 Red 가 내 변경 때문임을 보장).

---

## Phase 2: Foundational (Blocking)

**Purpose**: 온보딩과 프로필 수정이 공유할 **필드 단위 검증 함수**를 먼저 뽑아 둔다. 행위 변경이 없는 리팩터라 기존 테스트가 계속 green 이어야 한다(Red 없음).

- [X] T002 `application/client/…/member/MemberProfileUseCase.kt` 의 `validatedProfile(input, member)` 를 **필드 단위 검증 함수 4개**로 분해한다 — `validatedNickname(raw): String`(trim 후 blank 면 `INVALID_NICKNAME`) · `validatedCodes(raw): Set<AvoidanceSubstanceCodeRef>`(카탈로그 검사 + 중복 제거) · `validatedCountry(raw): CountryCode`(`INVALID_COUNTRY_CODE`) · `validatedLanguage(raw): LanguageCode`(정확 일치, `UNSUPPORTED_APP_LANGUAGE`). `completeOnboarding` 은 넷을 모두 호출해 기존과 동일하게 동작한다. **기존 `MemberProfileUseCaseTest` 전부 green 유지**.

**Checkpoint**: 검증 함수가 필드 단위로 재사용 가능해짐.

---

## Phase 3: User Story 1 — 미전송 필드는 유지된다 (P1) 🎯 MVP

**Goal**: 닉네임·국가·언어만 보낸 요청이 기피 성분을 지우지 않는다. 빈 요청은 아무것도 바꾸지 않는다.

**Independent Test**: 기피 성분이 설정된 회원에게 닉네임·국가·언어만 보낸 뒤, 프로필 조회로 기피 성분이 그대로인지 확인.

### Tests (Red 먼저)

- [X] T003 [US1] `application/client/src/test/…/member/MemberProfileUseCaseTest.kt` 에 `given("프로필 부분 수정")` 을 추가한다 — 닉네임·국가·언어만 전달 시 **기피 성분 유지** / 닉네임만 전달 시 나머지 3개 유지 / **아무 필드도 없는 입력** 시 프로필 불변 / 맵기 선호도 보존. Red 확인(현재 `update` 가 `MemberProfileInput` 을 받아 컴파일부터 실패).

### Implementation (Green)

- [X] T004 [US1] `application/client/…/member/dto/ProfileUpdateInput.kt` 를 신규 작성한다 — `memberId: Long`, `nickname: String? = null`, `avoidanceSubstanceCodes: List<String>? = null`, `countryCode: String? = null`, `appLanguage: String? = null`.
- [X] T005 [US1] `app/api/…/member/ProfileUpdateRequest.kt` 를 전 필드 **nullable + 기본값 `null`** 로 바꾸고 `toInput(memberId)` 가 `ProfileUpdateInput` 을 반환하게 한다. **`OnboardingRequest.kt` 는 건드리지 않는다**(전 필드 필수 유지).
- [X] T006 [US1] `application/client/…/member/MemberProfileUseCase.kt` 의 `update` 시그니처를 `update(input: ProfileUpdateInput)` 으로 바꾸고, 기존 프로필을 읽어 필드별로 `input.x?.let { validatedX(it) } ?: current.x` 로 해소한 뒤 `MemberProfile.of(...)` 로 재조립해 저장한다(맵기는 `current.spicinessPreference` 그대로). `@Transactional` 유지. T003 green 확인.

### Integration

- [X] T007 [US1] `app/api/src/test/…/member/MemberControllerTest.kt` 의 `given("프로필 수정")` 에 시나리오를 추가한다 — 온보딩 완료 회원에게 **닉네임·국가·언어만** 보낸 뒤 `GET /me/profile` 로 기피 성분이 유지되는지 확인 / **빈 본문 `{}`** → 200 이고 프로필 불변. Red → green 확인.

**Checkpoint**: 데이터 손실 버그 해소. 단독 배포 가능.

---

## Phase 4: User Story 2 — 기피 성분만 저장 · 빈 배열은 전부 해제 (P1)

**Goal**: 기피 성분만 보낸 요청이 통과하고, 빈 배열과 미전송이 다르게 동작한다.

**Independent Test**: 기피 성분만 담아 요청해 성공하고 나머지 3개가 유지되는지, `[]` 를 보내면 전부 해제되는지 확인.

- [X] T008 [US2] `application/client/src/test/…/member/MemberProfileUseCaseTest.kt` 에 시나리오를 추가한다 — 기피 성분만 전달 시 닉네임·국가·언어 유지 / `avoidanceSubstanceCodes = emptyList()` 전달 시 **전부 해제** / `avoidanceSubstanceCodes = null`(미전송) 시 **유지**. Red 확인.
- [X] T009 [US2] `app/api/src/test/…/member/MemberControllerTest.kt` 에 시나리오를 추가한다 — `{"avoidanceSubstanceCodes":["EGG","MILK"]}` 만 보낸 요청이 200 이고 닉네임·국가·언어가 유지된다 / `{"avoidanceSubstanceCodes":[]}` 로 전부 해제된다. Red 확인.
- [X] T010 [US2] T008·T009 green 확인 — **예상대로 프로덕션 코드 변경 없이 통과**(T005 의 nullable 필드가 `null`≠`emptyList()` 를 이미 구분).

**Checkpoint**: 기피 성분 화면이 단독으로 동작. 빈 배열/미전송 구분 확정.

---

## Phase 5: User Story 3 — 검증은 전달된 필드에만 (P2)

**Goal**: 미전송 필드 때문에 400 이 나지 않고, 전달된 값이 잘못됐을 때만 거절하며 프로필은 하나도 바뀌지 않는다.

**Independent Test**: 잘못된 국가 코드만 담아 요청하면 거절 + 프로필 불변, 유효한 닉네임만 담으면 성공.

- [X] T011 [US3] `application/client/src/test/…/member/MemberProfileUseCaseTest.kt` 에 시나리오를 추가한다 — 잘못된 국가 코드만 전달 시 `INVALID_COUNTRY_CODE` 로 거절되고 **프로필 불변**(저장 미호출) / 공백뿐인 닉네임 전달 시 `INVALID_NICKNAME` / 유효한 닉네임만 전달 시 국가·언어 미전송을 이유로 거절되지 않고 성공 / 온보딩 미완료 회원 수정 시 **완료로 전이되지 않음**. Red → green 확인.
- [X] T012 [US3] `app/api/src/test/…/member/MemberControllerTest.kt` 에 "전달된 값만 무효면 400, 그 외 필드 미전송은 400 사유가 아니다"를 확인하는 시나리오를 추가한다(예: `{"countryCode":"ZZ"}` → 400 / `{"nickname":"새닉"}` → 200). Red → green 확인.

**Checkpoint**: 검증 범위 확정. 잘못된 값이 조용히 저장되지 않는다.

---

## Phase 6: Polish & Cross-Cutting

- [X] T013 [P] `app/api/…/member/MemberApi.kt` 의 `PATCH /me/profile` swagger 를 갱신한다 — 설명에 **"미전송 = 유지, 빈 배열 = 전부 해제"** 를 명시하고 예시 3종(닉네임·국가·언어만 / 기피 성분만 / 기피 성분 빈 배열)을 넣는다. `@ApiResponses` 는 기존대로(200/400/401). **온보딩 쪽 문서는 건드리지 않는다.**
- [X] T014 `./gradlew build` 로 전체 green 을 확인한다 — 특히 **기존 온보딩 테스트(전 필드 필수·무효 입력 400·재제출 400)가 하나도 깨지지 않아야 한다**(FR-007·SC-005 회귀 가드). ArchUnit 포함.
- [ ] T015 [P] Swagger UI(`/swagger-ui/index.html`)에서 `PATCH /api/v1/members/me/profile` 문서가 [contracts/profile-update-api.md](./contracts/profile-update-api.md) 와 일치하는지 확인한다.

---

## Dependencies & Execution Order

```
Phase 1 (T001)
      ▼
Phase 2 (T002)                      ← 검증 함수 분해(리팩터, Red 없음)
      ▼
Phase 3 US1 (T003 → T004,T005[P] → T006 → T007)   🎯 MVP — 구현이 여기 모여 있다
      ▼
Phase 4 US2 (T008,T009[P] → T010)  ─┐  US1 완료 후 서로 독립 — 병렬 가능
Phase 5 US3 (T011, T012)           ─┘  (대부분 테스트 추가)
      ▼
Phase 6 (T013[P], T014, T015[P])
```

- **US2·US3 는 US1 에 의존**한다(부분 수정 구현이 있어야 검증 가능). US1 이 끝나면 둘은 서로 독립이다.
- **파일 충돌 주의**: `MemberProfileUseCase.kt`(T002·T006), `MemberProfileUseCaseTest.kt`(T003·T008·T011), `MemberControllerTest.kt`(T007·T009·T012)는 같은 파일이라 병렬 금지.

## Parallel Opportunities

- Phase 3: T004(신규 Input)와 T005(요청 DTO) — 다른 파일
- Phase 4: T008(단위)과 T009(통합) — 다른 파일
- Phase 6: T013(swagger)·T015(육안 확인)

## Implementation Strategy

**MVP = Phase 1~3 (T001~T007).** 여기까지면 **기피 성분이 조용히 삭제되는 데이터 손실 버그**가 사라진다. US2 는 기피 성분 화면을 살리고 빈 배열/미전송 구분을 못 박으며, US3 는 검증 범위를 고정한다. 셋 다 머지 전에 포함한다.

**최대 위험**: 온보딩 회귀. 온보딩과 수정이 검증 함수를 공유하므로 T002 리팩터와 T006 시그니처 변경이 온보딩의 "전 필드 필수"를 흔들지 않는지 T014 에서 반드시 확인한다.

## 총계

**15개 태스크** — Setup 1 · Foundational 1 · US1 5 · US2 3 · US3 2 · Polish 3.

## 실행 결과 (2026-07-12)

- `MemberProfileUseCaseTest` 22 → **32** (부분 수정 10건 추가), `MemberControllerTest` 15 → **21** (통합 6건 추가). `./gradlew build` green.
- **온보딩 회귀 없음** — 기존 온보딩 시나리오 전부 green(전 필드 필수 유지).
- US2·US3 는 예상대로 **프로덕션 코드 추가 없이** 통과했다(US1 의 nullable 전환 + 병합이 세 스토리를 모두 커버).
- 남은 것: T015(Swagger UI 육안 확인).
