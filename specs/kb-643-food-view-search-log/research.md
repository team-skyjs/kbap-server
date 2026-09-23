# Research: 음식 상세 조회 횟수 비동기 로그 적재

## R1. 이벤트 리스너 종류 — `@EventListener` vs `@TransactionalEventListener(AFTER_COMMIT)`

- **Decision**: `@Async @EventListener` (미터링 `LlmCallCostEventListener` 와 동일).
- **Rationale**: 발행 지점이 `getDetail` 의 마지막 줄이라 "발행됨 = 조회 성공" 이 이미 보장된다. `getDetail` 은 `readOnly = true` 트랜잭션이라 커밋 실패로 되돌릴 쓰기가 없어 AFTER_COMMIT 게이트가 주는 이득이 없다. `@TransactionalEventListener` 는 트랜잭션 밖에서 발행되면 기본값(`fallbackExecution = false`)으로 이벤트를 조용히 버리는 함정이 있어, 나중에 `getDetail` 이 다른 경로(비트랜잭션 호출)에서 재사용되면 로그가 사라진다.
- **Alternatives considered**: `HelpfulPushListener` 의 `@Async @TransactionalEventListener(AFTER_COMMIT)` — 그쪽은 좋아요 저장(쓰기) 커밋 이후에만 푸시를 보내야 해서 필요한 것. 여기선 쓰기가 없다.

## R2. 조회 시각 컬럼 — 별도 `viewed_at` vs `BaseEntity.createdAt`

- **Decision**: `createdAt` 재사용. 별도 컬럼 없음.
- **Rationale**: 이벤트 발행 → 리스너 저장 사이 지연은 스레드 핸드오프 수준(밀리초)이라 "조회 시각" 으로 `created_at` 을 써도 집계(일·시간 단위)에 오차가 없다. 컬럼 하나·매핑 하나·시드 하나가 준다. `llm_call_cost` 도 같은 방식(`idx_llm_call_cost_created_at`).
- **Alternatives considered**: 이벤트에 `Instant` 를 실어 `viewed_at` 에 저장 — 응답 시각과 저장 시각을 구분할 필요가 생기면(예: 큐 지연이 분 단위가 될 때) 그때 추가. append-only 라 컬럼 추가는 과거 행과 충돌하지 않는다.

## R3. 비동기 실행기 — 전용 executor vs Boot 기본

- **Decision**: Boot 기본 `applicationTaskExecutor`(`@EnableAsync` 가 이미 `BackgroundConfig` 에 있음). yml·config 변경 없음.
- **Rationale**: 미터링·HELPFUL 푸시가 이미 같은 executor 를 쓰고 있고, 상세 조회 트래픽에서 INSERT 1건은 큐 포화를 만들 규모가 아니다. 서버 종료 시 큐 잔여분 유실은 spec 이 감수한다.
- **Alternatives considered**: `spring.task.execution.pool.*` 튜닝 또는 전용 `Executor` 빈 — 실측 포화가 보일 때(부가 방어 기능 금지 메모리 규칙).

## R4. 저장 경로 — 서비스 창구 경유 vs 리스너가 리포지토리 직접 저장

- **Decision**: 리스너가 `FoodViewLogJpaRepository.save` 를 직접 호출하고 `@Transactional` 을 자기 메서드에 선언.
- **Rationale**: 원칙 IV(KB-220) — 리포지토리 위임 한 줄뿐인 창구 서비스를 만들지 않는다. 미터링의 `LlmCallCostService.record` 는 이 규칙 이전 잔재로, 따라 만들지 않는다.
- **Alternatives considered**: `FoodViewLogService.record` — 검증·정책이 생기면(예: 어뷰징 필터) 그때 도메인 서비스로 승격.

## R5. 외래키 — FK 유무

