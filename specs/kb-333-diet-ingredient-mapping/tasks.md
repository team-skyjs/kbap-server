# Tasks: diet 카테고리별 회피 재료 매핑 조회

**Input**: Design documents from `specs/kb-333-diet-ingredient-mapping/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md(번호→코드 변환표 = 매핑 단일 출처), contracts/diet-api.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 테스트는 Kotest BehaviorSpec(given/when/then 한국어)으로 구현보다 먼저 작성하고 Red 를 확인한다.

**Organization**: 기존 프로젝트 기능 추가라 Setup/Foundational 단계가 없다 — 스토리 2개 단계로 바로 간다. 모든 경로는 워크트리 루트 기준.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: User Story 1 - 식단 유형별 회피 재료 확인 (Priority: P1) 🎯 MVP

**Goal**: `GET /api/ingredients/diets` 가 15종 카테고리 전체와 카테고리별 매핑 재료(id·이름)를 기획 표와 정확히 일치하게 응답한다(JWT 보호).

**Independent Test**: `DietCategoryMappingSyncTest`(시드 SQL 파싱 전수 비교) + `IngredientControllerTest` diets 시나리오(200 구조·401·400)만으로 완결 검증.

### Tests for User Story 1 (Test-First — 작성 직후 Red 확인) ⚠️

- [X] T001 [P] [US1] 매핑 정합 테스트 작성: 기획 번호표(15종 전수)를 픽스처로 들고 시드 SQL(`db/migration/V2026.07.16.21.38.42__seed_avoidance_catalog.sql`)을 1-based 행 순서로 파싱(기존 `IngredientCatalogSeedSyncTest` regex 재사용)해 번호→코드 해석 결과와 `DietCategory` 매핑의 전수 일치를 검증 — `api/src/test/kotlin/com/kbap/api/ingredient/DietCategoryMappingSyncTest.kt` (신규. `DietCategory` 미존재로 컴파일 실패 = Red 확인)
- [X] T002 [P] [US1] MockMvc diets 시나리오 추가: ①정상 조회 200 — `payload.diets` 15종·첫 항목 VEGAN(41종)·`GLUTEN_FREE` 재료명 4건(밀·보리·호밀·귀리, id 오름차순), ②토큰 없음 401, ③`lang` 누락 400 COMMON-002 — `api/src/test/kotlin/com/kbap/api/ingredient/IngredientControllerTest.kt` (수정. 핸들러 미존재로 실패 = Red 확인. 계획의 "X-API-Version 누락 400" 시나리오는 제외 — 무버전 매핑은 헤더 미전송을 허용하는 것이 기존 계약(기존 재료 목록 테스트와 동일). 절대 id(26 등) 검증은 테스트 시드가 id 를 재발번해 재료명 검증으로 대체)

### Implementation for User Story 1

- [X] T003 [US1] `DietCategory` enum 구현: 15종, 필드 `koreanName`·`avoidedIngredients: Set<IngredientCode>`, data-model.md 변환표 그대로, 선언 순서 = 기획 표 순서 — `api/src/main/kotlin/com/kbap/api/ingredient/DietCategory.kt` (신규 → T001 Green)
- [X] T004 [P] [US1] 요청·응답 DTO 작성: `DietListRequest`(`lang` `@field:NotBlank`, 기존 `IngredientListRequest` 규약 동일) / `DietListResponse` → `DietItemResponse(code, name, ingredients)` → `DietIngredientResponse(id, name)`, swagger `@Schema` 포함 — `api/src/main/kotlin/com/kbap/api/ingredient/DietListRequest.kt`·`DietListResponse.kt` (신규)
- [X] T005 [US1] `IngredientQueryService.getDietIngredientMappings(lang)` 구현: `@Transactional(readOnly = true)`, `findAll()` 1회 → `code→entity` 맵 → 카테고리별 (id, `displayName(lang)`) 목록·재료 id 오름차순 조립 — `api/src/main/kotlin/com/kbap/api/ingredient/IngredientQueryService.kt` (수정, T003·T004 의존)
- [X] T006 [US1] 컨트롤러·swagger 추가: `IngredientController` 에 `@GetMapping("/diets")` 핸들러(`@Valid @ModelAttribute DietListRequest`, `LanguageCode.from` 변환, `BaseResponse.ok`), `IngredientApi` 에 문서 애너테이션만(`@Operation`·`@ApiResponses`·`@SecurityRequirement`) — `api/src/main/kotlin/com/kbap/api/ingredient/IngredientController.kt`·`IngredientApi.kt` (수정, T005 의존)
- [X] T007 [US1] JWT 보호 경로 등록: `jwtAuthenticationFilterRegistration` 의 `addUrlPatterns` 에 `"${ApiPaths.API}/ingredients/diets"` 추가(기존 `/api/ingredients` 는 공개 유지) — `api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt` (수정 → T002 의 401 시나리오 Green)
- [X] T008 [US1] Green 확인: `./gradlew :api:test --tests "com.kbap.api.ingredient.DietCategoryMappingSyncTest" --tests "com.kbap.api.ingredient.IngredientControllerTest"` 통과 확인 후 리팩터(주석 0·네이밍 규약 점검)

**Checkpoint**: US1 단독으로 배포 가능한 MVP — 매핑 조회가 기획 표와 일치.

---

## Phase 2: User Story 2 - 다국어 사용자 재료명 표시 (Priority: P2)

**Goal**: 같은 엔드포인트에서 재료명이 요청 `lang` 을 따라가고, 미지원 코드는 en·번역 부재는 ko 로 폴백한다(헌법 V).

**Independent Test**: lang 만 바꾼 동일 호출 2회로 재료명 변화·폴백을 확인.

### Tests for User Story 2 (Test-First — 작성 직후 Red 확인) ⚠️

- [X] T009 [US2] lang 폴백 시나리오 추가: ①`lang=en` 시 재료명 영어 표시(예: id 26 → "Wheat"), ②미지원 코드(`fr`) → en 표시명, ③`lang=ko` → 한국어 표시명 — `api/src/test/kotlin/com/kbap/api/ingredient/IngredientControllerTest.kt` (수정. T005 가 `displayName` 을 재사용하므로 곧장 Green 일 수 있음 — 그 경우 Red 생략 사유를 커밋 메시지에 남기고 회귀 고정 테스트로 유지)

### Implementation for User Story 2

- [X] T010 [US2] T009 실패 시에만 폴백 로직 보정(`displayName`/`LanguageCode.from` 재사용 원칙 유지 — 재구현 금지) 후 Green 확인: `./gradlew :api:test --tests "com.kbap.api.ingredient.IngredientControllerTest"` — `api/src/main/kotlin/com/kbap/api/ingredient/IngredientQueryService.kt`

**Checkpoint**: US1·US2 모두 독립 검증 완료.

---

## Phase 3: Polish & Cross-Cutting Concerns

- [X] T011 전체 빌드·회귀 확인: `./gradlew build` (ArchUnit `ModuleBoundaryTest` 포함) — 실패 시 원인 수정 후 재실행
- [X] T012 quickstart.md 의 curl 기대값(카테고리 15종·GLUTEN_FREE 4건·NO_ALCOHOL 3건)과 실제 응답 대조 — `specs/kb-333-diet-ingredient-mapping/quickstart.md`

---

## Dependencies & Execution Order

- **US1 (Phase 1)**: 선행 없음. 내부 순서 — T001·T002(Red, 병렬) → T003(T001 Green) → T004 → T005 → T006 → T007 → T008. T004 는 T003 과 병렬 가능.
- **US2 (Phase 2)**: US1 완료 후(같은 엔드포인트 위 시나리오 추가). T009 → T010.
- **Polish (Phase 3)**: US1·US2 완료 후. T011 → T012.

### Parallel Opportunities

- T001 ∥ T002 (다른 파일의 Red 테스트)
- T003 ∥ T004 (enum vs DTO — 다른 파일)

---

## Implementation Strategy

**MVP = US1 만** (T001~T008): 매핑 조회가 기획 표와 일치하면 배포 가치가 있다. US2 는 폴백 회귀 고정이 본질이라 작음(T009~T010). 각 task/논리 단위마다 커밋한다.
