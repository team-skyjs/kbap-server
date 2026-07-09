---
description: "Task list for 회원 도메인 — 소셜 신원·프로필·온보딩 상태·탈퇴"
---

# Tasks: 회원 도메인 — 소셜 신원·프로필·온보딩 상태·탈퇴

**Input**: Design documents from `specs/kb-103-member-domain/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/member-ports.md, quickstart.md

**Tests**: Test-First 는 **NON-NEGOTIABLE**(헌법 원칙 I). 각 유저스토리의 테스트를 구현 **전에** 먼저 작성하고 Red 를 확인한다(Kotest BehaviorSpec, 한국어 given/`when`/then).

**Organization**: 유저스토리별 그룹핑. US1(신원 해소)이 도메인+영속 수직 슬라이스를 깔고, US2(프로필·온보딩 전이)·US3(탈퇴)가 도메인 메서드 + 어댑터 메서드를 얹는다. 신원 해소 키는 (provider, providerUserId) 단독 — email 자동 통합 없음. web/application 계층은 범위 밖(소비자는 KB-102/104).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·의존 없음 → 병렬 가능
- **[Story]**: US1/US2/US3 매핑(추적성)
- 모든 경로는 저장소 루트 기준

## Path Conventions

- 도메인: `core/member/src/{main,test}/kotlin/com/meogo/core/member/`
- 영속: `infra/persistence/src/{main,test}/kotlin/com/meogo/infra/persistence/member/`
- 마이그레이션: `app/api/src/main/resources/db/migration/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 모듈 배선 — 신규 모듈 없음(`:core:member` 는 이미 `meogo.domain-conventions` 적용된 빈 모듈)

- [X] T001 `infra/persistence/build.gradle.kts` 에 `"implementation"(project(":core:member"))` 한 줄 추가(기존 `:core:food`·`:core:avoidance` 라인 옆). `core/member` 가 `meogo.domain-conventions`(kernel 만, Spring-free)만 적용됨을 확인하고 빈 모듈이 컴파일되는지 `./gradlew :core:member:compileKotlin` 로 확정한다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리가 공유하는 로직 없는 타입(enum·에러). 순수 상수/계약이라 전용 테스트 불요(원칙 I 의 "로직" 대상 아님).

**⚠️ CRITICAL**: 유저스토리 착수 전 완료

- [X] T002 [P] `core/member/src/main/kotlin/com/meogo/core/member/SocialProvider.kt` 생성 — `enum class SocialProvider { GOOGLE, APPLE }` (추가 가능 구조, data-model.md).
- [X] T003 [P] `core/member/src/main/kotlin/com/meogo/core/member/OnboardingStatus.kt` 생성 — `enum class OnboardingStatus { PENDING, COMPLETED }`.
- [X] T004 [P] `core/member/src/main/kotlin/com/meogo/core/member/MemberErrorCode.kt` + `MemberException.kt` 생성 — kernel `ErrorCode` 구현 enum(`DUPLICATE_SOCIAL_IDENTITY`·`ONBOARDING_ALREADY_COMPLETED`·`MEMBER_NOT_FOUND`, status/message)와 `open class MemberException(errorCode) : MeogoException(errorCode)` (food 전례, exception-hierarchy 컨벤션). 사용자 메시지는 ~습니다 종결.

**Checkpoint**: enum·에러 컴파일 통과 — 스토리 착수 가능

---

## Phase 3: User Story 1 - 소셜 신원 해소로 가입·재로그인 (Priority: P1) 🎯 MVP

**Goal**: (provider, providerUserId)로 신원을 해소해 신규 가입·재로그인을 구분하고, 온보딩 상태를 담은 회원을 반환하는 도메인+영속 수직 슬라이스. 동시 중복 가입은 DB 유니크 + 재조회로 수렴.

**Independent Test**: 같은 (provider, sub)로 두 번 해소 → 1회차 신규 생성(PENDING), 2회차 동일 회원·신규 아님. 같은 email 다른 provider → 별도 회원. 동시 가입 race → 회원 1명.

### Tests for User Story 1 (Test-First: 먼저 작성·FAIL 확인) ⚠️