- **Decision**: `food_id`·`member_id` 에 **FK 를 두지 않는다**(구현 중 2026-09-23 변경 — 처음엔 둘 다 걸었다).
- **Rationale**: 첫 실행에서 FK 가 기존 테스트 11개를 깼다 — 상세 조회를 호출하는 테스트마다 비동기로 이력 행이 생기고, 그 클래스들의 시드 정리(`DELETE FROM food`, FK 검사 켠 채 약 20곳)가 자식 행에 막힌다. 각 정리에 `DELETE FROM food_view_log` 를 앞세워도 비동기 insert 가 두 DELETE 사이에 끼면 다시 실패하는 경합이 남는다. 근본 원인은 "append-only 텔레메트리 로그가 원본 행의 생애에 묶여 있음"이라 FK 를 빼는 것이 맞다 — 로그는 원본이 사라져도 남아야 하고(FR-007), 저장 스레드가 원본 삭제와 순서를 다투면 안 된다. `llm_call_cost` 도 FK 없는 원장이다.
- **Alternatives considered**: (1) 테스트 정리 20곳 수정 — 경합 잔존·앞으로 추가되는 테스트마다 반복. (2) `ON DELETE CASCADE` — 프로젝트 규약(ON DELETE 없음)에 어긋나고 로그 보존 요구와 반대.
- **부작용**: 존재하지 않는 id 가 섞여도 DB 가 막지 않는다. 발행 지점이 `getReadyFood` 검증 뒤라 실제로 잘못된 id 가 들어올 경로는 없다. US2 의 실패 유도는 FK 위반 대신 예외를 던지는 리포지토리 프록시(`java.lang.reflect.Proxy`)로 한다.

## R6. 통합 테스트에서 비동기 완료 대기

- **Decision**: 존재 검증은 `eventually(5.seconds)`, 부재 검증(404 → 0건)은 `continually(1.seconds)`. 둘 다 `io.kotest.assertions.nondeterministic`. 카운트는 `DataSource` 로 직접 `SELECT COUNT(*)`.
- **Rationale**: `ReviewLikeControllerTest`·`LlmCallCostEventListenerTest` 와 같은 방식. `Thread.sleep` 은 느리고 불안정하다. JPA 리포지토리로 세면 테스트 스레드의 영속성 컨텍스트 캐시가 끼어들 수 있어 JDBC 로 센다.
- **Alternatives considered**: 테스트에서 `@Async` 를 동기 executor 로 바꾸는 별도 컨텍스트 — 컨텍스트 분기 금지 규약(KB-392) 위반.

## R7. 예비 컬럼 — `reserved_1~3 VARCHAR(255) NULL`

- **Decision**: 사용자 결정(2026-09-23) — 유입 경로 저장 가능성에 대비해 nullable 예비 컬럼 3개를 테이블 생성 시 함께 둔다. 엔티티에는 매핑하지 않는다.
- **Rationale**: 용도가 정해질 때 스키마 변경 없이 컬럼을 쓸 수 있게 하려는 것. 미매핑으로 두면 코드에 죽은 필드가 생기지 않고, `ddl-auto=validate` 도 통과한다(엔티티→스키마 방향만 검사).
- **Trade-off (기록)**: MySQL 8 은 nullable 컬럼 추가·이름 변경이 INSTANT DDL 이라 append-only 테이블에 나중에 붙여도 비용이 같다. 예비 컬럼은 이름·타입을 미리 고정하는 부담(VARCHAR(255) 가 맞지 않으면 어차피 ALTER)이 있다. 사용자가 이 트레이드오프를 알고 결정했다.
- **Alternatives considered**: 필요 시 `ADD COLUMN`(기본안, 기각) / 용도 이름으로 미리 명명(`source` 등) — 용도가 확정되지 않아 거짓 이름이 될 수 있어 `reserved_n` 채택.

## R8. 비동기 스레드 MDC 전파 — `TaskDecorator` 빈 vs 커스텀 executor vs 리스너별 수동 전달

