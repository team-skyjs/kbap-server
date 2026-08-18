# Quickstart: KB-334 검증

## 테스트로 검증 (기본)

```bash
./gradlew :api:test --tests "com.kbap.api.food.*"        # 상세 조회 스위트
./gradlew :api:test                                       # api 전체 회귀
```

핵심 시나리오 위치:
- 비회원 위험도 null·bookmarked false — `FoodDetailControllerTest`
- 비회원 리뷰 overall 실수치·sameCountry null·blur 부재 — `FoodDetailReviewSectionTest`
- 회원 불변 회귀 — `FoodDetailRatingTest`

## 로컬 수동 확인

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun

# 비회원 (Authorization 없음)
curl -s http://localhost:8080/api/foods/1?lang=en -H "X-API-Version: 1.0" | jq .payload
# 기대: overallRiskStatus=null, review.sameCountry=null, review.blur 키 없음, overall 은 실수치

# 회원
curl -s http://localhost:8080/api/foods/1?lang=en -H "X-API-Version: 1.0" \
  -H "Authorization: Bearer <accessToken>" | jq .payload
# 기대: 기존과 동일 (blur 키만 사라짐)
```
