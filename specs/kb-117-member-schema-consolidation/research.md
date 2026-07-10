# Research: member 스키마 재편 (KB-117)

## R1. 마이그레이션 전략 — 단일 스크립트, 데이터 무손실 이관

- **Decision**: 점 구분 timestamp 버전의 단일 Flyway 마이그레이션으로 처리한다 — ① `RENAME TABLE members TO member` ② 신원 3컬럼(`provider`·`provider_uid`·`email`)과 `member_status`·`profile` 컬럼 추가 ③ `member_social_identities` 에서 JOIN UPDATE 로 신원 백필 ④ 구 프로필 4컬럼 → `profile` JSON 백필 ⑤ `onboarding_status` VARCHAR→BOOLEAN 변환 ⑥ NOT NULL 승격 + `(provider, provider_uid)` 유니크 추가 ⑦ 구 프로필 4컬럼 DROP ⑧ `member_social_identities` DROP.
- **Rationale**: 프로덕션 이전 단계(개발 데이터만 존재)라 단계적 이관·롤백 창이 불필요하다. 마이그레이션 독립 작성 원칙(out-of-order 전제)상 이 스크립트는 member 관련 유일 후속 마이그레이션이며 다른 미적용 마이그레이션에 의존하지 않는다.
- **Alternatives considered**: 신 테이블 생성 후 복사(`CREATE TABLE member AS ...`) — RENAME 보다 인덱스·컬럼 순서 통제가 번거롭고 이득 없음. 다중 마이그레이션 분할 — 파일 수만 늘고 원자성 이점 없음(MySQL DDL 은 어차피 문장 단위 커밋).

## R2. 과거 탈퇴 회원(신원 행 없는 DELETED row) 백필

- **Decision**: 기존 `withdraw` 는 신원 행을 hard delete 했으므로 신원 없는 member 행이 존재할 수 있다. 이 행들은 마이그레이션에서 `provider = 'GOOGLE'`(임의 유효값), `provider_uid = CONCAT('DELETED:', id)` 로 백필한 뒤 NOT NULL 을 승격한다(원본 provider·email 은 이미 소실돼 복원 불가).
- **Rationale**: 새 탈퇴 정책(삭제 표식 치환)과 동일한 표현으로 수렴시켜 유니크 제약·NOT NULL 과 충돌하지 않는다. `DELETED:{id}` 는 회원별 유일하므로 (provider, provider_uid) 충돌이 없다.
- **Alternatives considered**: `provider` NULLABLE 유지 — 엔티티 매핑이 nullable 로 오염되고 도메인 불변(신원 필수)과 어긋남. 신원 없는 행 DELETE — 감사 이력 소실, 소프트 삭제 원칙 위배.

## R3. 정지 필터 위치 — 파생 쿼리 조건, 관리자 조회는 상속 메서드

- **Decision**: `MemberStatus`(ACTIVE/SUSPENDED) enum 조건을 서비스 조회 경로인 어댑터의 `findById`·`findByIdentity` 두 메서드(파생 쿼리 `findByIdAndMemberStatus`·`findByProviderAndProviderUidAndMemberStatus`)에 적용한다. 관리자 조회는 새 코드 없이 `MemberJpaRepository`(JpaRepository) 상속 `findById` 를 그대로 쓴다 — `@SQLRestriction`(소프트 삭제)만 적용되고 `member_status` 필터는 없으므로 정지 회원이 보인다.
- **Rationale**: DoD 가 `MemberRepository` port 를 그대로 두라고 명시하므로 관리자 조회는 영속 계층 능력(JPA 레포지토리 직접 조회)으로 제공·검증한다. 파생 쿼리로 fetch join 두 개가 사라진다(신원이 member 행에 흡수되어 join 자체가 불필요).
- **Alternatives considered**: port 에 `findByIdIncludingSuspended` 추가 — DoD 위반(port 불변) + 소비자 없는 스펙 선행(YAGNI). `@SQLRestriction` 에 member_status 포함 — 관리자 조회에서도 정지 회원이 사라져 이슈가 명시적으로 금지한 패턴.
- **Known limitation (기록)**: 정지 회원이 재로그인하면 `findByIdentity` 가 0건 → `saveNew` 유니크 충돌 → `DUPLICATE_SOCIAL_IDENTITY` 에러로 차단된다. 차단 자체는 의도(정지 회원 서비스 제외)에 부합하나 에러 의미가 어긋난다 — 정지 운영 도구 도입 시 `SUSPENDED_MEMBER` 전용 에러로 후속 개선한다.

