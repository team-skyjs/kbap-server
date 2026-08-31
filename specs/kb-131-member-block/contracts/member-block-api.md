# API Contract: 사용자 차단 (Member Block)

공통: 모든 응답은 `ResponseEntity<BaseResponse<T>>` 봉투. 경로는 `ApiPaths.V1` 상수 참조. 아래 4개 경로 전부 `JwtAuthenticationFilter` 보호 대상(기존 패턴 `/api/v1/members/*`·`/api/v1/reviews` 커버) — 무토큰 401(`AUTH-003`).

## POST /api/v1/members/me/blocks — 차단 등록

- 인증: `@AuthMemberId` (차단 주체 = 호출자)
- Request body:

```json
{ "memberId": 42 }
```

- `memberId`: 필수(`@field:NotNull`), 차단할 대상 회원 id
- 응답:

| 케이스 | HTTP | code | 비고 |
|--------|------|------|------|
| 성공(신규·재차단·이미 차단 중) | 200 | — | 멱등 — 세 경우 모두 동일 응답, payload 없음(`BaseResponse.ok(Unit)`) |
| 자기 자신 차단 | 400 | `BLOCK-001` | SELF_BLOCK_FORBIDDEN |
| 대상 미존재·탈퇴 회원 | 404 | `BLOCK-002` | BLOCK_TARGET_NOT_FOUND |
| body 검증 실패(memberId 누락) | 400 | `COMMON-002` | 기존 공통 핸들러 |

## DELETE /api/v1/members/me/blocks/{memberId} — 차단 해제

- 인증: `@AuthMemberId`, `@PathVariable memberId`
- 응답: 항상 200 멱등 — 차단 중이면 소프트삭제, 차단한 적 없거나 이미 해제·대상 미존재여도 200. payload 없음.

## GET /api/v1/members/me/blocks — 내가 차단한 회원 목록

- 인증: `@AuthMemberId`
- 응답 payload: `List<BlockedMemberResponse>` — 페이징 없음, 차단 등록 순서 무보장(정렬 계약 없음). 탈퇴한 차단 대상은 목록에서 제외.

```json
{
  "success": true,
  "payload": [
    { "memberId": 42, "nickname": "먹보", "profileImageUrl": "https://cdn.../profile/abc.jpg" }
  ]
}
```

- `nickname`: 미설정이면 null. `profileImageUrl`: 미설정이면 null, 설정 시 public base url 로 해석된 절대 URL(`ImageUrls.resolve`). 둘 다 **조회 시점 최신 값**(스냅샷 아님).

## GET /api/v1/reviews — 음식 리뷰 목록 (기존 API 수정)

- 변경: 컨트롤러가 `@AuthMemberId memberId`(조회자)를 받는다. **요청·응답 스키마 변화 없음** — 이 경로는 이미 인증 필터 보호 대상이라 클라이언트 계약 동일.
- 동작 변경: 조회자가 차단한 회원의 리뷰를 결과에서 제외한다(커서·페이지 크기 동작은 기존과 동일 — 제외는 쿼리 조건이므로 페이지가 덜 채워지는 왜곡 없음).
- 무변경 확인 대상: `GET /api/v1/reviews/me`(본인 글만 — 필터 비대상), `GET /api/v1/foods/{foodId}` 리뷰 집계 섹션(전역 값 유지).

## Swagger

`MemberBlockApi` 인터페이스에 `@Tag`·`@Operation`·`@ApiResponses`·`@SecurityRequirement` 문서 애너테이션만 두고, Spring 매핑·바인딩·인증 애너테이션은 전부 `MemberBlockController` 에 둔다(파라미터 애너테이션 위치 컨벤션). `ReviewApi.listFoodReviews` 는 파라미터 타입만 동기화.
