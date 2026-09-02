# Contract: `/actuator/prometheus` 노출 보장 (api)

관측 엔드포인트가 이 기능 이후 **추가로 보장**하는 항목. 경로·노출 범위(`health,prometheus`)·접근 방식은 KB-380 그대로다.

## 보장 1 — HTTP 지연 히스토그램

- **조건**: 앱 기동 후 HTTP 요청이 1건 이상 처리됨.
- **보장**: 응답 본문에 `http_server_requests_seconds_bucket{...,le="<경계>",...}` 행이 존재한다. `le` 경계는 5ms~10s 범위와 `+Inf` 를 포함한다.
- **소비자**: Grafana `histogram_quantile(q, sum by (le) (rate(..._bucket[5m])))`.
- **검증**: `ActuatorMetricsExposureTest` — MockMvc 로 `GET /api/app-version` 후 `GET /actuator/prometheus` 본문에 `http_server_requests_seconds_bucket{` 포함.

## 보장 2 — Tomcat 스레드풀

- **조건**: 실제 임베디드 Tomcat 으로 기동(bootRun·컨테이너).
- **보장**: `tomcat_threads_busy_threads`·`tomcat_threads_current_threads`·`tomcat_threads_config_max_threads` 가 존재한다.
- **소비자**: Grafana 스레드풀 패널.
- **검증**: 설정 바인딩은 `ActuatorMetricsExposureTest`(`TomcatServerProperties.mbeanregistry.enabled == true`). 실노출은 dev 배포 후 `curl <api>/actuator/prometheus | grep tomcat_threads` 육안 확인(quickstart).

## 비보장 (명시)

- 앱 내 계산 백분위(`http_server_requests_seconds{quantile="0.95"}`)는 **노출하지 않는다**. 인스턴스 간 합산 불가.
- 배치 앱(`kbap-batch`)에는 두 보장이 적용되지 않는다.
- `http.server.requests` 외 타이머에는 히스토그램을 켜지 않는다.
