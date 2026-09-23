# Implementation Plan: 음식 상세 조회 횟수 비동기 로그 적재

**Branch**: `kb-643-food-view-search-log` | **Date**: 2026-09-23 | **Status**: Implemented 2026-09-23 — api 1539·common 572·batch 52 테스트 전부 통과, 로컬 bootRun 검증(게스트 조회 행 1·400 시 미증가·테이블 RENAME 실패 유도 시 200 + `task-2` 스레드 error 로그에 reqId 전파) 완료 | **Spec**: [spec.md](spec.md) | **Jira**: [KB-643](https://simhani1.atlassian.net/browse/KB-643)

**Input**: Feature specification from `/specs/kb-643-food-view-search-log/spec.md`

## Summary

음식 상세 조회(`GET /api/foods/{foodId}`)가 성공할 때마다 `food_view_log` 에 append-only 행 하나(음식 id·회원 id nullable·시각)를 남긴다. `FoodService.getDetail` 이 음식 조회·검증을 마친 끝에서 `FoodViewed` 이벤트를 발행하고, `FoodViewLogListener`(`@Async @EventListener`)가 리포지토리에 저장한다 — 미터링 원장(`LlmCallCostIncurred` → `LlmCallCostEventListener` → `LlmCallCostService.record`)과 같은 구조를 그대로 복제한다. 저장 실패는 리스너가 삼키고 error 로그만 남긴다. **추가 범위(2026-09-23)**: `TaskDecorator` 빈 하나(`MdcTaskDecorator`)를 `BackgroundConfig` 에 등록해 Boot 자동구성 `applicationTaskExecutor` 가 요청 스레드의 MDC(requestId·memberId·osVersion·appVersion)를 `@Async` 스레드로 복사·복원하게 한다 — 이 리스너뿐 아니라 기존 `HelpfulPushListener`·`LlmCallCostEventListener` 도 같이 혜택. HTTP 계약·클라이언트 변경 없음. 검색 로그·유입 경로 값·집계 API 는 범위 밖.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 — `@EnableAsync`(`BackgroundConfig` 에 이미 켜져 있음), Spring Data JPA, Flyway. 신규 의존성 없음

**Storage**: MySQL — 신규 테이블 `food_view_log`(Flyway 마이그레이션 1건, `:api` 가 스키마 owner)

**Testing**: Kotest `BehaviorSpec` + `@IntegrationTest`(MySQL Testcontainers). 비동기 완료 대기는 `io.kotest.assertions.nondeterministic.eventually`(기존 `LlmCallCostEventListenerTest`·`ReviewLikeControllerTest` 와 동일). 실패 무영향은 리스너를 직접 생성해 저장 실패를 유도하는 방식(미터링 테스트 선례)

**Target Platform**: `:api` bootJar 만. `:batch` 는 이 로그를 쓰지도 읽지도 않는다

**Project Type**: web-service (Gradle 멀티모듈 모듈러 모놀리스)

**Performance Goals**: 상세 조회 응답 시간 불변(SC-002 — p95 +5% 이내). 이벤트 발행은 동기 호출 1회(마이크로초), 저장은 별도 스레드

**Constraints**: 응답이 저장을 기다리지 않음 / 저장 실패는 로그만 / 실패 응답에는 행 없음 / 엔티티는 `BaseEntity` 상속(status·created_at·updated_at 공통) / 컬럼 길이·타입은 MySQL 기준 / Kotlin 주석 금지

**Scale/Scope**: 프로덕션 신규 6파일(마이그레이션 1·엔티티 1·리포지토리 1·이벤트 1·리스너 1·`MdcTaskDecorator` 1) + 수정 2파일(`FoodService`·`BackgroundConfig`), 테스트 신규 1파일(`FoodViewLogTest`) + 수정 1파일(`TestTables`). `MdcTaskDecorator` 는 테스트 없음(설정 등록 — 로컬 실동작 확인)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS (예외 1) | 통합 테스트(상세 조회 → `eventually` 로 행 1건, 게스트 null, 404 시 0건, 저장 실패 시 예외 미전파)를 먼저 작성해 Red(테이블·리스너 부재) → 마이그레이션·엔티티·리스너로 Green. **예외**: `MdcTaskDecorator` 등록은 프레임워크 기능의 설정뿐이라 테스트를 두지 않는다(사용자 결정, 설정만 켜는 변경엔 테스트 없음 규칙) — 로컬 bootRun 에서 비동기 로그의 requestId 로 확인 |
| II. Bounded Contexts | PASS | 엔티티·리포지토리는 `common.domain.food.model`/`common.domain.food` — 음식 조회 사건은 food 컨텍스트 소유. 회원은 `memberId: Long?` 값 참조라 `ModuleBoundaryTest` 허용 맵(`food → ingredient`) 변경 없음. 이벤트·리스너는 `com.kbap.api.food` |
| III. Layered Dependency | PASS | api → common 단방향 유지. batch 무관. 외부 seam 없음. `MdcTaskDecorator` 는 api 전용 공통재라 `api.core.config`(BackgroundConfig 옆) |
| IV. Persistence Ownership | PASS | 엔티티 = 도메인 모델(`FoodViewLog`, 도메인 메서드 없음 — 사건 기록), 리포지토리 public, JPA 연관 없음(id 값). FK 는 의도적으로 없음(append-only 로그 — research R5). 리스너가 저장 트랜잭션을 명시 소유(`@Transactional`) |
| V. Content Language Policy | N/A | 언어·콘텐츠 무관 |

게이트 통과 — 위반 없음. (Phase 1 설계 후 재평가: 동일.)

## Project Structure

### Documentation (this feature)

```text
specs/kb-643-food-view-search-log/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks 산출 (여기서 만들지 않음)
```

contracts/ 는 만들지 않는다 — HTTP 요청·응답 계약이 바뀌지 않는다(FR-009).

### Source Code (repository root)

```text
api/src/main/resources/db/migration/
└── V2026.09.23.HH.mm.ss__food_view_log_table.sql       # 신규 (생성 시각으로 명명)

common/src/main/kotlin/com/kbap/common/domain/food/
├── FoodViewLogJpaRepository.kt                         # 신규: JpaRepository<FoodViewLog, Long>
└── model/FoodViewLog.kt                                # 신규: @Entity, BaseEntity 상속

api/src/main/kotlin/com/kbap/api/core/config/
├── BackgroundConfig.kt                                 # 수정: @Bean fun mdcTaskDecorator(): TaskDecorator = MdcTaskDecorator()
└── MdcTaskDecorator.kt                                 # 신규: MDC 복사 → 작업 전 setContextMap → finally 원복

api/src/main/kotlin/com/kbap/api/food/
├── FoodViewed.kt                                       # 신규: data class(foodId, memberId?)
├── FoodViewLogListener.kt                              # 신규: @Async @EventListener → repository.save, 실패는 log.error
└── FoodService.kt                                      # 수정: ApplicationEventPublisher 주입, getDetail 끝에서 publishEvent

api/src/test/kotlin/com/kbap/api/
├── TestTables.kt                                       # 수정: "food_view_log" 를 목록 맨 앞쪽(food 보다 앞)에 추가
└── food/FoodViewLogTest.kt                             # 신규: 통합 테스트
```

**Structure Decision**: 미터링 선례(`common.domain.metering` 엔티티 + `api.metering` 이벤트·리스너·서비스)를 food 에 그대로 옮긴다. 단, 미터링의 `LlmCallCostService.record`(위임 한 줄)는 만들지 않는다 — 원칙 IV "위임 전용 창구 서비스 금지" 에 따라 리스너가 리포지토리를 직접 저장하고 자기 `@Transactional` 을 선언한다. 이벤트 클래스는 소비자가 api 뿐이라 `com.kbap.api.food` 에 둔다(미터링 이벤트가 `common` 에 있는 것은 발행자가 `common.infra.llm` 이기 때문).

## 설계 요점

1. **발행 지점** — `FoodService.getDetail` 의 `return` 직전. `getReadyFood` 가 이미 존재·READY 를 검증하고 실패 시 `BusinessException` 을 던지므로, 발행에 도달했다는 것 자체가 "성공한 조회"다(FR-001·002). 컨트롤러는 손대지 않는다(컨트롤러 = 서비스 호출 + DTO 매핑 규약).
2. **리스너** — `@Async @EventListener`(미터링과 동일, `@TransactionalEventListener` 아님 — research R1). 본문은 `try { repository.save(FoodViewLog(...)) } catch (e: Exception) { log.error(...) }`. `@Transactional` 을 리스너 메서드에 선언(별도 스레드라 호출자 트랜잭션과 무관, 자기 경계 소유).
3. **엔티티** — `FoodViewLog(foodId: Long, memberId: Long?)` : `BaseEntity`. 조회 시각은 `BaseEntity.createdAt` 을 그대로 쓴다(별도 `viewed_at` 컬럼 없음 — research R2). 도메인 메서드 없음.
4. **스키마** — `food_view_log(id, food_id NOT NULL, member_id NULL, status, created_at, updated_at, reserved_1~3 VARCHAR(255) NULL)`. 인덱스 `idx_food_view_log_food_created (food_id, created_at)` 하나 — "기간 내 음식별 조회 수"(FR-008) 를 커버. 회원별 조회는 요구 없음 → 인덱스 없음. `reserved_1~3` 은 사용자 결정(2026-09-23)에 따른 예비 컬럼 — 유입 경로 등을 나중에 담을 자리이며 엔티티에 매핑하지 않는다(research R7).
5. **테스트** — 새 클래스 `FoodViewLogTest` 하나. (a) 회원 조회 → `eventually(5s)` 행 1건·member_id 일치, (b) 게스트 조회 → member_id NULL, (c) 같은 회원 3회 → 3건, (d) 없는 음식 404 → `continually` 로 0건, (e) 예외를 던지는 리포지토리 프록시로 리스너를 직접 생성해 `handle` 호출 → 예외 미전파. (d) 의 실패 응답은 `FOOD_NOT_FOUND` = 400. `TestTables.clearAll` 이 하드코딩 목록이라 `food_view_log` 추가 필수(안 하면 다른 클래스의 `food` 삭제가 FK 에 막힘 — FK 검사를 끄긴 하지만 잔여 행이 다음 클래스 카운트를 오염).
6. **MDC 전파** — `MdcTaskDecorator : TaskDecorator`. `decorate` 시점에 `MDC.getCopyOfContextMap()` 을 잡고, 반환 Runnable 은 실행 스레드의 기존 맵을 백업 → 캡처본 설정(null 이면 `MDC.clear()`) → `run()` → `finally` 백업 원복(null 이면 clear). `BackgroundConfig` 에 `@Bean` 으로 등록하면 Boot `ThreadPoolTaskExecutorBuilder` 자동구성이 `ObjectProvider<TaskDecorator>.getIfUnique()` 로 집어 `applicationTaskExecutor` 에 적용한다 — yml·executor 빈 정의 불필요(research R8). 배치 앱은 대상 아님(자기 스캔 범위에 `api.core.config` 없음).
7. **하지 않는 것** — 검색 로그, 유입 경로 값 수집(예비 컬럼만 둔다), 집계 API, 보존 정책, 재시도 큐, 전용 executor 튜닝(Boot 기본 `applicationTaskExecutor` 사용 — research R3).

## Complexity Tracking

위반 없음 — 해당 없음.
