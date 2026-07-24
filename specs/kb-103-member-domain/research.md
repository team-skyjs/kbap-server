# Research: 회원 도메인 — 소셜 신원·프로필·온보딩 상태·탈퇴

**Date**: 2026-07-08 | **Spec**: [spec.md](spec.md)

## R1. 수직 범위 절단 — web/application 계층 제외

- **Decision**: KB-103 은 `:core:member`(모델·port·신원 해소 도메인 서비스) + `:infra:persistence`(엔티티·어댑터·Flyway) + 테스트까지만 구현한다. `:application:client`·`:app:api` 변경 없음.
- **Rationale**: 신원 해소의 소비자는 로그인(KB-102), 프로필 저장·온보딩 전이의 소비자는 온보딩 API(KB-104)다. 인증 필터 없이는 회원용 컨트롤러를 노출할 수 없고, 컨트롤러 없는 유스케이스 빈은 죽은 코드다. 도메인 서비스는 Spring-free 로 두고 KB-102/104 가 빈 등록·조립한다.
- **Alternatives considered**: 유스케이스(:application:client)까지 선작성 — 소비자 없는 스캐폴드라 기각(YAGNI).

## R2. 테이블 네이밍 — `members` / `member_social_identities`

- **Decision**: 회원 테이블은 `members`, 신원 테이블은 `member_social_identities`.
- **Rationale**: `MEMBER` 는 MySQL 8.0.17+ 예약어라 `member` 단수 테이블명은 백틱 없이는 깨진다. 기존 `food` 는 단수지만 예약어 충돌이 없었다 — member 는 충돌하므로 복수형으로 회피한다(백틱 상시 표기는 마이그레이션·쿼리 전반에 노이즈).
- **Alternatives considered**: 백틱 `member` — 전 쿼리 백틱 강제라 기각. `app_member` — 접두어 컨벤션이 리포에 없어 기각.

## R3. 소셜 신원 저장 구조 — 애그리거트 내 단방향 @OneToMany

- **Decision**: `SocialIdentityJpaEntity` 를 `MemberJpaEntity` 의 단방향 `@OneToMany(cascade = ALL, orphanRemoval = true, fetch = LAZY, @JoinColumn(member_id))` 컬렉션으로 둔다. 도메인도 `Member` 애그리거트가 `identities: List<SocialIdentity>` 를 보유(스펙 1—N 유지 — 후속 명시적 연동 대비).
- **Rationale**: `FoodJpaEntity ↔ FoodAvoidanceSubstanceJpaEntity` 와 동일 패턴(리포 전례). 신원은 회원 애그리거트 밖에서 독립 수정될 일이 없다.
- **Alternatives considered**: 신원을 별도 애그리거트로 분리 — 신원 단독 수정 유스케이스가 없어 기각.

## R4. 동시 중복 가입 방지 — DB 유니크 + 예외 번역 + 1회 재조회

- **Decision**: `member_social_identities(provider, provider_user_id)` 에 UNIQUE 인덱스. 어댑터는 저장 시 무결성 위반(DataIntegrityViolation)을 도메인 예외 `MemberException(DUPLICATE_SOCIAL_IDENTITY)` 로 번역한다. 도메인 서비스 `MemberIdentityResolver` 는 조회 → 없으면 생성·저장 → 중복 예외 시 **재조회 1회**로 기존 회원 반환(신규 아님)으로 수렴한다.
- **Rationale**: 동시성 보장은 애플리케이션 락이 아니라 DB 제약이 근본이다(헌법 외 원칙 — DB constraint over app code). 재조회 폴백으로 race 패자도 정상 로그인된다.
- **Alternatives considered**: `SELECT ... FOR UPDATE`·분산락 — 유니크 제약으로 충분해 기각. upsert(`INSERT ... ON DUPLICATE KEY`) — 도메인 매핑·isNewUser 판별이 불투명해 기각.

## R5. 탈퇴 — 회원 soft delete + 신원 hard delete

