# Implementation Plan: member 스키마 재편 — 소셜 신원 통합·정지 상태 분리·탈퇴 시 신원 더미 치환

**Branch**: `kb-117-member-schema-consolidation` | **Date**: 2026-07-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-117-member-schema-consolidation/spec.md` (Jira KB-117)

## Summary

`member_social_identities` 테이블을 없애고 소셜 신원 3컬럼(provider·provider_uid·email)을 `member`(구 `members`, 단수형 리네임) 행으로 흡수한다. (provider, provider_uid) 유니크 제약을 member 로 옮겨 로그인 "조회→없으면 가입" 단일 분기를 유지한다(동시 첫 로그인 경합은 발생 확률이 희박해 범위 제외 — R8). 탈퇴는 소프트 삭제 + `provider_uid` 를 `DELETED:{memberId}` 삭제 표식으로 치환해, 유니크 제약을 유지한 채 재가입을 연다(email 은 보존). 정지 상태는 BaseEntity.status(@SQLRestriction 소프트 삭제 전용)와 분리된 `member_status ENUM('ACTIVE','SUSPENDED')` 컬럼으로 표현하고, 서비스 조회(파생 쿼리 조건)에서만 제외하며 관리자 조회(JPA 상속 findById)에는 보이게 한다. 프로필 4항목(기피성분·맵기·국가·언어)은 `profile` JSON 단일 컬럼으로 이관하고, `onboarding_status` 는 BOOLEAN(TINYINT(1)) 저장으로 바꾼다(도메인 enum 유지 — 엔티티 변환). 도메인 `Member` 는 identities(List) → 단일 identity 로 축소하고 `SocialIdentity`·`MemberRepository`·`MemberIdentityResolver` 는 그대로 둔다(`MemberProfile` 은 countryCode 형식 검증만 추가 — R10).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (data-jpa), Hibernate `@JdbcTypeCode(SqlTypes.JSON)`, Flyway(+flyway-mysql)

**Storage**: MySQL 8.4 (prod/local docker) — member 테이블. 마이그레이션은 Flyway 점 구분 timestamp 버전 1건

**Testing**: Kotest BehaviorSpec(한국어 given/when/then) + JUnit 5 플랫폼. 도메인 = 순수 단위, 영속 = `@SpringBootTest` + MySQL 8.4 Testcontainers(`MySqlContainerConfig`, 스키마는 Hibernate 엔티티 생성). Flyway 스크립트는 테스트 미커버 — 로컬 docker MySQL 부팅으로 별도 검증

**Target Platform**: Linux/macOS 서버 (bootJar `:app:api` — Flyway 스키마 owner)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 백엔드 — 이번 변경은 `:core:member`·`:infra:persistence`·`:app:api`(마이그레이션 리소스만) 3개 모듈에 국한. application·web 계층은 member 미사용이라 무접촉

**Performance Goals**: 신원 조회가 fetch join 2회 → 단일 테이블 파생 쿼리로 단순화(유니크 인덱스 커버). 별도 수치 목표 없음

**Constraints**: BaseEntity.status 에 SUSPENDED 추가 금지(@SQLRestriction 상속으로 관리자 조회에서도 사라짐). 마이그레이션은 다른 미적용 마이그레이션과 순서 독립. 시드-동기화 테스트가 참조하는 마이그레이션 파일명 변경 없음(신규 파일만 추가). 프로덕션 이전 단계 — 개발 데이터 기준 무손실 이관

**Scale/Scope**: 파일 단위 — kernel 1개 신규(CountryCode 197개국 enum) + 도메인 2개 수정(Member·MemberProfile) + 도메인 테스트 3개 수정, 영속 4개 수정·1개 삭제(SocialIdentityJpaEntity)·2개 신규(MemberStatus·MemberProfileJson), Flyway 1건 신규, 영속 통합 테스트 1개 이관·확장

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | ✅ PASS | 각 task 는 실패 테스트 선행(Red 확인) 후 구현. 기존 `MemberRepositoryAdapterTest` 의 재가입·중복·소프트삭제 테스트를 새 구조로 이관해 살리고, 더미 치환·정지 노출 테스트를 신규 작성 — tasks 단계에서 테스트 task 를 구현 task 앞에 배치 |
| II. Bounded Contexts | ✅ PASS | member 컨텍스트 내부 재편. 기피성분은 기존처럼 `AvoidanceSubstanceCodeRef` 코드 참조 유지 — avoidance enum import 없음. 타 도메인 모듈 무접촉 |
| III. Layered Dependency Direction | ✅ PASS | 의존 방향 변화 없음. port(`MemberRepository`) 시그니처 불변 — application 계층 영향 0 (현재 member 소비자 없음) |
| IV. Persistence Encapsulation | ✅ PASS | JPA 변경 전부 `:infra:persistence` 안. `MemberStatus`·`MemberProfileJson` 도 영속 모듈에 격리(도메인 ORM-free 유지). 도메인 변환은 엔티티 내 `toDomain`/`from` 유지 |
| V. Domain Content Language Policy | ✅ N/A | 음식 콘텐츠 아님. `appLanguage` 는 기존 `LanguageCode` 참조 그대로 |
| 추가 제약 (외부 호출 tx 금지·도메인 API 노출 금지) | ✅ PASS | 외부 호출 없음, API 표면 없음 |

**Post-Design Re-check (Phase 1 완료 후)**: 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-117-member-schema-consolidation/
├── plan.md              # This file
├── spec.md              # /speckit-specify 출력
├── research.md          # Phase 0 — 결정 8건 (R1~R8)
├── data-model.md        # Phase 1 — 도메인/영속/마이그레이션 모델
├── quickstart.md        # Phase 1 — 검증 절차
├── checklists/requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks — 이 명령이 만들지 않음)
```

