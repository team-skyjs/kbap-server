# Tasks: 리뷰 CRUD — 별점·본문·사진(≤3) + 전체/같은 국적 평점

**Input**: Design documents from `specs/kb-128-review-crud/` (plan·research·data-model·contracts/review-api.md·quickstart)

**Tests**: Test-First NON-NEGOTIABLE — 각 태스크 그룹은 실패 테스트 작성·Red 확인 → 구현(Green) → Refactor 순서를 지킨다.

**Organization**: **PR 단위 4개 Phase** 로 그룹핑(사용자 합의된 분할). 스토리 매핑 — PR1=기반(전 스토리 전제), PR2=[US1]작성/수정/삭제+[US4]랭킹, PR3=[US3]목록, PR4=[US2]평점 노출. PR3·PR4 는 PR1 에만 의존해 상호 병렬 가능.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일·미완료 태스크 의존 없음)
- 경로는 워크트리 루트 기준

---

## Phase 1: PR1 `kb-128-review-persistence` — 스키마·엔티티·리포지토리 (Foundational)

**Purpose**: review 테이블·`Review` 엔티티·`ReviewJpaRepository` — 모든 스토리의 전제. base=develop.

**⚠️ CRITICAL**: 이 Phase 머지(또는 최소 커밋 완료) 전에는 어떤 스토리 구현도 시작하지 않는다.

- [X] T001 [P] Red: `Review` 도메인 불변 단위 테스트 작성·실패 확인 — rating 1~5 경계(0·1·5·6), content 1000자 경계, imageRefs 3장 경계, `update` 재검증, `isOwnedBy` — `common/src/test/kotlin/com/kbap/common/domain/review/model/ReviewTest.kt`
- [X] T002 [P] Red: `ReviewJpaRepository` 영속 테스트 작성·실패 확인(Testcontainers) — keyset 2종(커서 null/값·21건 경계·id desc), countryCode 필터(null=전체·미존재 코드=빈 목록), `aggregateRating`(전체/국적별/0건 null), `countByMemberIdAndFoodId`, 소프트삭제 자동 제외 — `common/src/test/kotlin/com/kbap/common/domain/review/ReviewJpaRepositoryTest.kt`
- [X] T003 Green: `Review` 엔티티 구현 — BaseEntity 상속, memberId/foodId Long, rating TINYINT, content VARCHAR(1000) NULL, `@JdbcTypeCode(SqlTypes.JSON)` imageRefs NULL, authorCountryCode VARCHAR(10) NULL, init 불변·`update`·`isOwnedBy` — `common/src/main/kotlin/com/kbap/common/domain/review/model/Review.kt`
- [X] T004 Green: `ReviewJpaRepository` 구현 — data-model.md 의 keyset 2종·집계·count 쿼리 — `common/src/main/kotlin/com/kbap/common/domain/review/ReviewJpaRepository.kt`
- [X] T005 Green: Flyway 마이그레이션 작성(생성 시각 timestamp 채번) — `review` 테이블+인덱스 3종(`idx_review_food_recent`·`idx_review_member_recent`·`idx_review_food_country`)+FK 2종, 단수 테이블명 — `api/src/main/resources/db/migration/V<now>__review_table.sql`
- [X] T006 `ModuleBoundaryTest` 허용 맵에 `"review" to emptySet()` 추가(정확 일치 검사라 필수) — `api/src/test/kotlin/com/kbap/api/architecture/ModuleBoundaryTest.kt`
- [X] T007 검증·Refactor: `./gradlew :common:test :api:test --tests "com.kbap.api.architecture.*" --tests "com.kbap.api.migration.*"` Green 확인(엔티티↔스키마 validate 포함) 후 커밋 → draft PR(base=develop)

**Checkpoint**: 영속 계층 완성 — PR2 진행, PR1 머지 후 PR3·PR4 병렬 시작 가능

---

## Phase 2: PR2 `kb-128-review-write` — 작성/수정/삭제 + 랭킹 연동 (US1+US4) 🎯 MVP

**Goal**: POST/PATCH/DELETE `/api/v1/reviews` — 검증·403·이미지 소유 확인·국적 스냅샷·랭킹 원자 증감까지. base=PR1(미머지 시 스택).

**Independent Test**: 회원 토큰으로 작성→수정→삭제 전 과정 + 랭킹 카운트 증감(첫/추가/마지막)을 MockMvc 로 단독 검증.

