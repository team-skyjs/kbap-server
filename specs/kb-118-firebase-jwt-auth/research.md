# Research: Firebase 토큰 검증 소셜 로그인 — 자체 JWT 쿠키 발급 (KB-118)

## R1. 모듈 배치 — 사용자 결정(속도 우선, 헌법 III 의식적 완화)

- **Decision**: ① **firebase-admin 은 `:application:client` 직접 의존** — `SocialTokenVerifier` 인터페이스(테스트 seam)와 Firebase 구현체를 모두 application 의 `auth` 패키지에 둔다(이슈 DoD 의 "infra:firebase 신설 + core port + runtimeOnly 조립" 대체). ② **Redis 는 `:infra:persistence` 안에서 관리** — `RefreshTokenStore` port 는 `:core:member` 에, Redis 어댑터·spring-data-redis 의존성은 persistence 에 두고 app:api 가 기존 runtimeOnly 조립으로 연결한다.
- **Rationale**: 사용자 결정(2026-07-10) — "비즈니스 레벨에서 빠르게 구현하기 위해 눈감아준다". 인증 1개 기능에 infra 모듈 2개 신설은 과하다는 판단. 단 Redis 쪽은 port 를 core 에 두면 **기존 패턴 그대로**라 완화가 아니다(persistence 는 core 만 바라볼 수 있고 application 을 바라보면 의존 역전이므로 port 를 application 에 둘 수 없다). 완화는 firebase-admin 한 곳뿐이며 plan 의 Complexity Tracking 에 기록한다.
- **Alternatives considered**: infra:firebase 신설(이슈 원안) — 속도 우선으로 기각, 재사용·교체 필요 시 추출이 승격 경로. ArchUnit `ModuleBoundaryTest` 는 `com.meogo.infra` 모듈 의존만 검사하므로 서드파티 lib 은 통과.
- **테스트 seam 유지**: `SocialTokenVerifier` 인터페이스는 반드시 둔다 — DoD 가 페이크 verifier 단위 테스트를 요구하고, 없으면 단위 테스트가 Firebase 실호출에 묶인다.

## R2. Firebase 토큰 검증·클레임 매핑

- **Decision**: `firebase-admin` 의 `verifyIdToken` 으로 서명·iss·aud·exp 를 한 번에 검증한다. 매핑은 ① `firebase.sign_in_provider` → `SocialProvider`(google.com→GOOGLE, apple.com→APPLE, 그 외 명시적 예외) ② `firebase.identities[provider][0]` → `providerUserId`(**최상위 sub = Firebase uid 는 저장 금지** — 원본 sub 라야 Firebase 를 걷어내도 신원 유지) ③ `email`(null·릴레이 주소 허용, 신원 키 아님). 클레임 Map → `SocialIdentity` 매핑 함수를 순수 함수로 분리해 단위 테스트한다(실검증 제외 — DoD).
- **Rationale**: 페이로드는 base64 인코딩일 뿐이라 디코딩만으로 신뢰하면 신원 위조가 가능하다. firebase-admin 이 구글 공개키 캐싱·회전을 처리하므로 직접 JWKS 를 다루지 않는다.
- **FirebaseApp 초기화**: `meogo.auth.firebase.credentials-path` 프로퍼티 + `@ConditionalOnProperty` — 키가 없으면 verifier 빈 미생성으로 부팅 안전(LLM 모듈과 동일 패턴). 서비스 계정 키는 저장소 커밋 금지, 프로필별 주입: local=`.env`(경로), dev/staging/prod=배포 환경 변수·시크릿. 문서화는 quickstart.

## R3. 자체 JWT — jjwt, HS256, 클레임 = 회원 PK 단 하나

