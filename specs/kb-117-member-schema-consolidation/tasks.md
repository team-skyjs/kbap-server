# Tasks: member 스키마 재편 — 소셜 신원 통합·정지 상태 분리·탈퇴 시 신원 더미 치환

**Input**: Design documents from `/specs/kb-117-member-schema-consolidation/`

**Prerequisites**: plan.md, spec.md, research.md (R1~R11), data-model.md, quickstart.md, countries.json

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 스토리는 실패 테스트(Red — 컴파일 에러도 Red 로 인정) 작성·확인 후 구현(Green)한다. 테스트는 Kotest `BehaviorSpec`, given/when/then 한국어.

**Organization**: 유저 스토리별 그룹화. 단, **실행 순서는 우선순위(P1→P3)가 아니라 의존성 순서(US3→US1→US2)** 다 — 스키마 단일화(US3)가 US1(탈퇴 재가입)·US2(정지 상태)의 물리적 전제이기 때문(spec.md US3 "Why this priority" 명시). 각 스토리는 자기 phase 완료 시점에 독립 검증 가능하다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일·미완료 태스크 의존 없음)
- **[Story]**: US1(탈퇴 재가입)·US2(정지 상태)·US3(신원·스키마 단일화)

## Path Conventions

Gradle 멀티모듈 — `core/kernel`·`core/member`·`infra/persistence`·`app/api`(Flyway 리소스). 모든 경로는 리포 루트 기준.

---

## Phase 1: Setup — CountryCode vocabulary (R10)

**Purpose**: member·(미래) review 가 공유할 국가코드 enum 을 kernel 에 신설 — 이후 도메인 재편이 참조

- [X] T001 [P] CountryCode 실패 테스트 작성: `core/kernel/src/test/kotlin/com/meogo/core/kernel/lang/CountryCodeTest.kt` — 197개국·전 상수명 `^[A-Z]{2}$`·대표값 존재(`KR`(label "대한민국")·`US`·`JP`), Red(컴파일 에러) 확인 (`./gradlew :core:kernel:test`)
- [X] T002 CountryCode enum 생성: `core/kernel/src/main/kotlin/com/meogo/core/kernel/lang/CountryCode.kt` — `specs/kb-117-member-schema-consolidation/countries.json`(code/nameKo) 197건을 상수(코드) + 한국어 `label` 로 변환(LanguageCode 와 동형, Spring-free), T001 Green 확인

---

## Phase 2: Foundational — 도메인 Member 재편 (R9·R10·R11)

**Purpose**: 모든 스토리가 딛는 도메인 모델 변경 — 단일 identity·CountryCode 타입·비-널 맵기

**⚠️ CRITICAL**: 이 phase 완료 전에 어떤 스토리도 시작 불가

- [X] T003 도메인 실패 테스트 수정: `core/member/src/test/kotlin/com/meogo/core/member/MemberTest.kt`(단일 identity `signUp`/`reconstitute`)·`MemberProfileTest.kt`(`countryCode: CountryCode?`·`spicinessPreference` 비-널 `Int`·`empty()` = 맵기 5·빈 셋) — Red(컴파일 에러) 확인 (`./gradlew :core:member:test`)
- [X] T004 도메인 Green: `core/member/src/main/kotlin/com/meogo/core/member/Member.kt`(identities: List → `identity: SocialIdentity` 단일, `isNotEmpty` require 제거 — 타입이 대체)·`MemberProfile.kt`(countryCode `CountryCode?`, spicinessPreference `Int`, `empty()` 초기값 5) 수정 + `MemberIdentityResolverTest.kt` 호출부 컴파일 복구, `:core:member:test` 전체 Green 확인

**Checkpoint**: 도메인 모델 확정 — 영속 재편 시작 가능

---

## Phase 3: User Story 3 — 회원 신원·스키마 단일화 (Priority: P3, **의존성상 선행**) 🎯

**Goal**: member_social_identities 를 member 행으로 흡수(단수형 리네임·유니크 유지), 프로필 4항목 → profile JSON, onboarding BOOLEAN, member_status 컬럼 신설

**Independent Test**: 신원 조회 0건/1건 보장·프로필 JSON 저장/복원을 영속 통합 테스트(Testcontainers)로 검증, Flyway 는 로컬 docker MySQL 부팅으로 검증

### Tests for User Story 3 (Test-First — 작성 후 반드시 Red 확인) ⚠️

