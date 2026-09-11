# Tasks: Sentry 연동 — api·batch 컨테이너 에러 로그 수집

**Input**: Design documents from `/specs/kb-508-sentry-error-alerting/`

**Prerequisites**: plan.md, spec.md, research.md(R1~R11), data-model.md, contracts/sentry-config.md, quickstart.md

**Tests**: Test-First 필수(헌법 원칙 I). 각 스토리는 Spring 없는 Kotest `BehaviorSpec` 을 Red 로 먼저 두고 구현한다(R10). 통합 컨텍스트는 건드리지 않는다 — `@IntegrationTest` 에 프로퍼티 추가 금지, DSN 부재 = Sentry 빈 미등록.

**Organization**: 스토리별로 묶어 독립 구현·검증. 코드 변경은 전부 `api.core.observability`·`batch.observability` 와 설정/배포 파일. `GlobalExceptionHandler`·`RequestLoggingFilter` 무수정.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완 태스크 의존 없음)
- **[Story]**: US1 / US2 / US3
- Kotlin 소스 주석 금지 — 근거는 커밋 메시지·docs 로

## Path Conventions

- api 코드: `api/src/main/kotlin/com/kbap/api/core/observability/`, 테스트 `api/src/test/kotlin/com/kbap/api/core/observability/`
- batch 코드: `batch/src/main/kotlin/com/kbap/batch/observability/`, 테스트 `batch/src/test/kotlin/com/kbap/batch/observability/`
- 설정: `api/src/main/resources/application.yml`, `batch/src/main/resources/application.yml`
- 배포: `.github/workflows/deploy-{dev,prod,batch-dev,batch-prod}.yml`, `iac/terraform/modules/ecs-environment/variables.tf`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: SDK 아티팩트를 두 부트앱에 올리고 컴파일이 되는지 확인(R2)

- [X] T001 `gradle/libs.versions.toml` 에 `sentry` 버전(8.55.0)과 라이브러리 좌표 `sentry-spring-boot4-starter`(`io.sentry:sentry-spring-boot-4-starter` — Boot 4 전용 모듈명 확정)·`sentry-logback`(`io.sentry:sentry-logback`) 추가
- [X] T002 `api/build.gradle.kts` 에 `"implementation"(libs.sentry.spring.boot4.jakarta)`·`"implementation"(libs.sentry.logback)` 추가 (T001 의존)
- [X] T003 `batch/build.gradle.kts` 에 동일 두 좌표 추가 (T001 의존)
- [X] T004 `./gradlew :api:compileKotlin :batch:compileKotlin` 로 Boot 4 모듈 가용성 확인. 좌표가 없으면 R2 폴백 순서(`sentry-spring-boot-starter-jakarta` → 수동 `Sentry.init` + `SentryAppender`)로 T001~T003 을 수정하고 `research.md` R2 에 확정 좌표를 기록 (T002, T003 의존)

**Checkpoint**: 두 모듈이 Sentry 의존성을 물고 컴파일된다. 아직 DSN·설정이 없어 런타임 동작은 없다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리가 기대는 `sentry.*` 설정 두 벌을 테스트로 고정(R1·R6·R7·R8, contracts §2)

**⚠️ CRITICAL**: 이 단계 없이는 어떤 이벤트도 생기지 않는다

