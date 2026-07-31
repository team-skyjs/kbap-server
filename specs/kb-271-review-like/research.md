# Research: 리뷰 좋아요 (kb-271)

Technical Context 에 NEEDS CLARIFICATION 은 없었다. 설계 선택지가 갈리는 지점 4곳을 코드베이스 선례로 확정한다.

## R1. 유니크 제약 vs 소프트삭제 충돌 해법

**Decision**: `member_block`(KB-131) 패턴 채택 — `(review_id, member_id)` 유니크 키로 쌍당 1행 고정, 취소는 소프트삭제(`status=DELETED`), 재등록은 native `INSERT ... ON DUPLICATE KEY UPDATE status='ACTIVE'` 로 같은 행을 부활시킨다.

**Rationale**:
- Jira DoD 가 요구하는 유니크 제약과 `BaseEntity` 소프트삭제 규약(`@SQLRestriction("status = 'ACTIVE'")`)은 "취소된 행이 쌓이는" 방식으로는 양립 불가 — 쌍당 1행 + 부활로만 양립한다.
- upsert 한 문장이 신규·중복·재등록을 원자 처리하므로 동시 중복 요청 방어(FR-002)가 별도 코드 없이 DB 제약으로 해결된다. 트랜잭션 안 `save()` 예외 폴백은 rollback-only 마킹 때문에 불가하다는 것이 member_block 에서 이미 검증된 사실이다.
- 동시성 방어 수위 규약(2026-07-30)과 정합 — 격리수준 조정 없이 유니크 제약이라는 최소 수단만 사용.

**Alternatives considered**:
- 하드 삭제(행 제거): 유니크 제약과 자연 양립하지만 BaseEntity 소프트삭제 규약 이탈. 부활 upsert 로 규약 안에서 해결 가능하므로 기각.
- 취소 행 누적 + 유니크 제약 포기(북마크 방식): Jira DoD 의 유니크 제약 요구 위반, 동시 중복 방어도 잃음. 기각.

## R2. 좋아요 로직의 소유 계층

**Decision**: 신규 도메인 서비스를 만들지 않고 기존 `api` 의 `ReviewService` 에 `likeReview`·`unlikeReview` 를 추가한다. 엔티티·리포지토리는 헌법 IV 에 따라 `common.domain.review` 에 둔다.

**Rationale**: 좋아요는 api 만 소비한다(배치 무관). api 전용 회원-콘텐츠 토글의 선례인 `BookmarkService` 가 api 기능 패키지에 있고, 리뷰 존재 검증·목록 enrich 가 기존 `ReviewService` 의 조회 흐름과 한몸이다. 위임뿐인 창구 서비스 금지(ADR-0014) 원칙상 별도 서비스는 조각 수만 늘린다.

**Alternatives considered**: `common.domain.review` 에 `ReviewLikeService` 신설 — web·batch 공유가 없어 과잉. 기각.

## R3. 목록 응답의 좋아요 수·여부 로드 방식

**Decision**: 페이지(최대 20건) 단위 배치 쿼리 2개 — ① `reviewId in (...) group by reviewId` 집계로 좋아요 수 Map, ② `memberId = ? and reviewId in (...)` 로 내가 좋아요한 reviewId Set. `ReviewService.toPage` 에서 합성한다.

**Rationale**: 리뷰별 개별 카운트 쿼리는 N+1. `BookmarkService.getBookmarkedFoodIds`(id 목록 → Set 배치 로드)가 동일 패턴의 선례다. Review 엔티티에 `likeCount` 비정규화 컬럼을 두는 방식은 등록/취소마다 원자 UPDATE 가 추가로 필요하고 현 트래픽에서 이득이 없다.

**Alternatives considered**:
- `food_review.like_count` 비정규화 컬럼: 쓰기 경합·정합 관리 비용 추가, 목록 20건 집계는 유니크 키 좌측 접두 인덱스로 충분. 기각 (필요해지면 후속 이슈).
- 응답 조립 시점 리뷰별 count 쿼리: N+1. 기각.

## R4. API 형태와 응답

**Decision**: `POST /api/v1/reviews/{reviewId}/like`(등록) · `DELETE /api/v1/reviews/{reviewId}/like`(취소), 둘 다 `ResponseEntity<BaseResponse<Unit>>`. 등록은 리뷰 존재 검증(`REVIEW_NOT_FOUND`, 기존 REVIEW-001 재사용) 후 upsert, 취소는 무검증 멱등 소프트삭제. 신규 ErrorCode 없음.

**Rationale**: 경로는 리소스 하위 관계로 표현(`ApiPaths.V1` 상수 사용), 응답 봉투·Unit 페이로드는 북마크 등록/해제 API 와 동일. 취소의 무검증 멱등은 `BookmarkService.unbookmark`·`MemberBlockService.unblock` 과 동일한 계약이다.

**Alternatives considered**:
- 토글 단일 엔드포인트: 클라이언트 상태와 서버 상태가 어긋나면 의도 반전 사고 — 등록/취소 분리가 멱등 계약과 맞다. 기각.
- 등록 응답에 최신 likeCount 포함: 목록 재조회로 충분(SC-004), 페이로드 계약만 늘린다. 기각.
