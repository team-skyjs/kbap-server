# Data Model: 회원 도메인

**Date**: 2026-07-08 | **Spec**: [spec.md](spec.md) | **Research**: [research.md](research.md)

## 도메인 모델 (`:core:member`, `com.meogo.core.member`)

### Member (@AggregateRoot, 불변)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long? | 영속 식별자 (신규 생성 시 null) |
| identities | List\<SocialIdentity\> | 소셜 신원 1..N (이번 범위에선 생성 시 1개) |
| profile | MemberProfile | 프로필 (가입 직후 빈 값) |
| onboardingStatus | OnboardingStatus | PENDING / COMPLETED |

- 불변식: `identities` 는 비어 있을 수 없다. `identities` 내 (provider, providerUserId) 중복 불가.
- 상태 변경은 새 인스턴스 반환: `updateProfile(profile): Member`, `completeOnboarding(): Member`.
- `completeOnboarding()` 은 PENDING 에서만 허용 — COMPLETED 재호출 시 `MemberException(ONBOARDING_ALREADY_COMPLETED)` (R8).
- 생성: `Member.signUp(identity: SocialIdentity): Member` — 빈 프로필 + PENDING (FR-006). 복원: `reconstitute(...)`.
- private constructor + `private fun copy(...)` (public copy 미노출 — 컨벤션).

### SocialIdentity (값 객체)

| 필드 | 타입 | 설명 |
|------|------|------|
| provider | SocialProvider | GOOGLE / APPLE |
| providerUserId | String | 제공자 sub — blank 불가 |
| email | String? | 참고 정보, 부재 허용 (매칭 키 아님, R10) |

### MemberProfile (값 객체)

| 필드 | 타입 | 설명 |
|------|------|------|
| nickname | String? | 표시용, 미설정 null. 유일성 강제 안 함 (R11) |
| avoidanceSubstanceCodes | Set\<AvoidanceSubstanceCodeRef\> | 기피성분 코드 집합 (기본 빈 집합) |
| spicinessPreference | Int? | 0~10 (형식 불변식), 미설정 null |
| countryCode | String? | ISO 3166-1 alpha-2 가정, 목록 검증은 KB-104 |
| appLanguage | LanguageCode? | kernel 공유 vocabulary (10개국어) |

- `MemberProfile.empty()` — 가입 직후 상태 (US2 시나리오 3).

### AvoidanceSubstanceCodeRef (값 객체)

- `core.food.AvoidanceSubstanceCodeRef` 미러 (원칙 II — 컨텍스트별 소유, R9): blank 불가·trim·`^[A-Z0-9_]+$`.

### OnboardingStatus (enum)

```
PENDING ──completeOnboarding()──▶ COMPLETED    (역방향·기타 전이 없음)
```

### SocialProvider (enum)

- GOOGLE, APPLE (추가 가능 구조 — 스펙 Assumption).

### MemberErrorCode / MemberException

- kernel `ErrorCode` 구현 enum + `MeogoException` 하위 도메인 예외 (exception-hierarchy 컨벤션).
- 코드: `DUPLICATE_SOCIAL_IDENTITY`, `ONBOARDING_ALREADY_COMPLETED`, `MEMBER_NOT_FOUND`, (형식 불변식 위반은 require 메시지).

### MemberIdentityResolver (도메인 서비스, Spring-free)

- `resolve(identity: SocialIdentity): MemberResolution`
- `MemberResolution(member: Member, isNewMember: Boolean)` (FR-004) — 온보딩 상태는 `member.onboardingStatus` 로 노출되므로 별도 필드 불요. 로그인(KB-102)은 응답에 **`isNewUser` 와 `onboardingStatus`(또는 `needsOnboarding = status == PENDING`) 둘 다** 실어 재방문 온보딩 유도를 지원한다(R11).
- 알고리즘 (FR-003, R4):
  1. `repository.findByIdentity(provider, providerUserId)` → 있으면 `(member, false)`
  2. 없으면 `Member.signUp(identity)` → `repository.saveNew(...)` → `(saved, true)`
  3. `saveNew` 가 DUPLICATE_SOCIAL_IDENTITY 예외 → 재조회 1회 → `(found, false)` (동시 가입 race 수렴)

### MemberRepository (port)

| 메서드 | 반환 | 비고 |
|--------|------|------|
| findById(id: Long) | Member? | 탈퇴(soft delete) 회원 미노출 (FR-009) |
| findByIdentity(provider, providerUserId) | Member? | 〃 |
| saveNew(member: Member) | Member | 유니크 위반 → MemberException(DUPLICATE_SOCIAL_IDENTITY) |
| update(member: Member) | Member | 프로필·온보딩 상태 갱신 |
| withdraw(id: Long) | Unit | 회원 soft delete + 신원 hard delete (R5), 부재 시 MEMBER_NOT_FOUND |

## 영속 모델 (`:infra:persistence`, `com.meogo.infra.persistence.member`)

### 테이블 `members` (R2)

| 컬럼 | 타입 | 제약 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT | PK |
| nickname | VARCHAR(30) | NULL, 유일 제약 없음 (R11) |
| avoidance_substance_codes | JSON | NOT NULL (문자열 배열, 기본 `[]`) (R6) |
| spiciness_preference | INT | NULL (R7, 도메인 Int? 매핑 — ddl-auto=validate 정합) |
| country_code | VARCHAR(2) | NULL |
| app_language | VARCHAR(10) | NULL |
| onboarding_status | VARCHAR(20) | NOT NULL (BaseEntity `status` 와 별개 컬럼 — scan 전례) |
| status | VARCHAR(20) | NOT NULL (BaseEntity 소프트삭제) |
| created_at / updated_at | DATETIME(6) | NOT NULL |

### 테이블 `member_social_identities` (R2)

| 컬럼 | 타입 | 제약 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT | PK |
| member_id | BIGINT | NOT NULL, FK → members.id, INDEX |
| provider | VARCHAR(20) | NOT NULL |
| provider_user_id | VARCHAR(255) | NOT NULL |
| email | VARCHAR(255) | NULL, 인덱스 없음 (R10) |
| status | VARCHAR(20) | NOT NULL (BaseEntity) |
| created_at / updated_at | DATETIME(6) | NOT NULL |
| — | — | **UNIQUE (provider, provider_user_id)** (FR-002, R4) |

### JPA 엔티티

- `MemberJpaEntity : BaseEntity` — 프로필 컬럼 + `@OneToMany(cascade = ALL, orphanRemoval = true, LAZY, @JoinColumn(member_id))` identities (R3). `toDomain()` / `companion from(domain)` (컨벤션 — 변환은 엔티티 안).
- `SocialIdentityJpaEntity : BaseEntity` — provider/providerUserId/email.
- 조회는 identities fetch join 으로 초기화 (LAZY 규약).
- `MemberRepositoryAdapter` — port 구현: DataIntegrityViolation → `MemberException(DUPLICATE_SOCIAL_IDENTITY)` 번역 (R4), `withdraw` 는 member `delete()` + identities 물리 삭제 (R5).

### Flyway

- `V<생성시각 timestamp>__create_member_tables.sql` — 위 2개 테이블. 로컬 docker MySQL 에 DROP+CREATE 후 부팅 실측 (flyway-migration-validation-gap 컨벤션).
