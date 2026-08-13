# Tasks: 음식 목록 응답에 리뷰 평점·개수 추가

**Input**: Design documents from `/specs/kb-324-food-list-rating/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). Every user story MUST include failing tests written BEFORE its implementation (Red → Green → Refactor).

**Organization**: 단일 유저 스토리(US1) — 공유 스키마 확장이라 스토리 분할 없이 한 흐름으로 간다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- 모든 경로는 워크트리 루트(`.claude/worktrees/kb-324-food-list-rating/`) 기준

## Path Conventions

- 기존 모듈러 모놀리스 확장 — `:common`(review 쿼리·food dto) + `:api`(조립 서비스·응답 DTO)

---

## Phase 1: Setup (Shared Infrastructure)

**없음** — 신규 모듈·의존성·스키마·설정 없음(plan.md).

---

## Phase 2: Foundational (Blocking Prerequisites)

**없음** — 기반(집계 선례 `aggregateRating`·`@SQLRestriction`·공유 뷰 조립 구조) 전부 기존 코드에 존재.

**Checkpoint**: 바로 US1 시작 가능

---

## Phase 3: User Story 1 - 음식 카드에서 평점·리뷰 수 확인 (Priority: P1) 🎯 MVP

**Goal**: `FoodSummaryResponse`(목록·검색·홈·북마크·어드민 공유)에 `averageRating`(0건 null·소수 1자리)·`reviewCount` 추가 — 배치 집계 쿼리 1회, 상세 overall 과 값 일치.

**Independent Test**: 리뷰 있는 음식·없는 음식을 시드하고 목록/검색/홈/북마크 응답에서 두 필드와 상세 overall 일치를 확인.

### Tests for User Story 1 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T001 [P] [US1] Red: `common/src/test/kotlin/com/kbap/common/domain/review/ReviewJpaRepositoryTest.kt` 에 `aggregateRatingsByFoodIds` given 추가 — 여러 음식 배치 집계(평균·건수), 소프트 삭제 리뷰 제외, 리뷰 없는 foodId 는 결과 행 없음, 요청 목록 밖 음식 미포함. 컴파일 실패(Red) 확인
- [X] T002 [P] [US1] Red: `api/src/test/kotlin/com/kbap/api/food/FoodListControllerTest.kt` 보강 — 목록 아이템에 `averageRating`(4.3 반올림)·`reviewCount` 포함, 리뷰 0건 음식은 `averageRating` null·`reviewCount` 0, 기존 필드 유지. Red 확인
- [X] T003 [P] [US1] Red: `api/src/test/kotlin/com/kbap/api/food/FoodSearchControllerTest.kt` 보강 — 검색 결과 아이템에 두 필드 포함. Red 확인
- [X] T004 [P] [US1] Red: `api/src/test/kotlin/com/kbap/api/home/HomeControllerTest.kt` 보강 — `popularFoods`·`recentScans` 아이템에 두 필드 포함. Red 확인
- [X] T005 [P] [US1] Red: `api/src/test/kotlin/com/kbap/api/bookmark/BookmarkControllerTest.kt` 보강 — 북마크 목록 아이템에 두 필드 포함. Red 확인

### Implementation for User Story 1

- [X] T006 [US1] Green: `common/src/main/kotlin/com/kbap/common/domain/review/ReviewJpaRepository.kt` 에 `FoodRatingAggregate` projection(foodId·average·reviewCount) + `aggregateRatingsByFoodIds(foodIds)` JPQL(group by) 추가. T001 Green 확인
- [X] T007 [US1] Green: (계획 변경) `FoodSummaryView` 는 무변경 — 합류를 응답 조립 계층(`FoodSummaryResponse.from` + `ReviewService.getFoodRatings`)으로 이동해 도메인 경계·시그니처 파급 최소화
- [X] T008 [US1] Green: `common/src/main/kotlin/com/kbap/common/domain/food/FoodService.kt` `foodPage` 조립에 배치 집계 합류(소수 1자리 반올림 — 상세 `roundToFirstDecimal` 과 동일 공식) — 목록·검색 커버. T002·T003 Green 확인
- [X] T009 [P] [US1] Green: `api/src/main/kotlin/com/kbap/api/home/HomeService.kt` 인기·최근 스캔 조립에 합류. T004 Green 확인
- [X] T010 [P] [US1] Green: `api/src/main/kotlin/com/kbap/api/bookmark/BookmarkService.kt` 북마크 목록 조립에 합류. T005 Green 확인
- [X] T011 [P] [US1] Green: (스코프 제외) 어드민은 별도 `AdminFoodSummaryView` 스키마라 `FoodSummaryResponse` 를 공유하지 않음 — 적용 대상 아님
- [X] T012 [US1] Green: `api/src/main/kotlin/com/kbap/api/food/FoodSummaryResponse.kt` 에 두 필드 추가(view 미러링) + swagger `@Schema` 설명. 전 Red 테스트 Green 확인
- [X] T013 [US1] Refactor: 조립처 5곳의 집계 맵 생성 중복을 헬퍼로 정리할 가치가 있는지 판단 후(2곳 이상 동일 3줄이면 정리) `./gradlew :common:test :api:test` Green 유지 확인

**Checkpoint**: 목록·검색·홈·북마크·어드민 전 화면 아이템에 평점·리뷰 수 — US1 완결 (MVP)

---

## Phase 4: Polish & Cross-Cutting Concerns

- [X] T014 계약 대조: `specs/kb-324-food-list-rating/contracts/food-list-rating.md` 와 구현 일치 확인(0건 null·반올림·상세 일치)
- [X] T015 전체 검증: `./gradlew build`(ArchUnit 포함 — food→review 도메인 방향 미추가 확인) Green

---

## Dependencies & Execution Order

- **Setup/Foundational**: 태스크 없음
- **US1**: T001~T005(Red, 전부 병렬 — 서로 다른 파일) → T006(쿼리)→T007(view)→T008(foodPage) → T009∥T010∥T011(서로 다른 서비스) → T012(응답 DTO) → T013 → T014→T015
  - T007 이 view 시그니처를 바꾸므로 T008~T012 는 T007 이후(컴파일 의존)

### Parallel Opportunities

- Red 5개 전부 병렬(T001~T005), Green 조립처 3개 병렬(T009~T011)

---

## Implementation Strategy

단일 스토리 — Red 일괄 확인 후 쿼리→view→조립처→응답 순으로 Green. 논리 단위(쿼리/조립/응답)마다 커밋.

## Notes

- 모든 테스트 Kotest BehaviorSpec(한국어 given/when/then), Red 는 실제 실행으로 확인
- Kotlin 소스 주석 금지(2026-08-11 컨벤션)
- 스키마 변경 없음 — Flyway 마이그레이션 금지
