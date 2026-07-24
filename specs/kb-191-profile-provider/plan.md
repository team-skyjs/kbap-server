# Implementation Plan: 내 프로필 조회 응답에 소셜 로그인 연동 정보(provider) 추가

**Branch**: `kb-191-profile-provider` | **Date**: 2026-07-20 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-191-profile-provider/spec.md`

## Summary

`GET /api/v1/members/me/profile` 응답에 소셜 제공자 필드 `provider`(GOOGLE/APPLE)를 추가한다. `Member.provider: SocialProvider`(non-null, ENUM 'GOOGLE','APPLE')가 이미 저장돼 있으므로, 조회 응답 조립 경로의 DTO 두 개(`MyProfileResult` → `MyProfileResponse`)에 필드를 관통시키기만 하면 된다. 신규 API·DB 스키마·Flyway·모듈 그래프 변경 0.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (기존 스택 그대로)

**Primary Dependencies**: Spring Boot 4.1 (기존) — 신규 의존성 0

**Storage**: 변경 없음 — `member.provider` 컬럼(ENUM 'GOOGLE','APPLE', NOT NULL) 기존 저장값 그대로 노출

**Testing**: Kotest BehaviorSpec + MockMvc + MySQL Testcontainers (`MemberControllerTest` 기존 인프라 재사용)

**Target Platform**: `:app:api` web bootJar

**Project Type**: 기존 모듈러 모놀리스 내 응답 필드 추가

**Performance Goals**: 해당 없음 — 추가 조회·연산 없음(이미 로드된 엔티티 필드 노출)

**Constraints**: 기존 응답 필드 하위 호환(이름·값·구조 불변), `provider` 는 enum 이름 문자열 그대로(GOOGLE/APPLE)

**Scale/Scope**: 프로덕션 2파일 수정(`MyProfileResult`·`MyProfileResponse`) + Swagger 문서(`MemberApi`) + 테스트(`MemberControllerTest`). 신규 파일 0.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | ✅ | `MemberControllerTest` 에 provider 응답 검증을 먼저 추가해 Red 확인 후 DTO 필드 관통으로 Green |
| II. Bounded Contexts | ✅ | provider 는 member 컨텍스트 소유 데이터 — 타 도메인 참조·결합 없음 |
| III. Layered Dependency | ✅ | 기존 흐름(controller → MemberService.getMyProfile) 불변, 모듈 의존 변경 0 |
| IV. Persistence Encapsulation | ✅ | 엔티티·리포지토리 접근 방식 불변 — 도메인 서비스가 반환하는 DTO 에 필드만 추가 |
| V. Language Policy | ✅ | 음식 콘텐츠 아님 — enum 식별자 값 노출, 번역 대상 아님 |
| 도메인 모델 API 직노출 금지 | ✅ | `SocialProvider` enum 값을 String 으로 변환해 응답 DTO 에 담음(기존 `countryCode?.name` 선례와 동일) |

**Post-Phase-1 재평가**: 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-191-profile-provider/
├── spec.md
├── plan.md              # This file
├── research.md          # Phase 0 — 미해결 사항 없음(기록용)
├── quickstart.md        # Phase 1 — 검증 절차
├── contracts/
│   └── my-profile-response.md   # 응답 계약 변경분
└── tasks.md             # /speckit-tasks 가 생성
```

### Source Code (repository root)

```text
domain/member/src/main/kotlin/com/kbap/domain/member/dto/MyProfileResult.kt   # provider: String 추가, of() 매핑
app/api/src/main/kotlin/com/kbap/app/api/member/MyProfileResponse.kt          # provider: String 추가, from() 매핑
app/api/src/main/kotlin/com/kbap/app/api/member/MemberApi.kt                  # Swagger 예시 JSON·설명에 provider 반영
app/api/src/test/kotlin/com/kbap/app/api/member/MemberControllerTest.kt       # provider 응답 검증 추가 (Red 진입점)
```

**Structure Decision**: 신규 파일·모듈 없음. `MyProfileResult.of(member, …)` 가 이미 `Member` 를 받으므로 `member.provider.name` 매핑 한 줄, `MyProfileResponse.from(result)` 에 관통 한 줄이 전부다. DTO 필드 타입은 `String`(enum 이름) — `countryCode`(`member.profile.countryCode?.name`) 기존 선례를 따르며, 도메인 enum 을 응답 계층에 직노출하지 않는다.

## Complexity Tracking

위반 없음 — 해당 없음.
