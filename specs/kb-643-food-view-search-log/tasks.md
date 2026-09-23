# Tasks: 음식 상세 조회 횟수 비동기 로그 적재

**Input**: Design documents from `/specs/kb-643-food-view-search-log/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md (contracts/ 없음 — HTTP 계약 불변)

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 각 스토리는 실패하는 통합 테스트를 먼저 쓰고(Red) 구현으로 통과시킨다(Green).

**Organization**: 스토리별 단계. US1(조회 이력 적재)이 MVP, US2(실패 무영향)는 US1 위에 시나리오를 얹고, US3(MDC 전파)은 US1·US2 와 독립인 횡단 관심사다. **US3 는 Test-First 예외** — 프레임워크 기능의 설정 등록뿐이라 테스트를 두지 않고 로컬 실동작으로 확인한다(사용자 결정 2026-09-23, 설정만 켜는 노출 변경엔 테스트 없음 규칙).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완 태스크에 의존 없음)
- **[Story]**: US1 / US2
- 경로는 저장소 루트 기준

## Path Conventions

- 스키마: `api/src/main/resources/db/migration/`
- 엔티티·리포지토리: `common/src/main/kotlin/com/kbap/common/domain/food/`
- 이벤트·리스너·서비스: `api/src/main/kotlin/com/kbap/api/food/`
- 비동기 실행기 설정: `api/src/main/kotlin/com/kbap/api/core/config/`
- 테스트: `api/src/test/kotlin/com/kbap/api/`

---

## Phase 1: Setup

없음 — 신규 의존성·설정 변경 없음(`@EnableAsync` 는 `BackgroundConfig` 에 이미 있음).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 두 스토리가 공유하는 테스트 픽스처 정리. 스토리 시작 전에 끝낸다.

- [X] T001 `api/src/test/kotlin/com/kbap/api/TestTables.kt` 의 `tables` 목록에 `"food_view_log"` 를 `"food"` 보다 앞(예: `"bookmark"` 앞)에 추가한다 — FK 순서·잔여 행 오염 방지(plan 설계 요점 5).

**Checkpoint**: 이후 스토리 테스트가 `TestTables.clearAll` 로 시드를 정리할 수 있다.

---

## Phase 3: User Story 1 - 음식 상세를 볼 때마다 조회 이력이 한 줄 남는다 (Priority: P1) 🎯 MVP

**Goal**: `GET /api/foods/{foodId}` 성공 시 `food_view_log` 에 행 1개(food_id·member_id nullable·created_at). 실패 응답에는 행 없음. 응답은 저장을 기다리지 않는다.

**Independent Test**: 회원·게스트로 상세 조회 후 `eventually` 로 행 1건씩 확인, 같은 회원 3회 → 3건, 404 → `continually` 로 0건.

### Tests for User Story 1 (REQUIRED — 먼저 작성, 반드시 FAIL 확인) ⚠️

- [X] T002 [US1] `api/src/test/kotlin/com/kbap/api/food/FoodViewLogTest.kt` 를 `@IntegrationTest` + `BehaviorSpec` 으로 작성한다. 구조: `beforeContainer { TestTables.clearAll(dataSource) }`, `afterSpec { TestTables.clearAll(dataSource) }`, 시드는 `FoodJpaRepository.save(Food(koreanName=..., description=..., imageRef=null))` 로 READY 음식 1건(`FoodDetailImagesTest.seedFood` 방식 — READY 전이 방식은 그 파일을 따른다), 회원은 `FoodDetailControllerTest.accessToken` 과 같은 `INSERT INTO member ... ON DUPLICATE KEY UPDATE` + `TokenIssuer` 로 발급. 카운트 헬퍼는 `DataSource` 로 `SELECT COUNT(*) FROM food_view_log WHERE food_id = ?`(+ `member_id = ?` / `member_id IS NULL`). 시나리오(모두 `X-API-Version: 1.0`, `lang=ko`): (a) `given("음식 상세 조회 이력") > when("회원이 상세를 조회하면") > then("잠시 뒤 그 회원의 조회 이력 1행이 남는다")` — `eventually(5.seconds)`; (b) `when("게스트가 상세를 조회하면") > then("member_id 가 비어 있는 이력 1행이 남는다")`; (c) `when("같은 회원이 3번 조회하면") > then("이력 3행이 남는다")`; (d) `when("없는 음식을 조회해 실패 응답을 받으면") > then("이력이 남지 않는다")`(`FOOD_NOT_FOUND` 는 400) — `continually(1.seconds)`. 작성 후 `./gradlew :api:test` 로 **테이블 부재로 실패(Red)** 를 확인한다.

### Implementation for User Story 1

- [X] T003 [P] [US1] Flyway 마이그레이션 `api/src/main/resources/db/migration/V<파일 생성 시각 yyyy.MM.dd.HH.mm.ss>__food_view_log_table.sql` 을 data-model.md 의 SQL 그대로 만든다(`food_view_log`: id·food_id NOT NULL·member_id NULL(FK 없음 — research R5)·status·created_at·updated_at·`reserved_1~3 VARCHAR(255) NULL`, 인덱스 `idx_food_view_log_food_created (food_id, created_at)`). SQL 주석은 허용(Kotlin 만 금지). 버전은 `date "+%Y.%m.%d.%H.%M.%S"` 로 생성 시점 값을 쓴다.
- [X] T004 [P] [US1] 엔티티 `common/src/main/kotlin/com/kbap/common/domain/food/model/FoodViewLog.kt` — `@Entity @Table(name = "food_view_log", indexes = [Index(name = "idx_food_view_log_food_created", columnList = "food_id, created_at")]) class FoodViewLog(@Column(name = "food_id", nullable = false) var foodId: Long = 0, @Column(name = "member_id") var memberId: Long? = null) : BaseEntity()`. `reserved_1~3` 은 매핑하지 않는다. 도메인 메서드·주석 없음.
- [X] T005 [P] [US1] 리포지토리 `common/src/main/kotlin/com/kbap/common/domain/food/FoodViewLogJpaRepository.kt` — `interface FoodViewLogJpaRepository : JpaRepository<FoodViewLog, Long>`(public, 파생 쿼리 없음).
- [X] T006 [P] [US1] 이벤트 `api/src/main/kotlin/com/kbap/api/food/FoodViewed.kt` — `data class FoodViewed(val foodId: Long, val memberId: Long?)`.
- [X] T007 [US1] 리스너 `api/src/main/kotlin/com/kbap/api/food/FoodViewLogListener.kt` — `@Component class FoodViewLogListener(private val repository: FoodViewLogJpaRepository)`, 메서드 `@Async @EventListener @Transactional fun handle(event: FoodViewed)` 본문 `try { repository.save(FoodViewLog(foodId = event.foodId, memberId = event.memberId)) } catch (e: Exception) { log.error("음식 조회 이력 저장 실패 foodId={} memberId={}", event.foodId, event.memberId, e) }`. `LlmCallCostEventListener` 와 같은 형태이되 위임 서비스는 두지 않는다(research R4). (depends on T004, T005, T006)
- [X] T008 [US1] `api/src/main/kotlin/com/kbap/api/food/FoodService.kt` — 생성자에 `private val eventPublisher: ApplicationEventPublisher` 추가, `getDetail` 의 `return GetFoodDetailResult(...)` 직전에 `eventPublisher.publishEvent(FoodViewed(food.id, input.memberId))` 한 줄 추가. 기존 `FoodService` 를 직접 생성하는 테스트가 있으면 생성자 인자를 맞춘다(`grep -rn "FoodService(" api/src/test`). (depends on T006)
- [X] T009 [US1] `./gradlew :api:test` 로 `FoodViewLogTest` 4개 시나리오 Green + `ModuleBoundaryTest`(arch) 통과 + 기존 `FoodDetail*Test` 회귀 없음을 확인한다.

**Checkpoint**: US1 단독으로 배포 가능 — 조회 이력이 쌓이고 계약 변경 없음.

---

## Phase 4: User Story 2 - 이력 기록이 실패해도 사용자는 영향을 받지 않는다 (Priority: P2)

**Goal**: 저장 실패·지연이 상세 응답에 영향을 주지 않고, 실패는 error 로그로만 남는다.

**Independent Test**: 리스너를 직접 생성해 FK 위반 이벤트(존재하지 않는 food_id)를 `handle` 하면 예외가 전파되지 않고 행이 0건이다. 정상 경로에서 응답이 저장을 기다리지 않는 것은 US1 의 `eventually` 시나리오가 이미 증명한다(동기였다면 즉시 존재).

### Tests for User Story 2 (REQUIRED — 먼저 작성, 반드시 FAIL 확인) ⚠️

- [X] T010 [US2] `api/src/test/kotlin/com/kbap/api/food/FoodViewLogTest.kt` 에 시나리오 추가: `given("조회 이력 저장이 실패하는 상황") > when("리스너의 저장이 예외를 던지면") > then("예외가 전파되지 않는다")` — `java.lang.reflect.Proxy` 로 모든 호출이 `IllegalStateException` 을 던지는 `FoodViewLogJpaRepository` 프록시를 만들어 `FoodViewLogListener(failingRepository)` 를 직접 생성, `shouldNotThrowAny { listener.handle(FoodViewed(1L, null)) }`(모킹 라이브러리 없음·FK 없음이라 이 방식). T007 이전에 작성해 **Red 를 확인**한다.

### Implementation for User Story 2

- [X] T011 [US2] T007 의 try/catch + `log.error` 가 이 시나리오를 Green 으로 만드는지 확인한다. 추가 구현이 필요하면 `FoodViewLogListener.kt` 안에서만 한다(재시도·큐·전용 executor 금지 — research R3, 부가 방어 기능 금지).

**Checkpoint**: US1·US2 모두 Green.

---

## Phase 5: User Story 3 - 운영자가 비동기 저장 실패 로그를 원 요청과 연결한다 (Priority: P3)

**Goal**: 요청 스레드 MDC(requestId·memberId·osVersion·appVersion)가 `@Async` 스레드로 복사되고 작업 후 원복된다. `applicationTaskExecutor` 에 한 번 적용해 리스너 3종이 모두 혜택.

**Independent Test**: 테스트 코드 없음(2026-09-23 결정 — 프레임워크 기능의 설정 등록이라 새 테스트를 두지 않는다). 로컬 bootRun 에서 저장 실패 로그에 requestId·memberId 가 붙는지로 확인한다(T015).

### Implementation for User Story 3

- [X] T012 [US3] `api/src/main/kotlin/com/kbap/api/core/config/MdcTaskDecorator.kt` — `class MdcTaskDecorator : TaskDecorator { override fun decorate(runnable: Runnable): Runnable { val captured = MDC.getCopyOfContextMap(); return Runnable { val previous = MDC.getCopyOfContextMap(); if (captured == null) MDC.clear() else MDC.setContextMap(captured); try { runnable.run() } finally { if (previous == null) MDC.clear() else MDC.setContextMap(previous) } } } }`. 주석 없음.
- [X] T013 [US3] `api/src/main/kotlin/com/kbap/api/core/config/BackgroundConfig.kt` 에 `@Bean fun mdcTaskDecorator(): TaskDecorator = MdcTaskDecorator()` 추가. executor 빈·yml 은 만들지 않는다(Boot 자동구성이 유일한 TaskDecorator 빈을 `applicationTaskExecutor` 에 적용 — research R8). (depends on T012)
- [X] T014 [US3] `./gradlew :api:test` 로 기존 테스트 회귀 없음(컨텍스트 기동 포함)을 확인한다. 전파 동작 자체는 T015 의 로컬 확인으로 본다.

**Checkpoint**: 리스너 3종의 별도 스레드 로그에 requestId·memberId 가 붙는다(로컬 확인).

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T015 [P] quickstart.md 수동 검증 — 로컬 bootRun 후 회원·게스트 curl 2회 → `SELECT ... FROM food_view_log` 2행(member_id 하나는 NULL), 404 시 미증가, 테이블 RENAME 으로 실패 유도 시 200 + error 로그 1줄 **에 requestId·memberId 필드가 붙어 있는지**까지 본다. 결과를 plan.md `Status` 줄에 한 줄로 기록한다.
- [X] T016 [P] Kotlin 신규 파일에 주석이 없는지, `FoodService` 변경이 한 줄(+생성자 인자)인지, `BackgroundConfig` 변경이 `@Bean` 하나인지 diff 로 확인한다.
- [X] T017 spec.md `Status: Draft` → `Implemented (날짜)` 로 갱신하고 커밋한다(커밋 메시지에 KB-643, 검색 유기·예비 컬럼·MDC 전파 결정 근거 포함).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (T001)**: 즉시 시작. 스토리 테스트가 `clearAll` 에 의존하므로 먼저.
- **US1 (T002~T009)**: T001 이후. T002(테스트, Red) → T003·T004·T005·T006 병렬 → T007 → T008 → T009.
- **US2 (T010~T011)**: US1 의 T007 과 같은 파일이므로 US1 완료 후. T010 은 T002 와 같은 테스트 파일에 추가.
- **US3 (T012~T014)**: T001 이후 언제든. US1·US2 와 파일이 겹치지 않아 병렬 가능(같은 사람이면 US1 뒤에).
- **Polish (T015~T017)**: 전부 완료 후.

### Parallel Opportunities

- T003(SQL)·T004(엔티티)·T005(리포지토리)·T006(이벤트)는 서로 다른 파일이라 동시에 작성 가능.
- US3 전체(T012~T014)는 US1·US2 와 병렬.
- T015·T016 병렬.

---

## Parallel Example: User Story 1

```bash
# T002 를 먼저 쓰고 Red 확인 후:
Task: "Flyway food_view_log_table.sql 작성"          # T003
Task: "FoodViewLog 엔티티 작성"                        # T004
Task: "FoodViewLogJpaRepository 작성"                  # T005
Task: "FoodViewed 이벤트 작성"                          # T006
# 이어서 순차: T007 리스너 → T008 FoodService 발행 → T009 전체 테스트
```

---

## Implementation Strategy

### MVP First (US1)

1. T001 → T002(Red 확인) → T003~T008 → T009(Green).
2. 여기서 멈춰도 배포 가능: 이력이 쌓이고 클라이언트 영향 없음.

### Incremental Delivery

- US3 는 횡단 관심사라 US1 이전에 끝내도 무방하다. 끝내 두면 US1 의 저장 실패 로그에 처음부터 requestId 가 붙는다.
- US2 는 리스너의 예외 처리 검증이라 US1 구현에 이미 포함된 코드를 테스트로 고정하는 단계다. T010 을 T007 이전에 써서 Red 를 보는 것이 가장 정직한 순서다 — 실제 구현 순서는 T002·T010 을 같이 쓰고 Red 확인 → T003~T008 → T009 로 두 스토리를 한 번에 Green 으로 가져가도 된다.

---

## Notes

- 검색 로그·유입 경로 값·집계 API·보존 정책은 범위 밖(spec Assumptions). `reserved_1~3` 은 스키마에만 있고 코드에서 참조하지 않는다.
- Kotest 는 Gradle `--tests` 필터를 무시한다 — 부분 실행처럼 보여도 `:api:test` 전체가 돈다.
- 통합 테스트 헤더는 `@IntegrationTest` 하나만 쓴다. 새 컨텍스트를 만드는 `@SpringBootTest(properties=...)`·`@Import` 금지(KB-392).
- `WebConfig` JWT 보호 경로 등록은 불필요 — 새 엔드포인트가 없다.
- MDC 전파는 api 앱만. batch 는 `api.core.config` 를 스캔하지 않으며 `JobNameMdcListener` 가 자기 MDC 를 따로 관리한다.