- [X] T005 `api/src/test/kotlin/com/kbap/api/core/observability/SentryConfigTest.kt` 작성(BehaviorSpec, Spring 없음) — 클래스패스의 `application.yml` 을 SnakeYAML 로 읽어 `sentry.dsn == "${API_SENTRY_DSN:}"`, `sentry.exception-resolver-order == Integer.MIN_VALUE`, `sentry.send-default-pii == false`, `sentry.tags.service == "api"`, `sentry.logging.minimum-event-level == "error"`, `sentry.logging.minimum-breadcrumb-level == "info"`, `sentry.release == "${SENTRY_RELEASE:}"` 를 단언. 실행해 **실패 확인**
- [X] T006 `api/src/main/resources/application.yml` 에 contracts §2 의 `sentry:` 블록 추가(dsn·environment `${SPRING_PROFILES_ACTIVE:local}`·release·send-default-pii·exception-resolver-order·tags.service·logging). `application-local.yml`·`api/src/test/resources/application.yml` 에는 넣지 않는다. T005 **통과 확인** (T005 의존)
- [X] T007 [P] `batch/src/main/resources/application.yml` 에 `sentry:` 블록 추가 — `dsn: ${BATCH_SENTRY_DSN:}`, environment, release, send-default-pii false, `tags.service: batch`, logging 수위 동일, `exception-resolver-order` 없음
- [X] T008 `./gradlew :api:test :batch:test` 로 기존 통합 테스트가 컨텍스트 수 변화 없이 전부 통과하는지 확인(DSN 부재 → Sentry 자동구성 skip). 실패하면 자동구성이 DSN 없이도 올라오는 것이므로 R6 재검토 (T006, T007 의존)

**Checkpoint**: 설정이 고정됐고 통합 테스트 회귀 없음. 스토리 구현 시작 가능.

---

## Phase 3: User Story 1 - api 의 에러 응답이 요청 맥락과 함께 Sentry 에 남는다 (Priority: P1) 🎯 MVP

**Goal**: 핸들러 예외(4xx·5xx 전부)가 리졸버 경로로 캡처되고, `requestId`·`memberId`·`http.status`·`error.code` 태그와 에러 코드 핑거프린트가 붙는다(FR-001·FR-005·FR-008, R1·R3)

**Independent Test**: dev 배포 후 quickstart 시나리오 1~3·6 — 4xx/5xx 요청 각각 이벤트 생성, 태그 채움, 같은 요청 3회 = 이슈 1·이벤트 3. 로컬은 `SentryRequestContextProcessorTest` 가 태그·핑거프린트 규약을 검증

### Tests for User Story 1 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T009 [US1] `api/src/test/kotlin/com/kbap/api/core/observability/SentryRequestContextProcessorTest.kt` 작성(BehaviorSpec, Spring 없음, `SentryEvent` 직접 생성) — given/when/then 한국어: (1) `BusinessException`(4xx 코드) → `http.status`=코드의 status, `error.code`=코드 문자열, `fingerprint == ["business", code]`; (2) `BusinessException`(5xx 코드) → 같은 규칙; (3) `ErrorResponse` 구현 예외(예: `ResponseStatusException`) → `http.status` 가 그 상태, 핑거프린트 미설정; (4) 일반 `RuntimeException` → `http.status=500`, `error.code` 없음; (5) MDC 에 `requestId`·`memberId` 있으면 태그로, 없으면 태그 없음(게스트); (6) throwable 없는 이벤트(로그 경로)는 `http.status` 를 붙이지 않는다. 실행해 **실패 확인**(클래스 없음)

### Implementation for User Story 1

- [X] T010 [US1] `api/src/main/kotlin/com/kbap/api/core/observability/SentryRequestContextProcessor.kt` 구현 — `@Component class SentryRequestContextProcessor : io.sentry.EventProcessor`, `process(event: SentryEvent, hint: Hint)` 에서 MDC(`requestId`·`memberId`) → `event.setTag`, `event.throwable` 을 `BusinessException`/`ErrorResponse`/그 외로 분기해 `http.status`·`error.code`·`fingerprint` 설정. 태그 키는 data-model.md 그대로. T009 **통과 확인** (T009 의존)
- [X] T011 [US1] `./gradlew :api:test` 전체 통과 확인 — `@Component` 가 통합 컨텍스트에 올라가도 Sentry 빈 없이 무해한지(EventProcessor 인터페이스만 구현, Sentry 초기화 의존 없음) 검증 (T010 의존)

**Checkpoint**: api 프로세서 완성. DSN 만 주입하면 4xx·5xx 이벤트가 태그와 함께 수집된다.

