# Tasks: 기피성분 81종 포함 신뢰도 LLM 스코어링 (3모델 병렬 → Consensus Ensemble)

**Input**: Design documents from `specs/kb-53-llm-avoidance-scoring/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/(scoring-pipeline·llm-scoring-io·food-scoring-source)

**Tests**: Test-First 는 **NON-NEGOTIABLE**(헌법 원칙 I). 각 유저스토리는 구현 전에 실패하는 Kotest `BehaviorSpec`(given/when/then 한국어) 테스트를 먼저 작성하고 Red 를 확인한다. research 순수 서비스는 실 네트워크 없이 단위검증, 배치 잡은 페이크 `LlmFanoutClient`+페이크 `FoodScoringSource`, 어댑터는 MySQL Testcontainers.

**Organization**: 유저스토리별로 그룹화해 각 스토리를 독립 구현·검증한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완 태스크 의존 없음)
- **[Story]**: US1/US2/US3
- 모든 경로는 저장소 루트 기준. **Kotlin 주석 금지**(전 태스크 적용).

## 경로 규약(plan.md Structure)

- 스코어링 도메인: `core/research/src/{main,test}/kotlin/com/meogo/core/research/`
- 음식 공급 seam: `core/food/...`(port) + `infra/persistence/...food/`(어댑터)
- 조율: `app/batch/src/{main,test}/kotlin/com/meogo/app/batch/scoring/`
- 재사용(무변경): `:infra:llm`(`LlmFanoutClient`·`LlmChatRequest`·`LlmFanoutResult`·`LlmModelId`·`LlmModelFailure`), kernel `LanguageCode`·`LocalizedText`, `AvoidanceSubstanceCode`·`AvoidanceSubstance`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 스코어링 도메인·조율에 필요한 모듈 배선. `:core:research` 모듈·빌드는 이미 존재(스캐폴드), `:infra:llm`·kernel·avoidance 무변경.

- [X] T001 `app/batch/build.gradle.kts` 에 스코어링 의존 추가 — `"implementation"(project(":core:research"))`, `"implementation"(project(":core:food"))`, `"implementation"(project(":core:avoidance"))`, `"runtimeOnly"(project(":infra:persistence"))`(어댑터 런타임 조립). 기존 `:common`·`:infra:llm` 유지. (US3 잡 조립 전제 — Setup 에 두어 US1/US2 순수 도메인과 분리)
- [X] T002 [P] `./gradlew :core:research:build :app:batch:compileKotlin` 로 현행 스캐폴드+T001 배선이 깨지지 않는지 베이스라인 확인(신규 코드 전, 그린 상태 확보).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: US1(파싱 출력)·US2(집계 입력)가 공유하는 `:core:research` **입력/파싱 값타입**. ⚠️ 이 단계 완료 전 유저스토리 착수 불가.

**주의**: 값타입은 불변(모든 `val`), `private fun copy` 통제 복제, Kotlin 주석 금지. `require()` 불변식은 이를 소비하는 US 테스트에서 검증(별도 테스트 태스크 없음).

- [X] T003 [P] `ScoringFood.kt`(`foodId: Long`, `koreanName: String` — blank 금지) 값타입 in `core/research/src/main/kotlin/com/meogo/core/research/ScoringFood.kt`
- [X] T004 [P] `CandidateSubstance.kt`(`code: String` `^[A-Z0-9_]+$`, `koreanLabel: String` — DB 카탈로그 ko 원문) in `core/research/.../CandidateSubstance.kt`
- [X] T005 [P] `SubstanceJudgement.kt`(`code: String`, `score: Int` 0..2, `probability: Int` 1..100) in `core/research/.../SubstanceJudgement.kt`
- [X] T006 [US1/US2 공유] `ModelScoring.kt` — `included: Map<Long, List<SubstanceJudgement>>`, `nameTranslations: Map<Long, Map<LanguageCode, String>>`, `descriptions: Map<Long, LocalizedText>`(kernel import) in `core/research/.../ModelScoring.kt` (T005 의존)
- [X] T007 research `src/main`·`src/test` 의 `.gitkeep` 제거(실제 파일이 들어오는 디렉터리) — `core/research/src/main/kotlin/com/meogo/core/research/.gitkeep`, `core/research/src/test/kotlin/com/meogo/core/research/.gitkeep`

**Checkpoint**: 입력/파싱 값타입 준비 — US1·US2 착수 가능.

---

## Phase 3: User Story 1 - 음식 청크를 81종 후보에 대해 단일 모델로 구조화 스코어링 (Priority: P1) 🎯 MVP

**Goal**: (음식 ≤10, 후보 81) → 결정적 프롬프트 생성(포함된 것만·score 0/1/2·probability 1~100 강제·9개 언어 이름 번역·설명 ko+9개 목표 200/하드캡 230자 지시), 단일 모델 raw content → `ModelScoring` 파싱(누락=미포함, 방어 규칙).

**Independent Test**: 페이크/스텁 모델 응답으로 파서가 포함 조합을 유효 범위로 파싱, 미응답 성분 미포함 처리, 범위이탈·형식오류·미지코드·중복·미지음식 skip, 번역·설명 미지언어·ko·빈값 skip·230자 초과 잘라내기, 깨진 JSON → 예외를 검증(실 네트워크 불요).

### Tests for User Story 1 (Test-First — 먼저 작성, FAIL 확인) ⚠️

- [X] T008 [P] [US1] `ScoringPromptFactoryTest` — 프롬프트에 음식 목록·81 후보(code+ko명)·"포함된 것만"·score 0/1/2·**probability 1~100 강제**·9개 언어 번역·설명(ko 생성+9개, 목표 200/최대 230자) 지시 포함, 빈 청크 예외, 결정성(동일 입력→동일 프롬프트) in `core/research/src/test/kotlin/com/meogo/core/research/ScoringPromptFactoryTest.kt`
- [X] T009 [P] [US1] `ScoringResponseParserTest` — 정상 부분응답 파싱 / 누락=미포함(맵 미포함) / 미지코드·score∉0..2·probability∉1..100·비수치 skip / 동일(food,code) 중복 첫 유효만 / 미지·청크외 음식 skip / `nameTranslations` 미지언어·`ko`·빈값 skip·번역객체 깨짐→빈맵 / `description` 미지언어·빈값 skip·**230자 초과 앞 230자 잘라내기**·없음→빈 / 코드펜스 스트립 / 빈·깨진 JSON → `ScoringResponseParseException` in `core/research/src/test/kotlin/com/meogo/core/research/ScoringResponseParserTest.kt`

### Implementation for User Story 1

- [X] T010 [P] [US1] `ScoringPromptFactory.build(foods, candidates): ScoringPrompt(prompt, system)` — 순수·결정적, 빈 foods `require` 예외. 지시는 contracts/llm-scoring-io.md 준수 in `core/research/.../ScoringPromptFactory.kt`
- [X] T011 [P] [US1] `ScoringResponseParser.parse(content, foods, candidates): ModelScoring` — jackson 방어 파싱, 음식명→foodId 역매핑, FR-009 skip 규칙, 230자 잘라내기, `ScoringResponseParseException`(빈/깨짐) in `core/research/.../ScoringResponseParser.kt` (+예외 클래스, T006 의존)

**Checkpoint**: 단일 모델 프롬프트·파싱 계약 완성 — 독립 실행·검증 가능(MVP).

---

## Phase 4: User Story 2 - 3모델 병렬 스코어링 + Consensus Ensemble 최종 신뢰도 (Priority: P2)

**Goal**: 3개 `ModelScoring`(정확히 3개) → (음식, 후보 81성분)별 `inclusionConfidence` 정수 1~100 종합(문서 §4, 누락 보정 score0/prob1, agreement distinct 1→1.0/2→0.9/3→0.75, clamp). 텍스트(이름 번역·설명)는 앙상블 아님 — 우선순위 정렬 첫 비어있지 않은 모델 단일 채택. **3개 미만이면 집계 안 함**(계약상 `perModel.size!=3` → 예외).

**Independent Test**: 3개 페이크 모델에 일치/불일치/이상치 score·probability 를 주고 조합별 단일 `inclusionConfidence`(1~100), agreement_factor 반영, 골든(비빔밥-계란 [2,1,2]·[90,70,80]→74), 전부누락→1 clamp, `perModel.size!=3`→예외를 검증. `FoodContentSelector` 는 우선순위 첫 비어있지 않은 모델(이름·설명 동일)·전무→빈·결정성.

### Tests for User Story 2 (Test-First — 먼저 작성, FAIL 확인) ⚠️

- [X] T012 [P] [US2] `ConsensusEnsembleAggregatorTest` — **골든 74 재현**(SC-003) / agreement 1.0·0.9·0.75(SC-007) / 누락 보정(score0·prob1) / 전부누락→confidence 1(clamp, SC-002) / 후보 81 전부 커버 / **`perModel.size != 3` → `IllegalArgumentException`**(SC-004) in `core/research/src/test/kotlin/com/meogo/core/research/ConsensusEnsembleAggregatorTest.kt`
- [X] T013 [P] [US2] `FoodContentSelectorTest` — 우선순위(정렬 순서) 첫 비어있지 않은 모델의 이름 번역+설명 **동일 모델** 채택 / 전 모델 무텍스트→빈 번역맵·빈 설명 / 결정성 in `core/research/src/test/kotlin/com/meogo/core/research/FoodContentSelectorTest.kt`

### Implementation for User Story 2

- [X] T014 [P] [US2] `FoodInclusionScore.kt`(`foodId`, `substanceCode`, `inclusionConfidence` 1..100, `avgScore` Double, `avgProbability` Double, `agreementFactor` Double) in `core/research/.../FoodInclusionScore.kt`
- [X] T015 [P] [US2] `FoodScoringResult.kt`(`foodId`, `status: enum SCORED/FAILED`, `scores: List<FoodInclusionScore>`, `nameTranslations: Map<LanguageCode,String>`, `description: LocalizedText`) in `core/research/.../FoodScoringResult.kt` (T014 의존)
- [X] T016 [P] [US2] `FoodContentSelector.select(foodId, orderedModelScorings): FoodContent`(이름 번역+설명 단일 채택, 앙상블 아님) in `core/research/.../FoodContentSelector.kt` (T006 의존)
- [X] T017 [US2] `ConsensusEnsembleAggregator(policy: EnsemblePolicy = DEFAULT).aggregate(foods, candidates, perModel): List<FoodScoringResult>` — `perModel.size!=3` 예외, 누락 보정, base=0.6·(avgScore/2)+0.4·(avgProbability/100)·agreement, round·clamp 1..100, `FoodContentSelector` 결과 결합. `EnsemblePolicy(scoreWeight=0.6, floor=1)`·상수 문서화 in `core/research/.../ConsensusEnsembleAggregator.kt` (T005/T006/T014/T015/T016 의존)

**Checkpoint**: 3모델 종합 신뢰도 + 텍스트 선정 완성 — US1 위에서 독립 검증 가능.

---

## Phase 5: User Story 3 - 배치 잡으로 조사 대기열을 청크 단위 종단 스코어링 (Priority: P3)

**Goal**: `:app:batch` 잡이 조사 대기열을 10개 청크로 끊어 종단 처리 — Food→ScoringFood·AvoidanceSubstance→CandidateSubstance(카탈로그 단일출처) 매핑 → `LlmFanoutClient.generate` 병렬 → **successes==3 && failures 없음** 확인(아니면 실패 모델별 로깅 + 청크 미확정) → 파싱·집계·텍스트 선정(우선순위 OPENAI→UPSTAGE→GEMINI 정렬) → `FoodScoringResult` 산출. 저장·위험도 판정 범위 밖.

**Independent Test**: 페이크 `FoodScoringSource`(고정 목록·잔여<size 마지막 청크·빈 대기열) + 페이크 `LlmFanoutClient`(3성공/1~2성공/전멸)로 잡 종단 — 10개 청크 분할·빈 대기열 무호출, 3성공→SCORED 산출, 1~2성공→미확정+실패 모델별 로깅(부분 유입 0), 전멸→미확정, 카탈로그 증감 추종(81 비하드코딩)을 검증. 어댑터는 Testcontainers.

### Tests for User Story 3 (Test-First — 먼저 작성, FAIL 확인) ⚠️

- [X] T018 [P] [US3] `FoodScoringSourceAdapterTest` — MySQL Testcontainers 로 active `food` 조회·`size` 상한(≤size)·잔여<size·빈 반환 검증([[mysql-testcontainers-setup]]) in `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/food/FoodScoringSourceAdapterTest.kt`
- [X] T019 [P] [US3] `AvoidanceScoringJobTest` — 페이크 `LlmFanoutClient`+페이크 `FoodScoringSource`: 대기열 10개 청크 분할(마지막 잔여·빈 대기열 무호출, SC-001) / **3모델 성공→FoodScoringResult(SCORED) 산출** / **1~2개 성공→청크 미확정(부분 집계·유입 0)+실패 모델별 로깅**(modelId+사유, SC-004/010) / 전멸→미확정 / 카탈로그 후보 개수 비하드코딩(81 증감 추종, SC 2번) in `app/batch/src/test/kotlin/com/meogo/app/batch/scoring/AvoidanceScoringJobTest.kt`

### Implementation for User Story 3

- [X] T020 [P] [US3] `FoodScoringSource` port(`fun nextChunk(size: Int): List<Food>`) in `core/food/src/main/kotlin/com/meogo/core/food/FoodScoringSource.kt`
- [X] T021 [US3] `FoodScoringSourceAdapter`(읽기 전용 active food 공급, 스키마 무변경, `size` 상한) in `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/FoodScoringSourceAdapter.kt` (T020 의존)
- [X] T022 [US3] `AvoidanceScoringJob` — 대기열→청크(10)→매핑→`ScoringPromptFactory`→`LlmFanoutClient.generate`→[successes==3&&failures 비었는지 확인, 아니면 `LlmModelFailure` 별 로깅+청크 미확정]→모델별 `ScoringResponseParser.parse`(파싱 실패=그 모델 실패)→우선순위(OPENAI→UPSTAGE→GEMINI) 정렬→`ConsensusEnsembleAggregator.aggregate`→`FoodScoringResult` 산출 in `app/batch/src/main/kotlin/com/meogo/app/batch/scoring/AvoidanceScoringJob.kt` (US1·US2·T020/T021 의존)
- [X] T023 [US3] `ScoringJobConfig` — 빈 배선(`FoodScoringSource`·`LlmFanoutClient`·research 서비스·`AvoidanceSubstanceRepository`·청크크기 프로퍼티 주입, 페이크 대체 seam) in `app/batch/src/main/kotlin/com/meogo/app/batch/scoring/ScoringJobConfig.kt`
- [X] T024 [US3] `app/batch/src/main/resources/application.yml` 에 청크 크기 프로퍼티(기본 10)·`meogo.llm.*` 스코어링 활성 프로필 주석(키는 `.env`) 추가

**Checkpoint**: 대기열 종단 스코어링 잡 구동 — US1+US2+US3 독립 기능 완성.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 문서·검증·후속 seam 마감.

- [X] T025 [P] (선택) ADR-0011 작성 — `docs/adr/0011-scoring-domain-in-research-batch-orchestration.md`(research 스코어링 배치·조합 위치 결정, plan Complexity Tracking 근거)
- [X] T026 [P] 실키 3모델 스모크 `@Disabled` 테스트 + 수동 절차 주석(KB-49 패턴 계승) in `app/batch/src/test/kotlin/com/meogo/app/batch/scoring/AvoidanceScoringSmokeTest.kt`
- [X] T027 `./gradlew :core:research:test :infra:persistence:test :app:batch:test` 전체 그린 확인 + quickstart.md 수용 체크(SC-001..010) 대조
- [X] T028 CLAUDE.md SPECKIT 포인터 최신 상태 확인(이미 갱신됨 — 변경 발생 시에만)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup(P1)**: 즉시 시작.
- **Foundational(P2)**: Setup 후 — 값타입은 US1/US2 블로킹.
- **US1(P3)**: Foundational 후 착수(ModelScoring 필요). MVP.
- **US2(P4)**: Foundational 후 착수 가능(ModelScoring 필요). US1 과 병렬 가능(서로 다른 파일) — 단 잡 종단(US3)은 둘 다 필요.
- **US3(P5)**: 잡(T022)은 US1(프롬프트·파서)+US2(집계·선정) 완료 의존. 단 port/adapter(T018/T020/T021)는 US1/US2 와 병렬 가능(독립 파일).
- **Polish(P6)**: 원하는 스토리 완료 후.

### User Story Dependencies

- **US1(P1)**: Foundational 외 의존 없음 — 독립.
- **US2(P2)**: Foundational 외 의존 없음 — US1 과 독립(같은 `ModelScoring` 소비, 다른 서비스).
- **US3(P3)**: 잡 본체는 US1+US2 통합 필요. seam(port/adapter)은 선행 착수 가능.

### Within Each Story

- 테스트 먼저 작성·FAIL 확인(헌법 I) → 값타입 → 서비스 → 조율.
- US2 `ConsensusEnsembleAggregator`(T017)는 `FoodContentSelector`(T016)·산출 값타입(T014/T015) 뒤.
- US3 `AvoidanceScoringJob`(T022)은 어댑터(T021)·research 서비스 뒤.

### Parallel Opportunities

- Foundational T003·T004·T005 [P](다른 파일). T006 은 T005 뒤.
- US1: T008·T009 테스트 [P]; T010·T011 구현 [P](다른 파일, 서로 무의존).
- US2: T012·T013 테스트 [P]; T014·T016 [P], T015 는 T014 뒤, T017 은 마지막.
- US3: T018·T019 테스트 [P]; T020 [P]; T021→T022→T023 순차(같은 잡 배선).
- US1 전체와 US2 전체를 서로 다른 담당이 병렬 진행 가능(Foundational 후).

---

## Parallel Example: User Story 1

```bash
# 테스트 먼저(반드시 FAIL):
Task: "ScoringPromptFactoryTest in core/research/src/test/kotlin/com/meogo/core/research/ScoringPromptFactoryTest.kt"
Task: "ScoringResponseParserTest in core/research/src/test/kotlin/com/meogo/core/research/ScoringResponseParserTest.kt"

