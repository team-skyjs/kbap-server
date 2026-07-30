# Implementation Plan: 프로필 수정 국가 코드 변경 불가 — v2 프로필 수정 API 신설

**Branch**: `kb-268-profile-update-v2` | **Date**: 2026-07-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-268-profile-update-v2/spec.md`

## Summary

기획 변경으로 국적(countryCode)은 온보딩에서 확정 후 변경 불가가 된다. 기존 `PATCH /api/v1/members/me/profile` 은 1.0.0 앱이 사용 중이라 계약을 깰 수 없으므로 그대로 두고, `countryCode` 필드가 없는 **v2 프로필 수정 API**(`PATCH /api/v2/members/me/profile`)를 신설한다. `ApiPaths` 에 `V2` 상수를 추가하고 v1·v2 컨트롤러가 공존한다. 도메인·영속 계층은 변경하지 않는다 — v2 는 기존 `MemberService.updateProfile` 을 `countryCode = null`(부분 수정 의미상 "변경 없음")로 재사용한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (기존 스택 그대로)

**Primary Dependencies**: Spring Boot 4.1 (web·validation), springdoc-openapi (기존 그대로 — 신규 의존성 없음)

**Storage**: 변경 없음 — 스키마·엔티티·마이그레이션 무관 (Member.profileJson 그대로)

**Testing**: Kotest BehaviorSpec + `@SpringBootTest`/MockMvc + MySQL Testcontainers (기존 테스트 스택)

**Target Platform**: `:api` web bootJar

**Project Type**: web-service (기존 멀티모듈 모놀리스의 `:api` 모듈만)

**Performance Goals**: 해당 없음 — 기존 엔드포인트와 동일 경로의 얇은 변형

**Constraints**: v1 계약·동작 절대 불변(1.0.0 앱 호환). v2 는 JWT 인증 필수(신규 보호 경로 필터 등록 필수 — 리뷰 함정 목록).

**Scale/Scope**: 신규 파일 3개 내외(V2 컨트롤러·API 인터페이스·요청 DTO) + `ApiPaths`·`WebConfig` 각 1줄 + 테스트. 도메인 로직 변경 0.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | v2 MockMvc 통합 테스트(국적 불변·항목 수정·무시되는 countryCode)와 v1 회귀 테스트를 구현 전 Red 로 작성한다. |
| II. Bounded Contexts | PASS | member 도메인 단독. 신규 코드는 전부 `com.kbap.api.member`(기능 패키지)·`com.kbap.api.core`(ApiPaths). 도메인 간 의존 변화 없음. |
| III. Layered Dependency Direction | PASS | api → common 방향 그대로. v2 컨트롤러는 기존 `MemberService`(common.domain.member)를 호출할 뿐 새 의존 없음. |
| IV. Persistence Ownership | PASS | 엔티티·리포지토리·Flyway 변경 없음. 트랜잭션 경계는 기존 `MemberService.updateProfile` 의 `@Transactional` 그대로. |
| V. Domain Content Language Policy | PASS (해당 없음) | lang 파라미터·음식 콘텐츠 무관. |

**게이트 통과** — 위반 없음, Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-268-profile-update-v2/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── profile-update-v2.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/
├── core/
│   ├── ApiPaths.kt                    # [수정] V2 상수 추가
│   └── config/WebConfig.kt            # [수정] JwtAuthenticationFilter 에 "${ApiPaths.V2}/members/*" 등록
└── member/
    ├── MemberController.kt            # [불변] v1 — 계약·동작 유지
    ├── ProfileUpdateRequest.kt        # [불변] v1 요청 DTO
    ├── MemberApi.kt                   # [불변] v1 swagger 인터페이스
    ├── MemberV2Controller.kt          # [신규] base = ApiPaths.V2 + "/members", PATCH /me/profile
    ├── MemberV2Api.kt                 # [신규] v2 swagger 문서 인터페이스
    └── ProfileUpdateV2Request.kt      # [신규] countryCode 없는 요청 DTO

api/src/test/kotlin/com/kbap/api/
└── member/
    └── MemberV2ControllerTest.kt      # [신규] v2 통합 테스트 (+ 기존 v1 테스트로 회귀 커버)
```

**Structure Decision**: 신규 코드는 전부 기존 `com.kbap.api.member` 기능 패키지에 둔다(ADR-0017 — 파일 수가 적어 하위 패키지 없음). CLAUDE.md 경로 규약대로 v2 는 별도 컨트롤러 클래스가 `ApiPaths.V2` 베이스를 참조해 v1 컨트롤러와 공존한다. `:common`(도메인)·`:batch`·`:infra:*` 는 건드리지 않는다.

## Complexity Tracking

위반 없음 — 해당 없음.
