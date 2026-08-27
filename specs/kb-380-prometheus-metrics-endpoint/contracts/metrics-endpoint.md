# Contract: 앱 메트릭·헬스 제공 지점 (KB-380)

소비자: 같은 호스트의 수집기(KB-381 Grafana Alloy — `discovery.docker`, 컨테이너 이름/이미지로 api·batch 를 고르고 컨테이너 포트 8080 을 긁는다), ALB 타깃그룹 헬스체크(api), ECS 컨테이너 헬스체크(api·batch).

## 포트

api·batch 모두 **서비스 포트 8080 하나**. 태스크 정의 `portMappings` 변경 없음. 앱 측 접근 제어 없음(공개 차단은 ALB 규칙 후속 — research R-3).

## 엔드포인트 (api·batch 공통)

| 메서드·경로 | 응답 | 비고 |
|---|---|---|
| `GET /actuator/prometheus` | `200 text/plain; version=0.0.4` (Prometheus 텍스트 노출 형식) | 모든 미터에 `application="kbap-api"` / `"kbap-batch"` |
| `GET /actuator/health` | `200 {"status":"UP"}` | 상세 없음(`show-details` 기본 never) |
| `GET /actuator/health/readiness` · `/liveness` | `200` / `503` | api: ALB·ECS 헬스체크 경로(무변경). batch: 신설 ECS 헬스체크 경로 |
| `GET /actuator/**` (env·configprops·threaddump 등) | `404` | 노출 목록 `health,prometheus` 외 금지 |

## 필수 메트릭 패밀리

api: `jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `jvm_threads_live_threads`, `hikaricp_connections_active`, `http_server_requests_seconds`.
batch: 위 전부(`http_server_requests` 는 트리거 API 호출 시) + `spring_batch_job_seconds`, `spring_batch_step_seconds`.

## 인프라 측 계약 (terraform)

- `batch.tf` 컨테이너 `healthCheck`: `["CMD-SHELL", "curl -sf http://localhost:8080/actuator/health/readiness || exit 1"]`, `interval 15 / timeout 5 / retries 3 / startPeriod 150` (신설).
- `api.tf`·`alb.tf`·`sg.tf`: 변경 없음.
