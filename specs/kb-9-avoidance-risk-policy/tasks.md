---

description: "Task list for KB-9 기피성분 위험도 정책 + 음식 종합 위험도 판정"
---

# Tasks: 기피성분 포함 확률 기반 위험도 정책 + 음식 종합 위험도 판정 (KB-9)

**Input**: Design documents from `specs/kb-9-avoidance-risk-policy/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/food-detail-api.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 각 스토리는 구현 전 실패 테스트(Red)를 먼저 작성하고 Red 를 확인한다.

**Organization**: 스토리별 그룹. US1(성분별 실제 위험도) → US2(음식 종합 위험도) → US3(판정 불가 UNKNOWN 안전).

**Test style (고정)**: 모든 테스트는 Kotest `BehaviorSpec`(given/`when`/then 한국어). Spring 통합은 `SpringExtension` + `@AutoConfigureMockMvc`. Kotlin 소스 주석 금지.

## Path / 규약

- 소스는 각 모듈 `src/main/kotlin/...`, 테스트는 `src/test/kotlin/...`에서 패키지 미러링.
- **스키마/영속 변경 없음** — Flyway·JPA 엔티티 태스크 없음. 위험도는 로드된 도메인 위 순수 계산.
- 의존 방향: `:app:api` → `:application:client` → `:core:{food}` → `:core:kernel`.

---

## Phase 1: Setup (Shared)

**Purpose**: 기준선 확인. 신규 모듈·의존성 없음.

- [X] T001 브랜치 `kb-9-avoidance-risk-policy`에서 `./gradlew build` 그린 기준선 확인(기존 목 동작 상태에서 시작점 고정)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 확률→위험도 매핑을 `:core:kernel` `RiskLevel`에 단일 출처로 도입 — US1·US2 가 공유하는 규칙(FR-001·FR-010).

**⚠️ CRITICAL**: 이 매핑 없이는 어떤 스토리도 시작할 수 없다.

- [X] T002 [P] `RiskLevel.fromInclusionProbability` 실패 테스트 작성 in `core/kernel/src/test/kotlin/com/meogo/core/kernel/risk/RiskLevelTest.kt` — 경계값 given/when/then: p=9→SAFE, 10→CAUTION, 59→CAUTION, 60→DANGER, 100→DANGER. 작성 후 **Red 확인**.
- [X] T003 `RiskLevel`에 임계값 상수(`CAUTION_AT_LEAST=10`·`DANGER_AT_LEAST=60`)와 `fromInclusionProbability(probability: Int): RiskLevel` 추가 in `core/kernel/src/main/kotlin/com/meogo/core/kernel/risk/RiskLevel.kt` → T002 Green

**Checkpoint**: 확률→위험도 단일 규칙 준비 완료.

---

## Phase 3: User Story 1 - 포함 확률 기반 성분별 실제 위험도 (Priority: P1) 🎯 MVP

**Goal**: 음식 상세의 각 성분 `riskStatus`를 목이 아니라 포함 확률 기반 실제값으로 산출한다.

**Independent Test**: 여러 확률의 성분이 든 음식을 상세 조회해 각 성분 riskStatus 가 정책(경계 포함)과 일치하는지로 단독 검증. (overallRiskStatus 없이도 성립.)

### Tests for User Story 1 (Test-First — 먼저 작성, FAIL 확인) ⚠️

- [X] T004 [P] [US1] `FoodAvoidanceSubstance.riskLevel()` 실패 테스트 작성 in `core/food/src/test/kotlin/com/meogo/core/food/FoodAvoidanceSubstanceTest.kt` — 확률별 riskLevel 반환(경계 포함). **Red 확인**.
- [X] T005 [US1] `GetFoodDetailUseCaseTest` 갱신(실패 상태) in `application/client/src/test/kotlin/com/meogo/application/client/food/usecase/GetFoodDetailUseCaseTest.kt` — 성분별 riskStatus 가 `MockAvoidanceRiskMarker` 값이 아니라 확률 산출값(SOY100→DANGER·WHEAT80→DANGER)임을 검증하도록 수정. **Red 확인**.
- [X] T006 [US1] `FoodDetailControllerTest` 갱신(실패 상태) in `app/api/src/test/kotlin/com/meogo/app/api/food/FoodDetailControllerTest.kt` — 된장찌개 성분별 riskStatus 기대값을 실제 정책(SOY100→DANGER·WHEAT80→DANGER·CLAM50→CAUTION)으로 수정. **Red 확인**.

### Implementation for User Story 1

- [X] T007 [P] [US1] `FoodAvoidanceSubstance.riskLevel(): RiskLevel = RiskLevel.fromInclusionProbability(inclusionProbability)` 추가 in `core/food/src/main/kotlin/com/meogo/core/food/FoodAvoidanceSubstance.kt` → T004 Green
- [X] T008 [US1] `GetFoodDetailUseCase`에서 성분별 `riskStatus = substance.riskLevel()`로 대체하고 `MockAvoidanceRiskMarker` 의존·호출 제거 in `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/GetFoodDetailUseCase.kt` → T005 Green (언어 폴백·KB-47 카탈로그 결측 skip·NOT_FOUND 400 로직 유지)
- [X] T009 [P] [US1] `MockAvoidanceRiskMarker.kt` 및 `MockAvoidanceRiskMarkerTest.kt` 삭제 in `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/` · `application/client/src/test/kotlin/com/meogo/application/client/food/usecase/`
- [X] T010 [US1] `FoodDetailResponse` Swagger 문구에서 성분 riskStatus "mock" 표현 제거(포함 확률 기반 실제값으로 갱신) in `app/api/src/main/kotlin/com/meogo/app/api/food/FoodDetailResponse.kt` → T006 Green(성분별 부분)

**Checkpoint**: 성분별 riskStatus 가 실제 정책값. 목 성분 위험도 제거 완료 — 독립 배포 가능(MVP).

---

## Phase 4: User Story 2 - 사용자 회피 성분 기반 음식 종합 위험도 (Priority: P1)

**Goal**: 사용자 회피 성분 ∩ 음식 포함 성분의 최악값을 음식 종합 위험도로 판정해 응답 최상위 `overallRiskStatus`로 제공한다. 회피 목록은 목(mock) 제공.

**Independent Test**: 성분·확률과 목 회피 목록을 달리한 음식을 조회해 overallRiskStatus 가 최악값 규칙(공집합→SAFE 포함)과 일치하는지로 단독 검증.

### Tests for User Story 2 (Test-First — 먼저 작성, FAIL 확인) ⚠️

- [X] T011 [P] [US2] `RiskLevel.aggregate` 실패 테스트 작성 in `core/kernel/src/test/kotlin/com/meogo/core/kernel/risk/RiskLevelTest.kt` — `aggregate([])`→SAFE, `aggregate([SAFE,SAFE])`→SAFE, `aggregate([SAFE,CAUTION,DANGER])`→DANGER. **Red 확인**. (UNKNOWN 케이스는 US3)
- [X] T012 [P] [US2] `Food.overallRisk` 실패 테스트 작성 in `core/food/src/test/kotlin/com/meogo/core/food/FoodOverallRiskTest.kt` — 교집합 최악값(`{SOY}`→DANGER·`{CLAM}`→CAUTION), 공집합(`{MILK}`)→SAFE, 빈 avoidedCodes→SAFE, 성분 없는 음식→SAFE. **Red 확인**.
- [X] T013 [P] [US2] `MockAvoidedSubstanceProvider` 실패 테스트 작성 in `application/client/src/test/kotlin/com/meogo/application/client/food/usecase/MockAvoidedSubstanceProviderTest.kt` — 고정 회피 집합(예: SOY·MILK·PEANUT·SHRIMP·EGG) 반환 검증. **Red 확인**.
- [X] T014 [US2] `GetFoodDetailUseCaseTest`에 종합 위험도 케이스 추가(실패) in `application/client/src/test/kotlin/com/meogo/application/client/food/usecase/GetFoodDetailUseCaseTest.kt` — 목 회피 기반 `overallRiskStatus` 계산(된장찌개→DANGER, 회피 교집합 공집합 음식→SAFE)을 검증. **Red 확인**.
- [X] T015 [US2] `FoodDetailControllerTest`에 `payload.overallRiskStatus` 검증 추가(실패) in `app/api/src/test/kotlin/com/meogo/app/api/food/FoodDetailControllerTest.kt` — 된장찌개 조회 시 `overallRiskStatus == "DANGER"`. **Red 확인**.

### Implementation for User Story 2

- [X] T016 [P] [US2] `RiskLevel`에 `severity` + `aggregate(levels)`(빈→SAFE, 최악값; UNKNOWN 분기는 US3에서 추가) 도입 in `core/kernel/src/main/kotlin/com/meogo/core/kernel/risk/RiskLevel.kt` → T011 Green
- [X] T017 [US2] `Food.overallRisk(avoidedCodes: Set<String>): RiskLevel`(교집합 → `riskLevel()` 매핑 → `RiskLevel.aggregate`) 추가 in `core/food/src/main/kotlin/com/meogo/core/food/Food.kt` → T012 Green (avoidance enum 미import — String 코드만)
- [X] T018 [P] [US2] `AvoidedSubstanceProvider` port 인터페이스 생성 in `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/AvoidedSubstanceProvider.kt`
- [X] T019 [US2] `MockAvoidedSubstanceProvider`(@Component, 고정 집합 반환) 구현 in `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/MockAvoidedSubstanceProvider.kt` → T013 Green
- [X] T020 [US2] `GetFoodDetailResult`에 `overallRiskStatus: RiskLevel` 추가 in `application/client/src/main/kotlin/com/meogo/application/client/food/dto/GetFoodDetailResult.kt`
- [X] T021 [US2] `GetFoodDetailUseCase`에 `AvoidedSubstanceProvider` 주입 → `avoidedCodes = provider.avoidedCodes().map { it.name }.toSet()` → `overallRiskStatus = food.overallRisk(avoidedCodes)` 결선 in `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/GetFoodDetailUseCase.kt` → T014 Green
- [X] T022 [US2] `FoodDetailResponse`에 최상위 `overallRiskStatus: String`(= `result.overallRiskStatus.name`) 추가 + `from` 매핑 + Swagger 문서화 in `app/api/src/main/kotlin/com/meogo/app/api/food/FoodDetailResponse.kt` → T015 Green
- [X] T023 [US2] `contracts/food-detail-api.md`의 목 회피 집합·산출 예시가 T019 실제 집합과 일치하는지 대조·정합(불일치 시 문서 또는 목 집합 조정)

**Checkpoint**: US1 + US2 동작 — 성분별 실제 위험도 + 사용자(목) 기반 음식 종합 위험도.

---

## Phase 5: User Story 3 - 판정 불가 시 UNKNOWN(안전 오도 없음) (Priority: P2)

**Goal**: 판정 불가를 SAFE 로 오도하지 않는다. `aggregate`의 UNKNOWN 우선 규칙(§8) 확정 + 미등록 음식은 현행 400 유지(§5, R7) 회귀 보장.

**Independent Test**: `aggregate`에 UNKNOWN 을 섞으면 결과가 UNKNOWN(다른 값보다 우선)인지, 미등록 메뉴명이 SAFE/UNKNOWN 200 이 아니라 400 인지로 단독 검증.

### Tests for User Story 3 (Test-First — 먼저 작성, FAIL 확인) ⚠️

- [X] T024 [P] [US3] `RiskLevel.aggregate` UNKNOWN 우선 실패 테스트 추가 in `core/kernel/src/test/kotlin/com/meogo/core/kernel/risk/RiskLevelTest.kt` — `aggregate([SAFE,UNKNOWN])`→UNKNOWN, `aggregate([DANGER,UNKNOWN])`→UNKNOWN. **Red 확인**.
- [X] T025 [P] [US3] 미등록 음식 400 회귀 테스트 확인/보강 in `app/api/src/test/kotlin/com/meogo/app/api/food/FoodDetailControllerTest.kt`(또는 `FoodDetailErrorTest.kt`) — 미수록 메뉴명 조회 시 400·"해당 음식 정보 없음"(200+overallRiskStatus 아님) 검증. 이미 있으면 정책 도입 후에도 그린 유지 확인.

### Implementation for User Story 3

- [X] T026 [US3] `RiskLevel.aggregate`에 UNKNOWN 우선 분기(`levels.any { it == UNKNOWN } -> UNKNOWN`, 최악값보다 먼저) 추가 in `core/kernel/src/main/kotlin/com/meogo/core/kernel/risk/RiskLevel.kt` → T024 Green
- [X] T027 [US3] `GetFoodDetailUseCase`의 미등록 음식 경로가 `FoodException(NOT_FOUND)`(400)로 유지됨을 확인(정책 도입으로 회귀 없음) in `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/GetFoodDetailUseCase.kt` → T025 Green (코드 변경 불필요 시 회귀 확인만)

**Checkpoint**: 모든 스토리 독립 동작 — 판정 불가는 UNKNOWN, 절대 SAFE 아님, 미등록은 400.

---

## Phase 6: Polish & Cross-Cutting

**Purpose**: 문서 정합·경계 강제·전체 검증.

- [X] T028 [P] `ModuleBoundaryTest` 그린 확인 in `app/api/src/test/kotlin/com/meogo/app/api/architecture/ModuleBoundaryTest.kt` — `:core:food` 가 `:core:avoidance` enum 을 import 하지 않음(원칙 II) 보장
- [X] T029 [P] `FoodDetailApi` Operation 설명에서 "mock 위험도" 문구 제거·정책/overallRiskStatus 반영 in `app/api/src/main/kotlin/com/meogo/app/api/food/FoodDetailApi.kt`
- [X] T030 `quickstart.md` A(테스트)·C(회귀 체크리스트) 실행: `./gradlew build` 전체 그린 + `MockAvoidanceRiskMarker` 참조 잔존 0 확인
- [ ] T031 [P] (선택) 로컬 docker MySQL + `SPRING_PROFILES_ACTIVE=local` 기동해 quickstart.md B 실측(된장찌개 overallRiskStatus=DANGER·미등록 400) — IntelliJ 기동 시 8080 점유 주의, broad pkill 금지

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup(P1)**: 즉시 시작.
- **Foundational(P2)**: Setup 후. **모든 스토리 차단**(확률 매핑 공유).
- **US1(P3)**: Foundational 후 시작. 독립 배포 가능(MVP).
- **US2(P4)**: Foundational 후 시작 가능하나 실무상 US1 뒤(같은 유스케이스·DTO·컨트롤러 파일 편집). `aggregate` 기반 스토리.
- **US3(P5)**: US2 의 `aggregate` 도입 뒤(UNKNOWN 분기 augment). 미등록 400 회귀는 US1/US2 무관하게 검증 가능.
- **Polish(P6)**: 원하는 스토리 완료 후.

### User Story Dependencies

- **US1**: 다른 스토리 의존 없음(성분별 실제 위험도). Foundational(T003)만 필요.
- **US2**: Foundational + US1(같은 파일 순차 편집). `overallRiskStatus`·목 회피·`Food.overallRisk`.
- **US3**: US2 의 `RiskLevel.aggregate`를 UNKNOWN 우선으로 확장. 400 회귀는 독립.

### Within Each User Story

- 테스트를 **먼저 작성·FAIL 확인** 후 구현(Constitution I).
- kernel → food → application → app 순(의존 방향).
- 작업/논리 단위마다 커밋.

### Parallel Opportunities

- **US1 tests**: T004(food) [P] 는 T005(application)·T006(app) 와 다른 파일이라 병렬. (T005·T006 도 서로 다른 파일이나 동일 유스케이스 계약을 다뤄 순차 권장.)
- **US1 impl**: T007(food) 와 T009(삭제) 병렬. T008(use case)은 T007·T009 후.
- **US2 tests**: T011(kernel)·T012(food)·T013(provider) 서로 다른 파일 [P].
- **US2 impl**: T016(kernel)·T018(port) [P]. T017(food)은 T016 후, T019는 T018·T013 후.
- **US3 tests**: T024(kernel)·T025(app) [P].
- **Polish**: T028·T029·T031 [P].

---

## Parallel Example: User Story 2 (tests first)

```bash
# 실패 테스트 병렬 작성(서로 다른 파일):
Task: "RiskLevel.aggregate 실패 테스트 in core/kernel/.../RiskLevelTest.kt"          # T011
Task: "Food.overallRisk 실패 테스트 in core/food/.../FoodOverallRiskTest.kt"          # T012
Task: "MockAvoidedSubstanceProvider 실패 테스트 in application/client/.../MockAvoidedSubstanceProviderTest.kt"  # T013

