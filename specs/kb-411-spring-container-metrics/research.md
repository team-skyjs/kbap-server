# Research: 스프링 컨테이너 메트릭 개선

## 1. Tomcat 스레드풀 지표를 켜는 방법

- **Decision**: `server.tomcat.mbeanregistry.enabled: true` (api `application.yml` 베이스).
- **Rationale**: Boot 4.1.0 `spring-boot-tomcat` 의 `TomcatMetricsAutoConfiguration` 은 `TomcatMetricsBinder` 를 등록하지만, 바인더가 읽는 건 Tomcat JMX MBean 이라 MBean 레지스트리가 꺼져 있으면(기본값 false) `tomcat_*` 가 하나도 안 나온다. jar 메타데이터(`spring-configuration-metadata.json`)에서 프로퍼티 존재 확인. 켜면 `tomcat_threads_busy_threads`·`tomcat_threads_current_threads`·`tomcat_threads_config_max_threads`(+ sessions·global request 계열)가 나온다.
- **Alternatives considered**: (a) 커스텀 `MeterBinder` 로 Executor 를 직접 읽기 — 프레임워크가 제공하는 것을 재구현, 기각. (b) 스레드 덤프·CloudWatch — 시계열이 아님, 기각.

## 2. p95·p99 를 인스턴스 2대 합산으로 얻는 방법

- **Decision**: `management.metrics.distribution.percentiles-histogram.http.server.requests: true` + `minimum-expected-value: 5ms` + `maximum-expected-value: 10s`. Grafana 에서 `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{...}[5m])))`.
- **Rationale**: Micrometer Timer 기본 출력은 `_count`·`_sum`·`_max` 뿐. `percentiles`(앱 내 계산)는 인스턴스별 근사값이라 합산이 수학적으로 불가능하고, api 는 env 당 2대라 쓰면 틀린 값이 된다. 히스토그램 버킷은 `sum by (le)` 로 합산 가능. Boot 4.1.0 `spring-boot-micrometer-metrics` 의 `PropertiesMeterFilter` 가 이 프로퍼티를 적용하며 메터 이름은 접두 매칭(`http.server.requests` 정확 일치도 포함). YAML 은 평면 점 키(`http.server.requests: true`)와 중첩 키 모두 같은 결과 — 스칼라 값 Map 은 점을 보존한다.
- **Alternatives considered**: (a) `slo` 버킷 지정 — 임의 경계 몇 개만 내보내 카디널리티는 낮지만 p99 해상도가 거칠다. 1차는 percentiles-histogram 으로 시작하고 시계열이 부담되면 slo 로 전환. (b) `_max` 만 사용 — 2분 롤링 창 최대값이라 p99 대체 불가.

## 3. 카디널리티 추정과 방어

- **Decision**: 범위 5ms~10s 고정. dev 배포 후 `count({__name__="http_server_requests_seconds_bucket"})` 로 시계열 수 확인 → prod 릴리스.
- **Rationale**: Micrometer 기본 버킷은 범위 제한 없이 약 60~70개/조합. 5ms~10s 로 제한하면 약 40개 내외(정확 수치는 dev 에서 측정). 조합 수 = `uri × method × status × outcome × exception × instance`. 현재 api 엔드포인트 약 40개, 상태 3~4종, 인스턴스 2 → 대략 300 조합 × 40 버킷 ≈ 12k 시계열/env. 홈 Prometheus 단일 노드 기준 무리 없는 규모이나 measured 값으로 확정.
- **측정(2026-09-02 로컬 bootRun)**: 조합당 버킷 **51개**(`le=0.005` … `le=10.0` + `+Inf`). 요청 2건(`/api/app-version` 200, `/api/foods` 400)으로 버킷 행 102개. 클라이언트 백분위(`quantile=`) 행 0개 확인. 위 추정(40개)보다 약간 많으므로 시계열 추정은 조합 수 × 51 로 갱신.
- **Alternatives considered**: `management.metrics.web.server.max-uri-tags` 는 Boot 4 에 존재하지 않음(Boot 3 에서 제거). uri 폭증 방어는 프레임워크의 경로 템플릿 집계(`/api/foods/{id}`)와 404 의 `UNKNOWN`/`root` 집계에 의존.

