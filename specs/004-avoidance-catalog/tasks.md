---
description: "Task list — 회피·주의 성분 카탈로그 (3분류 81종, :core:avoidance enum)"
---

# Tasks: 회피·주의 성분 카탈로그 (3분류 81종)

**Input**: Design documents from `specs/004-avoidance-catalog/`

**Prerequisites**: plan.md · spec.md · research.md · data-model.md · contracts/avoidance-catalog-api.md · quickstart.md

**Tests**: Test-First **NON-NEGOTIABLE**(헌법 원칙 I). 각 단위는 실패 테스트(Red)를 먼저 작성하고 통과(Green)→정리(Refactor).

**테스트 스타일(고정)**: Kotest `BehaviorSpec`, given/when/then **한국어**. Kotlin 소스 **주석 금지**.

**핵심 설계**: 카탈로그 = `:core:avoidance` 컴파일 enum(DB 아님). `LanguageCode` 는 `:core:kernel` 공유로 이동. 영속·Flyway·web·application·평가 로직 없음(후속). 콘텐츠는 mock placeholder(확정 값 수령 시 교체). 근거: research.md.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·의존 없음 → 병렬 가능
- **[US1]**: User Story 1 소속
- 모든 경로는 repo 루트 기준 절대 경로 일부

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 모듈/패키지 확인. (`:core:avoidance` 는 이미 존재 — `domain-conventions` = `api(:core:kernel)`. 신규 Gradle 모듈·의존 없음.)

- [X] T001 `:core:avoidance` 빌드 가능 확인(`./gradlew :core:avoidance:compileKotlin`) 및 패키지 경로 `core/avoidance/src/{main,test}/kotlin/com/meogo/core/avoidance/` 준비

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: `LanguageCode` 를 `:core:food` → `:core:kernel` 로 이동(카탈로그 resolver 가 kernel 의 `LanguageCode` 를 사용). **동작 불변 리팩터** — 회귀 기준은 기존 테스트 전부 그린 유지(Red 는 import/컴파일 실패, Green 은 빌드 복구).

**⚠️ CRITICAL**: US1 의 resolver(T012~)가 이 이동에 의존 → 먼저 완료.

- [X] T002 `LanguageCode` 를 `core/kernel/src/main/kotlin/com/meogo/core/kernel/lang/LanguageCode.kt`(패키지 `com.meogo.core.kernel.lang`)로 생성(기존 값·`from(code)` 동작 동일)
- [X] T003 소비처 import 4곳을 `com.meogo.core.kernel.lang.LanguageCode` 로 갱신: `core/food/src/main/kotlin/com/meogo/core/food/FoodRepository.kt`, `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/FoodRepositoryAdapter.kt`, `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/LanguageResolver.kt`, `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/GetFoodDetailUseCase.kt`
- [X] T004 `core/food/src/main/kotlin/com/meogo/core/food/LanguageCode.kt` 삭제
- [X] T005 `LanguageCodeTest` 를 `core/kernel/src/test/kotlin/com/meogo/core/kernel/lang/LanguageCodeTest.kt`(패키지 `com.meogo.core.kernel.lang`)로 이동, `core/food` 의 기존 테스트 삭제
- [X] T006 회귀 확인: `./gradlew :core:kernel:test :core:food:test :infra:persistence:test :application:client:test :app:api:test` 그린(food 상세 다국어 동작 불변)

**Checkpoint**: 공유 `LanguageCode` 준비 완료 — US1 진행 가능

---

## Phase 3: User Story 1 - 분류된 회피·주의 성분 기준 목록 보유 (Priority: P1) 🎯 MVP

**Goal**: 81종 회피·주의 성분이 각각 안정 코드 + 1~3개 분류(ALLERGEN/DIETARY_RULE/PERSONAL_AVOIDANCE) + ko 원문 명칭 + 9개 대상 언어 번역(미보유 시 ko 폴백)을 갖춘 단일 마스터 카탈로그(`:core:avoidance` enum)로 존재한다.

**Independent Test**: `./gradlew :core:avoidance:test` — 81종·분류 멤버십 1~3·코드 유일·ko 비공백·분류 도메인 3종·번역 제공/ko 폴백·완전성 검증.

### Tests for User Story 1 (REQUIRED — Test-First: 먼저 작성하고 FAIL 확인) ⚠️

- [X] T007 [P] [US1] `core/avoidance/src/test/kotlin/com/meogo/core/avoidance/AvoidanceSubstanceTest.kt` 작성(실패): 항목 수 == 81 · 각 항목 `categories.size` ∈ [1,3]·중복 없는 Set · `koName` 비공백 · `name`(코드) 유일 · `AvoidanceCategory` 정확히 3값(C-1~C-4)
- [X] T008 [P] [US1] `core/avoidance/src/test/kotlin/com/meogo/core/avoidance/AvoidanceCatalogTest.kt` 작성(실패): `displayName(s, KO)` == `s.koName`(C-5) · 등록 번역 언어는 그 값·미등록은 `koName` 폴백·빈 문자열 0(C-6) · `byCategory(c)` 가 `c` 포함 성분 전부·그 외 제외(C-7) · 모든 성분 × 9개 대상 언어 키 보유 완전성(C-8)

### Implementation for User Story 1

