# Tasks: 온보딩 — 기피 음식·국가·앱 언어 설정 + 완료 처리

**Input**: Design documents from `/specs/kb-104-onboarding-profile/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/onboarding-api.md, quickstart.md

**Tests**: Test-First **NON-NEGOTIABLE** (헌법 원칙 I) — 모든 스토리는 실패 테스트(Red) 작성·확인 후 구현(Green)한다. 테스트는 Kotest `BehaviorSpec`(given/when/then 한국어).

**Organization**: 유저 스토리별 페이즈. 신규 코드는 application:client `member` 패키지와 app:api(`common/auth`·`member`), 그리고 온보딩 네이밍 통일 리팩터(research.md R8 — 도메인 boolean 화 + 칼럼 rename 마이그레이션 1건)다.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

없음 — 신규 라이브러리·인프라 0 (기존 jjwt·TokenParser·member 스키마 재사용). 바로 Phase 2 로 진행한다.

## Phase 2: Foundational (모든 스토리의 선행 조건)

**Goal**: (A) 온보딩 진행 표현을 전 계층 `onboardingCompleted` boolean 으로 통일(R8 — 사용자 결정), (B) 필터 레벨 인증·인가 — `JwtAuthenticationFilter` 가 보호 경로에서 액세스 토큰을 검증해 회원 PK·role 을 request attribute 로 넘기고, `@AuthMemberId` resolver 가 컨트롤러에 주입(R1 개정 — 사용자 결정, PR #46 role 클레임 선행). 두 스토리 모두 이 둘 위에서 작성된다.

- [X] T001 Red: 온보딩 네이밍 통일 — 기존 테스트를 boolean 기준으로 갱신. `core/member/src/test/kotlin/com/meogo/core/member/MemberTest.kt`(signUp 초기값 `onboardingCompleted=false`·completeOnboarding→true·재완료 400), `application/client/src/test/kotlin/com/meogo/application/client/auth/LoginUseCaseTest.kt`(`OnboardingStatus` 참조 제거), `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/member/MemberRepositoryAdapterTest.kt`, `app/api/src/test/kotlin/com/meogo/app/api/auth/AuthControllerTest.kt`(칼럼명 assert `"onboarding_status"` → `"onboarding_completed"`). 실패(Red — 컴파일 오류 포함) 확인.
- [X] T002 Green: 온보딩 네이밍 통일 구현 — `core/member/src/main/kotlin/com/meogo/core/member/Member.kt` 를 `onboardingCompleted: Boolean` 으로 변경(`completeOnboarding()` 행위·`ONBOARDING_ALREADY_COMPLETED` 유지), `OnboardingStatus.kt` 삭제, `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/member/MemberJpaEntity.kt` `@Column(name = "onboarding_completed")` + enum↔boolean 변환 제거, Flyway 신규 마이그레이션 `app/api/src/main/resources/db/migration/Vyyyy.MM.dd.HH.mm.ss__rename_onboarding_status_to_onboarding_completed.sql`(`ALTER TABLE member RENAME COLUMN onboarding_status TO onboarding_completed` — 버전은 생성 시점 로컬 시각, 기존 파일 무수정). `./gradlew :core:member:test :application:client:test :infra:persistence:test :app:api:test` Green 확인(통합 테스트가 Testcontainers 에서 마이그레이션+`ddl-auto=validate` 검증).
- [X] T003 [P] Red: `JwtAuthenticationFilter` 단위 테스트 작성 — `app/api/src/test/kotlin/com/meogo/app/api/common/auth/JwtAuthenticationFilterTest.kt`. `MockHttpServletRequest/Response` + 테스트용 시크릿의 `TokenIssuer`/`TokenParser` 실인스턴스 사용. 시나리오: 유효 Bearer → memberId·role 이 request attribute 에 저장되고 체인 진행 / 헤더 부재 → 401 + `BaseResponse.fail` JSON + 체인 미진행 / `Bearer ` 접두 없는 형식 → 401 / 위조 서명 → 401 "유효하지 않은 인증 토큰입니다" / 만료 토큰 → 401 "만료된 인증 토큰입니다". 실패(Red) 확인.
- [X] T004 Green: 필터 레벨 인증·인가 구현 — `app/api/src/main/kotlin/com/meogo/app/api/common/auth/JwtAuthenticationFilter.kt`(`OncePerRequestFilter`: Bearer 추출→`TokenParser.parseAccessToken`→attribute 저장, `AuthException` 은 직접 401 BaseResponse JSON 응답 — advice 미도달 구간), `AuthMemberId.kt`(파라미터 애너테이션), `AuthMemberIdArgumentResolver.kt`(attribute 회원 PK 주입), `WebMvcAuthConfig.kt`(`FilterRegistrationBean` urlPatterns `/api/v1/members/*` + resolver 등록). T003 통과 확인.

**Checkpoint**: 네이밍 통일 후 전 모듈 테스트 Green + 필터 단위 테스트 Green — 이후 모든 스토리가 `onboardingCompleted` boolean 과 `@AuthMemberId memberId: Long` 을 사용한다.

## Phase 3: User Story 1 — 온보딩 정보 제출로 프로필 설정·온보딩 완료 (P1) 🎯 MVP

**Goal**: 인증 회원이 닉네임·기피 성분·국가·앱 언어를 제출하면 프로필 저장 + `onboardingCompleted` false→true 전이. 재제출 400, 미인증 401.

**Independent Test**: 인증 토큰으로 `POST /api/v1/members/me/onboarding` 호출 → DB 프로필 반영·상태 전이를 MockMvc 통합 테스트로 검증.

- [X] T005 [P] [US1] Red: `CompleteOnboardingUseCase` 단위 테스트 작성 — `application/client/src/test/kotlin/com/meogo/application/client/member/CompleteOnboardingUseCaseTest.kt`. 페이크 `MemberRepository`(인메모리) 사용. 시나리오: 유효 입력 → 프로필(닉네임·성분 Set·국가·언어) 저장 + `onboardingCompleted=true` 전이 + 기존 spiciness 보존 / 빈 성분 목록 → 정상 / 이미 완료 → `ONBOARDING_ALREADY_COMPLETED`(400) + 프로필 불변 / 회원 부재 → `MEMBER_NOT_FOUND`(404). 실패(Red) 확인.
- [X] T006 [US1] Green: 제출 유스케이스 구현 — `application/client/src/main/kotlin/com/meogo/application/client/member/dto/OnboardingInput.kt`, `CompleteOnboardingUseCase.kt`(`@Transactional`, 조회→`updateProfile`→`completeOnboarding`→save 1회 — research.md R6). 검증 로직은 US2 에서 추가(이 단계는 흐름만). T005 통과 확인.
- [X] T007 [US1] Red: 제출 엔드포인트 MockMvc 통합 테스트 작성 — `app/api/src/test/kotlin/com/meogo/app/api/member/MemberControllerTest.kt`(`@SpringBootTest`+`@AutoConfigureMockMvc`+MySQL Testcontainers, 회원은 리포지토리로 직접 시드하고 토큰은 `TokenIssuer` 로 발급). 시나리오: 유효 제출 → 200 + DB 에 프로필·`onboarding_completed=true` 반영 / 재제출 → 400 "이미 온보딩을 완료했습니다" / Authorization 헤더 부재 → 401 / 위조 토큰 → 401. 실패(Red) 확인.
- [X] T008 [US1] Green: 제출 API 구현 — `app/api/src/main/kotlin/com/meogo/app/api/member/OnboardingRequest.kt`, `MemberApi.kt`(springdoc 인터페이스 — 기존 `AuthApi` 패턴), `MemberController.kt`(`@RequestMapping(ApiPaths.V1 + "/members")`, `POST /me/onboarding`, `@AuthMemberId` 사용, `ResponseEntity<BaseResponse<Unit>>`). T007 통과 확인.
- [X] T009 [US1] Refactor + 전체 검증 — 네이밍·중복 정리(주석 금지 규약 준수), `./gradlew :application:client:test :app:api:test` 전체 Green + ArchUnit `ModuleBoundaryTest` 통과 확인.

**Checkpoint**: MVP — 온보딩 제출이 끝까지 동작(인증→검증 없는 유효 흐름→저장→전이→재제출 거부→401).

## Phase 4: User Story 2 — 입력 검증 실패 시 저장 없이 거절 (P2)

**Goal**: 카탈로그 81종 밖 성분·미지정 국가·미지원 언어·빈 닉네임 → 400 + 저장·전이 없음. (research.md R3·R4)

**Independent Test**: 항목별 무효 입력 제출 → 400 응답 + DB 무변경을 각각 독립 검증.

- [X] T010 [P] [US2] Red: 유스케이스 검증 테스트 확장 — `application/client/src/test/kotlin/com/meogo/application/client/member/CompleteOnboardingUseCaseTest.kt` 에 추가. 시나리오: 81종 밖 성분 코드 → `INVALID_AVOIDANCE_SUBSTANCE_CODE`(400) + 저장 미호출·상태 불변 / `CountryCode` 밖 국가 → `INVALID_COUNTRY_CODE` / 10개국어 code 불일치(`fr`·`EN`·`ko-KR`) → `UNSUPPORTED_APP_LANGUAGE` / 공백 닉네임 → `INVALID_NICKNAME` / 닉네임 앞뒤 공백 → trim 저장 / 중복 성분 코드 → Set 화 저장 / 거절 후 유효 재제출 → 성공. 실패(Red) 확인.
- [X] T011 [US2] Green: 검증 구현 — `application/client/src/main/kotlin/com/meogo/application/client/member/OnboardingErrorCode.kt`(kernel `ErrorCode` 구현, 400 4종·~습니다 체 — data-model.md 표), `CompleteOnboardingUseCase` 에 저장 전 검증 추가(성분 = `AvoidanceSubstanceCode` enum name 집합 대조, 국가 = `CountryCode.from` null 검사, 언어 = `LanguageCode.code` 정확 일치, 닉네임 = trim 후 notBlank). T010 통과 확인.
- [X] T012 [US2] Red: 무효 입력 MockMvc 통합 테스트 확장 — `app/api/src/test/kotlin/com/meogo/app/api/member/MemberControllerTest.kt` 에 추가. 시나리오: 무효 성분/국가/언어/닉네임 각각 → 400 BaseResponse(fail 메시지) + DB 프로필·`onboarding_completed` 무변경 / 거절 후 유효 재제출 → 200. 실패(Red) 확인.
- [X] T013 [US2] Green: 통합 테스트 통과 확인(검증은 유스케이스 소유라 컨트롤러 변경은 없거나 최소) + `./gradlew :app:api:test` Green.

**Checkpoint**: DoD "유효성 검증, 위반 시 400(BaseResponse)" 충족 — 무효 코드가 프로필에 남는 경로 0.

## Phase 5: User Story 3 — 홈화면 진입 시 내 온보딩 프로필·상태 확인 (P3)

**Goal**: `GET /api/v1/members/me` — 프로필(닉네임·성분·국가·언어) + `onboardingCompleted` 응답. 미인증 401.

**Independent Test**: 완료/미완료 회원 각각으로 조회 → 프로필 값·상태 플래그 검증.

- [X] T014 [P] [US3] Red: `GetMyProfileUseCase` 단위 테스트 작성 — `application/client/src/test/kotlin/com/meogo/application/client/member/GetMyProfileUseCaseTest.kt`(페이크 repo). 시나리오: 온보딩 완료 회원 → 저장된 프로필 + `onboardingCompleted=true` / 미완료 회원 → null 프로필 필드·빈 성분 목록 + `false` / 회원 부재 → `MEMBER_NOT_FOUND`(404). 실패(Red) 확인.
- [X] T015 [US3] Green: 조회 유스케이스 구현 — `application/client/src/main/kotlin/com/meogo/application/client/member/dto/MyProfileResult.kt`, `GetMyProfileUseCase.kt`(읽기 전용). T014 통과 확인.
- [X] T016 [US3] Red: 조회 엔드포인트 MockMvc 통합 테스트 확장 — `app/api/src/test/kotlin/com/meogo/app/api/member/MemberControllerTest.kt` 에 추가. 시나리오: 온보딩 완료 회원 조회 → 200 + payload 프로필·`onboardingCompleted:true` / 미완료 회원 → `false` + null 필드 / 미인증 → 401 / **제출(US1)→조회 연계: 제출 직후 조회에 반영**. 실패(Red) 확인.
- [X] T017 [US3] Green: 조회 API 구현 — `app/api/src/main/kotlin/com/meogo/app/api/member/MyProfileResponse.kt`, `MemberApi`·`MemberController` 에 `GET /me` 추가. T016 통과 확인.

**Checkpoint**: DoD "홈화면 진입 시 현재 회원 온보딩 프로필·상태 응답" 충족 — 조회 한 번으로 온보딩 분기 가능(SC-004).

## Phase 6: Polish & Cross-Cutting

- [X] T018 전체 빌드·회귀 검증 — `./gradlew build`(전 모듈 테스트 + ArchUnit 경계 + 기존 auth·food·scan 회귀). quickstart.md 수동 시나리오로 로컬 확인(로그인→미완료 조회→제출→완료 조회→재제출 400→미인증 401 — rename 마이그레이션 자동 적용 포함), Swagger UI 에 members 엔드포인트 노출 확인.
- [X] T019 작업/논리 단위 커밋 정리 및 draft PR — `open-draft-pr-to-develop` 스킬로 base=develop draft PR 생성(제목 scope 에 kb-104 금지 — 모듈/도메인 scope).

## Dependencies

```text
Phase 2 (Foundational)
  T001→T002 (네이밍 통일)   T003[P]→T004 (인증 장치)   ← 두 묶음은 서로 병렬 가능(다른 파일)
        │                        │
        └────────┬───────────────┘
                 ▼
US1 (T005[P]→T006, T007→T008, T009)   ← MVP. T005 는 T004 와 병렬 가능(다른 모듈)
        │
        ▼
US2 (T010[P]→T011→T012→T013)          ← US1 의 유스케이스·테스트 파일을 확장하므로 US1 뒤
        │
        ▼
US3 (T014[P]→T015→T016→T017)          ← 논리적으론 Foundational 만 필요하나 MemberApi/MemberController/
        │                                MemberControllerTest 파일을 US1 과 공유하므로 순차 진행
        ▼
Polish (T018→T019)
```

## Parallel Execution Examples

- **T001·T002 ‖ T003·T004**: 네이밍 통일(core/infra/기존 테스트)과 인증 장치(app:api 신규 파일)는 파일 집합이 겹치지 않는다 — 단, T001 이 `AuthControllerTest` 를 만지므로 같은 파일을 쓰지 않는 T003 과만 병렬.
- **T005 ‖ T004**: 유스케이스 단위 테스트(application:client)와 resolver 구현(app:api)은 다른 모듈.
- 그 외는 같은 파일(`CompleteOnboardingUseCase`·`MemberController`·`MemberControllerTest`)을 이어서 수정하므로 순차가 안전하다.

## Implementation Strategy

**MVP = Phase 2 + US1**(T001~T009): 네이밍 통일 + 인증 주입 + 유효 제출·전이·재제출 400·401 까지 — 이 시점에 온보딩의 핵심 가치가 동작한다. 이후 US2(검증 방어선)·US3(조회) 를 증분 배송. 각 checkpoint 마다 전체 테스트 Green 을 확인하고 논리 단위로 커밋한다(네이밍 통일 리팩터는 독립 커밋 권장 — 리뷰 diff 분리).

**Format validation**: 전 태스크 체크박스·ID(T001~T019)·[P]/[Story] 라벨·파일 경로 포함 ✔
