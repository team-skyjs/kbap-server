# Tasks: 기피성분 조사에서 후보 밖 성분은 항목 단위로 스킵

**Input**: Design documents from `/specs/kb-236-avoidance-item-skip/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md (contracts/ 없음 — seam 시그니처 불변)

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 스토리는 실패 테스트 작성·Red 확인 후 구현한다.

**Organization**: 스토리별 그룹. 변경 파일이 2개(프로덕션 1 + 테스트 1)뿐인 소형 기능이라 Setup/Foundational 단계는 없다 — 기존 모듈·기존 테스트 파일을 그대로 쓴다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(서로 다른 파일·의존 없음). 이 기능은 모든 태스크가 같은 두 파일을 순차 수정하므로 **[P] 태스크 없음**.
- **[Story]**: US1(후보 밖 항목 스킵), US2(나머지 규칙 응답 단위 무효 유지)

## Path Conventions

- 프로덕션: `infra/llm/src/main/kotlin/com/kbap/infra/llm/food/SpringAiFoodAvoidanceAssessmentClient.kt`
- 테스트: `infra/llm/src/test/kotlin/com/kbap/infra/llm/food/SpringAiFoodAvoidanceAssessmentClientTest.kt`

---

## Phase 1: Setup

**해당 없음** — 신규 모듈·의존·구조 변경 없음 (plan.md Structure Decision).

---

## Phase 2: Foundational

**해당 없음** — 기존 `:infra:llm` 클라이언트와 테스트 파일에 국한된 변경.

---

## Phase 3: User Story 1 - 후보 밖 성분이 섞인 응답도 정상 성분만 종합해 저장 (Priority: P1) 🎯 MVP

**Goal**: `parseValidOrNull` 이 후보 밖 코드 항목만 스킵하고 후보 안 정상 성분·맵기를 유효 응답으로 종합한다 — dev 실측 장애(단일 모델 + 후보 밖 코드 혼입 → 유효 0/1 → INCOMPLETE 고착) 해소.

**Independent Test**: 페이크 `LlmModelCaller` 로 후보 밖 코드가 섞인 응답을 주입해 정상 성분·맵기만 종합되는지 `:infra:llm` 단위 테스트로 단독 검증.

### Tests for User Story 1 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T001 [US1] 기존 `given("한 모델이 후보 밖 코드를 섞어 응답")` 의 then 을 새 정책 기대값으로 갱신 — 위반 모델 강등 없이 유효 응답으로 종합에 참여: PORK `(80+90+0)/3 = 57`, spiciness `(3+4+5)/3 = 4` (plan.md 핵심 설계 결정 4) in `infra/llm/src/test/kotlin/com/kbap/infra/llm/food/SpringAiFoodAvoidanceAssessmentClientTest.kt`
- [X] T002 [US1] 신규 `given`(핵심 — dev 실측 재현): 최소 합의 1 구성 + 단일 모델이 후보 안 코드(예: WHEAT 90)와 후보 밖 코드(예: OIL 80)를 섞어 응답 → 예외 없이 WHEAT·맵기만 종합 in `infra/llm/src/test/kotlin/com/kbap/infra/llm/food/SpringAiFoodAvoidanceAssessmentClientTest.kt`
- [X] T003 [US1] 신규 `given`: 후보 밖 코드만 있는 응답(스킵 후 정상 항목 0개) + 유효 맵기 → 유효 응답으로 인정, 성분 없음 + 맵기 종합 (spec US1 시나리오 3) in `infra/llm/src/test/kotlin/com/kbap/infra/llm/food/SpringAiFoodAvoidanceAssessmentClientTest.kt`
- [X] T004 [US1] 신규 `given`(경계 — 스킵이 검증보다 먼저): 후보 밖 코드가 percent 범위 밖(OIL 150)인 경우와 후보 밖 코드끼리 중복(OIL 2회)인 경우 → 응답 무효를 촉발하지 않고 모두 스킵 (spec Edge Cases, research Decision 1) in `infra/llm/src/test/kotlin/com/kbap/infra/llm/food/SpringAiFoodAvoidanceAssessmentClientTest.kt`
- [X] T005 [US1] Red 확인: `./gradlew :infra:llm:test --tests "com.kbap.infra.llm.food.SpringAiFoodAvoidanceAssessmentClientTest"` 실행 — T001~T004 시나리오가 **실패**함을 확인하고 실패 내용 기록

### Implementation for User Story 1

- [X] T006 [US1] Green: `parseValidOrNull` 항목 루프 변경 — `item.code !in candidateCodes` 이면 `valid = false; break` 대신 해당 항목 스킵(continue, percent·중복 검사 없이 제외), 후보 안 항목의 기존 검사(percent `!in 0..100`·중복 `in byCode` → 응답 무효)는 유지 in `infra/llm/src/main/kotlin/com/kbap/infra/llm/food/SpringAiFoodAvoidanceAssessmentClient.kt`
- [X] T007 [US1] Green 확인 + Refactor: `./gradlew :infra:llm:test` 전체 그린 확인, 검증 루프 가독성 정리(주석 규약 준수 — 코드로 표현 불가능한 제약만)

**Checkpoint**: 후보 밖 코드 혼입 응답이 단일 모델 구성에서 유효 응답으로 집계된다 (SC-001) — US1 단독 검증 완료 지점.

---

## Phase 4: User Story 2 - 나머지 무효 규칙은 응답 단위 무효 유지 (Priority: P2)

**Goal**: 완화는 "후보 밖 코드" 하나뿐임을 테스트로 고정 — 후보 안 percent 범위 밖·후보 안 코드 중복·맵기 범위 밖·파싱 실패는 여전히 응답 전체 무효 (FR-003, 안전 가드).

**Independent Test**: 각 무효 규칙별 응답을 주입하는 기존 테스트가 **무수정으로** 통과하는지 확인 — 수정이 필요해진다면 정책이 과도하게 완화된 것.

### Tests for User Story 2 (REQUIRED — Test-First) ⚠️

- [X] T008 [US2] 기존 가드 테스트 무수정 통과 확인: 범위 밖 맵기(11)·맵기 누락·null 맵기·null 포함률·후보 안 코드 중복·파싱 실패·유효 부족 예외 시나리오가 T006 구현 후에도 전부 그대로 통과함을 `./gradlew :infra:llm:test --tests "com.kbap.infra.llm.food.SpringAiFoodAvoidanceAssessmentClientTest"` 로 확인 (수정 발생 시 T006 구현이 과완화된 것 — 구현을 고친다)
- [X] T009 [US2] 신규 `given`(혼재 가드): 후보 밖 코드(스킵 대상)와 후보 안 코드의 percent 범위 밖(예: WHEAT 150)이 한 응답에 공존 → 응답 전체 무효 유지 확인 — 스킵 항목이 후보 안 항목의 무효 신호를 가리지 않음 (spec US2 시나리오 1 + Edge Case 교차) in `infra/llm/src/test/kotlin/com/kbap/infra/llm/food/SpringAiFoodAvoidanceAssessmentClientTest.kt`

**Checkpoint**: 기존 무효 규칙 테스트 전부 무수정 그린 (SC-003) — 완화 범위가 후보 밖 코드 하나로 한정됨이 테스트로 고정된 지점.

---

## Phase 5: Polish & Cross-Cutting Concerns

- [X] T010 전체 빌드 그린 확인: `./gradlew build` — 소비처(`:app:batch` `FoodAvoidanceMapProcessor`) 무수정 통과 포함 (SC-004, quickstart 검증 포인트)

---

## Dependencies & Execution Order

### Phase Dependencies

- Phase 1·2: 해당 없음
- **US1 (Phase 3)**: 즉시 시작 가능. T001→T005 (테스트 먼저, 같은 파일 순차) → T006 (구현) → T007
- **US2 (Phase 4)**: T006(구현) 완료 후 의미 있음 — T008 은 구현 후의 무수정 통과 검증, T009 는 추가 가드
- **Polish (Phase 5)**: T010 은 모든 스토리 완료 후

### Parallel Opportunities

**없음** — 전 태스크가 같은 테스트 파일 1개와 프로덕션 파일 1개를 순차 수정한다. T001~T004 는 논리적으로 독립 시나리오지만 한 파일이므로 한 번의 편집 세션에서 함께 작성하는 것이 실용적이다.

---

## Implementation Strategy

**MVP = US1 단독** (T001~T007): dev 장애 패턴 해소가 곧 이 이슈의 존재 이유. US2(T008~T009)는 정책 고정 가드, Polish(T010)는 최종 회귀 확인. 사실상 단일 TDD 사이클(Red 5개 시나리오 → Green 1개 루프 변경 → 가드 확인)로 완결되는 작업이며, 태스크/논리 단위마다 커밋한다.
