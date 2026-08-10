---

description: "Task list for 온보딩 시 닉네임·프로필 사진 랜덤 지정 (KB-300)"
---

# Tasks: 온보딩 시 닉네임·프로필 사진 랜덤 지정

**Input**: Design documents from `specs/kb-300-onboarding-random-profile/`

**Prerequisites**: [plan.md](./plan.md) · [spec.md](./spec.md) · [research.md](./research.md) · [data-model.md](./data-model.md) · [contracts/member-onboarding.md](./contracts/member-onboarding.md)

> **2026-08-10 개정**: v2 온보딩 엔드포인트 분리안을 철회하고 **v1 온보딩의 `nickname`·`profileImageUrl` 을 nullable 로 완화**하는 설계로 변경했다(사용자 결정, research R1). 아래 [X] 태스크 중 v2 산출물(`OnboardingV2Request`·`MemberV2Controller` 온보딩·`MemberV2ControllerTest` 온보딩 시나리오)은 개정 시점에 제거되고 같은 시나리오가 `OnboardingRequest` 완화 + `MemberControllerTest` 로 재배치됐다. 태스크 본문은 실행 이력으로 남긴다.
>
> **2026-08-10 개정 2~4**: 무조건 nullable 완화 대신 헤더 분기로 바꾸고(개정 2 — 앱 버전 `X-App-Version >= 1.1.0`), 분기 근거를 앱 버전에서 **클라이언트가 선언하는 계약 버전 `X-API-Version`**으로(개정 3 — 앱 버전은 트래킹 관심사), 표기를 **캘린더 버저닝 `yyyy.mm.sprint차수`**로 확정했다(개정 4 — 토스페이먼츠 커스텀, 비호환 변경만 새 버전 릴리즈. research R1). 이번 온보딩 계약은 `2026.08.07` — 이상이면 서버가 랜덤 지정하고, 미전송·이전·형식 오류면 두 필드 필수·누락 시 400 이라는 1.0.0 계약이 그대로다. 버전 파싱·비교는 `api.core.ApiVersion`(단위 테스트 `ApiVersionTest`). 계약·검증 시나리오는 [contracts/member-onboarding.md](./contracts/member-onboarding.md) 참조.

**Tests**: Test-First is **NON-NEGOTIABLE** (헌법 원칙 I). 각 스토리는 구현 전에 실패하는 테스트를 먼저 쓰고 **Red 를 눈으로 확인**한다.

**Organization**: 스토리별로 묶어 각각 독립적으로 구현·검증·전달할 수 있게 한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 실행 가능(다른 파일, 선행 미완료 의존 없음)
- **[Story]**: 소속 사용자 스토리(US1·US2·US3)
- 모든 태스크에 정확한 파일 경로를 적는다

## Path Conventions

모듈러 모놀리스 — `common/src/{main,test}/kotlin/com/kbap/common/...`, `api/src/{main,test}/kotlin/com/kbap/api/...`. 워크트리 루트에서 실행한다.

---

## Phase 1: Setup

**Purpose**: 변경 전 기준선 확보 — 이후 나타나는 Red 가 내 변경 때문임을 보장한다.

- [X] T001 기준선 그린 확인: `./gradlew :api:test --tests "*MemberControllerTest" --tests "*MemberV2ControllerTest"` 실행해 전부 통과함을 확인한다(실패 항목이 있으면 먼저 원인을 규명하고 이 작업을 시작하지 않는다)

---

## Phase 2: Foundational (Blocking Prerequisites)

**해당 없음.** 신규 Gradle 모듈·의존성·설정·Flyway 마이그레이션이 0건이고(plan.md), 스토리 간 공유 선행물이 없다. `OnboardingProfileDefaults` 는 US1 이 동작하기 위해 필요하므로 US1 단계에 둔다(US3 는 그 값의 **품질**을 검증한다).

---

## Phase 3: User Story 1 — 신규 앱 사용자의 무입력 온보딩 (Priority: P1) 🎯 MVP

**Goal**: 닉네임·프로필 사진을 받지 않는 `POST /api/v2/members/me/onboarding` 이 동작하고, 서버가 두 값을 자동 지정한다.

