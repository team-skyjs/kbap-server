# API Contract: 음식 목록 아이템 평점·리뷰 수

## 대상 (응답 스키마 `FoodSummaryResponse` 공유 — 전부 동일 적용)

- `GET /api/v1/foods` (목록) · `GET /api/v1/foods/search` (검색)
- `GET /api/v1/home` — `popularFoods`·`recentScans` 섹션
- `GET /api/v1/bookmarks` (북마크 목록)

(어드민 그리드는 별도 `AdminFoodSummaryView` 스키마라 대상 아님)

요청 파라미터 변경 없음 — **응답 필드 2개 추가만**(기존 필드 불변).

## 아이템 예시

```json
{
  "foodId": 1,
  "name": "Kimbap",
  "koreanName": "김밥",
  "imageRef": "https://cdn.kbap.io/images/food/1.jpg",
  "spiciness": 0,
  "overallRiskStatus": "SAFE",
  "bookmarked": false,
  "averageRating": 4.3,
  "reviewCount": 3
}
```

| 필드 | 타입 | 의미 |
|------|------|------|
| `averageRating` | Double, nullable | 전체(overall) 평균 평점, 소수 1자리 반올림. **리뷰 0건이면 `null`**("—" 표시) |
| `reviewCount` | Long | 리뷰 수. 0건이면 `0` |

## 규칙

- **리뷰가 1건 이상일 때** 값은 음식 상세 `FoodDetailResponse.review.overall` 과 일치한다(같은 집계: 소프트 삭제 리뷰 제외, 탈퇴·차단 회원 리뷰 포함, 동일 소수 1자리 반올림).
- **리뷰 0건의 표현은 경로별로 다르다**: 목록은 `averageRating: null`(FE "—" 처리, 신규 필드라 하위 호환 부담 없음), 상세는 기존 계약대로 `0.0`. 값 일치 규약은 0건 표현에는 적용되지 않는다.
- 국적별(sameCountry) 집계는 목록에 내리지 않는다 — 상세 전용.
- **비회원에게도 목록·홈·검색의 평점·리뷰 수는 공개한다**(2026-08-11 결정 — Codex 리뷰 지적 후 확정). 상세의 리뷰 섹션 blur(비회원 가림)는 가입 유도 정책으로 존치하며, 두 정책의 비대칭은 의도된 것이다.
