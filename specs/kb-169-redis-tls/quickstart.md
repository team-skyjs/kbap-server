# Quickstart: KB-169 Redis TLS 검증 런북

## 1. 로컬 가드 테스트

```bash
./gradlew :app:api:test --tests "com.kbap.app.api.config.RedisSslConfigTest"
```

- Red 확인(구현 전): 4개 프로필 yml 에 `spring.data.redis.ssl.enabled` 부재로 실패해야 한다.
- Green 확인(구현 후): 통과 + 전체 `./gradlew :app:api:test` 회귀 무손상.

## 2. 로컬 개발 흐름 비파괴 확인

평문 docker Redis(localhost:6379) 그대로:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun
```

부팅 후 로그인 → refresh token 발급이 기존과 동일해야 한다(local 기본값 `REDIS_SSL_ENABLED:false`).

## 3. prod 배포 후 검증 (Jira DoD)

1. 배포 직후 앱 로그에 Redis 커넥션 예외(`RedisConnectionFailureException` 등)가 없는지 확인.
2. 로그인 호출:

```bash
curl -s -X POST https://<prod-host>/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"idToken": "<유효한 Firebase idToken>"}'
```

기대: `success=true` + accessToken/refreshToken 발급(500 소멸). refresh 갱신 API 까지 한 번 돌리면 저장·조회 왕복이 검증된다.

3. (선택) 서버에서 TLS 접속 자체 확인: `redis-cli --tls -h $REDIS_HOST -p $REDIS_PORT ping` → `PONG`.

## 4. 환경변수 탈출구

특정 환경의 Redis 가 평문으로 판명되면 재커밋 없이 해당 환경에 `REDIS_SSL_ENABLED=false` 를 주입한다(반대 방향도 동일).
