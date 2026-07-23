# Tasks: OpenAI Batch API 기반 음식 이미지 비동기 생성

**Input**: Design documents from `/specs/kb-226-food-image-batch/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/admin-food-image-batch.md, quickstart.md

**Tests**: Test-First **NON-NEGOTIABLE** (헌법 원칙 I) — 모든 로직 태스크는 실패 테스트(Red) 작성·확인 후 구현(Green). Kotest BehaviorSpec, given/when/then 한국어.

**Organization**: user story 별 독립 구현·검증 가능하도록 그룹화.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup

**Purpose**: 의존성·설정 준비

- [X] T001 ShedLock 의존성 추가 — `gradle/libs.versions.toml`에 shedlock-spring·shedlock-provider-jdbc-template 버전 등록, `app/api/build.gradle.kts`에 implementation 추가
- [X] T002 [P] `FoodImageProperties`(모델 gpt-image-2·quality medium·size 1024x1024·배치크기 10·프롬프트·promptVersion) 생성 in `application/src/main/kotlin/com/kbap/application/foodimage/FoodImageProperties.kt` + `app/api/src/main/resources/application.yml` 바인딩

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리가 의존하는 스키마·엔티티·상태 모델·포트. **US 착수 전 완료 필수**

- [X] T003 Flyway 마이그레이션(timestamp 버전) 3건 작성 in `app/api/src/main/resources/db/migration/` — ① `image_batch`·`image_batch_item`(FK·인덱스는 data-model.md 대로), ② `shedlock` 테이블, ③ `food.content_status` ENUM에 TEXT_READY 추가(MODIFY COLUMN)
- [X] T004 T003의 ENUM 변경을 테스트 손스텁 CREATE TABLE·시드에 전파 — `content_status`를 정의하는 테스트 파일 전수(`domain/scan`·`domain/bookmark`·`domain/food`·`app/api` 테스트 시드) 동기화, `./gradlew build`로 확인
- [X] T005 [P] (Red) `Food` 수렴 전이 테스트 작성 in `domain/food/src/test/kotlin/com/kbap/domain/food/model/FoodTest.kt` — 수렴표 3행(텍스트미완→INCOMPLETE 유지·이미지선도착 imageRef만, 텍스트완료+이미지없음→TEXT_READY, 텍스트완료+이미지있음→PENDING_REVIEW 직행) + PENDING_REVIEW/READY 후퇴 없음, 실패 확인
- [X] T006 (Green) `FoodContentStatus`에 TEXT_READY 추가 in `domain/food/src/main/kotlin/com/kbap/domain/food/model/FoodContentStatus.kt`, `Food.transitionToPendingReviewIfComplete()`를 수렴 전이 함수로 재작성 in `domain/food/src/main/kotlin/com/kbap/domain/food/model/Food.kt` — T005 통과
- [X] T007 [P] `ImageBatch`·`ImageBatchItem` 엔티티(BaseEntity 상속·public·JPA 연관관계 금지, batchId/foodId는 Long)와 `ImageBatchStatus`·`ImageBatchItemStatus` enum 생성 in `domain/food/src/main/kotlin/com/kbap/domain/food/model/`
- [X] T008 [P] `ImageBatchJpaRepository`(SUBMITTED 조회)·`ImageBatchItemJpaRepository`(batchId별·PENDING food_id 집합 조회) 생성 in `domain/food/src/main/kotlin/com/kbap/domain/food/`
- [X] T009 [P] `FoodImageBatchClient` 포트 인터페이스(submit/status/streamResults — contracts 문서 시그니처) 생성 in `core/src/main/kotlin/com/kbap/core/food/FoodImageBatchClient.kt` + 테스트 페이크 `FakeFoodImageBatchClient` in `application/src/test/kotlin/com/kbap/application/foodimage/`

**Checkpoint**: 스키마·상태 모델·포트 준비 완료 — US1/US2/US4 병렬 착수 가능

---

## Phase 3: User Story 1 - 관리자 이미지 일괄 제출 (Priority: P1) 🎯 MVP

**Goal**: 이미지 필요 음식을 10건 단위로 OpenAI Batch에 제출하고 메타 기록, 즉시 응답. 재실행 멱등.

**Independent Test**: 페이크 클라이언트로 — 이미지 없는 음식 N건 제출 시 배치 생성·SUBMITTED/PENDING 기록·즉시 응답, 연속 2회 호출 시 두 번째는 0건.

- [X] T010 [P] [US1] (Red) 제출 후보 조회 쿼리 테스트 작성 in `domain/food/src/test/kotlin/com/kbap/domain/food/FoodJpaRepositoryTest.kt` — `imageRef` 부재 AND PENDING item 미포함(상태값 필터 없음 — INCOMPLETE도 포함됨을 검증), 실패 확인
- [X] T011 [US1] (Green) 후보 조회 쿼리 구현 in `domain/food/src/main/kotlin/com/kbap/domain/food/FoodJpaRepository.kt` — T010 통과
- [X] T012 [US1] (Red) `FoodImageBatchSubmitService` 테스트 작성 in `application/src/test/kotlin/com/kbap/application/foodimage/FoodImageBatchSubmitServiceTest.kt` — 25건→10/10/5 분할 제출, entries의 customId=food PK·프롬프트에 koreanName, image_batch=SUBMITTED(promptVersion·model 기록)·item=PENDING 저장, 후보 0건이면 무제출 정상 응답, 연속 호출 재제출 없음, OpenAI 호출은 트랜잭션 밖, 실패 확인
- [X] T013 [US1] (Green) `FoodImageBatchSubmitService` 구현 in `application/src/main/kotlin/com/kbap/application/foodimage/FoodImageBatchSubmitService.kt` — T012 통과
- [X] T014 [P] [US1] (Red) admin 제출 엔드포인트 테스트 작성 in `app/api/src/test/kotlin/com/kbap/app/api/admin/AdminFoodImageControllerTest.kt` — `POST /api/v1/admin/foods/images` 200 + BaseResponse 봉투(submittedBatchCount/submittedFoodCount), 실패 확인
- [X] T015 [US1] (Green) admin 엔드포인트 구현 — `AdminApi`에 Swagger 시그니처 추가 in `app/api/src/main/kotlin/com/kbap/app/api/admin/AdminApi.kt`, `AdminController.submitFoodImages()` + `AdminFoodImageSubmitResponse` in `app/api/src/main/kotlin/com/kbap/app/api/admin/` — T014 통과
- [X] T016 [US1] `OpenAiFoodImageBatchClient.submit` 구현(JSONL 조립→Files 업로드(purpose=batch)→Batch 생성(24h window)) in `infra/llm/src/main/kotlin/com/kbap/infra/llm/food/OpenAiFoodImageBatchClient.kt` — 기존 OpenAI 키(`LlmModelProperties`) 재사용, JSONL 직렬화는 단위 테스트 in `infra/llm/src/test/kotlin/com/kbap/infra/llm/food/OpenAiFoodImageBatchClientTest.kt` (Red 먼저)
- [X] T017 [US1] 빈 조립 — `app/api` 설정에서 `FoodImageBatchClient`→`OpenAiFoodImageBatchClient` + `FoodImageBatchSubmitService` 와이어링 in `app/api/src/main/kotlin/com/kbap/app/api/config/` (기존 LLM 조립 위치 관례 따름)

**Checkpoint**: 제출 MVP 동작 — SUBMITTED 배치가 메타에 쌓임

---

## Phase 4: User Story 2 - 완료 배치 자동 회수 (Priority: P1)

**Goal**: 1시간 틱(2대 중 1대)이 SUBMITTED 배치를 폴링, 완료분을 스트리밍 회수→S3 저장→imageRef 갱신+수렴 전이→COLLECTED 마감. 중단 시 PENDING만 재처리.

**Independent Test**: 페이크 클라이언트(completed+결과 스트림)·페이크 스토리지로 — put 호출·imageRef 갱신·item DONE·배치 COLLECTED·상태 전이 확인. ShedLock은 Testcontainers 통합 테스트.

- [X] T018 [US2] (Red) `FoodImageBatchCollectService` 테스트 작성 in `application/src/test/kotlin/com/kbap/application/foodimage/FoodImageBatchCollectServiceTest.kt` — ① completed: 항목별 storage.put(`images/food/{foodId}.png` 결정적 키)→imageRef 갱신→수렴 전이(TEXT_READY→PENDING_REVIEW, INCOMPLETE는 유지)→item DONE, 전 항목 후 배치 COLLECTED, 장당 `LlmCallCostIncurred` 발행 ② in_progress: 스킵 ③ 멱등 재회수: DONE 항목 건너뛰고 PENDING만 처리 ④ 음식 삭제됨: item만 마감 ⑤ 항목별 error: 해당 item FAILED(error_msg), 실패 확인
- [X] T019 [US2] (Green) `FoodImageBatchCollectService` 구현 in `application/src/main/kotlin/com/kbap/application/foodimage/FoodImageBatchCollectService.kt` — seam 3분할(상태 조회/바이트 이동/DB 전이), 항목당 짧은 트랜잭션(TransactionTemplate), 외부 호출 트랜잭션 밖 — T018 통과
- [X] T020 [P] [US2] `OpenAiFoodImageBatchClient.status`·`streamResults` 구현 in `infra/llm/src/main/kotlin/com/kbap/infra/llm/food/OpenAiFoodImageBatchClient.kt` — 결과 파일 줄 단위 스트리밍(전체 메모리 적재 금지), 파싱 단위 테스트(Red 먼저) in `infra/llm/src/test/kotlin/com/kbap/infra/llm/food/OpenAiFoodImageBatchClientTest.kt`
- [X] T021 [US2] `SchedulingConfig`(@EnableScheduling·@EnableSchedulerLock·JdbcTemplateLockProvider) + `FoodImageCollectScheduler`(@Scheduled 1시간·@SchedulerLock name="food-image-collect" lockAtMostFor=30m) 생성 in `app/api/src/main/kotlin/com/kbap/app/api/config/`
- [X] T022 [US2] (Red→Green) ShedLock 선점 통합 테스트 in `app/api/src/test/kotlin/com/kbap/app/api/config/FoodImageCollectSchedulerLockTest.kt` — MySQL Testcontainers로 동시 2회 실행 시 1회만 수행됨을 검증

**Checkpoint**: 제출→회수 전 구간 자동화 완료

---

## Phase 5: User Story 3 - 실패·만료 항목 자동 재제출 (Priority: P2)

**Goal**: failed/expired 배치의 항목을 FAILED 마감하고, 다음 제출에 자동 재포함.

**Independent Test**: 페이크가 failed/expired 반환 → item FAILED·배치 FAILED, 이어서 제출 호출 시 해당 음식이 새 배치에 포함.

- [X] T023 [US3] (Red) failed/expired 처리 테스트 추가 in `application/src/test/kotlin/com/kbap/application/foodimage/FoodImageBatchCollectServiceTest.kt` — PENDING 전 항목 FAILED(error_msg)+배치 FAILED, 그리고 재제출 시나리오(FAILED 음식이 후보에 재포함 — imageRef 부재·PENDING 아님이므로) 검증, 실패 확인
- [X] T024 [US3] (Green) `FoodImageBatchCollectService`에 failed/expired 분기 구현 — T023 통과 (재제출은 US1 후보 조건이 이미 커버 — 별도 로직 없음을 테스트로 증명)

**Checkpoint**: 복구 경로 = 정상 경로 (멱등 완성)

---

## Phase 6: User Story 4 - 콘텐츠 배치와 이미지의 분리 (Priority: P2)

**Goal**: 콘텐츠 배치는 텍스트 3작업 전담 — 이미지 분기 제거, 수렴 전이 사용. 이미지만 남은 음식 재선정 0건.

**Independent Test**: 텍스트 3작업 완료·이미지 미보유 음식이 배치 재실행 시 선정되지 않음(TEXT_READY라 INCOMPLETE 조회에서 제외).

- [X] T025 [US4] (Red) 기존 배치 테스트 수정·추가 in `app/batch/src/test/kotlin/com/kbap/app/batch/content/FoodContentItemProcessorTest.kt`·`FoodContentPipelineTest.kt` — 이미지 분기 없음, 텍스트 4조건 완료 시 수렴 전이로 TEXT_READY(이미지 없음)/PENDING_REVIEW(이미지 있음) 저장, 이미지만 남은 음식 재선정 0건, 실패 확인
- [X] T026 [US4] (Green) `FoodContentItemProcessor`에서 `needsImage()` 분기·빈 스텁 `generateImage()` 제거 + 수렴 전이 호출 in `app/batch/src/main/kotlin/com/kbap/app/batch/content/FoodContentItemProcessor.kt`, `FoodContentBatchConfig`의 주석 처리된 이미지 클라이언트 조립 삭제 — T025 통과
- [X] T027 [P] [US4] 대체된 동기식 구현 삭제 — `core/src/main/kotlin/com/kbap/core/food/FoodImageGenerationClient.kt`·`infra/llm/src/main/kotlin/com/kbap/infra/llm/food/OpenAiFoodImageGenerationClient.kt` 및 참조·설정 잔재 제거(`FoodContentClientConfiguration` 포함)

**Checkpoint**: 배치는 텍스트 전담 — KB-224 잔재 정리 완료

---

## Phase 7: Polish & Cross-Cutting

- [X] T028 전체 검증 `./gradlew build` — ENUM 손스텁 누락·ArchUnit(`ModuleBoundaryTest`) 포함 전 모듈 통과 확인
- [X] T029 [P] quickstart.md 시나리오 수동 점검(로컬 제출 curl → 스케줄러 메서드 직접 호출) 및 배포 체크리스트 확인
- [X] T030 [P] Jira KB-226 DoD 체크 대조 및 spec/plan 문서 최종 정합(변경 발생 시 반영)

---

## Dependencies

```text
Phase 1 (Setup) → Phase 2 (Foundational) → ┬→ US1 (제출)  ─┬→ US3 (실패·재제출: US1+US2 필요)
                                           ├→ US2 (회수)  ─┘
                                           └→ US4 (배치 분리 — 독립)
