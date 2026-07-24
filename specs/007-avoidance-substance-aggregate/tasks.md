---
description: "Task list — 회피·주의 성분 식별자 enum + 도메인 어그리게이트 분리"
---

# Tasks: 회피·주의 성분 — 식별자 enum + 도메인 어그리게이트 분리

**Input**: Design documents from `/specs/007-avoidance-substance-aggregate/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ports.md

**Tests**: Test-First is **NON-NEGOTIABLE** (헌법 원칙 I). 각 변경은 실패 테스트(Red)를 먼저 쓴 뒤 통과(Green)시킨다.

**성격(중요)**: 이 기능은 **리팩터**다. 스토리는 독립 배포 단위라기보다 **관측 가능한 결과별 검증 슬라이스**이며, 모듈 경계(도메인→영속) 특성상 순차 결합된다 — 브랜치는 US2 완료 시점에 전체 컴파일·Green 에 도달한다. 스토리 라벨은 spec 추적성을 위해 유지한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일·선행 미완 의존 없음)
- **[Story]**: US1/US2/US3 (spec 사용자 스토리)

---

## Phase 1: Setup

**Purpose**: 회귀 기준선 확보(기존 동작 보존 확인용)

- [X] T001 회귀 기준선 캡처 — `./gradlew build` 로 전 모듈 컴파일·테스트 Green 확인(리팩터 전 관측 동작 기준선)

---

## Phase 2: Foundational (도메인 타입 분리 — Blocking Prerequisites)

**Purpose**: 식별자 enum + 어그리게이트 도메인 타입 신설. 모든 스토리(영속·포트·검증)가 이 타입에 의존하므로 선행 완료 필수.

**⚠️ CRITICAL**: 이 단계 완료 전에는 US1~US3 착수 불가.

- [X] T002 [P] (Red) 어그리게이트 단위 테스트 작성 in `core/avoidance/src/test/kotlin/com/meogo/core/avoidance/AvoidanceSubstanceTest.kt` — `displayName(KO)`=koreanName, `displayName(EN)`=translations[EN], 번역 없음/blank → ko 폴백, `belongsTo` 참/거짓, 불변식(categories 1~3, koreanName not blank). 기존 enum 데이터 테스트를 어그리게이트 테스트로 교체
- [X] T003 [P] `AvoidanceSubstanceCode` 식별자 enum 신설(무데이터 81 상수, 필드·init 없음) in `core/avoidance/src/main/kotlin/com/meogo/core/avoidance/AvoidanceSubstanceCode.kt` — 상수명 = 기존 enum 상수명(= DB `code`)
- [X] T004 `AvoidanceSubstance` enum → `@AggregateRoot` 어그리게이트 전환 in `core/avoidance/src/main/kotlin/com/meogo/core/avoidance/AvoidanceSubstance.kt` — `private constructor(id, code: AvoidanceSubstanceCode, koreanName, translations: Map<LanguageCode,String>, categories: Set<AvoidanceCategory>)` + `companion object { fun reconstitute(...) }` + `displayName`/`belongsTo` + 불변식(T002 Green)
- [X] T005 도메인 port 시그니처 갱신 in `core/avoidance/src/main/kotlin/com/meogo/core/avoidance/AvoidanceSubstanceRepository.kt`·`IngredientAvoidanceSubstanceRepository.kt` — `byCategory`·`findByCodes(Set<AvoidanceSubstanceCode>)`·`findByIngredientIds` 가 어그리게이트 반환, **`translatedName` 제거**
- [X] T006 전이 유물 제거 in `core/avoidance/...` — `AvoidanceCatalog.kt`·`AvoidanceSubstanceTranslations.kt` 및 테스트 `AvoidanceCatalogTest.kt` 삭제(표시명·byCategory 는 어그리게이트·port 로 대체; 사용처 테스트뿐 확인됨)

**Checkpoint**: `:core:avoidance` 컴파일·테스트 Green(도메인 단독). 하위 어댑터는 US1~ 에서 새 계약 구현.

---

## Phase 3: User Story 1 — 운영자가 고친 한국어명 즉시 반영 (Priority: P1) 🎯 MVP

**Goal**: 표시명(모든 언어, 특히 KO)을 **DB 단일 출처**로 조회 — Finding ① 데이터 누수 제거.

**Independent Test**: DB `korean_name` 을 바꾼 뒤 `displayName(KO)` 가 저장된 값을 반환(enum 하드코딩 값 반환 0).

### Tests for User Story 1 (Test-First: 먼저 작성·실패 확인) ⚠️

- [X] T007 [US1] (Red) 어댑터 H2 슬라이스 테스트 in `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/avoidance/AvoidanceSubstanceRepositoryAdapterTest.kt` — (a) `korean_name` 을 기존 enum 값과 다르게 저장 → `byCategory`/`findByCodes` 결과의 `displayName(KO)` 가 **저장값** 반영, (b) 대상 언어 번역/폴백, (c) `translatedName` 제거 후 어그리게이트로 검증

### Implementation for User Story 1

- [X] T008 [US1] `AvoidanceSubstanceJpaEntity.toDomain(categories: Set<AvoidanceCategory>)` 구현 in `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/AvoidanceSubstanceJpaEntity.kt` — 번역 컬럼→`Map<LanguageCode,String>`(null/blank 제외), `code`→`AvoidanceSubstanceCode.valueOf`, `AvoidanceSubstance.reconstitute(id, ...)`
- [X] T009 [US1] `AvoidanceSubstanceRepositoryAdapter` 재구현 in `infra/persistence/.../AvoidanceSubstanceRepositoryAdapter.kt` — `translatedName` 제거, 공통 `reconstituteByIds(substanceIds)`(성분행+분류행 배치 조회→조립)로 `byCategory`·`findByCodes` 어그리게이트 반환(T007 Green)

**Checkpoint**: KO 표시명이 DB 를 반영(Finding ① 해소).

---

## Phase 4: User Story 2 — 성분 데이터·행위를 단일 어그리게이트로 (Priority: P2)

**Goal**: 조회 결과가 어그리게이트(코드·한국어명·번역·분류 + 행위)로 반환되고, 소비자·매핑까지 일원화. N+1 없음.

**Independent Test**: 코드/분류/재료로 조회 시 어그리게이트가 `displayName`·`belongsTo` 를 스스로 답하고, 조회 쿼리 수가 성분 수와 무관.

### Tests for User Story 2 (Test-First) ⚠️

- [X] T010 [P] [US2] (Red) `byCategory`/`findByCodes` 어그리게이트 완전성(코드·번역·분류 포함) + **N+1 없음**(쿼리 카운트 상수) 테스트 in `infra/persistence/.../AvoidanceSubstanceRepositoryAdapterTest.kt`
- [X] T011 [P] [US2] (Red) 매핑 어댑터 테스트 어그리게이트 반환으로 갱신 in `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/avoidance/IngredientAvoidanceSubstanceRepositoryAdapterTest.kt`

### Implementation for User Story 2

- [X] T012 [US2] `IngredientAvoidanceSubstanceRepositoryAdapter` 재구현 in `infra/persistence/.../IngredientAvoidanceSubstanceRepositoryAdapter.kt` — 매핑행→substanceIds→`reconstituteByIds` 재사용→ingredientId group, `Map<Long, Set<AvoidanceSubstance 어그리게이트>>` 반환(T011 Green)
- [X] T013 [US2] JpaRepository 배치 조회 보강 in `infra/persistence/.../AvoidanceSubstanceCategoryJpaRepository.kt`·`AvoidanceSubstanceJpaRepository.kt` — `findBySubstanceIdIn(ids)`, `findByCodeIn(names)` 등 in-절(N+1 회피, T010 Green)
- [X] T014 [US2] 소비자 정합 확인/갱신 in `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/FoodAvoidanceSubstanceResolver.kt`(+ `FoodAvoidanceSubstanceResolverTest.kt`) — 어그리게이트 `Set` 반환 유지, 테스트를 어그리게이트로 갱신

**Checkpoint**: 포트·소비자·매핑이 어그리게이트로 일원화, 상수 쿼리. **전체 빌드 Green 도달.**

---

## Phase 5: User Story 3 — 식별자 enum·저장 형식·시드 정합 일관 (Priority: P3)

**Goal**: enum 무데이터 확정, JPA 분류 String 저장, 시드 정합 = 코드 집합 일치, 구조 회귀 가드.

**Independent Test**: 식별자 enum 무필드 확인 + 시드 코드 집합 == enum 코드 집합 + 분류 String 저장.

### Tests for User Story 3 (Test-First) ⚠️

- [X] T015 [P] [US3] (Red) 시드 정합 축소 테스트 in `app/api/src/test/kotlin/com/meogo/app/api/avoidance/AvoidanceCatalogSeedSyncTest.kt` — V5 SQL 코드 집합 == `AvoidanceSubstanceCode.entries`(koName·번역·멤버십 대조 제거)
- [X] T016 [P] [US3] (Red) ArchUnit 회귀 in `app/api/src/test/kotlin/com/meogo/app/api/architecture/ModuleBoundaryTest.kt` — (a) `AvoidanceSubstanceCode` 선언 필드 0, (b) avoidance 영속 엔티티가 도메인 enum(`AvoidanceCategory`)을 `@Enumerated` 필드로 쓰지 않음

### Implementation for User Story 3

- [X] T017 [US3] `AvoidanceSubstanceCategoryJpaEntity.category` → `@Column(length=30) String` 저장으로 변경 in `infra/persistence/.../AvoidanceSubstanceCategoryJpaEntity.kt`, 어댑터 경계 변환 정렬(`byCategory` 문자열 질의, 조립 시 `AvoidanceCategory.valueOf`) in `AvoidanceSubstanceRepositoryAdapter.kt`(T016b Green)
- [X] T018 [US3] 시드 정합 테스트 Green 확인 및 seed 원천 주석/문서 정리(`specs/004-avoidance-catalog/seed/avoidance-substances.json` 이 단일 출처, T015 Green)

**Checkpoint**: enum 무데이터·String 저장·코드집합 정합·ArchUnit 회귀 모두 Green.

---

## Phase 6: Polish & Cross-Cutting

- [X] T019 `./gradlew build` 전체 회귀 + quickstart.md 검증 절차 수행(SC-001~005 확인)
- [X] T020 후속 메모: 헌법 원칙 V "고정 taxonomy = 컴파일 enum 저장" 예외 문구를 enum 무데이터 전제로 조정 — 별도 `/speckit-constitution`(MINOR). 이 브랜치에서 코드 변경 없음, TODO 로만 기록
- [ ] T021 작업 단위 커밋 정리(원칙: 논리 단위마다 커밋)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup(P1)**: 즉시 시작.
- **Foundational(P2)**: Setup 후. **US1~US3 전부 차단**(도메인 타입 선행).
- **US1(P3 phase)**: Foundational 후. 영속 toDomain·어댑터로 Finding ① 해소.
- **US2(P4 phase)**: US1 후(같은 어댑터 파일·`reconstituteByIds` 재사용). 전체 빌드 Green 도달점.
- **US3(P5 phase)**: US2 후(어댑터/엔티티 안정 위에 저장형식·정합·ArchUnit).
- **Polish(P6)**: 전 스토리 완료 후.

### 리팩터 특성상 순차 결합

- 본 브랜치는 **모듈 경계를 가로지르는 단일 리팩터**라 US1↔US2 는 동일 어댑터를 순차 수정한다(진짜 병렬 스토리 아님). 파일이 다른 테스트 작성(T010/T011, T015/T016)만 `[P]`.

### Within Each Story

- 테스트 먼저 작성·실패 확인(원칙 I) → 구현 → Green.
- 도메인 모델 → 영속 → 소비자 순.

### Parallel Opportunities

- T002·T003 병렬(다른 파일: 테스트 vs 코드 enum).
- T010·T011 병렬(서로 다른 어댑터 테스트 파일).
- T015·T016 병렬(seed 테스트 vs ArchUnit 테스트).

---

## Parallel Example: User Story 2 테스트

```bash
# US2 실패 테스트를 함께 작성(다른 파일):
Task: "byCategory/findByCodes 어그리게이트+N+1 테스트 in AvoidanceSubstanceRepositoryAdapterTest.kt"
Task: "매핑 어댑터 어그리게이트 반환 테스트 in IngredientAvoidanceSubstanceRepositoryAdapterTest.kt"
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 Setup → 2 Foundational(도메인 타입) → 3 US1.
2. **STOP & VALIDATE**: `korean_name` 변경 시 KO 표시명 반영(Finding ①) 확인.
   - 단, US1 완료 시점엔 매핑 어댑터가 아직 미갱신이라 전체 빌드는 US2에서 Green.

### Incremental Delivery

1. Foundational → US1(표시명 DB 단일 출처) → US2(어그리게이트 일원화·전체 Green) → US3(정합·저장형식·회귀).
2. 각 단계는 관측 동작을 보존하며 회귀 테스트로 가드.

---

## Notes

- `[P]` = 다른 파일·의존 없음. 본 리팩터는 대부분 동일 어댑터/엔티티를 순차 수정하므로 `[P]` 는 테스트 작성에 국한.
- DB 마이그레이션 없음(category 컬럼 이미 VARCHAR — 엔티티 필드 타입만 변경).
- Kotlin `.kt` 주석 금지(규약) — 설명은 커밋/문서로.
- 각 task 후 논리 단위 커밋. 스토리 체크포인트에서 독립 검증.
- 후속: 원칙 V 문구 조정(`/speckit-constitution`), #16 판정 로직이 어그리게이트/코드 enum 소비.
