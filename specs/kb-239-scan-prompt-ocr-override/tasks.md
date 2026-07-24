# Tasks: 메뉴판 스캔 — 사진 판독 메뉴명이 OCR 텍스트를 덮어쓰도록 인식 지시 개선

**Input**: Design documents from `/specs/kb-239-scan-prompt-ocr-override/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md

**Tests**: 신규 테스트 없음 — 변경 대상이 프롬프트 문자열 상수뿐이라 자동 검증할 로직 분기가 없다. 문자열 존재를 assert 하는 테스트는 개선의 성패(모델의 오타 교정)를 보장하지 못해 두지 않는다(research.md Decision 5, 사용자 결정). 기존 테스트는 회귀 가드로 무수정 통과시킨다.

**Organization**: 스토리별로 묶되, 변경 파일은 `OpenAiMenuBoardVisionExtractor.kt` 하나다 — 스토리 간 [P] 병렬은 없다(의도된 것).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일·의존 없음). 이 기능에는 해당 태스크가 없다.
- **[Story]**: US1(사진 판독 우선)·US2(비메뉴 제외 회귀 방지)·US3(API 계약 동결)

## Path Conventions

Gradle 멀티모듈 — 변경은 `:infra:llm` 의 파일 하나:

- `infra/llm/src/main/kotlin/com/kbap/infra/llm/menu/OpenAiMenuBoardVisionExtractor.kt`

---

## Phase 1: Setup

**없음** — 기존 파일 위에서 작업한다. 신규 파일·의존·스키마가 없다(plan.md Structure Decision).

---

## Phase 2: Foundational

**없음** — 스토리 간 공유 선행 작업이 없다.

---

## Phase 3: User Story 1 - 오타 섞인 OCR 에도 제대로 된 메뉴명을 본다 (Priority: P1) 🎯 MVP

**Goal**: 프롬프트에 (1) OCR 텍스트 오타 가능성 고지, (2) 사진 판독과 다르면 사진을 따르라는 규칙을 넣어, 오타 OCR 이 최종 메뉴명을 오염시키지 않게 한다(FR-001·FR-002).

**Independent Test**: 실사진 스캔에서 오타 `rawMenuName`(예: `김치피개`)을 보내도 응답 메뉴명이 사진 표기(`김치찌개`)로 나온다(quickstart.md 수동 검증 SC-001).

- [X] T001 [US1] `OpenAiMenuBoardVisionExtractor.kt` 의 `SYSTEM_PROMPT` 규칙 목록에 "OCR 텍스트는 참고 메타정보이며 오타가 있을 수 있다 / 사진에서 읽은 메뉴명과 다르면 사진을 따른다 / 그대로 옮겨 적지 않는다" 규칙을 추가한다.
- [X] T002 [US1] `OpenAiMenuBoardVisionExtractor.kt` 의 `userPromptWith` OCR 목록 안내문을 참고용 표현으로 정렬한다("OCR 한 참고 정보다 … 오타가 섞일 수 있으니 메뉴명은 사진을 보고 판단해라"). `matchedIdx`·price 문장은 무수정.

**Checkpoint**: 프롬프트가 두 규칙을 전달한다.

---

## Phase 4: User Story 2 - 메뉴가 아닌 글자는 여전히 결과에 없다 (Priority: P2)

**Goal**: 사진 우선 강조가 "OCR 목록 전체를 결과로 되돌려주는" 해석으로 번지지 않게, 결과 기준이 사진 추출 메뉴임을 프롬프트에 명시한다(FR-004, research.md Decision 4).

**Independent Test**: 노이즈(상호·원산지·영업시간)가 섞인 실사진 스캔 결과에 해당 항목이 없다(SC-004).

- [X] T003 [US2] `OpenAiMenuBoardVisionExtractor.kt` 의 비메뉴 제외 규칙 옆에 "results 는 사진에서 추출한 메뉴 기준이다 — OCR 항목마다 결과를 만들지 않는다"를 덧붙인다.

**Checkpoint**: 노이즈 유입 방어 문장이 규칙에 들어갔다.

---

## Phase 5: User Story 3 - 앱 업데이트 없이 개선 효과를 받는다 (Priority: P3)

**Goal**: 요청·응답 계약이 실제로 0건 변경임을 확인한다(FR-005). 검증 전용 스토리다.

**Independent Test**: `git diff` 에 DTO·seam·컨트롤러 변경이 없고 전 모듈 테스트가 무수정 통과한다.

- [X] T004 [US3] 계약 동결 확인 — `git diff --stat develop` 으로 변경 파일이 `OpenAiMenuBoardVisionExtractor.kt`(+ specs 문서)뿐인지 확인한다.

**Checkpoint**: 계약 동결 입증.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T005 회귀 검증 — `./gradlew build` 통과 확인(기존 테스트·ArchUnit 무수정 통과).
- [ ] T006 실사진 수동 검증(quickstart.md 절차) — 오타 `rawMenuName` 요청으로 SC-001~SC-005 확인 후 결과를 Jira KB-239 에 코멘트로 기록.

---

## Dependencies & Execution Order

- **US1 (T001→T002)**: 선행 없음 — 같은 파일이라 순차.
- **US2 (T003)**: 같은 파일이므로 US1 완료 후.
- **US3 (T004)**: T001~T003 완료 후(검증 대상이 최종 diff).
- **Polish (T005→T006)**: 전 스토리 완료 후. T006 은 로컬 실행 환경(OpenAI 키) 필요.

### Parallel Opportunities

**없음(의도된 것)** — 전 태스크가 같은 파일 또는 전체 빌드를 대상으로 한다.

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. T001 → T002
2. **STOP and VALIDATE**: 실사진으로 오타 교정 확인 — 이 시점만으로 배포 가능하다.

### Incremental Delivery

1. US1 → 오타 교정 규칙 전달 (MVP)
2. US2 → 노이즈 유입 방어 문장 추가
3. US3 → 계약 동결 입증
4. Polish → 회귀 빌드 + 실사진 검증·기록

---

## Notes

- 프롬프트 문구를 assert 하는 테스트는 만들지 않는다(research.md Decision 5).
- Kotlin 주석 규약 준수 — 프롬프트 상수에 서사형 주석을 달지 않는다(기존 파일 헤더 라인 주석 수준 유지).
