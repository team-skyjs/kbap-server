# Quickstart: 맵기 선호 -1 센티널 (KB-158)

## 검증 명령

```bash
# member 도메인 단위 테스트
./gradlew :domain:member:test

# web 통합 테스트 (MockMvc + MySQL Testcontainers)
./gradlew :app:api:test --tests "com.kbap.app.api.member.MemberControllerTest"

# 전체 (ArchUnit 포함)
./gradlew build
```

## 수동 확인 시나리오 (local 프로필 + Swagger UI)

1. `./gradlew :app:api:bootRun` (SPRING_PROFILES_ACTIVE=local)
2. 로그인 → access token 획득
3. `POST /api/v1/members/me/onboarding` — `spicinessPreference` 생략 → `GET /api/v1/members/me/profile` 에서 `-1` 확인
4. `PATCH /api/v1/members/me/profile` — `{"spicinessPreference": 7}` → 조회 시 7
5. `PATCH /api/v1/members/me/profile` — `{"spicinessPreference": -1}` → 조회 시 -1 (미설정 복귀)
6. `PATCH /api/v1/members/me/profile` — `{"nickname": "x"}` (맵기 생략) → 맵기 값 유지 확인
7. `PATCH /api/v1/members/me/profile` — `{"spicinessPreference": 11}` → 400 + `MEMBER-009` + 갱신 메시지 확인
