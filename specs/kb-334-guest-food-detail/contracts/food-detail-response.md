# Contract: GET /api/foods/{foodId} 응답 (KB-334 이후)

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
    "ingredients": [],
    "bookmarked": false,
    "review": {
      "overall":     { "averageRating": 3.7, "reviewCount": 3 },
      "sameCountry": null
    }
  }
}
```

## 회원 (활성, 국적 보유)

```json
{
  "payload": {
    "overallRiskStatus": "DANGER",
    "ingredients": [ { "name": "Soybean", "iconRef": null, "inclusionPercent": 100, "riskStatus": "DANGER" } ],
    "bookmarked": true,
    "review": {
      "overall":     { "averageRating": 3.7, "reviewCount": 3 },
      "sameCountry": { "averageRating": 4.0, "reviewCount": 1 }
    }
  }
}
```

## 필드 규칙

| 필드 | 비회원 | 회원 |
|------|--------|------|
| `overallRiskStatus` | **항상 null** (비회원 판별 기준) | `SAFE \| CAUTION \| DANGER \| UNKNOWN` — 기존 정책 불변 |
| `bookmarked` | 항상 `false` | 실제 북마크 여부 |
| `ingredients` | 항상 `[]` | 회피 교집합 (기존) |
| `review.overall` | 실제 집계값 (리뷰 없으면 `{0.0, 0}`) | 동일 |
| `review.sameCountry` | **항상 null** | 국적 보유 시 실수치, 국적 없으면 `{0.0, 0}` |
| `review.blur` | **필드 없음** (제거) | **필드 없음** (제거) |

- 회원 응답에서 `sameCountry: null` 은 나오지 않는다 — null 은 비회원 전용 시그널.
- 오류(404 FOOD-*, 400 COMMON-002 등)는 기존과 동일.
