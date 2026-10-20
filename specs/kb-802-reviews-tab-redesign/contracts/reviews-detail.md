# Contract: KB-802 BE (cto 계획 C-1·C-2 사본)

응답 봉투는 기존 `BaseResponse` = `{ success, payload, message, code }`. 계획 예시의 `data` 는 `payload` 다.

| ID | 메서드 경로 | X-API-Version | 요청 | 응답 payload | 에러(code·HTTP) |
|----|------------|---------------|------|---------------|-----------------|
| C-1 | `GET /api/reviews/{reviewId}?lang={lang}` | `1.0` 필수 (1.0+, 누락 = COMMON-002) | `reviewId` Long, `lang` 필수, `Authorization` 선택 | `ReviewResponse` (목록 항목과 같은 필드, `food` 항상 채움 — 본인 리뷰의 삭제된 음식만 생략) | `REVIEW-001`·400 — 없음·삭제·차단 작성자·음식 삭제(타인) / `COMMON-002`·400 — lang 누락·회원의 reviewId 비숫자·X-API-Version 누락 / `AUTH-003`·401 — 무효 토큰, 비회원의 reviewId 비숫자(게스트 예외 정규식 `\d+` 밖) |
| C-2 | `GET /api/reviews`·`GET /api/reviews/me`·C-1 의 `food` | 변경 없음 | 변경 없음 | `food.koreanName: string \| null` 추가 (lang=ko 또는 이름이 같으면 null) | 변경 없음 |

예시 (lang=en):

```json
{
  "success": true,
  "payload": {
    "reviewId": 42, "rating": 5, "servingSpeed": 4, "staffKindness": 0,
    "content": "Broth was rich.", "language": "en", "imageUrls": [],
    "createdAt": 1760572800000,
    "author": { "memberId": 7, "nickname": "foodie", "profileImageUrl": null, "countryCode": "VN", "tier": "GOURMET", "level": 2, "score": 15 },
    "authorWithdrawn": false, "likeCount": 34, "likedByMe": true,
    "food": { "foodId": 1, "name": "Seolleongtang", "koreanName": "설렁탕", "imageUrl": null },
    "place": null
  },
  "message": null,
  "code": null
}
```