- **Decision**: `TaskDecorator` 구현 하나(`MdcTaskDecorator`)를 `BackgroundConfig` 에 `@Bean` 으로 등록. executor 빈·yml 변경 없음.
- **Rationale**: 프로젝트는 플랫폼 스레드(`spring.threads.virtual` 미설정)라 Boot 4.1 의 `TaskExecutionAutoConfiguration` 이 `ThreadPoolTaskExecutorBuilder` 로 `applicationTaskExecutor` 를 만들고, 빌더 자동구성은 유일한 `TaskDecorator` 빈을 자동 적용한다. `@EnableAsync` 는 이 executor 를 기본으로 잡으므로 `@Async` 리스너 3종(`FoodViewLogListener`·`HelpfulPushListener`·`LlmCallCostEventListener`)이 코드 변경 없이 전파를 받는다. 복원(`finally`)을 넣는 이유는 풀 스레드 재사용 — 없으면 다음 작업 로그에 이전 요청의 requestId 가 남는다. `null` 캡처(요청 밖 발행 — 스케줄러·배치성 호출)는 `clear()` 로 처리해 오염을 막는다.
- **Alternatives considered**: (1) 커스텀 `ThreadPoolTaskExecutor` 빈 + `setTaskDecorator` — Boot 자동구성을 밀어내 풀 크기 등 기본값을 다시 적어야 하고 이득 없음. (2) 이벤트에 requestId 를 실어 리스너가 `MDC.put` — 리스너·이벤트마다 반복, 기존 2종도 수정 필요. (3) Micrometer Context Propagation(`ContextSnapshot`) — 의존성 추가·MDC 외 컨텍스트가 없어 과함.
- **검증**: 테스트 코드 없음(사용자 결정 2026-09-23 — 설정 등록만인 변경엔 테스트를 두지 않는다). 로컬 bootRun 에서 상세 조회 → 이력 저장 실패를 유도해 error 로그에 requestId·memberId 가 붙는지, 이어지는 다른 비동기 로그에 앞 요청 id 가 남지 않는지 눈으로 확인한다.

## R9. Codex 리뷰(PR #301) 처리 — 2026-09-23

- **반영**: 집계 인덱스 선두 컬럼을 `created_at` 으로 교체(`(created_at, food_id)`). 집계 쿼리가 시간 창으로 먼저 자르므로 `(food_id, created_at)` 은 선두 조건이 없어 전체 인덱스 스캔이 된다.
- **기각 — 발행 시점**: 결과 조립·컨트롤러의 북마크/리뷰 조회 실패 시 이력이 남을 수 있다는 지적. 발행은 `getReadyFood` 검증 뒤라 "음식 없음/비노출" 실패는 걸러지고, 그 이후 실패는 500 급 서버 오류다. 그 경우 이력 1행이 남는 것은 통계상 무시 가능한 잔여 위험으로 수용. 요청 완료 훅은 과설계.
- **기각 — 조회 시각 별도 캡처**: 큐 적체 시 `created_at` 이 저장 시점으로 밀릴 수 있다는 지적. R2 결정 유지 — 정상 부하에선 ms 단위, 적체 시에도 창 경계의 소수 행이 옆 창으로 옮겨가는 수준이며 스펙은 종료 시 유실까지 감수한다. 정밀도가 필요해지면 `viewed_at` 추가.

## R10. 게스트 식별자 — installation id 로 대체하지 않는다 (2026-09-23)

- **Decision**: 비회원 조회는 `member_id = NULL` 로 남기고 별도 식별자(installation id·session id)를 기록하지 않는다.
- **Rationale**: 사용자 판단 — 비회원의 조회를 추적해 개인화(유니크 뷰어·게스트→회원 연결)까지 잇을 필요가 없다. 인기순 집계는 총 조회수만 쓰므로 식별자가 없어도 결과가 달라지지 않는다.
- **조사 결과(참고)**: 프론트 공용 클라이언트가 모든 요청에 `X-Installation-Id` 를 붙이고 있어(`ApiHeaders.INSTALLATION_ID`, 로그인 시 회원과 연결됨) 기술적으로는 프론트 변경 없이 서버만 헤더를 읽으면 됐다. session id 는 실행마다 바뀌어 대안이 못 된다. 나중에 유니크 뷰어·어뷰징 필터가 필요해지면 `installation_id VARCHAR(36) NULL` 컬럼 추가 + 컨트롤러 헤더 읽기로 붙일 수 있고, 과거 행은 null 로 남는다.
- **Alternatives considered**: installation id 정식 컬럼 / 예비 컬럼 활용 — 둘 다 보류.
