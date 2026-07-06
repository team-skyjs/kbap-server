---
description: "Task list — LLM 호출 토대(:infra:llm) + 배치 직접 의존"
---

# Tasks: LLM 호출 토대 — `:infra:llm` 모듈(Spring AI 3모델 병렬 fan-out), 배치가 직접 의존

**Input**: Design documents from `specs/kb-49-llm-client-foundation/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/(llm-fanout-client.md · llm-client-properties.md)

**Tests**: Test-First is **NON-NEGOTIABLE**(헌법 원칙 I). 각 유스케이스는 구현 전에 **실패하는 테스트**를 먼저 작성하고 Red 를 확인한 뒤 Green 으로 넘어간다.

**Organization**: 유스케이스(US1·US2·US3)별로 그룹핑해 독립 구현·검증한다.

**Conventions(고정)**: Kotlin `.kt` 주석 금지(self-documenting). 테스트는 Kotest `BehaviorSpec`(given/`when`/then 한국어). 모든 값타입 불변(val). 공개 API 벤더 중립(Spring AI 타입 미노출). 모듈 build 파일 의존성은 문자열 표기(`"implementation"(...)`), 라이브러리 좌표는 `libs.*`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일·선행 의존 없음)
- **[Story]**: US1·US2·US3 (Setup·Foundational·Polish 는 라벨 없음)
- 모든 경로는 저장소 루트 기준

---

## Phase 1: Setup (모듈 신설 + 등록)

**Purpose**: `:infra:llm` 모듈 스캐폴드와 Spring AI 의존을 갖춘다. 이후 모든 코드의 컴파일 토대.

- [X] T001 `settings.gradle.kts` 의 주석 `// ":infra:external" …` 을 `include(":infra:llm")` 로 대체(인프라 어댑터 블록에 추가)
- [X] T002 `infra/llm/build.gradle.kts` 생성 — `plugins { id("meogo.spring-conventions") }` + `dependencies { "implementation"(libs.spring.ai.starter.openai); "implementation"(libs.spring.ai.starter.google.genai) }`
- [X] T003 소스 디렉터리 생성 — `infra/llm/src/main/kotlin/com/meogo/infra/llm/` · `infra/llm/src/test/kotlin/com/meogo/infra/llm/` (빈 채로 남으면 `.gitkeep`)
- [X] T004 `./gradlew :infra:llm:compileKotlin` 으로 빈 모듈이 카탈로그·플러그인 해석에 성공하는지 확인(스타터 좌표·`libs` 접근자 검증)

**Checkpoint**: `:infra:llm` 모듈이 빌드 그래프에 올라오고 Spring AI 스타터가 클래스패스에 있다.

---

## Phase 2: Foundational (공유 값타입 + seam — 모든 US 의 선행 차단 요소)

**Purpose**: US1·US2·US3 가 공통으로 참조하는 벤더 중립 값타입과 단일모델 seam 을 만든다. 여기까지 끝나야 어떤 US 도 시작 가능.

**⚠️ CRITICAL**: 이 phase 완료 전에는 US 작업을 시작할 수 없다.

- [X] T005 [P] `infra/llm/src/main/kotlin/com/meogo/infra/llm/LlmModelId.kt` — enum `OPENAI, UPSTAGE, GEMINI`
- [X] T006 [P] `infra/llm/src/main/kotlin/com/meogo/infra/llm/LlmChatRequest.kt` — `prompt: String`(init 에서 `require(prompt.isNotBlank())`), `system: String? = null`
- [X] T007 [P] `infra/llm/src/main/kotlin/com/meogo/infra/llm/LlmChatResult.kt` — `modelId: LlmModelId`, `content: String`
- [X] T008 [P] `infra/llm/src/main/kotlin/com/meogo/infra/llm/LlmModelFailure.kt` — `modelId: LlmModelId`, `message: String`
- [X] T009 [P] `infra/llm/src/main/kotlin/com/meogo/infra/llm/LlmFanoutResult.kt` — `successes: List<LlmChatResult>`, `failures: List<LlmModelFailure>` + `isAllFailed()` / `attemptedCount()`
- [X] T010 `infra/llm/src/main/kotlin/com/meogo/infra/llm/LlmModelCaller.kt` — interface: `val modelId: LlmModelId`, `fun call(request: LlmChatRequest): String`(성공 시 content, 실패 시 예외 throw) — 페이크 대상 seam

