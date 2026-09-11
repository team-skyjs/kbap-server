# Contract: Sentry 연동 설정·환경변수·배포 계약

## 1. 컨테이너 환경변수

| 이름 | 주입 경로 | api | batch | 없을 때 |
|---|---|---|---|---|
| `API_SENTRY_DSN` | SSM SecureString `/kbap/<env>/API_SENTRY_DSN` → `api_secret_names` | ● | | Sentry 비활성(정상 기동) |
| `BATCH_SENTRY_DSN` | SSM SecureString `/kbap/<env>/BATCH_SENTRY_DSN` → `batch_secret_names` | | ● | Sentry 비활성 |
| `SENTRY_RELEASE` | 배포 워크플로 jq (이미지 태그) | ● | ● | release 비어 있음(이벤트는 수집) |
| `SPRING_PROFILES_ACTIVE` | 기존 Terraform env | ● | ● | (기존) |

**주의**: ECS 는 `secrets` 에 적힌 SSM 파라미터가 없으면 태스크를 기동하지 않는다. 순서는 반드시 **SSM 파라미터 생성(dev·prod 각 2개) → `terraform apply` → 배포**.

## 2. `application.yml` (api)

```yaml
sentry:
  dsn: ${API_SENTRY_DSN:}
  environment: ${SPRING_PROFILES_ACTIVE:local}
  release: ${SENTRY_RELEASE:}
  send-default-pii: true
  exception-resolver-order: -2147483648
  tags:
    service: api
  logging:
    minimum-event-level: error
    minimum-breadcrumb-level: info
```

batch 는 `dsn: ${BATCH_SENTRY_DSN:}`, `tags.service: batch`, `exception-resolver-order` 없음(웹 없음).

`application-local.yml`·테스트 `application.yml` 에는 `sentry.*` 를 두지 않는다(DSN 부재 = 비활성).

## 3. 배포 워크플로 jq 변경 (4개 파일 공통)

기존:
```
.containerDefinitions |= map(if .name == "api" then .image = $IMAGE else . end)
```
변경:
```
.containerDefinitions |= map(if .name == "api" then
    .image = $IMAGE
    | .environment = ((.environment // []) | map(select(.name != "SENTRY_RELEASE"))
        + [{name: "SENTRY_RELEASE", value: ($IMAGE | split(":") | last)}])
  else . end)
```
release 값은 `$IMAGE` URI 의 태그 부분(`api-<sha>` / `batch-<sha>`, 또는 수동 입력 `image_tag`)이다 — batch 워크플로엔 `tag` 출력이 없어 `--arg TAG` 대신 URI 에서 잘라 네 파일이 같은 식을 쓴다. batch 워크플로는 `.name == "batch"`.

## 4. Terraform

`iac/terraform/modules/ecs-environment/variables.tf`:
- `api_secret_names` 기본값에 `"API_SENTRY_DSN"` 추가
- `batch_secret_names` 기본값에 `"BATCH_SENTRY_DSN"` 추가

`outputs.ssm_secret_parameters` 가 새 파라미터 경로를 보여 준다.

## 5. Sentry 콘솔 (코드 밖, 문서에 기록)

- 프로젝트 2개: `kbap-api`, `kbap-batch` (플랫폼 Java/Spring Boot). 각 DSN 을 SSM 에.
- 환경은 이벤트의 `environment` 로 자동 생성(dev/prod).
- 알림 규칙은 이 기능 범위 밖. 후속 예시: 프로젝트별 "새 이슈 → Slack", 조건 `http.status` 가 `5*` 로 시작.

## 6. 코드 계약

- `com.kbap.api.core.observability.SentryRequestContextProcessor : io.sentry.EventProcessor` — `@Component`. 태그 규약은 data-model.md.
- `com.kbap.batch.observability.JobNameMdcListener : JobExecutionListener` — 두 잡 빌더에 `.listener(...)`.
- `GlobalExceptionHandler`·`RequestLoggingFilter` 무수정.
