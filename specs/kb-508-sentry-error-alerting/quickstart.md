# Quickstart: Sentry 연동 검증

## 순서 (반드시 이 순서)

1. **Sentry 콘솔**: 프로젝트 생성 → DSN 복사. (2026-09-09 실제: org `skyjs`, dev 프로젝트는 `kbap-server-dev`(api)·`kbap-batch-dev`(batch). prod 는 후속.)
2. **SSM 파라미터**(dev 먼저, prod 는 dev 검증 후):
   ```bash
   aws ssm put-parameter --name /kbap/dev/API_SENTRY_DSN   --type SecureString --value '<api dsn>'
   aws ssm put-parameter --name /kbap/dev/BATCH_SENTRY_DSN --type SecureString --value '<batch dsn>'
   ```
3. **terraform apply**(dev workspace) — `api_secret_names`·`batch_secret_names` 변경으로 태스크 정의에 secrets 추가.
4. **코드 배포**(api·batch dev 워크플로) — 새 jq 가 `SENTRY_RELEASE` 를 넣는다.
5. 아래 시나리오로 검증. prod 는 2~4 를 prod 로 반복.

## 로컬 검증

```bash
./gradlew :api:test --tests "com.kbap.api.core.observability.*" :batch:test --tests "com.kbap.batch.observability.*"
./gradlew build
```
로컬 부팅(`SPRING_PROFILES_ACTIVE=local`)에서 예외를 내도 Sentry 이벤트가 없어야 한다(DSN 부재).

## dev 시나리오

| # | 행동 | 기대 |
|---|---|---|
| 1 | `GET /api/foods/999999999`(없는 id, 회원 토큰) | `kbap-api` 에 이벤트. 태그 `http.status=400`(또는 해당 코드), `error.code=FOOD-…`, `requestId`, `memberId`, `environment=dev`, `release=api-<sha>`, `service=api`, `server_name` |
| 2 | 1 을 세 번 반복 | 이슈 1개, 이벤트 3 |
| 3 | 5xx 유발(예: dev 에서 OpenAI 키를 잘못 넣고 스캔 호출 → 503) | `http.status=503`, `error.code=SCAN-006` 이벤트. `logFailure` ERROR 로그와 중복되지 않고 1건 |
| 4 | 인증 없이 보호 경로 호출(401) | 이벤트 **없음**(필터 단계, 예외 아님) — 의도된 동작 |
| 5 | batch: `POST /internal/batch/jobs/foodVectorSyncJob`(트리거 컨트롤러) 를 벡터 스토어 미설정 상태로 | `kbap-batch` 에 이벤트, 태그 `job=foodVectorSyncJob`, `service=batch`, breadcrumb 에 직전 INFO 로그 |
| 6 | api 태스크 2대에서 같은 오류 | 이슈 1개, 이벤트의 `server_name` 이 서로 다름 |

## 확인 명령

```bash
aws ecs describe-task-definition --task-definition kbap-dev-api \
  --query 'taskDefinition.containerDefinitions[0].{env:environment,secrets:secrets}'
# environment 에 SENTRY_RELEASE, secrets 에 API_SENTRY_DSN 이 있어야 한다
```

## 되돌리기

DSN 파라미터를 지우면 다음 배포부터 태스크 기동이 실패하므로, 끄려면 **파라미터 값을 빈 문자열이 아닌 더미 DSN 으로 두거나** `api_secret_names` 에서 빼고 apply 한 뒤 배포한다. 코드 revert 만으로도 SDK 가 빠지므로 안전하다.
