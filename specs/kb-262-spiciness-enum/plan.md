# Implementation Plan: 사용자 프로필 맵기 설정 ENUM 전환

**Branch**: `kb-262-spiciness-enum` | **Date**: 2026-07-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-262-spiciness-enum/spec.md`

## Summary

회원 프로필의 맵기 선호를 정수(-1 미설정, 0~10)에서 6단계 enum `SpicinessPreference`(SKIP·NONE·MILD·MEDIUM·HOT·EXTREME)로 전환한다. 표현 전환은 member 컨텍스트에 국한된다: 도메인 값 객체(`MemberProfile`)·영속 JSON(`MemberProfileJson`)·도메인 dto·API 요청/응답·Swagger 문서·관리자 회원 조회가 대상이고, 음식 맵기 점수(`Food.spiciness: Int`)는 별개 개념으로 손대지 않는다. 저장은 `member.profile` JSON 컬럼 내부 속성이므로 Flyway 마이그레이션은 컬럼 타입 전환이 아니라 **JSON 값 재작성**(정수→enum 이름 문자열)이다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (기존 스택 그대로)

**Primary Dependencies**: Spring Boot 4.1(web·validation·data-jpa), Jackson(프로필 JSON 직렬화), Flyway

**Storage**: MySQL — `member.profile` JSON 컬럼 내부 `spicinessPreference` 속성(전용 컬럼 없음)

**Testing**: Kotest BehaviorSpec + MySQL Testcontainers(`@SpringBootTest`), 단위는 순수 Kotest

**Target Platform**: `:api` bootJar (member 도메인은 `:common`) — `:batch` 는 맵기 선호 미사용

**Project Type**: 모듈러 모놀리스 백엔드 — 기존 구조 변경 없음

**Performance Goals**: 해당 없음(표현 전환 — 신규 쿼리·핫패스 없음)

**Constraints**: 하위 호환 없음(클라이언트 동시 전환) — 정수 입력은 400 거절. 이관은 일회성 Flyway, 조용한 유실 금지

**Scale/Scope**: 파일 ~12개 수정 + enum 1개·마이그레이션 1개 신규 + 테스트 갱신

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | ✅ | 각 task 를 Red→Green 으로 진행: enum 검증·경계 매핑 단위 테스트, 요청 거절·왕복 통합 테스트를 구현 전 작성 |
| II. Bounded Contexts | ✅ | `SpicinessPreference` 는 member 소유 — `com.kbap.common.domain.member.model` 에 둔다. food 의 `Spiciness.RANGE`(0~10 점수)는 별개로 유지, 도메인 간 의존 변화 없음 |
| III. Layered Dependency Direction | ✅ | 모듈·패키지 의존 방향 변화 없음(member 내부 표현 교체) |
| IV. Persistence Ownership | ✅ | 영속 표현(`MemberProfileJson`)은 member 패키지가 소유, 이관은 Flyway(스키마 owner=api) 마이그레이션으로 수행 |
| V. Language Policy | ✅ | enum 이름은 API vocabulary(코드 값)지 음식 콘텐츠가 아니다 — 번역 정책 비대상 |

**Post-design re-check**: Phase 1 설계 후 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-262-spiciness-enum/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── member-api.md    # Phase 1 output — 요청/응답 계약 변경분
└── tasks.md             # Phase 2 output (/speckit-tasks — 이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/
├── core/error/ErrorCode.kt                          # MEMBER-009 메시지 갱신(코드 유지)
└── domain/member/
    ├── model/SpicinessPreference.kt                 # 신규 — 6단계 enum
    ├── model/MemberProfile.kt                       # Int → enum, 검증 교체
    ├── model/MemberProfileJson.kt                   # 영속 JSON: enum 이름 문자열
    └── dto/{MemberProfileInput,ProfileUpdateInput,MyProfileResult}.kt  # Int → String/enum

api/src/main/kotlin/com/kbap/api/
├── member/{OnboardingRequest,ProfileUpdateRequest,MyProfileResponse,MemberApi}.kt  # 계약·Swagger
└── admin/AdminMemberQueryService.kt                 # AdminMemberDetailView.spicinessPreference

api/src/main/resources/db/migration/
└── V<timestamp>__member_spiciness_enum.sql          # 신규 — profile JSON 값 이관

테스트(미러링): common·api 의 member/admin/scenario 테스트에서 정수 사용처 전수 갱신
```

**Structure Decision**: 신규 모듈·패키지 없음. member 컨텍스트 내부 표현 교체 + api member/admin 기능 패키지의 계약 갱신만.

## Complexity Tracking

위반 없음 — 해당 없음.
