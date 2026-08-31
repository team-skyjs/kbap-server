# Data Model: 리뷰 평가 항목 추가 — 제공 속도·직원 친절도

## Review (`food_review`) — 기존 엔티티 확장

| 필드 (Kotlin) | 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|---|
| `servingSpeedRating` | `serving_speed_rating` | `TINYINT` | `NOT NULL DEFAULT 0`, 0~5 (엔티티 require) | 제공 속도 평가 — 0=평가 안 함, 1~5=평가값 |
| `staffKindnessRating` | `staff_kindness_rating` | `TINYINT` | `NOT NULL DEFAULT 0`, 0~5 (엔티티 require) | 직원 친절도 평가 — 0=평가 안 함, 1~5=평가값 |

- 두 필드는 `var`, 기본값 0 — kotlin-jpa no-arg 자동 생성 유지.
- `requireValid` 에 `EXTRA_RATING_RANGE = 0..5` 검증 추가. `update(rating, content, imageRefs, place)` 시그니처에 `servingSpeedRating`·`staffKindnessRating` 추가(전체 교체 계약).
- 기존 필드·연관·소프트삭제·`@Version` 불변. 별도 상태 전이 없음.

## Flyway

- `V<생성시각 timestamp>__food_review_extra_ratings.sql`:

```sql
ALTER TABLE food_review
    ADD COLUMN serving_speed_rating TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN staff_kindness_rating TINYINT NOT NULL DEFAULT 0;
```

- 기존 행은 DEFAULT 0(평가 안 함)으로 충족 — 백필 불필요, 다른 마이그레이션과 순서 독립.
- additive + DEFAULT 라 구 리비전 코드(컬럼 모름)와 공존 안전(블루/그린).

## 관련 무변경 확인

- 집계(`aggregateRating`·`aggregateRatingsByFoodIds`)·목록 쿼리(`findReviewPage`·`findMemberReviewPage`)·노출 규칙(차단/신고/삭제 음식)·`ReviewLike`·`MemberRankingEvent` — 전부 무변경.