**Independent Test**: 닉네임·사진 없이 v2 온보딩 요청 → 200, 이어서 `GET /api/v1/members/me/profile` 의 `nickname` 이 `^[A-HJ-NP-Z2-9]{6}$` 를 만족하고 `profileImageUrl` 이 후보 6종 중 하나로 끝나면 통과.

### Tests (Red 먼저)

- [X] T002 [US1] `api/src/test/kotlin/com/kbap/api/member/MemberV2ControllerTest.kt` — v2 온보딩 시나리오 추가: ① 닉네임·사진 없이 `POST /api/v2/members/me/onboarding` → 200 + 프로필 조회 `nickname` 이 `Regex("^[A-HJ-NP-Z2-9]{6}\$")` 일치 ② `profileImageUrl` 이 `OnboardingProfileDefaults.PROFILE_IMAGE_PATHS` 중 하나로 `endsWith` ③ 요청에 `nickname`·`profileImageUrl` 을 끼워 보내도 무시(지정값이 보낸 값과 다름) ④ 재온보딩 → 400 + `MEMBER-002` ⑤ `countryCode` 누락 → 400 + `COMMON-002` ⑥ 미인증 → 401. 실행해 **Red 확인**(`./gradlew :api:test --tests "*MemberV2ControllerTest"` — 컴파일 실패 또는 404)

### Implementation (Green)

- [X] T003 [P] [US1] `common/src/main/kotlin/com/kbap/common/domain/member/model/OnboardingProfileDefaults.kt` 신규 — `PROFILE_IMAGE_PATHS: List<String>`(아바타 6종, data-model.md 확정값·선행 `/` 없이), `randomProfileImagePath()`, `randomNickname()`(private `NICKNAME_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"` 에서 6자 추첨), `Random` 주입 seam 없이 표준 라이브러리 사용
- [X] T004 [P] [US1] `api/src/main/kotlin/com/kbap/api/member/OnboardingV2Request.kt` 신규 — `avoidanceSubstanceCodes: List<String> = emptyList()`·`countryCode: String`·`spicinessPreference: String` 3필드 + `toInput(memberId)`(닉네임·이미지는 넘기지 않아 기본 `null` 유지)
- [X] T005 [US1] `common/src/main/kotlin/com/kbap/common/domain/member/dto/MemberProfileInput.kt` — `nickname`·`profileImageUrl` 을 `String?` 로 바꾸고 기본값 `null` 부여(선언 순서 유지 — v1 의 명명 인자 호출이 깨지지 않게)
- [X] T006 [US1] `common/src/main/kotlin/com/kbap/common/domain/member/MemberService.kt` — `completeOnboarding` 에서 `input.nickname ?: OnboardingProfileDefaults.randomNickname()`, `input.profileImageUrl ?: OnboardingProfileDefaults.randomProfileImagePath()` 로 대체해 `Member.completeOnboarding` 에 전달(T005 선행). `Member.kt` 는 수정하지 않는다
- [X] T007 [P] [US1] `api/src/main/kotlin/com/kbap/api/member/MemberV2Api.kt` — `completeOnboarding` swagger 선언 추가(`@Operation` 설명에 "닉네임·프로필 사진은 서버가 자동 지정, 요청에 포함해도 무시" 명시, `@ApiResponses` 200/400/401, 요청 예시 3필드). **Spring 애너테이션은 넣지 않는다**(CLAUDE.md 파라미터 애너테이션 위치 규약)
- [X] T008 [US1] `api/src/main/kotlin/com/kbap/api/member/MemberV2Controller.kt` — `@PostMapping("/me/onboarding")` + `@AuthMemberId memberId: Long`·`@RequestBody request: OnboardingV2Request` → `memberService.completeOnboarding(request.toInput(memberId))` → `ResponseEntity.ok(BaseResponse.ok(Unit))`. T002 를 **Green 으로 전환**해 확인

**Checkpoint**: US1 완료 시점에 신규 앱 온보딩이 로그인부터 프로필 조회까지 전 구간 동작한다 (MVP).

