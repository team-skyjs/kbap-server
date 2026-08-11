# API Contract: 리뷰 목록 — 전체 피드 및 음식 정보 보강

공통: 인증 필수(`@AuthMemberId`), 응답 봉투 `ResponseEntity<BaseResponse<T>>`, 경로 베이스 `ApiPaths.V1`.

## 1. GET /api/v1/reviews/feed (신규)

전체 리뷰 피드 — 음식 무관 최신순, 무한 스크롤.

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `lang` | String | ✅ | 표시 언어. 미지원 코드 → `en` 폴백, 번역 부재 → `ko` 폴백. 빈 값 → 400 |
| `cursor` | String | — | 이전 응답의 `nextCursor`. 생략 시 첫 페이지 |

- 정렬: 최신 작성순(id desc). 페이지 크기 20.
- 제외: 조회 회원이 차단한 회원의 리뷰, 조회 회원이 신고한 리뷰, 소프트 삭제된 음식의 리뷰.

## 2. GET /api/v1/reviews (변경 — `lang` 추가)

기존 `foodId`(필수)·`countryCode`·`cursor` 유지, **`lang` 필수 추가**.

## 3. GET /api/v1/reviews/me (변경 — `lang` 추가)

기존 `cursor` 유지, **`lang` 필수 추가**.

## 응답 페이로드 (세 경로 공통): `Page<ReviewResponse>`

```json
{
  "success": true,
  "payload": {
    "items": [
      {
        "reviewId": 42,
        "rating": 4,
        "content": "정말 맛있어요",
        "imageUrls": ["https://cdn.example.com/review/1.jpg"],
        "createdAt": "2026-08-11T12:00:00",
        "author": { "memberId": 7, "nickname": "김밥러버", "countryCode": "VN", "tier": "GOURMET", "level": 2, "score": 15 },
        "likeCount": 3,
        "likedByMe": true,
        "food": {
          "foodId": 1,
          "name": "Kimbap",
          "imageUrl": "https://cdn.example.com/food/1.jpg"
        }
      }
    ],
    "hasNext": true,
    "nextCursor": 42
  }
}
```

- `food`: **신규 중첩 객체.** 목록 조회에서 채워지며, 음식이 삭제된 리뷰(음식별·내 리뷰 경로)는 `null`. 리뷰 생성·수정 응답(`POST /reviews`·`PATCH /reviews/{id}`)에서도 `null`.
- **top-level `foodId`·`memberId` 는 제거** — `food.foodId`·`author.memberId` 와 중복이라 뺐다(2026-08-11 결정). 음식 식별은 `food.foodId`, 작성자 식별은 `author.memberId` 를 쓴다. 생성·수정 응답에는 둘 다 없다(클라이언트가 요청 맥락으로 보유).
- 그 외 기존 필드는 이름·타입·의미 불변.
- `author` 객체 구조는 기존 `ReviewAuthorResponse` 그대로.
- `food.imageUrl` 은 `kbap.storage.public-base-url` 기준으로 해석된 절대 URL(리뷰 `imageUrls` 와 동일 규칙).

## 오류

| 상황 | HTTP | code |
|---|---|---|
| `lang` 누락·빈 값 | 400 | 요청 검증 실패(기존 공통 핸들러) |
| 미인증 | 401 | 기존 인증 규칙 |
| (음식별 경로) foodId 음식 없음/미준비 | 기존 동작 유지 | `FOOD-*` 기존 코드 |
