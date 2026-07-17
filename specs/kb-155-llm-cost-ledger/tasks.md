# Tasks: 메뉴 스캔 LLM 호출 비용 기록 원장

**Input**: Design documents from `/specs/kb-155-llm-cost-ledger/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md

**Tests**: Test-First NON-NEGOTIABLE (헌법 I) — 각 스토리의 테스트를 구현보다 먼저 작성하고 Red 를 확인한다.

**Organization**: US1(비용 기록 적재 — P1) → US2(실패 격리 — P2). 조회 API 없음(범위 밖).

## Format: `[ID] [P?] [Story] Description`

## Path Conventions

Gradle 멀티모듈 — `:core`, `:infra:llm`, `:domain:scan`, `:app:api` 4모듈 터치, 신규 모듈 0.

---

## Phase 1: Setup

신규 모듈·신규 외부 의존성 없음 — 별도 Setup 태스크 없다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 두 스토리가 공유하는 이벤트 타입·스키마·async 인프라.

- [ ] T001 [P] `:core` 에 Spring-free 이벤트 데이터 클래스 `LlmCallCostIncurred(modelName, inputTokens, outputTokens, costUsd: BigDecimal, costKrw: BigDecimal)` 생성 — `core/src/main/kotlin/com/kbap/core/llm/LlmCallCostIncurred.kt` (순수 데이터 클래스 — 단독 테스트 불요, 소비 테스트가 커버)
- [ ] T002 [P] Flyway 마이그레이션 `app/api/src/main/resources/db/migration/V2026.07.17.<생성시각 HH.mm.ss>__create_llm_call_cost.sql` — data-model.md 대로: BaseEntity 공통(id·status·created_at·updated_at) + model_name VARCHAR(100)·input_tokens/output_tokens BIGINT·cost_usd DECIMAL(12,6)·cost_krw DECIMAL(14,2) 전부 NOT NULL, `idx_llm_call_cost_created_at`, FK 없음. 기존 `MigrationValidationTest`(전 마이그레이션 적용 성공 검증)가 유효성 커버
- [ ] T003 [P] `:app:api` 에 `config/AsyncConfig.kt`(`@Configuration @EnableAsync`) 추가 — `app/api/src/main/kotlin/com/kbap/app/api/config/AsyncConfig.kt` (실행자는 Boot 기본 applicationTaskExecutor)

**Checkpoint**: 이벤트 타입·스키마·async 기반 준비 — 스토리 구현 시작 가능.

---

## Phase 3: User Story 1 - 관리자가 누적 LLM 비용을 집계한다 (Priority: P1) 🎯 MVP

**Goal**: vision 응답 1건 = 원장 1행. 발행(extractor)→비동기 소비(listener)→저장(도메인 서비스) 전 구간.

**Independent Test**: 이벤트 발행 → `llm_call_cost` 에 모델명·토큰·USD/KRW 채워진 행 1행(eventually). extractor 단위에서 발행 시점·값 검증.

### Tests for User Story 1 (Red 먼저 — 작성 직후 실패 확인) ⚠️

- [ ] T004 [P] [US1] `LlmCallCostServiceTest` 작성 — `domain/scan/src/test/kotlin/com/kbap/domain/scan/LlmCallCostServiceTest.kt`: Testcontainers(기존 `ScanTestApp`·testFixtures 패턴)로 `record(이벤트)` → 행 저장·DECIMAL 정밀도(USD 6·KRW 2자리) 왕복 보존. BehaviorSpec given/when/then 한국어
- [ ] T005 [P] [US1] `OpenAiMenuBoardVisionExtractorTest` 작성 — `infra/llm/src/test/kotlin/com/kbap/infra/llm/menu/OpenAiMenuBoardVisionExtractorTest.kt`: 스텁 `ChatModel`(usage 포함 `ChatResponse`) + 기록형 publisher 로 (1) 성공 응답→이벤트 1회·토큰·HALF_UP 반올림(USD 6·KRW 2)·모델명(metadata.model 우선, 빈 값이면 구성값 폴백) (2) usage 누락→토큰 0 (3) 파서 실패에도 이벤트 발행 (4) `chatModel.call` 예외 시 미발행
- [ ] T006 [P] [US1] `LlmCallCostEventListenerTest` 작성 — `app/api/src/test/kotlin/com/kbap/app/api/scan/LlmCallCostEventListenerTest.kt`: `@SpringBootTest`(Testcontainers MySQL)에서 `ApplicationEventPublisher.publishEvent(LlmCallCostIncurred)` → kotest `eventually` 로 행 존재 단언(@Async 경로 통과 검증)
- [ ] T007 [US1] T004~T006 Red 확인 — `./gradlew :domain:scan:test :infra:llm:test :app:api:test --tests` 로 신규 테스트만 실행, 컴파일 실패/assertion 실패 확인

### Implementation for User Story 1

- [ ] T008 [P] [US1] `LlmCallCost` 엔티티 + `internal LlmCallCostJpaRepository` — `domain/scan/src/main/kotlin/com/kbap/domain/scan/model/LlmCallCost.kt`(BaseEntity 상속, `@Column(length=100)`·`precision/scale` Flyway 일치, 전 필드 기본값 no-arg) · `domain/scan/src/main/kotlin/com/kbap/domain/scan/LlmCallCostJpaRepository.kt`
- [ ] T009 [US1] `LlmCallCostService` — `domain/scan/src/main/kotlin/com/kbap/domain/scan/LlmCallCostService.kt`: `@Service internal constructor`, `@Transactional fun record(event: LlmCallCostIncurred)` 만 노출(append-only — 수정·삭제 창구 없음)
- [ ] T010 [US1] extractor 이벤트 발행 — `infra/llm/src/main/kotlin/com/kbap/infra/llm/menu/OpenAiMenuBoardVisionExtractor.kt`: 생성자에 publisher 콜백 추가, `chatModel.call()` 직후·파싱 전 `LlmCallCostIncurred` 생성(HALF_UP 반올림 1회, usage 누락=0+warn, 단가 0=warn)·발행. 기존 `logTokenUsage` 는 동일 스냅샷 값 사용. `LlmConfiguration.menuBoardVisionExtractor` 빈에 `ApplicationEventPublisher` 주입
- [ ] T011 [US1] `LlmCallCostEventListener` — `app/api/src/main/kotlin/com/kbap/app/api/scan/LlmCallCostEventListener.kt`: `@Component`, `@Async @EventListener fun handle(event)` → `llmCallCostService.record(event)`
- [ ] T012 [US1] Green 확인 — T004~T006 전부 통과, `:domain:scan`·`:infra:llm` 기존 테스트 회귀 없음

**Checkpoint**: 이벤트 발행→저장 전 구간 동작. MVP 완성.

---

## Phase 4: User Story 2 - 비용 기록 실패가 스캔 기능을 방해하지 않는다 (Priority: P2)

**Goal**: 기록 경로(발행·비동기 처리·저장) 어느 실패도 스캔 응답에 비전파.

**Independent Test**: publisher 가 예외를 던져도 `extract()` 정상 반환. 리스너 내부 서비스 예외가 호출부로 전파되지 않고 error 로그만 남음.

### Tests for User Story 2 (Red 먼저) ⚠️

- [ ] T013 [P] [US2] `OpenAiMenuBoardVisionExtractorTest` 보강 — 발행 시 예외를 던지는 publisher 로도 `extract()` 가 정상 결과 반환(발행 실패 격리)
- [ ] T014 [P] [US2] `LlmCallCostEventListenerTest` 보강 — 예외 던지는 `LlmCallCostService` 스텁(또는 저장 실패 유도)으로 `handle()` 호출 시 예외 비전파 확인
- [ ] T015 [US2] T013~T014 Red 확인

### Implementation for User Story 2

- [ ] T016 [P] [US2] extractor 발행부 try/catch — 발행 실패는 warn 로그 후 계속(스캔 흐름 무영향)
- [ ] T017 [P] [US2] 리스너 try/catch — 기록 실패는 error 로그로 종결(재던지기 없음)
- [ ] T018 [US2] Green 확인 — T013~T014 통과

**Checkpoint**: 실패 격리 완성 — FR-004 충족.

---

## Phase 5: Polish & Cross-Cutting

- [ ] T019 전체 테스트 스위트 — `./gradlew test` (arch 태그 포함: ArchUnit `ModuleBoundaryTest` 로 계층 위반 없음 확인)
- [ ] T020 [P] quickstart.md §1 명령 실행 검증 + spec.md Status → Implemented, Jira DoD 체크 대조(테이블·1호출 1행·환율 1500·실패 격리·조회 API 범위 밖)

---

## Dependencies & Execution Order

- **Phase 2 (T001~T003)**: 즉시 시작 가능, 상호 [P]. T001 이 모든 스토리 테스트의 컴파일 전제
- **US1 (T004~T012)**: T001·T002 완료 후. 테스트(T004~T006 [P]) → Red(T007) → 구현(T008→T009→T010·T011) → Green(T012). T010·T011 은 서로 [P](다른 모듈)
- **US2 (T013~T018)**: US1 완료 후(같은 파일 보강). 테스트 → Red → 구현 → Green
- **Polish (T019~T020)**: 전 스토리 완료 후

### Parallel Opportunities

- T001·T002·T003 동시 진행 가능
- US1 테스트 3건(T004·T005·T006) 동시 작성 가능(서로 다른 모듈)
- US2 의 T016·T017 동시 진행 가능(infra vs app)

## Implementation Strategy

US1 = MVP(기록 적재 전 구간). US2 는 같은 파일들의 실패 격리 보강이라 US1 직후 순차 진행. 태스크/논리 단위마다 커밋(헌법 Workflow).
