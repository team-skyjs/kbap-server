# Tasks: 음식 리스트·상세 조회 응답에 북마크 여부(bookmarked) 포함

**Input**: Design documents from `specs/kb-153-food-bookmark-flag/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/is-bookmarked-response-contract.md, quickstart.md

**Tests**: Test-First **NON-NEGOTIABLE** (헌법 원칙 I). Phase 2(도메인 창구)와 US1(API 계약)은 각각 Red → Green 사이클. US2 는 US1 구현이 비회원 경로까지 함께 만들므로(창구의 null 처리) 회귀 pin 성격 — 즉시 통과가 정상.

**Organization**: user story 별 페이즈. US1 이 MVP.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

해당 없음 — 신규 모듈·의존성·인프라 0 (기존 도메인/DTO 확장 + 컨트롤러 병합).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 도메인 창구 `BookmarkService.findBookmarkedFoodIds` — 두 user story 가 모두 이 창구를 쓴다.

- [X] T001 [P] Red: `domain/bookmark/src/test/kotlin/com/kbap/domain/bookmark/BookmarkServiceTest.kt` 에 `findBookmarkedFoodIds` 시나리오 작성 — (1) 회원+요청 집합 → 북마크한 foodId 교집합만 반환, (2) memberId null → emptySet(비회원), (3) foodIds 빈 집합 → emptySet, (4) unbookmark(소프트삭제) 후 해당 foodId 제외. 기존 테스트 픽스처(Testcontainers·시드) 패턴 재사용
- [X] T002 Red 확인: `./gradlew :domain:bookmark:test` — 신규 시나리오가 컴파일 실패 또는 실패(Red)임을 확인
- [X] T003 Green: `domain/bookmark/src/main/kotlin/com/kbap/domain/bookmark/BookmarkJpaRepository.kt` 에 `findByMemberIdAndFoodIdIn(memberId: Long, foodIds: Collection<Long>): List<Bookmark>` derived query 추가(internal, status 조건 금지 — `@SQLRestriction` 자동) + `domain/bookmark/src/main/kotlin/com/kbap/domain/bookmark/BookmarkService.kt` 에 `@Transactional(readOnly = true) fun findBookmarkedFoodIds(memberId: Long?, foodIds: Collection<Long>): Set<Long>` 구현 — null 회원/빈 집합이면 쿼리 없이 emptySet
- [X] T004 Green 확인: `./gradlew :domain:bookmark:test` 전부 통과

**Checkpoint**: 창구 완성 — API 계약 작업 시작 가능

---

## Phase 3: User Story 1 - 회원이 리스트·상세에서 자기 북마크 상태를 바로 확인 (Priority: P1) 🎯 MVP

**Goal**: 회원 조회 응답(리스트·검색·상세·북마크 목록)에 bookmarked 가 실제 북마크 상태로 내려온다.

**Independent Test**: 회원 토큰으로 북마크 혼재 상태를 만들어 리스트·상세 조회 — 항목별 true/false 가 실제와 일치.

### Red — API 계약 테스트 (구현 금지, 작성 후 실패 확인)

- [X] T005 [P] [US1] `app/api/src/test/kotlin/com/kbap/app/api/food/FoodListControllerTest.kt` — 회원이 페이지 내 일부만 북마크한 상태에서 리스트 조회 시 항목별 bookmarked true/false 혼재 검증(인증·북마크 시딩은 `BookmarkControllerTest` 의 `TokenIssuer` 헬퍼·등록 API 패턴 재사용)
- [X] T006 [P] [US1] `app/api/src/test/kotlin/com/kbap/app/api/food/FoodDetailControllerTest.kt` — 회원 북마크 후 상세 bookmarked=true, unbookmark 후 재조회 시 false 검증
- [X] T007 [P] [US1] `app/api/src/test/kotlin/com/kbap/app/api/food/FoodSearchControllerTest.kt` — 회원 검색 결과 항목의 bookmarked 가 실제 여부 반영 검증(구조 공유 — 동일 규칙)
- [X] T008 [P] [US1] `app/api/src/test/kotlin/com/kbap/app/api/bookmark/BookmarkControllerTest.kt` — 북마크 목록 조회 항목의 bookmarked 가 전부 true 검증
- [X] T009 [US1] Red 확인: `./gradlew :app:api:test --tests "com.kbap.app.api.food.FoodListControllerTest" --tests "com.kbap.app.api.food.FoodDetailControllerTest" --tests "com.kbap.app.api.food.FoodSearchControllerTest" --tests "com.kbap.app.api.bookmark.BookmarkControllerTest"` — 신규 케이스가 **실패**(필드 부재)함을 확인

### Green — 최소 구현

- [X] T010 [US1] API DTO 필드 추가 — `app/api/src/main/kotlin/com/kbap/app/api/food/FoodSummaryResponse.kt`·`FoodDetailResponse.kt` 에 `bookmarked: Boolean`(non-null) + `from(...)` 에 `bookmarked` 파라미터(기본값 없음), `FoodDetailResponse` 쪽은 `@field:Schema(description = "조회 회원의 북마크 여부. 비회원 조회는 항상 false", example = "true")`
- [X] T011 [US1] 컨트롤러 병합 — `app/api/src/main/kotlin/com/kbap/app/api/food/FoodController.kt` 에 `BookmarkService` 주입: browse·search 는 `findBookmarkedFoodIds(memberId, items.map { it.foodId })` 결과로 항목별 `foodId in bookmarkedIds`, detail 은 `foodId in findBookmarkedFoodIds(memberId, listOf(foodId))`. `app/api/src/main/kotlin/com/kbap/app/api/bookmark/BookmarkController.kt` 목록은 `from(view, bookmarked = true)` 상수
- [X] T012 [US1] Green 확인: T009 와 동일 명령 전부 통과

**Checkpoint**: MVP 완료 — 회원 경로 완결(+비회원 경로도 창구 null 처리로 함께 구현됨)

---

## Phase 4: User Story 2 - 비회원 조회는 항상 false 고정 (Priority: P2)

**Goal**: 비회원(인증 없음) 응답의 bookmarked 는 항상 false 이고 필드 누락이 없다 — US1 구현이 이미 만든 경로의 계약 pin.

**Independent Test**: 인증 헤더 없이 리스트·검색·상세 조회 — 모든 bookmarked=false, 필드 항상 존재.

- [X] T013 [P] [US2] `app/api/src/test/kotlin/com/kbap/app/api/food/FoodListControllerTest.kt`·`FoodSearchControllerTest.kt` — 어떤 회원이 북마크한 음식이 있어도 비회원 리스트·검색 조회는 전 항목 bookmarked=false(필드 존재) pin
- [X] T014 [P] [US2] `app/api/src/test/kotlin/com/kbap/app/api/food/FoodDetailControllerTest.kt` — (1) 비회원 상세 bookmarked=false, (2) 회원 A 가 북마크한 음식을 회원 B 가 조회하면 false(조회자 본인 기준) pin
- [X] T015 [US2] 실행 확인: `./gradlew :app:api:test --tests "com.kbap.app.api.food.FoodListControllerTest" --tests "com.kbap.app.api.food.FoodSearchControllerTest" --tests "com.kbap.app.api.food.FoodDetailControllerTest"` 통과(즉시 통과가 정상 — 회귀 pin)

**Checkpoint**: 비회원 false·조회자 본인 기준 계약이 테스트로 고정됨

---

## Phase 5: Polish & Cross-Cutting

- [X] T016 [P] Swagger 문구 — `app/api/src/main/kotlin/com/kbap/app/api/food/FoodApi.kt`(리스트·검색·상세)·`app/api/src/main/kotlin/com/kbap/app/api/bookmark/BookmarkApi.kt`(목록) 응답 설명에 bookmarked 규칙 1줄 추가("비회원은 항상 false", 북마크 목록은 "항상 true")
- [X] T017 전체 회귀: `./gradlew test` (ArchUnit 포함) — SC-004 기존 동작 회귀 0건 확인
- [X] T018 quickstart.md 수동 확인 — local 부트 후 비회원 리스트 false, 회원 등록→상세 true, Swagger UI 문구 확인

---

## Dependencies

```text
Phase 2: T001 → T002 (Red) → T003 → T004 (Green)
  └─ Phase 3 US1: T005~T008 [P] → T009 (Red) → T010 → T011 → T012 (Green)
       └─ Phase 4 US2: T013~T014 [P] → T015 (US1 과 동일 테스트 파일 — US1 완료 후)
            └─ Phase 5: T016 [P] (문서 — T010 이후 언제든) → T017 → T018
```

- **Phase 2 → US1**: 컨트롤러 병합(T011)이 창구(T003)를 호출. 단 US1 의 Red 작성(T005~T008)은 T001 과 병렬 가능(다른 파일).
- **US1 → US2**: 같은 테스트 파일 3개를 수정하므로 순차. 구현 의존은 없음(경로는 US1 에서 완성).

## Parallel Execution Examples

- 최초 병렬: T001(도메인 Red) + T005 + T006 + T007 + T008(API Red — 파일 5개 상호 독립)
- T010 완료 후: T016(Swagger 문구) 병렬 가능
- 순차 필수: T002→T003→T004, T009→T010→T011→T012, T013~T015 는 T012 후

## Implementation Strategy

**MVP = Phase 2 + Phase 3 (T001~T012)** — 회원·비회원 경로가 모두 이 단계에서 구현 완료되고(창구의 null 처리), US2 는 계약 pin, Polish 는 문서·회귀다. 커밋은 논리 단위(도메인 창구 → US1 Red+Green → US2 pin → 문서·회귀).
