# k6 + JFR 성능 캠페인 런북

이 런북은 개발 환경의 localhost 대시보드에서 endpoint별 k6 부하와 두 ECS task JFR을 한 campaign으로 수집하는 절차다. 대시보드는 `127.0.0.1`에만 bind되고 브라우저에는 token 입력이나 S3 URL이 없다.

## 1. 사전 준비와 실행

저장소 루트에서 다음 명령을 그대로 실행한다.

```bash
export ACCESS_TOKEN="$(python3 k6/mint-token.py 35 2)"
cp k6/fixtures/dev.example.json k6/fixtures/dev.json
scripts/perf/dashboard.sh
```

필수 도구는 Python 3, k6, AWS CLI, jq다. `k6/fixtures/dev.json`은 실제 개발 fixture ID를 확인한 뒤 로컬에서만 관리한다. Dashboard launcher는 `ACCESS_TOKEN` 값을 출력하지 않지만 같은 shell의 자식 runner에는 전달한다.

브라우저에서 `http://127.0.0.1:8765`를 연다. 외부 hostname이나 reverse proxy로 노출하지 않는다.

## 2. 권장 실행 순서

1. `app-version` 같은 읽기 target 하나만 남기고 `smoke 검증`, 부하 1, 반복 1로 실행한다.
2. 상태가 `PASSED`이고 `report.html`, `summary.json`, `manifest.json`, task JFR 두 개가 모두 준비됐는지 확인한다.
3. `안전 대상 전체 실행`으로 default-enabled safe target을 smoke profile에서 직렬 실행한다.
4. read 또는 write 부하가 필요하면 같은 profile의 target만 선택한다. read는 요청률과 지속 시간, write는 VU와 지속 시간을 사용한다.
5. fixture 또는 cost target은 검색과 risk 필터로 좁힌 뒤 경고의 target 수와 예상 최대 호출 수를 확인한다. 위험 확인 checkbox를 직접 선택한 뒤 실행한다.
6. 외부 비용 target은 가장 작은 반복 수로 시작한다. 외부 provider의 실제 청구와 rate limit은 대시보드 상한보다 우선한다.
7. 취소가 필요하면 `캠페인 취소`를 한 번만 누른다. 상태가 `CANCELLING`인 동안 runner가 cleanup과 JFR stop/download를 마칠 때까지 기다린다.
8. 종료 후 `전체 artifact ZIP`을 받고, target별 HTML, JSON, manifest, 두 JFR 링크가 모두 있는지 확인한다. `부분 수집`은 성공으로 간주하지 않는다.

실행 중 browser가 SSE를 잃으면 snapshot을 먼저 조회하고 최대 10초 간격으로 다시 연결한다. 이때 새 campaign을 시작하지 말고 live panel과 서버 terminal을 함께 확인한다.

## 3. 프로파일과 상한

| Profile | 첫 번째 값 | 두 번째 값 | 서버 상한 | 용도 |
|---|---|---|---|---|
| `smoke` | 1 | 반복 1 | 1 / 1 | 계약과 연결 검증 |
| `read` | 초당 요청 수 | `30s`, `1m` 같은 지속 시간 | 40 / 300초 | 읽기 처리량 |
| `write` | VU | `30s`, `1m` 같은 지속 시간 | 10 / 120초 | 제한된 쓰기 부하 |
| `external` | VU | 반복 횟수 | 10 / 10회 | 외부 비용 endpoint |

`smoke` 외 profile은 target의 `defaultProfile`과 일치해야 한다. fixture와 cost는 `allowRisk=true` 승인이 필요하다. JFR 해제는 단일 target smoke에만 허용된다.

## 4. CLI fallback

Dashboard를 사용할 수 없을 때도 같은 campaign의 모든 target은 같은 `CAMPAIGN_ID`를 공유해야 한다.

```bash
export CAMPAIGN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
export JFR_ENABLED=true

scripts/perf/run-endpoint.sh app-version read 5 1m
scripts/perf/run-endpoint.sh home-auth read 5 1m
```

새 shell이나 target마다 `CAMPAIGN_ID`를 다시 만들지 않는다. 출력은 `artifacts/performance/$CAMPAIGN_ID/$TARGET/`에 모인다. Runner 인자는 항상 다음 순서다.

```text
scripts/perf/run-endpoint.sh TARGET PROFILE RATE_OR_VUS DURATION_OR_ITERATIONS
```

## 5. Prometheus 상관 분석

campaign 시작과 종료 시각을 Grafana 범위로 맞추고 target route 및 service label을 실제 환경 값으로 바꾼다. Micrometer metric 이름은 배포 설정에 따라 suffix나 label이 다를 수 있으므로 Grafana autocomplete에서 실제 series를 먼저 확인한다.

HTTP p95:

```promql
histogram_quantile(
  0.95,
  sum by (le) (
    rate(http_server_requests_seconds_bucket{uri="/api/foods"}[5m])
  )
)
```

HTTP p99:

```promql
histogram_quantile(
  0.99,
  sum by (le) (
    rate(http_server_requests_seconds_bucket{uri="/api/foods"}[5m])
  )
)
```

Tomcat busy thread와 max thread 비율:

```promql
max(tomcat_threads_busy_threads) by (instance)
/
max(tomcat_threads_config_max_threads) by (instance)
```

Hikari pending connection:

```promql
max(hikaricp_connections_pending) by (instance, pool)
```

p95/p99 증가와 함께 Tomcat busy 비율이 접근하면 요청 처리 포화 가능성을 확인한다. Hikari pending이 0보다 오래 유지되면 DB pool 대기와 쿼리 지연을 JFR thread 및 socket/SQL stack과 함께 본다.

## 6. JFR 분석과 전달

각 target의 `manifest.json`에서 campaign ID, target, task ID를 먼저 확인한다. JMC 또는 Claude에 분석을 의뢰할 때는 다음 세 종류를 한 묶음으로 전달한다.

- 첫 번째 task의 원본 `task-*.jfr`
- 두 번째 task의 원본 `task-*.jfr`
- 두 task ID와 campaign 정보를 가진 같은 target의 `manifest.json`

한 task의 JFR만 전달하면 task 간 편향과 교체 여부를 판단할 수 없다. HTML report와 `summary.json`도 함께 주면 k6 p95/p99, 실패율, dropped iterations, threshold와 CPU, allocation, lock, thread 상태를 같은 시간축에서 해석할 수 있다.

> 경고: JFR에는 내부 class와 method 이름, thread 이름, stack trace, endpoint 처리 경로, 일부 환경 및 application context 같은 민감한 내부 정보가 남을 수 있다. 외부 서비스나 공개 issue에 업로드하기 전에 조직의 허용 범위, 보관 기간, 비식별화 요구를 확인한다. Access token과 fixture secret은 별도로 절대 첨부하지 않는다.

## 7. 종료와 정리

브라우저에서 active campaign이 없는지 확인하고 dashboard terminal에서 `Ctrl+C`로 종료한다. Dashboard 종료는 active runner가 있으면 먼저 cancel과 bounded cleanup을 시도한다. `k6/fixtures/dev.json`, token이 남은 shell, 다운로드한 JFR과 ZIP은 팀 보안 정책에 따라 정리한다.

이 도구의 범위는 로컬 개발자 운용과 artifact 수집이다. 공개 community 게시, 외부 benchmark 홍보, 자동 업로드, 공유 링크 생성은 명시적으로 제외한다.
