# Data Model: 음식 목록 응답에 리뷰 평점·개수 추가

## 스키마 변경

**없음.** 기존 `food_review` 테이블 집계 조회만 추가한다(`food_id in (...) group by food_id` — 기존 `food_id` FK 인덱스 사용).

## 쿼리 추가 (`ReviewJpaRepository`)

```
interface FoodRatingAggregate { foodId: Long; average: Double?; reviewCount: Long }

aggregateRatingsByFoodIds(foodIds: List<Long>): List<FoodRatingAggregate>
```

- `select r.foodId as foodId, avg(r.rating) as average, count(r) as reviewCount from Review r where r.foodId in :foodIds group by r.foodId`
- 소프트 삭제 리뷰는 `@SQLRestriction` 으로 자동 제외 — 상세 단건 집계(`aggregateRating`)와 동일 규칙. 리뷰 없는 foodId 는 결과에 행이 없다(소비처에서 null/0 처리).

## DTO 변경

```
FoodSummaryView (common.domain.food.dto)     # 기존 필드 유지 + 추가
├── averageRating: Double?                   # 소수 1자리 반올림, 리뷰 0건 null
└── reviewCount: Long                        # 0건 0

FoodSummaryResponse (api.food)               # view 미러링 + 추가
├── averageRating: Double?
└── reviewCount: Long
```

- `FoodSummaryView.from(food, lang, userAvoidedCodes, imageUrl, averageRating, reviewCount)` — review 타입을 모른 채 값만 받는다(R4).
- 반올림은 상세와 동일 공식(`Math.round(avg * 10) / 10.0`) — 조립 지점에서 적용.

## 조립처 (5곳 — 배치 집계 맵 합류)

| 조립처 | 화면 |
|--------|------|
| `FoodService.foodPage` | 음식 목록(browse)·검색 |
| `HomeService` | 홈 인기 음식 + 최근 스캔 |
| `BookmarkService` | 북마크 목록 |
| `AdminFoodService` | 어드민 음식 그리드 |

## 상태 전이

없음 — 조회 전용.
