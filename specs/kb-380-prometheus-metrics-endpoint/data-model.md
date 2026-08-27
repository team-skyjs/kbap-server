# Data Model: 앱 내부 메트릭 노출 (KB-380)

이 기능은 **영속 데이터를 만들지 않는다.** 엔티티·마이그레이션·리포지토리 변경 없음.

## 런타임 모델 (비영속)

| 개념 | 정체 | 소유 | 생명주기 |
|---|---|---|---|
| 메트릭 스냅샷 | `/actuator/prometheus` 응답 — 읽는 순간의 미터 값 텍스트 | `PrometheusMeterRegistry`(Boot 자동구성) | 프로세스 메모리, 재시작 시 초기화. 누적·보존은 수집기(KB-381) 몫 |
| 공통 태그 `application` | 모든 미터에 붙는 `application=kbap-api` / `kbap-batch` | `management.metrics.tags` | 설정값, 불변 |
| 컨테이너 헬스 | ECS 가 `/actuator/health/readiness` 연속 실패 횟수로 판정하는 HEALTHY/UNHEALTHY | ECS 태스크 정의 `healthCheck` | 태스크 수명 |

## 필수 메트릭군 (FR-001) → 실제 미터 이름

| 요구 | Prometheus 이름(접두) | 공급자 |
|---|---|---|
| JVM 메모리 힙/논힙 | `jvm_memory_used_bytes{area=heap\|nonheap}` · `jvm_memory_max_bytes` | Micrometer JVM 바인더(자동) |
| GC | `jvm_gc_pause_seconds_*` · `jvm_gc_memory_allocated_bytes_total` | 〃 |
| 스레드 | `jvm_threads_live_threads` · `jvm_threads_states_threads` | 〃 |
| DB 커넥션 풀 | `hikaricp_connections_active` · `_pending` · `_max` | Boot `DataSourcePoolMetricsAutoConfiguration` |
| HTTP 요청 | `http_server_requests_seconds_count\|_sum\|_max{uri,method,status,outcome}` | Spring MVC 관측(자동) |
| 배치 잡·스텝 (batch 만) | `spring_batch_job_seconds_*{spring_batch_job_name,spring_batch_job_status}` · `spring_batch_step_seconds_*{spring_batch_step_name,spring_batch_step_status}` | Spring Batch 6 관측 + `DefaultMeterObservationHandler` (R-6) |
