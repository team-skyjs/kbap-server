# Tasks: 배치 콘텐츠 4개 작업용 LLM 클라이언트 구현

**Input**: Design documents from `/specs/kb-224-llm-content-clients/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/food-content-clients.md

**Tests**: Test-First **NON-NEGOTIABLE**(헌법 I) — 모든 스토리는 실패 테스트(Red) 작성·확인 후 구현(Green)한다. 전부 Kotest BehaviorSpec(given/when/then 한국어), 외부 호출은 `LlmModelCaller`·`ImageModel`·`StorageObjectStore` 페이크.

**Organization**: 스토리별 독립 구현·검증. 경로는 워크트리 루트 기준.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

없음 — 기존 모듈(`:infra:llm`·`:infra:storage`·`:core`)에 파일을 추가하는 작업이라 프로젝트 초기화가 필요 없다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: US1·US2·US3 텍스트 클라이언트가 공유하는 JSON 파싱 공통(코드펜스 제거 + jackson — `MenuBoardResultParser` 선례 일반화)

- [X] T001 [Red] `FoodContentJsonParser` 실패 테스트 작성 — 정상 JSON 파싱, ```json 코드펜스 제거, 비JSON/구조 불일치 시 예외 in `infra/llm/src/test/kotlin/com/kbap/infra/llm/food/FoodContentJsonParserTest.kt`
- [X] T002 [Green] `FoodContentJsonParser` 구현(reified 타입 파싱 + stripCodeFence + 파싱 실패 예외) in `infra/llm/src/main/kotlin/com/kbap/infra/llm/food/FoodContentJsonParser.kt`

**Checkpoint**: `:infra:llm:test` 통과 — 스토리 구현 시작 가능 (US4 는 파서 불사용 — 이 phase 와 무관하게 진행 가능)

---

## Phase 3: User Story 1 - 기피성분 조사 콘텐츠 생성 (Priority: P1) 🎯 MVP

**Goal**: `LlmFanoutClient`(3모델) 재사용 + 코드별 평균 종합으로 `FoodAvoidanceAssessmentClient` 구현체·빈 제공

**Independent Test**: 페이크 `LlmModelCaller` 3개로 구성한 fan-out 을 주입해 — 후보 코드 범위 안 결과 종합·계약 위반 강등·유효 응답 부족 예외를 외부 호출 없이 검증

- [X] T003 [US1] [Red] `SpringAiFoodAvoidanceAssessmentClient` 실패 테스트 작성 in `infra/llm/src/test/kotlin/com/kbap/infra/llm/food/SpringAiFoodAvoidanceAssessmentClientTest.kt` — 시나리오: (1) 3모델 정상 응답 → 코드별 percent 평균(반올림)·0 제외, (2) 한 모델이 후보 밖 코드/범위 밖 percent → 그 모델만 강등하고 나머지 2개로 종합, (3) 유효 응답 2개 미만 → 예외 전파, (4) `candidateCodes` 빈 집합 → LLM 무호출·빈 목록, (5) 프롬프트에 koreanName·전 후보 코드 포함
- [X] T004 [US1] [Green] `SpringAiFoodAvoidanceAssessmentClient` 구현(프롬프트 조립 → `LlmFanoutClient.generate` → 응답별 파싱·검증·강등 → 최소 2개 검사 → 평균 종합) in `infra/llm/src/main/kotlin/com/kbap/infra/llm/food/SpringAiFoodAvoidanceAssessmentClient.kt`
- [X] T005 [US1] `FoodContentClientConfiguration` 신설 — `FoodAvoidanceAssessmentClient` 빈(`llmFanoutClient` 주입, 상시 등록) in `infra/llm/src/main/kotlin/com/kbap/infra/llm/config/FoodContentClientConfiguration.kt`, `:app:batch:test` 부팅 검증 통과 확인

**Checkpoint**: US1 단독으로 배치가 기피성분 조사 빈을 주입받을 수 있는 상태(KB-209 연결 준비 완료)

---

