# Sentry 에러 수집 (KB-508 · KB-568)

api·batch 두 컨테이너의 **5xx·잠금 충돌 409(교착·잠금 대기·낙관 충돌)·예상 밖 예외와 ERROR 로그·배치 잡 실패**를 Sentry 이벤트로 모은다(그 밖의 4xx·클라이언트 끊김·예상된 저하는 미수집 — 아래 "수집되지 않는 것"). Grafana 는 "얼마나"(처리량·지연·자원), Sentry 는 "무엇이 어디서"(예외·스택·요청 맥락)를 맡는다. 알림 규칙은 이 기능 범위 밖이며 Sentry 콘솔에서 설정한다.

## 구성

| 항목 | 값 |
|---|---|
| SDK | `io.sentry:sentry-spring-boot-4-starter` + `io.sentry:sentry-logback` (버전 카탈로그 `sentry`) |
| Sentry 프로젝트 | org `skyjs`, **환경별·서비스별**: dev 는 `kbap-server-dev`(api)·`kbap-batch-dev`(batch, 2026-09-09 생성). prod 는 같은 규칙으로 추가 |
| DSN 주입 | SSM SecureString `/kbap/<env>/API_SENTRY_DSN`·`/kbap/<env>/BATCH_SENTRY_DSN` → terraform `api_secret_names`/`batch_secret_names`(전 env 공통 기본값 — apply 하는 env 마다 파라미터가 먼저 있어야 태스크가 뜬다) |
| release | 배포 워크플로 jq 가 이미지 태그(`api-<sha>`/`batch-<sha>`)를 컨테이너 env `SENTRY_RELEASE` 로 넣는다 |
| environment | `${SPRING_PROFILES_ACTIVE}` (dev/prod) |
| 로컬·테스트 | DSN 없음 = 자동구성 통째로 skip. 별도 `sentry.enabled` 스위치 없음 |

`sentry.*` 설정은 각 앱 `application.yml` 에 있다. 핵심 한 줄은 api 의 `exception-resolver-order: -2147483648` — `GlobalExceptionHandler` 가 모든 예외를 삼키므로 Sentry 리졸버가 그 앞에서 캡처해야 이벤트가 생긴다(어드바이스 응답은 그대로). 5xx 는 리졸버·ERROR 로그 두 경로로 잡히지만 SDK 중복 감지(같은 throwable)가 한 번만 보낸다. 로그 수위는 ERROR = 이벤트, INFO 이상 = breadcrumb.

## 태그 규약

| 키 | api | batch | 출처 |
|---|---|---|---|
| `service` | `api` | `batch` | yml `sentry.tags.service` |
| `environment` / `release` / `server_name` | ● | ● | yml / `SENTRY_RELEASE` / SDK 기본(호스트명 = ECS 컨테이너 id → 인스턴스 구분) |
| `requestId`, `memberId` | ● | | MDC ← `RequestLoggingFilter` (`SentryRequestContextProcessor`). 게스트면 `memberId` 없음 |
| `http.status` | ● | | **실제 응답 상태와 같다**(KB-683). 처리기가 `GlobalExceptionHandler` 에서 그 예외를 받을 핸들러 메서드를 스프링의 리졸버로 구해 정한다: 상태가 고정인 핸들러는 메서드의 `@ResponseStatus`(검증 실패·본문 파싱 실패·`IllegalArgumentException`·파라미터 타입 불일치 400) / `BusinessException` 은 코드의 상태 / 그 밖(catch-all·잠금 핸들러)은 `GlobalExceptionHandler.unexpectedStatusOf` — 잠금 충돌(cause 포함) 409 · Spring `ErrorResponse` 상태 · 그 외 500. 4xx 판정이면 이벤트 자체를 버린다(KB-568) — **잠금 충돌 409 만 예외로 보낸다** |
| `lock.conflict` | ● | | 잠금 충돌 이벤트에만 붙는다(KB-683): `deadlock`(MySQL 1213 교착 희생자) · `lock_wait`(잠금 대기 초과 등 그 밖의 비관 잠금 실패) · `optimistic`(낙관 충돌). 알림 규칙이 심각도와 무관하게 교착만 고를 때 쓴다 |
| `error.code` | ● | | `BusinessException.errorCode.code` (예: `COMMON-002`) |
| `job` | | ● | MDC ← `JobNameMdcListener` (잡 빌더 `.listener`), `sentry.context-tags: job` 으로 태그 승격 |

심각도(KB-683): Sentry 의 Spring 리졸버는 모든 예외에 `fatal` 을 붙인다. 처리기가 다음만 고친다 — 잠금 충돌 중 `deadlock`·`lock_wait` 은 `error`, `optimistic` 은 `warning`(응답 로그도 같은 구분: ERROR / WARN). `BusinessException` 5xx(서버가 스스로 돌려준 5xx)는 `error`. 처리되지 않은 500 과 Spring `ErrorResponse` 5xx 는 `fatal` 그대로다(알림 규칙이 `fatal` 기준일 수 있어 바꾸지 않았다).