contracts/ 는 생성하지 않는다 — 외부 인터페이스(REST API·이벤트) 변경이 없는 순수 내부 재편이고, 유일한 계약면인 `MemberRepository` port 는 시그니처 불변이다.

### Source Code (repository root)

```text
core/kernel/src/main/kotlin/com/meogo/core/kernel/lang/
└── CountryCode.kt                     # 신규 — ISO 3166-1 alpha-2 197개국 enum(한국어 label), 소스 specs/.../countries.json (R10)

core/member/src/main/kotlin/com/meogo/core/member/
├── Member.kt                          # 수정 — identities: List → identity 단일
├── SocialIdentity.kt                  # 불변
├── MemberProfile.kt                   # 수정 — countryCode: String? → CountryCode?(R10), spicinessPreference 비-널 Int·empty() 초기값 5(R11)
├── MemberRepository.kt                # 불변 (port)
└── MemberIdentityResolver.kt          # 불변
core/member/src/test/kotlin/com/meogo/core/member/
├── MemberTest.kt                      # 수정 — 단일 identity 반영
├── MemberProfileTest.kt               # 수정 — countryCode 형식 검증·empty() 기본 5 (R10·R11)
├── MemberIdentityResolverTest.kt      # 수정 — signUp/reconstitute 호출부만
└── SocialIdentityTest.kt              # 불변

infra/persistence/src/main/kotlin/com/meogo/infra/persistence/member/
├── MemberJpaEntity.kt                 # 수정 — @Table("member")·신원 3컬럼 흡수·profile JSON·member_status·유니크 선언
├── SocialIdentityJpaEntity.kt         # 삭제
├── MemberStatus.kt                    # 신규 — ACTIVE/SUSPENDED
├── MemberProfileJson.kt               # 신규 — JSON 스냅샷 (또는 MemberJpaEntity.kt 내 동거)
├── MemberJpaRepository.kt             # 수정 — fetch join 2개 제거, 파생 쿼리 2개
└── MemberRepositoryAdapter.kt         # 수정 — withdraw 더미 치환, 파생 쿼리 사용
infra/persistence/src/test/kotlin/com/meogo/infra/persistence/member/
└── MemberRepositoryAdapterTest.kt     # 이관·확장 — 재가입/중복/프로필 이관 + 더미 잔존·정지 노출 신규

app/api/src/main/resources/db/migration/
└── V<timestamp>__consolidate_member_schema.sql   # 신규 — 생성 시점 로컬 시각으로 명명
```

