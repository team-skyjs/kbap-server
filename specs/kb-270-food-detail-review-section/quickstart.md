# Quickstart: 음식 상세 리뷰 섹션 응답 개편 검증

## 테스트 실행

```bash
# 이 기능의 계약 테스트만
./gradlew :api:test --tests "com.kbap.api.food.FoodDetailReviewSectionTest"

# 회귀 확인 — 기존 상세 응답 테스트 포함 api 전체
./gradlew :api:test

# ArchUnit 제외 빠른 루프
./gradlew :api:test -Dkotest.tags="!arch"
```

통합 테스트는 MySQL Testcontainers(`:common` testFixtures `MySqlContainerConfig`)로 뜬다 — Docker 실행 중이어야 한다.

## 수동 확인 (local 프로필)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
```

```bash
# 비회원 — blur=true, 수치 기본값
curl "http://localhost:8080/api/v1/foods/detail?foodId=1&lang=en" | jq .payload.review

# 회원 — blur=false, 실수치 (ACCESS_TOKEN 은 로그인 API 로 발급)
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  "http://localhost:8080/api/v1/foods/detail?foodId=1&lang=en" | jq .payload.review
```

기대 결과는 [contracts/food-detail-response.md](contracts/food-detail-response.md) 의 케이스 4종과 일치해야 한다. Swagger UI(`/swagger-ui.html`)에서 `review` 스키마 설명(기본값·blur 의미)도 함께 확인한다.