- **Decision**: `jjwt`(api/impl/jackson — impl·jackson 은 runtimeOnly)로 access·refresh 둘 다 JWT 로 발급한다. 서명 HS256, 시크릿은 `meogo.auth.jwt.secret`(env `JWT_SECRET`, 32바이트 이상). 사용자 클레임은 **`sub` = 회원 PK 하나뿐**(사용자 확정 — 이메일 등 개인정보 금지, KB-102 강화). refresh 에는 `jti`(UUID)를 추가해 Redis 키로 쓴다. 발급·파싱은 application:client `auth` 패키지의 `TokenIssuer`/`TokenParser` 로 응집.
- **Rationale**: Spring Security 미도입 상태라 jjwt 단독이 최소 의존이다(시큐리티 필터 체인은 이번 범위에 없음). 수명: **access 30분, refresh 14일**(원칙 = access 는 분 단위·refresh 는 주 단위, 값은 프로퍼티로 조정 가능).
- **Alternatives considered**: spring-security-oauth2-jose(nimbus) — Spring Security 전체가 따라와 과함. opaque random refresh — 사용자가 "자체 JWT (access, refresh)" 로 명시해 둘 다 JWT.

## R4. Refresh 토큰 저장 — Redis, TTL = 토큰 수명 (사용자 결정)

- **Decision**: `RefreshTokenStore` port(core:member): `save(jti, memberId, ttl)` / `findMemberId(jti)` / `delete(jti)`. Redis 어댑터(infra:persistence)는 `StringRedisTemplate` 로 키 `auth:refresh:{jti}` → 값 memberId, **TTL = refresh 수명** — 만료 토큰이 저장소에 잔존하지 않는다(스펙 FR-008). 다중 기기 = 로그인마다 새 jti 발급으로 자연 허용, 로그아웃은 제시된 refresh 의 jti 만 삭제.
- **Rationale**: 사용자 결정(TTL 실익). Redis 유실 = 전체 재로그인은 수용(스펙 기록). refresh 검증 = JWT 서명·만료 + Redis jti 존재 이중 확인 — 위조(저장된 적 없음)·로그아웃(삭제됨)·만료(TTL 소멸) 모두 401 로 수렴.
- **Refresh rotation 포함 (사용자 결정 2026-07-10 — 초기 "범위 밖" 개정)**: 재발급 성공 시 **access·refresh 둘 다 새로 발급**한다. 이전 jti 를 Redis 에서 삭제 → 새 jti 를 전체 TTL(14일)로 저장 — refresh 유효기간이 재발급마다 연장되는 sliding session. 이전 refresh 는 즉시 무효(재사용 시 Redis 부재로 401). 탈취 재사용 **탐지·알림**(reuse detection)은 여전히 범위 밖.
- **인프라 도입**: docker-compose.yml 에 `meogo-redis`(redis:8) 추가, `application-local.yml` 등 프로필별 `spring.data.redis.*` 설정, 카탈로그에 `spring-boot-starter-data-redis`(persistence). 통합 테스트는 Redis Testcontainers(`GenericContainer` + `@ServiceConnection`).

## R5. API·쿠키 설계

- **Decision**: 컨트롤러 3개 — `POST /api/v1/auth/login`(body `{idToken}`) / `POST /api/v1/auth/refresh` / `POST /api/v1/auth/logout`. 응답은 전부 `ResponseEntity<BaseResponse<T>>`(규약). 토큰은 **`Set-Cookie`(ResponseCookie)** 로: `access_token`(Path=/, Max-Age 30m), `refresh_token`(**Path=/api/v1/auth** — refresh·logout 에만 전송돼 노출 최소화, Max-Age 14d). 둘 다 HttpOnly·SameSite=Lax, Secure 는 프로필별(local 제외 활성). 로그인 응답 payload = `{memberId, newMember}` — 클라이언트 온보딩 분기용. logout·refresh 는 payload 없이 ok. 로그아웃 시 두 쿠키 즉시 만료(Max-Age=0).
- **Rationale**: 사용자 결정(쿠키 전달). refresh 쿠키의 Path 제한은 모든 API 요청에 refresh 가 실려 나가는 것을 막는 표준 완화책.
- **예외 응답 체계 (사용자 요구 — 조작/만료 구분 정의)**: `AuthErrorCode`(kernel `ErrorCode` 구현) + `AuthException`(MeogoException 하위) 을 application:client `auth` 에 둔다. 전부 401, 공통 봉투(BaseResponse fail)로 응답 — 기존 `GlobalExceptionHandler` 가 자동 매핑.

