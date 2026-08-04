# API Contract: 커뮤니티 피드 조회 + 글 상세 (KB-291)

공통: 응답 봉투 `BaseResponse<T>`, 경로 베이스 `ApiPaths.V1`. 두 API 모두 **인증 선택**(`@AuthMemberIdOrNull`) — Authorization 헤더 없음/비Bearer 는 게스트, 만료·위조 토큰은 AUTH-004/005 로 거절(기존 규약).

## 1. 피드 목록

```
GET /api/v1/community/posts?lang={lang}&cursor={cursor}
```

| 파라미터 | 필수 | 설명 |
|----------|------|------|
| lang | O | 표시 언어. 빈 값 → 400(COMMON-002 계열 검증 오류). 미지원 코드 → en 폴백, 번역 부재 → ko 폴백 |
| cursor | X | 이전 응답의 `nextCursor`. 생략 시 첫 페이지. 숫자 아님·음수 → `INVALID_CURSOR` |

**성공 200** — `BaseResponse<Page<PostingItemResponse>>`:

```json
{
  "success": true,
  "payload": {
    "items": [
      {
        "postId": 42,
        "author": { "memberId": 7, "nickname": "먹보", "profileImageUrl": "https://cdn.../profiles/7.webp" },
        "content": "오늘 김치찌개 최고였다",
        "imageUrls": ["https://cdn.../community/a.webp", "https://cdn.../community/b.webp"],
        "foodTags": [ { "foodId": 12, "name": "Kimchi Stew" } ],
        "likeCount": 0,
        "dislikeCount": 0,
        "commentCount": 0,
        "createdAt": "2026-08-04T13:00:00"
      },
      {
        "postId": 41,
        "author": { "memberId": null, "nickname": "탈퇴한 사용자", "profileImageUrl": null },
        "content": "탈퇴해도 글은 남는다",
        "imageUrls": [],
        "foodTags": [],
        "likeCount": 0,
        "dislikeCount": 0,
        "commentCount": 0,
        "createdAt": "2026-08-04T12:00:00"
      }
    ],
    "hasNext": true,
    "nextCursor": 23
  }
}
```

- 페이지 크기 20. `imageUrls[0]` 이 피드 커버. 빈 피드는 `items: []`, `hasNext: false`, `nextCursor: null`.
- `author.memberId == null` ⇔ 탈퇴 작성자(FE 로컬라이즈 분기 키).

**실패**:

| 상황 | HTTP | code |
|------|------|------|
| 게스트가 2페이지 초과 커서 요청 | 401 | `COMMUNITY-005` (로그인 필요 — FE 로그인 게이트) |
| 잘못된 커서 형식 | 400 | `INVALID_CURSOR` 기존 코드 |
| lang 누락·빈 값 | 400 | 검증 오류(기존 규약) |
| 만료 access 토큰 | 401 | `AUTH-004` → FE refresh 후 재시도 |

게스트 게이트 판정: `cursor` 위치가 최신 20건(=1페이지 소비분) 이내면 허용. 순차 호출 여부 무관 — 임의 커서로 우회 불가.

## 2. 글 상세

```
GET /api/v1/community/posts/{postId}?lang={lang}
```

**성공 200** — `BaseResponse<PostingItemResponse>` (피드 항목과 동일 형태, 게스트 제한 없음):

```json
{
  "success": true,
  "payload": {
    "postId": 42,
    "author": { "memberId": 7, "nickname": "먹보", "profileImageUrl": "https://cdn.../profiles/7.webp" },
    "content": "오늘 김치찌개 최고였다",
    "imageUrls": ["https://cdn.../community/a.webp"],
    "foodTags": [ { "foodId": 12, "name": "Kimchi Stew" } ],
    "likeCount": 0,
    "dislikeCount": 0,
    "commentCount": 0,
    "createdAt": "2026-08-04T13:00:00"
  }
}
```

**실패**:

| 상황 | HTTP | code |
|------|------|------|
| 삭제·미존재 글 | 400 | `COMMUNITY-001` (기존 NOT_FOUND) |
| lang 누락·빈 값 | 400 | 검증 오류 |

## 인증 필터 변경

`JwtAuthenticationFilter` 에 게스트 허용 예외 추가 — **GET 이면서** 아래 패턴에 정확히 일치할 때만 필터를 건너뛴다:

- `^/api/v1/community/posts$`
- `^/api/v1/community/posts/\d+$`

POST/PUT/DELETE 및 더 깊은 경로(예: `/community/posts/{id}/comments`)는 기존대로 필터를 탄다.

## 신규 에러 코드

| enum | code | HTTP | message |
|------|------|------|---------|
| `COMMUNITY_LOGIN_REQUIRED` | COMMUNITY-005 | 401 | 로그인이 필요합니다 |
