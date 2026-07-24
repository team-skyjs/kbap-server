---
description: "Task list for 언어 무관 메뉴명 한국어 항상 포함 (KB-99)"
---

# Tasks: 언어 무관 메뉴명 한국어 항상 포함

**Input**: Design documents from `specs/kb-99-always-korean-menu-name/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/api-delta.md

**Tests**: Test-First is **NON-NEGOTIABLE** (헌법 원칙 I). 각 스토리는 구현 전 실패 테스트(Red)를 먼저 작성한다. 모든 테스트는 Kotest **BehaviorSpec**(given/when/then 한국어).

**Organization**: 스토리별로 그룹화. US1(상세)·US2(목록)는 공유 도메인 seam(Phase 2) 완료 후 독립·병렬 진행 가능(서로 다른 파일).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·의존 없음 → 병렬 가능
- **[Story]**: US1(상세 조회) / US2(목록 페이징)

## Path Conventions

모듈러 모놀리스. 실제 경로: `core/food/`, `application/client/`, `app/api/` 각 모듈의 `src/main/kotlin/...`·`src/test/kotlin/...`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 프로젝트 초기화.

신규 모듈·의존성·설정 없음(기존 3계층 재사용). **Setup 작업 없음** — Phase 2 로 진행.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: US1·US2 가 공통으로 쓰는 도메인 seam. 두 스토리 모두 이 seam 에 의존한다.

**⚠️ CRITICAL**: 이 phase 완료 전에는 어떤 스토리 구현도 시작할 수 없다.

- [X] T001 [P] `core/food/src/test/kotlin/com/meogo/core/food/FoodContentTest.kt` 에 실패 테스트 추가(Red) — `given("한국어·영어 번역이 있는 FoodContent") { when("koreanName 을 조회하면") { then("요청 언어와 무관하게 한국어 원문을 반환한다") } }`.
- [X] T002 [P] `core/food/src/test/kotlin/com/meogo/core/food/FoodTest.kt` 에 실패 테스트 추가(Red) — `Food.koreanName()` 이 `content.name.korean` 을 반환한다.
- [X] T003 구현(Green) — `core/food/src/main/kotlin/com/meogo/core/food/FoodContent.kt` 에 `fun koreanName(): String = name.korean`, `core/food/src/main/kotlin/com/meogo/core/food/Food.kt` 에 `fun koreanName(): String = content.koreanName()` 추가. T001·T002 통과 확인.

**Checkpoint**: 도메인 seam 준비 완료 — US1·US2 병렬 시작 가능.

---

## Phase 3: User Story 1 - 외국어 상세 조회에서 한국어 메뉴명 확인 (Priority: P1) 🎯 MVP

**Goal**: 상세 조회 응답에 지역화명과 별개로 `koreanName`(언어 무관 한국어 원문) 노출. 지역화명=한국어면 `null`.

**Independent Test**: `GET /api/v1/foods/detail?menuName=...&lang=en` → `payload.koreanName` 에 한국어 원문; `lang=ko` → `payload.koreanName` 이 명시적 `null`.

### Tests for User Story 1 (Test-First: 먼저 작성·FAIL 확인) ⚠️

- [X] T004 [P] [US1] `application/client/src/test/kotlin/com/meogo/application/client/food/usecase/GetFoodDetailUseCaseTest.kt` 에 실패 테스트 추가(Red) — `lang=en`(영어 번역 존재) 시 `result.koreanName` = 한국어 원문; `lang=ko` 및 `lang=en`(영어 번역 부재→ko 폴백) 시 `result.koreanName == null`.
- [X] T005 [P] [US1] `app/api/src/test/kotlin/com/meogo/app/api/food/FoodDetailLangTest.kt` 에 실패 통합 테스트 추가(Red, MockMvc BehaviorSpec) — en/ja 요청 시 `$.payload.koreanName` = 한국어 원문, ko·미지원 폴백 시 `$.payload.koreanName` 이 **명시적 null 로 응답에 존재**(필드 생략 아님).

### Implementation for User Story 1

- [X] T006 [US1] `application/client/src/main/kotlin/com/meogo/application/client/food/dto/GetFoodDetailResult.kt` 에 `val koreanName: String?` 추가.
- [X] T007 [US1] `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/GetFoodDetailUseCase.kt` 에서 `koreanName = food.koreanName().takeIf { it != foodName }` 계산해 `GetFoodDetailResult` 에 전달. T004 통과 확인(Green).
- [X] T008 [US1] `app/api/src/main/kotlin/com/meogo/app/api/food/FoodDetailResponse.kt` 에 `val koreanName: String?` 필드(+`@field:Schema(description="언어 무관 한국어 메뉴명. 지역화명과 동일하면 null", nullable=true)`) 추가하고 `from()` 에서 `koreanName = result.koreanName` 매핑. T005 통과 확인(Green).

**Checkpoint**: 상세 조회가 독립적으로 완결·검증 가능(MVP).

---

## Phase 4: User Story 2 - 외국어 목록 조회에서 각 항목 한국어 메뉴명 확인 (Priority: P1)

**Goal**: 목록(페이징) 응답의 각 항목에 상세와 동일 규약으로 `koreanName` 노출.

**Independent Test**: `GET /api/v1/foods?lang=ja` → 각 `payload.items[].koreanName` 이 상세와 동일 규약(다르면 원문, 같으면 명시적 null).

### Tests for User Story 2 (Test-First: 먼저 작성·FAIL 확인) ⚠️

- [X] T009 [P] [US2] `application/client/src/test/kotlin/com/meogo/application/client/food/usecase/BrowseMenusUseCaseTest.kt` 에 실패 테스트 추가(Red) — 지역화 항목은 `view.koreanName` = 한국어 원문, ko 폴백/`lang=ko` 항목은 `view.koreanName == null`.
- [X] T010 [P] [US2] `app/api/src/test/kotlin/com/meogo/app/api/food/MenuListControllerTest.kt` 에 실패 통합 테스트 추가(Red, MockMvc BehaviorSpec) — `lang=ja` 목록에서 항목별 `$.payload.items[*].koreanName` 이 다르면 원문·같으면 **명시적 null**.

### Implementation for User Story 2

- [X] T011 [US2] `application/client/src/main/kotlin/com/meogo/application/client/food/dto/BrowseMenusResult.kt` 의 `MenuSummaryView` 에 `val koreanName: String?` 추가.
- [X] T012 [US2] `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/BrowseMenusUseCase.kt` 에서 각 항목의 지역화명 계산 뒤 `koreanName = food.koreanName().takeIf { it != 지역화명 }` 를 `MenuSummaryView` 에 전달. T009 통과 확인(Green).
- [X] T013 [US2] `app/api/src/main/kotlin/com/meogo/app/api/food/MenuSummaryResponse.kt` 에 `val koreanName: String?` 필드 추가(+ `MenuSummaryResponse` 에 `@Schema` 없으면 최소 필드 추가)하고 `from()` 에서 `koreanName = view.koreanName` 매핑. T010 통과 확인(Green).

**Checkpoint**: 상세·목록 모두 독립 검증 가능, 규약 일관(FR-004).

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: 회귀·계약 확정.

- [X] T014 [P] 회귀 확인 — 기존 상세·목록 테스트(`FoodDetailControllerTest`·`FoodDetailDescriptionTest`·`MenuListControllerTest` 기존 케이스)가 지역화명·폴백 값 변화 없이 통과하는지 확인(SC-004).
- [X] T015 `./gradlew build` 전체 통과 + `specs/kb-99-always-korean-menu-name/quickstart.md` 완료 기준 체크리스트 검증(DB·Flyway·스캔 API 무변경 재확인).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 없음.
- **Foundational (Phase 2)**: US1·US2 를 BLOCK. 먼저 완료.
- **US1 (Phase 3)·US2 (Phase 4)**: Phase 2 완료 후 시작. 서로 다른 파일이라 **병렬 가능**.
- **Polish (Phase 5)**: US1·US2 완료 후.

### User Story Dependencies

- **US1 (P1)**: Phase 2 후 시작. 타 스토리 무의존.
- **US2 (P1)**: Phase 2 후 시작. 타 스토리 무의존.

### Within Each User Story

- 테스트 먼저 작성·FAIL 확인(원칙 I) → 구현.
- Result DTO(application) → 유스케이스 → web Response 순.
- 유스케이스 테스트(단위) → web 통합 테스트.

### Parallel Opportunities

- Phase 2: T001·T002(다른 테스트 파일) 병렬 → T003 구현.
- Phase 2 완료 후 US1 전체와 US2 전체를 서로 다른 담당이 병렬 진행.
- 각 스토리 내 `[P]` 테스트(T004·T005 / T009·T010)는 병렬 작성 가능.

---

## Parallel Example: Foundational + Stories

```bash
# Phase 2 — 도메인 seam 테스트 병렬 작성 (Red):
Task: "T001 FoodContentTest 에 koreanName 실패 테스트"
Task: "T002 FoodTest 에 Food.koreanName() 실패 테스트"

# Phase 3·4 — seam 완료 후 두 스토리 병렬:
Task: "US1 상세 — T004~T008"
Task: "US2 목록 — T009~T013"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 2 Foundational(도메인 seam) 완료.
2. Phase 3 US1(상세) 완료 → `GET /api/v1/foods/detail` 로 언어별 `koreanName` 독립 검증 → MVP.

### Incremental Delivery

1. Foundational → US1(상세, MVP) → US2(목록) → Polish. 각 스토리는 이전을 깨지 않고 값을 더한다.

---

## Notes

- `[P]` = 다른 파일·의존 없음. 같은 파일 수정 태스크는 순차.
- **Kotlin 주석 금지**(고정 규약) — 테스트·구현 모두 `.kt` 에 주석 작성 금지.
- `koreanName` 은 지역화명과 **동일하면 null**(FR-003). 판정은 유스케이스에서 지역화명과 문자열 비교로 수행(단일 출처=서버).
- null 은 **명시적으로 응답에 포함**(전역 Jackson NON_NULL 설정 없음 확인됨) — 통합 테스트로 못박는다.
- 각 task/논리 단위마다 커밋(원칙: 작업 단위 커밋).
- DB·Flyway·영속·스캔 API **변경 없음**.
