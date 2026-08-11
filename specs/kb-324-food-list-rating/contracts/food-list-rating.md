# API Contract: 음식 목록 아이템 평점·리뷰 수

## 대상 (응답 스키마 `FoodSummaryResponse` 공유 — 전부 동일 적용)

- `GET /api/v1/foods` (목록) · `GET /api/v1/foods/search` (검색)
- `GET /api/v1/home` — `popularFoods`·`recentScans` 섹션
- `GET /api/v1/bookmarks` (북마크 목록)
- 어드민 음식 그리드

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

- 값은 음식 상세 `FoodDetailResponse.review.overall` 과 **항상 일치**한다(같은 집계: 소프트 삭제 리뷰 제외, 탈퇴·차단 회원 리뷰 포함, 동일 반올림).
- 상세는 0건을 `averageRating: 0.0` 으로 내리는 기존 계약 유지 — **목록만 null**(신규 필드라 하위 호환 부담 없음). FE 는 목록에서 null → "—" 처리.
- 국적별(sameCountry) 집계는 목록에 내리지 않는다 — 상세 전용.
