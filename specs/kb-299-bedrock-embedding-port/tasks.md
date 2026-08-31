# Tasks: 임베딩 생성 포트 및 인프라 어댑터

**Input**: Design documents from `/specs/kb-299-bedrock-embedding-port/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/text-embedding-client.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 각 스토리는 실패 테스트(Red) 작성·확인 후 구현(Green)한다. 테스트는 전부 Kotest BehaviorSpec(given/when/then 한국어).

**Organization**: 스토리별 독립 구현·검증. US2(부팅 안전 구성)는 US1의 어댑터 클래스를 빈으로 조립하므로 US1 완료에 의존한다(예외적 스토리 간 의존 — 아래 Dependencies 참조).

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

**Purpose**: Bedrock 스타터 의존 추가 — 이후 모든 작업의 컴파일 전제

- [X] T001 `gradle/libs.versions.toml`에 `spring-ai-starter-bedrock = { module = "org.springframework.ai:spring-ai-starter-model-bedrock" }` 엔트리 추가(버전 없음 — BOM 관리, 기존 spring-ai 엔트리 스타일 미러링) 후 `infra/llm/build.gradle.kts`에 `"implementation"(libs.spring.ai.starter.bedrock)` 추가. `./gradlew :infra:llm:compileKotlin`으로 해석 확인

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 양쪽 스토리가 공유하는 seam 계약

- [X] T002 seam `TextEmbeddingClient` 작성 — `common/src/main/kotlin/com/kbap/common/port/llm/TextEmbeddingClient.kt`, contracts/text-embedding-client.md 의 시그니처 그대로(`fun interface`, `embed(texts: List<String>): List<FloatArray>`). Spring·JPA·AWS 타입 비노출(기존 ArchUnit `common.port` Spring-free 규칙이 자동 커버 — 규칙 수정 없음). `./gradlew :common:compileKotlin` 통과 확인

**Checkpoint**: seam 확정 — US1 시작 가능

---

## Phase 3: User Story 1 - 텍스트 묶음을 임베딩 벡터로 변환 (Priority: P1) 🎯 MVP

**Goal**: 페이크로 계약이 검증된 Bedrock Titan V2 어댑터 — 순서 보존·개수 일치·차원 검증·빈 목록 단락·실패 전파

**Independent Test**: `./gradlew :infra:llm:test --tests "*SpringAiTextEmbeddingClientTest*"` — AWS 없이 통과. 실호출은 자격증명 환경에서 스모크로 확인

### Tests for User Story 1 (Red — 구현 전 작성, 실패 확인) ⚠️

- [X] T003 [US1] `infra/llm/src/test/kotlin/com/kbap/infra/llm/embedding/SpringAiTextEmbeddingClientTest.kt` 작성 — 페이크 Spring AI `EmbeddingModel`로 BehaviorSpec 시나리오: ① 텍스트 N건 → N건 벡터 순서 보존 ② 빈 목록 → 외부 호출 없이 빈 목록 ③ 페이크가 1024가 아닌 차원 반환 → 예외 ④ 페이크가 예외 던짐 → 그대로 전파(부분 결과 없음). `SpringAiTextEmbeddingClient` 미존재로 컴파일 실패(Red) 확인

### Implementation for User Story 1 (Green → Refactor)

- [X] T004 [US1] `infra/llm/src/main/kotlin/com/kbap/infra/llm/embedding/SpringAiTextEmbeddingClient.kt` 구현 — `TextEmbeddingClient` 구현체, 생성자 주입 `EmbeddingModel`·기대 차원(Int). `embed`는 빈 목록 단락 후 `EmbeddingModel.embed(texts)` 위임 + 각 벡터 차원 검증. T003 테스트 통과(Green) 확인
- [X] T005 [US1] `infra/llm/src/test/kotlin/com/kbap/infra/llm/config/EmbeddingSmokeTest.kt` 작성 — 기존 `LlmSmokeTest` 패턴 미러링(활성 조건 미충족 시 스킵, 기본 비활성). `BedrockTitanEmbeddingModel` + `TitanEmbeddingBedrockApi`(모델 `amazon.titan-embed-text-v2:0`, 리전 프로퍼티)를 직접 구성해 한국어 음식명 1건 실호출 → 1024차원 벡터 확인. 자격증명 있는 환경에서 1회 실행·결과 기록(SC-003)

**Checkpoint**: 계약 검증된 어댑터 완성 — US2 시작 가능

---

## Phase 4: User Story 2 - 미설정 환경에서 부팅 안전 (Priority: P2)

**Goal**: `kbap.llm.embedding.enabled` 미설정이면 빈 미생성(부팅 무영향), 설정 시 `TextEmbeddingClient` 빈 생성

**Independent Test**: `./gradlew :infra:llm:test --tests "*LlmConfigurationBootSafetyTest*"` + 전체 `./gradlew build`(기존 전 프로필 부팅 회귀)

### Tests for User Story 2 (Red — 구현 전 작성, 실패 확인) ⚠️

- [X] T006 [US2] `infra/llm/src/test/kotlin/com/kbap/infra/llm/config/LlmConfigurationBootSafetyTest.kt`에 embedding 시나리오 추가 — ApplicationContextRunner 기존 패턴: ① 프로퍼티 없음 → `TextEmbeddingClient` 빈 없음 ② `kbap.llm.embedding.enabled=true` → 빈 존재. ②가 실패(Red)함을 확인(구성 미구현 상태)

### Implementation for User Story 2 (Green → Refactor)

- [X] T007 [US2] `infra/llm/src/main/kotlin/com/kbap/infra/llm/config/LlmModelProperties.kt`에 `EmbeddingProps`(enabled=false·model 기본 `amazon.titan-embed-text-v2:0`·region 기본 `ap-northeast-2`·dimension 기본 1024·timeout) 추가하고, `LlmConfiguration.kt`에 `@ConditionalOnProperty(prefix = "kbap.llm.embedding", name = ["enabled"], havingValue = "true")` 빈 추가 — `TitanEmbeddingBedrockApi(model, region, timeout)`(AWS 기본 자격증명 체인) → `BedrockTitanEmbeddingModel` → `SpringAiTextEmbeddingClient` 조립. T006 통과(Green) 확인

**Checkpoint**: 두 스토리 완결 — 미설정 부팅 안전 + 계약 검증 어댑터

---

## Phase 5: Polish & Cross-Cutting Concerns

- [X] T008 전체 회귀 `./gradlew build` — 미설정 환경(api·batch 부팅 컨텍스트 테스트 포함) 전부 통과 확인(SC-002·SC-004), quickstart.md 절차 유효성 검증. 작업/논리 단위 커밋 정리

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (P1단계)**: 없음 — 즉시 시작
- **Foundational (T002)**: T001과 독립(다른 모듈)이나 관례상 Setup 후
- **US1 (T003→T004→T005)**: T001·T002 완료 후. 스토리 내부는 Red→Green 순서 고정
- **US2 (T006→T007)**: T004 완료 후(구성이 `SpringAiTextEmbeddingClient`를 조립) — 예외적 스토리 간 의존
- **Polish (T008)**: 전 스토리 완료 후

### Parallel Opportunities

- T001(gradle) ∥ T002(common) — 서로 다른 파일·모듈, 동시 진행 가능
- T005(스모크)는 T004 후 T006과 병행 가능(다른 파일)
- 그 외는 단일 파이프라인(작은 기능 — 인위적 병렬화 불필요)

---

## Implementation Strategy

**MVP = US1**: T001·T002 → T003(Red)→T004(Green)까지가 최소 가치 단위 — 계약 검증된 어댑터. 벡터 DB 미확정 상태에서 후속(KB-299 잔여)이 이 seam 위에 붙는다.

**Incremental**: US1 완료 후 US2(구성·부팅 안전)로 운영 투입 가능 상태를 만들고, T008 회귀로 마감. 각 태스크/논리 단위마다 커밋.

---

## Notes

- Red 확인은 컴파일 실패도 인정(대상 클래스 미존재 시) — 단 assertion 실패로 Red를 보는 것이 더 정확하므로 가능하면 스텁 없이 테스트만 먼저 커밋하지 말고 같은 태스크 안에서 Red 확인 후 바로 Green 진행
- 스모크(T005)는 CI에서 항상 스킵되는 조건부 — 실측 1회는 자격증명 있는 로컬/EC2에서 수행하고 결과(모델·차원·소요시간)를 커밋 메시지나 PR 본문에 기록
- `spring.ai.model.embedding: none`은 양쪽 yml에 이미 존재 — 수정 금지(변경 시 자동구성 유입 위험)
- Kotlin 주석 규약: 코드로 표현 불가능한 제약만 라인 주석(예: 자격증명 체인 의존)
