# Tasks: 앱 내부 메트릭 노출 — api·batch Prometheus 엔드포인트 + 배치 헬스체크

**Input**: Design documents from `/specs/kb-380-prometheus-metrics-endpoint/`

**Prerequisites**: plan.md, spec.md, research.md(R-1~R-8), data-model.md, contracts/metrics-endpoint.md, quickstart.md

**Tests**: **작성하지 않는다**(사용자 지시 2026-08-27 — 헌법 원칙 I 면제, plan.md Constitution Check 참조). 검증은 로컬 `bootRun` + curl 과 dev 배포 확인으로 한다.

**Organization**: 스토리별 그룹. US1(api)·US2(batch)는 서로 독립, US3 은 US2 의 헬스체크가 전제.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완 태스크 의존 없음)
- **[Story]**: US1 / US2 / US3
- Kotlin 소스 주석 금지(yml·terraform 주석은 허용)

## Path Conventions

- api: `api/build.gradle.kts`, `api/src/main/resources/application.yml`
- batch: `batch/build.gradle.kts`, `batch/src/main/resources/application.yml`, `batch/src/main/kotlin/com/kbap/batch/config/`
- 인프라: `iac/terraform/modules/ecs-environment/batch.tf`, 문서 `iac/terraform/README.md`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 두 모듈이 공유하는 라이브러리 좌표 등록

- [X] T001 `gradle/libs.versions.toml` `[libraries]` 에 `micrometer-registry-prometheus = { module = "io.micrometer:micrometer-registry-prometheus" }` 추가(버전은 Boot BOM 관리 — 버전 표기 없음, 주석으로 KB-380·BOM 관리 명시). `./gradlew help` 로 카탈로그 파싱 확인

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 해당 없음 — 도메인·스키마·공통 인프라 변경이 없다. T001 완료 즉시 US1·US2 병렬 착수.

---

## Phase 3: User Story 1 - api 가 컨테이너 안 상태를 수집 가능한 형태로 제공 (Priority: P1) 🎯 MVP

**Goal**: api 의 `/actuator/prometheus` 가 JVM·GC·스레드·HikariCP·HTTP 요청 메트릭을 `application="kbap-api"` 태그로 응답하고, 노출 목록 밖 엔드포인트는 404, 기존 readiness 는 그대로.

**Independent Test**: `:api:bootRun`(local) 후 quickstart "로컬 검증" curl 4종 통과.

### Implementation for User Story 1

- [X] T002 [P] [US1] `api/build.gradle.kts` dependencies 에 `"runtimeOnly"(libs.micrometer.registry.prometheus)` 추가(actuator 줄 바로 아래)
- [X] T003 [P] [US1] `api/src/main/resources/application.yml` 최상위에 `management:` 블록 추가 — `endpoints.web.exposure.include: health,prometheus`, `metrics.tags.application: ${spring.application.name}`. yml 주석 한 줄: KB-380, 포트 분리 없음, 공개 차단은 ALB 규칙 후속(research R-3)
- [X] T004 [US1] `./gradlew :api:compileKotlin :api:test` — 기존 테스트 회귀 없음 확인(`RequestLoggingFilterTest` 등 actuator 를 건드리는 테스트 포함)
- [X] T005 [US1] 로컬 검증 — `SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun` 후:
  - `curl -s localhost:8080/actuator/prometheus | grep -E '^(jvm_memory_used_bytes|hikaricp_connections_active|jvm_gc_pause_seconds|jvm_threads_live_threads)' | head` → 4종 존재
  - `curl -s localhost:8080/actuator/prometheus | grep -c 'application="kbap-api"'` → 0 초과
  - `curl -s localhost:8080/api/app-version >/dev/null; curl -s localhost:8080/actuator/prometheus | grep 'http_server_requests_seconds_count' | grep 'uri="/api/app-version"'` → 1줄 이상
  - `curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/actuator/env` → 404
  - `curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/actuator/health/readiness` → 200
  결과를 PR 본문용으로 기록
- [ ] T006 [US1] 커밋: `feat(api): Prometheus 메트릭 엔드포인트 노출 (KB-380)` — T001~T003

**Checkpoint**: api 단독으로 KB-381 Alloy 가 긁을 수 있는 상태(MVP). 인프라 변경 없이 dev 배포 가능

---

## Phase 4: User Story 2 - batch 도 같은 메트릭 + 헬스체크로 자동 교체 (Priority: P2)

**Goal**: batch 의 `/actuator/prometheus` 가 JVM 메트릭과 Spring Batch 잡·스텝 메트릭을 `application="kbap-batch"` 로 응답하고, ECS 컨테이너 헬스체크가 `/actuator/health/readiness` 를 본다.

