# Tasks: 스프링 컨테이너 메트릭 개선 (Tomcat 스레드풀·HTTP 지연 백분위 노출)

**Input**: Design documents from `specs/kb-411-spring-container-metrics/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/prometheus-exposition.md, quickstart.md

**Tests**: **자동 테스트 없음 — 사용자 결정(2026-09-02, "테스트 코드까지는 짤 필요 없어")**. 프레임워크 기본 기능의 설정 노출뿐이라 KB-380 의 "설정 노출은 자동화 테스트 없음" 결정과 같은 선상이다. 검증은 로컬 bootRun 실노출 확인(quickstart §2)과 dev 배포 후 카디널리티 확인(§3)으로 한다. 헌법 원칙 I 예외는 이 사유로 plan.md Complexity Tracking 에 기록.

**Organization**: 유저 스토리별 Phase. US1·US2 는 같은 두 파일(yml·테스트 클래스)을 건드리므로 순차 실행한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완료 의존 없음)
- **[Story]**: US1·US2·US3·US4
- 경로는 저장소 루트 기준

## Path Conventions

- 설정: `api/src/main/resources/application.yml`
- 테스트: `api/src/test/kotlin/com/kbap/api/core/metrics/`
- 문서: `docs/observability/`
- 지식 위키: `../kbap-agenthub/wiki/`

---

## Phase 1: Setup

없음 — 새 의존·모듈·디렉터리 초기화가 필요 없다(actuator·prometheus 레지스트리 이미 존재).

## Phase 2: Foundational

없음 — 유저 스토리를 막는 선행 작업이 없다.

---

## Phase 3: User Story 1 - 응답 지연 백분위를 본다 (Priority: P1) 🎯 MVP

**Goal**: `/actuator/prometheus` 가 `http_server_requests_seconds_bucket` 을 노출해 홈서버가 인스턴스 2대를 합산한 p95·p99 를 계산할 수 있다.

**Independent Test**: 로컬 bootRun 후 요청 1건 → `/actuator/prometheus` 본문에 `http_server_requests_seconds_bucket{` 가 있고 `quantile=` 행은 없다.

### Implementation for User Story 1

- [x] T003 [US1] `api/src/main/resources/application.yml` 의 `management.metrics` 아래에 `distribution.percentiles-histogram.http.server.requests: true`·`distribution.minimum-expected-value.http.server.requests: 5ms`·`distribution.maximum-expected-value.http.server.requests: 10s` 추가(yml 주석으로 "앱 내 백분위 금지 — 인스턴스 2대 합산" 근거, KB-411).
- [x] T005 [US1] 로컬 실노출 확인(quickstart §2): `bootRun` 후 `curl /api/app-version` → `curl /actuator/prometheus | grep -c "^http_server_requests_seconds_bucket"` 로 조합당 버킷 수 기록 → **51개/조합**, `quantile=` 행 0. research.md §3 에 기록.

(T001·T002·T004 테스트 태스크는 사용자 결정으로 삭제.)

**Checkpoint**: 버킷 노출 확인. US2 와 함께 한 커밋.

---

## Phase 4: User Story 2 - 웹 서버 포화 여부를 본다 (Priority: P1)

**Goal**: 실제 Tomcat 으로 기동한 api 가 `tomcat_threads_busy_threads`·`tomcat_threads_current_threads`·`tomcat_threads_config_max_threads` 를 노출한다.

**Independent Test**: 로컬 `bootRun` 의 `/actuator/prometheus` 에 `tomcat_threads_*` 3종이 있다.

### Implementation for User Story 2

- [x] T008 [US2] `api/src/main/resources/application.yml` 의 `server` 아래에 `tomcat.mbeanregistry.enabled: true` 추가(yml 주석 "Tomcat 스레드풀 메트릭은 MBean 레지스트리가 켜져야 등록된다 — KB-411").
- [x] T010 [US2] 로컬 실노출 확인(quickstart §2): `bootRun` 후 `curl /actuator/prometheus | grep -E "^tomcat_threads_(busy|current|config_max)_threads"` → busy 1·current 10·max 200 확인. `batch/src/main/resources/application.yml` 미변경.

(T006·T007·T009 테스트 태스크는 사용자 결정으로 삭제.)

**Checkpoint**: 앱 측 변경 완료. 커밋 `feat(api): Tomcat 스레드풀·HTTP 지연 히스토그램 메트릭 노출`. draft PR #222 → develop 머지 → dev 배포로 US3 의 실데이터가 생긴다.

---

## Phase 5: User Story 3 - 앱 대시보드 한 장으로 상태를 훑는다 (Priority: P2)

**Goal**: 홈서버 Grafana 에 `env` 필터 + 6 패널(req/sec·p95/p99·HikariCP·GC·Heap·Tomcat) 앱 대시보드가 있다.

**Independent Test**: `env` 를 prod / dev / All 로 바꿀 때 6 패널 전부가 따라온다. 처리량 패널 y 축이 초당 요청 수다.

**전제**: US1·US2 가 dev 에 배포돼 있다(p95/p99·Tomcat 패널에 데이터가 있으려면). 나머지 4 패널은 배포 전에도 구성 가능.

### Implementation for User Story 3 (홈서버 Grafana — 저장소 밖)

> 2026-09-02: T012~T018 은 Grafana UI 클릭 대신 **import 가능한 JSON 을 직접 작성**(`docs/observability/grafana-app-dashboard.json`)하는 것으로 수행했다. 홈 Grafana 에 import 한 뒤 T019 로 검증한다. T020 은 export 가 아니라 이 JSON 자체다. T011·T019·T023 은 dev 배포·홈 Grafana 접근이 필요해 사용자 수행.

- [ ] T011 [US3] dev 배포 후 카디널리티 확인(quickstart §3): Grafana Explore 에서 `count({__name__="http_server_requests_seconds_bucket", env="dev"})` 와 `count by (uri) (...)`. 시계열 수와 uri 에 실제 id 값 미포함을 확인하고 수치를 research.md §3 에 기록. 범위 밖이면 prod 릴리스 보류 후 `slo` 전환 검토.
- [x] T012 [US3] 새 대시보드 "kbap api" 생성, 변수 `env`(Query · Label values · label `env` · metric `http_server_requests_seconds_count` · Include All · Sort asc) 추가.
- [x] T013 [P] [US3] 처리량 패널(Time series): `sum by (env, instance) (rate(http_server_requests_seconds_count{env=~"$env"}[1m]))`, 단위 req/s, 범례 `{{env}}-{{instance}}`.
- [x] T014 [P] [US3] p95/p99 패널(Time series): `histogram_quantile(0.95, sum by (env, le) (rate(http_server_requests_seconds_bucket{env=~"$env"}[5m])))` 와 0.99 두 쿼리, 단위 s, 범례 `{{env}} p95` / `{{env}} p99`. 데이터 없음은 null 로(0 으로 채우지 않음 — 스펙 Edge Case).
- [x] T015 [P] [US3] HikariCP 패널(Time series): `hikaricp_connections_active|idle|pending|max{env=~"$env"}` 4 쿼리 by instance.
- [x] T016 [P] [US3] GC 패널(Time series 2개 또는 1개 2축): `sum by (env, instance, action) (rate(jvm_gc_pause_seconds_count{env=~"$env"}[5m]))`(횟수/s) 와 `..._sum`(초/s).
- [x] T017 [P] [US3] Heap 패널(Time series): `sum by (env, instance) (jvm_memory_used_bytes{env=~"$env", area="heap"})` 와 `jvm_memory_max_bytes` 동일 집계, 단위 bytes.
- [x] T018 [P] [US3] Tomcat 스레드풀 패널(Time series): `tomcat_threads_busy_threads`·`tomcat_threads_current_threads`·`tomcat_threads_config_max_threads{env=~"$env"}` by instance, 범례 `{{env}}-{{instance}} busy|current|max`.
- [ ] T019 [US3] `env` 를 prod → dev → All 로 전환하며 6 패널 전부가 따라오는지, All 에서 처리량 패널에 dev·prod 선이 구분되는지 확인(스펙 US3 AC 1·2). 저장.

**Checkpoint**: 대시보드 동작. US4 로 기록.

---

## Phase 6: User Story 4 - 대시보드를 기록으로 남긴다 (Priority: P3)

**Goal**: 대시보드 정의 JSON 과 패널별 PromQL 의미가 저장소에 있고, 위키의 "실제로 없는 메트릭" 공백이 해소 기록된다.

**Independent Test**: JSON 을 새 Grafana 에 import 하면 변수·6 패널이 재현된다. md 의 임의 패널 항목에 질의 의미가 한 줄로 있다.

### Implementation for User Story 4

- [x] T020 [US4] Grafana Export → "Export for sharing externally"(datasource 입력 변수화) → `docs/observability/grafana-app-dashboard.json` 저장. 홈 Prometheus datasource uid 가 하드코딩되지 않았는지 확인.
- [x] T021 [P] [US4] `docs/observability/grafana-app-dashboard.md` 작성: 목적 1문단, import 절차, 변수 `env` 설명, 패널 6종 각각 "PromQL — 무엇을 어떻게 계산하는지" 한 줄(rate 의 창 1m 근거·`sum by (le)` 로 2대 합산 근거·HikariCP 지연 등록 주의 포함), 되돌리기(quickstart §5).
- [x] T022 [P] [US4] `../kbap-agenthub/wiki/observability-app-metrics-and-ecs-healthcheck.md` 의 "실제로 없는 메트릭" 절에 "KB-411(2026-09-xx) 로 해소 — 설정 위치·범위 5ms~10s·측정된 시계열 수" 를 덧붙이고 `INDEX.md` 한 줄 갱신, 허브에서 커밋.
- [ ] T023 [US4] 다른 Grafana(또는 같은 서버의 새 폴더)에 JSON 을 import 해 변수·6 패널 재현 확인(스펙 SC-005). 확인 후 임시 대시보드 삭제.

**Checkpoint**: 커밋 `docs(observability): kbap api Grafana 대시보드 정의·PromQL 설명 (KB-411)`.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T024 `git diff develop --stat` 로 변경 파일이 yml 1·docs 2·specs 뿐인지 확인.
- [ ] T025 draft PR(`open-draft-pr-to-develop`) 본문에 Jira KB-411·dev 카디널리티 측정치·prod 릴리스 전 확인 사항(T011) 기재.
- [ ] T026 Jira KB-411 DoD 체크: US1~US4 완료 항목 체크, "actuator 히스토그램 설정 추가" 문구가 실제와 맞게 유지되는지 확인.

---

## Dependencies & Execution Order

### Phase Dependencies

- Phase 1·2: 없음.
- **US1 (Phase 3)** → **US2 (Phase 4)**: 같은 yml·테스트 파일을 건드리므로 순차. 논리적 의존은 없지만 파일 충돌 회피.
- **US3 (Phase 5)**: p95/p99·Tomcat 패널은 US1·US2 의 dev 배포 뒤에만 데이터가 있다. 나머지 4 패널(T013·T015~T017)은 즉시 가능.
- **US4 (Phase 6)**: US3 완료 후.
- Polish: 전부 완료 후.

### Within Each User Story

- 테스트 먼저 → Red 확인 → yml 변경 → Green → 로컬 실노출 확인.

### Parallel Opportunities

- US3 의 패널 6개(T013~T018)는 서로 독립 — 한 번에 만들어도 된다.
- US4 의 T021·T022 는 T020 과 병렬.

---

## Parallel Example: User Story 3

```text
T012 (변수) 완료 후:
  T013 처리량 · T014 p95/p99 · T015 HikariCP · T016 GC · T017 Heap · T018 Tomcat — 동시 구성
```

---

## Implementation Strategy

### MVP First (US1 + US2 — 앱 측 변경)

1. T001~T010 을 한 세션에서 끝낸다(설정 2블록 + 테스트 1클래스).
2. draft PR → develop 머지 → dev 자동 배포.
3. T011 카디널리티 확인 → 문제없으면 prod 릴리스 PR 에 포함.

### Incremental Delivery

- 앱 변경(US1·US2) 은 대시보드 없이도 그 자체로 가치(지표 존재)가 있어 먼저 머지한다.
- 대시보드(US3) 와 기록(US4) 은 dev 데이터가 쌓인 뒤 진행하며 저장소 변경은 docs 뿐이라 별도 커밋으로 같은 PR 에 얹거나 후속 PR 로 낸다.

---

## Notes

- Kotlin 테스트 파일에 주석을 달지 않는다(2026-08-11 규율). 근거는 yml 주석·커밋 메시지·위키에.
- `@IntegrationTest` 외 다른 SpringBootTest 조합을 만들지 않는다(KB-392). `RANDOM_PORT` 금지.
- 배치 앱 설정은 건드리지 않는다.
- 작업 단위마다 커밋한다(US1·US2·US4 체크포인트).
