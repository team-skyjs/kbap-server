# Quickstart: Firebase 소셜 로그인 검증 (KB-118)

## 1. 테스트 (Docker 필요 — MySQL·Redis Testcontainers)

```bash
./gradlew :application:client:test        # 로그인/재발급/로그아웃 유스케이스(페이크 verifier)·클레임 매핑 단위
./gradlew :infra:persistence:test         # RefreshTokenRedisAdapter 통합 (Redis Testcontainers)
./gradlew :app:api:test                   # auth 엔드포인트 MockMvc (페이크 verifier 빈)
./gradlew build                           # 전체 회귀 (ArchUnit 포함)
```

Firebase 실호출은 어떤 테스트에도 없다 — 서비스 계정 키 없이 전부 돈다.

## 2. 로컬 실행

```bash
docker compose up -d meogo-mysql meogo-redis   # redis 서비스 신규
```

`.env`(git-ignored)에:

```
JWT_SECRET=<32바이트 이상 랜덤>
FIREBASE_CREDENTIALS_PATH=/path/to/firebase-service-account.json
```

서비스 계정 키: Firebase 콘솔 → 프로젝트 설정 → 서비스 계정 → 새 비공개 키. **저장소 커밋 금지.** dev/staging/prod 는 배포 환경 변수/시크릿으로 동일 프로퍼티 주입(문서화 = 이 파일).

키 미설정 시: 검증기가 항상 거절하는 폴백으로 대체되어 **부팅은 정상**이고 `/api/v1/auth/login` 만 401 을 돌려준다(검증됨).
`JWT_SECRET` 은 필수다 — 32바이트 미만이거나 비어 있으면 부팅에 실패한다(약한 키로 조용히 뜨는 것을 막는다).

## 3. 수동 스모크

```bash
# 로그인 (클라이언트에서 얻은 Firebase ID 토큰)
curl -i -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"idToken":"<token>"}'
# → Set-Cookie 2건 + {"success":true,"payload":{"memberId":N,"newMember":true|false}}

# 재발급
curl -i -X POST localhost:8080/api/v1/auth/refresh --cookie "refresh_token=<jwt>"
# 로그아웃
curl -i -X POST localhost:8080/api/v1/auth/logout --cookie "refresh_token=<jwt>"
# 로그아웃 후 재발급 → 401
```

Redis 확인: `docker exec meogo-redis redis-cli keys 'auth:refresh:*'` / `ttl <key>`

## 4. 수용 시나리오 ↔ 테스트 매핑

| 시나리오 | 위치 |
|----------|------|
| 신규 가입 / 기존 재로그인 (newMember 플래그) | LoginUseCase 단위 + MockMvc |
| 위조·만료 토큰 401, 회원 미생성 | LoginUseCase 단위(페이크 verifier 예외) + MockMvc |
| 원본 sub 추출·provider 매핑·릴레이 이메일 | FirebaseClaimMapper 단위 |
| 정지 회원 거부 + 회원 미생성 | app:api 통합(멤버 정지 후 로그인) |
| 재발급 rotation — 두 토큰 갱신·구 refresh 즉시 무효·유효기간 연장 | RefreshUseCase 단위 + MockMvc (재발급 후 구 refresh 재사용 → 401) |
| refresh 만료 → EXPIRED_REFRESH_TOKEN 401 + 강제 로그아웃(쿠키 만료) | RefreshUseCase 단위(만료 토큰 발급 페이크) + MockMvc |
| 조작·위조·폐기 refresh → INVALID_REFRESH_TOKEN 401 | RefreshUseCase 단위 + Redis 통합 + MockMvc |
| 로그아웃 후 재발급 불가 | MockMvc (login→logout→refresh 401) |
| TTL = 토큰 수명 | Redis 어댑터 통합 (save 후 ttl 확인) |
