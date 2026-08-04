# Research: 커뮤니티 피드 조회 + 글 상세 (KB-291)

기존 코드베이스 조사 결과와 결정 사항. 신규 기술 도입 없음 — 전부 기존 규약 재사용이다.

## R1. 커서 페이징 규약

**Decision**: `api.core.Page<T>`(items/hasNext/nextCursor) + `CursorParser.parse` + `PAGE_SIZE = 20`, id 내림차순 keyset(`id < :cursor`), `PAGE_SIZE + 1` 조회 후 잘라내기.

**Rationale**: 리뷰(`ReviewService.getFoodReviewPage`)·북마크·음식 목록이 전부 이 패턴이다. 잘못된 커서는 `CursorParser` 가 `INVALID_CURSOR` 로 거절(FR-011 충족).

**Alternatives considered**: Spring Data `Slice` — 기존 API 와 응답 형태가 갈라져 기각.

## R2. 게스트 첫 페이지 게이트 판정 (개정 2026-08-04 — 2페이지 허용안 축소, 사용자 결정)

**Decision**: 게스트 요청은 `cursor == null`(첫 페이지)만 통과. **커서가 있으면 값과 무관하게 로그인 필요 오류** — 커서의 존재 자체가 두 번째 페이지 이후 요청이다.

**Rationale**: DB 질의 없는 널 체크 한 줄로 판정이 끝난다(초기 2페이지안의 커서 위치 판정 쿼리 `findIdsFrom` 삭제). 임의 커서 우회도 원천 불가.

**Alternatives considered**: 2페이지 허용 + LIMIT 21 커서 위치 판정(초기안 — 노출량 과다로 축소), 페이지 번호 파라미터(커서 규약과 이중화 — 기각).

## R3. 로그인 게이트 에러 코드

**Decision**: `ErrorCode.COMMUNITY_LOGIN_REQUIRED("COMMUNITY-005", 401, "로그인이 필요합니다")` 신설.

**Rationale**: FE 가 이 코드로 로그인 게이트 UI 를 분기한다(코드 분기 규약). AUTH-004(만료)·AUTH-006(재로그인)과 의미가 다르다 — 토큰 문제가 아니라 "게스트 허용 범위 초과"다. COMMUNITY 접두 5번 채번(001~004 사용 중).

## R4. 게스트/회원 판정과 만료 토큰

**Decision**: `@AuthMemberIdOrNull` 리졸버 재사용. 헤더 없음·Bearer 형식 아님 → null(게스트). 형식은 맞으나 만료·위조 → `parseAccessToken` 이 던지는 AUTH 오류 그대로 응답(home API 와 동일).

**Rationale**: 만료 토큰을 조용히 게스트로 강등하면 로그인한 회원이 3페이지에서 영문 모를 게이트에 막힌다. AUTH-004 를 받아야 FE 가 refresh 후 재시도한다. 기존 선택 인증 경로(home)와 동일 동작 — 신규 규칙 없음.

## R5. 공개 GET 과 JwtAuthenticationFilter 충돌 (사용자 확정)

**Decision**: 읽기도 `GET /api/v1/community/posts`·`GET /api/v1/community/posts/{postId}` 경로를 쓴다. `JwtAuthenticationFilter` 에 `shouldNotFilter` 예외를 추가한다 — **GET + 정확히 두 패턴**(`^/api/v1/community/posts$`, `^/api/v1/community/posts/\d+$`)만. 예외 목록은 WebConfig 등록부에서 주입한다.

**Rationale**: 같은 리소스는 같은 경로(REST 일관성). 서블릿 필터 URL 패턴은 메서드를 구분하지 못하므로 필터 내부 예외가 유일한 동일-경로 해법. 1단계 깊이 숫자만 허용하므로 후속 댓글 GET(`/community/posts/{id}/comments`, 회원 전용)은 계속 필터를 탄다.

**Alternatives considered**: `GET /community/feed` 별도 경로(필터 무변경) — 리소스 경로 이원화로 기각(사용자 선택).

## R6. 음식 태그 표현 (사용자 확정)

