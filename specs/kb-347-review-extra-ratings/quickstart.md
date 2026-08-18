# Quickstart: 리뷰 세부 평가 2종 수동 검증

## 준비

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
# 회원 토큰 확보 후 $TOKEN, 대상 음식 id $FOOD_ID
```

## 1. 작성 — 두 항목 포함

```bash
curl -s -X POST localhost:8080/api/reviews \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 1.0" -H "Content-Type: application/json" \
  -d '{"foodId": '$FOOD_ID', "rating": 4, "servingSpeed": 5, "staffKindness": 3}'
# → payload.servingSpeed=5, payload.staffKindness=3
```

## 2. 누락·0 = 평가 안 함

```bash
curl -s -X POST ... -d '{"foodId": '$FOOD_ID', "rating": 3}'
# → payload.servingSpeed=0, payload.staffKindness=0
```

## 3. 범위 밖 400

```bash
curl -s -X POST ... -d '{"foodId": '$FOOD_ID', "rating": 3, "servingSpeed": 6}'
# → 400, success=false
```

## 4. 열람 경로 노출 (비회원 포함)

```bash
curl -s "localhost:8080/api/reviews?foodId=$FOOD_ID&lang=en" -H "X-API-Version: 1.0"   # 목록(비회원)
curl -s "localhost:8080/api/foods/$FOOD_ID?lang=en" -H "X-API-Version: 1.0"            # recentReviews
curl -s "localhost:8080/api/reviews/me?lang=en" -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 1.0"
# → 전 경로에서 servingSpeed·staffKindness 가 항상 0~5 숫자로 내려간다
```

## 5. 수정 — 0 으로 지움

```bash
curl -s -X PATCH localhost:8080/api/reviews/$REVIEW_ID ... -d '{"rating": 4, "servingSpeed": 0, "staffKindness": 2}'
# → servingSpeed=0 저장 확인
```

## 6. 기존 리뷰 회귀

- 마이그레이션 전 작성된 리뷰 조회 → 두 필드 0, 별점·본문 등 불변.
