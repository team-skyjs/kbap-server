# Implementation Plan: 회원 도메인 — 소셜 신원·프로필·온보딩 상태·탈퇴

**Branch**: `kb-103-member-domain` | **Date**: 2026-07-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-103-member-domain/spec.md`

## Summary

빈 스켈레톤인 `:core:member` 에 회원 애그리거트(Member 1—N SocialIdentity·프로필·온보딩 상태)와 신원 해소 도메인 서비스를 만들고, `:infra:persistence` 에 JPA 어댑터·Flyway 마이그레이션을 얹는 2계층 수직 슬라이스. 신원 해소 키는 **(provider, providerUserId) 단독**(이메일 자동 통합 철회 — 사용자 결정 2026-07-08), 동시 중복 가입은 DB 유니크 + 예외 번역 + 재조회 1회로 수렴, 탈퇴는 회원 soft delete + 신원 hard delete. web/application 계층은 범위 밖(소비자인 KB-102 로그인·KB-104 온보딩이 조립).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: `:core:member` 는 `meogo.domain-conventions`(kernel 만, 완전 Spring-free). `:infra:persistence` 는 Spring Data JPA + Hibernate(JSON 매핑 `@JdbcTypeCode(SqlTypes.JSON)`).

**Storage**: MySQL 8.4 (Flyway 마이그레이션, 스키마 owner = `:app:api`). 신규 테이블 `members`·`member_social_identities`.

**Testing**: Kotest BehaviorSpec(given/when/then 한국어). 도메인 = 순수 단위(+fake repository), 영속 = MySQL Testcontainers 통합(`MySqlContainerConfig` testFixtures).

**Target Platform**: Linux server (Spring Boot 4.1 모듈러 모놀리스의 도메인/영속 계층)

**Project Type**: web-service 백엔드 — 이번 슬라이스는 HTTP 미노출(도메인+영속만)

**Performance Goals**: 해당 없음(단건 CRUD·유니크 조회 — 특이 성능 요구 없음)

**Constraints**: 동시 최초 로그인에서 중복 가입 0건(DB 유니크가 보장), 탈퇴 즉시 전 조회 미노출(@SQLRestriction), PII(제공자 sub·email) 탈퇴 시 물리 삭제

**Scale/Scope**: 도메인 클래스 ~8개 + 엔티티 2개 + 어댑터 1개 + 마이그레이션 1개 + 테스트 ~5개 파일

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | ✅ PASS | 모든 task 를 Red→Green 순서로 배열(도메인 불변·resolver·어댑터·유니크 위반 모두 실패 테스트 선행). tasks.md 에서 강제. |
| II. Bounded Contexts | ✅ PASS | member 는 avoidance enum 을 import 하지 않고 자체 `AvoidanceSubstanceCodeRef` 값타입으로 코드 참조(R9, food 전례). kernel 공유 vocabulary(`LanguageCode`)만 사용. 타 도메인 모듈 의존 없음. |
| III. Layered Dependency Direction | ✅ PASS | `:core:member` → `:core:kernel` 만(`meogo.domain-conventions`). `:infra:persistence` 가 member 를 `implementation` 으로 의존해 port 구현. 상위 계층 변경 없음. |
| IV. Persistence Encapsulation | ✅ PASS | JPA 엔티티·Spring Data Repository·어댑터 전부 `:infra:persistence`(`com.meogo.infra.persistence.member`). 도메인은 ORM-free. ArchUnit `ModuleBoundaryTest` 가 com.meogo 전체 스캔으로 자동 커버. |
| V. Domain Content Language Policy | ✅ PASS (해당 없음) | 음식 콘텐츠 아님. 앱 언어 값으로 kernel `LanguageCode`(ko+9) 재사용만 — 번역 데이터 없음. |
| Additional Constraints | ✅ PASS | 도메인/영속 모델 API 노출 없음(HTTP 자체가 없음). 외부 호출 없음(트랜잭션 무관). |

**Post-Phase-1 재평가**: 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-103-member-domain/
├── plan.md              # This file
├── research.md          # Phase 0 — R1~R10 결정
├── data-model.md        # Phase 1 — 도메인·테이블 설계
├── quickstart.md        # Phase 1 — 빌드·검증 절차
├── contracts/
│   └── member-ports.md  # Phase 1 — port·도메인 서비스 계약
└── tasks.md             # Phase 2 (/speckit-tasks — 이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
core/member/
├── src/main/kotlin/com/meogo/core/member/
│   ├── Member.kt                        # @AggregateRoot, signUp/updateProfile/completeOnboarding
│   ├── SocialIdentity.kt                # 값 객체 (provider·providerUserId·email?)
│   ├── SocialProvider.kt                # enum GOOGLE/APPLE
│   ├── MemberProfile.kt                 # 값 객체 (닉네임·기피성분·맵기·국가·언어, +empty())
│   ├── AvoidanceSubstanceCodeRef.kt     # member 소유 코드 참조 값타입 (R9)
│   ├── OnboardingStatus.kt              # enum PENDING/COMPLETED
│   ├── MemberRepository.kt              # port
│   ├── MemberIdentityResolver.kt        # 도메인 서비스 (+MemberResolution)
│   ├── MemberErrorCode.kt               # kernel ErrorCode 구현
│   └── MemberException.kt               # MeogoException 하위
└── src/test/kotlin/com/meogo/core/member/
    ├── MemberTest.kt
    ├── MemberProfileTest.kt
    ├── SocialIdentityTest.kt
    └── MemberIdentityResolverTest.kt    # fake repository 로 신규/기존/race/email 무관

infra/persistence/
├── src/main/kotlin/com/meogo/infra/persistence/member/
│   ├── MemberJpaEntity.kt               # toDomain()/from() (변환은 엔티티 안)
│   ├── SocialIdentityJpaEntity.kt
│   ├── MemberJpaRepository.kt           # Spring Data (fetch join 조회)
│   └── MemberRepositoryAdapter.kt       # port 구현, 예외 번역·withdraw
└── src/test/kotlin/com/meogo/infra/persistence/member/
    ├── MemberPersistenceTestApp.kt      # 기존 도메인별 TestApp 패턴 미러
    └── MemberRepositoryAdapterTest.kt   # Testcontainers 통합

app/api/src/main/resources/db/migration/
└── V<생성시각>__create_member_tables.sql
```

**Structure Decision**: 기존 컨텍스트(food·scan·avoidance)와 동일한 미러 배치. 신규 모듈·빌드 파일 변경 없음(`:core:member` 는 이미 `meogo.domain-conventions` 적용, `:infra:persistence` 는 이미 member 를 의존하는지 확인 후 필요 시 `implementation(project(":core:member"))` 한 줄 추가).

## Complexity Tracking

위반 없음 — 해당 없음.
