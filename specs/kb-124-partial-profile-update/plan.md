# Implementation Plan: 프로필 수정 API 부분 수정 전환 — 미전송 필드는 기존 값 유지

**Branch**: `kb-124-partial-profile-update` | **Date**: 2026-07-12 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/kb-124-partial-profile-update/spec.md`

## Summary

`PATCH /api/v1/members/me/profile` 을 전체 덮어쓰기에서 **진짜 부분 수정**으로 바꾼다. 요청 DTO 의 모든 필드를 nullable(기본값 `null`)로 만들어 **미전송(`null`)과 빈 배열(`[]`)을 타입 레벨에서 구분**하고, 유스케이스가 전달된 필드만 검증해 기존 프로필과 병합한다.

기술적 요점 셋:
- **도메인 무변경.** `MemberProfile` 은 닉네임·국가·언어가 이미 nullable 이고 `of(...)` 가 공개 팩토리라, 유스케이스에서 필드별로 `?: current.x` 로 해소해 재조립하면 끝이다. 비공개 `copy` 를 열거나 도메인에 병합 팩토리를 넣을 필요가 없다(research R2).
- **입력 타입을 가른다.** 지금 온보딩과 수정이 `MemberProfileInput` 하나와 `validatedProfile()` 하나를 공유하는데, 그대로 nullable 로 풀면 **온보딩의 "전 필드 필수"까지 함께 무너진다**. `ProfileUpdateInput`(전 필드 nullable)을 신설하고 검증은 필드 단위 함수 4개로 쪼개 두 경로가 공유한다(research R3).
- **부분 저장은 구조적으로 불가능.** 병합된 프로필을 다 만든 뒤 단일 `repository.update(...)` 로 저장하므로, 검증 실패 시 그 호출에 도달하지 못한다(research R5).

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), jackson-module-kotlin(요청 기본값 해석), springdoc-openapi

**Storage**: MySQL 8.4 — `member.nickname` 컬럼 + `member.profile` JSON 컬럼. **스키마 변경 없음**

**Testing**: Kotest `BehaviorSpec`. 단위는 손으로 쓴 페이크, 통합은 MySQL·Redis Testcontainers + MockMvc

**Target Platform**: Linux 서버 (`:app:api` bootJar)

**Project Type**: 모듈러 모놀리스 web 서비스 (ADR-0008)

**Performance Goals**: 저빈도 쓰기 경로. 조회 1 + 쓰기 1로 현행과 동일

**Constraints**: 온보딩 API 의 동작·검증이 100% 그대로여야 한다(SC-005). 기존 클라이언트(네 필드 전송)가 깨지지 않아야 한다

**Scale/Scope**: 소스 4개 파일(신규 1·수정 3), 테스트 2개 파일. Flyway 0건. 도메인·영속 무변경

## Constitution Check

*GATE: Phase 0 이전 통과 필수. Phase 1 설계 후 재확인.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| **I. Test-First** | ✅ | 부분 수정 시나리오를 `MemberProfileUseCaseTest`·`MemberControllerTest` 에 먼저 써 Red 확인 후 구현. 기존 온보딩 테스트는 회귀 가드로 계속 green 이어야 한다. |
| **II. Bounded Contexts** | ✅ | member 컨텍스트 단독. 도메인 모듈 간 의존 추가 없음. |
| **III. Layered Dependency** | ✅ | 의존 방향 변화 없음(app:api → application:client → core:member). 신규 외부 의존 없음. |
| **IV. Persistence Encapsulation** | ✅ | 영속 코드 무변경. 병합은 application 계층에서 끝나고 완성된 도메인 프로필만 내려간다. |
| **V. Domain Content Language** | ✅ | 언어 코드 검증 규칙(정확 일치·미지원 시 400)이 그대로다. 폴백 정책 변경 없음. |

**Post-Phase-1 재확인**: 설계 산출물이 위 판정을 바꾸지 않는다. **헌법 위반 없음 — Complexity Tracking 공란.**

## Project Structure

### Documentation (this feature)

```text
specs/kb-124-partial-profile-update/
├── plan.md                      # 이 파일
├── spec.md
├── research.md                  # Phase 0 — R1~R6
├── data-model.md                # Phase 1 — 스키마 무변경 + 타입 분리 + 병합 규칙
├── quickstart.md                # Phase 1 — 건드리는 파일·테스트·검증
├── contracts/
│   └── profile-update-api.md    # Phase 1 — PATCH /me/profile 계약(변경 전후 포함)
├── checklists/
│   └── requirements.md
└── tasks.md                     # Phase 2 (/speckit-tasks 가 생성)
```

### Source Code (repository root)

```text
app/api/src/main/kotlin/com/meogo/app/api/member/
├── ProfileUpdateRequest.kt      # 수정 — 전 필드 nullable + 기본값 null, toInput() → ProfileUpdateInput
├── OnboardingRequest.kt         # 무변경 — 전 필드 필수
└── MemberApi.kt                 # 수정 — PATCH /me/profile 설명·예시(빈 배열 vs 미전송)

application/client/src/main/kotlin/com/meogo/application/client/member/
├── dto/ProfileUpdateInput.kt    # 신규 — 전 필드 nullable
├── dto/MemberProfileInput.kt    # 무변경 — 온보딩 전용(전 필드 non-null)
└── MemberProfileUseCase.kt      # 수정 — update(ProfileUpdateInput) 병합, 검증을 필드 단위 4함수로 분해

core/member/…                    # 무변경 (MemberProfile.of 공개 팩토리를 그대로 사용)
infra/persistence/…              # 무변경
```

**Structure Decision**: 기존 모듈 경계를 그대로 쓴다. 신규 모듈·마이그레이션·엔드포인트가 없다. `MemberController` 도 호출 형태(`update(request.toInput(memberId))`)가 유지돼 무변경이다.

## Complexity Tracking

> 헌법 위반 없음 — 작성할 항목 없음.
