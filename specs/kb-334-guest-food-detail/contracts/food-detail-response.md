# Contract: GET /api/foods/{foodId} 응답 (KB-334, 2026-08-18 개정)

무버전 매핑 즉시 변경 — 이 계약이 유일본이다(구 계약 공존 없음). `X-API-Version` 헤더는 기존 규약대로 필수.

## 비회원 (Authorization 없음)

```json
{
  "success": true,
  "payload": {
    "name": "Doenjang Stew",
    "koreanName": "된장찌개",
    "imageRef": "https://cdn.../doenjang.png",
    "description": "A hearty Korean soybean paste stew.",
    "spiciness": 3,
    "overallRiskStatus": null,
    "ingredients": [
      { "code": "SOY",  "name": "Soybean", "inclusionPercent": 100 },
      { "code": "CLAM", "name": "Clam",    "inclusionPercent": 50 }
    ],
    "avoidedIngredients": null,
    "bookmarked": false,
    "reviewSummary": {
      "overall":     { "averageRating": 3.7, "reviewCount": 3 },
      "sameCountry": null
    },
    "recentReviews": [
      {
        "reviewId": 42, "rating": 4, "content": "정말 맛있어요",
        "imageUrls": ["https://cdn.../r1.png"], "createdAt": "2026-08-14T12:00:00",
        "author": { "nickname": "민수", "countryCode": "KR" }, "authorWithdrawn": false,
        "likeCount": 3, "likedByMe": false, "food": null, "place": null
      }
    ]
  }
}
```

## 회원 (활성, 국적 KR, SOY·CLAM 회피, 북마크함)

```json
{
  "payload": {
    "overallRiskStatus": "DANGER",
    "ingredients": [
      { "code": "SOY",  "name": "Soybean", "inclusionPercent": 100 },
      { "code": "CLAM", "name": "Clam",    "inclusionPercent": 50 }
    ],
    "avoidedIngredients": [
      { "code": "SOY",  "riskStatus": "DANGER" },
      { "code": "CLAM", "riskStatus": "CAUTION" }
    ],
    "bookmarked": true,
    "reviewSummary": {
      "overall":     { "averageRating": 3.7, "reviewCount": 3 },
      "sameCountry": { "averageRating": 4.0, "reviewCount": 1 }
    },
    "recentReviews": [ { "...": "ReviewResponse 동일, likedByMe 는 실제값" } ]
  }
}
```

## 필드 규칙

| 필드 | 비회원 | 회원 |
|------|--------|------|
| `overallRiskStatus` | **항상 null** (비회원 판별 기준) | `SAFE \| CAUTION \| DANGER \| UNKNOWN` — 기존 정책 불변 |
| `ingredients` | 재료 전체 `{code, name, inclusionPercent}` 확률 내림차순 — **공통** | 동일 |
| `avoidedIngredients` | **항상 null** | 회피 교집합 `{code, riskStatus}` 확률 내림차순 (겹침 없으면 `[]`) |
| `bookmarked` | 항상 `false` | 실제 북마크 여부 |
| `reviewSummary.overall` | 실제 집계값 (리뷰 없으면 `{0.0, 0}`) | 동일 |
| `reviewSummary.sameCountry` | **항상 null** | 국적 보유 시 실수치, 국적 없으면 `{0.0, 0}` |
| `recentReviews` | 최신순 최대 5개, `ReviewResponse` 형태, `likedByMe` 전부 false | 동일 + `likedByMe` 실제값, 차단/신고 리뷰 제외 |
| `review` / `review.blur` | **필드 없음** | **필드 없음** |

- 회원 응답에서 `avoidedIngredients: null`·`sameCountry: null` 은 나오지 않는다 — null 은 비회원 전용 시그널.
- `recentReviews[].food` 는 상세 맥락에서 항상 null(자기 자신), `place` 는 리뷰 목록과 동일 규칙.
- 오류(404 FOOD-*, 400 COMMON-002 등)는 기존과 동일.
