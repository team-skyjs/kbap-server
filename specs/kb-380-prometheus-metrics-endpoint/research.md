# Research: 앱 내부 메트릭 노출 (KB-380)

## R-1. 메트릭 레지스트리 — `micrometer-registry-prometheus`

- **Decision**: `io.micrometer:micrometer-registry-prometheus`(버전은 Boot 4.1 BOM — Prometheus Java client 1.5.1 계열) 를 api·batch 에 `runtimeOnly` 로 추가. 좌표는 `gradle/libs.versions.toml` 에 `micrometer-registry-prometheus` 로 등록. batch 에는 `spring-boot-starter-actuator` 도 추가.
- **Rationale**: Boot 4.1 `spring-boot-micrometer-metrics` 자동구성이 클래스패스의 Prometheus 레지스트리를 감지해 `/actuator/prometheus` 와 `PrometheusMeterRegistry` 를 올린다(`management.prometheus.metrics.export.enabled` 기본 true). 추가 코드 0.
- **Alternatives considered**: `micrometer-registry-prometheus-simpleclient`(구 0.16 계열) — 레거시. OTLP push — 접근안 1 로 기각(KB-381 이 Alloy remote_write).

## R-2. 관리 포트·경로 — 8080 · `/actuator` 유지

- **Decision**: `management.server.port` 를 두지 않는다(서비스 포트 8080 공유). `management.endpoints.web.exposure.include: health,prometheus`. 노출 엔드포인트 외(env·configprops·threaddump…)는 기본대로 미노출.
- **Rationale**: 포트 분리는 ALB 헬스체크(`/actuator/health/readiness`, 8080)를 깨뜨려 `/readyz` 추가 노출 + TG 경로 변경 + 3단계 롤아웃(과도기 matcher)을 끌고 들어온다. 공개 차단이라는 목적은 R-3 의 필터로 달성되며, 그러면 태스크 정의 포트·TG·롤아웃 순서가 전부 사라진다. batch 는 ALB 뒤에 없어 애초에 분리 이유가 없다.
- **Alternatives considered**: `management.server.port=8081` 분리(표준적이나 위 비용), 관리 base-path 변경(보안 아님).

## R-3. 공개 차단 — 이 기능에서 하지 않음 (ALB 규칙 후속)

- **Decision**: 앱 측 접근 제어(필터·Security·포트 분리) 없음. api 가 아닌 경로의 외부 접근은 사용자가 ALB 리스너 규칙으로 후속 차단한다(2026-08-27 결정).
- **Rationale**: 관측은 이미 한 계층(ALB)에서 경계를 그을 계획이 있는데 앱에도 같은 경계를 두면 두 곳이 어긋날 때 어느 쪽이 진실인지 모호해진다. 이 기능은 노출만 담당한다.
- **후속 작업 메모**: ALB 는 raw 경로를 매칭하고 Tomcat 은 정규화(`//`→`/`)·디코딩(`%61`→`a`) 후 라우팅한다. 차단 목록(`/actuator/*` → 404)은 `//actuator/…`·`/%61ctuator/…` 를 놓친다. **허용 목록**(`/api/*` 만 forward, 그 외 기본 액션 404)으로 만들면 정규화 문제와 무관하게 안전하다. 그때 ALB 헬스체크는 리스너 규칙을 거치지 않고 타깃에 직접 가므로 `/actuator/health/readiness` 경로는 계속 통과한다.
- **Alternatives considered**: XFF 헤더 기반 앱 필터(ALB 는 XFF 를 항상 덧붙이고 SG 인바운드가 ALB 뿐이라 판정 가능) — 동작하지만 ALB 규칙과 중복. `management.server.port=8081` 분리 — ALB 헬스체크 경로 이동·3단계 롤아웃 비용. 둘 다 기각.

## R-4. batch 컨테이너 헬스체크

- **Decision**: `batch.tf` 컨테이너 정의에 api 와 동일 패턴 — `["CMD-SHELL", "curl -sf http://localhost:8080/actuator/health/readiness || exit 1"]`, `interval 15 / timeout 5 / retries 3 / startPeriod 150`. api 헬스체크는 무변경.
- **Rationale**: 두 앱 다 `eclipse-temurin:21-jre`(curl 포함 — api 헬스체크가 운영 중 동작하는 것이 증거). batch 는 actuator 가 새로 생기므로 readiness 경로가 이번 배포부터 존재한다. ECS 서비스는 essential 컨테이너가 UNHEALTHY 가 되면 태스크를 교체한다.

## R-5. terraform 리비전 반영 — `ignore_changes=[container_definitions]`

