# Implementation Plan: 온보딩 — 기피 음식·국가·앱 언어 설정 + 완료 처리

**Branch**: `kb-104-onboarding-profile` | **Date**: 2026-07-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-104-onboarding-profile/spec.md`

## Summary

인증된 회원이 온보딩 정보(닉네임·기피 성분 코드 집합·국가·앱 언어)를 제출하면 검증 후 프로필로 저장하고 온보딩 상태를 미완료→완료로 전이한다(`POST /api/v1/members/me/onboarding`). 홈화면 진입용으로 내 프로필·온보딩 상태 조회를 제공한다(`GET /api/v1/members/me`).

도메인(`Member.updateProfile`/`completeOnboarding`·`MemberProfile`)과 영속(member 테이블: `nickname`·`profile` JSON)은 KB-103/117 에서 이미 완성돼 있다. 다만 온보딩 진행 표현이 계층마다 제각각이라(도메인 enum `OnboardingStatus` ↔ 엔티티 필드 `onboardingCompleted` ↔ 칼럼 `onboarding_status` BOOLEAN ↔ 응답 boolean) **전 계층을 `onboardingCompleted: Boolean` / `onboarding_completed` 로 통일**한다(사용자 결정): enum `OnboardingStatus` 삭제, 칼럼 rename 마이그레이션 1건. 신규 작업은 네 가지다:

0. **온보딩 네이밍 통일 리팩터**(선행): 도메인 `Member.onboardingCompleted: Boolean`(enum 삭제, `completeOnboarding()` 행위·재완료 400 유지), `MemberJpaEntity` `@Column(name = "onboarding_completed")`(enum↔boolean 왕복 변환 제거), Flyway `ALTER TABLE member RENAME COLUMN onboarding_status TO onboarding_completed`. 기존 참조 5파일(MemberTest·LoginUseCaseTest·MemberRepositoryAdapterTest·AuthControllerTest 칼럼명 assert 포함) 동반 수정. 마이그레이션은 app:api 통합 테스트가 Testcontainers MySQL 에서 Flyway 실행+`ddl-auto=validate` 로 자동 검증한다(KB-46).

1. **필터 레벨 인증·인가**(app:api 신규, R1 개정 — 사용자 결정): `JwtAuthenticationFilter`(`OncePerRequestFilter`, 보호 경로 `/api/v1/members/*` 에 `FilterRegistrationBean` 등록)가 `Authorization: Bearer` 를 `TokenParser.parseAccessToken`(PR #46 — memberId+role 반환)으로 검증해 request attribute 에 저장. 실패는 필터가 직접 401 BaseResponse JSON 응답(advice 미도달 구간). 컨트롤러는 `@AuthMemberId` ArgumentResolver 로 attribute 의 회원 PK 만 주입. 추후 전 API 일괄 인증은 urlPatterns 확장으로 처리. KB-118 이 토큰 발급만 만들었고 **수신 요청 검증 장치는 이번이 최초 도입**이다.
2. **유스케이스 2종**(application:client `member` 패키지 신설): `CompleteOnboardingUseCase`(검증→프로필 저장→상태 전이, 단일 트랜잭션), `GetMyProfileUseCase`(조회). 카탈로그 81종 멤버십 검증은 컨텍스트 조합 계층인 application 에서 `AvoidanceSubstanceCode` enum 대조로 수행(헌법 II — member 는 코드 문자열만 보유).
3. **컨트롤러**(app:api `member` 패키지 신설): 제출·조회 2개 엔드포인트, `BaseResponse` 봉투·`ApiPaths.V1` 규약.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation), jjwt(기존 — TokenParser 재사용), Spring Data JPA(기존 어댑터 재사용). **신규 라이브러리 없음.**

**Storage**: MySQL `member` 테이블 (기존 — `nickname` VARCHAR(30)·`profile` JSON·온보딩 boolean 칼럼). **Flyway 마이그레이션 1건** — `onboarding_status` → `onboarding_completed` 칼럼 rename(기존 적용 파일 무수정, 신규 timestamp 버전).

**Testing**: Kotest BehaviorSpec(given/when/then 한국어) + JUnit5 플랫폼. 단위: 페이크 `MemberRepository` 로 유스케이스 검증. 통합: `@SpringBootTest` + MockMvc + MySQL Testcontainers(persistence testFixtures).

**Target Platform**: Linux server (`:app:api` bootJar)

**Project Type**: web-service (모듈러 모놀리스 — ADR-0008)

**Performance Goals**: 표준 CRUD 수준 — 온보딩 제출·조회 모두 단건 회원 row 접근, 추가 쿼리 없음.

**Constraints**: 검증 실패 시 저장 0건(단일 트랜잭션·저장 전 검증). 응답은 BaseResponse 봉투, 실패 메시지 ~습니다 체. 무효 코드 400 / 미인증 401.

**Scale/Scope**: 신규 엔드포인트 2개, 유스케이스 2개, ArgumentResolver 1개, 에러코드 1 enum, 네이밍 통일 리팩터(도메인 boolean 화 + 칼럼 rename 마이그레이션 1건).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | ✅ PASS | 모든 task 를 Red→Green→Refactor 로 진행. 유스케이스는 페이크 repo 단위 테스트, 엔드포인트는 MockMvc 통합 테스트(유효·무효·상태 전이·401 — DoD)를 구현 전 작성. |
| II. Bounded Contexts | ✅ PASS | member 도메인은 기피 성분을 `AvoidanceSubstanceCodeRef`(코드 문자열)로만 보유(기존). 카탈로그 81종 멤버십 검증은 avoidance 의 식별자 enum 을 **application:client(컨텍스트 조합 계층)** 에서 대조 — 도메인 간 직접 의존 없음. |
| III. Layered Dependency Direction | ✅ PASS | app:api → application:client → core:member/avoidance → kernel 단방향 유지. ArgumentResolver 는 app:api 에 두고 application:client 의 `TokenParser` 를 사용(허용 방향). 신규 infra 의존 없음. |
| IV. Persistence Encapsulation | ✅ PASS | 영속 변경 없음 — 기존 `MemberRepository` port 만 사용. application·app:api 는 JPA 를 import 하지 않는다. |
| V. Domain Content Language Policy | ✅ PASS | 앱 언어 입력은 지원 10개국어 코드 정확 일치만 허용, 불일치는 조용한 폴백 없이 400 fail-fast(헌법 V 의 fail-fast 방침과 일치). 온보딩은 콘텐츠 서빙이 아니므로 번역 정책 무관. |

**게이트 통과 — Complexity Tracking 불필요 (위반 없음).**

Post-design 재점검(Phase 1 완료 후): 설계 산출물(data-model·contracts)에서도 도메인 변경·컨텍스트 결합·영속 노출이 발생하지 않음을 확인 — ✅ 유지.

## Project Structure

### Documentation (this feature)

```text
specs/kb-104-onboarding-profile/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── onboarding-api.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
application/client/src/main/kotlin/com/meogo/application/client/member/   # 신규 패키지
├── CompleteOnboardingUseCase.kt        # 검증 → updateProfile+completeOnboarding → 저장 (단일 tx)
├── GetMyProfileUseCase.kt              # 내 프로필·온보딩 상태 조회
├── OnboardingErrorCode.kt              # 400 검증 에러 (ErrorCode 구현, AuthErrorCode 패턴)
└── dto/
    ├── OnboardingInput.kt              # nickname·avoidanceSubstanceCodes·countryCode·appLanguage
    └── MyProfileResult.kt              # 프로필 + onboardingCompleted

application/client/src/test/kotlin/com/meogo/application/client/member/
├── CompleteOnboardingUseCaseTest.kt    # 페이크 MemberRepository 단위 테스트
└── GetMyProfileUseCaseTest.kt

app/api/src/main/kotlin/com/meogo/app/api/
├── common/auth/                        # 신규 — 필터 레벨 인증·인가
│   ├── JwtAuthenticationFilter.kt      # Bearer 검증 → attribute 저장, 실패 시 직접 401 JSON
│   ├── AuthMemberId.kt                 # 파라미터 애너테이션
│   ├── AuthMemberIdArgumentResolver.kt # attribute 의 회원 PK 주입 (파싱 없음)
│   └── WebMvcAuthConfig.kt             # 필터 등록(/api/v1/members/*) + resolver 등록
└── member/                             # 신규 — 컨트롤러
    ├── MemberApi.kt                    # springdoc 인터페이스 (기존 AuthApi 패턴)
    ├── MemberController.kt             # POST /members/me/onboarding, GET /members/me
    ├── OnboardingRequest.kt
    └── MyProfileResponse.kt

app/api/src/test/kotlin/com/meogo/app/api/member/
└── MemberControllerTest.kt             # MockMvc 통합 — 유효/무효/상태전이/401 (DoD)

app/api/src/main/resources/db/migration/
└── Vyyyy.MM.dd.HH.mm.ss__rename_onboarding_status_to_onboarding_completed.sql   # 신규

# 네이밍 통일 리팩터로 수정되는 기존 파일
core/member/src/main/kotlin/com/meogo/core/member/Member.kt                      # onboardingCompleted: Boolean
core/member/src/main/kotlin/com/meogo/core/member/OnboardingStatus.kt            # 삭제
infra/persistence/src/main/kotlin/com/meogo/infra/persistence/member/MemberJpaEntity.kt  # @Column rename·변환 제거
```

**Structure Decision**: 기존 auth(KB-118) 수직 슬라이스 패턴을 member 로 미러링한다 — application:client 에 유스케이스·에러코드·DTO, app:api 에 Api 인터페이스+컨트롤러+요청/응답 DTO. 도메인·영속은 온보딩 네이밍 통일(boolean 화·칼럼 rename)만 반영하고 그 외 무변경 재사용.

## Complexity Tracking

> Constitution Check 위반 없음 — 해당 없음.
