# Quickstart: 스프링 컨테이너 메트릭 개선

## 1. 테스트 (TDD 순서)

```bash
# Red — 설정 추가 전
./gradlew :api:test --tests "com.kbap.api.core.metrics.ActuatorMetricsExposureTest"
# Kotest 는 --tests 필터를 무시하고 api 전 스펙을 돌린다(메모리 kotest-ignores-gradle-tests-filter). 결과는 리포트에서 해당 클래스만 본다.

# Green — application.yml 두 블록 추가 후 같은 명령
```

## 2. 로컬 실노출 확인 (Tomcat 은 실제 서버가 있어야 나온다)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun --no-daemon   # 병행 워크트리면 포트 8081 등으로 격리
curl -s localhost:8080/api/app-version > /dev/null                 # 타이머 1건 생성
curl -s localhost:8080/actuator/prometheus | grep -E "^tomcat_threads_(busy|current|config_max)_threads"
curl -s localhost:8080/actuator/prometheus | grep -c "^http_server_requests_seconds_bucket"   # 버킷 행 수 (조합당 ~40)
```

## 3. dev 배포 후 카디널리티 확인 (prod 릴리스 전)

홈 Grafana Explore 에서:

```promql
count({__name__="http_server_requests_seconds_bucket", env="dev"})      # 시계열 수
count by (uri) ({__name__="http_server_requests_seconds_bucket", env="dev"})   # uri 폭증 여부
```

예상 범위: 조합 수 × 40 내외. uri 에 실제 id 값이 섞이면 경로 템플릿 집계가 깨진 것이니 즉시 중단.

## 4. 대시보드 구성 순서

1. 새 대시보드 → 변수 `env`(Query, Label values, label `env`, metric `http_server_requests_seconds_count`, Include All).
2. 패널 6종을 research.md §5 의 PromQL 로 추가. 범례 `{{env}}-{{instance}}`.
3. `env` 를 prod / dev / All 로 바꿔 전 패널이 따라오는지 확인.
4. Export → "Export for sharing externally" → `docs/observability/grafana-app-dashboard.json` 저장.
5. `docs/observability/grafana-app-dashboard.md` 에 변수·패널별 질의 의미 한 줄씩.

## 5. 되돌리기

`application.yml` 의 두 블록 삭제 → 배포. 시계열은 홈 Prometheus 보존 기간 뒤 자연 소멸.