- **Decision**: 탈퇴 시 `members` 행은 BaseEntity 소프트삭제(`status=DELETED`), `member_social_identities` 행은 **물리 삭제**한다.
- **Rationale**: 주된 이유는 **유니크 제약 충돌 해소**다 — 신원 행이 남으면(soft delete 여도) 같은 소셜 계정의 재가입 INSERT 가 (provider, provider_user_id) 유니크에 막힌다. 스펙은 "탈퇴 후 재로그인 = 신규 가입"이므로 신원 행을 물리 삭제해야 이 요구가 자연 성립한다. 부수적으로 PII(provider sub·email)도 함께 사라진다. **법적으로 즉시 삭제가 강제되는 것은 아니다**(한국 개인정보보호법의 "지체 없이 파기"는 정당한 기간 내를 뜻하고, 부정 재가입 방지 목적의 일정 기간 보존도 허용된다) — 현 스펙에 탈퇴 이력·재가입 남용 방지 요구가 없어 최소 설계로 hard delete 를 택한 것이다. 회원 행(프로필·통계성 데이터)은 소프트삭제 규약(BaseEntity)을 따른다.
- **Note (정책 훅)**: 향후 탈퇴 감사 로그·재가입 남용 방지가 요구되면 신원 soft delete + 부분 유니크 인덱스(ACTIVE 만) 로 전환한다. 회원 행에 남는 프로필도 민감(기피성분=알러지/종교 프록시)하므로, 개인정보 보존 정책 확정 시 탈퇴 시 프로필 컬럼 비우기를 함께 결정한다 — 둘 다 이번 범위 밖(요구 없음).
- **Alternatives considered**: 신원도 soft delete + 유니크에 status 포함 — 두 번째 탈퇴에서 DELETED 중복으로 다시 깨져 기각. 신원 soft delete + provider_user_id 변조(탈퇴 마킹) — 데이터 왜곡이라 기각.

## R6. 기피성분 코드 집합 저장 — JSON 컬럼

- **Decision**: `members.avoidance_substance_codes JSON NOT NULL`(문자열 배열, 기본 `[]`). `@JdbcTypeCode(SqlTypes.JSON)` 매핑.
- **Rationale**: 코드 집합은 항상 회원 애그리거트와 통째로 읽고 쓴다(KB-9 위험도 계산도 회원 로드 후 메모리 대조). 개별 코드 기준 SQL 질의 요구가 없다. 리포에 JSON 컬럼 전례(food name/description_translations, KB-48)가 있다.
- **Alternatives considered**: 자식 테이블 `member_avoidance_substances` — "성분 X 를 기피하는 회원 수" 같은 역방향 질의가 생기면 승격한다. 현재는 테이블·조인만 늘어 기각.

## R7. 프로필 미설정 상태 표현 — nullable 컬럼

- **Decision**: `spiciness_preference TINYINT NULL`, `country_code VARCHAR(2) NULL`, `app_language VARCHAR(10) NULL`, 기피성분 `[]`. 도메인 `MemberProfile` 은 nullable 필드 + 빈 집합으로 "아직 온보딩 전" 을 표현한다(별도 Optional 래퍼·플래그 없음 — 온보딩 여부는 `onboardingStatus` 가 담당).
- **Rationale**: 신규 가입 직후엔 프로필 값이 없다(스펙 US2 시나리오 3). 값 유효성(0~10·카탈로그 81종·국가 코드 목록·10개국어) 검증은 KB-104 입력 검증 책임이고, 도메인은 형식 불변(맵기 범위·코드 형식)만 지킨다.
- **Alternatives considered**: 국가 코드 즉시 enum 화 — 목록이 기획 미확정(KB-104 차단 요소)이라 문자열(ISO 3166-1 alpha-2 가정)로 보류.

## R8. 온보딩 전이 규칙 — PENDING→COMPLETED 단방향

- **Decision**: `Member.completeOnboarding()` 은 PENDING 에서만 허용하고 COMPLETED 재호출은 `MemberException(ONBOARDING_ALREADY_COMPLETED)`. 역방향 전이 없음.
- **Rationale**: 스펙 FR-008 은 단방향 전이만 요구한다. 중복 완료를 조용히 무시하면 KB-104 에서 재제출 버그를 숨긴다 — fail-fast. (API 레벨 멱등 정책이 필요하면 KB-104 가 유스케이스에서 결정.)
- **Alternatives considered**: 멱등(no-op) — 버그 은폐 우려로 기각.

## R9. 컨텍스트 간 코드 참조 — member 자체 CodeRef 값타입

- **Decision**: `com.meogo.core.member.AvoidanceSubstanceCodeRef`(대문자·숫자·underscore 형식 검증)를 member 모듈에 둔다. avoidance 모듈 import 금지.
- **Rationale**: 원칙 II — 타 컨텍스트는 코드로만 참조. food 가 동일 패턴(`core.food.AvoidanceSubstanceCodeRef`)을 이미 쓴다. 실존 코드(81종 카탈로그 내) 검증은 KB-104 입력 검증에서 카탈로그 조회로 수행한다.
- **Alternatives considered**: kernel 로 CodeRef 승격 — food·member 가 각자 소유하는 현 패턴 유지(공유 vocabulary 는 아직 2곳뿐, 승격은 3번째 소비자 때).