# 그린 구현(다른 파일 병렬):
Task: "ScoringPromptFactory in core/research/.../ScoringPromptFactory.kt"
Task: "ScoringResponseParser in core/research/.../ScoringResponseParser.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 만)

1. Phase 1 Setup(T001~T002) → Phase 2 Foundational(T003~T007) 완료.
2. Phase 3 US1(T008~T011) — 단일 모델 프롬프트·파싱 계약.
3. **STOP & VALIDATE**: `./gradlew :core:research:test` 로 US1 독립 검증(파서·프롬프트 결정성·방어 규칙).

### Incremental Delivery

1. Setup+Foundational → 값타입 준비.
2. US1(프롬프트·파서) → 독립 검증(MVP).
3. US2(집계·선정) → 골든 74·agreement·3-모델-필수 독립 검증.
4. US3(배치 잡·seam) → 페이크로 종단·부분실패·청크 분할 검증.
5. 각 스토리는 이전을 깨지 않고 가치 추가.

### 안전 정책(전 스토리 관통)

- **3개 모델 모두 취합 시에만 확정** — 1~2개 성공은 청크 미확정 + 실패 모델별 로깅(부분 집계·유입 0). aggregator 는 `perModel.size!=3` 방어.
- 최종 `inclusionConfidence` 정수 **1~100** clamp(KB-9 `fromInclusionProbability` 호환).
- 후보 성분은 카탈로그 단일출처(81 비하드코딩).
- 텍스트(이름 번역·설명)는 앙상블 아님 — 우선순위 단일 모델, 설명 하드캡 230자.

## Notes

- [P] = 다른 파일·무의존. [Story] 라벨로 추적성 유지.
- 각 유저스토리는 독립 완결·검증 가능(헌법 I: 구현 전 테스트 FAIL 확인).
- Kotlin 주석 금지(main·test), 도메인 불변(모든 `val`·`private fun copy`).
- `:infra:llm`·kernel·avoidance 무변경 — 재사용만.
- 커밋은 태스크 또는 논리 그룹 단위.
