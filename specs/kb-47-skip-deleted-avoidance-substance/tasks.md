# Tasks: 음식 상세조회 — 삭제된 기피 성분 skip 처리(조회 장애 내성)

**Input**: Design documents from `specs/kb-47-skip-deleted-avoidance-substance/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/food-detail-get.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 각 스토리는 구현 전 실패 테스트(Red)를 먼저 작성한다.

**Organization**: 스토리별로 그룹화. 단, 본 기능은 변경 표면이 좁아 두 스토리가 **같은 두 파일**(`GetFoodDetailUseCase.kt`·`GetFoodDetailUseCaseTest.kt`)을 수정하므로 순차 진행한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 의존 없음)
- **[Story]**: US1 / US2
- 파일 경로는 리포지토리 루트 기준

## 대상 파일 (전체)

- `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/GetFoodDetailUseCase.kt` — 수정
- `application/client/src/test/kotlin/com/meogo/application/client/food/usecase/GetFoodDetailUseCaseTest.kt` — 테스트 추가

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 변경 전 그린 베이스라인 확인

- [x] T001 베이스라인 확인 — `./gradlew :application:client:test` 실행해 기존 `GetFoodDetailUseCaseTest` 가 모두 통과함을 확인한다(변경 시작 기준점).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 없음 — 본 기능은 DB 스키마·마이그레이션·엔티티·DTO·신규 모듈이 필요 없다. 기존 유스케이스/포트/Fake 리포지토리가 이미 갖춰져 있어 선행 인프라 작업이 없다.

**Checkpoint**: 별도 선행 없이 곧바로 User Story 1 착수 가능.

---

## Phase 3: User Story 1 - 삭제된 성분이 섞인 음식도 상세조회가 정상 동작한다 (Priority: P1) 🎯 MVP

**Goal**: 카탈로그에 없는(소프트 삭제된) 참조 성분을 예외 대신 skip 하고 나머지 성분으로 상세조회를 정상 조립한다.

**Independent Test**: 참조 성분 중 일부(또는 전부)를 카탈로그에서 제외한 상태로 `getDetail` 호출 시, 예외 없이 삭제 성분만 빠진(또는 빈) 성분 목록과 나머지 상세가 반환되는지로 검증.

### Tests for User Story 1 (Test-First: 먼저 작성하고 실패 확인) ⚠️

- [x] T002 [US1] `GetFoodDetailUseCaseTest.kt` 에 실패 테스트 추가 — given("참조 성분 일부가 카탈로그에 없음(소프트 삭제)"): (a) `when`("성분 2개 중 1개만 카탈로그에 있으면") → `then` 예외 없이 존재하는 1개만 조립, 확률 내림차순 유지. (b) given("참조 성분이 전부 카탈로그에 없음") `when`("조회하면") → `then` `avoidanceSubstances` 빈 목록 + `name`·`spiciness` 정상. 삭제는 Fake `AvoidanceSubstanceRepository` 가 해당 code 를 미반환하는 것으로 재현. `./gradlew :application:client:test` 로 **RED 확인**.

### Implementation for User Story 1

- [x] T003 [US1] `GetFoodDetailUseCase.kt` 성분 조립 루프를 `map { … ?: throw IllegalStateException(...) }` 에서 `partition { code in catalog }` 으로 교체 — 부재 성분은 skip(로그는 US2), 존재 성분만 `map { catalog.getValue(code) … }` 으로 조립(`null` 미사용, 정렬·위험도 룩업 로직 불변). `./gradlew :application:client:test` 로 **GREEN 확인**.
- [x] T004 [US1] 회귀 확인 — 기존 시나리오(참조 성분 전부 존재) 테스트가 그대로 통과하는지 확인해 응답 계약·정렬·번역 폴백 불변을 보장한다.

**Checkpoint**: 삭제 성분이 섞이거나 전부 삭제돼도 상세조회가 500 없이 정상 응답. MVP 완성.

---

## Phase 4: User Story 2 - 삭제된 성분 skip 을 운영이 인지할 수 있다 (Priority: P2)

**Goal**: skip 발생 시 WARN 로그로 `foodId`·`substanceCode` 를 남겨 안전 민감 정합성 깨짐을 운영이 추적할 수 있게 한다.

**Independent Test**: 삭제 성분을 참조하는 음식 조회 시, `GetFoodDetailUseCase` 로거에 WARN 이벤트가 남고 메시지에 `foodId`·`substanceCode` 가 포함되는지로 검증.

**Depends on**: User Story 1(T003) — 로그는 T003 에서 만든 skip 분기 안에 추가된다(같은 파일, 순차).

### Tests for User Story 2 (Test-First: 먼저 작성하고 실패 확인) ⚠️

- [x] T005 [US2] (결정으로 생략) 로그 **방출 여부를 단언하는 테스트는 작성하지 않는다.** 구현 세부(로깅)에 결합되고 깨지기 쉬워 행동 계약 테스트만 남긴다는 사용자 결정(2026-07-05). WARN 로그 자체는 DoD(운영 안전) 요구라 구현(T006)에는 유지한다. skip 의 관찰 가능한 행동(부분/빈 목록·정렬 보존)은 US1 테스트가 이미 커버.

### Implementation for User Story 2

- [x] T006 [US2] `GetFoodDetailUseCase.kt` 에 `LoggerFactory.getLogger(...)` 추가하고, T003 의 부재 그룹 순회(`missing.forEach { (_, code) -> … }`)에서 `log.warn("avoidance substance skipped (catalog missing / soft-deleted): foodId={} substanceCode={}", food.id, code)` 를 남긴다. **GREEN 확인**.

**Checkpoint**: US1 + US2 완성 — skip 이 조용히 넘어가지 않고 관측 가능.

---

## Phase 5: Polish & Cross-Cutting Concerns

- [x] T007 [P] 전체 회귀 — `./gradlew build` 실행해 전 모듈 컴파일·테스트·ArchUnit 모듈 경계(`ModuleBoundaryTest`) 통과를 확인한다.
- [x] T008 [P] `quickstart.md` 검증 절차 수행 — TDD 흐름·검증 명령이 문서대로 재현되는지 확인.
- [x] T009 리팩터 — WARN 메시지/로거 정리(필요 시). Kotlin 주석 금지 규약 준수 확인. 계약·정렬·위험도 로직 불변 유지.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 선행 없음 — 즉시 시작.
- **Foundational (Phase 2)**: N/A(작업 없음).
- **User Story 1 (Phase 3)**: Setup 후 시작. 다른 스토리에 의존하지 않음. **MVP**.
- **User Story 2 (Phase 4)**: US1(T003)의 skip 분기 위에 로그를 얹으므로 **US1 완료 후** 진행(같은 파일).
- **Polish (Phase 5)**: US1·US2 완료 후.

### Within Each User Story

- 테스트를 먼저 쓰고 RED 확인(원칙 I) → 최소 구현으로 GREEN → 리팩터.
- 두 스토리 모두 `GetFoodDetailUseCase.kt` 단일 파일을 수정하므로 스토리 간 병렬 불가(순차).

### Parallel Opportunities

- Phase 5 의 T007·T008 은 서로 독립이라 [P].
- Phase 3·4 는 같은 두 파일을 만지므로 스토리 내/간 병렬 없음.

---

## Implementation Strategy

### MVP First (User Story 1)

1. T001 베이스라인 그린 확인.
2. T002 실패 테스트(RED) → T003 skip 구현(GREEN) → T004 회귀 확인.
3. **STOP & VALIDATE**: 삭제 성분 섞인/전부 삭제 케이스가 200 으로 정상 응답.
4. 여기까지가 조회 장애 내성 MVP.

### Incremental Delivery

1. US1 완료 → 조회 복원(사용자 가치) 확보.
2. US2 추가 → skip 관측성(운영·안전 가치) 확보.
3. Polish → 전체 회귀·문서 검증.

---

## Notes

- [P] = 다른 파일·의존 없음. 본 기능은 변경 파일이 2개뿐이라 [P] 기회가 Polish 에 한정된다.
- Test-First: T002·T005 는 반드시 구현 전에 RED 를 확인한다.
- 작업/논리 단위마다 커밋한다.
- **불변 유지**: DB 스키마·마이그레이션·엔티티·`FoodDetailResponse` DTO·정렬·언어 폴백. 변경은 유스케이스 조립 루프 한 곳 + 로그.
- Kotlin 소스 주석 금지 규약 준수(설명은 커밋 메시지/문서에).
