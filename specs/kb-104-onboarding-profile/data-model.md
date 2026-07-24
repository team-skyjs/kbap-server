# Data Model: 온보딩 — 기피 음식·국가·앱 언어 설정 + 완료 처리

**Date**: 2026-07-12 | **Plan**: [plan.md](plan.md)

## 기존 모델 (온보딩 네이밍 통일 리팩터만 반영 — research.md R8)

### Member (core:member, Aggregate Root)

| 필드 | 타입 | 비고 |
|------|------|------|
| id | Long? | 회원 PK |
| identity | SocialIdentity | provider·providerUserId·email (KB-118) |
| profile | MemberProfile | 아래 참조 |
| onboardingCompleted | Boolean | **[변경]** enum `OnboardingStatus` 삭제 → boolean 통일 (초기값 false) |

**상태 전이**: `false --completeOnboarding()--> true`. 이미 true 에서 재호출 시 `MemberException(ONBOARDING_ALREADY_COMPLETED)`(400). 역전이 없음.

**행위**: `updateProfile(profile)` — 새 인스턴스 반환(불변), `completeOnboarding()` — 재완료 검사 포함.

**온보딩 표현 통일(전 계층)**: 도메인 `onboardingCompleted: Boolean` = 엔티티 필드 `onboardingCompleted`(`@Column(name = "onboarding_completed")`) = DB 칼럼 `onboarding_completed` BOOLEAN = 응답 필드 `onboardingCompleted`.

### MemberProfile (core:member, VO)

| 필드 | 타입 | 검증(도메인) |
|------|------|-------------|
| nickname | String? | — (규격 검증은 application) |
| avoidanceSubstanceCodes | Set\<AvoidanceSubstanceCodeRef\> | 각 코드: 대문자·숫자·언더스코어, blank 금지 |
| spicinessPreference | Int | 0~10 (온보딩 입력 밖 — 기존 값 보존, 초기 5) |
| countryCode | CountryCode? | — |
| appLanguage | LanguageCode? | — |

### 영속 (infra:persistence — 칼럼 rename 만 변경)

`member` 테이블: `nickname` VARCHAR(30) · `profile` JSON(`MemberProfileJson`: avoidanceSubstanceCodes·spicinessPreference·countryCode·appLanguage) · `onboarding_completed` BOOLEAN(**rename** ← `onboarding_status`). **Flyway 마이그레이션 1건**: `ALTER TABLE member RENAME COLUMN onboarding_status TO onboarding_completed` (신규 timestamp 버전 — 기존 적용 파일 무수정). `MemberJpaEntity` 는 `@Column(name = "onboarding_completed")` 로 맞추고 enum↔boolean 변환 코드를 제거한다.

### 참조 목록 (검증 기준 — 변경 없음)

- `AvoidanceSubstanceCode`(core:avoidance) — 81종 식별자 enum. application 에서 멤버십 대조.
- `CountryCode`(core:kernel) — 국가 ENUM, `from(code): CountryCode?`.
- `LanguageCode`(core:kernel) — 10개국어(`ko` + 9), `code` 정확 일치.

## 신규 모델 (application:client `member` 패키지)

### OnboardingInput (dto)

| 필드 | 타입 | 검증(유스케이스, 위반 시 400) |
|------|------|------------------------------|
| memberId | Long | 토큰에서 주입 — 회원 부재 시 MEMBER_NOT_FOUND(404) |
| nickname | String | trim 후 비어 있지 않음 → INVALID_NICKNAME |
| avoidanceSubstanceCodes | List\<String\> | 전 원소가 81종 enum name 집합에 포함(중복은 Set 화) → INVALID_AVOIDANCE_SUBSTANCE_CODE |
| countryCode | String | `CountryCode.from` non-null → INVALID_COUNTRY_CODE |
| appLanguage | String | `LanguageCode.code` 정확 일치 → UNSUPPORTED_APP_LANGUAGE |

처리 순서: 회원 조회 → 필드 검증 → `updateProfile` → `completeOnboarding`(재완료 400) → save. 단일 `@Transactional`.

### MyProfileResult (dto)

| 필드 | 타입 |
|------|------|
| memberId | Long |
| nickname | String? |
| avoidanceSubstanceCodes | List\<String\> |
| countryCode | String? |
| appLanguage | String? |
| onboardingCompleted | Boolean |

### OnboardingErrorCode (ErrorCode 구현, 전부 400)

| 코드 | 메시지(~습니다 체) |
|------|--------------------|
| INVALID_NICKNAME | 닉네임은 비어 있을 수 없습니다 |
| INVALID_AVOIDANCE_SUBSTANCE_CODE | 지원하지 않는 기피 성분 코드입니다 |
| INVALID_COUNTRY_CODE | 지원하지 않는 국가 코드입니다 |
| UNSUPPORTED_APP_LANGUAGE | 지원하지 않는 언어입니다 |

재사용: `MemberErrorCode.ONBOARDING_ALREADY_COMPLETED`(400), `MemberErrorCode.MEMBER_NOT_FOUND`(404), `AuthErrorCode.INVALID_ACCESS_TOKEN`/`EXPIRED_ACCESS_TOKEN`(401).
