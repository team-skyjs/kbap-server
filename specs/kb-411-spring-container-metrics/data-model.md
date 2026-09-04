# Data Model: 노출 지표 패밀리

앱 영속 모델 변경은 없다. 이 기능의 "데이터" 는 관측 엔드포인트가 노출하는 시계열 패밀리다.

## 신규 노출 (이 기능이 켜는 것)

| 패밀리 | 타입 | 주요 라벨 | 의미 |
|--------|------|-----------|------|
| `http_server_requests_seconds_bucket` | 누적 히스토그램 | `le`, `uri`, `method`, `status`, `outcome`, `exception`, `application`, +수집기 라벨(`env`, `instance`, `version`, `host`) | 지연 ≤ `le` 초인 요청의 누적 수. `le="+Inf"` 는 `_count` 와 같다. 버킷 경계는 5ms~10s 범위의 Micrometer 기본 격자. |
| `tomcat_threads_busy_threads` | 게이지 | `name`(커넥터), `application`, +수집기 라벨 | 요청 처리 중인 스레드 수 |
| `tomcat_threads_current_threads` | 게이지 | 위와 같음 | 현재 생성된 스레드 수 |
| `tomcat_threads_config_max_threads` | 게이지 | 위와 같음 | 스레드 상한(기본 200) |

## 기존 노출 (대시보드가 함께 쓰는 것, 변경 없음)

| 패밀리 | 타입 | 용도 |
|--------|------|------|
| `http_server_requests_seconds_count` / `_sum` / `_max` | 카운터 / 카운터 / 게이지 | req/sec(`rate(_count)`), 평균(`rate(_sum)/rate(_count)`) |
| `hikaricp_connections_active` / `_idle` / `_pending` / `_max` | 게이지 | DB 풀 상태. 풀 생성(첫 DB 접근) 전엔 부재 |
| `jvm_gc_pause_seconds_count` / `_sum` / `_max` | 카운터 / 카운터 / 게이지 | GC 횟수·소요. 라벨 `action`, `cause`, `gc` |
| `jvm_memory_used_bytes` / `jvm_memory_max_bytes` | 게이지 | 힙(`area="heap"`) 영역별(`id`) 사용량 |

## 라벨 규약 (KB-381, 변경 없음)

- `application`: 앱이 붙임(`kbap-api`). `service` 중복 금지.
- `env`: Alloy external_labels — `dev` / `prod`. 대시보드 필터 키.
- `instance`: `<env>-<container>-<task id 6자>`. 태스크 단위.
- `version`: ECS 태스크 정의 리비전 — 배포 버전 비교용.
- `host`: EC2 호스트명.

## 검증 규칙

- 버킷 범위: `minimum-expected-value=5ms`, `maximum-expected-value=10s`. 범위 밖 지연은 양끝 버킷에 누적된다(`+Inf` 포함).
- 대상 메터: `http.server.requests` 하나. 다른 타이머(예: `spring.batch.*`, `jdbc.*`)에는 히스토그램을 켜지 않는다.
