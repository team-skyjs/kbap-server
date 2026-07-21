# Tasks: 배치 콘텐츠 파이프라인 골격 재구축 — 음식 단위 처리 + READY 전이 규칙

**Input**: Design documents from `specs/kb-182-batch-pipeline-skeleton/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md

**Tests**: 도메인(`:domain:food`) 신규 로직은 Test-First(헌법 원칙 I) 유지. **배치(`:app:batch`)는 사용자 지시(2026-07-21)로 테스트 선행 없이 구현 우선** — 골격 검증은 도메인 테스트 + 전체 빌드·부팅 그린으로 갈음(배치 테스트 보강은 후속). 삭제 작업(US3)은 "빌드·기존 테스트 그린"이 게이트.

**Organization**: 유저 스토리별 그룹. 실행 순서는 **US3 → US1 → US2** — 레거시를 먼저 걷어내 깨끗한 바닥에서 시작한다(티켓 DoD 1항, 구 잡은 기본 off 라 제거 리스크 없음). US2 가 대체하는 `FoodScoringSource` 의 유일 소비자(scoring 패키지)도 이때 함께 사라진다.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [X] T001 기준선 확인 — `./gradlew build` 전체 그린 확인 (레거시 포함 현재 상태)

---

## Phase 2: Foundational

없음 — 공통 선행 인프라 없음. 골격 자체가 스토리다.

---

## Phase 3: User Story 1 - 음식 READY 전이 규칙 (Priority: P1) 🎯 MVP

**Goal**: 콘텐츠 4작업(사진·설명·이름 번역·기피성분 매핑) 완비 시에만 READY 전이하는 도메인 메서드. 판정은 `Food` 소유(FR-001~003).

**Independent Test**: `./gradlew :domain:food:test` — 필드 조합 전수로 전이 여부 검증(순수 단위, DB 불필요).

### Tests for User Story 1 (Red — 먼저 작성, 실패 확인) ⚠️

- [X] T002 [US1] `FoodReadyTransitionTest` 작성 in `domain/food/src/test/kotlin/com/kbap/domain/food/model/FoodReadyTransitionTest.kt` — BehaviorSpec(given/when/then 한국어). 케이스: ① 4조건 전부 충족→READY·true ② imageRef null/blank→불변·false ③ description blank 또는 placeholder("설명 준비 중")→불변·false ④ nameTranslations/descriptionTranslations 9개 대상 언어 중 하나라도 누락→불변·false ⑤ hasAvoidanceMapping=false→불변·false ⑥ 이미 READY→불변·true(멱등) ⑦ spiciness=0 이어도 나머지 충족 시 READY(게이트 제외). 작성 후 실패(컴파일 에러 = Red) 확인

### Implementation for User Story 1

- [X] T003 [US1] `Food.transitionToReadyIfComplete(hasAvoidanceMapping: Boolean): Boolean` 구현 in `domain/food/src/main/kotlin/com/kbap/domain/food/model/Food.kt` — data-model.md 판정 기준. 9개 대상 언어 = `LanguageCode` 중 KO 제외. `./gradlew :domain:food:test` 그린 확인

**Checkpoint**: 전이 규칙 독립 검증 완료 — US2·US3 과 무관하게 머지 가능한 증분.

---

## Phase 4: User Story 3 - 레거시 배치 코드 제거 (Priority: P2)

**Goal**: 구 스코어링 배치·`:domain:research`·`FoodScoringSource` 를 걷어내고 빌드·테스트 그린 유지(FR-007). US2 의 자리 확보.

**Independent Test**: `./gradlew build` 그린 + `KbapBatchApplicationTests` 부팅 그린.

### Implementation for User Story 3 (삭제 작업 — 테스트 게이트는 기존 스위트 그린)

- [X] T004 [P] [US3] `:domain:research` 모듈 통째 삭제 — `domain/research/` 디렉터리 제거, `settings.gradle.kts` 의 `":domain:research"` include 제거, 루트 `build.gradle.kts` 의 `jacocoAggregation(project(":domain:research"))` 제거, `buildSrc/src/main/kotlin/kbap.domain-conventions.gradle.kts` 주석의 research 언급 정리
- [X] T005 [P] [US3] scoring 패키지 삭제 — `app/batch/src/main/kotlin/com/kbap/app/batch/scoring/`(4파일)·`app/batch/src/test/kotlin/com/kbap/app/batch/scoring/`(4파일) 제거, `app/batch/build.gradle.kts` 의 `:domain:research` 의존 제거, `app/batch/src/main/resources/application.yml` 의 `kbap.scoring.*` 블록 삭제(`kbap.llm.*`·`:infra:llm` 의존은 유지 — research.md D5)
- [X] T006 [US3] `FoodScoringSource` 삭제 in `domain/food/src/main/kotlin/com/kbap/domain/food/FoodScoringSource.kt` — 유일 소비자(ScoringJobConfig)가 T005 에서 제거된 뒤 수행. `FoodJpaRepository.findFoodIds` 도 소비자가 없어지면 함께 제거
- [X] T007 [US3] 전체 그린 확인 — `./gradlew build` + `:app:batch:test`(KbapBatchApplicationTests 부팅) 통과, 커밋

**Checkpoint**: 레거시 0 상태에서 빌드·부팅 그린 — US2 시작 가능.

---

## Phase 5: User Story 2 - 음식 단위 처리 러너 골격 (Priority: P1)

**Goal**: INCOMPLETE 키셋 조회 창구(`FoodContentBatchService`) + 단일 잡(`FoodContentJob`, 작업별 메서드 4개·건 단위 실패 격리) + 청크 설정 외부화(FR-004~006, FR-008).

**Independent Test**: `./gradlew :domain:food:test`(창구 Testcontainers 통합) + `:app:batch:test`(기존 부팅 테스트 그린) + 러너 on 수동 실행.

### Tests for User Story 2 (도메인만 Red — 배치는 테스트 생략) ⚠️

- [X] T008 [US2] `FoodContentBatchServiceTest` 작성 in `domain/food/src/test/kotlin/com/kbap/domain/food/FoodContentBatchServiceTest.kt` — BehaviorSpec + `@SpringBootTest` + MySQL Testcontainers(`:core` testFixtures). 케이스: ① `getIncompleteFoods(null, k)` 는 INCOMPLETE 만 id 오름차순 k건 ② `afterId` 이후만 반환(키셋) ③ READY 행 미포함 ④ `completeContent` 는 스텝이 채운 필드를 저장하고 완비 시 READY 전이·true, 미완비 시 저장만·false. 실패(Red) 확인

### Implementation for User Story 2

- [X] T009 [US2] `FoodJpaRepository` 키셋 쿼리 추가 + `FoodContentBatchService` 구현 in `domain/food/src/main/kotlin/com/kbap/domain/food/FoodContentBatchService.kt` — `@Service` + `internal constructor`, `getIncompleteFoods(afterId, size)`(@Transactional readOnly)·`completeContent(food, hasAvoidanceMapping)`(@Transactional, save + transitionToReadyIfComplete). T008 그린 확인
- [X] T010 [US2] `FoodContentJob` 구현 in `app/batch/src/main/kotlin/com/kbap/app/batch/content/FoodContentJob.kt` — **테스트 선행 없이 구현**(사용자 지시). 청크 소진 루프(키셋 afterId) + 음식 1건 try/catch 실패 격리 + 작업별 private 메서드 4개(`generateImage`·`generateDescription`·`translateNames`·`mapAvoidance` — 본문 비움, KB-183·184·209 자리) + 결과 로그(total/transitioned/failed)
- [X] T011 [US2] `ContentJobConfig` 구현 in `app/batch/src/main/kotlin/com/kbap/app/batch/content/ContentJobConfig.kt` — **테스트 선행 없이 구현**(사용자 지시). `@Import(FoodContentBatchService)` + `@ConditionalOnProperty("kbap.batch.content.runner.enabled")` 러너 + `@Value("\${kbap.batch.content.chunk-size:10}")`, `application.yml` 에 `kbap.batch.content` 블록(chunk-size: 10, runner.enabled: false) 추가. 검증: `:app:batch:test` 부팅 그린 + 러너 off/on 수동 확인

**Checkpoint**: 골격 완성 — 후속 태스크는 메서드 본문만 채우면 됨(SC-005). 배치 테스트 보강은 후속 스텝 태스크에서.

---

## Phase 6: Polish & Cross-Cutting

- [X] T012 전체 검증 — `./gradlew build`(ArchUnit 포함) 그린, quickstart.md 시나리오 확인(러너 off 부팅), 커밋 정리

---

## Dependencies & Execution Order

- **T001** → 모든 작업의 기준선
- **US3 (T004·T005 [P] → T006 → T007)**: 의존 없음 — **가장 먼저 실행**(깨끗한 바닥). T006 은 T005 뒤(소비자 제거 후)
- **US1 (T002→T003)**: 의존 없음 — US3 과 병렬도 가능하나 기본은 US3 뒤 순차
- **US2 (T008 → T009 → T010 → T011)**: T003(전이 메서드)·T007(FoodScoringSource 자리 확보) 뒤
- **T012**: 전부 완료 후

```text
T001 ── US3: (T004 ∥ T005) → T006 → T007 ── US1: T002 → T003 ── US2: T008 → T009 → T010 → T011 ── T012
```

## Implementation Strategy

- **증분 순서**: US3(제거) → US1(전이 규칙) → US2(러너 골격), 각 체크포인트마다 커밋(작업/논리 단위 커밋 규약).
- Red 확인 없이 구현 금지는 **도메인 태스크(T002·T003, T008·T009)에만 적용** — 배치 태스크(T010·T011)와 삭제 작업(T004~T007)은 기존 스위트·부팅 그린이 게이트(사용자 지시로 헌법 I 예외, 배치 테스트는 후속 보강).
