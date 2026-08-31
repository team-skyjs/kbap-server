# Research: 커뮤니티 게시글 도메인 (KB-290)

## R1. 게시글 이미지 저장 방식 — JSON 컬럼 (리뷰 선례 재사용)

- **Decision**: 게시글 사진은 별도 테이블 없이 `community_post.image_refs` **JSON 컬럼**(`List<String>` — storage key 목록)으로 저장한다. 리스트 순서가 표시 순서이고 **첫 원소가 피드 커버**다. 최대 4장 제약은 엔티티 `require` + 요청 DTO 검증이 강제한다.
- **Rationale**: `Review.imageRefs`(`@JdbcTypeCode(SqlTypes.JSON)`, 최대 3장)와 동일 패턴 — 이미지는 storage key 문자열이라 FK 를 걸 대상이 없고, 이미지 단위로 쿼리할 요구가 없으며, 순서 보존이 리스트로 자연스럽다. 조회 시 조인 없이 글 한 건으로 완결된다.
- **Alternatives considered**: `community_post_image` 별도 테이블(Jira DoD 표현) — 이미지 단위 쿼리·FK 대상이 없어 테이블·엔티티·정렬 컬럼만 늘어난다. 기각. **DoD 와의 delta**: "게시글 이미지 엔티티"는 JSON 컬럼으로 실현함을 태스크 진행 시 명시.

## R2. 음식 태그 저장 방식 — `food_ids` JSON 컬럼 (2026-08-04 개정 — 별도 테이블안 기각)

- **Decision**: 음식 태그는 별도 테이블 없이 `community_post.food_ids` **JSON 컬럼**(`List<Long>?`)으로 저장한다. 글당 최대 3개·중복 불가는 엔티티 `require`, READY 음식 존재 검증은 서비스(`FoodService.getReadyFood`)가 강제한다.
- **Rationale**: 최대 3개짜리 참조 목록이라 `image_refs` 와 동일 패턴이 가장 단순하다. 조회 시 조인 없이 글 한 건으로 완결되고, 피드의 태그 표시는 "연관 데이터는 id 목록으로 명시 조회" 컨벤션대로 food 를 id 로 로드하면 된다. 테이블·엔티티·리포지토리 한 벌이 통째로 사라진다.
- **Alternatives considered**: `community_post_food_tag` 테이블 + FK/UNIQUE(초안 결정) — DB 강제와 음식별 역방향 조회가 장점이나, 역방향 조회("음식별 글 모아보기")는 스코프에 없는 추측성 요구고 정합 검증은 어차피 서비스가 수행한다. 필요가 생기면 그때 테이블로 옮긴다. 기각.

## R3. 태그 유효성 — READY 음식만 허용

- **Decision**: 태그 대상 음식은 `FoodService.getReadyFood`(리뷰 작성과 동일 경로)로 검증한다 — 존재하지 않거나 삭제(소프트)됐거나 READY 가 아닌 음식은 태그 불가.
- **Rationale**: 태그 검색이 기존 `/foods/search`(READY 노출) 재사용이므로 검색 결과에 나오는 음식 = 태그 가능 음식으로 정합. 리뷰 작성 검증과 동일 규칙이라 사용자 기대 일치.
- **Alternatives considered**: 존재만 검증(상태 무관) — 검색에 안 나오는 음식이 태그되는 불일치 발생. 기각.

## R4. 수정 시각 — `edited_at` 명시 컬럼

- **Decision**: `community_post.edited_at`(nullable DATETIME) 컬럼을 두고 **본문·사진·태그 수정 성공 시에만** 갱신한다. null 이면 수정된 적 없음.
- **Rationale**: 기획 확정 — "(edited)" 표시는 안 하되 표시 도입 시 마이그레이션 없이 켤 수 있게 수정 시각을 미리 확보. `BaseEntity.updatedAt` 은 관리자 조치·상태 변경 등 모든 변경에 움직여 "사용자 수정" 판별로 부정확하다.
- **Alternatives considered**: `updatedAt` 재사용(`createdAt != updatedAt` 비교) — 위 이유로 오탐 가능. 컬럼 하나가 더 싸다. 기각.