---

## Phase 4: User Story 2 - 코드가 남긴 에러 로그와 batch 잡 실패가 Sentry 에 남는다 (Priority: P1)

**Goal**: ERROR 로그 → 이벤트, INFO 이상 → breadcrumb(Phase 2 의 logging 수위가 담당), batch 잡 실패에 `job` 태그(FR-002·FR-003·FR-006, R7·R9)

**Independent Test**: quickstart 시나리오 5 — 벡터 스토어 미설정 상태로 `foodVectorSyncJob` 트리거 → `kbap-batch` 에 `job=foodVectorSyncJob`·`service=batch` 이벤트, breadcrumb 에 직전 INFO 로그. 로컬은 `JobNameMdcListenerTest` 가 MDC 세팅·해제를 검증

### Tests for User Story 2 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T012 [US2] `batch/src/test/kotlin/com/kbap/batch/observability/JobNameMdcListenerTest.kt` 작성(BehaviorSpec, Spring 없음, `JobExecution`/`JobInstance` 직접 생성) — `beforeJob` 후 `MDC.get("job") == 잡 이름`, `afterJob` 후 `MDC.get("job") == null`, 잡 실패(`ExitStatus.FAILED`)여도 `afterJob` 이 MDC 를 지운다. 실행해 **실패 확인**

### Implementation for User Story 2

- [X] T013 [US2] `batch/src/main/kotlin/com/kbap/batch/observability/JobNameMdcListener.kt` 구현 — `@Component class JobNameMdcListener : JobExecutionListener`, `beforeJob` 에 `MDC.put("job", jobExecution.jobInstance.jobName)`, `afterJob` 에 `MDC.remove("job")`. T012 **통과 확인** (T012 의존)
- [X] T014 [P] [US2] `batch/src/main/kotlin/com/kbap/batch/outbox/FoodContentOutboxBatchConfig.kt` 의 `JobBuilder("foodContentOutboxPublishJob", …)` 체인에 `.listener(jobNameMdcListener)` 추가(생성자/파라미터 주입) (T013 의존)
- [X] T015 [P] [US2] `batch/src/main/kotlin/com/kbap/batch/vector/FoodVectorSyncBatchConfig.kt` 의 `JobBuilder("foodVectorSyncJob", …)` 체인에 `.listener(jobNameMdcListener)` 추가 (T013 의존)
- [X] T016 [US2] `./gradlew :batch:test` 전체 통과 확인 — `@BatchIntegrationTest` 컨텍스트가 리스너 빈을 올리고 기존 잡 테스트가 그대로 통과하는지 (T014, T015 의존)

**Checkpoint**: batch 이벤트에 잡 이름이 붙는다. api·batch 의 ERROR 로그는 Phase 2 설정으로 이미 이벤트가 된다.

---

## Phase 5: User Story 3 - 컨테이너별로 구분되고, 비밀값 없이 환경별로 켜고 끈다 (Priority: P2)

**Goal**: api/batch 프로젝트 분리(DSN 2개), SSM 주입, release=이미지 태그, 로컬 비활성(FR-004·FR-009·FR-010·FR-011, R4·R5·R6). 코드는 Phase 2 yml 로 끝났고 이 단계는 배포 계약(contracts §1·§3·§4)

**Independent Test**: `git grep -i "sentry.io\|ingest.sentry"` 0건, 로컬 부팅에서 예외 내도 이벤트 없음, dev 배포 후 `aws ecs describe-task-definition` 에 `SENTRY_RELEASE` env 와 `API_SENTRY_DSN`/`BATCH_SENTRY_DSN` secrets 존재, 이벤트의 `environment=dev`·`release=api-<sha>`

### Implementation for User Story 3