## Phase 4: User Story 2 - 음식명 다국어 번역 생성 (Priority: P2)

**Goal**: OpenAI `LlmModelCaller` 1건 호출로 `FoodNameTranslationClient` 구현체·빈 제공

**Independent Test**: 페이크 `LlmModelCaller` 를 주입해 9언어 전수 맵 반환·언어 누락/빈 값 예외를 검증

- [X] T006 [P] [US2] [Red] `SpringAiFoodNameTranslationClient` 실패 테스트 작성 in `infra/llm/src/test/kotlin/com/kbap/infra/llm/food/SpringAiFoodNameTranslationClientTest.kt` — 시나리오: (1) 9언어 전수 응답 → `TargetLanguageTexts` 반환, (2) 언어 누락/여분 키/blank 값 → 예외 전파, (3) 코드펜스 응답 허용, (4) 프롬프트에 koreanName·9개 언어 코드 포함
- [X] T007 [P] [US2] [Green] `SpringAiFoodNameTranslationClient` 구현 in `infra/llm/src/main/kotlin/com/kbap/infra/llm/food/SpringAiFoodNameTranslationClient.kt`
- [X] T008 [US2] `FoodContentClientConfiguration` 에 `FoodNameTranslationClient` 빈 추가(`@ConditionalOnProperty(kbap.llm.openai.enabled)`, `openAiModelCaller` 주입) in `infra/llm/src/main/kotlin/com/kbap/infra/llm/config/FoodContentClientConfiguration.kt`

**Checkpoint**: US1·US2 각각 독립 동작

---

## Phase 5: User Story 3 - 음식 설명 콘텐츠 생성 (Priority: P2)

**Goal**: OpenAI `LlmModelCaller` 1건 호출로 `FoodDescriptionClient` 구현체·빈 제공(설명+번역+맵기 단일 호출)

**Independent Test**: 페이크 `LlmModelCaller` 를 주입해 255자·플레이스홀더·맵기 0..10 계약 통과/위반을 검증

- [X] T009 [P] [US3] [Red] `SpringAiFoodDescriptionClient` 실패 테스트 작성 in `infra/llm/src/test/kotlin/com/kbap/infra/llm/food/SpringAiFoodDescriptionClientTest.kt` — 시나리오: (1) 정상 응답 → `FoodDescriptionContent` 반환, (2) 256자 설명/플레이스홀더("설명 준비 중")/맵기 범위 밖/번역 언어 누락 → 예외 전파, (3) 프롬프트에 koreanName·255자 제한·0..10 맵기 지시 포함
- [X] T010 [P] [US3] [Green] `SpringAiFoodDescriptionClient` 구현 in `infra/llm/src/main/kotlin/com/kbap/infra/llm/food/SpringAiFoodDescriptionClient.kt`
- [X] T011 [US3] `FoodContentClientConfiguration` 에 `FoodDescriptionClient` 빈 추가(`@ConditionalOnProperty(kbap.llm.openai.enabled)`) in `infra/llm/src/main/kotlin/com/kbap/infra/llm/config/FoodContentClientConfiguration.kt`

**Checkpoint**: 텍스트 3종 클라이언트 완료

---

## Phase 6: User Story 4 - 음식 사진 생성 (Priority: P3)

**Goal**: Spring AI `ImageModel`(OpenAI) + `StorageObjectStore.put` 확장으로 `FoodImageGenerationClient` 구현체·빈 제공, 배치에 storage 조립

**Independent Test**: 페이크 `ImageModel`·인메모리 `StorageObjectStore` 로 저장 후 키 반환·저장 실패 시 예외·같은 키 덮어쓰기를 검증

