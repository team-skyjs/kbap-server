# Contract: 음식 상세 조회 응답 — 리뷰 섹션

**Endpoint**: `GET /api/v1/foods/{foodId}?lang={lang}` (경로·파라미터 변경 없음)

**인증**: 선택(`@AuthMemberIdOrNull`) — 회원/비회원 모두 호출 가능. 봉투는 `BaseResponse<FoodDetailResponse>` 유지.

## Before (현행)

```json
{
  "success": true,
  "payload": {
    "name": "Bulgogi",
    "koreanName": "불고기",
    "imageRef": "bulgogi.png",
    "description": "...",
    "spiciness": 1,
    "overallRiskStatus": "SAFE",
    "ingredients": [ ... ],
    "bookmarked": false,
    "averageRating": 3.7,
    "reviewCount": 3,
    "sameCountryAverageRating": 4.5
  }
}
```

## After (개편)

### 회원 조회 — 리뷰 3건, 같은 국적 리뷰 있음

```json
{
  "success": true,
  "payload": {
    "name": "Bulgogi",
    "koreanName": "불고기",
    "imageRef": "bulgogi.png",
    "description": "...",
    "spiciness": 1,
    "overallRiskStatus": "SAFE",
    "ingredients": [ ... ],
    "bookmarked": false,
    "review": {
      "overall": { "averageRating": 3.7, "reviewCount": 3 },
      "sameCountry": { "averageRating": 4.5, "reviewCount": 2 },
      "blur": false
    }
  }
}
```

### 회원 조회 — 리뷰 0건 (0.0 기본값 계약)

```json
"review": { "overall": { "averageRating": 0.0, "reviewCount": 0 }, "sameCountry": { "averageRating": 0.0, "reviewCount": 0 }, "blur": false }
```

### 회원 조회 — 전체 리뷰는 있으나 같은 국적 리뷰 없음(또는 국적 미보유 회원)

```json
"review": { "overall": { "averageRating": 3.7, "reviewCount": 3 }, "sameCountry": { "averageRating": 0.0, "reviewCount": 0 }, "blur": false }
```

### 비회원 조회 — 리뷰 유무 무관 (서버측 가림)

```json
"review": { "overall": { "averageRating": 0.0, "reviewCount": 0 }, "sameCountry": { "averageRating": 0.0, "reviewCount": 0 }, "blur": true }
```

## 계약 규칙

1. 최상위 `averageRating`·`reviewCount`·`sameCountryAverageRating` 필드는 **삭제**된다(중복 제공 없음 — FR-001). FE 실연결(KB-73) 진행 중이라 하위호환 파괴 아님(spec Assumptions).
2. `review` 는 항상 존재하고, 수치 필드는 항상 숫자다(null 없음 — FR-004).
3. `blur=true` 응답의 수치는 항상 기본값 — 클라이언트는 `blur` 로 "가려짐" 상태를, `blur=false && overall.reviewCount==0` 으로 "리뷰 없음" 상태를 구분해 렌더링한다(FR-002).
4. 클라이언트는 필드 존재 여부가 아니라 값으로 분기한다 — `sameCountry.averageRating == 0.0` 은 "표시할 같은 국적 평점 없음"으로 취급(0.0 실평균 리뷰는 별점 1~5 제약상 존재 불가).
5. Swagger 스키마(`@Schema`)는 위 규칙(기본값·blur 의미 포함)을 필드 설명에 반영한다(FR-007).