## R4. MemberStatus enum 위치 — 영속 모듈

- **Decision**: `MemberStatus` 는 `com.meogo.infra.persistence.member` 에 둔다. 도메인 `Member` 는 이 상태를 들지 않는다.
- **Rationale**: DoD 가 도메인 변경을 identities→identity 축소로 한정했고, 정지시키는 운영 행위(도메인 로직)가 범위 밖이므로 현재 소비자는 엔티티·쿼리뿐이다. `EntityStatus`(소프트 삭제) 가 영속 모듈에 있는 선례와 같다. 정지가 도메인 행위(suspend/reinstate)로 승격되면 그때 `core/member` 로 옮긴다.
- **Alternatives considered**: `core/member` 에 두기(OnboardingStatus 선례) — OnboardingStatus 는 도메인 Member 가 직접 들고 있어 도메인 소유가 맞지만, MemberStatus 는 도메인에서 참조되지 않아 미사용 타입이 된다.

## R5. profile JSON 매핑 — 영속 전용 스냅샷 클래스

- **Decision**: `profile JSON NOT NULL` 단일 컬럼에 기피성분 코드 배열·맵기 선호·국가코드·언어코드를 담는다. 엔티티는 `@JdbcTypeCode(SqlTypes.JSON)` + 영속 모듈 내 데이터 클래스 `MemberProfileJson`(4필드)으로 매핑하고, `toDomain`/`applyProfile` 에서 도메인 `MemberProfile` 과 상호 변환한다. `nickname` 은 DoD 이관 목록에 없으므로 독립 컬럼으로 유지한다.
- **Rationale**: 기존 `avoidance_substance_codes` 가 이미 같은 방식(JSON + JdbcTypeCode)으로 검증돼 있어 확장일 뿐이다. 항목 추가 시 스키마 변경이 불필요하다(이슈 요구). 도메인 `MemberProfile` 은 그대로 두고 JSON 직렬화 형태만 영속에 가둔다(원칙 IV).
- **Alternatives considered**: `Map<String, Any?>` 매핑 — 타입 안전성 상실. 도메인 `MemberProfile` 직접 직렬화 — 도메인 타입이 저장 포맷에 결합돼 원칙 IV 위반.
- **JSON 형태**: `{"avoidanceSubstanceCodes":["PEANUT"],"spicinessPreference":7,"countryCode":"KR","appLanguage":"en"}` — 언어는 `LanguageCode.code` 문자열, 미설정 필드는 null/빈 배열.

## R6. 탈퇴 시 신원 처리 — 삭제 표식 치환, 이메일 보존 (사용자 결정 — 개정)

