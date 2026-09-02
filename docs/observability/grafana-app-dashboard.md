# kbap api Grafana 대시보드 (KB-411)

홈서버 Grafana 에서 api 앱의 **컨테이너 안** 상태(처리량·지연 백분위·Tomcat 스레드풀·HikariCP·GC)를 한 장으로 보는 대시보드다. 정의는 [`grafana-app-dashboard.json`](grafana-app-dashboard.json) 에 있고, 홈 Grafana 가 사라져도 이 파일을 import 하면 그대로 복원된다. 컨테이너 밖(ALB·RDS·EC2)은 CloudWatch 데이터소스 쪽이고 이 문서의 범위가 아니다.

## Import

1. Grafana → Dashboards → New → **Import** → JSON 파일 업로드(또는 내용 붙여넣기).
2. `prometheus` 입력에 홈 Prometheus 데이터소스(이름 `prometheus`, `http://prometheus:9090`)를 고른다. 같은 URL 의 중복 데이터소스가 있으니 반드시 `prometheus` 로.
3. Import. uid 는 `kbap-api-app` 으로 고정돼 있어 재-import 하면 기존 것을 덮어쓴다.

## 앱 측 전제

`api/src/main/resources/application.yml` 의 두 설정이 켜져 있어야 p95/p99·Tomcat 패널에 데이터가 있다.

- `server.tomcat.mbeanregistry.enabled: true` — 없으면 `tomcat_threads_*` 자체가 안 나온다.
- `management.metrics.distribution.percentiles-histogram.http.server.requests: true` + 범위 5ms~10s — 없으면 `_bucket` 이 없어 `histogram_quantile` 이 빈 값이다.

## 변수

| 이름 | 질의 | 의미 |
|------|------|------|
| `env` | `label_values(http_server_requests_seconds_count, env)` | Alloy `external_labels` 가 모든 시계열에 붙이는 환경 라벨(`dev`·`prod`). All 이면 `.*` 로 풀려 두 환경이 함께 그려진다. 모든 패널이 `env=~"$env"` 로 이 변수를 따른다. |

## 패널별 PromQL

| 패널 | 질의 | 무엇을 어떻게 계산하나 |
|------|------|------------------------|
| HTTP 처리량 | `sum by (env, instance) (rate(http_server_requests_seconds_count{env=~"$env"}[1m]))` | 누적 요청 카운터의 1분 창 증가량을 창 길이로 나눈 초당 평균 요청 수. 창은 수집 간격(15초)의 2배 이상이어야 표본이 2개 이상 들어와 rate 가 성립한다. |
| HTTP 지연 p95/p99 | `histogram_quantile(0.95, sum by (env, le) (rate(http_server_requests_seconds_bucket{env=~"$env"}[5m])))` (0.99 동일) | 버킷별 증가율을 `le` 만 남기고 합산(= 인스턴스 2대 합산)한 뒤 백분위를 보간. 앱이 계산한 `quantile=` 값은 인스턴스 간 합산이 불가해 쓰지 않는다. 요청이 없는 구간은 값 없음이지 0 이 아니다. |
| Tomcat 스레드풀 | `tomcat_threads_busy_threads` · `_current_threads` · `_config_max_threads` `{env=~"$env"}` | 게이지 그대로. busy 가 max(점선)에 붙으면 앱 포화, 그 전이면 병목은 DB·외부 호출 쪽. |
| HikariCP 커넥션 풀 | `hikaricp_connections_active` · `_idle` · `_pending` · `_max` `{env=~"$env"}` | 게이지 그대로. pending 이 지속되면 풀 고갈. 풀은 첫 DB 접근 때 생성되므로 기동 직후엔 시계열이 없다. |
| GC pause | 왼쪽 축 `sum by (env, instance, action) (rate(jvm_gc_pause_seconds_count{env=~"$env"}[5m]))`, 오른쪽 축 `rate(..._sum[5m])` | 초당 pause 횟수와 초당 pause 에 쓴 시간. 시간 축이 1 에 가까우면 GC 가 CPU 를 다 먹는 상태. `action` 은 minor/major 구분. |

JVM 힙은 기존 JVM 대시보드에서 이미 보고 있어 이 대시보드에는 두지 않는다(2026-09-02 결정).

## 카디널리티 확인 (prod 반영 전)

히스토그램은 `uri × method × status × outcome × exception × instance` 조합마다 버킷 51개를 만든다. dev 배포 후 Explore 에서:

```promql
count({__name__="http_server_requests_seconds_bucket", env="dev"})
count by (uri) ({__name__="http_server_requests_seconds_bucket", env="dev"})
```

`uri` 에 `/api/foods/123` 처럼 실제 id 가 섞이면 경로 템플릿 집계가 깨진 것이니 즉시 원인을 찾는다. 시계열 수가 부담이면 `percentiles-histogram` 대신 `management.metrics.distribution.slo.http.server.requests` 로 경계 몇 개만 내보내는 방식으로 전환한다.

## 되돌리기

`application.yml` 의 두 블록을 지우고 배포하면 노출이 멈춘다. 이미 쌓인 시계열은 Prometheus 보존 기간이 지나면 사라진다. 대시보드는 그대로 두면 p95/p99·Tomcat 패널만 빈다.