## 4. MockMvc 통합 테스트로 무엇을 검증할 수 있는가

- **Decision**: 테스트 클래스 하나(`ActuatorMetricsExposureTest`, `@IntegrationTest`)에 두 시나리오. (1) `GET /api/app-version`(헤더 불필요) 한 번 → `GET /actuator/prometheus` 본문에 `http_server_requests_seconds_bucket{` 포함. (2) `TomcatServerProperties` 주입 → `mbeanregistry.enabled == true`.
- **Rationale**: `@AutoConfigureMockMvc` 는 필터 체인을 포함하므로 `ServerHttpObservationFilter` 가 MockMvc 요청도 관측하고 `http.server.requests` 타이머가 Prometheus 레지스트리에 등록된다 → 버킷 노출을 실제로 검증. Tomcat 바인더는 `WebServer` 시작 이벤트에 붙어 MOCK 환경에선 동작하지 않으므로 설정 바인딩만 본다. 새 컨텍스트(`RANDOM_PORT`)를 띄우면 MySQL 컨테이너·Flyway 가 한 번 더 돌아 KB-392 규율 위반 — 기각.
- **Red 확인 지점**: (1) 은 `_bucket` 이 없어 실패, (2) 는 기본값 false 라 실패. 둘 다 yml 두 블록 추가로 Green.
- **위험**: `TomcatServerProperties` 빈이 MOCK 컨텍스트에 없을 수 있음(`TomcatServletWebServerAutoConfiguration` 조건). 없으면 (2) 는 `Environment.getProperty("server.tomcat.mbeanregistry.enabled")` 로 대체 — 바인딩 대신 프로퍼티 존재 검증(가치는 낮지만 회귀 감지는 됨).

## 5. 대시보드 env 필터와 PromQL

- **Decision**: 변수 `env` = `label_values(http_server_requests_seconds_count, env)`, Include All. 모든 패널 셀렉터에 `env=~"$env"`. 범례에 `{{env}}-{{instance}}` 로 dev·prod 를 구분.
- **Rationale**: `env` 는 Alloy `external_labels` 가 모든 시계열에 붙이는 라벨(KB-381) — 앱 변경 0. 2026-09-02 Node Exporter 대시보드에 같은 방식 적용 완료. 사용자가 말한 "phase" 는 이 라벨의 이름이 `env` 다.
- **패널 PromQL 초안**(docs 에 확정본 기록):
  - req/sec: `sum by (env, instance) (rate(http_server_requests_seconds_count{env=~"$env"}[1m]))` — 15초 수집이라 창은 1m(≥ 수집 간격 ×2).
  - p95/p99: `histogram_quantile(0.95, sum by (env, le) (rate(http_server_requests_seconds_bucket{env=~"$env"}[5m])))` — 인스턴스 합산 후 백분위.
  - HikariCP: `hikaricp_connections_active|idle|pending|max{env=~"$env"}` (풀 생성 전엔 부재 — 첫 요청 후 등장).
  - GC: `sum by (env, instance, action, cause) (rate(jvm_gc_pause_seconds_count{env=~"$env"}[5m]))`·`..._sum` — 횟수·소요.
  - Heap: `sum by (env, instance) (jvm_memory_used_bytes{env=~"$env", area="heap"})` vs `jvm_memory_max_bytes`.
  - Tomcat: `tomcat_threads_busy_threads`·`tomcat_threads_current_threads`·`tomcat_threads_config_max_threads{env=~"$env"}` by instance.
- **Alternatives considered**: 앱이 `phase` 태그를 추가 — `env` 와 중복, 기각.

## 6. 문서 위치

- **Decision**: `docs/observability/grafana-app-dashboard.json`(Grafana export, "Export for sharing externally" 로 datasource 를 입력 변수화) + `grafana-app-dashboard.md`(변수·패널별 PromQL 한 줄 설명). 지식 위키 `observability-app-metrics-and-ecs-healthcheck.md` 의 "실제로 없는 메트릭" 절에 해소 기록.
- **Rationale**: 홈서버 설정 저장소가 따로 없고, 기존 운영 문서(`docs/performance/`)가 이 저장소에 있다. 위키는 코드로 알 수 없는 결정(2대 합산 이유·범위 근거)을 담는 곳.