## R11. 온보딩 유도 신호 — onboardingStatus (isNewUser 아님) + 닉네임 프로필 필드

- **Decision**: 재방문 회원의 온보딩 유도 판단은 `Member.onboardingStatus`(PENDING/COMPLETED)로 한다. `MemberIdentityResolver` 는 `member` 를 그대로 담아 반환하므로 온보딩 상태가 이미 노출된다 — resolver 에 별도 필드를 추가하지 않는다. 닉네임은 `MemberProfile.nickname: String?`(nullable·유일 강제 없음)로 프로필에 추가한다.
- **Rationale**: 가입만 하고 온보딩을 안 끝낸 회원이 재로그인하면 `isNewUser=false` 지만 여전히 PENDING 이다. `isNewUser` 로 라우팅하면 이 사용자는 온보딩을 영영 건너뛴다. 따라서 라우팅 신호는 반드시 온보딩 상태여야 한다(스펙 US1 시나리오 3·4·FR-004·FR-008). 로그인 응답(KB-102)이 `isNewUser` 와 `onboardingStatus`(또는 파생 `needsOnboarding`) 둘 다 실어야 완결된다 — KB-102 응답 계약에 반영 필요. 닉네임은 온보딩에서 함께 받는 표시용 값이라 프로필에 속한다(유일성 불필요 — 표시 전용).
- **Alternatives considered**: `isNewUser` 단일 신호 — 미완료 재방문자를 놓쳐 기각. 온보딩 완료 여부를 프로필 값 존재로 추론(닉네임 null 이면 미완료) — 상태를 데이터에서 역추론하는 취약 설계라 기각(명시적 `onboardingStatus` 가 단일 진실). 닉네임 유일 인덱스 — 표시용이라 불필요, 가입 마찰만 늘어 기각.

### R11-a. 재진입 시 온보딩 상태 확인 경로 — JWT 클레임 아님, `/me` hydration

- **Decision**: 온보딩 상태를 **JWT 클레임에 넣지 않는다**. JWT 는 회원 id 만 담고(가변 상태 배제), 온보딩 상태의 단일 진실은 `members.onboarding_status`(DB)다. 클라이언트는 (1) 로그인 응답의 `onboardingStatus`, (2) 앱 재진입 시 인증된 부트스트랩 조회(`GET /api/v1/members/me` — 현재 회원의 온보딩 상태·프로필)로 최신값을 읽는다. 온보딩 완료 시 토큰 재발급 불필요(토큰에 상태가 없으므로).
- **Rationale**: JWT 는 발급 시점에 얼어붙는 정적 토큰이라 가변 상태를 담으면 stale 된다 — 완료 후에도 만료 전까지 PENDING 을 말해 사용자가 온보딩에 갇히거나 강제 refresh 가 필요하다. 업계 합의: **JWT = identity(자주 안 바뀌는 사실)만, 최신값 필요한 상태는 서버 조회**(가변 상태 JWT 삽입은 문서화된 안티패턴). meogo 는 재진입 시 어차피 프로필(기피성분·언어)로 화면을 그려야 하므로 `/me` hydration 이 자연스럽다.
- **KB-103 범위**: 도메인은 이미 충족 — `MemberRepository.findById(memberId).onboardingStatus` 로 노출한다. `GET /me` 엔드포인트 자체(인증 필터로 현재 회원 주입)는 KB-102/KB-104 가 조립한다.
- **Alternatives considered**: 토큰에 coarse 온보딩 플래그 + 완료 시 강제 refresh — `/me` 를 어차피 호출하는 앱이라 이득 없고 복잡도만 늘어 기각.

## R10. 이메일 컬럼 — nullable, 매칭 키 아님

- **Decision**: `member_social_identities.email VARCHAR(255) NULL`. 인덱스 없음.
- **Rationale**: 이메일은 참고 정보다(스펙 FR-005 — 자동 통합 철회로 매칭 키 아님). 애플 최초-only·릴레이·부재 허용. 조회 조건으로 쓰지 않으므로 인덱스 불요. JWT 클레임에도 미포함(사용자 결정).
- **Alternatives considered**: NOT NULL + 빈 문자열 — 부재 의미가 사라져 기각.