- **Decision**: `withdraw(id)` 는 활성 회원 로드 → `provider_uid = "DELETED:{memberId}"` 삭제 표식으로 치환 → 소프트 삭제(`delete()`) 순으로 바꾼다. **`email` 과 `provider` 는 원값을 보존**한다(초기 결정의 `email = NULL` 을 대체 — 사용자 지시 2026-07-10).
- **Rationale**: `provider_uid` 를 표식으로 덮어야 유니크 제약 `(provider, provider_uid)` 를 유지한 채 같은 소셜 계정 재가입이 열린다. 표식에 `memberId` 를 붙이는 이유는 **상수 `'DELETED'`·`'DUMMY'` 만 쓰면 같은 provider 의 두 번째 탈퇴에서 `(GOOGLE, 'DELETED')` 가 중복되어 탈퇴 자체가 실패**하기 때문이다 — 접두사가 삭제 의미를, memberId 가 유일성을 담당한다. `DELETED:` 접두사는 `MemberJpaEntity.DELETED_PROVIDER_UID_PREFIX` 상수로 단일 출처를 둔다.
- **Alternatives considered**: 상수 `'DELETED'` 단독 — 두 번째 탈퇴에서 유니크 충돌(치명). UUID 더미 — memberId 로 충분한데 난수 의존 추가. `email = NULL`(초기 결정) — 사용자 결정으로 철회, 탈퇴 회원 이메일은 보존한다.
- **주의(개인정보)**: 탈퇴 회원의 이메일이 row 에 남는다. 소셜 식별자만 지워지므로 provider 계정과의 재연결은 불가능하지만, 이메일 자체는 개인정보이므로 보존 기간·파기 정책이 필요하면 별도 작업으로 다룬다.

## R7. 컬럼 타입 — 상태·provider 는 ENUM, 엔티티 columnDefinition 동기화

- **Decision**: `provider ENUM('GOOGLE','APPLE') NOT NULL`, `member_status ENUM('ACTIVE','SUSPENDED') NOT NULL DEFAULT 'ACTIVE'` 로 만들고, 엔티티에도 동일 `columnDefinition` 을 선언한다. 유니크 제약은 엔티티 `@Table(uniqueConstraints)` 에도 선언한다.
- **Rationale**: 영속 통합 테스트는 Flyway 없이 Hibernate 가 엔티티에서 스키마를 생성하므로(KB-46 구조), 엔티티 선언이 곧 테스트 스키마다 — columnDefinition·uniqueConstraints 를 빼면 테스트가 프로덕션과 다른 스키마(유니크 없음)로 돌아 재가입·중복 검증이 무의미해진다(KB-90 에서 확립된 규칙).
- **Alternatives considered**: VARCHAR 유지(기존 members.onboarding_status 방식) — DoD 가 member_status ENUM 을 명시. 기존 onboarding_status VARCHAR→ENUM 정리는 이번 범위 밖(스크립트 최소화).

## R8. 동시 첫 로그인 경합 — 범위 제외 (사용자 결정)

- **Decision**: 동일 소셜 계정의 동시 첫 로그인 두 건 경합은 고려하지 않는다 — 발생 가능성이 희박해 무시해도 되는 수준(사용자 결정, 2026-07-10). 요구사항·검증 대상에서 제외하며 관련 테스트를 새로 만들지 않는다.
- **Rationale**: 유니크 제약은 데이터 정합(중복 신원 차단·findByIdentity 0/1건 보장)용으로 그대로 유지하고, 기존 `MemberIdentityResolver` 폴백 코드도 삭제하지 않는다(동작 변경 없음) — 다만 경합 시나리오 자체를 성공 기준·테스트로 삼지 않는다.

## R9. 컬럼 명명·타입 변경 (사용자 결정)

- **Decision**: 신원 식별자 컬럼명은 `provider_user_id` 가 아니라 **`provider_uid`** 로 한다(엔티티 프로퍼티 `providerUid`). `onboarding_status` 는 VARCHAR enum 저장을 **BOOLEAN(TINYINT(1)) NOT NULL DEFAULT 0** 으로 바꾼다(true=완료).
- **Rationale**: 사용자 지시(2026-07-10). 도메인은 불변 — `SocialIdentity.providerUserId` 프로퍼티명과 `OnboardingStatus` enum(PENDING/COMPLETED)은 그대로 두고, 엔티티의 컬럼 매핑·Boolean↔enum 변환(`toDomain`/`applyProfile`)에서만 흡수한다(원칙 IV — 저장 표현은 영속에 가둔다).
- **Alternatives considered**: 도메인 `onboardingStatus` 를 Boolean 으로 변경 — DoD 가 도메인 변경을 identity 축소로 한정했고, 온보딩 상태가 3단계 이상으로 늘 때 도메인 enum 이 더 안전.

