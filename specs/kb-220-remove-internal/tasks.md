# Tasks: 모든 JPA 엔티티·리포지토리 internal 제거 — 영속 캡슐화 완화

**Input**: Design documents from `/specs/kb-220-remove-internal/`

**Prerequisites**: plan.md, spec.md, research.md(결정 D1~D6), data-model.md, quickstart.md

**Tests**: Test-First 는 헌법 원칙 I. 단 이 기능의 성격에 맞게 적용한다 — 가시성 완화(US1)는 동작 불변이라 기존 스위트가 회귀 방어를 맡고, 동작이 이동하는 재배선(US2)은 **특성화 테스트를 먼저 작성해 현행 그린을 고정한 뒤** 리팩터링한다(Red 가 아니라 "그린 고정 → 그린 유지"가 이 리팩터의 TDD 형태다). 창구 테스트 시나리오는 손실 없이 이전한다(research D6).

**Organization**: 스펙의 US1(P1: 직접 참조 가능) → US2(P2: 창구 제거) → US3(P3: 규칙·문서 갱신) 순. US2 는 US1 에, US3 는 US1·US2 에 의존한다(문서는 최종 상태를 서술해야 하므로).

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

없음 — 기존 멀티모듈 프로젝트에 신규 모듈·의존성·도구가 없다.

## Phase 2: Foundational

없음 — US1 자체가 전체의 전제 조건이며 독립 스토리로 수행한다.

---

## Phase 3: US1 — 소비 계층이 리포지토리를 직접 참조할 수 있다 (P1)

**Goal**: 도메인 모듈의 `internal` 선언 9곳과 부산물 `internal constructor` 6곳을 제거해 컴파일 차단을 해제한다.

**Independent Test**: `grep` 으로 잔존 `internal` 0건 확인 + 전 도메인 모듈 컴파일 성공(스펙 US1 수용 시나리오 2).

- [X] T001 [P] [US1] `internal` 제거 — food 3파일: `domain/food/src/main/kotlin/com/kbap/domain/food/FoodJpaRepository.kt` · `FoodJpaRepositoryCustom.kt` · `FoodJpaRepositoryCustomImpl.kt`, `internal constructor` 제거: `FoodService.kt`
- [X] T002 [P] [US1] `internal` 제거: `domain/member/src/main/kotlin/com/kbap/domain/member/MemberJpaRepository.kt`, `internal constructor` 제거: `MemberService.kt`
- [X] T003 [P] [US1] `internal` 제거: `domain/scan/src/main/kotlin/com/kbap/domain/scan/ScanHistoryJpaRepository.kt`, `internal constructor` 제거: `ScanService.kt`
- [X] T004 [P] [US1] `internal` 제거: `domain/bookmark/src/main/kotlin/com/kbap/domain/bookmark/BookmarkJpaRepository.kt`, `internal constructor` 제거: `BookmarkService.kt`
- [X] T005 [P] [US1] `internal` 제거: `domain/image/src/main/kotlin/com/kbap/domain/image/UploadedImageJpaRepository.kt`, `internal constructor` 제거: `ImageUploadService.kt`
- [X] T006 [P] [US1] `internal` 제거: `domain/metering/src/main/kotlin/com/kbap/domain/metering/LlmCallCostJpaRepository.kt`, `internal constructor` 제거: `LlmCallCostService.kt`
- [X] T007 [P] [US1] `internal` 제거: `domain/avoidance/src/main/kotlin/com/kbap/domain/avoidance/AvoidanceSubstanceJpaRepository.kt` (창구 `AvoidanceCatalogService` 의 ctor 는 US2 에서 파일째 삭제되므로 여기선 손대지 않음)
- [X] T008 [US1] 검증 — 엔티티 포함 잔존 `internal` 전수 확인(`grep -rn "^internal \|internal constructor" --include="*.kt" domain/*/src/main` 결과 0건) 후 `./gradlew compileKotlin compileTestKotlin` 그린 확인 (quickstart SC-001)

