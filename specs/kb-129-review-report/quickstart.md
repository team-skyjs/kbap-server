# Quickstart: 리뷰 신고 (kb-129)

## 자동 검증

```bash
./gradlew :api:test --tests "com.kbap.api.report.*"     # 신고 MockMvc·유스케이스
./gradlew :common:test --tests "com.kbap.common.domain.report.*"
./gradlew build                                          # 전체 (ArchUnit·ErrorCodeStatusTest 포함)
```

## 수동 시나리오 (local 프로필 + 도커 MySQL)

회원 A·B 토큰과 READY 음식(리뷰 1건 이상)이 준비돼 있다고 가정.

```bash
# 1. B의 리뷰를 A가 신고 → 200
curl -X POST localhost:8080/api/v1/reports \
  -H "Authorization: Bearer $TOKEN_A" -H "Content-Type: application/json" \
  -d '{"targetType":"REVIEW","targetId":42,"reason":"SPAM","detail":"광고"}'

# 2. 같은 신고 반복 → 409 REPORT-002
# 3. A가 자기 리뷰 신고 → 400 REPORT-001
# 4. 없는 리뷰(999999) 신고 → 404 REPORT-003
# 5. 토큰 없이 신고 → 401  (필터 등록 확인 — 이게 200 이면 WebConfig 누락)

# 6. A의 목록에서 42 사라짐 / B의 목록에는 그대로
curl "localhost:8080/api/v1/reviews?foodId=7" -H "Authorization: Bearer $TOKEN_A"
curl "localhost:8080/api/v1/reviews?foodId=7" -H "Authorization: Bearer $TOKEN_B"
```

Flyway 확인: `SHOW CREATE TABLE report;` — UNIQUE `uk_report_reporter_target`, FK 는 `reporter_member_id` 만.
