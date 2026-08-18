# Tasks: 리뷰 목록 조회 정렬·필터 추가 (KB-338)

**Input**: Design documents from `/specs/kb-338-review-sort-filter/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/review-list-sort-filter.md

**Tests**: Test-First NON-NEGOTIABLE (헌법 원칙 I).

**Organization**: Foundational(커서 코덱·정렬 enum — 전 스토리 공유) → 스토리 3개 → Polish.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Foundational — 정렬 enum·커서 코덱

- [ ] T001 커서 코덱 단위 테스트(Red) — `api/src/test/kotlin/com/kbap/api/review/ReviewListCursorTest.kt`: LATEST 숫자 파싱·지표 정렬 `"{metric}_{id}"` 파싱/인코딩·형식 불일치 FOOD-002·음수/비숫자 FOOD-002
- [ ] T002 `common/src/main/kotlin/com/kbap/common/domain/review/ReviewSort.kt` — 정렬 5종 enum(리포지토리 계약 소유). `api/src/main/kotlin/com/kbap/api/review/ReviewListCursor.kt` — 커서 코덱(Green)

## Phase 2: User Story 1 - 정렬 5종 (Priority: P1) 🎯 MVP

- [ ] T003 [US1] `GlobalReviewListControllerTest`(Red) — 평점↑↓·helpful·음식 리뷰 수 정렬 순서와 동점 최신 우선, sort 생략 = 최신순 불변, 허용값 밖 400 COMMON-002
- [ ] T004 [US1] `common/.../review/ReviewRepositoryCustom(Impl).kt` — 동적 JPQL: 정렬별 order/커서 조건(helpful 은 entity join+group by+having, 음식 리뷰 수는 상관 서브쿼리), (review, metric) 행 반환. 기존 `@Query findReviewPage` 대체·삭제
- [ ] T005 [US1] `ReviewService`·`ReviewController`·`ReviewListRequest`·`ReviewListPage`·`ReviewApi` — sort 파라미터 바인딩→enum, 복합 커서 해석/발급, 리뷰 목록 전용 봉투(nextCursor String), `getRecentFoodReviews` 는 새 계약의 LATEST 경로 재사용. T003 Green

## Phase 3: User Story 2 - 별점 구간 필터 (Priority: P1)

- [ ] T006 [US2] `ReviewListControllerTest`(Red) — 1~3점 구간·단일 점수(min=max)·기존 필터(foodId·countryCode)/정렬과 조합·min>max 400·범위 밖 400
- [ ] T007 [US2] `ReviewListRequest`(@Min/@Max)·컨트롤러 교차 검증·JPQL rating 조건 추가. T006 Green

## Phase 4: User Story 3 - 커서 페이징 정합 (Priority: P1)

- [ ] T008 [US3] `ReviewListControllerTest`(Red) — 동점(같은 별점) 25건이 페이지 경계에 걸린 상태로 RATING_DESC 전량 순회 시 중복·누락 0, HELPFUL_DESC 전량 순회, 형식 불일치 커서(LATEST 커서를 RATING_DESC 에) 400 FOOD-002
- [ ] T009 [US3] 커서 조건 구현 보정(필요시). T008 Green 확인

## Phase 5: Polish & Cross-Cutting

- [ ] T010 [P] 리뷰 스위트 전체 회귀 + 전체 빌드 그린 (`./gradlew test`) — 기존 시나리오 회귀 0(SC-004)
- [ ] T011 `kbap-db-review` 스킬로 신규 동적 쿼리 성능 검토(quickstart 필수 후속) — 인덱스/비정규화 후속 여부 확정
- [ ] T012 swagger 확인·계약 문서와 실응답 대조, PR 생성

---

## Dependencies & Execution Order

- Phase 1(코덱·enum)이 전 스토리의 전제. US1 이 리포지토리·서비스 골격을 세우고 US2·US3 은 그 위에 조건·검증 추가 — 순차 진행(같은 파일들).
- 각 단계 Red → Green 절대 순서.

## Implementation Strategy

- MVP = US1(정렬). US2·US3 은 같은 골격 위의 추가 조건이라 순차로 빠르게.
- 단일 세션 직접 구현, 스토리(논리 단위)마다 커밋.
