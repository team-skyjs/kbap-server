# Tasks: 배치 콘텐츠 4작업 LLM 호출 인터페이스 사전 선언

**Input**: Design documents from `/specs/kb-182-llm-client-interface/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/food-content-contracts.md, quickstart.md

**Tests**: 헌법 원칙 I(Test-First) 적용 — 유일한 로직인 DTO init 불변은 실패 테스트 선행(Red → Green). 인터페이스 선언 자체는 로직이 없어 컴파일·페이크 SAM 검증으로 대체한다.

**Organization**: 유저 스토리별 그룹. US1(계약+DTO 선언) → US2(페이크 대체 검증) 순차 — US2 는 US1 산출물 없이는 컴파일되지 않는다.

## Phase 1: Setup

- [X] T001 기준선 확인 — `./gradlew build` 전체 그린 확인 (현재 상태)

## Phase 2: Foundational

없음 — 신규 의존·인프라 작업 없음(`:core` 에 선언만 추가).

## Phase 3: US1 — 콘텐츠 4작업 호출 계약 선언 (P1) 🎯 MVP

**Goal**: 4계약(fun interface) + DTO 3종을 `com.kbap.core.food` 에 선언, DTO 불변을 테스트로 고정.

**Independent Test**: `./gradlew :core:test` 그린 + 전체 컴파일 — 계약·DTO 만으로 검증 가능(spec US1 Independent Test).

- [X] T002 [P] [US1] `TargetLanguageTextsTest` 작성(Red) in `core/src/test/kotlin/com/kbap/core/food/TargetLanguageTextsTest.kt` — Kotest BehaviorSpec(given/when/then 한국어): 9개 대상 언어 전수(키 집합 == `LanguageCode.entries - KO`) 아니면 실패 / KO 포함 시 실패 / 값 blank 시 실패 / 정상 9종 생성 성공. 작성 직후 컴파일 실패(Red) 확인
- [X] T003 [P] [US1] `FoodContentDtoTest` 작성(Red) in `core/src/test/kotlin/com/kbap/core/food/FoodContentDtoTest.kt` — `FoodDescriptionContent`: description blank·255자 초과·"설명 준비 중"(플레이스홀더) 거절, spiciness 0..10 밖(-1·11) 거절, 정상 생성 성공 / `FoodAvoidanceAssessment`: code blank 거절, inclusionPercent 0..100 밖 거절, 정상 생성 성공. 작성 직후 Red 확인
- [X] T004 [US1] 계약·DTO 선언(Green) — `core/src/main/kotlin/com/kbap/core/food/` 에 5파일: `TargetLanguageTexts.kt`(init 불변), `FoodNameTranslationClient.kt`, `FoodDescriptionClient.kt`(+`FoodDescriptionContent` 동거), `FoodImageGenerationClient.kt`, `FoodAvoidanceAssessmentClient.kt`(+`FoodAvoidanceAssessment` 동거) — 네이밍 `Food{X}Client`+`call` 통일, 시그니처는 contracts/food-content-contracts.md 그대로(전부 `fun interface`), seam 의도 주석은 기존 `MenuBoardVisionExtractor` 스타일
- [X] T005 [US1] `./gradlew :core:test` 그린 확인(Green) + `./gradlew build` 컴파일·ArchUnit 통과 확인

**Checkpoint**: 계약 4/4 + DTO 선언 완료(SC-001) — 이 증분만으로 머지 가능. 후속 태스크(KB-183·184·209)가 병렬 착수 가능한 상태(SC-004).

## Phase 4: US2 — 대체 구현(페이크)으로 배치 검증 가능 (P2)

**Goal**: 4계약 전부가 테스트에서 람다 페이크(SAM 변환)로 대체 가능함을 실행 테스트로 고정.

**Independent Test**: 페이크 구현 생성·호출 테스트가 외부 네트워크 없이 그린(spec US2 Independent Test). 배치 스텝 본문이 아직 없으므로 스텝 흐름 결합 검증은 후속 스텝 태스크 범위(spec Assumptions).

- [X] T006 [US2] `FoodContentFakeTest` 작성 in `core/src/test/kotlin/com/kbap/core/food/FoodContentFakeTest.kt` — 4계약 각각 람다로 페이크 생성(quickstart.md 예시 스타일), 호출 결과가 DTO 불변을 통과함을 확인. 실패 전달 확인: 예외 던지는 페이크 호출 시 예외가 그대로 전파됨(FR-003)

**Checkpoint**: 페이크 대체 가능성 검증 완료 — KB-182 골격의 "페이크 스텝 검증" 전제 충족.

## Phase 5: Polish

- [X] T007 전체 검증 — `./gradlew build` 그린 + `:app:batch:test`(KbapBatchApplication 부팅 — 구현·배선 0 상태 부팅 유지, SC-003) + quickstart.md 검증 절차 일치 확인

## Dependencies

- **T001** → 기준선
- **US1 (T002 ∥ T003 → T004 → T005)**: T002·T003 은 서로 다른 파일이라 병렬 [P]. T004 는 두 테스트의 Red 확인 후
- **US2 (T006)**: T004 완료 후(계약 타입 필요)
- **T007**: 전부 완료 후

```text
T001 ── US1: (T002 ∥ T003) → T004 → T005 ── US2: T006 ── T007
```

## Parallel Execution

- T002·T003 동시 작성 가능(별개 테스트 파일, 상호 의존 없음). 그 외는 순차 — 총 7태스크의 소형 피처라 병렬 이득은 이 한 쌍뿐이다.

## Implementation Strategy

- **MVP = US1**: 계약+DTO 선언만으로 이 피처의 존재 이유(후속 태스크 병렬 착수 가능)가 충족된다. US2 는 얇은 보증 레이어.
- **증분 순서**: US1 → US2, 체크포인트마다 커밋(작업/논리 단위 커밋 규약).
- 프로덕션 로직·Spring 배선·배치 스텝 수정 없음 — 범위 이탈 시 후속 태스크(KB-183·184·209) 침범이다.