- [X] T005 [P] [US1] `core/member/src/test/kotlin/com/meogo/core/member/SocialIdentityTest.kt` — providerUserId blank/공백이면 예외, email null 허용, 정상 생성 시 값 보존. Red 확인.
- [X] T006 [P] [US1] `core/member/src/test/kotlin/com/meogo/core/member/MemberTest.kt` — `Member.signUp(identity)` 는 온보딩 PENDING·빈 프로필·identity 1개를 보유하고, identities 가 비면 생성 불가(불변식). Red 확인.
- [X] T007 [P] [US1] `core/member/src/test/kotlin/com/meogo/core/member/MemberIdentityResolverTest.kt` — 페이크 `MemberRepository` 로: (a) 기존 신원 존재 → (member, isNewMember=false), (b) 신원 없음 → signUp·saveNew → (member, true), (c) saveNew 가 `DUPLICATE_SOCIAL_IDENTITY` → 재조회 1회 → (member, false), (d) 동일 email 다른 provider 여도 신규 생성(email 은 해소에 무관), (e) 반환 회원의 onboardingStatus 가 PENDING 그대로 노출(미완료 재방문). Red 확인.
- [X] T008 [P] [US1] `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/member/MemberRepositoryAdapterTest.kt` + `MemberPersistenceTestApp.kt`(`@SpringBootApplication`) — MySQL Testcontainers(testFixtures `MySqlContainerConfig`·`SpringExtension`): saveNew 가 members+member_social_identities 저장, findByIdentity 로 복원, 같은 (provider, providerUserId) 재저장 → `MemberException(DUPLICATE_SOCIAL_IDENTITY)`, findById 는 소프트삭제 회원 미반환. Red 확인.

### Implementation for User Story 1

- [X] T009 [P] [US1] `core/member/src/main/kotlin/com/meogo/core/member/SocialIdentity.kt` — 값 객체(provider·providerUserId·email?), providerUserId blank/trim 불변식.
- [X] T010 [P] [US1] `core/member/src/main/kotlin/com/meogo/core/member/AvoidanceSubstanceCodeRef.kt` + `MemberProfile.kt` — CodeRef(대문자·숫자·underscore 형식, food 미러) 값타입과 `MemberProfile`(nickname?·avoidanceSubstanceCodes: Set·spicinessPreference: Int? (있으면 0~10)·countryCode?·appLanguage: LanguageCode?) + `MemberProfile.empty()`.
- [X] T011 [US1] `core/member/src/main/kotlin/com/meogo/core/member/Member.kt` — `@AggregateRoot` 불변 클래스(id?·identities·profile·onboardingStatus), private constructor + `private fun copy(...)`, `signUp(identity)`(PENDING·empty 프로필)·`reconstitute(...)`, identities 비어있음 불변식. (T009·T010 의존)
- [X] T012 [US1] `core/member/src/main/kotlin/com/meogo/core/member/MemberRepository.kt`(port) + `MemberIdentityResolver.kt`(+`MemberResolution`) — port 는 이번 스토리에 필요한 `findById`·`findByIdentity`·`saveNew` 만 선언(update/withdraw 는 US2/US3 에서 추가). resolver 는 data-model.md 알고리즘(조회→생성→중복 시 재조회). T007 Green.
- [X] T013 [US1] `app/api/src/main/resources/db/migration/V<생성시각 timestamp>__create_member_tables.sql` — `members`(id·nickname VARCHAR(30) NULL·avoidance_substance_codes JSON NOT NULL·spiciness_preference TINYINT NULL·country_code VARCHAR(2) NULL·app_language VARCHAR(10) NULL·onboarding_status VARCHAR(20) NOT NULL·status·created_at·updated_at)·`member_social_identities`(id·member_id BIGINT NOT NULL INDEX·provider VARCHAR(20)·provider_user_id VARCHAR(255)·email VARCHAR(255) NULL·status·시각, **UNIQUE(provider, provider_user_id)**). MySQL 기준 컬럼, 파일명은 생성 시각 점-timestamp(Flyway 컨벤션).
- [X] T014 [P] [US1] `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/member/MemberJpaEntity.kt` + `SocialIdentityJpaEntity.kt` — BaseEntity 상속, JSON 매핑(`@JdbcTypeCode(SqlTypes.JSON)` avoidance codes), 단방향 `@OneToMany(cascade=ALL, orphanRemoval=true, LAZY, @JoinColumn(member_id))` identities. `toDomain()` + `companion from(domain)` (변환은 엔티티 안).
- [X] T015 [US1] `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/member/MemberJpaRepository.kt` + `MemberRepositoryAdapter.kt` — Spring Data(신원 fetch join 조회) + port 구현: saveNew 시 DataIntegrityViolation → `MemberException(DUPLICATE_SOCIAL_IDENTITY)` 번역, findById/findByIdentity 는 `@SQLRestriction` 로 ACTIVE 만. T008 Green.

