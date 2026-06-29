---
description: "Task list — 음식 상세 조회에 음식 설명(간단·자세) 추가"
---

# Tasks: 음식 상세 조회에 음식 설명(간단·자세) 추가

**Input**: Design documents from `specs/002-food-description/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/food-detail-api.md, quickstart.md (모두 존재)

**Tests**: Test-First는 **NON-NEGOTIABLE**(헌법 원칙 I). 각 레이어의 실패 테스트를 구현보다 먼저 작성하고 Red 확인 후 Green→Refactor. 모든 테스트는 Kotest `BehaviorSpec`(given/when/then 한국어).

**Organization**: 단일 사용자 스토리(US1, P1) — 가산적 변경. 레이어 순(도메인→영속→application→web)으로 TDD.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·선행 의존 없음 → 병렬 가능
- **[US1]**: 사용자 스토리 1 소속
- 경로는 repo 루트 기준 절대 경로(모듈 평탄화: `meogo-api/<module>/src/...`)

---

## Phase 1: Setup & Foundational (Shared)

**Purpose**: 모든 레이어가 의존하는 공유 타입. 신규 모듈·의존성 없음(기존 멀티모듈 재사용).

- [X] T001 [P] `FoodDescriptionKind` enum(BRIEF, DETAILED) 생성 — `meogo-api/food/src/main/kotlin/com/meogo/api/food/FoodDescriptionKind.kt` (순수 enum, 별도 테스트 불요)

**Checkpoint**: 공유 enum 준비 — US1 시작 가능

---

## Phase 2: User Story 1 - 외국인 사용자가 음식 소개를 모국어로 읽는다 (Priority: P1) 🎯 MVP

**Goal**: 음식 상세 응답에 간단·자세 설명 2종을 추가하고, 음식명과 동일한 ko 원문+9개 언어 번역·`lang` 폴백(설명별 독립)을 제공한다. 기존 동작은 불변.

**Independent Test**: seed 음식을 `lang=en`으로 조회 → `briefDescription`·`detailedDescription` 영어 포함; `lang` 미지정/미지원 → 둘 다 ko; 한 설명만 번역 부재 → 그 설명만 ko 폴백(나머지 유지). 기존 400 시나리오 회귀 없음.

### Tests for User Story 1 (먼저 작성·반드시 FAIL) ⚠️

- [X] T002 [P] [US1] 도메인 단위 테스트(Red) — `Food.create`/`reconstitute`에 `briefDescription`·`detailedDescription` 추가, blank/공백 시 예외, 길이 상한(≤255 / ≤1024) 불변 검증 — `meogo-api/food/src/test/kotlin/com/meogo/api/food/FoodTest.kt`
- [X] T003 [P] [US1] 영속 테스트(Red, H2) — `FoodRepositoryAdapter.findFoodDescriptionTranslations(foodId, lang)`가 `food_description_translation`에서 BRIEF·DETAILED 행을 `Map<FoodDescriptionKind,String>`으로 반환, `lang=ko`면 빈 맵, 미존재 lang이면 누락(폴백 유도), `findByKoreanName`이 설명 컬럼을 도메인에 복원 — `meogo-api/persistence/src/test/kotlin/com/meogo/api/persistence/food/FoodRepositoryAdapterTest.kt`
- [X] T004 [P] [US1] application 단위 테스트(Red, fake repo) — `GetFoodDetailUseCase`가 두 설명을 `lang` 번역으로 채우고, 번역 부재 시 **설명별 독립**으로 ko 원문 폴백(간단만 부재→간단만 ko, 자세·name 무영향) — `meogo-api/application/src/test/kotlin/com/meogo/api/application/food/usecase/GetFoodDetailUseCaseTest.kt`
- [X] T005 [P] [US1] web 테스트(Red, MockMvc) — 응답 payload에 `briefDescription`·`detailedDescription`이 **non-null**로 포함, `lang=en`/미지정/부분폴백 시나리오, 미수록 400·blank 400 회귀. 필요한 설명 seed를 테스트 헬퍼에 추가 — `meogo-api/presentation/src/test/kotlin/com/meogo/api/presentation/food/FoodDetailControllerTest.kt`, `FoodDetailLangTest.kt`, `FoodTestSeed.kt`

### Implementation for User Story 1 (Green, 의존 순서)

- [X] T006 [US1] `Food`에 `briefDescription`·`detailedDescription`(non-null) 필드 + `create`/`reconstitute` 인자 + `require(notBlank)`·길이 상한 불변 추가 — `meogo-api/food/src/main/kotlin/com/meogo/api/food/Food.kt` (T002 통과)
- [X] T007 [US1] `FoodRepository` port에 `findFoodDescriptionTranslations(foodId: Long, lang: LanguageCode): Map<FoodDescriptionKind, String>` 추가 — `meogo-api/food/src/main/kotlin/com/meogo/api/food/FoodRepository.kt` (T001)
- [X] T008 [P] [US1] `FoodDescriptionTranslationJpaEntity` 생성(`food_description_translation`: food_id, kind VARCHAR(10), lang_code VARCHAR(10), content VARCHAR(1024), BaseEntity 상속) — `meogo-api/persistence/src/main/kotlin/com/meogo/api/persistence/food/FoodDescriptionTranslationJpaEntity.kt` (T001)
- [X] T009 [P] [US1] `FoodDescriptionTranslationJpaRepository`(`findByFoodIdAndLangCode(foodId, langCode): List<...>`) 생성 — `meogo-api/persistence/src/main/kotlin/com/meogo/api/persistence/food/FoodDescriptionTranslationJpaRepository.kt`
- [X] T010 [US1] `FoodJpaEntity`에 `brief_description`(255)·`detailed_description`(1024) 컬럼 추가 + `toDomain()`/`from()`에 두 설명 매핑 — `meogo-api/persistence/src/main/kotlin/com/meogo/api/persistence/food/FoodJpaEntity.kt` (T006)
- [X] T011 [US1] `FoodRepositoryAdapter`에 `findFoodDescriptionTranslations` 구현(`lang=ko`→emptyMap, 아니면 조회 후 `FoodDescriptionKind.valueOf(kind)`로 매핑) — `meogo-api/persistence/src/main/kotlin/com/meogo/api/persistence/food/FoodRepositoryAdapter.kt` (T007, T008, T009 → T003 통과)
- [X] T012 [P] [US1] `GetFoodDetailResult`에 `briefDescription`·`detailedDescription` 필드 추가 — `meogo-api/application/src/main/kotlin/com/meogo/api/application/food/dto/GetFoodDetailResult.kt`
- [X] T013 [US1] `GetFoodDetailUseCase`에서 ko 원문(`food.briefDescription`/`detailedDescription`)을 기본값으로 두고 `findFoodDescriptionTranslations` 번역으로 설명별 독립 폴백 채움 — `meogo-api/application/src/main/kotlin/com/meogo/api/application/food/usecase/GetFoodDetailUseCase.kt` (T006, T007, T012 → T004 통과)
- [X] T014 [US1] `FoodDetailResponse`에 `briefDescription`·`detailedDescription`(+`@Schema`) 추가 및 `from(result)` 매핑 — `meogo-api/presentation/src/main/kotlin/com/meogo/api/presentation/food/FoodDetailResponse.kt` (T012)
- [X] T015 [US1] `FoodDetailApi` Swagger 응답 예시에 두 설명 필드 반영 — `meogo-api/presentation/src/main/kotlin/com/meogo/api/presentation/food/FoodDetailApi.kt` (T014 → T005 통과)
- [X] T016 [US1] V4 마이그레이션 작성 — `food` 컬럼 2개 nullable ADD → 기존 seed 음식 ko 설명 UPDATE(mock) → `food_description_translation` 생성(UNIQUE(food_id,kind,lang_code)·FK·CHECK kind/lang) → 음식×{BRIEF,DETAILED}×9개 언어 번역 INSERT(mock) → 컬럼 NOT NULL 강화 — `meogo-api/presentation/src/main/resources/db/migration/V4__add_food_description.sql` (data-model.md §V4 절차)

**Checkpoint**: US1 완료 — 음식 상세가 간단·자세 설명을 다국어·독립폴백으로 응답. 독립 테스트 가능.

---

## Phase 3: Polish & Cross-Cutting

- [X] T017 [P] 회귀 확인 — 기존 음식 상세 테스트(정상·ko 폴백·미수록 400·blank 400) 그대로 통과 + `./gradlew build` 그린
- [X] T018 [P] quickstart.md 시나리오 로컬 검증(MySQL, V4 적용, curl en/미지정/미수록) — `specs/002-food-description/quickstart.md`
- [X] T019 [P] 문서 정합 — contracts/data-model 과 실제 응답 필드·컬럼 길이 일치 확인

---

## Dependencies & Execution Order

### Phase 의존

- **Phase 1 (Setup/Foundational)**: 즉시 시작. `FoodDescriptionKind`가 port·엔티티·adapter의 선행.
- **Phase 2 (US1)**: Phase 1 후. 테스트(T002~T005) 먼저 Red, 이후 구현.
- **Phase 3 (Polish)**: US1 완료 후.

### US1 내부 의존

- 테스트 우선: T002~T005 작성·FAIL 확인 → 구현.
- 도메인 시그니처 T006 → 영속 매핑 T010, application T013.
- port T007 + 번역 엔티티 T008 + 리포지토리 T009 → adapter T011(→ T003 Green).
- result T012 → usecase T013(→ T004 Green) → response T014 → Swagger T015(→ T005 Green).
- 도메인 T006 → 영속 T010 → adapter findByKoreanName 설명 복원(→ T003 Green, usecase ko 원문 공급).
- V4(T016)는 코드와 독립(런타임 스키마). H2 테스트는 flyway off라 무관하나 실제 실행·quickstart 전 필요.

### 병렬 기회

- T002·T003·T004·T005 (서로 다른 모듈 테스트 파일) 병렬.
- T008·T009·T012 (서로 다른 파일, 도메인 시그니처 외 의존 없음) 병렬.

---

## Parallel Example: US1 테스트(Red 먼저)

```text
Task: 도메인 테스트 — meogo-api/food/.../FoodTest.kt
Task: 영속 테스트 — meogo-api/persistence/.../FoodRepositoryAdapterTest.kt
Task: application 테스트 — meogo-api/application/.../GetFoodDetailUseCaseTest.kt
Task: web 테스트 — meogo-api/presentation/.../FoodDetailControllerTest.kt
```

---

## Implementation Strategy

### MVP (US1 = 전체)

1. Phase 1 enum
2. Phase 2 테스트 Red(T002~T005) → 레이어 구현 Green(T006~T015) → V4(T016)
3. STOP & VALIDATE: quickstart 시나리오 + 회귀
4. 데모/머지

### Notes

- [P] = 다른 파일·무의존. 같은 파일 동시 수정 금지.
- 각 구현 task 전 해당 테스트 FAIL 확인(Test-First).
- Kotlin 주석 금지·BaseResponse/`/api/v` 규약·도메인 불변·LAZY 로딩·영속 캡슐화 준수.
- task 또는 논리 묶음마다 커밋(브랜치는 squash 대상이라 자유).
- 콘텐츠(mock placeholder) — 실제 편집 콘텐츠는 기획 확정 시 V5 또는 seed 교체로 반영(spec Dependencies).