**Decision**: `foodTags: [{foodId, name}]` — 요청 언어 기준 음식명 포함. `FoodService.getReadyFoodsByIds(ids)` 로 페이지 전체 태그를 일괄 조회하고 `Food.displayName(lang)` 으로 이름을 뽑는다. 조회 결과에 없는 id(삭제·비READY 음식)는 태그에서 제외(FR-009).

**Rationale**: FE 가 태그 칩을 추가 조회 없이 렌더링. 번역 부재 → ko 폴백은 `displayName` 의 기존 규칙(헌법 V) 그대로.

**주의**: `Food` 는 성분 EAGER `@OneToMany` + `@BatchSize` 를 갖고 있어 페이지당 고정 횟수 쿼리로 유지된다(SC-005 충족 — 항목 수 비례 아님).

## R7. lang 파라미터

**Decision**: 피드·상세 모두 `lang` **필수**(`@field:NotBlank`), `LanguageCode.from` 으로 변환. 헌법 V 3분기(빈 값 400 / 번역 부재 ko / 미지원 코드 en) 그대로.

## R8. 탈퇴 작성자 글 숨김 (개정 2026-08-04 — 익명화 노출안 폐기, 사용자 결정)

**Decision**: 탈퇴 작성자의 글은 피드·상세에서 **존재 자체를 숨긴다**. 피드·게이트 쿼리에 `exists (select m.id from Member m where m.id = p.memberId)` 를 넣는다 — Member 의 `@SQLRestriction(ACTIVE)` 이 서브쿼리에도 적용돼 탈퇴(소프트 삭제) 회원이 자동 제외된다. 상세는 `findById` 후 `memberRepository.existsById` 로 검증해 COMMUNITY-001 로 거절한다. 게이트 카운트도 같은 가시성으로 세야 커서 위치가 어긋나지 않는다.

**Rationale**: 글은 피드의 1급 콘텐츠라 익명 잔존 가치가 낮다(초기 익명화안 폐기). 데이터는 보존한다. 같은 트랜잭션(REPEATABLE READ 스냅샷) 안에서 피드 쿼리와 작성자 일괄 조회가 같은 스냅샷을 보므로, 노출된 글의 작성자는 항상 조회된다 — `PostingAuthorResponse.memberId` 를 non-null 로 확정.

**후속 정책 기록**: 탈퇴 회원의 **댓글**은 숨기지 않고 "(삭제)" 표기로 남긴다(스레드 맥락 보존) — 댓글 태스크(KB-292)에서 구현.

## R9. 리액션·댓글 카운트

**Decision**: `likeCount`·`dislikeCount`·`commentCount` 필드를 응답 계약에 확정하고 상수 0 으로 채운다.

**Rationale**: FE 목 교체가 응답 구조 변경 없이 되도록 자리를 지금 판다(SC-007). 리액션·댓글 도메인이 생기면 조립 지점 한 곳만 실제 카운트로 교체.

## R10. 단일 조회 경로 (FR-010)

**Decision**: 조회 유스케이스를 `CommunityService` 의 두 public 메서드(`getPostingPage`·`getPosting`)로 두되, **게시글 → 응답 조립을 private 함수 하나**(작성자·음식 태그 일괄 로드 + 매핑)로 모은다. 피드와 상세가 같은 조립 함수를 지난다.

**Rationale**: 후속 차단 필터·신고 숨김·번역은 이 서비스의 조회 쿼리(차단)·조립 함수(번역) 한 곳만 고치면 피드·상세에 동시 반영된다. 리뷰의 차단 제외 패턴(`excludedMemberIds`, -1 센티널)을 그대로 이식할 수 있는 자리다. 별도 Reader 클래스는 현재 조각 수만 늘려 기각(컨벤션 — 위임 전용 계층 금지).

## R11. 스키마

**Decision**: 마이그레이션 없음. 피드 쿼리(`status='ACTIVE' AND id < ? ORDER BY id DESC LIMIT 21`)와 게이트 count 는 PK 인덱스로 충분하다.

**Rationale**: `@SQLRestriction` 이 status 조건을 붙이지만 id 범위 스캔이 지배적이라 별도 (status, id) 복합 인덱스는 현 규모에서 불필요 — 필요해지면 후속 마이그레이션으로 추가한다.
