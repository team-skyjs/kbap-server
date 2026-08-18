# Research: 리뷰 목록 조회 API 비회원 공개 (KB-348)

Technical Context 에 NEEDS CLARIFICATION 없음 — 코드 조사 결과와 결정만 기록한다.

## Decision 1: GET 만 여는 수단 = JwtAuthenticationFilter 의 GuestExemption

- **Decision**: `WebConfig.jwtAuthenticationFilterRegistration` 의 `guestExemptions` 에 `GuestExemption("GET", Regex("^${ApiPaths.API}/reviews$"))` 를 추가한다.
- **Rationale**: 서블릿 필터 URL 패턴은 메서드를 구분하지 못하지만, 필터가 이미 `shouldNotFilter` 에서 **method + URI 정규식 정확 일치** 면제 메커니즘을 갖고 있다 — community posts GET 2건이 선례. 예외의 단일 출처(WebConfig)에 항목 하나 추가로 끝난다.
- **Alternatives considered**: (a) `/api/reviews` 를 보호 패턴에서 제거 — POST 작성까지 풀려 기각. (b) 필터 내부 메서드 분기 하드코딩 — 예외를 흩뿌리는 안이라 기각. (c) 경로 분리(`/api/reviews/public`) — 계약 변경이라 기각.

## Decision 2: 컨트롤러·서비스는 community/KB-334 패턴 재사용

- **Decision**: `listReviews` 바인딩을 `@AuthMemberIdOrNull memberId: Long?` 로, `getReviewPage(viewerMemberId: Long?)` 로 완화. 비회원 exclusion 은 `viewerMemberId?.let(::excludedMemberIds) ?: listOf(-1L)` — `getRecentFoodReviews`(KB-334)와 동일 관용구. `toPage` 의 viewer 를 nullable 로 완화하면 내부 `toResponses` 는 이미 nullable(KB-334 공용화)이라 추가 작업 없음.
- **Rationale**: 선례 2개(community 컨트롤러 바인딩·recentReviews 서비스 조립)와 정확히 같은 모양 — 새 패턴을 만들지 않는다.
- **Alternatives considered**: 게스트 전용 서비스 메서드 신설 — 목록 로직 중복이라 기각.

## Decision 3: 탈퇴 회원 토큰은 별도 분기 없음

- **Decision**: 유효 토큰이면 필터 통과·리졸버가 memberId 제공 → 그 id 기준으로 동작(차단 목록 빈 값·likedByMe 조회). 별도 활성 회원 검증을 추가하지 않는다.
- **Rationale**: 기존 회원 경로도 목록 조회에서 활성 검증을 하지 않았고(변경 전 `@AuthMemberId` 는 토큰의 id 만 줌), 상세 recentReviews 취급과 일관.

## 코드 조사 결과

- `JwtAuthenticationFilter.shouldNotFilter`: `guestExemptions.any { method == it.method && it.path.matches(requestURI) }` — 정확 일치라 `^/api/reviews$` 면제가 `/api/reviews/me`·`/api/reviews/{id}`·`/api/reviews/{id}/like` 에 영향 없음.
- `AuthMemberIdOrNullArgumentResolver` 는 토큰이 있으면 파싱, 없으면 null — 면제 경로에서 회원 맥락 유지 가능(community 와 동일).
- 401 회귀 커버리지: `ReviewControllerTest`·`ReviewLikeControllerTest` 등에 미인증 401 시나리오가 이미 존재 — 쓰기 계열 보호 유지가 기존 테스트로도 감시된다.
- 응답 조립: `toResponses(page, viewerMemberId: Long?, lang, includeFood)` — KB-334 에서 이미 nullable. `likedByMe` 는 viewer null 이면 항상 false.
