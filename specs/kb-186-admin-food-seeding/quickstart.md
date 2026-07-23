# Quickstart: 관리자 신규 음식 적재

## 1. ADMIN 토큰 발급 (발급 API 없음 — 오프라인 발급)

대상 환경의 `kbap.auth.jwt.secret` 으로 직접 서명한다. 가장 간단한 방법은 임시 Kotest/main 스니펫:

```kotlin
val issuer = JwtTokenIssuer(
    AuthTokenProperties(
        secret = "<대상 환경의 kbap.auth.jwt.secret>",
        accessTtl = Duration.ofDays(1),   // 필요 기간만큼 — 표준 TTL 정책(Clarify Q2)
        refreshTtl = Duration.ofDays(14), // 미사용
    ),
)
println(issuer.issueAccessToken(memberId = 0, role = MemberRole.ADMIN))
```

- subject(memberId)=0 이어도 된다 — admin 엔드포인트는 member 조회를 하지 않는다.
- 만료되면 같은 방법으로 재발급. 유출 시 시크릿 회전으로 폐기(스펙 Assumptions).

## 2. 호출

```bash
curl -X POST http://localhost:8080/api/v1/admin/foods \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"koreanNames": ["마라샹궈", "김치찌개", "탕후루"]}'
# → {"success":true,"payload":{"requested":3,"created":2,"skipped":1},...}
```

재실행하면 `created:0, skipped:3` — 멱등 확인.

USER 토큰으로 호출하면 403 `AUTH-008`, 토큰 없이 호출하면 401.

## 3. 테스트

```bash
./gradlew :domain:food:test --tests '*FoodServiceTest*'        # seedIncomplete 카운트·멱등·경합
./gradlew :app:api:test --tests '*AdminFoodControllerTest*'    # 401/403/200·멱등·INCOMPLETE (Testcontainers MySQL)
./gradlew build                                                # 전체 + ArchUnit ModuleBoundaryTest
```

## 4. 확인 쿼리

```sql
SELECT korean_name, content_status, spiciness, avoidance_substances
FROM food WHERE content_status = 'INCOMPLETE' ORDER BY id DESC LIMIT 10;
-- 기대(kb-182 머지 후): spiciness = -1, avoidance_substances = NULL
```
