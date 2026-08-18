# Quickstart: KB-348 검증

## 테스트로 검증 (기본)

```bash
./gradlew :api:test --tests "com.kbap.api.review.*"    # 리뷰 스위트 (비회원 개방 + 401 회귀)
./gradlew :api:test                                     # api 전체 회귀
```

## 로컬 수동 확인

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun

# 비회원 목록 (음식별) — 200 기대
curl -s "http://localhost:8080/api/reviews?foodId=1&lang=en" -H "X-API-Version: 1.0" | jq '.payload.items[0].likedByMe'
# 기대: false

# 비회원 전체 피드 — 200 기대
curl -s "http://localhost:8080/api/reviews?lang=en" -H "X-API-Version: 1.0" | jq '.success'

# 비회원 작성 — 401 기대
curl -s -o /dev/null -w "%{http_code}\n" -X POST "http://localhost:8080/api/reviews" \
  -H "X-API-Version: 1.0" -H "Content-Type: application/json" -d '{"foodId":1,"rating":5}'

# 비회원 내 리뷰 — 401 기대
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/api/reviews/me?lang=en" -H "X-API-Version: 1.0"
```
