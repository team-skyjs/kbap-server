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
FoodSummaryResponse (api.food)               # 응답 계층에만 필드 추가
├── averageRating: Double?                   # 소수 1자리 반올림, 리뷰 0건 null
└── reviewCount: Long                        # 0건 0

FoodRating (api.review)                      # ReviewService.getFoodRatings 결과 값
├── averageRating: Double
└── reviewCount: Long
```

- **`FoodSummaryView`(common) 는 무변경** — view 에 필드를 넣으면 `FoodService`(common.domain.food)가 review 리포지토리를 참조해 도메인 방향 맵에 food→review 가 생기므로, 합류를 api 응답 계층으로 옮겼다(구현 시 확정).
- 집계 조회는 `ReviewService.getFoodRatings(foodIds)`(distinct·빈 목록 가드·상세와 동일 반올림 `Math.round(avg * 10) / 10.0`), 컨트롤러는 서비스만 호출한다(리포지토리 직접 호출 금지 규칙).

## 조립처 (컨트롤러 3곳 — 배치 집계 맵 합류)

| 조립처 | 화면 |
|--------|------|
| `FoodController.toPage` | 음식 목록(browse)·검색 |
| `HomeController` → `HomeResponse.from` | 홈 인기 음식 + 최근 스캔 |
| `BookmarkController.list` | 북마크 목록 |

(어드민 그리드는 별도 `AdminFoodSummaryView` 스키마라 대상 아님)

## 상태 전이

없음 — 조회 전용.
