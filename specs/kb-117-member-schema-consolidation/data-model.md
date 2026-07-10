# Data Model: member 스키마 재편 (KB-117)

## 도메인 모델 (core/member — 변경)

### Member (Aggregate Root)

| 필드 | 타입 | 변경 | 규칙 |
|------|------|------|------|
| id | Long? | 유지 | 신규 생성 시 null |
| identity | SocialIdentity | **identities: List → 단일로 축소** | 필수(non-null 타입으로 강제 — 기존 `isNotEmpty` require 대체) |
| profile | MemberProfile | 유지 | |
| onboardingStatus | OnboardingStatus | 유지 | PENDING → COMPLETED 단방향 — 영속은 BOOLEAN 저장, 엔티티가 변환 |

- `signUp(identity)` / `reconstitute(id, identity, profile, onboardingStatus)` — 시그니처가 단일 identity 로 바뀐다.
- `SocialIdentity`·`MemberRepository`(port)·`MemberIdentityResolver`·`OnboardingStatus` 는 **불변**.
- `MemberProfile` 수정 2건: ① **countryCode 타입을 `String?` → `CountryCode?`(kernel enum) 로 변경** — 타입이 값 집합 검증을 대체, regex require 불필요 (R10) ② **spicinessPreference 를 비-널 `Int` 로 변경, `empty()` 초기값 5** — 기피성분은 기존처럼 비-널 `Set`(초기 빈 셋). null 분기 없는 null-safe 코드 (R11).

### CountryCode (신규, :core:kernel — LanguageCode 옆)

- ISO 3166-1 alpha-2 **197개국 enum** — 상수명 = 코드(`KR`·`US`·`JP`…), 개발자 가독성용 한국어 `label`(`KR("대한민국")`). 소스: [countries.json](countries.json) (code/nameEn/nameKo).
- kernel 에 두는 이유: member(프로필) + 미래 review(작성자 국적 필터)가 공유하는 vocabulary — `LanguageCode` 와 동형. 사용자 노출 표시명은 클라이언트 소유(서버 label 은 런타임 미사용).
- 리뷰 국적 필터는 member JSON 조회가 아니라 review 행의 국가코드 스냅샷 컬럼으로 구현 예정 — profile JSON 유지에 영향 없음.
- 계정 연결(멀티 identity)은 지원하지 않는다 — 필요 시 자식 테이블 재분리 마이그레이션.

## 영속 모델 (infra/persistence/member — 변경)

### MemberJpaEntity → 테이블 `member` (구 `members` 리네임)

| 컬럼 | 타입 | 변경 | 비고 |
|------|------|------|------|
| id | BIGINT PK AUTO_INCREMENT | 유지 (BaseEntity) | |
| provider | ENUM('GOOGLE','APPLE') NOT NULL | **신규 (신원 흡수)** | 엔티티 columnDefinition 동기화 |
| provider_uid | VARCHAR(255) NOT NULL | **신규 (신원 흡수)** | 탈퇴 시 `withdrawn:{id}` 치환 |
| email | VARCHAR(255) NULL | **신규 (신원 흡수)** | 탈퇴 시 NULL |
| nickname | VARCHAR(30) NULL | 유지 | JSON 이관 대상 아님 |
| profile | JSON NOT NULL | **신규** | 아래 MemberProfileJson |
| member_status | ENUM('ACTIVE','SUSPENDED') NOT NULL DEFAULT 'ACTIVE' | **신규** | BaseEntity.status 와 별개 축 |
| onboarding_status | TINYINT(1) NOT NULL DEFAULT 0 | **변경 (VARCHAR → BOOLEAN)** | 도메인 `OnboardingStatus` 는 유지 — 엔티티 `Boolean` ↔ enum 변환(true=COMPLETED) |
| status | VARCHAR(20) NOT NULL | 유지 (BaseEntity 소프트 삭제) | SUSPENDED 추가 금지 |
| created_at / updated_at | DATETIME(6) NOT NULL | 유지 (BaseEntity) | |

**제약/인덱스**: `UNIQUE KEY uk_member_provider_uid (provider, provider_uid)` — 엔티티 `@Table(uniqueConstraints)` 에도 선언.

**삭제 컬럼**: `avoidance_substance_codes`, `spiciness_preference`, `country_code`, `app_language` → `profile` JSON 으로 이관 후 DROP.

**삭제 테이블·클래스**: `member_social_identities` DROP, `SocialIdentityJpaEntity` 삭제, `MemberJpaEntity.identities`(@OneToMany) 제거.

### MemberProfileJson (영속 전용, JSON 스냅샷) — 스키마 확정

```json
{
  "avoidanceSubstanceCodes": ["PEANUT", "SOYBEAN"],
  "spicinessPreference": 7,
  "countryCode": "KR",
  "appLanguage": "en"
}
```

