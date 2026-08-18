# Contract: GET /api/foods/{foodId} 응답 (KB-334, 2026-08-18 2차 개정)

무버전 매핑 즉시 변경 — 이 계약이 유일본이다(구 계약 공존 없음). `X-API-Version` 헤더는 기존 규약대로 필수.

응답은 세 층: **`food`**(음식 고유 — 조회자 무관) · **조회자 맥락**(`overallRiskStatus`·`avoidedIngredients`·`bookmarked`) · **리뷰**(`reviewSummary`·`recentReviews`).

## 비회원 (Authorization 없음)

```json
{
  "success": true,
  "payload": {
    "food": {
      "name": "Doenjang Stew",
      "koreanName": "된장찌개",
      "imageRef": "https://cdn.../doenjang.png",
      "description": "A hearty Korean soybean paste stew.",
      "spiciness": 3,
      "ingredients": [
        { "code": "SOY",   "name": "Soybean", "inclusionPercent": 100 },
        { "code": "WHEAT", "name": "Wheat",   "inclusionPercent": 80 },
        { "code": "CLAM",  "name": "Clam",    "inclusionPercent": 50 }
      ]
    },
    "overallRiskStatus": null,
    "avoidedIngredients": null,
    "bookmarked": false,
    "reviewSummary": {
      "overall":     { "averageRating": 3.7, "reviewCount": 3 },
      "sameCountry": null
    },
    "recentReviews": [
      {
        "reviewId": 42, "rating": 4, "content": "정말 맛있어요",
        "imageUrls": ["https://cdn.../r1.png"], "createdAt": 1755142800000,
        "author": { "memberId": 7, "nickname": "민수", "profileImageUrl": "https://cdn.../profiles/7.png", "countryCode": "KR", "tier": "GOURMET", "level": 2, "score": 15 },
        "authorWithdrawn": false,
        "likeCount": 3, "likedByMe": false, "place": null
      }
    ]
  }
}
```

## 회원 (활성, 국적 KR, SOY·CLAM 회피, 북마크함)

```json
{
  "payload": {
    "food": { "...": "비회원과 동일" },
    "overallRiskStatus": "DANGER",
    "avoidedIngredients": [
      { "code": "SOY",  "riskStatus": "DANGER" },
      { "code": "CLAM", "riskStatus": "CAUTION" }
    ],
    "bookmarked": true,
    "reviewSummary": {
      "overall":     { "averageRating": 3.7, "reviewCount": 3 },
      "sameCountry": { "averageRating": 4.0, "reviewCount": 1 }
    },
    "recentReviews": [ { "...": "위와 동일 형태, likedByMe 는 실제값·차단/신고 리뷰 제외" } ]
  }
}
```

## 필드 규칙

| 필드 | 비회원 | 회원 |
|------|--------|------|
| `food` | 음식 고유 정보(재료 전체 포함, 확률 내림차순) — **공통** | 동일 |
| `overallRiskStatus` | **항상 null** (비회원 판별 기준) | `SAFE \| CAUTION \| DANGER \| UNKNOWN` — 기존 정책 불변 |
| `avoidedIngredients` | **항상 null** | 회피 교집합 `{code, riskStatus}` 확률 내림차순 (겹침 없으면 `[]`) |
| `bookmarked` | 항상 `false` | 실제 북마크 여부 |
| `reviewSummary.overall` | 실제 집계값 (리뷰 없으면 `{0.0, 0}`) | 동일 |
| `reviewSummary.sameCountry` | **항상 null** | 국적 보유 시 실수치, 국적 없으면 `{0.0, 0}` |
| `recentReviews` | 최신순 최대 5개, `likedByMe` 전부 false | 동일 + `likedByMe` 실제값, 차단/신고 리뷰 제외 |
| `review` / `review.blur` | **필드 없음** | **필드 없음** |

## recentReviews 항목 (리뷰 목록 API 와 공통 계약)

- `food` 필드는 **생략**된다 — 이 음식에 대한 리뷰라 중복(`ReviewResponse.food` 는 비채움 맥락에서 키 자체 생략, 리뷰 목록에서는 기존대로 채워짐).
- `createdAt` 은 **epoch millis(long)** — 리뷰 목록·작성·수정 응답 공통 전환.
- `author.profileImageUrl` 포함(미설정이면 null) — 공통 추가.
- 회원 응답에서 `avoidedIngredients: null`·`sameCountry: null` 은 나오지 않는다 — null 은 비회원 전용 시그널.
- 오류(404 FOOD-*, 400 COMMON-002 등)는 기존과 동일.
