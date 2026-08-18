# Contract: GET /api/reviews 정렬·필터 (KB-338 이후)

기존 계약(항목 형태·필터·비회원 접근 — KB-334/348)은 불변. 추가·변경분만 기술한다.

## 요청 파라미터 (추가분)

| 파라미터 | 필수 | 허용값 | 기본 | 의미 |
|---|---|---|---|---|
| `sort` | X | `latest` \| `rating_high` \| `rating_low` \| `food_review_count` \| `helpful` (소문자 정확 일치) | `latest` | 정렬 — 값 하나가 UI 정렬 옵션 하나와 1:1. 허용값 밖 → 400 COMMON-002 |
| `minRating` | X | 1~5 정수 | 없음 | 별점 하한(이상) |
| `maxRating` | X | 1~5 정수, `minRating` 이상 | 없음 | 별점 상한(이하). `min>max`·범위 밖 → 400 COMMON-002 |

- 기존 `foodId`·`countryCode`·`lang`·`cursor` 와 전부 AND 조합.
- 예: 3점만 = `minRating=3&maxRating=3`. 1~3점 = `minRating=1&maxRating=3` (또는 `maxRating=3` 만).

## 정렬 규칙

- 모든 정렬의 동점은 **최신 리뷰 우선**(id 내림차순 2차 키) — 순서 결정적.
- `FOOD_REVIEW_COUNT_DESC` 는 리뷰가 속한 음식의 활성 리뷰 총수 기준 — `foodId` 지정 조회에서는 전 항목 동점이라 최신순과 동일 결과.
- `HELPFUL_DESC` 는 조회 시점 활성 좋아요 수 기준(등록·취소 반영).

## 커서 규칙 (변경점)

- **응답 `nextCursor` 가 number → string 으로 바뀐다** (리뷰 목록만 — `/reviews/me`·다른 목록 API 는 불변). 클라이언트는 불투명 문자열로 취급해 그대로 되돌려준다.
- `sort` 생략(LATEST) 시 커서 값은 기존과 동일한 숫자 문자열(`"42"`) — 구 클라이언트 동작 불변.
- 지표 정렬 커서는 (지표값, id) 를 base64url(패딩 없음)로 인코딩한 불투명 토큰이다(예: "MTdfMjA0"). 커서는 발급된 정렬 기준에 종속: 디코딩·형식 검증 실패는 400 FOOD-002, 형식이 같은 다른 지표 정렬에 재사용하면 파싱은 되나 순서 보장 없음.
- 페이징 중 지표 값(좋아요 수 등)이 변하면 항목이 페이지 간 이동할 수 있다 — 중복·누락 없음만 보장(keyset 특성).

## 응답 예 (sort=helpful, 2페이지째 요청)

```
GET /api/reviews?lang=en&sort=helpful&cursor=MTdfMjA0
```

```json
{
  "success": true,
  "payload": {
    "items": [ { "reviewId": 203, "likeCount": 17, "...": "기존 항목 형태 그대로" } ],
    "hasNext": true,
    "nextCursor": "MTJfMTgw"
  }
}
```