- [X] T009 [US1] `core/avoidance/src/main/kotlin/com/meogo/core/avoidance/AvoidanceCategory.kt` — enum `ALLERGEN`, `DIETARY_RULE`, `PERSONAL_AVOIDANCE`
- [X] T010 [US1] **콘텐츠 확정**: 실데이터 수령 완료 — `specs/004-avoidance-catalog/seed/avoidance-substances.json`(81종, 코드·분류·ko·9개 번역, 검증 통과)을 단일 출처로 사용. mock 불필요
- [X] T011 [US1] `core/avoidance/src/main/kotlin/com/meogo/core/avoidance/AvoidanceSubstance.kt` — seed 의 81종을 enum 으로(`categories: Set<AvoidanceCategory>`, `koName`=seed `name`), `init { require(categories.isNotEmpty() && categories.size <= 3); require(koName.isNotBlank()) }` (T007 통과)
- [X] T012 [US1] `core/avoidance/src/main/kotlin/com/meogo/core/avoidance/AvoidanceSubstanceTranslations.kt` — seed `translations` 중 **ko 제외 9개 대상 언어**를 `Map<AvoidanceSubstance, Map<LanguageCode, String>>` 로(ko 는 `koName` 에서)
- [X] T013 [US1] `core/avoidance/src/main/kotlin/com/meogo/core/avoidance/AvoidanceCatalog.kt` — `displayName(substance, lang)`(ko 폴백) · `byCategory(category)` · `all()` (T008 통과)
- [X] T014 [US1] Refactor: 중복 제거·네이밍 정리 후 `./gradlew :core:avoidance:test` 재그린 유지

**Checkpoint**: US1 카탈로그가 독립적으로 기능·테스트 가능

---

## Phase 4: Polish & Cross-Cutting Concerns

- [X] T015 [P] 경계 회귀: `./gradlew :app:api:test --tests "com.meogo.app.api.architecture.ModuleBoundaryTest"` — food·member 가 avoidance 에 의존하지 않음(도메인 격리, 005 ArchUnit)이 유지됨을 확인
- [X] T016 전체 빌드: `./gradlew build` 그린(전 모듈)
- [X] T017 quickstart.md 검증 시나리오 수행(사용 예 컴파일·resolver 동작 확인)

> **후속(코드 작업 아님, 본 기능 밖)**: `/speckit-constitution` 으로 ① 원칙 V 에 "고정 reference taxonomy 는 컴파일 enum 저장 허용" 단서, ② ADR-0008 모듈명 동기화, ③ **`assessment` → `avoidance` BC 리네임**(헌법 원칙 II 컨텍스트 열거 `{food,member,scan,avoidance,research}`, ADR 0001·0004·0008, `docs/architecture/*`, `docs/architecture/domains/assessment.md` → `avoidance.md`). 코드/빌드/ArchUnit/CLAUDE.md/004 문서는 이미 리네임 완료 — 이 항목은 헌법·ADR·architecture 레퍼런스 문서 한정(scan 의 `MenuItemAssessment`·일반명사 "평가"는 보존). plan §Complexity·research D-PRINV 참조.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup(P1)**: 즉시 시작.
- **Foundational(P2, T002~T006)**: Setup 후. **US1 의 resolver(T013)가 kernel `LanguageCode` 에 의존하므로 선행**. (T012 translations 도 `LanguageCode` 타입 사용.)
- **US1(P3)**: Foundational 후.
- **Polish(P4)**: US1 후.

### Within User Story 1

- 테스트(T007·T008) → 구현(T009~T013) → Refactor(T014). 테스트가 **FAIL 함을 먼저 확인**(원칙 I).
- T009(enum 분류) → T011(성분, 분류 사용) → T012(번역, 성분·언어 사용) → T013(resolver, 전부 사용). T010(콘텐츠 수령)은 T011·T012 입력.

### Parallel Opportunities

- T007, T008 [P] 병렬 작성(서로 다른 파일).
- T002~T006(LanguageCode 이동)은 순차(같은 타입·컴파일 연쇄).
- T015 [P] 는 다른 모듈 테스트라 T016 와 독립.

---

## Parallel Example: User Story 1

```bash
# US1 테스트 먼저 작성(반드시 FAIL):
Task: "AvoidanceSubstanceTest 작성 in core/avoidance/src/test/.../avoidance/AvoidanceSubstanceTest.kt"
Task: "AvoidanceCatalogTest 작성 in core/avoidance/src/test/.../avoidance/AvoidanceCatalogTest.kt"
```

---

## Implementation Strategy

### MVP (User Story 1 only)

1. Phase 1 Setup → 2. Phase 2 Foundational(LanguageCode 이동, 회귀 그린) → 3. Phase 3 US1(Red→Green→Refactor) → 4. **검증**: `./gradlew :core:avoidance:test` + `build` 그린 → 5. 커밋.

### Notes

- [P] = 다른 파일·무의존. 구현 전 테스트 FAIL 확인. 작업/논리 단위마다 커밋.
- 콘텐츠(T010)는 **수령 완료** — `seed/avoidance-substances.json`(81종, 검증 통과)을 단일 출처로 enum/번역 Map 생성. mock 없음.
- 평가 Spec·판정 로직·batch 프롬프트·application 조합·조회 API 는 **본 기능 밖**(후속).