**Independent Test**: `:batch:bootRun` 후 잡 1회 트리거 → `grep spring_batch_job_seconds` 존재; dev 배포 후 `describe-tasks` 의 `healthStatus == HEALTHY`.

### Implementation for User Story 2

- [X] T007 [P] [US2] `batch/build.gradle.kts` dependencies 에 `"implementation"(libs.spring.boot.starter.actuator)` + `"runtimeOnly"(libs.micrometer.registry.prometheus)` 추가
- [X] T008 [P] [US2] `batch/src/main/resources/application.yml` 에 `management:` 블록 추가 — api 와 동일 2개 + yml 주석 한 줄(KB-380, ECS 컨테이너 헬스체크가 readiness 를 본다)
- [X] T009 [US2] `./gradlew :batch:compileKotlin :batch:test` — 기존 테스트(`KbapBatchApplicationTests`·`BatchJobTriggerControllerTest`·`BatchJdbcJobRepositoryConfigTest`) 회귀 없음. actuator 추가로 컨텍스트에 빈이 늘어나므로 부팅 실패가 없는지 확인
- [ ] T010 [US2] 로컬 판정(R-6) — `SPRING_PROFILES_ACTIVE=local ./gradlew :batch:bootRun` 후:
  - `curl -s localhost:8080/actuator/prometheus | grep -c 'application="kbap-batch"'` → 0 초과
  - `curl -s -X POST 'localhost:8080/internal/batch/jobs?jobName=foodContentOutboxPublishJob'` → 202, `executionId` 로 `GET /internal/batch/executions/{id}` 가 COMPLETED 될 때까지 확인
  - `curl -s localhost:8080/actuator/prometheus | grep -E '^spring_batch_(job|step)_seconds_count'` → 있으면 T011 건너뜀, **없으면 T011 수행**
  - `curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/actuator/env` → 404
- [ ] T011 [US2] (조건부 — T010 에서 잡 메트릭 부재일 때만) `batch/src/main/kotlin/com/kbap/batch/config/BatchJdbcJobRepositoryConfig.kt` 에 `io.micrometer.observation.ObservationRegistry` 생성자 주입 + `override fun getObservationRegistry(): ObservationRegistry = observationRegistry` 추가(주석 없음). T010 의 grep 재확인. 결과(수행 여부와 근거)를 research R-6 아래 한 줄로 기록
- [X] T012 [US2] `iac/terraform/modules/ecs-environment/batch.tf` batch 컨테이너 정의(`portMappings` 다음, `environment` 앞)에 `healthCheck` 블록 추가 — `api.tf` 것과 동일: `command = ["CMD-SHELL", "curl -sf http://localhost:8080/actuator/health/readiness || exit 1"]`, `interval 15`, `timeout 5`, `retries 3`, `startPeriod 150`. `terraform -chdir=iac/terraform fmt` 실행(init 돼 있으면 `validate` 도)
- [ ] T013 [US2] 커밋: `feat(batch): Prometheus 메트릭 노출 + ECS 컨테이너 헬스체크 (KB-380)` — T007~T012

**Checkpoint**: 인프라 diff 는 `batch.tf` 헬스체크뿐(SC-002)

---

## Phase 5: User Story 3 - 배포 후에도 batch 헬스체크 유지 (Priority: P2)

**Goal**: CI 이미지 교체 배포가 헬스체크를 승계하도록 롤아웃 순서와 절차를 문서로 고정하고 dev 에서 실증한다.

**Independent Test**: dev 에서 quickstart 순서(앱 → `-replace` → 재배포) 수행 후 `describe-task-definition kbap-dev-ecs-batch` 에 `healthCheck` 존재 + 태스크 HEALTHY.

### Implementation for User Story 3

- [X] T014 [US3] `iac/scripts/deploy-batch.sh` 확인 — `jq` 가 `.containerDefinitions` 전체를 복제하고 `.image` 만 교체하므로 `healthCheck` 가 승계됨을 확인(변경 불필요 예상). 승계되지 않는 구조였다면 보존 로직 추가
- [X] T015 [US3] `iac/terraform/README.md` 갱신 — (a) "## 배포" batch 항목에 헬스체크 롤아웃 순서 3줄: ① actuator 포함 batch 이미지 먼저 배포 ② `terraform apply -replace=module.ecs_environment.aws_ecs_task_definition.batch` ③ batch 재배포 1회로 승계; (b) "## 알아둘 것" 에 두 줄: 구 이미지(actuator 없음)를 새 리비전에 올리면 헬스체크 실패 → 서킷브레이커 롤백(되돌릴 땐 healthCheck 제거 후 `-replace` 먼저) / `/actuator/**` 공개 차단은 ALB 리스너 규칙 후속 — 차단 목록 대신 `/api/*` 허용 목록 권장(경로 정규화 우회 방지, research R-3)
- [ ] T016 [US3] 커밋: `docs(infra): batch 헬스체크 롤아웃 순서·공개 차단 후속 메모 (KB-380)` — T015
- [ ] T017 [US3] dev 롤아웃 — **사용자 수행**(AWS 자격증명 필요). quickstart "dev 롤아웃" 1~3 단계 순서대로 실행하고 확인 결과(api 컨테이너 `application="kbap-api"` grep 수, batch `healthStatus`, 리비전의 `healthCheck`, ALB 타깃 healthy 유지)를 기록

