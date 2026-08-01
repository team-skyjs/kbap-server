# API Contract: 리뷰 좋아요 (kb-271)

모든 응답은 `ResponseEntity<BaseResponse<T>>` 봉투 규약을 따른다. 경로 베이스는 `ApiPaths.V1`(`/api/v1`). 인증 필수(`@AuthMemberId`) — 보호 경로다.

## 1. 좋아요 등록/취소 (상태 지정)

```
POST /api/v1/reviews/{reviewId}/like?liked={true|false}
Authorization: Bearer <access token>
```

| 항목 | 값 |
|------|-----|
| Path | `reviewId: Long` — 대상 리뷰 id |
| Query | `liked: Boolean` (필수) — `true` 등록, `false` 취소 |
| Body | 없음 |
| 성공 | `200 OK` — `{"success": true, "payload": null}` (`BaseResponse<Unit>`) |
| 멱등 | 같은 상태로 재요청해도 200 성공, 상태 변화 없음. `liked=false` 는 좋아요가 없어도(미등록·이미 취소·리뷰 없음) 200 no-op |
| 실패 | `400` — `liked` 파라미터 누락 |
| 실패 | `400` `REVIEW-001` — `liked=true` 인데 리뷰가 없거나 삭제됨 |
| 실패 | `401` — 미인증 (기존 인증 규약) |

토글이 아니라 **상태 지정**이다 — 클라이언트가 목표 상태를 보내므로 서버 상태와 어긋나도 의도가 반전되지 않는다.

## 3. 기존 목록/단건 응답 확장 — `ReviewResponse`

`GET /api/v1/reviews`(음식 리뷰 목록)·`GET /api/v1/reviews/me`(내 리뷰 목록)·리뷰 생성/수정 응답의 각 리뷰 항목에 두 필드가 추가된다.

```jsonc
{
  "reviewId": 42,
  "foodId": 1,
  "memberId": 7,
  "rating": 4,
  "content": "정말 맛있어요",
  "imageUrls": [],
  "createdAt": "2026-08-01T12:00:00",
  "author": { "...": "기존과 동일" },
  "likeCount": 3,       // 신규 — 이 리뷰의 활성 좋아요 수 (없으면 0)
  "likedByMe": true     // 신규 — 조회(인증) 회원이 좋아요를 눌렀는지
}
```

- 추가 전용(additive) 변경 — 기존 필드·의미 불변, 기존 클라이언트 비파괴.
- 리뷰 생성 직후 응답은 `likeCount=0`·`likedByMe=false`.
- 음식 상세(`/api/v1/foods/detail`)의 별점 요약 섹션은 무변경.

## Swagger

`ReviewApi` 인터페이스에 두 오퍼레이션 문서(`@Operation`·`@ApiResponses`·`@SecurityRequirement`)를 추가한다. Spring 애너테이션(매핑·`@PathVariable`·`@AuthMemberId`)은 구현 컨트롤러에만 둔다(파라미터 애너테이션 위치 규약).
