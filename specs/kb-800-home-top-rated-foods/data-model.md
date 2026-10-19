# Data Model — KB-800

스키마 변경 없음. 읽기 모델만 추가.

- **TopRatedFoodAggregate** (common, native 프로젝션): foodId, reviewCount, taste, servingSpeed?, staffKindness?
- **TopRatedFoodRating** (api.review): foodId, reviewCount, taste, servingSpeed?, staffKindness?, overallRating — 네 값 소수 1자리 half-up. overall = null 아닌 원값 평균 후 반올림.
- **TopRatedFoodView** (api.home): summary(FoodSummaryView) + rating(TopRatedFoodRating)
- 소스 컬럼: `food_review.rating`(1~5), `serving_speed_rating`·`staff_kindness_rating`(0~5, 0 = 미입력), `status`; `food.status`, `food.content_status`.