- **Decision**: `terraform apply -replace=module.ecs_environment.aws_ecs_task_definition.batch`. 서비스는 `ignore_changes=[task_definition]` 이라 terraform 이 배포를 일으키지 않고, 다음 CI 배포(`deploy-batch.sh` 의 `describe-task-definition FAMILY` = 최신 ACTIVE 리비전 복제 + 이미지 교체)가 헬스체크를 승계한다. api 태스크 정의는 건드리지 않는다.
- **Rationale**: `ignore_changes` 는 config 변경도 무시하므로 일반 apply 로는 리비전이 안 만들어진다.
- **순서**: terraform `-replace` 는 리비전을 등록만 하고 서비스에 붙이지 않으므로 **머지 전에 먼저** 한다 → 머지 후 CI 가 그 리비전을 복제해 actuator 포함 이미지로 배포하면 한 번에 끝(재배포 불필요). 그 사이 구 이미지로 CI 가 돌면 헬스체크 실패로 서킷브레이커 롤백 — 안전하지만 막히므로 창을 짧게.

## R-6. Spring Batch 6 잡 메트릭이 나오는 조건

- **Decision**: batch 에 actuator + prometheus 레지스트리를 추가하면 Boot 4.1 `BatchObservationAutoConfiguration`(조건: `ObservationRegistry` 빈 존재)이 `BatchObservabilityBeanPostProcessor` 를 등록하고, 이 BPP 가 `AbstractJob`·`AbstractStep`·`TaskExecutorJobOperator` 에 ObservationRegistry 를 주입한다. Boot 의 `DefaultMeterObservationHandler` 가 관측을 `spring.batch.job`·`spring.batch.step` 타이머로 바꿔 `spring_batch_job_seconds_*` 로 나온다. **코드 변경 없이 동작하는지 테스트가 판정**하고, NOOP 로 남으면 `BatchJdbcJobRepositoryConfig`(`DefaultBatchConfiguration` 상속)에 `getObservationRegistry()` 를 Boot 빈으로 override 한다.
- **Rationale**: `AbstractJob` 바이트코드에 "No ObservationRegistry has been set, defaulting to ObservationRegistry NOOP" — 주입이 안 되면 잡 메트릭이 조용히 빠진다. `DefaultBatchConfiguration` 직접 상속 구조에서 Boot `BatchAutoConfiguration` 본체는 백오프하지만 관측 자동구성은 별도 클래스라 살아 있다.
- **Alternatives considered**: `JobBuilder(...).observationRegistry(...)` 잡마다 명시 — BPP 가 되면 불필요.
- **판정 결과(2026-08-27, 로컬 bootRun)**: 코드 변경 없이 동작 — `foodContentOutboxPublishJob` 1회 완료 후 `spring_batch_job_seconds_count{spring_batch_job_name="foodContentOutboxPublishJob",spring_batch_job_status="COMPLETED"} 1`·`spring_batch_step_seconds_count{spring_batch_step_name="foodContentOutboxPublishStep",…} 1` 확인. `getObservationRegistry()` override 불필요(T011 스킵). 레이블은 `name`/`status` 가 아니라 `spring_batch_job_name`·`spring_batch_job_status`(스텝은 `spring_batch_step_*`) — 대시보드·알림 쿼리에서 이 이름을 쓴다.

## R-7. 검증 전략 — 자동화 테스트 없음

- **Decision**: 테스트 코드를 작성하지 않는다(사용자 지시). 검증은 (1) 로컬 `bootRun` + curl(quickstart "로컬 검증"), (2) dev 배포 후 컨테이너 안 curl·ECS `healthStatus`, 두 단계.
- **Rationale**: 변경이 프레임워크 기본 기능의 설정 노출(의존성 + yml)이라 앱 로직이 없다. R-6(Batch 잡 메트릭 주입 여부)만 판정이 필요한데, 이는 로컬에서 잡 1회 트리거 후 `grep spring_batch_job_seconds` 로 즉시 확인된다.
- **대가**: 회귀(누군가 `exposure.include` 를 지우거나 레지스트리를 빼는 경우)를 빌드가 잡지 못한다 — KB-381 이후 Grafana 대시보드 공백·`up` 알림이 사실상의 회귀 검출이 된다.

## R-8. 앱 식별 태그

- **Decision**: `management.metrics.tags.application: ${spring.application.name}`(Boot 4.1 메타데이터에 유효, deprecated 아님). api `kbap-api`, batch `kbap-batch`.
- **Rationale**: 이름을 두 곳에 적지 않는다. env(dev/prod) 는 KB-381 Alloy `external_labels` 몫.