## R10. profile JSON 스키마 확정 + 국가코드 `CountryCode` enum (사용자 결정 — 개정)

- **Decision**: profile JSON 은 4키 camelCase 고정 스키마(읽기 관대·쓰기 완전)로 확정한다. 국가코드는 `:core:kernel` 에 **`CountryCode` enum(ISO 3166-1 alpha-2, 197개국)** 으로 관리한다 — 상수명 = 코드(`KR`·`US`…), 개발자 가독성용 한국어 `label`(예: `KR("대한민국")`) 포함. 도메인 `MemberProfile.countryCode` 는 `String?` 이 아니라 **`CountryCode?` 타입**으로 두어 타입이 검증을 대체한다(별도 regex require 불필요 — 초기 R10 의 형식 검증 결정을 대체). 소스 데이터는 `specs/kb-117-member-schema-consolidation/countries.json`(197건, code/nameEn/nameKo — 검증 완료: 전 코드 유일·2자 대문자). 사용자 노출 표시명(nameKo/nameEn)은 클라이언트 소유 — 서버는 label 만 갖고 런타임에 사용하지 않는다.
- **Rationale**: 국가코드의 서버측 소비처가 확정됐다 — 리뷰 국적 필터링(내 국적 사람들의 리뷰 조회), appLanguage 는 전 조회의 번역 응답. 조회 차원이 되는 값은 형식만 맞는 미배정 코드가 섞이면 필터가 조용히 틀어지므로 컴파일 타임 값 집합 고정이 필요하다. `LanguageCode`(kernel, member·food 공유 vocabulary)·`AvoidanceSubstanceCode`(식별자 enum + 한국어 label) 선례와 동형이고, member + 미래 review 두 컨텍스트가 공유하므로 kernel 이 맞다(헌법 II·V).
- **profile JSON 유지 근거**: 리뷰 국적 필터는 member JSON 을 SQL 로 조회하는 게 아니라 (리뷰 도메인 신설 시) review 행에 작성자 국가코드를 스냅샷으로 저장해 그 컬럼을 필터링한다. member.profile 은 "내 설정 단건 읽기"뿐이라 JSON 으로 충분하다. appLanguage 도 동일(설정을 읽어 기존 `lang` 조회 파라미터로 전달).
- **Alternatives considered**: String + 형식 regex 검증(초기 결정) — 조회 차원이 된 이상 값 집합 검증이 안 됨. DB 테이블 + 시드 — 운영 편집이 없는 고정 목록이라 과함(콘텐츠 표시명도 서버 미보관이라 헌법 V 의 "DB 단일 출처" 대상 콘텐츠 자체가 없음).

## R11. 맵기 선호·기피성분 — 비-널 초기값 (사용자 결정)

- **Decision**: 도메인 `MemberProfile.spicinessPreference` 를 비-널 `Int` 로 두고 `empty()`(가입 시 초기 프로필)가 **5 로 초기화**한다 — 온보딩 UI 기본 제안값과 일치. 기피성분은 기존처럼 비-널 `Set` + 초기 빈 셋. 결과적으로 두 필드 모두 null 이 존재하지 않아 소비 코드가 null 분기 없이 작성된다(null-safe). JSON 도 `spicinessPreference` 는 항상 숫자(키 부재 시 읽기에서 5 간주), 마이그레이션 백필은 `COALESCE(spiciness_preference, 5)`.
- **Rationale**: UI 가 항상 5 를 보여주므로 "화면값 = 저장값"이 유지되고, 온보딩 전(PENDING) 회원까지 5 로 초기화하면 null 상태 자체가 소멸해 표현·분기가 단순해진다. skip 과 실제 5 응답의 구분은 포기한다 — 리마인드·재질문 UX 가 필요해지면 별도 신호(예: 온보딩 항목 응답 이력)로 해결한다.
- **Alternatives considered**: `Int?` 유지 + 클라이언트만 5 전송 — 온보딩 전 null 분기가 소비 코드에 남음. skip = null 저장 — 화면(5)과 저장값(null) 불일치.
