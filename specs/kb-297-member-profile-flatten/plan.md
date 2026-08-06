# Implementation Plan: 회원 프로필 JSON 컬럼 평탄화

**Branch**: `kb-297-member-profile-flatten` | **Date**: 2026-08-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-297-member-profile-flatten/spec.md` (Jira KB-297, 에픽 KB-208)

## Summary

`member.profile` JSON 컬럼(회피 성분 코드 목록·맵기 선호도·국가 코드·프로필 이미지 경로)을 단일 값 컬럼 3종 + 코드 목록 전용 JSON 배열 컬럼(`avoidance_substance_codes`) 으로 평탄화한다. 외부 계약(API 응답·검증·에러)은 불변 — 변경은 `Member` 엔티티 내부 표현과 Flyway 스키마에 국한된다. 소비처 7곳(MyProfileResult·AdminMemberQueryService·ReviewAuthorResponse·ReviewService·CommunityService·BlockedMemberResponse·FoodController)은 전부 `Member.profile: MemberProfile` getter 를 경유하므로 getter 조립만 컬럼 기반으로 바꾸면 소비처 수정이 없다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1 (data-jpa), Flyway(+flyway-mysql), Jackson(제거 대상 — MemberProfileJson 직렬화)

**Storage**: MySQL (prod) / MySQL Testcontainers (통합 테스트, Flyway on + `ddl-auto=validate` 로 엔티티↔스키마 정합 검증)

**Testing**: Kotest BehaviorSpec (+ SpringExtension, MockMvc) — JUnit 5 플랫폼

**Target Platform**: `:api` web bootJar (Flyway 스키마 owner). `:batch` 는 member 엔티티를 스캔만 함(직접 사용 없음)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — 변경 모듈: `:common`(엔티티·값객체), `:api`(Flyway 마이그레이션·테스트)

**Performance Goals**: 기존과 동등 — member 단건 로드 경로 불변(컬럼만 늘고 조인·추가 쿼리 없음)

**Constraints**: 데이터 무손실(SC-001), API 계약 불변(SC-003), 마이그레이션 단계 분리(백필 실패 시 JSON 원본 보존·재시도 가능 — FR-007)

**Scale/Scope**: 소규모 서비스(운영 api 2대 롤링). 회원 수 소량 — 백필 단일 UPDATE/INSERT 로 충분

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS (계획) | tasks 단계에서 Red→Green 순서 강제. 백필 마이그레이션은 Testcontainers 통합 테스트(JSON 시드 → 마이그레이션 적용 → 컬럼 검증)로 선-실패 작성 |
| II. Bounded Contexts | PASS | 변경은 `common.domain.member` 컨텍스트 내부. 회피 성분 참조는 기존과 동일하게 코드 문자열(`AvoidanceSubstanceCodeRef`) — 도메인 간 의존 방향 맵 변경 없음 |
| III. Layered Dependency Direction | PASS | 모듈·패키지 의존 변화 없음. seam·infra 무관 |
| IV. Persistence Ownership | PASS | 엔티티=도메인 모델 유지, 영속은 `common.domain.member` 소유. 연관관계·별도 테이블 없음 — 코드 목록은 JSON 배열 컬럼(기존 profileJson 과 동일 매핑 메커니즘, research.md R2) |
| V. Language Policy | PASS | 음식 콘텐츠·lang 무관 |

**Post-Design Re-check (Phase 1 후)**: 위반 없음 — 신규 테이블·연관관계 없이 member 컬럼 4종 추가로 확정(R2 — 2026-08-05 사용자 결정). Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-297-member-profile-flatten/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

contracts/ 는 생성하지 않는다 — 이 기능은 외부 인터페이스(API 요청·응답) 변경이 0건인 내부 저장 구조 리팩터링이다(SC-003).

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/member/
├── model/
│   ├── Member.kt                     # [수정] profileJson 필드 제거 → 단일 값 컬럼 3종 + 코드 JSON 배열 컬럼, profile getter 조립 변경
│   ├── MemberProfileJson.kt          # [삭제]
│   └── MemberProfile.kt              # [수정] hydration 경로 정리(of 유지, 로드 시 trimStart 정규화 제거 — 백필이 보장)
└── MemberService.kt                  # [불변] — profile getter 계약 유지로 수정 없음

common/src/test/kotlin/com/kbap/common/domain/member/model/
├── MemberProfileTest.kt              # [수정] MemberProfileJson 직렬화/역직렬화 스펙 삭제, 값 검증 스펙 유지
└── MemberTest.kt                     # [수정] profileJson 생성자 사용부 → 컬럼 필드 기반으로 교체

api/src/main/resources/db/migration/
├── V<ts1>__member_profile_flatten_schema.sql    # [신규] member 컬럼 4종 추가(단일 값 3 + 코드 JSON 배열)
├── V<ts2>__member_profile_flatten_backfill.sql  # [신규] JSON → 컬럼/테이블 백필 (MySQL JSON 함수)
└── V<ts3>__member_profile_drop_json.sql         # [신규] profile JSON 컬럼 drop

api/src/test/kotlin/com/kbap/api/
├── member/MemberControllerTest.kt        # [수정] profile JSON 컬럼 직접 검증부(424행 근방) → 컬럼 검증으로 교체
├── admin/AdminMemberPageControllerTest.kt# [수정] MemberProfileJson 시드 → 컬럼 시드
├── community/PostingReadControllerTest.kt# [수정] raw SQL 시드의 profile JSON → 컬럼
└── migration/MemberProfileBackfillTest.kt# [신규] 백필 마이그레이션 통합 검증(JSON 시드 → 백필 결과 대조)
```

**Structure Decision**: 기존 구조 유지 — 엔티티·값 객체는 `common.domain.member.model`, 마이그레이션은 `:api`(스키마 owner). 신규 소스 파일 0개(별도 테이블·엔티티·리포지토리 없음), 신규 파일은 Flyway SQL 3개 + 백필 통합 테스트 1개.

## Complexity Tracking

위반 없음 — 해당 없음.