**Checkpoint**: fan-out 이 다룰 값타입·seam 계약이 컴파일된다. 실 네트워크·Spring AI 없이 페이크 주입 가능.

---

## Phase 3: User Story 1 - 하나의 클라이언트로 N개 모델 병렬 호출 + 부분 실패 격리 (Priority: P1) 🎯 MVP

**Goal**: `LlmFanoutClient.generate(request)` 한 번 호출로 활성 N개 모델을 병렬 실행하고, 개별 실패를 격리해 성공분만 모아 `LlmFanoutResult` 로 반환한다.

**Independent Test**: `LlmModelCaller` 페이크 여러 개(정상/예외/지연)를 주입해, (a) 병렬 실행 (b) 1개 예외 시 나머지 성공 온전 반환 (c) 전멸/활성 0 시 무예외 빈 성공집합을 단위 테스트로 확인(실 키·네트워크 불필요).

### Tests for User Story 1 (Test-First: 먼저 작성하고 FAIL 확인) ⚠️

- [X] T011 [US1] `infra/llm/src/test/kotlin/com/meogo/infra/llm/LlmFanoutClientTest.kt` 작성(BehaviorSpec) — 페이크 `LlmModelCaller` 로 다음 시나리오가 **모두 실패(Red)**함을 확인:
  - given 정상 3개 → then successes=3, failures=0 (spec US1 #1)
  - given 1개 예외 → then successes=2, 해당 modelId 는 failures 에(전체 중단 없음) (US1 #2, 불변식 5: 중복 없음)
  - given 지연 caller 혼재 → then 총 소요 ≈ 최장 단일 호출(병렬성 — latch/지연으로 검증) (US1 #3, SC-001)
  - given caller 0개 → then successes=[], failures=[] 무예외 (US1 #4)

### Implementation for User Story 1

- [X] T012 [US1] `infra/llm/src/main/kotlin/com/meogo/infra/llm/LlmFanoutClient.kt` — `class LlmFanoutClient(callers: List<LlmModelCaller>, executor: Executor)` · `generate()`: caller 별 `CompletableFuture.supplyAsync({ caller.call(request) }, executor)` → `handle` 로 성공/실패 분할 → `join` 집계 → `LlmFanoutResult`(예외 밖으로 던지지 않음). T011 을 Green 으로.
- [X] T013 [US1] `./gradlew :infra:llm:test --tests "*LlmFanoutClientTest"` 통과 확인 후 리팩터(가드레일: future 예외는 `handle`에서만 흡수, `join` 은 이미 완료된 future 에 대해서만 — research D4a)

**Checkpoint**: fan-out 병렬·부분실패·전멸 격리가 페이크로 독립 검증된다(실모델 없이 MVP 성립).

---

## Phase 4: User Story 2 - OpenAI·Upstage·Gemini 3모델 구성 + 키 없이 부팅 안전 (Priority: P2)

**Goal**: 프로퍼티/프로필로 3개 `ChatModel`(→ `LlmModelCaller`) 빈을 조건부 구성하고, 키가 하나도 없어도 `:app:batch`·`:app:api` 부팅이 회귀 없이 성공한다. 배치가 `:infra:llm` 를 의존해 실제로 소비한다.

**Independent Test**: 키 없는 컨텍스트 기동 → 로딩 성공·LLM 빈 0개. 키/활성 플래그 준 프로필 → 3개 caller 빈 등록. 일부만 활성 → 활성분만 fan-out 대상.

### Tests for User Story 2 (Test-First: 먼저 작성하고 FAIL 확인) ⚠️

- [X] T014 [P] [US2] `infra/llm/src/test/kotlin/com/meogo/infra/llm/LlmModelPropertiesBindingTest.kt` — `meogo.llm.{openai,upstage,gemini}.{enabled,api-key,base-url,model}` 바인딩(기본 enabled=false) 검증(Red)
- [X] T015 [P] [US2] `infra/llm/src/test/kotlin/com/meogo/infra/llm/LlmConfigurationBootSafetyTest.kt` — `@SpringBootTest`(SpringExtension): 키·활성 전무 → 컨텍스트 로딩 성공 + `LlmModelCaller` 빈 0개, `LlmFanoutClient` 빈은 존재(빈 리스트 주입) 검증(Red) (spec US2 #1, SC-003)

### Implementation for User Story 2

- [X] T016 [P] [US2] `infra/llm/src/main/kotlin/com/meogo/infra/llm/LlmModelProperties.kt` — `@ConfigurationProperties("meogo.llm")` + 중첩 `ModelProps(enabled=false, apiKey?, baseUrl?, model?)` × openai/upstage/gemini. T014 Green.
- [X] T017 [US2] `infra/llm/src/main/kotlin/com/meogo/infra/llm/SpringAiModelCaller.kt` — `LlmModelCaller` 구현: `LlmChatRequest` → Spring AI `Prompt`(system+user) → `chatModel.call(prompt)` → 응답 텍스트 추출(벤더 타입은 여기서만)
- [X] T018 [US2] `infra/llm/src/main/kotlin/com/meogo/infra/llm/LlmConfiguration.kt` — `@Configuration`: 3개 caller 빈 각 `@ConditionalOnProperty("meogo.llm.<model>.enabled", havingValue="true")`(Upstage=OpenAI 클래스 base-url 교체, Gemini=google-genai) + `llmFanoutExecutor()`=`Executors.newVirtualThreadPerTaskExecutor()` + `llmFanoutClient(callers, executor)` 빈. T015 Green.
- [X] T019 [US2] `app/batch/build.gradle.kts` — `"implementation"(project(":infra:llm"))` 추가하고 "디커플드·:common 이벤트로만 소통" 주석을 ADR-0008/0010 반영해 정정(도메인/infra 직접 의존)
- [X] T020 [US2] `app/batch/src/main/resources/application.yml` — `spring.ai.model.*=none` 추가(스타터 자동구성 차단) + 낡은 "Spring AI 자동구성 클래스패스에 없음" 주석 정정
- [X] T021 [US2] `app/batch/src/test/kotlin/com/meogo/app/batch/` 에 배치 부팅 안전 테스트 추가(또는 기존 컨텍스트 로드 테스트 확인) — 키 없이 `:app:batch` 컨텍스트 로딩 성공(SC-003, FR-009 회귀 0)
- [X] T022 [US2] `./gradlew :infra:llm:test :app:batch:test :app:api:test` 로 US2 Green + `:app:api` 부팅 무회귀 확인

**Checkpoint**: 3모델 조건부 구성·키 없는 부팅 안전이 검증되고, 배치가 fan-out 클라이언트를 주입받을 수 있다. US1+US2 독립 동작.

---

## Phase 5: User Story 3 - 실모델 스모크 검증 & 모듈/의존 정합 (Priority: P3)

**Goal**: 실 키로 3모델 각 1회 호출 스모크(평소 `@Disabled`)를 제공하고, `:app:batch → :infra:llm` 의존·모듈 명명이 코드/설정/문서에 일관됨을 확정한다.

**Independent Test**: 키 채운 로컬에서 스모크 수동 실행 → 3모델 각 1회 성공. `settings.gradle.kts`·`app/batch/build.gradle.kts`·`CLAUDE.md` LLM 라인에서 모듈이 `:infra:llm` 로 일관.

### Tests for User Story 3

- [X] T023 [US3] `infra/llm/src/test/kotlin/com/meogo/infra/llm/LlmSmokeTest.kt` — `@Disabled`(또는 `-Dllm.smoke.enabled` 게이트) 실 키 3모델 각 1회 실호출 → `LlmFanoutResult.successes.size == 3`, `failures` 빈(SC-004, FR-011). 수동 절차는 quickstart §3 로 문서화.

### Implementation for User Story 3

- [X] T024 [P] [US3] `docs/adr/0010-llm-adapter-module-named-infra-llm.md` 작성 — `:infra:llm` 신설 + 배치 `implementation` 직접 의존(kernel port·runtimeOnly 생략, 단일 소비자=배치 속도 우선), 범용 `:infra:external` catch-all 은 범위 밖. `docs/adr/_template.md` 형식.
- [X] T025 [P] [US3] `CLAUDE.md` — 기술스택/모듈 라인의 LLM 서술을 `:infra:llm`(배치 직접 의존)으로 갱신. 범용 `:infra:external` catch-all 서술은 건드리지 않음(FR-014 — 현재 구현 직결분만).
- [X] T026 [US3] 정합 확인 — `grep -n ":infra:llm" settings.gradle.kts app/batch/build.gradle.kts` 로 `include`·`implementation` 일관 확인(quickstart §5, SC-005)

**Checkpoint**: 스모크 절차 확보 + 모듈/의존/문서 정합 확정. 세 US 모두 독립 기능.

---

## Phase 6: Polish & Cross-Cutting

**Purpose**: 전체 정합·회귀 최종 확인.

- [X] T027 `./gradlew build` 전체 컴파일+테스트 통과(회귀 0, FR-009/SC-003)
- [X] T028 quickstart.md 절차 재현 검증(부팅 안전 §1 · 정합 §5) — 문서와 실제 산출물 일치 확인
- [X] T029 [P] Kotlin 주석 금지·벤더 중립(공개 API 에 Spring AI 타입 미노출) 최종 스캔 후 커밋

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup(P1)**: 선행 없음 — 즉시 시작
- **Foundational(P2)**: Setup 완료 후 — 모든 US 차단
- **US1(P3 phase)**: Foundational 후 시작. MVP.
- **US2(P4 phase)**: Foundational 후 시작. US1 의 `LlmFanoutClient`·`LlmModelCaller` 를 빈 배선에서 참조(T018 은 T012 필요) — 테스트(T014·T015)는 US1 완료 전에도 작성 가능하나, T018 구현은 T012 이후.
- **US3(P5 phase)**: US1·US2 완료 후(스모크는 실 caller 구성 T016~T018 필요, 정합은 T019 필요).
- **Polish(P6)**: 원하는 US 완료 후.

### Within Each User Story

- 테스트 먼저 작성·FAIL 확인(헌법 I) → 구현 → Green → 리팩터
- 값타입/seam(Foundational) → fan-out(US1) → 구성/배선(US2) → 스모크/정합(US3)

### Parallel Opportunities

- Setup: T001·T002 는 순차(둘 다 build 그래프), T003 은 이후.
- **Foundational**: T005~T009 모두 [P](서로 다른 파일). T010 은 LlmModelId(T005) 참조.
- **US2**: 테스트 T014·T015 [P]. 구현 T016 [P](다른 파일), T017·T018 은 seam/타입 의존.
- **US3**: T024(ADR)·T025(CLAUDE.md) [P].
- US1↔US2 테스트 작성은 병렬 가능하나, US2 빈 구현(T018)은 US1 fan-out(T012)에 의존.

---

## Parallel Example: Foundational 값타입

```bash
# 서로 다른 파일 — 동시 작성 가능:
Task: "LlmModelId.kt (enum)"          # T005
Task: "LlmChatRequest.kt (require)"    # T006
Task: "LlmChatResult.kt"               # T007
Task: "LlmModelFailure.kt"             # T008
Task: "LlmFanoutResult.kt (파생)"      # T009
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 Setup → Phase 2 Foundational
2. Phase 3 US1(fan-out 페이크 검증) → **STOP & VALIDATE**: 병렬·부분실패·전멸을 실모델 없이 확인
3. 이 시점에서 다중모델 오케스트레이션 토대 성립(MVP)

### Incremental Delivery

1. Setup + Foundational → 토대 준비
2. US1 → 페이크로 fan-out 독립 검증(MVP)
3. US2 → 3모델 구성 + 키 없는 부팅 안전 + 배치 배선
4. US3 → 실키 스모크 + 모듈/문서 정합
5. Polish → 전체 build·정합 확정

---

## Notes

- [P] = 다른 파일·선행 의존 없음
- 각 US 는 독립 완결·독립 검증 가능
- 구현 전 테스트 FAIL 확인(헌법 I) — 실 네트워크는 US3 스모크(@Disabled)에서만
- task 또는 논리 그룹 단위로 커밋
- 공개 API·값타입에 Spring AI/벤더 SDK 타입 노출 금지(어댑터 내부 격리)
