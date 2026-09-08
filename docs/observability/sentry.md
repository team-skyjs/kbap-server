# Sentry 에러 수집 (KB-508)

api·batch 두 컨테이너의 **핸들러 예외(4xx·5xx 전부)와 ERROR 로그·배치 잡 실패**를 Sentry 이벤트로 모은다. Grafana 는 "얼마나"(처리량·지연·자원), Sentry 는 "무엇이 어디서"(예외·스택·요청 맥락)를 맡는다. 알림 규칙은 이 기능 범위 밖이며 Sentry 콘솔에서 설정한다.

## 구성

| 항목 | 값 |
|---|---|
| SDK | `io.sentry:sentry-spring-boot-4-starter` + `io.sentry:sentry-logback` (버전 카탈로그 `sentry`) |
| Sentry 프로젝트 | `kbap-api`, `kbap-batch` (DSN 2개) |
| DSN 주입 | SSM SecureString `/kbap/<env>/API_SENTRY_DSN`, `/kbap/<env>/BATCH_SENTRY_DSN` → terraform `api_secret_names`/`batch_secret_names` |
| release | 배포 워크플로 jq 가 이미지 태그(`api-<sha>`/`batch-<sha>`)를 컨테이너 env `SENTRY_RELEASE` 로 넣는다 |
| environment | `${SPRING_PROFILES_ACTIVE}` (dev/prod) |
| 로컬·테스트 | DSN 없음 = 자동구성 통째로 skip. 별도 `sentry.enabled` 스위치 없음 |

`sentry.*` 설정은 각 앱 `application.yml` 에 있다. 핵심 한 줄은 api 의 `exception-resolver-order: -2147483648` — `GlobalExceptionHandler` 가 모든 예외를 삼키므로 Sentry 리졸버가 그 앞에서 캡처해야 이벤트가 생긴다(어드바이스 응답은 그대로). `SentryConfigTest` 가 이 값을 고정한다. 5xx 는 리졸버·ERROR 로그 두 경로로 잡히지만 SDK 중복 감지(같은 throwable)가 한 번만 보낸다. 로그 수위는 ERROR = 이벤트, INFO 이상 = breadcrumb.

## 태그 규약

| 키 | api | batch | 출처 |
|---|---|---|---|
| `service` | `api` | `batch` | yml `sentry.tags.service` |
| `environment` / `release` / `server_name` | ● | ● | yml / `SENTRY_RELEASE` / SDK 기본(호스트명 = ECS 컨테이너 id → 인스턴스 구분) |
| `requestId`, `memberId` | ● | | MDC ← `RequestLoggingFilter` (`SentryRequestContextProcessor`). 게스트면 `memberId` 없음 |
| `http.status` | ● | | `BusinessException.errorCode.status` / Spring `ErrorResponse` 상태 / 그 외 500 |
| `error.code` | ● | | `BusinessException.errorCode.code` (예: `COMMON-002`) |
| `job` | | ● | MDC ← `JobNameMdcListener` (잡 빌더 `.listener`) |

핑거프린트: `BusinessException` 은 `["business", <error.code>]` — 같은 코드는 던진 위치와 무관하게 이슈 1개. 그 외는 SDK 기본(스택). PII 는 보내지 않는다(`send-default-pii: false`, 요청 본문 미첨부, `Authorization`·`Cookie` 제외).

**수집되지 않는 것**: 필터 단계에서 예외 없이 응답을 쓰는 401(JWT)·400(`X-API-Version`) — 사용자 입력 노이즈라 의도된 제외.

## 배포 순서 (반드시)

ECS 는 `secrets` 의 SSM 파라미터가 없으면 태스크를 기동하지 않는다.

1. Sentry 콘솔에서 프로젝트 2개 생성, DSN 복사
2. `aws ssm put-parameter --name /kbap/<env>/API_SENTRY_DSN --type SecureString --value '<dsn>'` (BATCH 도 동일)
3. `terraform apply` (해당 env)
4. api·batch 배포 워크플로 실행

검증 시나리오는 `specs/kb-508-sentry-error-alerting/quickstart.md`.

## 후속·조정

- **알림**: 프로젝트별 규칙 "새 이슈 → Slack", 4xx 를 빼려면 조건 `http.status` starts with `5`.
- **노이즈**: 특정 에러 코드를 빼려면 `SentryRequestContextProcessor` 에서 해당 코드에 `null` 반환(이벤트 드롭), 양이 많으면 `sentry.sample-rate`.
- **되돌리기**: 코드 revert 로 SDK 가 빠진다. SSM 파라미터는 지우지 말 것(지우면 다음 배포부터 기동 실패) — 끄려면 `*_secret_names` 에서 빼고 apply 후 배포.
