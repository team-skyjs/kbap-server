---
description: "Task list for 음식 상세 조회 foodId 정합 (menuName → foodId)"
---

# Tasks: 음식 상세 조회 foodId 정합 (menuName → foodId)

**Input**: Design documents from `specs/kb-98-food-detail-by-food-id/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/food-detail-by-id.md

**Tests**: Test-First는 **NON-NEGOTIABLE** (헌법 원칙 I). 각 스토리는 구현 전 실패 테스트를 먼저 작성한다(Red → Green → Refactor). 테스트는 Kotest **BehaviorSpec**(given/when/then 한국어).

**Organization**: 사용자 스토리별로 그룹핑. US1(성공 조회)·US2(실패 처리)는 web 계층에서 각각 독립 검증 가능하다. 조회 진입점 교체가 작아 두 스토리가 근접 배포되지만, 성공/실패 경로를 분리 테스트한다.

**참고**: 이 기능은 기존 모듈·클래스 재사용이라 새 프로젝트 셋업/스캐폴딩이 없다. Setup은 베이스라인 확인만 한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 선행 의존 없음)
- **[Story]**: US1/US2
- 파일 경로는 worktree 루트 기준 상대경로.

---

## Phase 1: Setup (Shared)

**Purpose**: 변경 전 그린 베이스라인 확인(이후 Red가 의미 있도록).

- [x] T001 변경 전 관련 모듈 테스트가 통과함을 확인한다: `./gradlew :app:api:test :infra:persistence:test :application:client:test`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: US1·US2가 공통으로 딛는 조회 포트/어댑터/입력 DTO 배선. 이 단계 완료 전 스토리 구현 불가.

**⚠️ CRITICAL**: 아래 완료 전 US1/US2 구현을 시작하지 않는다.

- [x] T002 [P] 영속 어댑터 실패 테스트 작성: `findById(id)` 가 (a) 활성 음식을 성분 포함해 반환, (b) 미존재 id는 null, (c) 소프트삭제(status=DELETED) id는 null 임을 검증 — `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/food/FoodRepositoryAdapterTest.kt` (BehaviorSpec, Testcontainers). **(c) 케이스를 위해 `status='DELETED'` 로 food 를 심는 시드 경로가 필요하다** — 기존 `FoodTestSeed`/어댑터 테스트 시드는 항상 `status='ACTIVE'` 라 DELETED 행을 못 만든다. status 를 넘길 수 있는 시드 헬퍼(예: `seedDeletedFood(id)` 또는 status 파라미터화)를 추가해 실제로 DELETED 행을 심고 `@SQLRestriction("status='ACTIVE'")` 자동 제외를 검증한다(안 그러면 소프트삭제 경로가 조용히 미검증). 작성 후 실패(Red) 확인.
- [x] T003 `FoodRepository` 포트에 `fun findById(id: Long): Food?` 추가 — `core/food/src/main/kotlin/com/meogo/core/food/FoodRepository.kt`
- [x] T004 `FoodRepositoryAdapter.findById` 구현: 기존 fetch-join `foodJpaRepository.findByIdInWithAvoidanceSubstances(listOf(id)).firstOrNull()?.toDomain()` 재사용(새 JPA 쿼리 금지) — `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/FoodRepositoryAdapter.kt`. T002 그린 확인.
- [x] T005 `GetFoodDetailInput` 의 `menuName: String` → `foodId: Long` 로 교체 — `application/client/src/main/kotlin/com/meogo/application/client/food/dto/GetFoodDetailInput.kt`

**Checkpoint**: foodId 조회 포트·어댑터·입력 DTO 준비 완료.

---

## Phase 3: User Story 1 - 목록/검색에서 고른 음식의 상세를 foodId로 연다 (Priority: P1) 🎯 MVP

**Goal**: `GET /api/v1/foods/{foodId}?lang=` 로 해당 음식의 상세(기존 스키마)를 요청 언어로 정확히 반환.

**Independent Test**: 시드된 음식(id=1 된장찌개)을 `GET /api/v1/foods/1?lang=en` 로 조회해 200 + 기존 payload 스키마·필드 값을 받는다.

### Tests for User Story 1 (Test-First: 먼저 작성, FAIL 확인) ⚠️

- [x] T006 [P] [US1] 유스케이스 단위 테스트를 foodId 기준으로 갱신: fake `FoodRepository.findById` 로 `getDetail(GetFoodDetailInput(foodId=...))` 성공 경로(이름·설명·맵기·성분·위험도·lang 폴백) 검증 — `application/client/src/test/kotlin/com/meogo/application/client/food/usecase/GetFoodDetailUseCaseTest.kt`. Red 확인.
- [x] T007 [P] [US1] web 통합 테스트를 `/api/v1/foods/{id}` 성공 경로로 재작성: `FoodDetailControllerTest`(성공+trim 제거)·`FoodDetailLangTest`(lang별 응답)·`FoodDetailDescriptionTest`(설명 폴백) 의 요청을 `param("menuName", …)` → path `/{seedId}` 로 교체 — `app/api/src/test/kotlin/com/meogo/app/api/food/{FoodDetailControllerTest,FoodDetailLangTest,FoodDetailDescriptionTest}.kt`. Red 확인.

### Implementation for User Story 1

- [x] T008 [US1] `GetFoodDetailUseCase.getDetail`: `foodRepository.findByKoreanName(input.menuName.trim())` → `foodRepository.findById(input.foodId)` (없으면 `FoodException(FoodErrorCode.NOT_FOUND)` 유지) — `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/GetFoodDetailUseCase.kt`
- [x] T009 [US1] `FoodDetailController`: `@GetMapping("/{foodId}")` + `@PathVariable foodId: Long` 로 교체, `menuName` blank 검증 제거, `GetFoodDetailInput(foodId = foodId, lang = lang)` 전달 — `app/api/src/main/kotlin/com/meogo/app/api/food/FoodDetailController.kt`
- [x] T010 [US1] `FoodDetailApi` Swagger 문서 갱신: `@Tag`/`@Operation`/파라미터를 menuName → `{foodId}` path 로 재작성(예시·설명·400 시맨틱) — `app/api/src/main/kotlin/com/meogo/app/api/food/FoodDetailApi.kt`. T006·T007 그린 확인.

**Checkpoint**: US1 단독 동작·검증 가능(성공 조회).

---

## Phase 4: User Story 2 - 없거나 삭제/형식오류 foodId는 400으로 실패한다 (Priority: P2)

**Goal**: 미존재·소프트삭제·비숫자 foodId 조회를 모두 400(잘못된 요청) + 실패 메시지로 응답. (미지원 lang 400은 기존 동작 유지.)

**Independent Test**: `GET /api/v1/foods/999999`(미존재)·삭제 음식 id·`/api/v1/foods/abc`(비숫자)를 각각 400으로 받는다.

### Tests for User Story 2 (Test-First: 먼저 작성, FAIL 확인) ⚠️

- [x] T011 [P] [US2] web 통합 실패 테스트 작성: 미존재 foodId → 400`해당 음식 정보 없음`, 소프트삭제 foodId → 400, 비숫자 foodId(`/foods/abc`) → 400 — `app/api/src/test/kotlin/com/meogo/app/api/food/FoodDetailErrorTest.kt`. **소프트삭제 케이스는 `FoodTestSeed` 에 `status='DELETED'` food 를 심는 헬퍼(T002 와 동일 요건)를 써서 실제 DELETED 행을 조회해야 한다** — 안 그러면 미존재 케이스와 구분되지 않아 소프트삭제 경로가 조용히 미검증된다. 비숫자 foodId 실패 메시지는 `GlobalExceptionHandler` 관례대로 `"잘못된 요청입니다"` 로 확정. (미존재·삭제는 T004+T008 로 이미 그린, 비숫자는 T013 전까지 Red.)
- [x] T012 [P] [US2] 언어 오류 테스트를 foodId path 로 갱신(미지원 lang 400 유지) — `app/api/src/test/kotlin/com/meogo/app/api/food/FoodDetailLanguageErrorTest.kt`. Red 확인.

### Implementation for User Story 2

- [x] T013 [US2] `GlobalExceptionHandler` 에 `MethodArgumentTypeMismatchException` 핸들러 추가 → 400 `BaseResponse.fail(...)` 봉투로 응답(비숫자 path variable) — `app/api/src/main/kotlin/com/meogo/app/api/common/GlobalExceptionHandler.kt`. T011·T012 그린 확인.

**Checkpoint**: US1·US2 모두 독립 동작·검증.

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: 대체된 menuName 조회 경로 정리 및 전체 검증.

- [x] T014 [P] 대체돼 사용처가 없어진 `findByKoreanName` 제거(사전 재확인: 상세 usecase 외 프로덕션 소비자 없음): 포트 메서드(`core/food/.../FoodRepository.kt`)·어댑터 override(`infra/persistence/.../FoodRepositoryAdapter.kt`)·JPA 쿼리 `findByKoreanNameWithAvoidanceSubstances`(`infra/persistence/.../FoodJpaRepository.kt`) 삭제하고, 포트를 구현하는 fake/테스트에서 해당 override·케이스 제거 — `application/client/src/test/.../GetFoodDetailUseCaseTest.kt`·`application/client/src/test/.../BrowseMenusUseCaseTest.kt`·`infra/persistence/src/test/.../FoodRepositoryAdapterTest.kt`.
- [x] T015 [P] `CorsConfigTest` 의 CORS 프리플라이트 프로브 경로 `OPTIONS /api/v1/foods/detail` → 유효 경로(`/api/v1/foods/1`)로 교체 — `app/api/src/test/kotlin/com/meogo/app/api/config/CorsConfigTest.kt`
- [x] T016 quickstart.md 검증 실행 + 전체 빌드: `./gradlew build` 그린 확인.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 의존 없음.
- **Foundational (Phase 2)**: Setup 후. US1·US2를 BLOCK.
- **US1 (Phase 3)**: Foundational 후. MVP.
- **US2 (Phase 4)**: Foundational 후. 미존재/삭제 케이스는 Foundational+US1 구현으로 이미 충족되고, 비숫자 처리(T013)만 US2 고유. US1과 병행 가능하나 web 실패 테스트는 컨트롤러 경로(T009) 확정 후 안정.
- **Polish (Phase 5)**: US1·US2 완료 후(T014의 findByKoreanName 제거는 T008이 호출을 없앤 뒤라야 안전).

### Within Each Story

- 테스트 먼저 작성·FAIL 확인(헌법 I) → 포트/도메인 → usecase → controller/문서.

### Parallel Opportunities

- T002는 다른 파일이라 Foundational 내 단독 [P].
- US1 테스트 T006·T007은 서로 다른 파일 → 병렬.
- US2 테스트 T011·T012 병렬.
- Polish T014·T015 병렬(T016은 이후).

---

## Parallel Example: User Story 1

```bash
# US1 테스트 먼저(실패 확인):
Task: "T006 GetFoodDetailUseCaseTest 를 foodId 성공 경로로 갱신"
Task: "T007 FoodDetailControllerTest·FoodDetailLangTest·FoodDetailDescriptionTest 를 /foods/{id} 성공으로 재작성"
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 Setup → 2. Phase 2 Foundational → 3. Phase 3 US1 → **STOP & VALIDATE**: `GET /api/v1/foods/{id}` 성공 조회 단독 검증 → 배포 가능.

### Incremental Delivery

1. Foundational 완료 → foodId 포트 준비.
2. US1 → 성공 조회 데모(MVP).
3. US2 → 실패 처리(400) 완성.
4. Polish → 죽은 menuName 경로 제거 + 전체 빌드.

---

## Notes

- [P] = 다른 파일·무의존. [Story] = 추적용.
- 각 단위 구현 전 테스트 FAIL 먼저 확인.
- 태스크/논리 그룹마다 커밋.
- 스키마·BaseResponse 봉투·`/api/v1` 규약 불변(SC-003) — 응답 payload를 바꾸지 않는다.
- Kotlin 주석 금지 규약 준수(테스트 포함).
