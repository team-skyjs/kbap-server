# Quickstart: 음식 리스트·상세 조회 응답에 북마크 여부 포함 (KB-153)

## 검증 실행

```bash
# 관련 통합 테스트 (MySQL Testcontainers — Docker 필요)
./gradlew :app:api:test --tests "com.kbap.app.api.food.FoodListControllerTest" \
  --tests "com.kbap.app.api.food.FoodDetailControllerTest" \
  --tests "com.kbap.app.api.food.FoodSearchControllerTest" \
  --tests "com.kbap.app.api.bookmark.BookmarkControllerTest"
./gradlew :domain:bookmark:test

# 회귀 전체
./gradlew test
```

## 수동 확인 (local 프로필)

```bash
./gradlew :app:api:bootRun   # SPRING_PROFILES_ACTIVE=local

# 비회원 — 모든 항목 bookmarked=false
curl -s "http://localhost:8080/api/v1/foods" | jq '.payload.items[] | {foodId, bookmarked}'

# 회원 — 북마크 등록 후 리스트·상세에서 true
TOKEN="<access token>"
curl -s -X POST -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/bookmarks/1"
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/foods/detail?foodId=1" | jq '.payload.bookmarked'
```

## 산출물 체크

- `BookmarkService.findBookmarkedFoodIds` — 비회원 null → emptySet, 페이지당 IN 쿼리 1회
- `FoodSummaryResponse`·`FoodDetailResponse` — `bookmarked` non-null boolean
- 컨트롤러 병합 4곳(browse·search·detail·북마크 목록 상수 true)
- Swagger UI — 필드 설명에 "비회원은 항상 false" 명시