## R5. 삭제 모델 — BaseEntity 소프트 삭제, 통삭제는 조회 경로가 성립

- **Decision**: 글 삭제는 `BaseEntity.delete()`(status=DELETED) 소프트 삭제만 수행한다. 댓글 rows 는 건드리지 않는다 — 댓글 조회(KB-292)가 **항상 활성 글 경유**로 설계되면 글 삭제 시 하위 댓글이 자동으로 노출 불가가 되어 통삭제 정책이 성립한다.
- **Rationale**: `@SQLRestriction("status='ACTIVE'")` 이 전 조회에서 삭제 글을 자동 제외한다. 글 삭제 시 댓글을 일괄 soft-delete 하면 "댓글 단독 삭제" 와 상태가 섞이고 복구 의미가 훼손된다.
- **Alternatives considered**: 삭제 시 댓글 일괄 soft-delete — 위 이유 + 이 태스크에 댓글 도메인이 없어 불가능. 기각.

## R6. 서비스 배치 — 엔티티·리포는 `:common`, 유스케이스 서비스는 `:api`

- **Decision**: `Posting` 엔티티와 `PostingJpaRepository` 는 `com.kbap.common.domain.community`(+`model/`)에, 작성/수정/삭제 유스케이스는 `com.kbap.api.community` 기능 패키지에 **`Community*` 접두**(`CommunityController`·`CommunityService`·`CommunityApi`·요청/응답 DTO)로 둔다. `ModuleBoundaryTest` 허용 맵에 `"community" to emptySet()` 을 추가한다(엔티티는 id 값 참조만 — 타 도메인 import 없음).
- **Rationale**: 리뷰 도메인 선례와 동일 — 커뮤니티는 api 전용 소비(배치 미사용)라 공유 도메인 서비스가 불필요하고, 교차 도메인 조합(FoodService·이미지 검증)은 api 기능 패키지 몫이다(ADR-0017).
- **Alternatives considered**: `common.domain.community` 에 도메인 서비스 — 소비자가 api 뿐이라 조각만 는다. 기각.

## R7. 이미지 소유 검증·업로드 경로 — 기존 인프라 재사용 + `UploadPurpose.COMMUNITY`

- **Decision**: 사진 업로드는 기존 presigned URL 흐름(`ImageUploadUrlController` → `UploadedImage` 완료 기록)을 재사용하고, `UploadPurpose` 에 `COMMUNITY("community")` 를 추가한다. 글 등록·수정 시 `verifyImageOwnership`(리뷰와 동일 — 본인이 업로드·검증 완료한 key 만 허용) 검증을 거친다.
- **Rationale**: 신규 업로드 경로를 만들지 않는 것이 스펙 가정. 검증 규칙(REVIEW-003 상당)도 재사용.
- **Alternatives considered**: 없음(기존 인프라 강제 재사용).

## R8. 에러 코드 — `COMMUNITY-` 접두 신설

- **Decision**: `ErrorCode` 에 신설: `COMMUNITY_POSTING_NOT_FOUND("COMMUNITY-001", 400)`, `COMMUNITY_POSTING_FORBIDDEN("COMMUNITY-002", 403)`, `COMMUNITY_IMAGE_NOT_VERIFIED("COMMUNITY-003", 400)`, `COMMUNITY_FOOD_TAG_INVALID("COMMUNITY-004", 400)`. 본문 길이·개수 위반 등 형식 검증은 요청 DTO(Bean Validation)가 소유(헌법 V 검증 소유 조항)해 공통 검증 오류로 응답한다.
- **Rationale**: 도메인 접두 + 3자리 채번 규약, 리뷰(REVIEW-001~003) 대칭.