**Checkpoint**: 이 시점에 도메인 외부 모듈이 리포지토리를 import 해도 컴파일된다 — US2 재배선 가능.

---

## Phase 4: US2 — 우회용 창구 서비스가 사라진다 (P2)

**Goal**: `FoodContentBatchService`·`AvoidanceCatalogService` 를 삭제하고 소비처가 리포지토리를 직접 쓰되, 트랜잭션 의미(진행 저장 독립 커밋)를 보존한다(research D1·D3).

**Independent Test**: 창구 2파일 부재 + `:domain:food`·`:app:batch`·`:application`·`:app:api` 테스트 그린 + 진행 저장 독립 커밋 시나리오 통과.

- [X] T009 [US2] 특성화 테스트 선행 작성 — `app/batch/src/test/kotlin/com/kbap/app/batch/content/FoodContentPipelineTest.kt`(BehaviorSpec + MySQL Testcontainers): (1) 진행 저장은 뒤 작업 실패(청크 롤백)에도 커밋이 유지된다, (2) 콘텐츠 완비 음식은 READY 로 전이된다. **현행 배선(창구 경유)에서 그린임을 먼저 확인**해 리팩터 안전망으로 고정
- [X] T010 [US2] 창구 테스트 이전 — `domain/food/src/test/kotlin/com/kbap/domain/food/FoodContentBatchServiceTest.kt` 를 `FoodJpaRepositoryTest.kt` 로 개명·재작성: `getIncompleteFoods` 시나리오 3건은 `foodJpaRepository.findIncompleteAfter` 직접 검증으로, `completeContent` 시나리오 2건은 `food.transitionToReadyIfComplete()` + `save` 조합 검증으로 이전(시나리오 5건 전부 보존, research D6), 그린 확인
- [X] T011 [US2] 배치 재배선 — `app/batch/src/main/kotlin/com/kbap/app/batch/content/` 3파일: `IncompleteFoodItemReader.kt` 는 `FoodJpaRepository.findIncompleteAfter` 직접 호출, `FoodContentItemProcessor.kt` 는 `FoodJpaRepository` + `TransactionTemplate(PROPAGATION_REQUIRES_NEW)` 로 진행 저장(research D1), `FoodContentBatchConfig.kt` 는 `@Import(FoodContentBatchService)` 제거·`FoodJpaRepository` 주입·라이터에 `transitionToReadyIfComplete()+save` 인라인. T009 테스트 그린 유지 확인
- [X] T012 [US2] `domain/food/src/main/kotlin/com/kbap/domain/food/FoodContentBatchService.kt` 삭제 후 `./gradlew :domain:food:test :app:batch:test` 그린
- [X] T013 [P] [US2] 홈 재배선 — `application/src/main/kotlin/com/kbap/application/home/HomeApplicationService.kt` 가 `AvoidanceSubstanceJpaRepository.findByCodeIn` 직접 호출로 교체하고 빈 컬렉션 가드를 호출부에 둔다(research D3), `domain/avoidance/src/main/kotlin/com/kbap/domain/avoidance/AvoidanceCatalogService.kt` 삭제 (기존 홈 API 테스트가 동작 동일성 안전망)
- [X] T014 [US2] `./gradlew :application:test :app:api:test` 그린 확인 — 홈 응답 불변(SC-005)

**Checkpoint**: 위임 전용 창구 0개, 배치·홈 동작 동일 — MVP 가치 실현 완료.

---

## Phase 5: US3 — 아키텍처 규칙과 문서가 새 정책을 서술한다 (P3)

**Goal**: 헌법·ADR·컨벤션·CLAUDE.md·ArchUnit 문구를 완화된 정책으로 갱신한다(research D4·D5).

**Independent Test**: quickstart SC-004 grep 0건 + `arch` 태그 테스트 그린.

