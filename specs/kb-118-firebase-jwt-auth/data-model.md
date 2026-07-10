# Data Model: Firebase 토큰 검증 소셜 로그인 (KB-118)

## 도메인/포트 (core/member — 추가만, 기존 불변)

### RefreshTokenStore (신규 port, Spring-free)

| 메서드 | 시그니처 | 의미 |
|--------|----------|------|
| save | `save(jti: String, memberId: Long, ttl: Duration)` | 로그인 시 refresh 세션 등록 |
| findMemberId | `findMemberId(jti: String): Long?` | 재발급 시 유효 세션 확인 (없으면 null → 401) |
| delete | `delete(jti: String)` | 로그아웃 폐기 |

- `Member`·`SocialIdentity`·`MemberRepository` 는 **불변**. `MemberIdentityResolver` 는 소비자가 로그인 하나뿐이라 `LoginUseCase` 로 인라인 후 **삭제**(사용자 결정 — 수동 @Bean 등록 제거).

## 유스케이스 계층 (application/client — 신규 `auth` 패키지)

| 구성 요소 | 역할 |
|-----------|------|
| `SocialTokenVerifier` (interface) | idToken 문자열 → `SocialIdentity` (테스트 seam) |
| `FirebaseTokenVerifier` | firebase-admin `verifyIdToken` 구현. `@ConditionalOnProperty(meogo.auth.firebase.credentials-path)` |
| `FirebaseClaimMapper` (순수 함수) | 클레임 Map → `SocialIdentity` — sign_in_provider 매핑·identities 원본 sub 추출·email. 단위 테스트 대상 |
| `TokenIssuer` / `TokenParser` | jjwt HS256. access(sub=memberId, 30m) / refresh(sub=memberId, jti=UUID, 14d) 발급·검증 |
| `LoginUseCase` | verify → 신원 해소(findByIdentity→없으면 signUp+saveNew→DUPLICATE 시 재조회 폴백) → 토큰 2종 발급 → `RefreshTokenStore.save(jti)` → `LoginResult` |
| `RefreshUseCase` | refresh JWT 검증(조작→INVALID, 만료→EXPIRED) + store jti 존재 확인(부재→INVALID) → **rotation**: 이전 jti delete → access·refresh 둘 다 신규 발급 → 새 jti 를 전체 TTL 로 save → `RefreshResult(accessToken, refreshToken)` |
| `LogoutUseCase` | refresh JWT 에서 jti 추출 → `store.delete(jti)` (無 refresh 도 성공 처리 — 멱등) |
| `AuthErrorCode` / `AuthException` | INVALID_SOCIAL_TOKEN·UNSUPPORTED_PROVIDER·INVALID_REFRESH_TOKEN·**EXPIRED_REFRESH_TOKEN(강제 로그아웃)** — 전부 401, kernel ErrorCode/MeogoException 계층 (상세 표 = research R5) |

## 영속 (infra/persistence — 신규 `auth` 패키지)

### RefreshTokenRedisAdapter

- `StringRedisTemplate` 기반 `RefreshTokenStore` 구현.
- 키 `auth:refresh:{jti}` → 값 `memberId`, **TTL = refresh 수명**(저장 수명 = 토큰 수명, 만료 잔존 없음).
- 다중 기기: 로그인마다 새 jti → 키가 기기 수만큼 자연 공존. 로그아웃은 제시된 jti 만 삭제.
- **Flyway 마이그레이션 없음** (RDB 미사용 — 이슈 DoD 의 "마이그레이션 작성"은 Redis 결정으로 대체).

## 토큰 명세

| | Access | Refresh |
|---|---|---|
| 형식 | JWT HS256 | JWT HS256 |
| 클레임 | `sub`=회원 PK, `exp` | `sub`=회원 PK, `jti`=UUID, `exp` |
| 개인정보 | **없음 (금지)** | **없음 (금지)** |
| 수명 | 30분 (`meogo.auth.jwt.access-ttl`) | 14일 (`meogo.auth.jwt.refresh-ttl`) |
| 서버 저장 | 없음 (stateless) | Redis jti (폐기 가능) |
| 쿠키 | `access_token`, Path=/ | `refresh_token`, **Path=/api/v1/auth** |
| 쿠키 속성 | HttpOnly·SameSite=Lax·Secure(non-local) | 동일 |

## 상태 흐름

```
[로그인]      verify(idToken) → resolve(identity) → access+refresh 발급 → Redis save(jti, TTL 14d)
[재발급 성공] refresh 검증 ∧ Redis jti 존재 → delete(구 jti) → access+refresh 신규 발급 → save(신 jti, TTL 14d)
              → 쿠키 2종 갱신 (rotation — refresh 유효기간 연장, 구 refresh 즉시 무효)
[재발급 실패] 조작·위조·폐기(구 jti 재사용 포함) → 401 INVALID_REFRESH_TOKEN + 쿠키 2종 만료
              만료 → 401 EXPIRED_REFRESH_TOKEN + Redis jti 삭제 + 쿠키 2종 만료 (강제 로그아웃 → 재로그인)
[로그아웃]    Redis delete(jti) + 쿠키 2종 만료(Max-Age=0)
[정지회원]    findByIdentity 0건 → saveNew 유니크 충돌 → DUPLICATE_SOCIAL_IDENTITY (새 회원 미생성 — R6)
[탈퇴회원]    재로그인 시 신규 가입 (KB-117 DELETED:{id} 표식이 유니크 개방)
```

## 설정 (application.yml 계열)

```yaml
meogo:
  auth:
    firebase:
      credentials-path:      # 서비스 계정 키 경로 — 미설정 시 verifier 빈 미생성(부팅 안전)
    jwt:
      secret:                # env JWT_SECRET, 32바이트 이상
      access-ttl: 30m
      refresh-ttl: 14d
spring:
  data:
    redis:                   # 프로필별 host/port (local: docker meogo-redis)
```

## 신규 의존성 (gradle/libs.versions.toml)

| 좌표 | 모듈 | 스코프 |
|------|------|--------|
| `com.google.firebase:firebase-admin` | application:client | implementation (헌법 III 완화 — plan Complexity Tracking) |
| `io.jsonwebtoken:jjwt-api` | application:client | implementation |
| `io.jsonwebtoken:jjwt-impl` / `jjwt-jackson` | application:client | runtimeOnly |
| `spring-boot-starter-data-redis` | infra:persistence | implementation |
| Redis Testcontainers (`GenericContainer`+`@ServiceConnection`) | persistence testFixtures | testFixtures |
