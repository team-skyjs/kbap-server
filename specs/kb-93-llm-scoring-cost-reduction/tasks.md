# Tasks: LLM 스코어링 호출 비용 절감 — 호출당 ₩1 미만 (프롬프트 압축·텍스트 역할 분리)

**Input**: Design documents from `/specs/kb-93-llm-scoring-cost-reduction/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/compressed-scoring-llm-contract.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 스토리는 실패하는 테스트(Red)를 먼저 작성·확인한 뒤 구현(Green)한다. 테스트는 Kotest `BehaviorSpec`(given/when/then 한국어).

**Organization**: 스토리별 독립 구현·검증 단위로 그룹핑. US1(포맷 압축)→US2(역할 분리)는 같은 파일(`ScoringPromptFactory`·`ScoringResponseParser`)을 순차 확장하므로 순서 고정.

**사용자 지시 반영**: OpenAI 모델은 **gpt-5-nano 로 고정**한다(FR-009). US3 에서 gpt-5 계열 전용 파라미터(`max_completion_tokens`·`reasoning_effort=minimal`)로 배선하고, yml 의 model·단가($0.05/$0.40 per 1M)가 gpt-5-nano 기준임을 검증·유지한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일·미완 태스크 의존 없음)
- **[Story]**: US1(압축 포맷)·US2(역할 분리)·US3(모델·옵션 튜닝+실측)·US4(후속 티켓)

## Path Conventions

Gradle 멀티모듈 — `core/research/`, `infra/llm/`, `app/batch/` 아래 `src/{main,test}/kotlin/com/meogo/...` 미러 구조(plan.md Project Structure 참고).

---

## Phase 1: Setup

**Purpose**: 기준선 확보 — KB-53 이 green 인 상태에서 교체를 시작한다

- [X] T001 기준선 확인 — `./gradlew :core:research:test :infra:llm:test :app:batch:test` 전부 통과 확인(KB-53 green 기준선), 실패 시 원인 먼저 해소

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 없음 — KB-53 이 만든 모듈·계약(`:core:research` 순수 서비스, `:infra:llm` fan-out, `:app:batch` 잡)을 그대로 기반으로 사용하므로 신규 차단 선행물이 없다. Phase 1 완료 즉시 US1 착수 가능.

**Checkpoint**: T001 통과 = Foundation ready

---

## Phase 3: User Story 1 - 스코어링 프롬프트·응답을 압축 포맷으로 교체 (Priority: P1) 🎯 MVP

**Goal**: 프롬프트에서 음식·후보 성분을 인덱스 열거(후보는 코드만·라벨 제거·지시문 축약)하고, 응답을 `{"c":[커버 음식 인덱스],"r":[[음식idx,성분idx,score,prob],...]}` 압축 JSON 으로 수신·파싱한다. 판단 결과 의미는 KB-53 과 동일(FR-001~003, contract §1~§3).

**Independent Test**: 실 네트워크 없이 — 압축 프롬프트 골든 테스트(인덱스 열거·코드 전용·축약 지시문·배열 응답 스키마) + 페이크 배열 응답 파싱(유효 범위·이탈/중복/커버리지 규칙 P2~P8) + KB-53 동등성 골든(동일 판단 → 동일 `ModelScoring`)이 `./gradlew :core:research:test` 로 통과.

### Tests for User Story 1 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T002 [P] [US1] `ScoringPromptFactoryTest` 를 압축 포맷 골든으로 갱신 — system(축약 지시문+`c`/`r` 응답 스키마 지시+후보 81종 `성분인덱스:CODE` 코드 전용)·user(`음식인덱스:한국어명`) 분리, 한국어 라벨·설명 지시문 부재 검증, Red 확인 in `core/research/src/test/kotlin/com/meogo/core/research/prompt/ScoringPromptFactoryTest.kt`
- [X] T003 [P] [US1] `ScoringResponseParserTest` 를 인덱스 배열 파서 규칙으로 갱신 — 정상 파싱(부분 응답→미응답 조합 미포함 해석), P2(루트/`r` 불량→예외), P3~P5(형식·인덱스·범위 이탈 항목 스킵), P6(중복 첫-채택), P7(`coveredFoodIds`=`c`∪`r` 합집합), P1(코드펜스 strip), KB-53 동등성 골든(동일 판단 집합의 KB-53 기대 `ModelScoring` = 압축 픽스처 파싱 산출, R6), Red 확인 in `core/research/src/test/kotlin/com/meogo/core/research/parse/ScoringResponseParserTest.kt`

### Implementation for User Story 1

- [X] T004 [US1] `ScoringPromptFactory` 를 압축 포맷으로 재작성(contract §1.1~1.2 — 지시문+후보 목록은 system, 음식 청크는 user, 스코어링 판단 전용) → T002 Green in `core/research/src/main/kotlin/com/meogo/core/research/prompt/ScoringPromptFactory.kt`
- [X] T005 [US1] `ScoringResponseParser` 를 인덱스 배열 파서로 재작성(파싱 규칙 P1~P8, `ModelScoring` 형태 유지 — `descriptions` 는 항상 empty) → T003 Green in `core/research/src/main/kotlin/com/meogo/core/research/parse/ScoringResponseParser.kt`
- [X] T006 [US1] `AvoidanceScoringJobTest` 페이크 fan-out 응답 픽스처를 압축 JSON 으로 이행(잡 로직 무변경 — 확정 게이트·실패 로깅 기존 검증 유지)하고 `./gradlew :core:research:test :app:batch:test` green 확인 in `app/batch/src/test/kotlin/com/meogo/app/batch/scoring/AvoidanceScoringJobTest.kt`

**Checkpoint**: 판단 압축 계약 완성 — 프롬프트·파서가 압축 포맷으로 동작하고 판단 산출은 KB-53 과 동일

---

## Phase 4: User Story 2 - 텍스트 역할 분리 (이름 번역 단일 모델·설명 제외) (Priority: P1)

**Goal**: 음식 설명 지시문을 전 모델에서 완전 제거(이미 US1 프롬프트에 없음 — 골든으로 고정)하고, 음식명 9언어 번역은 `includeNameTranslations` 변형(Gemini 전용, `t` = `[음식idx,[9개 번역—고정 언어 순서]]`)으로만 요청·수신한다. `LlmFanoutClient` 에 모델별 요청 seam 을 추가하고 배치가 GEMINI→번역 변형으로 분기한다(FR-004~008, contract §1.3·§2.2·§4).

**Independent Test**: 페이크만으로 — 변형별 프롬프트 골든(번역 지시 Gemini 변형만·설명 지시 전무) + `t` 파싱(P9/P10) + fan-out 모델별 상이 요청·1회 호출 + selector 단일 소스 병합·설명 null + 잡 레벨(Gemini 만 번역, 번역 누락이 확정 안 막음)이 `./gradlew :core:research:test :infra:llm:test :app:batch:test` 로 통과.

### Tests for User Story 2 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T007 [P] [US2] `ScoringPromptFactoryTest` 확장 — `build(foods, candidates, includeNameTranslations = true)` 변형 골든(고정 언어 순서 `zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es` 1회 선언 + `t` 스키마 지시), `false` 변형에 번역 지시문 부재, **두 변형 모두 설명 지시문 부재**(FR-004), Red 확인 in `core/research/src/test/kotlin/com/meogo/core/research/prompt/ScoringPromptFactoryTest.kt`
- [X] T008 [P] [US2] `ScoringResponseParserTest` 확장 — `t` 파싱 P9(위치→언어 복원·blank 는 언어 누락·길이 부족분만 채택)·P10(음식 인덱스 중복 첫-채택·이탈 스킵), `t` 부재 시 `nameTranslations` empty(스코어링 전용 응답), Red 확인 in `core/research/src/test/kotlin/com/meogo/core/research/parse/ScoringResponseParserTest.kt`
- [X] T009 [P] [US2] `LlmFanoutClientTest` 확장 — `generate(requestFor: (LlmModelId) -> LlmChatRequest)` 가 모델별 상이 요청을 각 caller 에 전달하고 **모델별 정확히 1회 호출**(FR-006/SC-004), 부분 실패 수집 의미 불변, Red 확인 in `infra/llm/src/test/kotlin/com/meogo/infra/llm/client/LlmFanoutClientTest.kt`
- [X] T010 [P] [US2] `FoodContentSelectorTest` 보강 — 단일 소스(1개 `ModelScoring` 만 번역 보유) 병합 정상, 세 모델 설명 전무 → `description = null` 안전 산출(FR-007; 기존 로직으로 이미 통과하면 특성화 테스트로 유지·Red 불요 명시) in `core/research/src/test/kotlin/com/meogo/core/research/ensemble/FoodContentSelectorTest.kt`
- [X] T011 [US2] `AvoidanceScoringJobTest` 갱신 — 페이크 fan-out 이 모델별 요청을 노출해 GEMINI 요청에만 번역 지시 포함 검증, Gemini 만 `t` 응답 시 이름 번역이 Gemini 결과로 채워지고 설명 null, **이름 번역 일부/전부 누락이어도 스코어링 3모델 전량 취합이면 청크 확정**(FR-008), Red 확인 in `app/batch/src/test/kotlin/com/meogo/app/batch/scoring/AvoidanceScoringJobTest.kt`

### Implementation for User Story 2

- [X] T012 [US2] `ScoringPromptFactory.build` 에 `includeNameTranslations: Boolean` 파라미터 추가 — true 변형에 고정 언어 순서 선언·`t` 응답 스키마 지시 추가(contract §1.3) → T007 Green in `core/research/src/main/kotlin/com/meogo/core/research/prompt/ScoringPromptFactory.kt`
- [X] T013 [US2] `ScoringResponseParser` 에 `t` 파싱(P9/P10 — `LanguageCode.entries` 중 `KO` 제외 순서로 위치 복원) 구현 → T008 Green in `core/research/src/main/kotlin/com/meogo/core/research/parse/ScoringResponseParser.kt`
- [X] T014 [P] [US2] `LlmFanoutClient` 에 `generate(requestFor: (LlmModelId) -> LlmChatRequest)` 오버로드 구현(기존 `generate(request)` 는 위임 유지, 벤더 중립) → T009 Green in `infra/llm/src/main/kotlin/com/meogo/infra/llm/client/LlmFanoutClient.kt`
- [X] T015 [US2] `AvoidanceScoringJob.scoreChunk` 역할 분기 — 청크당 프롬프트 2종 구성(스코어링 전용·번역 포함) 후 `generate { modelId -> if (modelId == GEMINI) 번역변형 else 전용 }` 호출, 확정 게이트 로직 불변 확인, `ScoringJobConfig` 배선 반영 → T011 Green in `app/batch/src/main/kotlin/com/meogo/app/batch/scoring/AvoidanceScoringJob.kt` + `app/batch/src/main/kotlin/com/meogo/app/batch/scoring/ScoringJobConfig.kt`

**Checkpoint**: US1+US2 = P1 완료 — 설명 0·번역 단일 모델·호출 1회/모델 유지. `./gradlew build` green

---

## Phase 5: User Story 3 - 모델·옵션 튜닝 + 프리픽스 캐싱 정렬 + 비용 실측 (Priority: P2)

**Goal**: OpenAI 를 **gpt-5-nano**(사용자 지시 — 모델 고정)·추론 노력 minimal 로, 전 모델에 출력 토큰 상한을 두고(벤더별 매핑 R4), 프롬프트 정적→동적 순서를 골든으로 고정(FR-009~010)한 뒤 대표 청크 실측으로 모델별 ₩1 미만을 확인한다(FR-011/SC-001).

**Independent Test**: 옵션 프로퍼티 바인딩·벤더 배선 테스트가 `./gradlew :infra:llm:test` 로 통과하고, 프롬프트 순서 골든이 `:core:research:test` 로 통과하며, quickstart.md 절차의 수동 스모크에서 3개 모델 각각 `costKrw < 1.00` 로그 확인.

### Tests for User Story 3 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T016 [P] [US3] `ScoringPromptFactoryTest` 에 프리픽스 캐싱 정렬 골든 추가 — 서로 다른 음식 청크 2개로 build 해도 **system(지시문+후보 81종)은 바이트 동일**하고 user 만 달라짐(FR-010; US1 구조로 이미 통과하면 특성화 테스트로 고정·Red 불요 명시) in `core/research/src/test/kotlin/com/meogo/core/research/prompt/ScoringPromptFactoryTest.kt`
- [X] T017 [P] [US3] `LlmModelPropertiesBindingTest` 확장 — `meogo.llm.<model>.max-output-tokens`·`meogo.llm.openai.reasoning-effort` 바인딩(미설정 시 null), Red 확인 in `infra/llm/src/test/kotlin/com/meogo/infra/llm/config/LlmModelPropertiesBindingTest.kt`
- [X] T018 [P] [US3] `LlmConfiguration` 옵션 배선 테스트 신규/확장 — openai(gpt-5-nano)→`maxCompletionTokens`+`reasoningEffort=minimal`, upstage→`maxTokens`, gemini→`maxOutputTokens`, **null 프로퍼티는 옵션 미탑재**(boot-safety), Red 확인 in `infra/llm/src/test/kotlin/com/meogo/infra/llm/config/LlmConfigurationOptionsTest.kt`

### Implementation for User Story 3

- [X] T019 [US3] `LlmModelProperties.ModelProps` 에 `maxOutputTokens: Int?`·`reasoningEffort: String?` 추가 → T017 Green in `infra/llm/src/main/kotlin/com/meogo/infra/llm/config/LlmModelProperties.kt`
- [X] T020 [US3] `LlmConfiguration` 벤더별 옵션 배선 구현(R4 매핑 표 — gpt-5 계열은 `max_tokens` 미지원이므로 openai 는 반드시 `maxCompletionTokens`) → T018 Green in `infra/llm/src/main/kotlin/com/meogo/infra/llm/config/LlmConfiguration.kt`
- [X] T021 [US3] 배치 yml 갱신 — openai `model: gpt-5-nano`·단가(input 0.05/output 0.40 per 1M) **유지 검증**(사용자 지시: gpt-5-nano 사용) + `reasoning-effort: minimal`·`max-output-tokens: 2048` 추가, upstage `max-output-tokens: 2048`, gemini `max-output-tokens: 4096`(번역 포함 응답, R4 산정) + 주석으로 산정 근거 기록 in `app/batch/src/main/resources/application.yml`
- [ ] T022 [US3] 비용 실측 스모크 — quickstart.md 절차로 대표 청크(음식 10개) 실행, 3개 모델 각각 `costKrw < 1.00` 확인(SC-001)·OpenAI/Upstage 응답에 `t` 없음(SC-003)·설명 텍스트 0(SC-002, DEBUG 본문)·호출 1회/모델(SC-004) 확인, 실측 수치를 `specs/kb-93-llm-scoring-cost-reduction/spec.md` Success Criteria 하단 또는 research.md 에 기록. **미달 시** 지시문 추가 축약(1순위 레버, R7) 후 재실측

**Checkpoint**: 비용 목표 실측 통과 — 전 스토리 코드 완료

---

## Phase 6: User Story 4 - 음식 설명 생성·번역 분리 티켓 등록 (Priority: P3)

**Goal**: 스코어링에서 제외한 음식 설명 생성·번역 책임을 별도 Jira 티켓으로 등록·추적한다(FR-013/SC-007). 코드 변경 없음.

**Independent Test**: KB 프로젝트에 설명 생성·번역 티켓이 존재하고 KB-93 과 연결되며 spec.md 가 이를 참조.

### Implementation for User Story 4

- [X] T023 [US4] Jira KB 티켓 등록(제목 예: "[BE] 음식 설명 생성·번역 배치 — 스코어링 분리 후속", `create-jira-task` 스킬 표준: KB 프로젝트·작업 유형·BE 레이블·본인 할당·ADF 본문) + KB-93 이슈 링크 연결 + 티켓 키를 spec.md FR-013/SC-007 옆에 참조 기입 in `specs/kb-93-llm-scoring-cost-reduction/spec.md`

**Checkpoint**: 전 스토리 완료

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T024 [P] `call-timeout: 180s` 하향 검토 — T022 실측의 실제 응답 시간 기준으로 축소(설명 장문 대응 사유 소멸, R7 선택 항목)·주석 갱신 in `app/batch/src/main/resources/application.yml`
- [X] T025 최종 검증 — `./gradlew build` 전체 green + quickstart.md 검증 표(SC-001~007) 전 항목 충족 확인, 남은 골든 픽스처에 KB-53 잔재(key-value 포맷·라벨·설명) 없음 그레프 확인

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: 없음 — 즉시 시작
- **Phase 2 (Foundational)**: 태스크 없음(KB-53 기반 재사용)
- **US1 (Phase 3)**: T001 이후. 다른 스토리 의존 없음
- **US2 (Phase 4)**: **US1 완료 후** — 같은 파일(`ScoringPromptFactory`·`ScoringResponseParser`·두 테스트) 순차 확장
- **US3 (Phase 5)**: T016 은 US1 이후 가능, T017~T021 은 US1/US2 와 파일 독립(`infra/llm` config·yml)이라 병행 가능하나 **T022(실측)는 US1+US2 완료 필수**(압축·역할 분리 반영 상태로 측정해야 SC-001 유효)
- **US4 (Phase 6)**: 코드 독립 — 언제든 가능(권장: US2 확정 후, 분리 결정이 코드로 고정된 뒤)
- **Polish (Phase 7)**: T024 는 T022 이후, T025 는 전 태스크 이후

### User Story Dependencies

- US1 → US2 (파일 공유·계약 확장) → US3-실측(T022)
- US3-옵션(T017~T021) 은 US1/US2 와 병렬 가능
- US4 는 완전 독립

### Within Each User Story

- 테스트 먼저 작성·**Red 확인** 후 구현(Green) — T010·T016 처럼 기존 동작으로 이미 통과하는 특성화 테스트는 Red 불요를 태스크에 명시함
- 같은 테스트 파일을 여러 태스크가 만지는 경우([P] 아님) 태스크 순서대로

### Parallel Opportunities

- US1: T002 ∥ T003 (다른 테스트 파일)
- US2: T007 ∥ T008 ∥ T009 ∥ T010 (4개 테스트 파일 상호 독립), 구현은 T014 가 T012·T013 과 병렬(모듈 다름)
- US3: T016 ∥ T017 ∥ T018, 그리고 US3-옵션 전체(T017~T021)를 US2 진행과 병렬로 다른 작업자가 수행 가능
- US4(T023) ∥ 모든 코드 태스크

---

## Parallel Example: User Story 2

```bash
# 테스트 4개 동시 착수(모두 Red 확인):
Task: "T007 ScoringPromptFactoryTest 변형 골든 확장"
Task: "T008 ScoringResponseParserTest t 파싱 확장"
Task: "T009 LlmFanoutClientTest 모델별 요청 seam"
Task: "T010 FoodContentSelectorTest 단일 소스·설명 null"

