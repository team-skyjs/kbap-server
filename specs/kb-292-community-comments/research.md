# Research: 커뮤니티 댓글/대댓글

Technical Context 에 NEEDS CLARIFICATION 은 없었다. 아래는 설계 갈림길에 대한 결정 기록이다.

## R1. 통삭제 실현 방식 — 삭제 시점 bulk soft-delete

- **Decision**: 최상위 댓글 삭제 시 본체는 `BaseEntity.delete()`(dirty checking), 하위 대댓글은 `@Modifying` bulk UPDATE 로 일괄 `DELETED` 전환한다. 이후 모든 목록·카운트는 `@SQLRestriction("status = 'ACTIVE'")` 하나로 삭제분이 자동 제외된다.
- **Rationale**: 조회 시점에 "부모가 삭제된 대댓글" 을 걸러내는 방식은 목록·카운트·피드 카운트 등 모든 읽기 경로마다 부모 join/서브쿼리를 요구한다. 쓰기 시점 1회 UPDATE 가 읽기 전 경로를 단순하게 만든다. 이미 삭제된 대댓글에 UPDATE 가 다시 닿아도 멱등이라 무해하다.
- **Alternatives considered**: (a) 조회 시점 부모 상태 join 필터 — 읽기 경로 전부가 복잡해져 기각. (b) 부모 삭제 시 대댓글 개별 로드 후 `delete()` 루프 — N건 SELECT+UPDATE, bulk 1문이면 충분해 기각.
- **동시성 주의**: "부모 삭제와 동시에 새 대댓글 등록" 경합은 비치명 경합으로 감수한다(헌법 외 컨벤션 2026-07-30 — 격리수준 조정·과잉 동시성 테스트 금지).

## R2. 커서 계약 — top-level `id` asc 커서 + 대댓글 전량 중첩

- **Decision**: 커서 페이징은 **최상위 댓글에만** 적용한다 — 등록순(`id` asc, IDENTITY 단조 증가), `id > :cursor`, 페이지 크기 20(size+1 로 hasNext 판정), `nextCursor` = 페이지 마지막 top-level 댓글 id. 페이지에 실린 top-level 댓글들의 대댓글은 `parentId in (…)` 한 방 조회로 전량 로드해 항목 안에 등록순으로 중첩한다.
- **Rationale**: 피드(KB-291)가 확립한 `CursorParser`·`Page<T>`·size+1 패턴을 방향만 뒤집어(피드 desc → 댓글 asc) 재사용한다. 등록순 asc 는 새 댓글이 항상 목록 끝에 붙으므로 커서 진행 중 중복·누락이 구조적으로 없다(SC-006). 대댓글에 별도 커서를 두는 것은 계약·FE 복잡도만 키운다 — 유튜브식 UX 에서 답글 수는 소수가 일반적.
- **Alternatives considered**: (a) 대댓글도 독립 커서 — 필요 근거 없음, 필요해지면 추가 API 로 확장 가능. (b) createdAt 커서 — 동시각 중복 처리(tie-break)가 필요해지고 id 로 충분해 기각.

## R3. 답글 대상 정규화 — 부모는 항상 최상위 댓글, 서버가 평탄화

- **Decision**: 작성 요청의 `parentCommentId` 가 대댓글을 가리키면 서버가 그 대댓글의 최상위 부모로 정규화해 저장한다(`parent.parentId ?: parent.id`). 저장된 `parentId` 는 항상 최상위 댓글을 가리킨다 — 1depth 불변식을 서버가 보장한다.
- **Rationale**: FE 가 "대댓글에 답글" 을 그대로 보내도 깊이 2 가 생길 수 없다. 정규화는 한 줄이고, 거부 방식은 FE 에 평탄화 책임을 떠넘겨 깨지기 쉽다.
- **Alternatives considered**: 대댓글을 부모로 지정하면 400 거부 — FE·BE 양쪽에 계약 부담만 추가해 기각.
- **파생 계약**: 삭제된 대댓글에 대한 답글은 FE 가 스레드 루트(최상위 댓글 id)를 보내면 정상 등록된다(스펙 edge case). 삭제된 **최상위 댓글** id 는 `@SQLRestriction` 에 걸려 조회 불가 → 대상 없음 오류.