- [X] T008 [P] [US1] Red: `ReviewControllerTest` 작성·실패 확인 — 작성 성공(별점만/본문+사진 2장), 검증 실패(rating 0·6, content 1001자, imagePaths 4장, foodId 누락), 401(무토큰), 타인 수정/삭제 403(REVIEW-002), 없는 리뷰 400(REVIEW-001), 없는 음식 400(FOOD-001), 미소유 이미지 400(REVIEW-003), 수정 후 값 반영·스냅샷 불변, 삭제 후 재수정 400 — `api/src/test/kotlin/com/kbap/api/review/ReviewControllerTest.kt` (시드는 BookmarkControllerTest 선례의 raw JDBC INSERT 헬퍼)
- [X] T009 [P] [US4] Red: 랭킹 카운트 시나리오 테스트 작성·실패 확인 — 첫 리뷰(+1/+1), 같은 음식 두 번째(+1/+0), 중간 삭제(-1/-0), 마지막 삭제(-1/-1), `MemberService` 증감 단위(0건 갱신 시 MEMBER_NOT_FOUND) — T008 파일 내 given 블록 + `common/src/test/kotlin/com/kbap/common/domain/member/MemberServiceReviewCountTest.kt`
- [X] T010 [US4] Green: `MemberJpaRepository` 에 `increaseReviewCounts`/`decreaseReviewCounts` JPQL(@Modifying, 두 컬럼 단일 UPDATE, 감소 가드) + `MemberService` 공개 메서드 — `common/src/main/kotlin/com/kbap/common/domain/member/{MemberJpaRepository,MemberService}.kt`
- [X] T011 [P] [US1] Green: `ErrorCode` 에 REVIEW-001(400)·REVIEW-002(403)·REVIEW-003(400) 추가 — `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt`
- [X] T012 [P] [US1] Green: `UploadedImageRepository` 에 `findByPathIn` 추가 — `common/src/main/kotlin/com/kbap/common/domain/image/UploadedImageRepository.kt` (실경로 확인 후)
- [X] T013 [US1] Green: `ReviewService` 구현 — `createReview`(food 존재→이미지 일괄 소유 검증→국적 스냅샷→저장→count==1 판정→랭킹 증가, `@Transactional`), `updateReview`(조회→본인→이미지 검증→update), `deleteReview`(조회→본인→delete→count==0 판정→랭킹 감소) — `api/src/main/kotlin/com/kbap/api/review/ReviewService.kt`
- [X] T014 [US1] Green: Request/Response DTO — `ReviewCreateRequest`(@NotNull foodId·rating @Min1@Max5, @Size content≤1000·imagePaths≤3)·`ReviewUpdateRequest`·`ReviewResponse`(imageUrls CDN 조립) — `api/src/main/kotlin/com/kbap/api/review/`
- [X] T015 [US1] Green: `ReviewApi`(swagger 전용)+`ReviewController`(@RequestMapping(ApiPaths.V1), POST/PATCH/DELETE `/reviews`, @AuthMemberId, BaseResponse 봉투) — `api/src/main/kotlin/com/kbap/api/review/{ReviewApi,ReviewController}.kt`
- [X] T016 [US1] 검증·Refactor: `./gradlew :api:test --tests "com.kbap.api.review.*" :common:test` Green + `ErrorCodeStatusTest` 통과 확인 후 커밋 → draft PR

**Checkpoint**: 리뷰 데이터 생성 가능 — MVP. 이후 PR3·PR4 는 서로 독립.

---

## Phase 3: PR3 `kb-128-review-lists` — 리뷰 목록 2종 (US3)

**Goal**: GET `/api/v1/foods/{foodId}/reviews`(keyset+국적 필터)·GET `/api/v1/members/me/reviews`. base=PR1. PR2·PR4 와 병렬 가능.

**Independent Test**: 리뷰 21건 시드로 첫 페이지 20건·커서 이어보기·국적 필터·내 리뷰·401 을 MockMvc 단독 검증.