**Structure Decision**: 기존 모듈러 모놀리스 구조 그대로 — 신규 모듈 없음. 변경은 member 컨텍스트 소유 파일 + Flyway 리소스(스키마 owner `:app:api`)에 국한된다.

## Phase 0: Research 결과 요약

전 항목 [research.md](research.md) 에 기록 — NEEDS CLARIFICATION 0건.

| # | 결정 |
|---|------|
| R1 | 단일 Flyway 스크립트: RENAME → 컬럼 추가 → JOIN 백필 → NOT NULL·유니크 승격 → 구컬럼·구테이블 DROP |
| R2 | 신원 없는 과거 탈퇴 행은 `DELETED:{id}` 표식으로 백필 후 NOT NULL 승격 |
| R3 | 정지 필터는 파생 쿼리 조건(findById·findByIdentity) — 관리자 조회는 JPA 상속 findById(무필터), port 불변 |
| R4 | `MemberStatus` 는 영속 모듈(도메인 미보유 — 정지 운영 도구 도입 시 core 승격) |
| R5 | profile JSON 은 영속 전용 `MemberProfileJson` 스냅샷으로 매핑, nickname 은 컬럼 유지 |
| R6 | 탈퇴 = 소프트 삭제 + `provider_uid="DELETED:{memberId}"` 표식 치환, email·provider 는 보존 |
| R7 | provider·member_status 는 ENUM — 엔티티 columnDefinition·uniqueConstraints 동기화(테스트 스키마 = 엔티티) |
| R8 | 동시 첫 로그인 경합은 범위 제외(발생 확률 희박 — 사용자 결정). 유니크 제약·폴백 코드는 유지하되 검증 대상 아님 |
| R9 | 컬럼명 `provider_uid`(프로퍼티 `providerUid`), `onboarding_status` 는 BOOLEAN 저장 — 도메인 타입 불변, 엔티티 변환 |
| R10 | profile JSON 4키 스키마 확정(읽기 관대·쓰기 완전). 국가코드는 kernel `CountryCode` enum(197개국, 한국어 label — 소스 countries.json)으로 관리, `MemberProfile.countryCode: CountryCode?` — 소비처: 리뷰 국적 필터(review 행 스냅샷 예정)·표시명은 클라이언트 소유 |
| R11 | `MemberProfile.spicinessPreference` 비-널 `Int`·`empty()` 초기값 5(UI 기본 제안값 일치), 기피성분 비-널 Set·빈 셋 — null 상태 소멸(null-safe) |

## Phase 1: Design 산출물

- [data-model.md](data-model.md) — 도메인(Member 단일 identity)·영속(member 테이블·MemberProfileJson·MemberStatus·레포지토리 단순화)·상태 전이·마이그레이션 8단계.
- [quickstart.md](quickstart.md) — Testcontainers 테스트, 로컬 docker MySQL 마이그레이션 검증, 수용 시나리오↔테스트 매핑.
- contracts/ 없음(상기 사유).

## Phase 2 준비 (참고 — /speckit-tasks 입력)

태스크 흐름 권장 순서: ① 도메인 Red→Green(Member 단일 identity) ② 영속 Red(이관·신규 통합 테스트가 새 스키마 가정 — 컴파일/스키마 Red) → Green(엔티티·레포지토리·어댑터 재편) ③ Flyway 스크립트 작성 + 로컬 docker 검증 ④ 전체 빌드 회귀.