## R4. 탈퇴 작성자 익명화 — 작성자 lookup miss 를 익명 표기로 치환

- **Decision**: 목록 조립 시 `MemberJpaRepository.findAllById`(active 만 반환 — `@SQLRestriction`) lookup 에 없는 작성자는 `author = { memberId: null, nickname: "탈퇴한 사용자", profileImageUrl: null }` 로 응답한다. 댓글 본문은 그대로 유지한다.
- **Rationale**: 피드 조립(`CommunityService.assemble`)과 같은 일괄 lookup 패턴을 재사용하되, 게시글(작성자 탈퇴 시 글 자체 숨김)과 달리 댓글은 스레드 문맥 보존을 위해 익명화 유지(Jira 명시). lookup miss = 탈퇴가 이미 시스템의 사실 표현이므로 별도 상태 컬럼이 필요 없다.
- **Alternatives considered**: 댓글에 탈퇴 여부 별도 표시 필드(boolean) 추가 — `memberId: null` 이 이미 그 신호라 중복이어서 기각. FE 다국어 표기가 필요해지면 그때 flag 를 추가한다.

## R5. commentCount 배선 — 페이지당 group-count 1회

- **Decision**: `CommunityService.assemble` 의 `commentCount = 0` 자리에 `CommentJpaRepository` 의 `select c.postId, count(c.id) … where c.postId in :postIds group by c.postId` 결과를 배선한다. 카운트 = 노출 가능한 최상위 댓글 + 대댓글 전부(`@SQLRestriction` 이 삭제분·통삭제분 자동 제외 — R1 덕분에 별도 조건 불필요).
- **Rationale**: 피드 한 페이지(20건)당 쿼리 1회 추가로 끝난다. 카운트 컬럼 비정규화(post 에 comment_count 유지)는 쓰기마다 원자 UPDATE 가 필요하고 현 트래픽에서 이득이 없다.
- **Alternatives considered**: `community_post.comment_count` 비정규화 컬럼 — 정합 유지 비용 대비 이득 없음(성능 문제가 실측되면 도입). 기각.

## R6. 서비스 배치 — 기존 `CommunityService` 에 통합 (2026-08-05 개정)

- **Decision**: 댓글 유스케이스(작성/수정/삭제/목록)를 기존 `com.kbap.api.community.CommunityService` 에 추가한다 — 별도 `CommentService` 를 만들지 않는다(사용자 결정). 도메인 검증(본문 길이·소유권·1depth 정규화 재료)은 `Comment` 엔티티가 소유한다.
- **Rationale**: KB-290 선례 — community 는 web 전용 소비라 common 도메인 서비스 없이 api 서비스가 리포지토리를 직접 조립한다(헌법 IV, 위임 창구 금지). 게시글·댓글이 한 서비스에 있으면 피드 commentCount 배선까지 접점이 한 파일에 모인다.
- **Alternatives considered**: (a) `CommentService` 분리 — 파일 비대·병렬 태스크 충돌 방지 논거였으나 서비스 수를 늘리지 않는 단순함을 우선해 기각(사용자 결정). (b) `common.domain.community` 도메인 서비스 — batch·infra 소비가 없어 배치 기준 미충족, 기각.
- **컨트롤러도 동일**: 댓글 엔드포인트 4개는 기존 `CommunityController` + `CommunityApi` 인터페이스에 추가한다 — 댓글 전용 컨트롤러·Api 인터페이스를 만들지 않는다(사용자 결정, 같은 날).

## R7. 오류 코드 — COMMUNITY 접두 이어서 채번

- **Decision**: `COMMUNITY_COMMENT_NOT_FOUND("COMMUNITY-006", 400)`·`COMMUNITY_COMMENT_FORBIDDEN("COMMUNITY-007", 403)` 두 개를 추가한다. 본문 필수·길이 초과는 요청 DTO 의 jakarta validation(기존 전역 핸들러), 게시글 부재는 기존 `COMMUNITY_POSTING_NOT_FOUND`, 인증은 기존 리졸버(`@AuthMemberId`) 경로를 재사용한다.
- **Rationale**: 도메인 접두 + 3자리 채번 규칙, 폐번 재사용 금지. 신규 상황은 "댓글 없음"·"남의 댓글" 두 가지뿐이다.
