# Tasks: 관리자 대시보드 확장 — 가입자·스캔·신규 음식·LLM 비용 주간 지표 시각화

**Input**: Design documents from `specs/kb-264-admin-dashboard-metrics/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/admin-dashboard-page.md

**Tests**: Test-First **NON-NEGOTIABLE**(헌법 원칙 I) — 모든 스토리는 실패 테스트(Red) 작성·확인 후 구현(Green)한다. 테스트는 Kotest BehaviorSpec(given/when/then 한국어), 통합은 MySQL Testcontainers.

**Organization**: 스토리별 독립 구현·검증. 스키마 변경 없음(마이그레이션 태스크 없음).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일·미완료 태스크 의존 없음)
- **[Story]**: US1(가입자 수)·US2(스캔 그래프)·US3(신규 음식 그래프)·US4(LLM 비용 그래프)

## Path Conventions

kbap 멀티모듈 — `common/src/main/kotlin/com/kbap/common/...`, `api/src/{main,test}/kotlin/com/kbap/api/...`, 템플릿 `api/src/main/resources/templates/admin/`, CSS `api/src/main/resources/static/assets/admin.css`.

---

## Phase 1: Setup

없음 — 기존 `com.kbap.api.admin` 패키지·빌드 구성을 그대로 사용한다(신규 의존성·프로젝트 구조 변경 없음).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 4개 스토리가 공유하는 뷰 모델·서비스 골격·컨트롤러 연결 지점. US1 테스트가 이 골격을 Red 로 끌고 가므로 별도 선행 구현 태스크를 두지 않는다 — **Phase 2 없음**(US1 이 골격을 소유).

**Checkpoint**: 즉시 US1 시작 가능.

---

## Phase 3: User Story 1 - 총 가입자 수 확인 (Priority: P1) 🎯 MVP

**Goal**: `/admin/foods` 대시보드에 ACTIVE 회원 기준 총 가입자 수 카드를 추가한다. 서비스 골격(`AdminDashboardMetricsService`·`AdminDashboardMetricsView`)과 컨트롤러 `metrics` 모델 연결을 이 스토리가 만든다.

**Independent Test**: 활성 3명 + 탈퇴 1명 상태에서 서비스가 3을 반환하고, 페이지 모델 `metrics.totalActiveMembers` 로 렌더되는지 단독 검증.

### Tests for User Story 1 (Red 먼저 — 실패 확인 필수) ⚠️

- [x] T001 [US1] `AdminDashboardMetricsServiceTest` 신규 작성 — given(활성·탈퇴 회원 혼재) when(지표 조회) then(totalActiveMembers = 활성 수만) + 회원 0명 시 0 시나리오. `api/src/test/kotlin/com/kbap/api/admin/AdminDashboardMetricsServiceTest.kt` (Testcontainers `@SpringBootTest`). 컴파일 실패 = Red 확인
- [x] T002 [US1] `AdminFoodPageControllerTest` 확장 — given(관리자 인증) when(GET /admin/foods) then(모델 `metrics` 존재·`dashboard` 유지). `api/src/test/kotlin/com/kbap/api/admin/AdminFoodPageControllerTest.kt`. Red 확인

### Implementation for User Story 1

- [x] T003 [US1] `MemberJpaRepository` 에 `countByMemberStatus(memberStatus: MemberStatus): Long` 파생 쿼리 추가. `common/src/main/kotlin/com/kbap/common/domain/member/MemberJpaRepository.kt`
- [x] T004 [US1] `AdminDashboardMetricsService` + `AdminDashboardMetricsView`(우선 `totalActiveMembers` 필드만) 신규 — `@Transactional(readOnly = true)`, `MemberJpaRepository` 직접 주입. `api/src/main/kotlin/com/kbap/api/admin/AdminDashboardMetricsService.kt` → T001 Green
- [x] T005 [US1] `AdminFoodPageController.foods()` 에 `metrics` 모델 속성 추가. `api/src/main/kotlin/com/kbap/api/admin/AdminFoodPageController.kt` → T002 Green
- [x] T006 [US1] `foods.html` 에 총 가입자 수 스탯 카드 추가(기존 `.stat-card` 패턴 재사용, 기존 적재 현황 마크업 무변경). `api/src/main/resources/templates/admin/foods.html`

**Checkpoint**: 가입자 수 카드가 뜨는 MVP — 단독 배포 가능.

---

## Phase 4: User Story 2 - 최근 일주일 요일별 스캔 횟수 그래프 (Priority: P2)

**Goal**: 최근 7일(오늘 포함) 일자별 스캔 횟수를 요일 라벨과 함께 바 차트로 표시. 주간 0-fill 헬퍼와 바 차트 템플릿·CSS 패턴을 이 스토리가 확립한다(US3·US4 재사용).

**Independent Test**: 날짜를 달리한 스캔 이력(7일 경계 밖 포함)을 심고, `weeklyScans` 가 7원소·과거→오늘 순·누락일 0·경계 밖 제외로 반환되고 차트가 렌더되는지 단독 검증.

### Tests for User Story 2 (Red 먼저 — 실패 확인 필수) ⚠️

- [x] T007 [US2] `AdminDashboardMetricsServiceTest` 에 스캔 주간 집계 시나리오 추가 — given(특정 요일만 스캔 존재/7일 밖 이력 존재) then(해당일 카운트·나머지 0·7원소 고정·경계 제외·dayLabel 정확). Red 확인
- [x] T008 [US2] `AdminFoodPageControllerTest` 에 스캔 차트 렌더 시나리오 추가 — then(모델 `metrics.weeklyScans` 7원소, 뷰 렌더 성공). Red 확인

### Implementation for User Story 2

- [x] T009 [US2] `ScanHistoryJpaRepository` 에 일자별 카운트 집계 쿼리 추가 — JPQL `function('date', ...)` group-by + 프로젝션(`DailyCount`), `createdAt >= :from`. `common/src/main/kotlin/com/kbap/common/domain/scan/ScanHistoryJpaRepository.kt`
- [x] T010 [US2] `AdminDashboardMetricsService` 에 `weeklyScans`(`DailyMetricView(date, dayLabel, count)`) + 7일 0-fill·요일 라벨 헬퍼 구현 → T007 Green
- [x] T011 [US2] `foods.html` 에 스캔 바 차트 섹션 + `admin.css` 바 차트 스타일(막대 높이 % 인라인, 요일 라벨·값 표기, 전부 0 이어도 7일 축 유지) 추가. `api/src/main/resources/templates/admin/foods.html`, `api/src/main/resources/static/assets/admin.css` → T008 Green

**Checkpoint**: 가입자 카드 + 스캔 차트 동작 — 단독 배포 가능.

---

## Phase 5: User Story 3 - 최근 일주일 신규 등록 음식 개수 그래프 (Priority: P3)

**Goal**: 최근 7일 일자별 신규 등록 음식 개수 바 차트 추가(US2 가 만든 0-fill 헬퍼·차트 CSS 재사용).

**Independent Test**: 등록 시점이 7일 안/밖인 음식을 심고 `weeklyNewFoods` 집계·렌더를 단독 검증.

### Tests for User Story 3 (Red 먼저 — 실패 확인 필수) ⚠️

- [x] T012 [US3] `AdminDashboardMetricsServiceTest` 에 신규 음식 주간 집계 시나리오 추가 — given(7일 안·밖 등록 혼재) then(안쪽만 일자별 집계·7원소·0-fill). Red 확인

### Implementation for User Story 3

- [x] T013 [US3] `FoodJpaRepository` 에 일자별 신규 등록 카운트 집계 쿼리 추가(T009 와 동일 패턴). `common/src/main/kotlin/com/kbap/common/domain/food/FoodJpaRepository.kt`
- [x] T014 [US3] `AdminDashboardMetricsService` 에 `weeklyNewFoods` 구현 → T012 Green
- [x] T015 [US3] `foods.html` 에 신규 음식 바 차트 섹션 추가(US2 CSS 재사용). `api/src/main/resources/templates/admin/foods.html`

**Checkpoint**: 3지표 동작 — 단독 배포 가능.

---

## Phase 6: User Story 4 - 최근 일주일 LLM 호출 비용 그래프 (Priority: P4)

**Goal**: 최근 7일 일자별 LLM 호출 비용 합계(USD, `cost_usd`) 바 차트 추가. 1달러 미만 소수 값도 그대로 표시.

**Independent Test**: 일자별 `llm_call_cost` 기록(소수 비용 포함)을 심고 `weeklyLlmCostUsd` 합계·렌더를 단독 검증.

### Tests for User Story 4 (Red 먼저 — 실패 확인 필수) ⚠️

- [ ] T016 [US4] `AdminDashboardMetricsServiceTest` 에 LLM 비용 주간 집계 시나리오 추가 — given(일부 날짜만 비용 기록·1달러 미만 소수 포함) then(일자별 sum·없는 날 0·소수 정밀도 유지). Red 확인

### Implementation for User Story 4

- [ ] T017 [US4] `LlmCallCostJpaRepository` 에 일자별 `sum(costUsd)` 집계 쿼리 추가(`DailyCostSum` 프로젝션). `common/src/main/kotlin/com/kbap/common/domain/metering/LlmCallCostJpaRepository.kt`
- [ ] T018 [US4] `AdminDashboardMetricsService` 에 `weeklyLlmCostUsd`(`DailyCostView(date, dayLabel, costUsd)`) 구현 → T016 Green
- [ ] T019 [US4] `foods.html` 에 LLM 비용 바 차트 섹션 추가(값 라벨 USD 소수 표시, BigDecimal 높이 비율 계산 주의). `api/src/main/resources/templates/admin/foods.html`

**Checkpoint**: 4지표 전부 동작.

---

## Phase 7: Polish & Cross-Cutting

- [ ] T020 [P] 데이터 전무(회원·스캔·음식·비용 0건) 상태에서 페이지 렌더가 깨지지 않고 7일 축·0 값이 표시되는 통합 시나리오를 `AdminFoodPageControllerTest` 에 보강(스펙 Edge Case). `api/src/test/kotlin/com/kbap/api/admin/AdminFoodPageControllerTest.kt`
- [ ] T021 `./gradlew build` 전체 통과 확인(ArchUnit `ModuleBoundaryTest` 포함) — 회귀·경계 위반 없음(SC-003)
- [ ] T022 quickstart.md 절차로 로컬 화면 수동 확인(`SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun` → /admin/foods)

---

## Dependencies & Execution Order

- **Phase 1·2 없음** → US1 부터 시작.
- **US1 (T001–T006)**: 서비스 골격·컨트롤러 연결을 만들므로 **모든 후속 스토리의 선행**.
- **US2 (T007–T011)**: US1 이후. 0-fill 헬퍼·차트 CSS 를 확립하므로 US3·US4 의 템플릿 태스크가 이를 재사용(진행 병렬 가능하나 T011 이 먼저 끝나는 게 편함).
- **US3 (T012–T015) / US4 (T016–T019)**: US1 이후 서로 독립 — 병렬 가능. 리포지토리 태스크 T009·T013·T017 은 서로 다른 파일이라 전부 [P] 성격(단, 같은 서비스 파일을 고치는 T010·T014·T018 은 순차).
- **Polish (T020–T022)**: 전 스토리 완료 후.

```text
US1 (MVP) ──┬── US2 ──┐
            ├── US3 ──┼── Polish
            └── US4 ──┘
```

## Parallel Execution Examples

- US1 완료 후: T009(scan repo) · T013(food repo) · T017(metering repo) 동시 진행 가능(파일 분리).
- 서비스 구현 T010 → T014 → T018 은 같은 파일이라 순차(또는 한 번에 묶어 진행).
- T020 은 다른 폴리시 태스크와 병렬 가능.

## Implementation Strategy

- **MVP = US1**: 가입자 수 카드만으로 배포 가능한 최소 증분.
- 이후 US2(차트 패턴 확립) → US3·US4(패턴 복제) 순 증분 배포.
- 각 스토리는 Red 확인 → Green → 리팩터 순서를 지킨다(헌법 I). 스토리 단위로 커밋.