**Checkpoint**: 신원 해소가 도메인 단위 + 영속 통합에서 독립 검증됨(MVP). 로그인(KB-102)이 바로 소비 가능.

---

## Phase 4: User Story 2 - 프로필·온보딩 상태 보유와 전이 (Priority: P2)

**Goal**: 회원 프로필(닉네임·기피성분·맵기·국가·언어) 저장·조회와 온보딩 PENDING→COMPLETED 전이.

**Independent Test**: 프로필 저장 후 재조회 시 값 유지, PENDING 회원 완료 처리 시 COMPLETED 전이·유지, COMPLETED 재완료 시 예외.

### Tests for User Story 2 (Test-First) ⚠️

- [X] T016 [P] [US2] `core/member/src/test/kotlin/com/meogo/core/member/MemberTest.kt` 에 전이 케이스 추가 — `updateProfile(profile)` 는 새 인스턴스에 값 반영(불변, 원본 유지), `completeOnboarding()` 은 PENDING→COMPLETED, COMPLETED 재호출 시 `MemberException(ONBOARDING_ALREADY_COMPLETED)`. Red 확인.
- [X] T017 [P] [US2] `core/member/src/test/kotlin/com/meogo/core/member/MemberProfileTest.kt` — spiciness 0~10 경계·범위 밖 예외, CodeRef 집합·닉네임·국가·언어 보존. Red 확인.
- [X] T018 [P] [US2] `MemberRepositoryAdapterTest.kt` 에 update 라운드트립 추가 — 프로필·온보딩 상태 갱신 후 findById 재조회 값 일치(기피성분 JSON·맵기·국가·언어·COMPLETED). Red 확인.

### Implementation for User Story 2

- [X] T019 [US2] `core/member/src/main/kotlin/com/meogo/core/member/Member.kt` 에 `updateProfile(profile): Member`·`completeOnboarding(): Member` 추가(전이 규칙·불변 복제). T016 Green.
- [X] T020 [US2] `core/member/src/main/kotlin/com/meogo/core/member/MemberProfile.kt` 의 spiciness 범위 불변식 확정(T017 Green — T010 에서 골격만 있었다면 여기서 검증 강화).
- [X] T021 [US2] `MemberRepository.kt` 에 `update(member): Member` 추가 + `MemberRepositoryAdapter` 구현(엔티티 프로필 컬럼·onboarding_status 갱신, identities 유지). T018 Green.

**Checkpoint**: 프로필·온보딩 전이가 독립 검증됨. 온보딩 API(KB-104)가 소비 가능.

---

## Phase 5: User Story 3 - 회원 탈퇴 (Priority: P3)

**Goal**: 탈퇴 = 회원 행 soft delete + 신원 행 물리 삭제. 탈퇴 회원은 조회·해소에서 제외, 재로그인은 신규 가입.

**Independent Test**: 활성 회원 탈퇴 후 findById 부재, 같은 소셜 계정 재해소 시 신규 회원 생성.

### Tests for User Story 3 (Test-First) ⚠️

- [X] T022 [P] [US3] `MemberRepositoryAdapterTest.kt` 에 탈퇴 케이스 추가 — withdraw 후 findById/findByIdentity 부재(members status=DELETED, member_social_identities 물리 삭제), 같은 (provider, providerUserId) 재저장(saveNew) 이 유니크 충돌 없이 성공(재가입), 부재 회원 withdraw 시 `MemberException(MEMBER_NOT_FOUND)`. Red 확인.

### Implementation for User Story 3

- [X] T023 [US3] `MemberRepository.kt` 에 `withdraw(id)` 추가 + `MemberRepositoryAdapter` 구현 — member `BaseEntity.delete()`(status=DELETED) + 해당 member_social_identities 물리 삭제(신원 JpaRepository deleteByMemberId), 부재 시 `MEMBER_NOT_FOUND`. T022 Green.

**Checkpoint**: 신원 해소·프로필·탈퇴 전 스토리 독립 검증.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 경계 검증·마이그레이션 실측·회귀