---

## Phase 4: User Story 2 — 구버전 앱(1.0.0) 온보딩 무중단 (Priority: P1)

**Goal**: v1 온보딩의 요청 형식·검증·응답·오류가 한 글자도 바뀌지 않았음을 증명한다.

**Independent Test**: `MemberControllerTest` 를 **수정하지 않은 채** 전량 통과. v1 소스 3파일의 diff 가 0.

- [X] T009 [US2] v1 회귀 가드: `./gradlew :api:test --tests "*MemberControllerTest"` 전량 통과 확인. **이 테스트 파일을 수정해서 통과시키지 않는다** — 실패하면 v1 계약이 깨진 것이므로 T005·T006 구현을 되돌려 원인을 잡는다
- [X] T010 [US2] v1 소스 무변경 확인: `git diff --stat -- api/src/main/kotlin/com/kbap/api/member/OnboardingRequest.kt api/src/main/kotlin/com/kbap/api/member/MemberController.kt api/src/main/kotlin/com/kbap/api/member/MemberApi.kt` 가 **빈 출력**인지 확인(비어 있지 않으면 변경분을 되돌린다)
- [X] T011 [US2] `api/src/test/kotlin/com/kbap/api/member/MemberV2ControllerTest.kt` — 경로 간 동등성 시나리오 추가: v2 로 온보딩한 회원의 프로필 응답과 v1 로 온보딩한 회원의 프로필 응답이 **같은 필드 집합·같은 형태**(`profileImageUrl` 이 두 경우 모두 공개 베이스 URL 로 시작하는 절대 URL)임을 검증. ⚠️ `FakeSocialTokenVerifier` 가 항상 같은 `provider_uid`("google-sub-fixed")를 돌려주므로 **두 번째 온보딩 전에 `clearMembers()` 로 회원을 비우고 다시 로그인**해야 별개 회원이 만들어진다. Red → Green 순서 유지

**Checkpoint**: 구버전 앱 사용자가 영향을 받지 않음이 자동 검증으로 고정된다.

---

## Phase 5: User Story 3 — 자동 지정 값의 품질 (Priority: P2)

**Goal**: 생성 닉네임과 이미지 후보가 저장 제약을 만족하고 한 값에 쏠리지 않음을 배포 전에 강제한다.

**Independent Test**: `OnboardingProfileDefaultsTest` 단독 실행으로 형식·유효성·분산이 전부 검증된다(DB 불필요).

- [X] T012 [US3] `common/src/test/kotlin/com/kbap/common/domain/member/model/OnboardingProfileDefaultsTest.kt` 신규 (Kotest `BehaviorSpec`, given/when/then 한국어) — ① `randomNickname()` 이 `^[A-HJ-NP-Z2-9]{6}$` 일치·길이 6·공백 없음 ② 1,000회 호출 시 서로 다른 값이 990개 이상(중복 5% 미만, SC-004) ③ `PROFILE_IMAGE_PATHS` 가 비어 있지 않고 각 원소가 `ImageUrls.isAbsoluteUrl == false`·선행 `/` 없음·길이 1..512 ④ `randomProfileImagePath()` 1,000회 호출 시 한 값 점유율 30% 미만이고 반환값이 항상 후보 목록 원소. 실행해 **Red 확인** 후 필요한 보정만 T003 에 반영해 Green
- [X] T013 [US3] `common/src/test/kotlin/com/kbap/common/domain/member/model/OnboardingProfileDefaultsTest.kt` — 저장 제약 정합 시나리오 추가: 생성 닉네임과 각 이미지 후보를 `MemberProfile.empty().updatedWith(...)` 에 그대로 넣어 **예외 없이 통과**하는지 검증(FR-011 — 후보가 런타임 400 을 유발하지 않음을 보증하는 핵심 가드). Red → Green

**Checkpoint**: 후보·생성 규칙의 오류가 배포 전에 반드시 드러난다.

---

## Phase 6: Polish & Cross-Cutting

