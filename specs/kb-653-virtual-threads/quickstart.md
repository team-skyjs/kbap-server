# Quickstart: 검증 절차

## 1. 정적 검사

```bash
grep -n "virtual" api/src/main/resources/application-dev.yml          # spring.threads.virtual.enabled: true
git diff --stat develop -- api/src/main/resources/application-local.yml api/src/main/resources/application-staging.yml api/src/main/resources/application-prod.yml api/src/main/resources/application.yml batch/                                # 비어야 함
git diff --stat develop -- docs/observability/                                    # 비어야 함 — 대시보드 패널은 그라파나에서 직접 추가(research D3 개정)
```

## 2. 자동 테스트

```bash
./gradlew :api:test
```

## 3. 로컬 dev 프로필 실노출 (머지 전)

KB-652 와 같은 일회용 컨테이너 레시피.

```bash
docker run -d --name kbap-vt-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=kbap -p 3307:3306 mysql:8
docker run -d --name kbap-vt-redis -p 6380:6379 redis:7
set -a; source .env; set +a
SPRING_PROFILES_ACTIVE=dev DB_URL='jdbc:mysql://localhost:3307/kbap' DB_USERNAME=root DB_PASSWORD=root \
REDIS_HOST=localhost REDIS_PORT=6380 REDIS_SSL_ENABLED=false STORAGE_BUCKET=dummy IMAGE_PUBLIC_BASE_URL=http://localhost \
./gradlew :api:bootRun > api/build/vt.log 2>&1 &
```

다른 터미널에서:

```bash
curl -s -o /dev/null -H 'X-API-Version: 1.0' 'http://localhost:8080/api/home?lang=en'
grep -m1 '"--> GET /api/home' api/build/vt.log | python3 -c "import sys,json; print(json.loads(sys.stdin.read())['process']['thread']['name'])"   # tomcat-handler-N 이어야 함
curl -s http://localhost:8080/actuator/prometheus | grep -E '^http_server_requests_active' | head -3          # active_count 지표 존재
curl -s http://localhost:8080/actuator/prometheus | grep -E '^tomcat_threads_(busy|config_max)' | head        # 값 확인(무의미해진 값 기록)
docker rm -f kbap-vt-mysql kbap-vt-redis
```

## 4. dev 배포 후 (사용자 수행)

1. 같은 k6 스크립트(`~/Desktop/1만명-tomcat스레드.js`, 2천 VU × 5회)를 **AWS 안에서** 돌린다.
2. 그라파나 앱 대시보드에서 "처리 중 HTTP 요청 수" 최대값(SC-001: 200 초과), p95(SC-002), Hikari pending·500 건수(D1 의 예상 관측)를 기록한다.
3. 핀닝: `CAMPAIGN_ID=… JFR_ENABLED=true scripts/perf/run-endpoint.sh home-auth read 5 1m` 으로 JFR 을 받고
   ```bash
   jfr print --events jdk.VirtualThreadPinned <task>.jfr | grep -c "jdk.VirtualThreadPinned"
   jfr print --events jdk.VirtualThreadPinned <task>.jfr | grep -A12 "stackTrace" | head -40
   ```
   건수와 최상위 프레임을 Jira KB-653 DoD 4번째 항목에 기록한다.

## 기대 결과 요약

| 검사 | 기대 |
|------|------|
| dev yml | `spring.threads.virtual.enabled: true` 한 줄 추가 |
| 다른 프로필·batch diff | 없음 |
| 로컬 요청 스레드 이름 | `tomcat-handler-*` |
| `/actuator/prometheus` | `http_server_requests_active_seconds_gcount` 존재 |
| 대시보드 | 저장소 변경 없음 — "처리 중 HTTP 요청 수" 패널은 그라파나에서 직접 추가(`~/Desktop/처리중-HTTP-요청수-panel.json`) |
| `:api:test` | BUILD SUCCESSFUL |