**Checkpoint**: 세 스토리 완료. prod 는 별도 배포 창에서 같은 순서

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T018 `./gradlew build` 전체(ArchUnit `arch` 태그 포함) 통과 확인
- [ ] T019 [P] Jira KB-380 DoD 문구를 최종 설계에 맞게 갱신(Atlassian MCP `editJiraIssue`, ADF) — "management.server.port=8081"·"8081 portMappings"·"healthCheck → 8081"·"통합 테스트(BehaviorSpec)" 항목을 "8080·`/actuator` 유지, 공개 차단은 ALB 규칙 후속(별도 태스크)"·"batch.tf healthCheck 신설(`/actuator/health/readiness`)"·"로컬 curl + dev 확인으로 검증(테스트 코드 없음)" 으로 교체. ALB 허용 목록 규칙을 새 태스크로 등록할지 사용자에게 제안
- [ ] T020 [P] 지식 위키 `../kbap-agenthub/wiki/` 에 결정 기록 — "관측 경계: 앱은 노출만, 공개 차단은 ALB 허용 목록" + "ECS `ignore_changes=[container_definitions]` 하에서 헬스체크 반영은 `-replace` → CI 재배포 승계" + "R-6 판정 결과"(INDEX.md 한 줄 추가, 허브에서 커밋)
- [ ] T021 `open-draft-pr-to-develop` 스킬로 base=develop draft PR 생성 — 제목 `feat(observability): api·batch Prometheus 메트릭 노출 + batch ECS 헬스체크 (KB-380)`, 본문에 설계 요점(8080 유지·공개 차단 후속·롤아웃 순서·테스트 없음 결정)과 T005/T010/T017 검증 결과

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 즉시. T001 하나
- **Foundational (Phase 2)**: 없음
- **US1 (Phase 3)** ∥ **US2 (Phase 4)**: T001 이후 서로 독립(다른 모듈·다른 파일)
- **US3 (Phase 5)**: T012(batch.tf) 이후. T017 은 T006·T013 커밋이 dev 에 배포 가능한 상태여야 함
- **Polish (Phase 6)**: 전 스토리 완료 후. T019 ∥ T020 ∥ T018

### Within Each User Story

- 빌드 파일·yml 병렬(T002∥T003, T007∥T008) → 컴파일+기존 테스트 회귀(T004/T009) → 로컬 curl 검증(T005/T010) → 조건부 보강(T011) → 인프라(T012) → 커밋
- T011 은 T010 결과가 "잡 메트릭 부재" 일 때만 — 미리 하지 않는다(YAGNI, R-6)

### Parallel Opportunities

- T002 ∥ T003 · T007 ∥ T008 · Phase 3 ∥ Phase 4 · T018 ∥ T019 ∥ T020

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. T001 → T002~T006 (api 만, 인프라 변경 0)
2. **STOP and VALIDATE**: T005 curl 5종. 이 시점에 api 를 dev 에 배포하면 KB-381 이 api 부터 착수 가능

### Incremental Delivery

1. US1 → api 배포 (MVP)
2. US2 → batch 코드 + `batch.tf` 헬스체크 + R-6 판정
3. US3 → README + dev 롤아웃(앱 → `-replace` → 재배포)
4. Polish → 전체 빌드·Jira DoD·위키·PR

### 판정 포인트

- **T010**: `spring_batch_job_seconds` 존재 여부 → T011 수행/스킵. 결과를 research R-6 에 기록
- **T017**: batch `HEALTHY` 가 `startPeriod`(150s) 안에 안 오면 상향 검토

---

## Notes

- 테스트 코드 없음(사용자 지시). 검증 증거는 curl 출력·ECS 상태를 PR 본문에 남긴다
- Kotlin 변경은 T011(조건부) 하나뿐 — 주석 금지. 근거는 커밋 메시지·research R-6
- yml·terraform 주석은 규약 밖(허용) — 기존 블록과 같은 톤으로 한두 줄
- 커밋은 T006·T013·T016 세 번
