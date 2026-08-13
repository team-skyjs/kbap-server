# Quickstart: kb-324 음식 목록 평점·리뷰 수

## 작업 위치

워크트리 `.claude/worktrees/kb-324-food-list-rating/` (브랜치 `kb-324-food-list-rating`, develop 2308f0f8 기준). **모든 편집·빌드는 워크트리 경로에서.**

## 검증 명령

```bash
./gradlew :common:test --tests "com.kbap.common.domain.review.ReviewJpaRepositoryTest"
./gradlew :api:test --tests "com.kbap.api.food.*" --tests "com.kbap.api.home.*" --tests "com.kbap.api.bookmark.*"
./gradlew build
```

## 핵심 참조 파일

- `common/.../review/ReviewJpaRepository.kt` — 단건 `aggregateRating`(상세용) 선례 옆에 배치 버전 추가
- `common/.../food/dto/FoodSummaryView.kt` + `FoodService.foodPage` — 목록·검색 조립
- `api/.../home/HomeService.kt`·`bookmark/BookmarkService.kt`·`admin/AdminFoodService.kt` — 나머지 조립처
- `api/.../review/ReviewService.getFoodRatingSummary` — 반올림 공식(소수 1자리) 일치 확인