# Green 구현 병렬(서로 다른 파일):
Task: "RiskLevel.aggregate(빈→SAFE·최악값) in core/kernel/.../RiskLevel.kt"           # T016
Task: "AvoidedSubstanceProvider port in application/client/.../AvoidedSubstanceProvider.kt"  # T018
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 Setup → 2. Phase 2 Foundational(확률 매핑) → 3. Phase 3 US1(성분별 실제 위험도) → **STOP & VALIDATE**: 성분 riskStatus 실제값 확인 → 필요 시 배포(목 성분위험도 제거만으로도 가치).

### Incremental Delivery

1. Setup + Foundational → 확률 매핑 준비
2. US1 → 성분별 실제 위험도 → 검증/데모
3. US2 → 음식 종합 위험도(overallRiskStatus, 목 회피) → 검증/데모
4. US3 → UNKNOWN 안전 오도 방지 확정 → 검증/데모
5. Polish → 경계·문서·전체 그린

---

## Notes

- [P] = 서로 다른 파일·미완 태스크 의존 없음.
- 스키마/마이그레이션/엔티티 변경 없음 — 순수 도메인 계산.
- **주의(회귀)**: seed 된장찌개(SOY100·WHEAT80·CLAM50)의 성분별 riskStatus 가 목(CAUTION/SAFE/SAFE)→실제(DANGER/DANGER/CAUTION)로, overall→DANGER 로 바뀐다. T006·T015 에서 기대값을 이에 맞춘다.
- 회피 목록은 목(`MockAvoidedSubstanceProvider`)이며 교집합·판정은 실제 — member·인증 준비 시 목만 실제 구현으로 교체(port 유지).
- 각 스토리 완료 후 독립 검증, 커밋.
