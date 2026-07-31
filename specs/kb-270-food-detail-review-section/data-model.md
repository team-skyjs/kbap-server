# Data Model: 음식 상세 리뷰 섹션 응답 개편

**Feature**: kb-270-food-detail-review-section | **Date**: 2026-07-31

## DB 변경

**없음.** COUNT 집계 유지 결정(research.md)으로 Flyway 마이그레이션·엔티티 변경이 발생하지 않는다.
기존 접점만 재사용한다:

- `food_review` 테이블 — 집계 원천. 인덱스 `idx_food_review_food_recent(food_id, id)`·`idx_food_review_food_country(food_id, author_country_code)` 가 집계 WHERE 를 커버.
- `Review` 엔티티(`common.domain.review.model`) — 변경 없음. `BaseEntity.@SQLRestriction` 으로 활성 리뷰만 집계에 잡힘(FR-005 자동 충족).
- `ReviewJpaRepository.aggregateRating(foodId, countryCode?)` — 변경 없음(avg+count 단일 쿼리).

## 응답 모델 (api 경계)

### ReviewSummaryResponse (신규 — `FoodDetailResponse` 중첩 data class)

| 필드 | 타입 | 규칙 |
|------|------|------|
| `overall` | `ReviewRatingResponse` | 전체 사용자 리뷰 요약 — `averageRating`(소수 첫째 자리 반올림, 없음 → `0.0`)·`reviewCount` |
| `sameCountry` | `ReviewRatingResponse` | 같은 국적(작성 시점 스냅샷) 리뷰 요약 — 같은 형태. 해당 리뷰 없음·국적 미보유 → `0.0`·`0` |
| `blur` | `Boolean` | 비회원·탈퇴 회원 토큰 조회 `true` / 활성 회원 조회 `false` |

**불변식**:

- `blur == true` 이면 수치 3필드는 항상 기본값(0.0·0·0.0) — 실수치는 서버가 채우지 않는다(FR-003).
- 수치 필드는 null 을 갖지 않는다(FR-004) — nullable 은 서비스 내부(`RatingSummary`)까지만.

### FoodDetailResponse (수정)

- 제거: 최상위 `averageRating: Double?`, `reviewCount: Long`, `sameCountryAverageRating: Double?`
- 추가: `review: ReviewSummaryResponse` (항상 존재 — non-null)
- 나머지 필드(name·koreanName·imageRef·description·spiciness·overallRiskStatus·ingredients·bookmarked)는 불변.

## 상태·전이

해당 없음 — 조회 전용 개편. 리뷰 생성·삭제 흐름(`ReviewService.createReview/deleteReview`)은 손대지 않으며, 집계 방식이므로 쓰기 후 다음 조회에 즉시 반영된다(SC-005).

## 조립 흐름

```
FoodController.getFoodDetail(foodId, memberId?, lang)
├─ memberId == null (비회원)
│   └─ ReviewSummaryResponse(0.0, 0, 0.0, blur = true)   ← 집계 쿼리 0회
└─ memberId != null (회원)
    ├─ viewerCountryCode = member.profile.countryCode?.name
    ├─ rating = reviewService.getFoodRatingSummary(foodId, viewerCountryCode)  ← 집계 1~2회(현행)
    └─ ReviewSummaryResponse(rating.averageRating ?: 0.0, rating.reviewCount,
                             rating.sameCountryAverageRating ?: 0.0, blur = false)
```
