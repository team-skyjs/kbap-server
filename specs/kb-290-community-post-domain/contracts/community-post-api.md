# API Contract: 커뮤니티 게시글 작성/수정/삭제 (KB-290)

공통: 모든 응답은 `ResponseEntity<BaseResponse<T>>` 봉투. 인증은 `@AuthMemberId`(회원 전용 — 게스트는 인증 오류). 경로는 `ApiPaths.V1` 기준.

## POST /api/v1/community/posts — 글 작성

Request (JSON):

```json
{
  "content": "본문 (필수, 1~2000자)",
  "imagePaths": ["community/....jpg"],
  "foodIds": [11, 22, 33]
}
```

- `content`: 필수, `@NotBlank`, `@Size(max=2000)`
- `imagePaths`: 선택, `@Size(max=4)` — 리스트 순서 = 표시 순서, 첫 원소 = 커버. 본인이 업로드 완료(검증)한 key 만 허용
- `foodIds`: 선택, `@Size(max=3)` — READY 음식만, 중복 불가

Response 200 — `BaseResponse<PostingResponse>`:

```json
{
  "success": true,
  "payload": {
    "postId": 1,
    "content": "본문",
    "imageUrls": ["https://cdn.../community/....jpg"],
    "foodIds": [11, 22, 33],
    "createdAt": "2026-08-04T13:00:00"
  }
}
```

오류:

| 상황 | HTTP | code |
|---|---|---|
| 비회원/토큰 오류 | 401 | AUTH-* (기존) |
| 본문 누락·2000자 초과·사진 5장·태그 4개 | 400 | 공통 검증 오류(기존 핸들러) |
| 미검증·타인 업로드 이미지 key | 400 | COMMUNITY-003 |
| 미등록·비READY·중복 음식 태그 | 400 | COMMUNITY-004 |

## PUT /api/v1/community/posts/{postId} — 글 수정

Request: 작성과 동일 body(전체 교체 의미론 — 사진·태그 생략/빈 배열이면 제거).

Response 200 — `BaseResponse<PostingResponse>` (수정 반영본, `editedAt` 포함).

오류: 작성 오류 + 아래.

| 상황 | HTTP | code |
|---|---|---|
| 글 없음·삭제됨 | 400 | COMMUNITY-001 |
| 타인 글 | 403 | COMMUNITY-002 |

## DELETE /api/v1/community/posts/{postId} — 글 삭제 (소프트)

Response 200 — `BaseResponse<Unit>` (`payload` 없음).

오류:

| 상황 | HTTP | code |
|---|---|---|
| 글 없음·이미 삭제 | 400 | COMMUNITY-001 |
| 타인 글 | 403 | COMMUNITY-002 |

## 스코프 밖 (후속 태스크)

- 피드 목록·글 상세 조회 — KB-291 (이 계약의 `PostingResponse` 를 기반으로 확장)
- 장소 태그 — 제공 불가로 제외(요청·응답에 필드 자체를 두지 않음)
