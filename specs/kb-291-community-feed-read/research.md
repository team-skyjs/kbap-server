# Research: 커뮤니티 피드 조회 + 글 상세 (KB-291)

기존 코드베이스 조사 결과와 결정 사항. 신규 기술 도입 없음 — 전부 기존 규약 재사용이다.

## R1. 커서 페이징 규약

**Decision**: `api.core.Page<T>`(items/hasNext/nextCursor) + `CursorParser.parse` + `PAGE_SIZE = 20`, id 내림차순 keyset(`id < :cursor`), `PAGE_SIZE + 1` 조회 후 잘라내기.

**Rationale**: 리뷰(`ReviewService.getFoodReviewPage`)·북마크·음식 목록이 전부 이 패턴이다. 잘못된 커서는 `CursorParser` 가 `INVALID_CURSOR` 로 거절(FR-011 충족).

**Alternatives considered**: Spring Data `Slice` — 기존 API 와 응답 형태가 갈라져 기각.

## R2. 게스트 2페이지 게이트 판정

**Decision**: 게스트 요청에서 `cursor == null`(1페이지)이면 통과. 커서가 있으면 **커서 이후(최신 방향) 글 id 를 최대 `PAGE_SIZE + 1` 건만 조회**(`select p.id … where p.id >= :cursor order by p.id` + LIMIT 21, ACTIVE 만 — `@SQLRestriction` 자동 적용)해 결과가 **`PAGE_SIZE` 이하일 때만** 통과(= 2페이지 요청). 초과하면 로그인 필요 오류.

**Rationale**: 순차 호출 여부와 무관하게 커서 위치만으로 판정된다(임의 커서 우회 불가 — spec edge case). 세션·호출 횟수 추적이 필요 없다. 1페이지 커서(20번째 글 id)로 요청하면 20건 → 통과, 2페이지 커서(40번째 글 id)로 요청하면 21건에서 잘림 → 거절. **무제한 count 를 쓰지 않는 이유**: `count(id >= cursor)` 는 PK range scan 이지만 악의적 깊은 커서(`cursor=1`)에서 테이블 전체를 세게 된다 — LIMIT 21 프로젝션은 어떤 커서에도 스캔이 최대 21행에서 멈춘다(게스트는 비인증이라 이 경로의 최악 비용을 반드시 상수로 묶는다).

**감수하는 경계**: 판정 이후 글 삭제로 count 가 줄면 게스트가 한 페이지 정도 더 깊이 볼 수 있다 — 비치명 경합, 감수한다(격리수준·잠금 금지 컨벤션).

**Alternatives considered**: 페이지 번호 파라미터 추가(커서 규약과 이중화 — 기각), 응답에 남은 허용량 내려주기(FE 요구 없음 — YAGNI).

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

## R8. 탈퇴 작성자 익명화

**Decision**: 페이지 작성자를 `memberRepository.findAllById(memberIds)` 로 일괄 조회한다. 탈퇴 회원은 `withdraw()` 가 `delete()`(status=DELETED)를 호출하므로 `@SQLRestriction` 에 걸러져 결과에 없다 → 그 글의 작성자는 `nickname = "탈퇴한 사용자"`, `profileImageUrl = null`, `memberId = null` 로 응답한다(FE 는 null 이미지에 기본 아바타를 그린다).

**Rationale**: 별도 상태 질의 없이 소프트 삭제 모델이 익명화 판정을 공짜로 준다. 리뷰(`author: null`)와 달리 작성자 줄이 항상 렌더링돼야 하므로 서버가 문구를 채운다(Jira 명시).

**감수하는 비용**: "탈퇴한 사용자" 문구는 한국어 고정이다. UI 문구 다국어화는 정적 UI 번역 정책(FE 소관, 헌법 V 분리 원칙) 대상이라 서버 콘텐츠 번역과 섞지 않는다. FE 가 필요 시 `memberId == null` 로 분기해 자체 로컬라이즈할 수 있게 필드 계약을 남긴다.

## R9. 리액션·댓글 카운트

**Decision**: `likeCount`·`dislikeCount`·`commentCount` 필드를 응답 계약에 확정하고 상수 0 으로 채운다.

**Rationale**: FE 목 교체가 응답 구조 변경 없이 되도록 자리를 지금 판다(SC-007). 리액션·댓글 도메인이 생기면 조립 지점 한 곳만 실제 카운트로 교체.

## R10. 단일 조회 경로 (FR-010)

**Decision**: 조회 유스케이스를 `CommunityService` 의 두 public 메서드(`getPostingPage`·`getPosting`)로 두되, **게시글 → 응답 조립을 private 함수 하나**(작성자·음식 태그 일괄 로드 + 매핑)로 모은다. 피드와 상세가 같은 조립 함수를 지난다.

**Rationale**: 후속 차단 필터·신고 숨김·번역은 이 서비스의 조회 쿼리(차단)·조립 함수(번역) 한 곳만 고치면 피드·상세에 동시 반영된다. 리뷰의 차단 제외 패턴(`excludedMemberIds`, -1 센티널)을 그대로 이식할 수 있는 자리다. 별도 Reader 클래스는 현재 조각 수만 늘려 기각(컨벤션 — 위임 전용 계층 금지).

## R11. 스키마

**Decision**: 마이그레이션 없음. 피드 쿼리(`status='ACTIVE' AND id < ? ORDER BY id DESC LIMIT 21`)와 게이트 count 는 PK 인덱스로 충분하다.

**Rationale**: `@SQLRestriction` 이 status 조건을 붙이지만 id 범위 스캔이 지배적이라 별도 (status, id) 복합 인덱스는 현 규모에서 불필요 — 필요해지면 후속 마이그레이션으로 추가한다.