- [X] T014 [P] ArchUnit 경계 확인: `./gradlew :api:test --tests "*ModuleBoundaryTest"` — 신규 컨트롤러 매핑이 `/api/v` 규약을 지키는지, `common.domain` 이 api/infra 를 참조하지 않는지 검증
- [X] T015 전체 빌드 그린: `./gradlew build` (`:common`·`:api`·`:batch`·`:infra:*` 전 모듈 컴파일 + 테스트)
- [ ] T016 [P] Swagger 육안 확인: `./gradlew :api:bootRun` 후 `http://localhost:8080/swagger-ui.html` 의 "회원" 태그 온보딩에서 `nickname`·`profileImageUrl` 이 **선택 필드**로 안내되는지 확인 ([quickstart.md](./quickstart.md) 수동 절차)
- [X] T017 [P] 주석 규약 점검: 신규·수정 Kotlin 파일에 KDoc·서사형 주석이 없는지 확인. 코드로 드러나지 않는 설계 제약만 짧은 라인 주석으로 남긴다(예: `MemberProfileInput` 의 `null = 서버 지정` 규약)

---

## Dependencies

```
T001 (기준선)
  └─> Phase 3 (US1): T002(Red) ─> T003·T004 [P] ─> T005 ─> T006 ─> T008(Green)
                                   T007 [P] (독립)
        └─> Phase 4 (US2): T009 ─> T010 ─> T011
        └─> Phase 5 (US3): T012 ─> T013
              └─> Phase 6: T014·T016·T017 [P] ─> T015
```

**스토리 간 의존**:

- **US1 → US2**: US2 는 US1 이 만든 변경(`MemberProfileInput`·`MemberService`)이 v1 을 깨뜨리지 않았음을 검증하므로 US1 이후에 의미가 있다.
- **US1 → US3**: US3 는 US1 이 만든 `OnboardingProfileDefaults` 를 검증한다.
- **US2 ∥ US3**: 서로 독립 — 다른 파일을 만지므로 동시에 진행할 수 있다.

**태스크 간 의존**:

- T006 은 T005(`String?` 완화) 이후에만 컴파일된다.
- T008 은 T004(요청 DTO)·T006(서비스) 이후.
- T012·T013 은 T003(구현 존재) 이후 — 단 **테스트를 먼저 쓰고 Red 를 본 뒤** T003 을 보정하는 순서를 지킨다.

## Parallel Execution Examples

**US1 안에서** — T002(Red) 확인 직후 서로 다른 파일 3개를 동시에:

```
T003  common/.../model/OnboardingProfileDefaults.kt
T004  api/.../member/OnboardingV2Request.kt
T007  api/.../member/MemberV2Api.kt
```

**US1 완료 후** — 두 스토리 동시 진행:

```
Phase 4 (US2)  api/src/test/.../MemberV2ControllerTest.kt + git diff 확인
Phase 5 (US3)  common/src/test/.../OnboardingProfileDefaultsTest.kt
```

**Polish** — T014·T016·T017 동시, 그 뒤 T015 단독.

## Implementation Strategy

1. **MVP = US1 (T001~T008)**. 여기까지만 해도 신규 앱의 무입력 온보딩이 완성된다.
2. **US2 (T009~T011)를 곧바로 이어서** 한다 — P1 두 개가 동시에 성립해야 배포할 수 있다(구버전 온보딩이 깨지면 신규 가입 자체가 막히는 장애).
3. **US3 (T012~T013)** 로 품질 가드를 고정한다. 후보 값을 바꾸고 싶어질 때 이 테스트가 안전망이 된다.
4. **Polish (T014~T017)** 후 커밋. 작업/논리 단위로 나눠 커밋한다(헌법 Development Workflow).

## 후속 처리 (코드 밖)

- Jira KB-300 DoD 첫 항목이 "닉네임 후보 목록…이 상수로 선언"인데 닉네임은 목록이 아니라 **생성 규칙**으로 확정됐다(research R5). 티켓 문구 수정 여부는 이슈 담당자가 판단한다 — 이 작업에서 Jira 를 변경하지 않았다.
- v1 온보딩 경로 제거는 1.0.0 사용률을 보고 별도 티켓으로 판단한다(research R7).
