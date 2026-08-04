# API Contract: 커뮤니티 댓글/대댓글

공통 규약: 모든 응답은 `ResponseEntity<BaseResponse<T>>` 봉투, 경로는 `ApiPaths.V1` 기반. Spring 애너테이션은 기존 `CommunityController`, swagger 문서는 기존 `CommunityApi` 인터페이스에 추가한다(댓글 전용 컨트롤러 없음).

인증: 4개 엔드포인트 전부 **회원 전용** — `@AuthMemberId`(게스트/무토큰은 기존 인증 경로에서 401). `@AuthMemberIdOrNull` 사용처 없음.

## 1. 댓글 작성 — `POST /api/v1/community/posts/{postId}/comments`

Request body (`CommentCreateRequest`):

```json
{
  "content": "저도 먹어봤는데 최고예요 @먹보",   // 필수, 1~2000자. @멘션은 그냥 텍스트
  "parentCommentId": 10                          // 옵션. null=최상위 댓글, 값=답글 대상 댓글 id
}
```

- `parentCommentId` 가 **대댓글** id 면 서버가 그 최상위 부모로 정규화해 저장한다(1depth 보장, R3). FE 는 "답글 단 대상" 을 그대로 보내면 된다.
- 검증: `content` `@NotBlank`+`@Size(max=2000)` (jakarta validation → 기존 전역 핸들러).

Responses:

| 상황 | HTTP | code |
|------|------|------|
| 성공 | 200 | — (payload: `CommentResponse`) |
| 글 없음/삭제됨(탈퇴 작성자 글 포함) | 400 | `COMMUNITY-001` |
| `parentCommentId` 없음/삭제됨/다른 글 소속 | 400 | `COMMUNITY-006` |
| 본문 공백·2000자 초과 | 400 | 전역 validation 오류 |
| 게스트 | 401 | 기존 인증 오류 |

`CommentResponse`:

```json
{
  "commentId": 55,
  "postId": 42,
  "parentCommentId": 10,      // 최상위 댓글이면 null (정규화 결과 값)
  "content": "저도 먹어봤는데 최고예요 @먹보",
  "createdAt": "2026-08-04T22:00:00",
  "editedAt": null
}
```

## 2. 댓글 수정 — `PUT /api/v1/community/comments/{commentId}`

Request body (`CommentUpdateRequest`): `{ "content": "..." }` — content 만 수정 가능(부모 이동 불가).

| 상황 | HTTP | code |
|------|------|------|
| 성공 | 200 | — (payload: `CommentResponse`, `editedAt` 갱신) |
| 댓글 없음/이미 삭제 | 400 | `COMMUNITY-006` |
| 타인 댓글 | 403 | `COMMUNITY-007` |

## 3. 댓글 삭제 — `DELETE /api/v1/community/comments/{commentId}`

- 최상위 댓글: 본체 + 하위 대댓글 전부 소프트 삭제(통삭제).
- 대댓글: 해당 건만 소프트 삭제.

| 상황 | HTTP | code |
|------|------|------|
| 성공 | 200 | — (payload: `Unit`) |
| 댓글 없음/이미 삭제 | 400 | `COMMUNITY-006` |
| 타인 댓글 | 403 | `COMMUNITY-007` |

## 4. 댓글 목록 — `GET /api/v1/community/posts/{postId}/comments?cursor={cursor}`

- 회원 전용(피드와 달리 게스트 첫 페이지도 불가 — FE 는 피드/상세의 `commentCount` 만 노출하고 블러 처리).
- 최상위 댓글 등록순(오래된 순) 커서 페이징 — 페이지 크기 20, `cursor` 는 직전 페이지 `nextCursor`(첫 페이지는 생략). 형식 오류는 기존 `CursorParser` 규칙(`INVALID_CURSOR`).
- 각 항목의 `replies` 는 해당 최상위 댓글의 **대댓글 전량**(등록순) — 대댓글 별도 페이징 없음.
- 삭제분(통삭제 포함)은 응답에서 제외. 탈퇴 작성자는 익명화.

Response payload (`Page<CommentItemResponse>`):

```json
{
  "items": [
    {
      "commentId": 10,
      "author": { "memberId": 7, "nickname": "먹보", "profileImageUrl": "https://…" },
      "content": "정말 맛있죠",
      "createdAt": "2026-08-04T21:00:00",
      "replies": [
        {
          "commentId": 55,
          "author": { "memberId": null, "nickname": "탈퇴한 사용자", "profileImageUrl": null },
          "content": "저도 먹어봤는데 최고예요 @먹보",
          "createdAt": "2026-08-04T22:00:00"
        }
      ]
    }
  ],
  "hasNext": false,
  "nextCursor": null
}
```

- `author.memberId = null` ⇔ 탈퇴 작성자(익명화). 활성 회원은 피드 작성자 표기와 동일 규칙(닉네임 미설정 null·프로필 이미지 URL resolve).
- `editedAt` 은 목록에 노출하지 않는다(수정 표시 없음 — 게시글과 동일 정책).

| 상황 | HTTP | code |
|------|------|------|
| 성공 | 200 | — |
| 글 없음/삭제됨/탈퇴 작성자 글 | 400 | `COMMUNITY-001` |
| 커서 형식 오류 | 400 | `INVALID_CURSOR` 기존 코드 |
| 게스트 | 401 | 기존 인증 오류 |

## 5. 기존 계약 변경 — 피드/상세 `commentCount` 실값화

`GET /api/v1/community/posts`·`GET /api/v1/community/posts/{postId}` 의 `PostingItemResponse.commentCount` 가 0 고정에서 **노출 가능한 댓글+대댓글 수**(삭제분·통삭제분 제외)로 바뀐다. 필드 형태 불변 — FE 파괴적 변경 없음. swagger 설명 문구만 갱신.

## 신규 ErrorCode

| enum | code | HTTP | message |
|------|------|------|---------|
| `COMMUNITY_COMMENT_NOT_FOUND` | `COMMUNITY-006` | 400 | 해당 댓글을 찾을 수 없습니다 |
| `COMMUNITY_COMMENT_FORBIDDEN` | `COMMUNITY-007` | 403 | 본인이 작성한 댓글만 수정·삭제할 수 있습니다 |
