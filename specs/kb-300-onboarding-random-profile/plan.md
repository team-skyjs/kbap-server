# Implementation Plan: 온보딩 시 닉네임·프로필 사진 랜덤 지정

**Branch**: `kb-300-onboarding-random-profile` | **Date**: 2026-08-10 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-300-onboarding-random-profile/spec.md`

## Summary

온보딩에서 닉네임·프로필 사진을 입력받지 않고 서버가 상수 후보에서 무작위로 지정한다. 1.0.0 앱이 쓰는 기존 온보딩은 손대지 않는다 — **분기 수단은 이미 이 저장소에 있는 API 버전 경로**(`ApiPaths.V2` + `MemberV2Controller`, KB-297 선례)다. 강제 업데이트·버전 헤더를 새로 도입하지 않는다.

변경 4지점:

1. `common.domain.member.model.OnboardingProfileDefaults` 신규 — 닉네임 생성기(영숫자 6자 코드) + 프로필 이미지 경로 후보 6종 상수·무작위 선택.
2. `MemberProfileInput.nickname`·`profileImageUrl` 을 `String?`(기본 `null`)로 완화 — **null = 서버가 랜덤 지정**.
3. `MemberService.completeOnboarding` 이 null 을 후보 추첨으로 채운 뒤 기존 `Member.completeOnboarding` 에 넘긴다(정책은 도메인 서비스 소유 — 헌법 IV).
4. `:api` 에 `OnboardingV2Request` + `MemberV2Controller.completeOnboarding`(`POST /api/v2/members/me/onboarding`) + `MemberV2Api` swagger 추가.

v1 경로(`OnboardingRequest` non-null `nickname`/`profileImageUrl`, `MemberController`)는 **한 글자도 바꾸지 않는다** — Jackson 이 non-null 필드 누락을 그대로 400 COMMON-002 로 거절하는 현행 동작이 유지된다. DB 스키마·Flyway 마이그레이션 없음.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), springdoc-openapi, jackson-module-kotlin

**Storage**: MySQL — `member.nickname`(VARCHAR 30)·`member.profile_image_url`(VARCHAR 512). **스키마 변경 없음**

**Testing**: Kotest `BehaviorSpec` + JUnit5 플랫폼. 통합 테스트는 MySQL·Redis Testcontainers(`MySqlContainerConfig`·`RedisContainerConfig`) + MockMvc

**Target Platform**: Linux 서버 (`:api` bootJar)

**Project Type**: 모듈러 모놀리스 백엔드 (`:common`·`:api`)

**Performance Goals**: 온보딩 지연 영향 없음 — 추첨은 인메모리 리스트 인덱싱 1회

**Constraints**: 1.0.0 앱의 v1 온보딩 계약 완전 불변(요청 형식·검증·오류 코드). 앱 버전 식별 수단 없음 → 분기는 경로로만

**Scale/Scope**: 신규 파일 3개(상수 1·요청 DTO 1·테스트 1), 수정 파일 5개 내외. 마이그레이션 0건

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| **I. Test-First (NON-NEGOTIABLE)** | PASS | `OnboardingProfileDefaultsTest`(단위) → `MemberV2ControllerTest` v2 온보딩 시나리오 → v1 회귀 순으로 Red 확인 후 구현. 후보 상수 유효성도 테스트가 강제(FR-011) |
| **II. Bounded Contexts** | PASS | member 컨텍스트 내부에서 완결. 다른 도메인 참조 없음. 후보 상수는 `com.kbap.common.domain.member.model` — 소유 도메인 안 |
| **III. Layered Dependency Direction** | PASS | `:api` → `:common` 단방향. 신규 seam·infra 의존 없음. 컨트롤러는 서비스만 호출(리포지토리 직접 호출 없음) |
| **IV. Persistence Ownership** | PASS | 엔티티·리포지토리·스키마 무변경. 랜덤 지정은 **정책**이므로 `MemberService`(도메인 서비스)가 소유하고 web 계층 DTO 에 두지 않는다. 트랜잭션 경계는 기존 `@Transactional` 그대로 |
| **V. Domain Content Language Policy** | N/A | 음식 콘텐츠 번역 정책과 무관. 생성 닉네임은 언어 중립 ASCII 코드라 번역·`lang` 파라미터 대상이 아니다 |

**추가 제약 점검**: 외부 호출을 트랜잭션 안에 두지 않는다 — 추첨은 순수 인메모리라 해당 없음. 도메인/영속 모델을 응답에 노출하지 않는다 — 응답은 기존 `MyProfileResponse` 재사용.

위반 없음. Complexity Tracking 불필요.

**Phase 1 설계 후 재점검 (PASS)**: 설계 산출물이 새로 도입한 것은 상수 object 1개·요청 DTO 1개·컨트롤러 메서드 1개뿐이다. 신규 모듈·seam·인터페이스·설정·마이그레이션이 없어 원칙 II·III·IV 의 판정이 달라지지 않는다. 후보 값 유효성을 런타임 방어 코드가 아니라 테스트로 강제하는 결정(research R4)은 원칙 I 을 오히려 강화한다.

## Project Structure

### Documentation (this feature)

```text
specs/kb-300-onboarding-random-profile/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── member-onboarding-v2.md
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks 산출 — 이 명령이 만들지 않음
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/member/
├── MemberService.kt                        # 수정 — null 이면 후보 추첨으로 채움
├── dto/MemberProfileInput.kt               # 수정 — nickname·profileImageUrl 을 String? 로
└── model/OnboardingProfileDefaults.kt      # 신규 — 닉네임 생성기 + 이미지 후보 상수

common/src/test/kotlin/com/kbap/common/domain/member/
└── model/OnboardingProfileDefaultsTest.kt  # 신규 — 닉네임 형식·중복, 이미지 후보 유효성·분포

api/src/main/kotlin/com/kbap/api/member/
├── OnboardingV2Request.kt                  # 신규 — 닉네임·사진 필드 없음
├── MemberV2Api.kt                          # 수정 — v2 온보딩 swagger 문서 추가
├── MemberV2Controller.kt                   # 수정 — POST /api/v2/members/me/onboarding
├── OnboardingRequest.kt                    # 무변경 (v1)
├── MemberApi.kt / MemberController.kt      # 무변경 (v1) — deprecated 표기만 검토
└── ...

api/src/test/kotlin/com/kbap/api/member/
├── MemberV2ControllerTest.kt               # 수정 — v2 온보딩 시나리오 추가
└── MemberControllerTest.kt                 # 무변경 — v1 회귀 가드
```

**Structure Decision**: 기존 모듈 구조를 그대로 쓴다. 새 모듈·패키지·설정 없음. 후보 상수는 도메인 정책이므로 `:common` 의 member 도메인에 두고, HTTP 계약 차이(v1/v2)만 `:api` 에 둔다.

## Complexity Tracking

> Constitution Check 위반 없음 — 작성하지 않는다.
