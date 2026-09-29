# Data Model: 버추얼 스레드 전환

**스키마·엔티티·코드 변경 없음.** 프로필 설정 1줄과 대시보드 정의·문서만 바뀐다.

## 설정 변화

| 앱 | 프로필 | `spring.threads.virtual.enabled` | Hikari 풀 | Tomcat 스레드 |
|----|--------|-----------------------------------|-----------|----------------|
| api | dev | (없음=false) → **true** | 10 (불변) | 200 → 상한 없음(풀 미사용) |
| api | local·staging·prod | 불변 | 10 | 200 |
| batch | 전부 | 불변 | 10 | 해당 없음 |

## 관측 지표 (프로메테우스 이름)

| 지표 | 뜻 | 버추얼 스레드 모드에서 |
|------|-----|------------------------|
| `http_server_requests_active_seconds_gcount` | 처리 중 HTTP 요청 수 | **주 포화 지표**. 활성 요청 스레드 수와 동치 |
| `tomcat_threads_busy_threads` / `_current` / `_config_max` | Tomcat 풀 사용량 | 풀이 없어 무의미 — 세 게이지 모두 **-1** (로컬 실측) |
| `tomcat_connections_current` / `_config_max` | 열린 연결 수 / 상한 8,192 | 유효. 다음 천장 |
| `hikaricp_connections_pending` | DB 커넥션 대기 수 | 유효. **이번 전환 후 새 병목 지표** |
| JFR `jdk.VirtualThreadPinned` | 핀닝 이벤트(스택 포함) | 캠페인 JFR 에서 센다 |

## 대시보드 정의 변화 (`docs/observability/grafana-app-dashboard.json`)

| 패널 id | 제목 | 변경 |
|---------|------|------|
| 3 | Tomcat 스레드풀 → Tomcat 스레드풀 (플랫폼 스레드 모드) | 제목·description 갱신 |
| 5 | GC pause | gridPos y 16 → 24 |
| **8 (신규)** | 처리 중 HTTP 요청 수 | y=16, x=0, w=24, h=8. 질의 `sum by (env, instance) (http_server_requests_active_seconds_gcount{env=~"$env"})` legend `{{env}}-{{instance}}` |

## 검증 규칙

- 로컬 dev 프로필 요청 로그의 `process.thread.name` 이 `tomcat-handler-` 로 시작한다(전환 전 `http-nio-8080-exec-`).
- `/actuator/prometheus` 에 `http_server_requests_active_seconds_gcount` 가 있다(이름이 다르면 대시보드 질의를 그 이름으로 맞춘다).
- local·staging·prod yml 과 batch yml 은 diff 0.