- [X] T005 [US3] 영속 통합 테스트 이관: `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/member/MemberRepositoryAdapterTest.kt` 를 새 구조(단일 identity·`member` 테이블 DDL)로 재작성 — ① saveNew→findByIdentity 복원(신원·onboarding PENDING) ② 프로필 전값 저장→findById 복원(기피성분 셋·맵기·`CountryCode.KR`·`LanguageCode.EN`·onboarding COMPLETED=BOOLEAN 저장) ③ 빈 프로필 저장 시 맵기 5 복원 ④ 동일 (provider, providerUid) 중복 saveNew → `DUPLICATE_SOCIAL_IDENTITY`(0건/1건 보장 — FR-002) ⑤ update 갱신 복원 ⑥ 소프트삭제 회원 조회 제외 — `clear()`/직접 SQL 은 `member` 테이블 기준으로 갱신, Red(컴파일 에러) 확인 (`./gradlew :infra:persistence:test`)

### Implementation for User Story 3

- [X] T006 [US3] 영속 Green: `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/member/` — ① `MemberJpaEntity.kt` 재편: `@Table(name = "member", uniqueConstraints = [(provider, provider_uid)])`, 신원 3컬럼 흡수(`provider` columnDefinition `ENUM('GOOGLE','APPLE')`·`providerUid`·`email`), `profile` JSON(`@JdbcTypeCode(SqlTypes.JSON)` `MemberProfileJson` — 같은 파일 또는 별도 파일), `memberStatus` columnDefinition `ENUM('ACTIVE','SUSPENDED')` default ACTIVE, `onboardingCompleted: Boolean` ↔ 도메인 enum 변환, @OneToMany 제거 ② `MemberStatus.kt` 신규(ACTIVE/SUSPENDED) ③ `SocialIdentityJpaEntity.kt` 삭제 ④ `MemberJpaRepository.kt` fetch join 2개 삭제 → 파생 `findByProviderAndProviderUid`(정지 조건은 US2 에서) ⑤ `MemberRepositoryAdapter.kt` 새 매핑 사용(withdraw 는 일단 소프트 삭제만 — 더미 치환은 US1), T005 전체 Green 확인
- [X] T007 [US3] Flyway 마이그레이션 작성: `app/api/src/main/resources/db/migration/V<생성 시점 로컬시각 점구분>__consolidate_member_schema.sql` — data-model.md 9단계(RENAME→컬럼 추가→JOIN 백필→신원 없는 행 `withdrawn:{id}`·GOOGLE 백필→profile `JSON_OBJECT`(맵기 `COALESCE(...,5)`)→onboarding BOOLEAN 변환→NOT NULL·유니크 승격→구컬럼 DROP→`member_social_identities` DROP), 다른 미적용 마이그레이션과 순서 독립
- [X] T008 [US3] Flyway 로컬 검증(테스트 미커버 영역): `docker compose up -d meogo-mysql` → `meogo` DB DROP+CREATE → `SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun` 부팅 성공 → `SHOW CREATE TABLE member`(ENUM·유니크·profile JSON)·`member_social_identities` 부재 확인(quickstart.md §2)

**Checkpoint**: 새 스키마에서 저장·조회·중복 방어·프로필 복원 완전 동작 — US1·US2 시작 가능

---

## Phase 4: User Story 1 — 탈퇴 후 같은 소셜 계정으로 재가입 (Priority: P1) 🎯 MVP

**Goal**: 탈퇴 = 소프트 삭제 + provider_uid `withdrawn:{memberId}` 치환 + email NULL — 재가입 개방·개인정보 미잔존

**Independent Test**: 가입→탈퇴→같은 계정 재가입 성공, 탈퇴 행에 원본 식별자·이메일 미잔존을 영속 통합 테스트로 검증

### Tests for User Story 1 (Test-First — 작성 후 반드시 Red 확인) ⚠️

- [X] T009 [US1] 탈퇴·재가입 실패 테스트 추가: `MemberRepositoryAdapterTest.kt` — ① 탈퇴 후 findById·findByIdentity 제외 + 같은 소셜 계정 재가입 성공(새 id) ② 탈퇴 행 직접 SELECT 로 `provider_uid = 'withdrawn:{id}'`·`email IS NULL` 검증(원본 미잔존 — FR-004) ③ 가입→탈퇴 2회 반복에도 유니크 충돌 없음 ④ 존재하지 않는 회원 탈퇴 → `MEMBER_NOT_FOUND`, Red 확인

### Implementation for User Story 1

- [X] T010 [US1] withdraw Green: `MemberRepositoryAdapter.kt` — 활성 회원 로드 → `providerUid = "withdrawn:{id}"` 치환 → `email = null` → `delete()`(소프트 삭제) 순 구현(R6), T009 Green + `:infra:persistence:test` 전체 회귀 확인

**Checkpoint**: P1 수용 시나리오 전부 통과 — 재가입 흐름 완결

---

## Phase 5: User Story 2 — 회원 정지 상태 관리 (Priority: P2)

**Goal**: member_status 를 소프트 삭제와 별개 축으로 — 서비스 조회 제외, 관리자 조회 노출