- [X] T017 [P] [US3] `iac/terraform/modules/ecs-environment/variables.tf` — `api_secret_names` 기본값에 `"API_SENTRY_DSN"`, `batch_secret_names` 기본값에 `"BATCH_SENTRY_DSN"` 추가
- [X] T018 [P] [US3] `.github/workflows/deploy-dev.yml` 의 jq(`.name == "api"`) 를 contracts §3 형태로 변경 — `.image = $IMAGE` 에 이어 `.environment` 에서 기존 `SENTRY_RELEASE` 를 제거하고 `{name: "SENTRY_RELEASE", value: $TAG}` 추가. `$TAG` 는 그 워크플로가 계산한 이미지 태그(`api-<sha>` 또는 수동 `image_tag`)를 `--arg TAG` 로 전달
- [X] T019 [P] [US3] `.github/workflows/deploy-prod.yml` 동일 변경
- [X] T020 [P] [US3] `.github/workflows/deploy-batch-dev.yml` 동일 변경(`.name == "batch"`, `$TAG`=`batch-<sha>`)
- [X] T021 [P] [US3] `.github/workflows/deploy-batch-prod.yml` 동일 변경
- [X] T022 [US3] 4개 워크플로의 jq 식을 로컬에서 검증 — 스크래치패드에 `describe-task-definition` 형태의 샘플 JSON(environment 없음 / 기존 SENTRY_RELEASE 있음 두 케이스)을 만들어 `jq --arg IMAGE x --arg TAG api-abc '<식>'` 실행, 결과에 SENTRY_RELEASE 가 정확히 1개인지 확인 (T018~T021 의존)
- [X] T023 [US3] `git grep -n "sentry.io"` 와 `git grep -n "ingest"` 로 레포에 DSN 문자열 0건 확인, `SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun` 부팅 로그에 Sentry 초기화 로그가 없고 정상 기동하는지 확인 (T006 의존)

**Checkpoint**: 배포 계약 완성. SSM 파라미터 생성 → terraform apply → 배포 순서(quickstart §순서)를 밟으면 dev 에서 E2E 검증 가능.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 문서·전체 빌드·Jira·dev 검증

- [X] T024 [P] `docs/observability/sentry.md` 신규 작성(R11) — 아티팩트·`sentry.*` 설정 의미·태그 규약(data-model 표)·프로젝트/SSM 이름·배포 순서·필터 단계 401/400 은 미수집(R1)·후속 알림 규칙 예시(프로젝트별, `http.status` 5xx 조건)·노이즈 조정(코드별 `beforeSend` 제외, 샘플링)·되돌리기
- [X] T025 [P] `docs/observability/grafana-app-dashboard.md` 또는 `docs/architecture/` 의 관측 개요가 있으면 Sentry 역할 분담(Grafana=얼마나, Sentry=무엇이 어디서) 한 줄 추가. 없으면 생략
- [X] T026 `./gradlew build` 전체 통과(ArchUnit `arch` 태그 포함 — `api.core.observability`·`batch.observability` 가 `ModuleBoundaryTest` 에 걸리지 않는지) (T011, T016 의존)
- [X] T027 커밋 분리 — (1) 의존성+yml+테스트, (2) api 프로세서, (3) batch 리스너, (4) terraform+워크플로, (5) 문서. 각 커밋 메시지에 설계 근거(R1 리졸버 순서, R3 핑거프린트) 기록
- [X] T028 Jira KB-508 본문 갱신 — 범위 정정(4xx 포함·Slack 알림은 콘솔 후속·컨테이너 구분 두 층) 및 quickstart 배포 순서 링크
- [ ] T029 dev 검증(quickstart) — Sentry 콘솔 프로젝트 2개 생성 → SSM dev 파라미터 2개 → terraform apply(dev) → api·batch dev 배포 → 시나리오 1~6 확인 결과를 PR 본문에 기록. prod 는 dev 검증 후 반복 (T026, T022 의존)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 즉시 시작. T004 가 SDK 좌표를 확정해야 이후 코드가 컴파일된다
- **Foundational (Phase 2)**: Phase 1 완료 후. 모든 스토리를 블록
- **User Story 1 (Phase 3)**: Phase 2 완료 후. 다른 스토리 의존 없음
- **User Story 2 (Phase 4)**: Phase 2 완료 후. US1 과 독립(다른 모듈)
- **User Story 3 (Phase 5)**: Phase 2 완료 후. 코드 의존 없음(배포 파일만) — 사실상 Phase 1 뒤 아무 때나 가능
- **Polish (Phase 6)**: T026 은 US1·US2 완료 후, T029 는 전부 완료 후