# 구현 병렬(모듈 분리):
Task: "T012+T013 :core:research (factory → parser 순차)"
Task: "T014 :infra:llm LlmFanoutClient 오버로드"
```

---

## Implementation Strategy

### MVP First (US1 → US2 = P1 묶음)

1. T001 기준선 → US1(T002~T006) 완료·검증 — 압축 계약만으로도 토큰 절감 가치
2. US2(T007~T015) 완료·검증 — 설명 0·번역 단일화로 최대 지렛대 확보
3. **STOP and VALIDATE**: `./gradlew build` green, 페이크 기반 SC-002/003/004/005 충족

### Incremental Delivery

1. US1+US2 (P1) → 코드상 비용 구조 완성
2. US3 (P2) → 옵션·gpt-5-nano 고정 배선 + **실측으로 SC-001 판정**(미달 시 지시문 축약 반복)
3. US4 (P3) → 후속 티켓 등록으로 마감
4. Polish → 타임아웃 하향·최종 검증

---

## Notes

- 커밋은 태스크/논리 단위마다(Red 커밋·Green 커밋 분리 권장 — 헌법 Workflow)
- Kotlin 주석 금지 유지 — 단 `ScoringPromptFactory` 의 영어 프롬프트·한국어 병기 주석은 KB-53 승인 예외로 존치, yml 주석은 규약 밖(허용)
- `ModelScoring.descriptions`·`FoodContent.description` 필드는 제거하지 않는다(설명 후속 티켓이 채울 슬롯 — data-model §4~5)
- T022 실측은 실 API 키·로컬 MySQL 필요(수동) — CI 대상 아님
