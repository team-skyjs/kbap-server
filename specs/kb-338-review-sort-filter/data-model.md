# Data Model: 리뷰 목록 정렬·필터 (KB-338)

**영속 모델 변경 없음** — 엔티티·스키마·인덱스 무변경. 신규 타입은 전부 api 조립 계층의 값 타입이다.

## 신규 타입 (com.kbap.api.review)

### ReviewSort (enum)

| 값 | 의미 | order by | 커서 지표 |
|---|---|---|---|
| `LATEST` (기본) | 최신순 | id desc | 없음(id 만) |
| `RATING_DESC` | 평점 높은 순 | rating desc, id desc | rating |
| `RATING_ASC` | 평점 낮은 순 | rating asc, id desc | rating |
| `FOOD_REVIEW_COUNT_DESC` | 음식 리뷰 수 많은 순 | foodReviewCount desc, id desc | foodReviewCount |
| `HELPFUL_DESC` | 좋아요 수 내림차순 | likeCount desc, id desc | likeCount |

- 요청 파라미터 `sort` 문자열 → enum 매핑, 미지원 값 400(COMMON-002), 생략 = LATEST.

### ReviewListCursor (커서 코덱)

| 정렬 | 형식 | 예 | 파싱 실패 |
|---|---|---|---|
| LATEST | `"{id}"` | `"42"` | FOOD-002 |
| 지표 정렬 | `"{metric}_{id}"` | `"4_42"` | FOOD-002 (숫자 단일·형식 불일치 포함) |

### ReviewListRequest (요청 DTO 확장)

| 필드 | 타입 | 제약 | 비고 |
|---|---|---|---|
| `sort` | String? | 5종 허용값 | 생략 = LATEST |
| `minRating` | Int? | 1~5 | 생략 = 하한 없음 |
| `maxRating` | Int? | 1~5, `minRating` 이상 | 생략 = 상한 없음 |
| 기존 `lang`·`cursor`·`countryCode` | — | 불변 | |

### ReviewListPage (응답 봉투 — 리뷰 목록 전용)

| 필드 | 타입 | 비고 |
|---|---|---|
| `items` | List\<ReviewResponse\> | 항목 형태 불변 |
| `hasNext` | Boolean | 불변 |
| `nextCursor` | **String?** | 복합 커서 수용 — 공용 `Page`(Long)와 분리. **number→string 계약 변경점** |

## 리포지토리 계약 (com.kbap.common.domain.review)

`ReviewRepositoryCustom.findReviewPage(foodId?, countryCode?, minRating?, maxRating?, sortKey, metricCursor?, idCursor?, excludedMemberIds, excludedReviewIds, limit)` — 정렬 키는 영속 계층의 열거(또는 파라미터 조합)로 받고, api 의 `ReviewSort` 와 1:1 매핑. 기존 고정 `@Query findReviewPage` 는 대체·삭제.