- [ ] T017 [US3] Red: 목록 MockMvc 테스트 작성·실패 확인 — 25건 시드 keyset(20건+nextCursor→5건+hasNext false), countryCode 필터(스냅샷 기준·리뷰 없는 국적=빈 목록), 내 리뷰(본인 것만 최신순), 삭제 리뷰 미노출, 401, 비정상 cursor 400(FOOD-002), 없는 음식 400(FOOD-001) — `api/src/test/kotlin/com/kbap/api/review/ReviewListControllerTest.kt`(또는 ReviewControllerTest 에 given 추가)
- [ ] T018 [US3] Green: `ReviewService` 에 `getFoodReviewPage(foodId, countryCode?, cursor)`·`getMyReviewPage(memberId, cursor)`(@Transactional(readOnly=true), CursorParser·PAGE_SIZE=20·+1건 트릭) + `ReviewListRequest`·`ReviewPage` — `api/src/main/kotlin/com/kbap/api/review/`
- [ ] T019 [US3] Green: `ReviewController`/`ReviewApi` 에 GET `/foods/{foodId}/reviews`·`/members/me/reviews` 추가(응답 `Page<ReviewResponse>` 형태) — `api/src/main/kotlin/com/kbap/api/review/{ReviewApi,ReviewController}.kt`
- [ ] T020 [US3] 검증·Refactor: `./gradlew :api:test --tests "com.kbap.api.review.*"` Green 후 커밋 → draft PR

---

## Phase 4: PR4 `kb-128-review-rating` — 음식 상세 평점 확장 (US2)

**Goal**: `FoodDetailResponse` 에 averageRating·reviewCount·sameCountryAverageRating(null 규칙). base=PR1. PR2·PR3 과 병렬 가능.

**Independent Test**: 국적 다른 리뷰 시드 후 상세를 비회원/국적 보유/국적 미보유로 조회해 3필드 규칙을 단독 검증.

- [ ] T021 [US2] Red: 음식 상세 확장 MockMvc 테스트 작성·실패 확인 — 전체 평균 소수 1자리 반올림(예: 11/3→3.7)·reviewCount, 같은 국적 평균(스냅샷 기준 — 국적 변경 회원의 과거 리뷰 포함), null 3분기(비회원/국적 미보유/해당 국적 리뷰 0건), 리뷰 0건 음식(count 0·avg null), 삭제 리뷰 집계 제외 — `api/src/test/kotlin/com/kbap/api/food/FoodDetailRatingTest.kt`(기존 FoodControllerTest 회귀 유지)
- [ ] T022 [US2] Green: `ReviewService.getFoodRatingSummary(foodId, viewerCountryCode?)` — aggregateRating 2회(전체/국적)·소수 1자리 반올림·`RatingSummary` 결과 타입 — `api/src/main/kotlin/com/kbap/api/review/ReviewService.kt`
- [ ] T023 [US2] Green: `FoodController.detail` 합성(@AuthMemberIdOrNull memberId → `MemberService.getMemberOrNull` 국적 → summary) + `FoodDetailResponse` 3필드 추가 + `FoodApi` 문서 갱신 — `api/src/main/kotlin/com/kbap/api/food/{FoodController,FoodDetailResponse,FoodApi}.kt`
- [ ] T024 [US2] 검증·Refactor: `./gradlew :api:test --tests "com.kbap.api.food.*" --tests "com.kbap.api.review.*"` Green 후 커밋 → draft PR

---

## Final Phase: Polish (마지막 PR 에 포함)

- [ ] T025 전체 빌드 `./gradlew build` Green(Testcontainers·arch 태그 포함) + Swagger UI 에서 Review 태그·음식 상세 스키마 육안 확인
- [ ] T026 Jira KB-128 DoD 체크 갱신·잔여 항목 확인(스탠드업 코멘트는 별도 스킬)

## Dependencies

```
PR1 (T001~T007) ──┬── PR2 (T008~T016, US1+US4)
                  ├── PR3 (T017~T020, US3)   ← PR2 와 독립
                  └── PR4 (T021~T024, US2)   ← PR2·PR3 과 독립
```

- Phase 내부: Red(T001·T002 / T008·T009 / T017 / T021) 가 항상 구현보다 먼저. Red 없이 구현 착수 금지.
- PR2 내부: T010(랭킹)·T011(ErrorCode)·T012(findByPathIn) 는 [P] 병렬 → T013(서비스) 이 셋을 소비 → T014→T015 순.
- PR3·PR4 가 같은 파일(`ReviewService`·`ReviewApi`·`ReviewController`)을 건드리므로 **동시 작업 시 충돌 주의** — 순차(3→4) 권장, 병렬 시 머지 순서 합의.

## Implementation Strategy

- **MVP = PR1+PR2**: 리뷰가 쌓이기 시작하는 최소 단위(랭킹 정합 포함).
- PR1 은 즉시 착수. 각 PR 완료 시 `open-draft-pr-to-develop` 규약으로 draft PR — 머지 전이면 다음 PR 은 스택(base=이전 브랜치)으로 열고 머지 후 base 를 develop 으로 갱신.
- 구현 구동은 `tdd-harness-orchestrator`(test-writer→implementer→리뷰) 로 Phase 단위 진행.
