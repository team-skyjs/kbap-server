# Quickstart: kb-268 프로필 수정 v2

## 검증 실행

```bash
./gradlew :api:test --tests "com.kbap.api.member.*"   # member 기능 테스트만
./gradlew build                                        # 전체 (ArchUnit 포함)
```

## 수동 확인 (local 프로필)

```bash
./gradlew :api:bootRun   # SPRING_PROFILES_ACTIVE=local
# 로그인으로 access token 획득 후:
curl -X PATCH http://localhost:8080/api/v2/members/me/profile \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"nickname":"새닉네임","countryCode":"US"}'
# 기대: 200, 닉네임만 변경 — countryCode 는 무시되어 GET /api/v1/members/me/profile 에서 기존 국적 유지
```

Swagger UI 에서 v2 엔드포인트 노출 확인: `http://localhost:8080/swagger-ui/index.html`