| 키 | 타입 | 미설정 표현 | 값 규칙 |
|----|------|------------|---------|
| avoidanceSubstanceCodes | string 배열 | `[]` (null 금지) | `AvoidanceSubstanceCode` 코드 문자열, 중복 없음(도메인 비-널 Set). 옵셔널 — skip = `[]` |
| spicinessPreference | number(정수) | 없음 — **항상 값 존재, null 금지** | 견딜 수 있는 맵기 0~10 (도메인 require 기존 검증). **도메인 `MemberProfile` 이 비-널 `Int`, `empty()` 초기값 5**(온보딩 UI 기본 제안값과 일치 — 사용자 결정 2026-07-10). 읽기 시 키 부재도 5 로 간주 |
| countryCode | string | `null` | **`CountryCode` enum 코드**(ISO 3166-1 alpha-2, 197개국 — kernel). 클라이언트가 코드값 전달, 도메인 타입이 값 집합 검증. 표시명(nameKo/nameEn)은 클라이언트 소유 — 서버 미보관 |
| appLanguage | string | `null` | `LanguageCode.code` (ko, en, zh-Hans, …) |

- 키는 camelCase(엔티티 프로퍼티 미러). JSON 컬럼은 `NOT NULL` — 빈 프로필도 4키를 모두 가진 객체로 저장한다(`{"avoidanceSubstanceCodes":[],"spicinessPreference":5,"countryCode":null,"appLanguage":null}`).
- **읽기 관대·쓰기 완전**: 읽을 때 없는 키는 null/빈 배열로 간주(항목 추가 시 기존 행 마이그레이션 불필요 — 이슈의 "스키마 변경 없이 항목 확장" 충족), 쓸 때는 항상 전체 키를 기록한다.
- `@JdbcTypeCode(SqlTypes.JSON)` 매핑 데이터 클래스. 도메인 `MemberProfile` ↔ `MemberProfileJson` 변환은 엔티티 안(`toDomain`/`applyProfile`/`from`)에서만.
- **검증 위치**: JSON 이관으로 구 `country_code VARCHAR(2)` 의 DB 가드가 사라지지만, 도메인 `MemberProfile.countryCode` 가 `CountryCode?` enum 타입이라 유효하지 않은 코드는 도메인에 진입 자체가 불가능하다(문자열→enum 변환은 입력 경계에서). 엔티티 읽기는 관대하게 — 미지 코드 문자열은 `null` 로 간주.

### MemberStatus (영속 모듈)

- `ACTIVE` / `SUSPENDED`. 도메인 Member 는 들지 않는다(정지 운영 도구 도입 시 core 승격).

### MemberJpaRepository (단순화)

| 메서드 | 변경 |
|--------|------|
| `findByIdWithIdentities` (@Query fetch join) | **삭제** → 파생 `findByIdAndMemberStatus(id, ACTIVE)` |
| `findByIdentity` (@Query fetch join) | **삭제** → 파생 `findByProviderAndProviderUidAndMemberStatus(provider, providerUid, ACTIVE)` |
| 상속 `findById` | 유지 — **관리자 조회 경로**(member_status 필터 없음, @SQLRestriction 만 적용) |

### 상태 전이

```
[가입]    member_status=ACTIVE, status=ACTIVE, provider_uid=원본, email=원본
[정지]    member_status=SUSPENDED            (범위 밖 — 표현·조회 규칙만 이번 작업)
[탈퇴]    status=DELETED, provider_uid="withdrawn:{id}", email=NULL
```

- 서비스 조회(findById·findByIdentity): `status=ACTIVE`(자동) + `member_status=ACTIVE`(명시) 인 회원만.
- 관리자 조회(JPA findById): `status=ACTIVE`(자동)만 — 정지 회원 포함.

## Flyway 마이그레이션 (신규 1건, 점 구분 timestamp)

순서(단일 스크립트 내):

1. `RENAME TABLE members TO member`
2. `ALTER TABLE member ADD` — provider(NULL 로 시작)·provider_uid(NULL 로 시작)·email·member_status·profile(NULL 로 시작)
3. 신원 백필: `UPDATE member m JOIN member_social_identities si ON si.member_id = m.id SET m.provider_uid = si.provider_user_id, ...`
4. 과거 탈퇴(신원 없는) 행 백필: `provider='GOOGLE'`, `provider_uid=CONCAT('withdrawn:', id)`
5. profile 백필: `JSON_OBJECT(...)` — 구 4컬럼에서 생성 (`avoidance_substance_codes` 는 기존 JSON 배열 그대로, `spiciness_preference` 는 `COALESCE(spiciness_preference, 5)` 로 비-널 보장)
6. onboarding_status 변환: `UPDATE member SET onboarding_status = IF(onboarding_status = 'COMPLETED', '1', '0')` 후 `MODIFY onboarding_status TINYINT(1) NOT NULL DEFAULT 0`
7. NOT NULL 승격(provider·provider_uid·profile) + `uk_member_provider_uid` 유니크 추가
8. 구 프로필 4컬럼 DROP
9. `DROP TABLE member_social_identities`