핑거프린트: `BusinessException` 은 `["business", <error.code>]` — 같은 코드는 던진 위치와 무관하게 이슈 1개. 그 외는 SDK 기본(스택). `send-default-pii: true` 로 요청자 IP·헤더·쿠키를 싣는다(2026-09-09 결정). 단 `Authorization` 헤더는 살아 있는 access 토큰이라 프로세서가 제거하고, 쿼리스트링의 `q`·`latitude`·`longitude` 는 로그와 같은 `maskQuery` 로 `***` 처리하며, 요청 본문은 첨부하지 않는다(`max-request-body-size` 기본 none).

**수집되지 않는 것**:

- 필터 단계에서 예외 없이 응답을 쓰는 401(JWT)·400(`X-API-Version` 폴백) — 종전부터 이벤트가 생기지 않는다.
- **잠금 충돌을 뺀 모든 4xx** — 앱 에러 코드 4xx(비즈니스 409 정상 충돌 포함)·검증 실패·파라미터 누락·본문 파싱·타입 불일치·`X-API-Version` 누락/미지원·405·415: `SentryRequestContextProcessor` 가 `GlobalExceptionHandler` 와 같은 상태 매핑으로 4xx 를 판정해 전송 직전 버린다(KB-568). 클라이언트 잘못이라 서버가 조치할 것이 없고, 무료 플랜 한도(5,000건/월)를 5xx 보다 먼저 채우기 때문이다. 서버 로그(WARN)는 그대로 남는다. 낙관적 락 충돌(cause 포함)의 409 는 서버 경합 신호라 계속 수집한다.
- **예상된 저하** — `BusinessException(…, expected = true)`: 부하 때 반복되는 "우리가 일부러 건 거절"이다. 응답은 5xx 그대로지만 WARN 으로만 남기고 이벤트를 보내지 않는다(KB-683). 지금 쓰는 곳은 번역 동시 상한 초과(`TRANSLATION-001` 503) 하나이고, `ExpectedDegradationSitesTest` 가 그 한 곳으로 고정한다 — 외부 장애·용량 신호(스캔 몰림 `SCAN-008` 등)에는 붙이지 않는다.
- **전용 핸들러가 있는 예외가 잠금 실패를 감싼 경우** — 응답이 그 핸들러의 4xx 이므로 이벤트도 보내지 않는다(예: 검증 실패·`IllegalArgumentException` 의 원인 사슬에 교착). 응답이 409 로 나가는 경우(catch-all 로 떨어지는 예외가 잠금 실패를 감쌈)는 잠금 충돌로 보낸다.
- **인바운드 클라이언트 끊김** — 원인 체인에 `ClientAbortException`(Tomcat)·`AsyncRequestNotUsableException`(Spring MVC): 같은 프로세서가 버린다. DB·S3·LLM 등 아웃바운드 I/O 실패("Broken pipe"·"Connection reset" 메시지)는 메시지로 판정하지 않으므로 500 으로 계속 수집한다.
- 매핑되지 않은 경로의 404(`NoResourceFoundException` — 봇 스캔·오타 URL): `sentry.ignored-exceptions-for-type` 으로 SDK 가 이벤트 프로세서 이전에 버린다(정확한 클래스 일치, KB-510).
- batch 는 HTTP 경계가 없어 4xx·끊김 개념이 없다 — 변경 없음(ERROR 로그·잡 실패 기준 유지, KB-568 결정).

## 배포 순서 (반드시)

ECS 는 `secrets` 의 SSM 파라미터가 없으면 태스크를 기동하지 않는다.

1. Sentry 콘솔에서 해당 env 프로젝트 생성(Settings → Client Keys 의 DSN 복사. 위자드의 Gradle 플러그인·auth token·OTel 에이전트·트레이싱/프로파일링 설정은 쓰지 않는다)
2. `aws ssm put-parameter --profile kbap-infra --name /kbap/<env>/API_SENTRY_DSN --type SecureString --value '<dsn>'` (`BATCH_SENTRY_DSN` 도 동일)
3. `terraform apply` (해당 env) — **`*_secret_names` 는 전 env 공통 기본값**이라 prod 를 apply 하기 전에 `/kbap/prod/API_SENTRY_DSN`·`BATCH_SENTRY_DSN` 이 먼저 있어야 한다
4. api·batch 배포 워크플로 실행

검증 시나리오는 `specs/kb-508-sentry-error-alerting/quickstart.md`(수집)·`specs/kb-568-sentry-4xx-drop/quickstart.md`(4xx·끊김 미수집).

## 후속·조정

- **알림**: 프로젝트별 규칙 "새 이슈 → Slack". 4xx 는 이벤트 자체가 없어 조건이 필요 없다.
- **노이즈**: 4xx·인바운드 끊김은 이미 제외. 예외 종류 단위 제외는 `sentry.ignored-exceptions-for-type`(정확한 클래스 일치), 5xx 중 특정 에러 코드를 빼려면 `SentryRequestContextProcessor` 에서 해당 코드에 `null` 반환(이벤트 드롭), 양이 많으면 `sentry.sample-rate`.
- **되돌리기**: 코드 revert 로 SDK 가 빠진다. SSM 파라미터는 지우지 말 것(지우면 다음 배포부터 기동 실패) — 끄려면 `*_secret_names` 에서 빼고 apply 후 배포.