- [X] T015 [P] [US3] `app/api/src/test/kotlin/com/kbap/app/api/architecture/ModuleBoundaryTest.kt` — 옛 정책 문구("영속 접근은 …도메인 서비스만 허용" 류 then 설명)를 새 정책 서술로 갱신(규칙 구조는 불변, research D4), `./gradlew :app:api:test --tests "*ModuleBoundaryTest"` 그린
- [X] T016 [P] [US3] 헌법 개정 — `.specify/memory/constitution.md`: 원칙 IV 재정의(internal 폐기·완화 정책·유지 조항 명시 + 엔티티=도메인 모델 현실 정합화)·원칙 III "유일한 공개 창구" 조항 완화, 버전 4.0.0 → 5.0.0, Sync Impact Report 에 KB-220 근거·전파 대상 기록(plan Constitution Check 참고)
- [X] T017 [P] [US3] ADR 신설 — `docs/adr/0014-relax-persistence-encapsulation.md`: 완화 결정·근거(우회 창구 비용)·ADR-0012 의 internal 캡슐화 부분 supersede 표기(`_template.md` 구조 준수)
- [X] T018 [P] [US3] 아키텍처 문서 갱신 — `docs/architecture/meogo-conventions.md`(18·28·58·69·96행 일대의 internal·창구 서술)와 `docs/architecture/meogo-api-module-structure.md`(79행 일대)를 새 정책으로 수정
- [X] T019 [P] [US3] `CLAUDE.md` 갱신 — 개요(리포지토리는 internal…컴파일러 강제)·모듈 구조(`@Service` + `internal constructor`·리포지토리(internal))·컨벤션(경계 강제 세 겹·109행 패키지 서술·125행 JPA 엔티티 작성 절)을 새 정책으로 수정
- [X] T020 [US3] SC-004 검증 — `grep -rn "리포지토리는 .internal.\|internal 로 감춘다\|유일한 공개 창구" CLAUDE.md docs/architecture/ .specify/memory/constitution.md` 에 옛 정책 서술 0건(개정 이력·ADR 의 과거 기록 인용은 예외)

---

## Phase 6: Polish

- [X] T021 전체 검증 — `./gradlew build`(ArchUnit 포함 전 모듈 테스트) 그린 + `specs/kb-220-remove-internal/quickstart.md` SC-001~005 전 항목 수행·기록

## Dependencies

```text
US1 (T001–T008)  ──►  US2 (T009–T014)  ──►  US3 (T015–T020)  ──►  Polish (T021)
```

- US1 내부: T001~T007 전부 병렬(모듈별 독립 파일) → T008 검증.
- US2 내부: T009(특성화 테스트) → T010·T011 → T012. T013(홈)은 food 트랙과 파일이 겹치지 않아 T009~T012 와 병렬 가능(단 US1 완료 후) → T014.
- US3 내부: T015~T019 전부 병렬(서로 다른 파일) → T020 검증.
- 예외: T009 는 US1 완료 전에도 작성 가능(현행 배선 대상 테스트)하나, 단순화를 위해 순서대로 진행해도 손해 없음.

## Parallel Execution Examples

- US1: T001·T002·T003·T004·T005·T006·T007 동시 수행(7개 모듈 독립).
- US2: food 트랙(T009→T010→T011→T012)과 홈 트랙(T013) 병렬.
- US3: T015·T016·T017·T018·T019 동시 수행(테스트 1·문서 4 독립 파일).

## Implementation Strategy

- **MVP = US1 + US2**: internal 제거만으로는(US1) 사용자 가치가 이름뿐이므로, 창구 제거(US2)까지가 실질 MVP 다. US3 는 코드 최종 상태 확정 후 한 번에 서술한다.
- 커밋 단위 권장: US1 1커밋 → US2 food 트랙 1커밋 → US2 홈 트랙 1커밋 → US3 1커밋.