**Independent Test**: 직접 SQL 로 정지 회원을 만들어 서비스 경로(어댑터)·관리자 경로(JPA findById) 노출 차이를 영속 통합 테스트로 검증

### Tests for User Story 2 (Test-First — 작성 후 반드시 Red 확인) ⚠️

- [X] T011 [US2] 정지 상태 실패 테스트 추가: `MemberRepositoryAdapterTest.kt` — ① 신규 가입 회원 member_status 기본 ACTIVE(직접 SELECT) ② `UPDATE member SET member_status='SUSPENDED'` 후 어댑터 findById·findByIdentity 0건(서비스 제외 — FR-007) ③ 같은 회원이 `MemberJpaRepository.findById`(관리자 경로)로는 조회됨 ④ 정지 회원은 소프트 삭제와 무관(status 는 ACTIVE 유지), Red 확인

### Implementation for User Story 2

- [X] T012 [US2] 정지 필터 Green: `MemberJpaRepository.kt` 파생 쿼리를 `findByIdAndMemberStatus`·`findByProviderAndProviderUidAndMemberStatus` 로 교체하고 `MemberRepositoryAdapter.kt` 가 ACTIVE 를 전달(R3 — port 시그니처 불변, BaseEntity.status 는 무변경), T011 Green + 전체 회귀 확인

**Checkpoint**: 세 스토리 수용 시나리오 전부 독립 통과

---

## Phase 6: Polish & Cross-Cutting

**Purpose**: 전체 회귀·경계 검증·산출물 정리

- [X] T013 전체 빌드 회귀: `./gradlew build` — ArchUnit `ModuleBoundaryTest`(도메인 Spring/ORM-free·계층 방향) 포함 전 모듈 Green, Kotlin 주석 금지·BehaviorSpec 한국어 규약 위반 없는지 훑기
- [X] T014 quickstart.md 검증 절차 완주 확인 + 태스크/논리 단위 커밋 정리(작업 단위마다 커밋 — 헌법 Workflow)

---

## Dependencies & Execution Order

### Phase Dependencies

```
Phase 1 (Setup: CountryCode)
  → Phase 2 (Foundational: 도메인 재편)     ← T003 이 CountryCode 참조
    → Phase 3 (US3: 영속·스키마 단일화)     ← 엔티티가 도메인 타입 참조
      → Phase 4 (US1: 탈퇴 더미 치환)       ← withdraw 가 새 엔티티 전제
      → Phase 5 (US2: 정지 필터)            ← member_status 컬럼 전제, US1 과 독립
        → Phase 6 (Polish)
```

- **US1·US2 는 서로 독립** — US3 완료 후 병렬 진행 가능(다른 관심사·같은 테스트 파일이므로 순차 권장)
- 우선순위(P1) 기준 MVP 는 **Phase 1~4 완료 시점**(재가입 흐름) — US2 는 그 위에 증분

### Parallel Opportunities

- T001(kernel 테스트) ∥ T003(member 도메인 테스트) — 다른 모듈. 단 T003 의 CountryCode 참조 컴파일은 T002 이후 Green 가능
- 그 외는 같은 파일(`MemberRepositoryAdapterTest.kt`·`MemberRepositoryAdapter.kt`)을 순차 수정하므로 병렬 불가 — 단일 작업자 순차 진행이 안전

## Parallel Example

```bash
# Phase 1·2 Red 를 함께 착수 (다른 모듈):
Task: "T001 CountryCodeTest.kt 실패 테스트 (core/kernel)"
Task: "T003 MemberTest·MemberProfileTest 수정 (core/member)"
```

## Implementation Strategy

1. Phase 1~3 완료 → **새 스키마 위에서 저장·조회 정합 확보** (Flyway 까지 검증)
2. Phase 4(US1) → 재가입 흐름 완결 — **P1 MVP, 여기서 멈추고 검증/커밋 가능**
3. Phase 5(US2) → 정지 상태 증분
4. Phase 6 → 전체 회귀·정리
- 각 태스크 완료마다 커밋(Red 커밋은 Green 과 묶어도 됨 — 논리 단위 유지)

## Notes

- Red 단계는 **반드시 실행해 실패를 확인**한다 — 컴파일 에러도 Red 로 인정(구조 변경 특성상)
- 영속 테스트 스키마는 Hibernate 가 엔티티에서 생성 — 유니크·ENUM 은 엔티티 선언이 곧 테스트 스키마(R7). Flyway 는 T008 로컬 docker 로만 검증
- `MemberIdentityResolver`·`MemberRepository`(port)·`SocialIdentity`·`OnboardingStatus`·`BaseEntity` 는 수정 금지 대상
- 동시 첫 로그인 경합 테스트는 작성하지 않는다(R8 — 범위 제외)
