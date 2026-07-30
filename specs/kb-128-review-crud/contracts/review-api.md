# API Contract: 리뷰 CRUD (KB-128)

모든 응답은 `ResponseEntity<BaseResponse<T>>` 봉투. 인증은 deny-by-default — 아래 전 경로 Bearer 필수(음식 상세만 `@AuthMemberIdOrNull`). 경로 베이스는 `ApiPaths.V1`.

## 1. POST /api/v1/reviews — 작성 (PR2)

Request:
```json
{ "foodId": 1, "rating": 4, "content": "맛있어요", "imagePaths": ["images/review/2026/07/1_uuid.jpg"] }
```
- `foodId` 필수(`@NotNull`) · `rating` 필수 1~5(`@NotNull @Min(1) @Max(5)`) · `content` 옵션 ≤1000(`@Size(max=1000)`) · `imagePaths` 옵션 ≤3(`@Size(max=3)`)

처리: food 존재 확인(`FOOD-001`) → imagePaths 전 건 소유 검증(`findByPathIn`, 실패 시 `REVIEW-003`) → 국적 스냅샷(`MemberService.getMember(memberId).profile.countryCode`) → 저장 → 랭킹 증가(첫 리뷰면 unique+1)

Response 200: `payload = ReviewResponse` (아래 공통 형태)

## 2. PATCH /api/v1/reviews/{reviewId} — 수정 (PR2)

Request: `{ "rating": 5, "content": null, "imagePaths": [] }` — rating 필수, content/imagePaths 는 보낸 값으로 전량 교체(비우기 허용)

처리: 조회(`REVIEW-001`) → `isOwnedBy` 실패 시 `REVIEW-002`(403) → imagePaths 소유 검증 → `review.update(...)` (dirty checking, 카운트·스냅샷 불변)

## 3. DELETE /api/v1/reviews/{reviewId} — 소프트 삭제 (PR2)

처리: 조회(`REVIEW-001`) → 본인 확인(`REVIEW-002`) → `delete()` → 랭킹 감소(마지막 리뷰면 unique-1)

Response 200: `payload = null`

## 4. GET /api/v1/foods/{foodId}/reviews?cursor=&countryCode= — 음식별 목록 (PR3)

- `cursor` 옵션(`CursorParser` — 비정상 `FOOD-002`) · `countryCode` 옵션(정확 일치 필터, `CountryCode` 미존재 코드는 빈 목록)
- food 존재 확인(`FOOD-001`), 최신순(id desc) 20건 keyset

Response 200:
```json
{ "items": [ReviewResponse...], "hasNext": true, "nextCursor": 42 }
```

## 5. GET /api/v1/members/me/reviews?cursor= — 내 리뷰 목록 (PR3)

인증 회원 본인 리뷰 최신순 20건 keyset. Response 형태는 4와 동일.

## ReviewResponse (공통)

```json
{
  "reviewId": 42, "foodId": 1, "memberId": 7,
  "rating": 4, "content": "맛있어요",
  "imageUrls": ["<CDN>/images/review/2026/07/7_uuid.jpg"],
  "authorCountryCode": "VN",
  "createdAt": "2026-07-29T12:00:00",
  "author": { "memberId": 7, "nickname": "먹보", "countryCode": "VN", "tier": "GOURMET", "level": 2, "score": 15 }
}
```
- `imageUrls` 는 저장된 경로에 CDN 도메인을 붙여 조립(`ImageUrls` 유틸 선례). 사진 없으면 빈 배열.
- `author` = 작성자 프로필(닉네임·랭킹 티어/점수·**현재** 국적). 탈퇴한 회원이면 null. `authorCountryCode` 는 작성 시점 스냅샷(필터·같은 국적 평점 기준), `author.countryCode` 는 현재 프로필 — 국적 변경 시 둘이 다를 수 있다. 목록 조회는 작성자들을 일괄 조회(N+1 없음).

## 6. 음식 상세 응답 확장 — GET /api/v1/foods/{foodId} (PR4)

`FoodDetailResponse` 에 추가:

| 필드 | 타입 | 규칙 |
|------|------|------|
| `averageRating` | `Double?` | 전체 평균, 소수 첫째 자리 반올림. 리뷰 0건 → null |
| `reviewCount` | `Long` | ACTIVE 리뷰 수 |
| `sameCountryAverageRating` | `Double?` | viewer 국적 스냅샷 일치 리뷰 평균(소수 1자리). 비회원·국적 미보유·해당 국적 리뷰 0건 → null |

합성: `FoodController.detail` 에서 `ReviewService.getFoodRatingSummary(foodId, viewerCountryCode)` 호출(bookmark 합성 선례). viewer 국적은 `@AuthMemberIdOrNull` memberId → `MemberService.getMemberOrNull`.

## 오류 요약

| 상황 | HTTP | code |
|------|------|------|
| 미인증 | 401 | AUTH 계열(기존 필터) |
| 타인 리뷰 수정/삭제 | 403 | REVIEW-002 |
| 리뷰 없음/삭제됨 | 400 | REVIEW-001 |
| 음식 없음 | 400 | FOOD-001 |
| 이미지 미소유/미완료 | 400 | REVIEW-003 |
| rating·content·imagePaths 검증 위반 | 400 | Bean Validation 공통 핸들러 |
| cursor 비정상 | 400 | FOOD-002 |

## Swagger

`ReviewApi` 인터페이스(문서 애너테이션 전용 — `@Tag("Review")`·`@Operation`·`@ApiResponses`·`@SecurityRequirement("bearerAuth")`), Spring 애너테이션은 `ReviewController` 에만(파라미터 애너테이션 위치 규약). 음식 상세 확장은 기존 `FoodApi` 문서 갱신.