- [X] T024 로컬 docker MySQL 로 Flyway 마이그레이션 실측 — `docker compose up -d meogo-mysql` → DB DROP+CREATE → `SPRING_PROFILES_ACTIVE=local` 부팅으로 `V<...>__create_member_tables.sql` 적용·`flyway_schema_history` 확인(테스트에선 마이그레이션이 돌지 않음, flyway-migration-validation-gap 컨벤션). 8080 점유 시 IntelliJ 앱 종료 요청(broad pkill 금지).
- [X] T025 `./gradlew build` — ArchUnit `ModuleBoundaryTest`(member 도메인 ORM/Spring-free·avoidance enum 미import·엔티티 위치) 포함 전체 통과 확인. Kotlin 주석 0건(컨벤션) 점검.
- [X] T026 [P] quickstart.md 절차대로 `:core:member:test`·`:infra:persistence:test` 재실행해 전 스토리 그린 확인.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup(P1)**: 즉시 시작.
- **Foundational(P2)**: Setup 후 — 전 스토리 차단(enum·에러).
- **US1(P3)**: Foundational 후. 도메인+영속 수직 슬라이스 — 이후 스토리의 기반(port·엔티티·마이그레이션).
- **US2(P4)·US3(P5)**: US1 완료 후(같은 Member 클래스·MemberRepository port·어댑터를 확장). US2 와 US3 는 서로 독립(다른 메서드).
- **Polish(P6)**: 원하는 스토리 완료 후.

### User Story Dependencies

- **US1**: Foundational 만 의존. 단독 MVP.
- **US2**: US1 의 Member·port·어댑터·마이그레이션 위에 메서드 추가(전이·update).
- **US3**: US1 위에 withdraw 추가. US2 와 무관하게 병렬 가능.

### Within Each User Story

- 테스트 먼저 작성·Red 확인 후 구현(원칙 I).
- 값객체·enum → 애그리거트 → port/resolver → 엔티티/어댑터 → 마이그레이션 순.
- 커밋은 task 또는 논리 단위마다.

### Parallel Opportunities

- T002·T003·T004 (Foundational) 병렬.
- US1 테스트 T005·T006·T007·T008 병렬(다른 파일). 구현 중 T009·T010·T014 병렬.
- US2·US3 는 US1 후 서로 병렬 가능.

---

## Parallel Example: User Story 1

```bash
# US1 테스트 먼저(모두 FAIL 확인):
Task: "SocialIdentityTest 작성 (core/member/.../SocialIdentityTest.kt)"
Task: "MemberTest 작성 (core/member/.../MemberTest.kt)"
Task: "MemberIdentityResolverTest 작성 (core/member/.../MemberIdentityResolverTest.kt)"
Task: "MemberRepositoryAdapterTest 작성 (infra/persistence/.../MemberRepositoryAdapterTest.kt)"

# 구현 중 병렬 가능:
Task: "SocialIdentity 값객체 (core/member/.../SocialIdentity.kt)"
Task: "AvoidanceSubstanceCodeRef + MemberProfile (core/member/.../MemberProfile.kt)"
Task: "Member/SocialIdentity JPA 엔티티 (infra/persistence/.../MemberJpaEntity.kt)"
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 Setup → Phase 2 Foundational → Phase 3 US1.
2. **STOP & VALIDATE**: 신원 해소 도메인 단위 + 영속 통합 그린. 로그인(KB-102) 이 붙일 수 있는 완결 슬라이스.

### Incremental Delivery

1. Setup+Foundational → US1(MVP, 신원 해소) → US2(프로필·전이) → US3(탈퇴).
2. 각 스토리는 이전을 깨지 않고 값 추가.

---

## Notes

- [P] = 다른 파일·의존 없음.
- 도메인은 완전 Spring-free·ORM-free — JPA/Spring 은 `:infra:persistence` 에만(원칙 III·IV, ArchUnit 강제).
- Kotlin 소스 주석 금지(컨벤션) — 의도는 이름·구조로.
- 테스트는 Kotest BehaviorSpec, 한국어 given/`when`/then. 통합 테스트는 Docker 필요(MySQL Testcontainers).
- 마이그레이션 파일명·checksum 은 공유 DB 적용 후 수정 금지(현재 로컬 전용이라 실측 후 확정).