| 코드 | 상황 | 부가 동작 |
|------|------|-----------|
| `INVALID_SOCIAL_TOKEN` | Firebase 토큰 조작(서명 불일치)·만료·aud/iss 불일치 | — |
| `UNSUPPORTED_PROVIDER` | 구글·애플 외 provider | — |
| `INVALID_REFRESH_TOKEN` | refresh 조작(서명 불일치·형식 불량)·위조/폐기(Redis 미존재 — 로그아웃됐거나 rotation 으로 회전됨) | 쿠키 2종 만료 |
| `EXPIRED_REFRESH_TOKEN` | refresh 만료(jjwt `ExpiredJwtException` 으로 구분) | **강제 로그아웃** — Redis jti 삭제(있다면) + 쿠키 2종 만료. 재로그인 필수 |

  - access 토큰의 조작/만료 구분(`INVALID_ACCESS_TOKEN`/`EXPIRED_ACCESS_TOKEN`)은 `TokenParser` 가 동일하게 던질 수 있게 만들되, **소비자(보호 API 인증 필터)는 후속** — 이번 PR 에 보호 대상 API 가 없다. 클라이언트 규약: 어떤 401 이든 refresh 재발급을 먼저 시도하고, 재발급마저 401 이면 재로그인.
  - 클라이언트가 만료/조작을 기계적으로 구분할 필요는 없다(행동이 같음 — refresh 시도 → 실패 시 재로그인). 메시지 구분은 디버깅·로그 용도.

## R6. 정지 회원 로그인 거부 — 기존 메커니즘 수용

- **Decision**: 별도 코드를 추가하지 않는다. 정지 회원의 로그인은 `findByIdentity`(ACTIVE 필터) 0건 → `saveNew` 유니크 충돌 → 재조회 폴백 0건 → `DUPLICATE_SOCIAL_IDENTITY` 예외로 **자동 차단**되고, insert 가 유니크 제약으로 실패하므로 **새 회원은 생성되지 않는다**(FR-010 충족 — 통합 테스트로 검증).
- **Rationale**: KB-117 R3 에 이미 기록된 동작이다. 에러 의미(중복 신원 ≠ 정지)가 어긋나는 건 알려진 한계로, `SUSPENDED_MEMBER` 전용 에러는 정지 운영 도구(port 확장 필요)와 함께 후속 — port 불변·소비자 없는 확장 금지 원칙 유지.

## R7. 이메일 처리 — 가입 시 1회 저장, 재로그인 갱신 없음

- **Decision**: 이메일은 신규 가입(`Member.signUp`) 시 신원의 일부로 1회 저장하고, 기존 회원 재로그인에서는 갱신하지 않는다.
- **Rationale**: 신원 키는 (provider, providerUserId) 뿐이고(KB-103), `MemberIdentityResolver` 기존 흐름이 그대로다. 이메일 변경 동기화는 요구에 없다(YAGNI).

## R8. 테스트 전략

- **Decision**: ① application 단위 — 페이크 `SocialTokenVerifier`·인메모리 `RefreshTokenStore`·페이크 `MemberRepository` 로 로그인 유스케이스(신규 가입/기존 재로그인/미지원 provider/검증 실패)와 refresh/logout 유스케이스 검증 ② Firebase 클레임 매핑 순수 함수 단위 테스트(원본 sub 추출·provider 매핑·릴레이 이메일) ③ persistence 통합 — Redis Testcontainers 로 저장/조회/삭제/TTL ④ app:api web — MockMvc + `@TestConfiguration` 페이크 verifier 빈으로 login/refresh/logout 엔드포인트·쿠키 속성·401 매핑 검증(Redis·MySQL Testcontainers).
- **Rationale**: 헌법 I. Firebase 실호출은 어떤 테스트에도 없다(서비스 계정 키 불필요).