US2는 US1 산출물(SUBMITTED 메타)을 시나리오상 소비하지만 페이크로 독립 테스트 가능.
Phase 7은 전 스토리 완료 후.
```

## Parallel Execution Examples

- Phase 2: T005(수렴 전이 테스트) ∥ T007(엔티티) ∥ T008(리포지토리) ∥ T009(포트+페이크) — 서로 다른 파일
- Phase 3: T010(레포 쿼리 테스트) ∥ T014(컨트롤러 테스트) 동시 착수 가능
- Phase 4: T020(infra 클라이언트) ∥ T018~T019(application 서비스) — 파일 분리, 페이크로 결합 차단
- US4(T025~T027)는 US1/US2와 파일이 겹치지 않아 Foundational 직후 병렬 가능

## Implementation Strategy

- **MVP = Phase 1~3 (US1)**: 제출만으로 "SUBMITTED 배치 확인" 가치 제공, OpenAI 콘솔로 생성 확인 가능
- 이후 US2(회수 자동화) → US3(복구) → US4(배치 정리) 순 증분 딜리버리
- 작업/논리 단위마다 커밋(헌법 Development Workflow)

## Format Validation

✅ 전 태스크 체크박스·TaskID·[P]/[Story] 라벨·파일 경로 포함 — 총 30건 (Setup 2 · Foundational 7 · US1 8 · US2 5 · US3 2 · US4 3 · Polish 3)
