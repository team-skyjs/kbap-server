# Quickstart: 검증 절차

## 1. 정적 검사 (SC-001 의 설정 수준·SC-003)

```bash
# api dev 가 prod 와 같은 두 키를 가진다
grep -n "console: ecs\|show-sql" api/src/main/resources/application-dev.yml   # ecs 1건, show-sql: false
grep -n "console: ecs\|show-sql" api/src/main/resources/application-prod.yml  # 위와 같은 값

# batch dev 는 show-sql 만 false, structured 없음
grep -n "show-sql\|structured" batch/src/main/resources/application-dev.yml   # show-sql: false 만

# local·staging·prod 는 무변경
git diff --stat develop -- api/src/main/resources/application-local.yml api/src/main/resources/application-staging.yml api/src/main/resources/application-prod.yml batch/src/main/resources/application-local.yml batch/src/main/resources/application-staging.yml batch/src/main/resources/application-prod.yml   # 비어야 함
```

## 2. 자동 테스트 (SC-005, FR-006)

```bash
./gradlew :api:test     # StructuredConsoleLoggingTest 가 dev·staging·prod 세 프로필을 검사
```

## 3. 로컬에서 dev 프로필로 실노출 확인 (SC-001·SC-002, 머지 전)

일회용 MySQL·Redis 컨테이너에 dev 프로필로 붙인다. dev 프로필이 요구하는 환경변수만 로컬 값으로 준다.

```bash
docker run -d --name kbap-devlog-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=kbap -p 3307:3306 mysql:8
docker run -d --name kbap-devlog-redis -p 6380:6379 redis:7

set -a; source .env; set +a          # OPENAI_API_KEY 등 필수 키(메인 체크아웃의 .env)
SPRING_PROFILES_ACTIVE=dev \
DB_URL='jdbc:mysql://localhost:3307/kbap' DB_USERNAME=root DB_PASSWORD=root \
REDIS_HOST=localhost REDIS_PORT=6380 REDIS_SSL_ENABLED=false \
STORAGE_BUCKET=dummy IMAGE_PUBLIC_BASE_URL=http://localhost \
./gradlew :api:bootRun 2>&1 | tee /tmp/kbap-devlog.txt
```

다른 터미널에서:

```bash
curl -s -H 'X-API-Version: 1.0' 'http://localhost:8080/api/home?lang=en' >/dev/null
grep -c '^{' /tmp/kbap-devlog.txt                       # 0 보다 커야 함 (JSON 줄)
grep -m1 '"requestId"' /tmp/kbap-devlog.txt | head -c 300   # requestId 필드가 보여야 함
grep -ci 'select .* from ' /tmp/kbap-devlog.txt          # 0 이어야 함 (SQL 미출력)
```

정리:

```bash
docker rm -f kbap-devlog-mysql kbap-devlog-redis
```

## 4. dev 배포 후 수집기 확인 (SC-004, 머지 후)

develop 푸시로 `deploy-dev.yml` 이 api 를, `deploy-batch-dev.yml` 이 batch 를 자동 배포한다. 배포 완료 후 CloudWatch Logs Insights(dev api 로그 그룹):

```text
fields @timestamp, requestId, memberId, message
| filter ispresent(requestId)
| sort @timestamp desc
| limit 20
```

임의의 `requestId` 하나를 골라:

```text
fields @timestamp, message
| filter requestId = "<골라낸 값>"
```

그 요청의 줄만 나오면 통과. batch 로그 그룹에서는 `select` 문이 안 보이면 통과.

## 기대 결과 요약

| 검사 | 기대 |
|------|------|
| api dev yml | `console: ecs`, `show-sql: false` |
| batch dev yml | `show-sql: false`, structured 없음 |
| local·staging·prod 6파일 diff | 없음 |
| `:api:test` | BUILD SUCCESSFUL |
| 로컬 dev 프로필 로그 | JSON 줄, `requestId` 필드, SQL 0건 |
| dev CloudWatch | `requestId` 필드 검색 성공 |
