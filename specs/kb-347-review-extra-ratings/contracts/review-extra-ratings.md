# API Contract: 리뷰 세부 평가 2종 (additive, 무버전)

모든 변경은 **기존 엔드포인트에 필드 추가**다 — 경로·버전·기존 필드 불변.

## 요청 — POST /api/reviews · PATCH /api/reviews/{reviewId}

```jsonc
{
  "foodId": 1,
  "rating": 4,
  "content": "정말 맛있어요",
  "servingSpeed": 5,      // 추가 — 0~5 정수, 0=평가 안 함, 누락 시 0
  "staffKindness": 0      // 추가 — 0~5 정수, 0=평가 안 함, 누락 시 0
}
```

- 검증: `@Min(0)`/`@Max(5)` — 범위 밖은 400(COMMON-002 유효성 실패 규약 그대로).
- PATCH 는 전체 교체 계약 — 누락 = 0 저장(기존 content·imagePaths 누락 시 제거와 일관).

## 응답 — ReviewResponse (전 경로 공통)

적용 경로: `GET /api/reviews`(목록) · `GET /api/reviews/me` · `GET /api/foods/{foodId}` 의 `recentReviews` · 작성/수정 응답.

```jsonc
{
  "reviewId": 42,
  "rating": 4,
  "servingSpeed": 5,      // 추가 — 항상 0~5 숫자(null 없음), 0=평가 안 함
  "staffKindness": 0,     // 추가 — 항상 0~5 숫자(null 없음), 0=평가 안 함
  "...": "기존 필드 불변"
}
```

- 기존 리뷰(도입 전 작성)는 두 필드 모두 0.
- swagger: 두 필드 `@Schema(description, example)` — 0 의 의미(평가 안 함)를 description 에 명시한다.

## 무변경

- `X-API-Version` 정책(리뷰 1.0+)·JWT 보호 경로·비회원 공개(GET)·`reviewSummary`/`averageRating` 집계.
