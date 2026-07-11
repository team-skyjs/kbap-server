# Quickstart: 온보딩 — 기피 음식·국가·앱 언어 설정 + 완료 처리

**Date**: 2026-07-12 | **Plan**: [plan.md](plan.md)

## 사전 준비

로컬 스택(기존과 동일 — 신규 인프라 없음):

```bash
docker compose up -d          # meogo-mysql·meogo-mongo·meogo-redis
SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun
```

JWT 시크릿 등 인증 설정은 KB-118 quickstart 와 동일(루트 `.env`). Flyway 신규 마이그레이션 1건(`onboarding_status` → `onboarding_completed` 칼럼 rename) — 부팅 시 자동 적용되며, 통합 테스트(Testcontainers + `ddl-auto=validate`)에서도 검증된다.

## 테스트 실행

```bash
./gradlew :application:client:test --tests "com.meogo.application.client.member.*"   # 유스케이스 단위(페이크 repo)
./gradlew :app:api:test --tests "com.meogo.app.api.member.*"                         # MockMvc 통합(Testcontainers)
./gradlew build                                                                      # 전체
```

## 수동 검증 시나리오

1. 로그인으로 액세스 토큰 획득:

```bash
ACCESS=$(curl -s -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"idToken":"<firebase-id-token>"}' | jq -r '.payload.accessToken')
```

2. 온보딩 전 내 프로필 — `onboardingCompleted: false` 확인:

```bash
curl -s localhost:8080/api/v1/members/me -H "Authorization: Bearer $ACCESS" | jq
```

3. 온보딩 제출(성공 → 200):

```bash
curl -s -X POST localhost:8080/api/v1/members/me/onboarding \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"nickname":"길동이","avoidanceSubstanceCodes":["EGG","MILK"],"countryCode":"US","appLanguage":"en"}' | jq
```

4. 재조회 — 프로필 반영 + `onboardingCompleted: true` 확인(2번 명령 재실행).

5. 실패 경로:

```bash
# 무효 성분 코드 → 400
curl -s -X POST localhost:8080/api/v1/members/me/onboarding \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"nickname":"길동이","avoidanceSubstanceCodes":["NOT_A_CODE"],"countryCode":"US","appLanguage":"en"}' | jq
# 재제출 → 400 "이미 온보딩을 완료했습니다" (3번 재실행)
# 미인증 → 401
curl -s localhost:8080/api/v1/members/me | jq
```

Swagger UI: `localhost:8080/swagger-ui/index.html` (members 태그).