- [~] T012 [P] [US4] [Red] `S3StorageObjectStore.put` 전용 테스트 — **생략**: put 은 분기 없는 S3 SDK 위임이고 이 프로젝트에 mockk/localstack 미구비(PutObject 는 실 네트워크 필요). seam 계약은 T014(InMemoryStore)가 커버. ponytail: 자명한 위임엔 테스트 불필요
- [X] T013 [US4] [Green] `StorageObjectStore` seam 에 `put(path, bytes, contentType)` 추가 in `core/src/main/kotlin/com/kbap/core/storage/StorageObjectStore.kt` + `S3StorageObjectStore` PutObject 구현 in `infra/storage/src/main/kotlin/com/kbap/infra/storage/S3StorageObjectStore.kt` (기존 인터페이스 구현 페이크는 컴파일 에러로 전수 발견·수정)
- [X] T014 [P] [US4] [Red] `OpenAiFoodImageGenerationClient` 실패 테스트 작성 in `infra/llm/src/test/kotlin/com/kbap/infra/llm/food/OpenAiFoodImageGenerationClientTest.kt` — 시나리오: (1) 페이크 `ImageModel` b64 응답 → 디코딩·`put(storageKey, bytes, image/png)` 호출·storageKey 그대로 반환, (2) put 실패 → 예외 전파·키 미반환, (3) 같은 키 재호출 → put 재호출(덮어쓰기 멱등), (4) 프롬프트에 koreanName 포함
- [X] T015 [US4] [Green] `OpenAiFoodImageGenerationClient` 구현(Spring AI `ImageModel` 인터페이스 주입 — 페이크 가능 seam) in `infra/llm/src/main/kotlin/com/kbap/infra/llm/food/OpenAiFoodImageGenerationClient.kt`
- [X] T016 [US4] `LlmModelProperties` 에 `image` 프로퍼티(enabled·api-key·model·base-url·timeout — vision 선례) 추가 in `infra/llm/src/main/kotlin/com/kbap/infra/llm/config/LlmModelProperties.kt` + `FoodContentClientConfiguration` 에 `OpenAiImageModel` 조립·`FoodImageGenerationClient` 빈 추가(`@ConditionalOnProperty(kbap.llm.image.enabled)`)
- [X] T017 [US4] `:app:batch` 에 `:infra:storage` `implementation` 의존 추가 in `app/batch/build.gradle.kts` + `BatchStorageConfig` 조립(`S3StorageObjectStore.create(region, bucket)`, 프로퍼티 미구성 시 빈 미생성 — api `StorageConfig` 선례) in `app/batch/src/main/kotlin/com/kbap/app/batch/config/BatchStorageConfig.kt`, `:app:batch:test` 부팅 검증 통과 확인

**Checkpoint**: 4종 클라이언트 전부 빈 제공 완료

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T018 전체 빌드 검증 `./gradlew build`(SC-005) + quickstart.md 검증 포인트 전수 확인, 논리 단위 커밋 정리

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 2(T001–T002)**: US1·US2·US3 의 선행(파서 공유). **US4 는 파서를 쓰지 않아 Phase 2 와 무관하게 시작 가능**
- **US2·US3(T006–T011)**: 서로 파일이 달라 병행 가능 — 단 `FoodContentClientConfiguration` 수정 task(T008·T011)는 같은 파일이라 순차
- **T005 가 `FoodContentClientConfiguration` 을 신설**하므로 T008·T011·T016 은 T005 이후
- **T013(seam put)** 은 T015(이미지 클라이언트 구현)의 선행. T012·T014(Red)는 병행 가능
- **T018**: 전 스토리 완료 후

### Within Each User Story

- [Red] 테스트 작성 → **실패 확인** → [Green] 구현 → 통과 확인 → Refactor(필요 시) → 커밋

### Parallel Opportunities

- T006·T007(US2) ∥ T009·T010(US3) — 구현 파일·테스트 파일 분리
- T012(storage Red) ∥ T014(image Red) ∥ 텍스트 스토리 진행
- config 파일(T005→T008→T011→T016)만 순차 체인

---

## Implementation Strategy

**MVP First**: Phase 2 → US1(기피성분 — 안전 직결 P1) 완료 후 중단·검증 가능. 이후 US2→US3→US4 순 증분. 각 스토리 완료마다 커밋(논리 단위) — 헌법 Development Workflow.
