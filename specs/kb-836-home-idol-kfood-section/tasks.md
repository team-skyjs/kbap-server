# Tasks: idol-picks 전용 API (KB-836)

**Input**: specs/kb-836-home-idol-kfood-section/ (plan.md, spec.md, contracts/idol-picks.md)

## Phase 1: Setup

- [x] T001 마이그레이션 CREATE TABLE + 시드 36행(korean_name 기준) in api/src/main/resources/db/migration/V2026.10.21.02.20.22__food_idol_video_table.sql

## Phase 2: Foundational

- [x] T002 `FoodIdolVideo` + `IdolVideoPlatform` in common/src/main/kotlin/com/kbap/common/domain/food/model/FoodIdolVideo.kt
- [x] T003 `FoodIdolVideoJpaRepository` in common/src/main/kotlin/com/kbap/common/domain/food/FoodIdolVideoJpaRepository.kt

## Phase 3: User Story 1 — idol-picks 목록 (P1)

- [x] T004 [US1] 응답 DTO in api/src/main/kotlin/com/kbap/api/food/IdolPicksResponse.kt, 요청 DTO `FoodIdolPicksRequest` in FoodRequests.kt
- [x] T005 [US1] `IdolPickService.getIdolPicks`(대표 선정·D7·READY 필터·북마크·평점) in api/src/main/kotlin/com/kbap/api/food/IdolPickService.kt
- [x] T006 [US1] `FoodController.idolPicks` + `FoodApi` Swagger
- [x] T007 [US1] 통합 테스트(빈 목록·lang 누락·정렬·제외·회원/비회원) in api/src/test/kotlin/com/kbap/api/food/IdolPickControllerTest.kt, 시드 in home/HomeTestSeed.kt, TestTables.kt

## Phase 4: User Story 2 — 홈·상세 무변 + R-19 (P1)

- [x] T008 [US2] R-19 4항목(키 집합·금지 키·스키마·홈 1.3/상세 회귀) in IdolPickControllerTest.kt

## Phase 5: Polish

- [x] T009 `./gradlew build` 그린
- [x] T010 로컬 bootRun + curl 실측(헤더 누락 400 포함) 결과 기록

## Dependencies

T001 → T002/T003 → T004 → T005 → T006 → T007/T008 → T009 → T010
