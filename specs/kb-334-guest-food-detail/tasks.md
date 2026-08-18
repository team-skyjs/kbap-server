# Tasks: 비회원 음식 상세 조회 응답 개편 (KB-334)

**Input**: Design documents from `/specs/kb-334-guest-food-detail/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/food-detail-response.md

**Tests**: Test-First NON-NEEGOTIABLE (헌법 원칙 I) — 스토리마다 실패 테스트를 먼저 쓰고(Red 확인) 구현한다.

**Organization**: 스토리별 그룹. Setup·Foundational 은 기존 프로젝트라 해당 없음 — 바로 스토리 페이즈부터 시작한다.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: User Story 1 - 비회원도 리뷰 평판을 본다 (Priority: P1) 🎯 MVP

**Goal**: 비회원 상세 조회에서 `review.overall` 은 실제 집계값, `review.sameCountry` 는 null 로 내려간다. 회원 리뷰 수치는 불변.

**Independent Test**: 인증 없이 `GET /api/foods/{id}` 호출 → overall 실수치·sameCountry null 확인. 회원 호출 → 기존 수치 그대로.

### Tests (Red — 먼저 작성하고 실패 확인) ⚠️

- [X] T001 [US1] `api/src/test/kotlin/com/kbap/api/food/FoodDetailReviewSectionTest.kt` — 비회원 시나리오 추가: 리뷰 있는 음식을 인증 없이 조회하면 `review.overall` 이 실제 집계값이고 `review.sameCountry` 는 null 이다. 국적 보유 회원 조회는 overall·sameCountry 모두 기존 수치. 국적 없는 회원은 sameCountry `{0.0, 0}`. 작성 후 실행해 Red 확인 (`./gradlew :api:test --tests "*FoodDetailReviewSectionTest"`)

### Implementation (Green)

- [X] T002 [US1] `api/src/main/kotlin/com/kbap/api/food/FoodDetailResponse.kt` — `ReviewSummaryResponse.sameCountry` 를 `ReviewRatingResponse?` 로 완화, `from(rating, sameCountryVisible)` 형태로 비회원 조립 지원(형태는 구현 재량 — null 주입이 가능하면 됨). swagger `@Schema` nullable 반영
- [X] T003 [US1] `api/src/main/kotlin/com/kbap/api/food/FoodController.kt` — `reviewSummaryOf` 개편: 비회원(활성 회원 조회 실패 포함)도 `reviewService.getFoodRatingSummary(foodId, null)` 로 overall 을 집계하고 `sameCountry = null` 로 조립. `blurred()` 조기 반환 제거. T001 Green 확인

**Checkpoint**: 비회원 리뷰 공개 동작 — MVP 완성 지점

---

## Phase 2: User Story 2 - 위험도 null 로 비회원 판별 (Priority: P2)

**Goal**: 비회원 상세 조회의 `overallRiskStatus` 가 SAFE 오표시 대신 null 로 내려간다. `bookmarked: false`·`ingredients: []` 는 유지.

**Independent Test**: 인증 없이 조회 → `overallRiskStatus` null·`bookmarked` false·`ingredients` 빈 배열. 회원 조회 → 기존 위험도 값.

### Tests (Red — 먼저 작성하고 실패 확인) ⚠️

- [X] T004 [US2] `api/src/test/kotlin/com/kbap/api/food/FoodDetailControllerTest.kt` — 비회원 시나리오 추가: 인증 없이 조회하면 `overallRiskStatus` 가 null 이고 `bookmarked` 는 false, `ingredients` 는 빈 배열이다. 기피성분 겹치는 회원 조회는 기존 위험도(DANGER 등) 유지. 작성 후 Red 확인

### Implementation (Green)

- [X] T005 [P] [US2] `api/src/main/kotlin/com/kbap/api/food/GetFoodDetailResult.kt` — `overallRiskStatus: RiskLevel` → `RiskLevel?`
- [X] T006 [US2] `api/src/main/kotlin/com/kbap/api/food/FoodService.kt` — `getDetail` 에서 `input.memberId == null` 이면 `overallRiskStatus = null`, 회원이면 기존 `food.overallRisk(userAvoidedCodes)` 유지
- [X] T007 [US2] `api/src/main/kotlin/com/kbap/api/food/FoodDetailResponse.kt` — `overallRiskStatus: String` → `String?`(`result.overallRiskStatus?.name`), swagger `@Schema` nullable(비회원 null 설명) 반영. T004 Green 확인

**Checkpoint**: US1·US2 각각 독립 동작

---

## Phase 3: User Story 3 - blur 필드 제거 (Priority: P3)

**Goal**: 회원·비회원 응답 모두에서 `review.blur` 필드가 사라진다.

**Independent Test**: 회원·비회원 상세 조회 응답 JSON 에 `blur` 키가 없다.

### Tests (Red — 먼저 작성하고 실패 확인) ⚠️

- [X] T008 [US3] `api/src/test/kotlin/com/kbap/api/food/FoodDetailReviewSectionTest.kt` — 응답 JSON 의 `review` 객체에 `blur` 키가 존재하지 않음을 회원·비회원 각각 단언(기존 blur 단언은 이 시점에 새 계약 단언으로 교체). 작성 후 Red 확인

### Implementation (Green)

- [X] T009 [US3] `api/src/main/kotlin/com/kbap/api/food/FoodDetailResponse.kt` — `ReviewSummaryResponse.blur` 프로퍼티·`blurred()` 팩토리 삭제, 잔여 참조 전수 제거(`rg -n "blur" api/src` 로 0건 확인). T008 Green 확인

**Checkpoint**: 전 스토리 완료

---

## Phase 4: Polish & Cross-Cutting

- [X] T010 [P] `api/src/test/kotlin/com/kbap/api/food/FoodDetailRatingTest.kt` 등 기존 상세 스위트 전체 회귀 — 회원 응답 불변(SC-004) 확인 (`./gradlew :api:test --tests "com.kbap.api.food.*"`)
- [X] T011 전체 빌드·테스트 그린 확인 (`./gradlew test`) 후 quickstart.md 수동 검증 절차로 계약 문서와 실응답 대조

---

## Phase 5: User Story 4 - 재료 전체 공개 + avoidedIngredients 분리 (2026-08-18 확장)

- [X] T012 [US4] `FoodDetailControllerTest` — Red: 상세 응답의 `ingredients` 가 재료 전체(`{code, name, inclusionPercent}` 확률 내림차순, 회원·비회원 공통)이고, 회피 회원은 `avoidedIngredients` 에 `{code, riskStatus}` 교집합, 비회원은 `avoidedIngredients` null 단언
- [X] T013 [US4] `FoodService.getDetail`·`GetFoodDetailResult` — 전체 재료(카탈로그 번역명 조인) + 교집합(회원 List/비회원 null) 이원 산출로 재편
- [X] T014 [US4] `FoodDetailResponse` — `ingredients` 항목 `{code, name, inclusionPercent}` 재정의, `avoidedIngredients: List<{code, riskStatus}>?` 신설, swagger 갱신. T012 Green

## Phase 6: User Story 5 - reviewSummary 개명 + recentReviews 동봉 (2026-08-18 확장)

- [ ] T015 [US5] `FoodDetailReviewSectionTest` — Red: 요약이 `reviewSummary` 키로 내려가고 `review` 키 부재, `recentReviews` 가 최신순 최대 5개(`ReviewResponse` 형태), 비회원 `likedByMe` 전부 false 단언
- [ ] T016 [US5] `ReviewService` — 비회원 조회 가능한 최근 리뷰 로더(`getRecentFoodReviews(foodId, viewerMemberId: Long?, lang)` 5개 고정, 차단/신고 제외는 회원만) 추가
- [ ] T017 [US5] `FoodDetailResponse`·`FoodController` — `review`→`reviewSummary` 개명, `recentReviews: List<ReviewResponse>` 조립, `FoodApi` 문서 갱신. T015 Green

## Phase 7: Polish (확장분)

- [ ] T018 전체 스위트 그린(`./gradlew test`) + 계약 문서와 실응답 대조, PR #167 본문 갱신

---

## Dependencies & Execution Order

- Setup/Foundational 페이즈 없음 — 기존 프로젝트, 신규 인프라 0.
- **US1 → US3 순서 의존**: US3(blur 삭제)은 US1 이 `blurred()` 호출을 제거한 뒤가 자연스럽다(먼저 하면 US1 Red 테스트가 컴파일 에러로 뭉개짐). US2 는 US1·US3 과 파일이 일부 겹치나(FoodDetailResponse) 필드가 달라 순서 무관 — 권장 순서는 P1→P2→P3.
- 각 스토리 안에서 Red(테스트) → Green(구현) 순서는 절대 순서.
- [P] 표시: T005 는 T004 이후 다른 파일과 병렬 가능. T010 은 T011 이전 아무 때나.

## Implementation Strategy

- **MVP = Phase 1**(리뷰 공개) — 이 것만으로도 클라이언트 요청의 핵심 가치 전달.
- 단일 세션 직접 구현(CLAUDE.md — 멀티에이전트 금지). 스토리(논리 단위)마다 커밋.
- 3개 스토리가 같은 응답 계약의 세 측면이라 실제 작업량은 반나절(SP 1) — 페이즈는 검증 단위로만 쓴다.
