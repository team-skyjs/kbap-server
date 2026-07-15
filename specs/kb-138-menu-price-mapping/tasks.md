# Tasks: 메뉴판 사진 → 메뉴명·가격 추출 퀄리티 실험 (스파이크)

**Input**: Design documents from `specs/kb-138-menu-price-mapping/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/experiment-files.md, quickstart.md

**Tests**: Test-First (헌법 원칙 I) — 순수 로직(파서·지표 계산·manifest 로딩)은 실패 테스트 먼저. 실호출 하네스 스펙은 `LlmSmokeTest` 관례의 opt-in 수동 실행 도구라 Red 확인 대상이 아니다(파일럿 실행이 검증을 대신한다).

**Organization**: 유저 스토리별 그룹 — US1(추출) → US2(지표) → US3(결론 문서). 전 작업이 `:infra:llm` **테스트 소스셋**과 스펙 디렉터리 안에서만 이뤄진다(프로덕션 diff 0 — FR-008).

**⚠️ 수작업 협업**: T001·T011 은 실험자(사용자)가 메뉴판 사진과 접근 가능한 URL(S3 presigned 권장)을 제공해야 진행 가능하다.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

**Purpose**: 실험 자산 디렉터리와 파일럿 샘플 1건 준비

- [ ] T001 파일럿 샘플 준비(수작업 협업): 메뉴판 사진 1장을 외부 접근 가능한 URL 로 올리고 `specs/kb-138-menu-price-mapping/experiment/samples.json` 을 계약(contracts/experiment-files.md §1) 형식으로 작성 — `id`·`imageUrl`·`conditions`·수기 정답 `label` 포함

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 세 스토리가 공유하는 실험 데이터 모델 + manifest 로딩

- [ ] T002 [P] manifest 로딩 실패 테스트 작성(Red 확인): `infra/llm/src/test/kotlin/com/kbap/infra/llm/experiment/ExperimentModelsTest.kt` — samples.json 파싱(라벨 인라인·conditions), `imageUrl` 이 http(s)가 아니면 거부(FR-001 가드), `label` 빈 목록 거부
- [ ] T003 데이터 모델 + 로딩 구현(Green): `infra/llm/src/test/kotlin/com/kbap/infra/llm/experiment/ExperimentModels.kt` — `MenuPriceItem`·`ExperimentSample`·`SampleResult`·`MatchedPair`·`ExperimentSummary` data class(data-model.md)와 Jackson manifest 로더

**Checkpoint**: `./gradlew :infra:llm:test --tests "*ExperimentModelsTest"` Green — 스토리 작업 시작 가능

---

## Phase 3: User Story 1 - 이미지 URL 로 메뉴명·가격 구조화 추출 (Priority: P1) 🎯 MVP

**Goal**: 메뉴판 사진 URL 1건을 GPT vision 에 넘겨 메뉴명·가격 쌍 JSON 을 얻는다. 이미지 바이트는 하네스를 통과하지 않는다.

**Independent Test**: 파일럿 샘플 1장으로 하네스를 opt-in 실행해 구조화 추출 결과가 출력되고, 접근 불가 URL 은 원인이 보고되는지 확인 (spec US1 수용 시나리오 1~3)

### Tests for User Story 1 (Test-First — 먼저 작성하고 Red 확인) ⚠️

- [ ] T004 [P] [US1] LLM 응답 파서 실패 테스트 작성(Red 확인): `infra/llm/src/test/kotlin/com/kbap/infra/llm/experiment/MenuPriceParserTest.kt` — 정상 JSON 배열, ```json 코드펜스 감싼 응답, `price: null`, 빈 배열, JSON 아님/배열 아님/name 누락 시 파싱 실패 구분

### Implementation for User Story 1

- [ ] T005 [US1] 파서 구현(Green→Refactor): `infra/llm/src/test/kotlin/com/kbap/infra/llm/experiment/MenuPriceParser.kt` — 코드펜스 제거 + Jackson 파싱(기존 `ScannedNameParser` 관례), 실패는 사유 담긴 결과로 반환(예외로 하네스 중단 금지)
- [ ] T006 [US1] 실호출 하네스 작성: `infra/llm/src/test/kotlin/com/kbap/infra/llm/experiment/MenuBoardVisionExperimentTest.kt` — `@EnabledIf`(`llm.vision.experiment.enabled`, `LlmSmokeTest` 패턴), `OpenAiChatModel` 직접 생성(`LlmConfiguration.openAiChatOptions` internal 재사용, 모델 기본 `gpt-4o-mini`·`-Dllm.vision.experiment.model` 오버라이드), `UserMessage` + URL `Media` 로 contracts §2 프롬프트 전송, 샘플별 실패는 `error` 로 기록하고 다음 샘플 진행(FR-009 — 전체 중단 금지). URL Media 가 base64 변환되는 동작이 확인되면 research.md R3 fallback(raw RestClient)으로 전환
- [ ] T007 [US1] 파일럿 실행·검증(수동, `OPENAI_API_KEY` 필요): quickstart §2 명령으로 T001 샘플 실행 — 구조화 추출 확인 + 만료/오염 URL 1건을 임시로 넣어 `error` 보고 확인(US1 수용 시나리오 3). 관찰 결과(응답 모양·프롬프트 미세조정 필요 여부)를 `experiment/report.md` 초안 메모로 남김

**Checkpoint**: US1 완결 — URL 만으로 추출이 재현된다 (SC-003)

---

## Phase 4: User Story 2 - 정답 라벨 대비 지표 측정 (Priority: P2)

**Goal**: 조건 다양한 샘플셋 전체에 대해 지표 4종(메뉴명 정확도·가격 정확도·지연·토큰/비용)을 `results.json` 으로 산출한다.