### User Story Dependencies

- **US1 (P1)**: api 모듈만. 독립
- **US2 (P1)**: batch 모듈만. 독립(ERROR 로그 수집은 Phase 2 yml 이 이미 제공)
- **US3 (P2)**: iac·워크플로만. 독립

### Within Each User Story

- 테스트 먼저 작성·실패 확인 → 구현 → 통과 확인 → 모듈 전체 테스트
- 워크플로 jq 는 편집 후 T022 로컬 검증 필수(배포 시점에 깨지면 태스크 정의 등록 실패)

### Parallel Opportunities

- Phase 1: T002·T003 병렬(다른 build 파일)
- Phase 2: T007 은 T005·T006 과 병렬(batch yml)
- Phase 3·4·5 는 서로 병렬 가능(api / batch / iac+workflows)
- Phase 4: T014·T015 병렬(다른 config 파일)
- Phase 5: T017~T021 전부 병렬(파일 5개 모두 다름)
- Phase 6: T024·T025 병렬

---

## Parallel Example: Phase 5 (US3)

```bash
# 다섯 파일을 동시에 편집한 뒤 T022 로 한 번에 검증
Task: "variables.tf 에 API_SENTRY_DSN·BATCH_SENTRY_DSN 추가"
Task: "deploy-dev.yml jq 에 SENTRY_RELEASE 주입"
Task: "deploy-prod.yml jq 에 SENTRY_RELEASE 주입"
Task: "deploy-batch-dev.yml jq 에 SENTRY_RELEASE 주입"
Task: "deploy-batch-prod.yml jq 에 SENTRY_RELEASE 주입"
```

---

## Implementation Strategy

### MVP First (Phase 1 + 2 + US1)

1. Phase 1 로 SDK 좌표 확정(폴백 여부가 여기서 갈린다)
2. Phase 2 로 yml 고정 + 통합 테스트 회귀 없음 확인
3. US1 프로세서 → **여기까지가 MVP**: DSN 만 주입하면 api 4xx·5xx 가 태그와 함께 수집된다
4. US3 의 배포 계약(T017~T022)을 같은 PR 에 넣어야 dev 에서 실제로 볼 수 있다

### Incremental Delivery

1. Phase 1~3 → api 수집(MVP)
2. Phase 4 → batch 잡 이름 태그
3. Phase 5 → 배포 계약(release·DSN 주입)
4. Phase 6 → 문서·dev 검증 → PR

### Scope Guard

- 이 PR 은 **수집 기반**까지다. Slack 알림 규칙·4xx 샘플링·ECS 메타데이터 호출·`sentry.enabled` 스위치·batch 용 프로세서·APM 트레이싱은 넣지 않는다(spec Assumptions, R3·R6)
- `GlobalExceptionHandler`·`RequestLoggingFilter`·`@IntegrationTest` 무수정

---

## Notes

- T004 에서 Boot 4 모듈이 없으면 폴백 경로가 Phase 2·3 의 파일 구성을 바꾼다(수동 `Sentry.init` config + `HandlerExceptionResolver` 빈). 그 경우 tasks.md 를 갱신하고 진행
- 태그 키(`http.status`·`error.code`·`requestId`·`memberId`·`job`·`service`)는 data-model.md 가 단일 출처 — 문서(T024)와 코드가 어긋나지 않게 한다
- 커밋은 태스크 묶음 단위(T027 분할안), 각 checkpoint 에서 모듈 테스트 통과 확인 후
