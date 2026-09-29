# Research: 버추얼 스레드 전환 — 조사 결과와 결정

## 조사 결과

| 사실 | 출처 | 영향 |
|------|------|------|
| dev RDS `kbap-db-devstg` 는 db.t4g.micro, 기본 파라미터 그룹 → `max_connections` **60**. 조회 시점 연결 33, 역대 최대 **62**(직전 부하에서 한도 초과). | 바스티온 터널로 `SHOW VARIABLES` 실측 (2026-09-30) | 풀 확대 여지가 인스턴스당 ~20. 사용자 결정으로 **이번엔 풀을 손대지 않는다** |
| 이 RDS 소비자: dev api 2대(풀 10×2) + dev batch 1대(풀 10). staging ECS 클러스터는 존재하지 않는다. | `aws ecs describe-services` | 현재 앱 연결 30 + 툴링 |
| prod RDS `kbap-db-prod` 도 db.t4g.micro, 60. | `aws rds describe-db-instances` | prod 도 같은 벽. 이번 작업은 dev 만 |
| Hikari 설정은 어느 yml 에도 없다(기본 풀 10, 대기 30초). Tomcat 스레드 설정도 없다(기본 200). | grep | 전환 전 기준선 |
| `ops/jfr/kbap-profile.jfc` 가 `jdk.VirtualThreadStart/End/Pinned/SubmitFailed` 를 켜 둔다. | jfc 66~81행 | 핀닝 확인은 JVM 플래그(`JAVA_TOOL_OPTIONS`, Terraform 소유) 없이 기존 k6+JFR 캠페인으로 가능 |
| 그라파나 앱 대시보드(`docs/observability/grafana-app-dashboard.json`)는 패널 6개(id 1~7), Tomcat 스레드풀(id 3, y=8 x=0 w=12)·Hikari(y=8 x=12)·GC(y=16 w=24). 활성 요청 패널 없음. | JSON 파싱 | 패널 1개 추가 + Tomcat 패널 설명 갱신 |
| Boot 는 `spring.threads.virtual.enabled=true` 면 Tomcat 프로토콜 핸들러 executor 를 버추얼 스레드 executor 로 바꾼다. 요청 스레드 이름이 `http-nio-8080-exec-N` 에서 **`tomcat-handler-N`** 으로 바뀐다. | Spring Boot `TomcatVirtualThreadsWebServerFactoryCustomizer` | 로컬 실노출 판정 기준: 로그의 `process.thread.name` |
| Micrometer 는 HTTP 서버 관측에 LongTaskTimer 를 붙여 `http_server_requests_active_seconds_gcount`(처리 중 요청 수)를 프로메테우스로 낸다. | Micrometer observation → `DefaultMeterObservationHandler` | 새 지표 불필요. 로컬 `/actuator/prometheus` 로 이름 확정 |

## D1. dev 프로필에만 버추얼 스레드를 켠다 — 풀은 그대로

- **Decision**: `api/src/main/resources/application-dev.yml` 에 `spring.threads.virtual.enabled: true` 를 추가한다. Hikari·Tomcat 설정은 바꾸지 않는다. prod·staging·local 무변경.
- **Rationale**: 사용자 결정("버추얼 스레드 변경만 우선, dev 만 먼저"). RDS 60 한도 때문에 풀 확대 이득이 작고, 풀·RDS 변경은 별도 판단으로 넘긴다.
- **예상 관측(기록해 둠)**: 스레드 상한이 사라지면 2천 요청이 곧바로 커넥션 10개 앞에 줄을 선다. Tomcat 큐에는 타임아웃이 없었지만 Hikari 대기는 30초에 끊기므로 **커넥션 대기 초과 500 이 이전(1건)보다 늘 수 있다**. 이는 실패가 아니라 병목이 DB 로 옮겨갔다는 측정 결과이며, 다음 작업(풀·RDS) 의 근거가 된다. spec SC-003(대기 초과 0건)은 이 결정으로 **달성 대상이 아니라 관측 대상**으로 격하한다.
- **Alternatives considered**: 베이스 yml 에 켜서 전 프로필 적용 — 사용자가 dev 만 먼저를 택함. 기각.

## D2. 핀닝 확인은 기존 JFR 캠페인으로

- **Decision**: `-Djdk.tracePinnedThreads` 를 넣지 않는다. dev 배포 후 `scripts/perf/run-endpoint.sh home-auth …`(JFR_ENABLED=true)로 캠페인을 돌리고 JFR 파일에서 `jdk.VirtualThreadPinned` 이벤트 수·스택을 센다(`jfr print --events jdk.VirtualThreadPinned <file>.jfr`).
- **Rationale**: JVM 플래그는 Terraform 의 태스크 정의 소유라 코드 PR 로 못 바꾼다. JFR 이벤트가 같은 정보를 더 정확히 준다(스택 포함).
- **Alternatives considered**: 로컬에서 `-Djdk.tracePinnedThreads=full` 로 bootRun — 부하 없이는 핀닝이 안 나므로 보조 수단으로만.

## D3. 대시보드 — 패널 추가·Tomcat 패널 설명 갱신, 제거하지 않음

- **Decision**: 패널 id 8 "처리 중 HTTP 요청 수" 를 y=16 w=24 로 추가하고 GC 패널(id 5)을 y=24 로 내린다. 질의는 `http_server_requests_active_seconds_gcount{env=~"$env"}` (이름은 로컬 실노출로 확정 후 반영). Tomcat 패널은 남기되 제목을 "Tomcat 스레드풀 (플랫폼 스레드 모드)" 로 바꾸고 description 에 "버추얼 스레드 모드(dev)에서는 풀이 없어 무의미, 처리 중 요청 수 패널을 볼 것" 을 적는다. 문서 `grafana-app-dashboard.md` 의 패널 표에 행을 추가하고 Tomcat 행에 같은 주석을 단다.
- **Rationale**: prod 는 여전히 플랫폼 스레드라 Tomcat 패널이 유효하다. 한 대시보드가 두 모드를 보여야 하므로 제거 대신 설명이다.
- **Alternatives considered**: 커스텀 게이지로 "활성 버추얼 스레드 수" 노출 — 처리 중 요청 수와 같은 값을 두 번 내는 것. 기각(spec Assumptions).

## D4. 테스트 — 새로 만들지 않음

- **Decision**: 설정 토글 + 대시보드 JSON 이라 자동 테스트를 추가하지 않는다. 검증은 (1) 기존 스위트 그린, (2) 로컬 dev 프로필 bootRun 에서 요청 로그의 스레드 이름이 `tomcat-handler-` 로 시작하고 `/actuator/prometheus` 에 active 지표가 있는지, (3) dev 배포 후 사용자의 k6 재측정 + JFR.
- **Rationale**: KB-380·KB-411·KB-652 와 같은 원칙(프레임워크 토글은 실노출 확인).

## D5. 범위 밖으로 남기는 것

- Hikari 풀 확대·RDS 증설(`db.t4g.small` → max_connections ~170)·prod 적용. 이번 측정 결과가 근거가 된다.
- Tomcat `max-connections`(8,192)·ALB 제한. 1만 동접 목표에는 결국 이쪽도 봐야 한다.