**Independent Test**: 라벨 있는 샘플 3장만으로 지표 4종이 샘플별·집계로 산출되는지 확인 (spec US2 Independent Test)

### Tests for User Story 2 (Test-First — 먼저 작성하고 Red 확인) ⚠️

- [ ] T008 [P] [US2] 지표 계산 실패 테스트 작성(Red 확인): `infra/llm/src/test/kotlin/com/kbap/infra/llm/experiment/ExperimentMetricsTest.kt` — 이름 정규화(트림·공백 제거) 정확 일치 매칭, MISSING/SPURIOUS 분류, 가격 정확도(null 라벨 일치 포함·가격 있는 항목만 분모), 집계(`menuNameAccuracy`·`priceAccuracy`·평균 지연·비용 합·장당 비용, 실패 샘플 제외) — research.md R6 규칙 전수

### Implementation for User Story 2

- [ ] T009 [US2] 지표 계산 구현(Green→Refactor): `infra/llm/src/test/kotlin/com/kbap/infra/llm/experiment/ExperimentMetrics.kt`
- [ ] T010 [US2] 하네스에 측정·기록 통합: `MenuBoardVisionExperimentTest.kt` 에 지연(벽시계 ms)·토큰(`ChatResponse.metadata.usage`)·비용(`LlmPricing` 재사용) 수집과 contracts §3 형식 `experiment/results.json` 기록(`-Dllm.vision.experiment.output` 오버라이드) 추가
- [ ] T011 [US2] 본 샘플셋 구축(수작업 협업): 사진 10~20장 — 인쇄/손글씨·조명·각도·해상도·영문 병기 조건 커버(FR-003) — URL 업로드 + `samples.json` 에 수기 라벨 작성(FR-004, 다중 가격은 항목 분리 규칙 준수)
- [ ] T012 [US2] 전체 실험 실행(수동): 기본 `gpt-4o-mini` 로 전체 실행 → `experiment/results.json` 커밋. 품질 미달 판단 시 `-Dllm.vision.experiment.model=gpt-4o` 재실행해 두 결과 보존(비용/품질 비교 데이터)

**Checkpoint**: US2 완결 — 샘플 10장+ 지표가 samples/results 로 남는다 (SC-001·SC-002)

---

## Phase 5: User Story 3 - 현행 방식 대비 비교와 결론 문서 (Priority: P3)

**Goal**: 채택/미채택 판단 근거를 제3자가 읽을 수 있는 결론 문서로 남긴다.

**Independent Test**: `experiment/report.md` 가 contracts §4 필수 섹션 6개를 모두 갖추고, 문서만 읽고 판단 근거가 이해되는지 확인 (SC-004)

### Implementation for User Story 3

- [ ] T013 [US3] 결론 문서 작성: `specs/kb-138-menu-price-mapping/experiment/report.md` — ① 실행 조건 ② 지표 요약 ③ 오류 분석(정규화 불일치 쌍의 오타 수기 분류 포함) ④ 현행 방식(클라이언트 OCR + Upstage 텍스트 정제) 비교표(FR-006) ⑤ 채택/미채택 결론·근거(FR-007) ⑥ 후속 이슈 목록(`LlmChatRequest` 이미지 입력 확장·가격 스키마/응답 필드·`ScannedNameInterpreter` seam 재설계·Gemini URL fetch 미지원 대응)

**Checkpoint**: US3 완결 — DoD 전 항목 충족

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T014 [P] CI 안전·무변경 검증: 플래그 없이 `./gradlew :infra:llm:test` 실행 → 하네스 스펙 skip 확인, `git diff develop --stat` 으로 프로덕션 소스(`src/main`) 변경 0건 확인 (SC-005)
- [ ] T015 Jira KB-138 마무리: DoD 체크박스 상태 갱신 + 후속 이슈 후보를 코멘트로 등록(report.md §⑥ 전사)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (P1단계)**: 의존 없음 — 단, 사용자 사진/URL 제공 필요
- **Foundational (P2단계)**: 의존 없음(코드만) — T001 과 병렬 가능
- **US1**: T003(모델) 완료 후. T004→T005→T006→T007 순차(T004 는 T002 와 병렬 가능)
- **US2**: US1 완료 후(하네스에 통합하므로). T008 은 T003 직후 병렬 시작 가능, T011 은 코드와 무관하게 병렬
- **US3**: T012 의 results.json 필요
- **Polish**: 전 스토리 완료 후

### Parallel Opportunities

- T001(수작업) ∥ T002~T003(코드)
- T002 ∥ T004 ∥ T008 — 서로 다른 테스트 파일(단 각 Green 은 자기 Red 이후)
- T011(샘플 구축·수작업) ∥ T008~T010(코드)

## Implementation Strategy

**MVP = US1**: 파일럿 1장으로 "URL → 구조화 추출"이 검증되면 그 시점에 이미 사용자 질문(이미지 URL 로 되는가)의 실증 답이 나온다. 이후 US2·US3 는 측정과 문서다. 실행 순서 요약: T001∥T002 → T003 → T004 → T005 → T006 → T007(파일럿) → T008 → T009 → T010 → T011∥ → T12 → T013 → T014·T015.

## Notes

- 총 15 tasks — US1: 4개(T004~T007), US2: 5개(T008~T012), US3: 1개(T013), Setup/Foundational: 3개, Polish: 2개
- 수작업(사진·라벨·리포트): T001·T007·T011·T012·T013 — 코드 작업은 6개 파일 전부 `infra/llm/src/test/kotlin/com/kbap/infra/llm/experiment/`
- 커밋 단위: task 또는 논리 단위마다(헌법 Workflow)
