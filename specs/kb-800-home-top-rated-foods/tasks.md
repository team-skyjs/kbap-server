# Tasks: 홈 topRatedFoods (KB-800)

**Input**: specs/kb-800-home-top-rated-foods/ (plan.md, spec.md, contracts/home-v1.3.md)

## Phase 1: Setup

- [x] T001 `ApiVersions.HOME_TOP_RATED = "1.3"` 추가 in api/src/main/kotlin/com/kbap/api/core/ApiVersions.kt, 지원 버전 등록 in api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt

## Phase 2: Foundational

- [x] T002 `TopRatedFoodAggregate` 프로젝션 + native `findTopRatedFoods(size)` in common/src/main/kotlin/com/kbap/common/domain/review/ReviewJpaRepository.kt
- [x] T003 `ReviewService.getTopRatedFoods(size)` + `TopRatedFoodRating` in api/src/main/kotlin/com/kbap/api/review/ReviewService.kt
- [x] T004 [P] `FoodService.getFoodSummariesInOrder(memberId, lang, ids)` in api/src/main/kotlin/com/kbap/api/food/FoodService.kt

## Phase 3: User Story 1 — 1.3 에서 topRatedFoods (P1)

**Independent Test**: 1.3 헤더로 홈 호출 → 정렬·값·null 규칙·비회원 확인

- [x] T005 [US1] `HomeService.getHome(..., topRated)` 집계·id 합류·`TopRatedFoodView` in api/src/main/kotlin/com/kbap/api/home/HomeService.kt, HomeResult.kt
- [x] T006 [US1] `TopRatedFoodResponse`(@JsonUnwrapped) + `topRatedFoods` 필드(NON_NULL) in api/src/main/kotlin/com/kbap/api/home/HomeResponse.kt
- [x] T007 [US1] 컨트롤러 분기 in api/src/main/kotlin/com/kbap/api/home/HomeController.kt, Swagger 설명 in HomeApi.kt
- [x] T008 [US1] 통합 테스트(정렬·20개 컷·null 세부·제외 조건·비회원·반올림) in api/src/test/kotlin/com/kbap/api/home/HomeTopRatedTest.kt, 시드 in HomeTestSeed.kt

## Phase 4: User Story 2 — 1.2 이하 무변 (P1)

- [x] T009 [US2] 1.2 요청에 `topRatedFoods` 키 부재 테스트 in api/src/test/kotlin/com/kbap/api/home/HomeTopRatedTest.kt

## Phase 5: Polish

- [x] T010 `./gradlew build` 그린

## Dependencies

T001 → T002 → T003/T004 → T005 → T006 → T007 → T008/T009 → T010
