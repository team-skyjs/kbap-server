# Research: Sentry 연동 — api·batch 에러 로그 수집

현 상태: 전역 `GlobalExceptionHandler`(`@RestControllerAdvice`)가 모든 예외를 잡아 `BaseResponse.fail` 로 응답한다. `logFailure` 는 5xx 만 ERROR(+cause), 4xx 는 WARN 으로 남긴다. `RequestLoggingFilter` 가 `requestId`·`memberId` 를 MDC 에 넣는다. 배치는 Spring Batch(`JobOperator.start`)로 잡 2개(`foodContentOutboxPublishJob`·`foodVectorSyncJob`)를 돌린다. ECS 태스크 정의는 Terraform 소유(`container_definitions` ignore_changes)이고, 배포 워크플로가 `describe-task-definition` → jq 로 `.image` 만 바꿔 새 리비전을 등록한다. 비밀값은 `api_secret_names`/`batch_secret_names` → SSM `/kbap/<env>/<NAME>` SecureString → 컨테이너 환경변수.

## R1. 캡처 지점 — `sentry.exception-resolver-order = HIGHEST_PRECEDENCE`, 어드바이스 무수정

- **Decision**: `application.yml` 에 `sentry.exception-resolver-order: -2147483648` 을 둔다. Sentry 스타터의 `SentryExceptionResolver` 가 `ExceptionHandlerExceptionResolver`(어드바이스) **앞에서** 예외를 캡처하고 null 을 돌려주므로 어드바이스는 종전대로 응답한다. 핸들러에서 던져진 예외는 4xx·5xx 가리지 않고 전부 이벤트가 된다(FR-001). `GlobalExceptionHandler` 는 한 글자도 바꾸지 않는다.
- **Rationale**: 어드바이스가 모든 예외를 삼키는 구조라 기본 순서(LOWEST)로는 이벤트가 0건이다. 어드바이스 안에서 `Sentry.captureException` 을 부르는 대안은 핸들러 메서드마다(또는 `logFailure` 에) 호출을 심어야 하고, 관측 관심사가 응답 조립 코드에 섞인다. 리졸버 순서는 설정 한 줄이고 Sentry 공식 문서가 안내하는 방식이다.
- **중복 방지**: 5xx 는 리졸버 캡처 + `logFailure` 의 ERROR 로그(cause 포함)로 두 번 잡힐 수 있으나, SDK 기본 `DuplicateEventDetectionEventProcessor` 가 같은 throwable 인스턴스를 한 번만 보낸다. 4xx 는 WARN 이라 로그 경로로는 안 잡히고 리졸버 경로로만 잡힌다.
- **잡히지 않는 것**: 필터 단계에서 예외 없이 401/400 을 써 버리는 응답(JWT 필터·API 버전 필터). 사용자 입력 노이즈라 제외가 맞다(문서에 명시).
- **Alternatives**: `logFailure` 에서 명시 캡처 — 위 이유로 기각. `HandlerInterceptor.afterCompletion` — 어드바이스가 처리하면 `ex` 가 null 이라 불가.

## R2. SDK 아티팩트 — Spring Boot 4 라인 스타터 + logback

- **Decision**: 버전 카탈로그에 `sentry = "8.x"`(구현 시 최신 8.x 확정)와 `sentry-spring-boot-4-jakarta`·`sentry-logback` 두 좌표를 두고, `:api` 는 둘 다, `:batch` 는 `sentry-spring-boot-4-jakarta`(웹 없이도 `Sentry.init`·스코프·EventProcessor 빈 등록 담당) + `sentry-logback` 을 쓴다. Sentry Java 8.2x 부터 Spring Boot 4 / Spring Framework 7 전용 모듈이 있다.
- **Rationale**: 스타터가 `sentry.*` 프로퍼티 바인딩, 요청 필터(URL·메서드·헤더·경로 템플릿), 예외 리졸버, `EventProcessor` 빈 자동 등록, DSN 부재 시 비활성까지 전부 해 준다. logback 모듈은 ERROR 로그 → 이벤트, INFO 이상 → breadcrumb.
- **검증 항목(구현 T001)**: Maven Central 에 Boot 4 모듈이 없으면 `sentry-spring-boot-starter-jakarta`(Boot 3 라인)로 시도하고, 그것도 Boot 4.1 과 안 맞으면 수동 `Sentry.init` + `SentryAppender` 로 폴백한다(리졸버 순서는 `HandlerExceptionResolver` 빈을 직접 등록).

## R3. 태그·핑거프린트 — `EventProcessor` 빈 하나(api), yml 고정 태그

- **Decision**: `api.core.observability.SentryRequestContextProcessor : EventProcessor`(`@Component`) 하나가 (1) MDC 의 `requestId`·`memberId` 를 태그로 승격, (2) `event.throwable` 이 `BusinessException` 이면 `error.code`·`http.status` 태그 + `fingerprint = ["business", code]`, (3) `ErrorResponse` 구현 예외면 `http.status` 를 그 상태로, 그 외 예외는 `http.status=500` 으로 태그한다. `service` 태그는 yml `sentry.tags.service: api`/`batch` 로 코드 없이. 인스턴스 구분은 SDK 기본 `server_name`(= 호스트명 = ECS 컨테이너 id) 그대로 쓴다.
- **Rationale**: 리졸버 캡처 시점엔 응답 상태가 아직 없지만 `BusinessException.errorCode` 에 status·code 가 들어 있어 예외에서 꺼낼 수 있다. MDC 는 logback 경로에서만 자동 첨부되므로 리졸버 경로용으로 프로세서가 읽어 준다. 4xx 를 전부 받으면 같은 `COMMON-002` 가 던져진 위치마다 이슈가 갈라지므로 에러 코드 핑거프린트로 묶는다(FR-008). 인스턴스 태그를 위해 ECS 메타데이터 엔드포인트를 호출하는 코드는 YAGNI — 컨테이너 id 로 충분히 구분되고 필요 시 ECS 콘솔에서 태스크를 찾는다.
- **Alternatives**: `sentry.context-tags`(MDC 키 → 태그) — logback 어펜더 경로에만 적용돼 리졸버 캡처엔 안 붙는다. `beforeSend` 람다 — 프로세서 빈과 같은 일이나 테스트하기 불편.

## R4. release — 배포 워크플로가 이미지 태그를 `SENTRY_RELEASE` 로 주입

- **Decision**: 4개 배포 워크플로(`deploy-dev`·`deploy-prod`·`deploy-batch-dev`·`deploy-batch-prod`)의 jq 단계에서 `.image` 를 바꿀 때 컨테이너 `environment` 에 `SENTRY_RELEASE=<이미지 태그>`(`api-<sha>`/`batch-<sha>`)를 함께 넣는다(있으면 교체, 없으면 추가). yml 은 `sentry.release: ${SENTRY_RELEASE:}`. Terraform 은 `container_definitions` 를 ignore 하므로 드리프트 없음.
- **Rationale**: 이미지 태그 = git 커밋이라 "이 배포부터" 를 커밋으로 읽는다(FR-004·SC-005). 런타임에 ECS 메타데이터에서 이미지를 읽는 대안은 코드가 들고 두 앱에 중복된다. 워크플로 jq 한 줄이 가장 짧다.
- **Alternatives**: Gradle `bootBuildInfo` 의 `build.version` — 이미지 태그와 다른 값이라 배포와 대조가 안 된다.

## R5. DSN — SSM `API_SENTRY_DSN`·`BATCH_SENTRY_DSN`, 프로젝트 2개

- **Decision**: Sentry 프로젝트를 `kbap-api`·`kbap-batch` 둘로 만들고(콘솔), SSM SecureString `/kbap/<env>/API_SENTRY_DSN`·`/kbap/<env>/BATCH_SENTRY_DSN` 을 dev·prod 에 만든 뒤 `api_secret_names`·`batch_secret_names` 기본값에 각각 추가한다. yml 은 `sentry.dsn: ${API_SENTRY_DSN:}` / `${BATCH_SENTRY_DSN:}`. `environment` 는 `${SPRING_PROFILES_ACTIVE}` 를 그대로 쓴다(dev/prod).
- **Rationale**: SSM 이름이 곧 환경변수 이름이고 `/kbap/<env>/` 아래에서 api·batch 가 같은 이름을 공유하므로, 프로젝트별 DSN 을 나누려면 이름을 달리해야 한다(FR-009·FR-010). ECS 는 secrets 에 적힌 SSM 파라미터가 없으면 태스크 기동을 거부하므로 **파라미터 생성 → terraform apply → 배포** 순서가 필수(quickstart).
- **Alternatives**: 프로젝트 1개 + `service` 태그로 구분 — 알림 규칙·이슈 목록을 앱별로 나누기 불편하고 사용자 요구(컨테이너별 알림 분리)와 어긋난다.

## R6. 로컬·테스트 — DSN 부재로 비활성, 별도 스위치 없음

- **Decision**: `application-local.yml` 과 테스트 `application.yml` 에 DSN 을 두지 않는다. Sentry 스타터는 `sentry.dsn` 이 없으면 자동구성 자체를 건너뛰어(빈·필터·리졸버 미등록) 앱은 정상 기동한다(FR-011). `sentry.enabled` 같은 별도 스위치를 만들지 않는다.
- **Rationale**: 프로퍼티 하나로 켜고 끄는 게 SDK 의 설계 의도이고, 통합 테스트 컨텍스트(`@IntegrationTest` 단일 컨텍스트 규칙)에 아무 영향을 주지 않는다.

## R7. 로그 연동 수위

- **Decision**: `sentry.logging.minimum-event-level: error`, `minimum-breadcrumb-level: info`. WARN 은 이벤트가 아니다(FR-002). breadcrumb 는 SDK 기본 100개.
- **Rationale**: 4xx 를 WARN 으로 남기는 현행 `logFailure` 정책을 유지하면서 4xx 는 R1 리졸버 경로로만 잡는다. INFO breadcrumb 는 "에러 직전 무슨 일" 을 준다(FR-006).

## R8. 개인정보 — 기본 PII 비전송, 요청 본문 비첨부

- **Decision**: `sentry.send-default-pii: false`(기본), `sentry.max-request-body-size: none`(기본). 프로세서가 붙이는 사용자 정보는 `memberId` 숫자 태그뿐. SDK 는 PII 비전송 시 `Authorization`·`Cookie` 류 헤더를 제외한다(FR-007).

## R9. 배치 잡 이름 — `JobExecutionListener` 로 MDC `job` 키

- **Decision**: `batch.observability.JobNameMdcListener : JobExecutionListener`(beforeJob 에 `MDC.put("job", name)`, afterJob 에 remove)를 만들어 두 잡 빌더에 `.listener(...)` 로 붙인다. Spring Batch 가 스텝 실패를 ERROR 로 로그하므로(`AbstractStep`) logback 경로로 이벤트가 되고, MDC 의 `job` 은 어펜더가 자동 첨부한다. batch 용 프로세서는 두지 않는다(요청 컨텍스트가 없음). `service=batch` 는 yml 태그.
- **Rationale**: Spring Batch 는 전역 리스너 빈을 자동 적용하지 않아 잡별 등록이 필요하지만 잡이 2개뿐이다. 스텝은 잡 스레드에서 도니 MDC 가 유지된다.
- **Alternatives**: `BatchJobLauncher.launch` 에서 MDC — `JobOperator.start` 가 다른 스레드에서 돌 수 있어 전파 안 됨.

## R10. 테스트 범위

- **Decision**: (1) `SentryRequestContextProcessorTest`(BehaviorSpec, Spring 없음): BusinessException 4xx/5xx → 태그·핑거프린트, 일반 예외 → `http.status=500`, MDC 유무에 따른 requestId·memberId 태그. (2) `SentryConfigTest`(Spring 없음): `api/src/main/resources/application.yml` 을 YAML 로 읽어 `sentry.exception-resolver-order == Integer.MIN_VALUE`, `sentry.dsn` 가 `${API_SENTRY_DSN:}` 플레이스홀더, `send-default-pii` false 임을 고정 — R1 의 핵심 설정이 실수로 지워지는 회귀를 막는다. (3) `JobNameMdcListenerTest`: before/after 로 MDC 세팅·해제. 통합 테스트는 DSN 부재로 Sentry 빈이 없어 리졸버 순서를 컨텍스트에서 검증할 수 없고, 그러려면 컨텍스트를 새로 띄워야 해 규약 위반이므로 두지 않는다. dev 배포 후 quickstart 의 수동 시나리오가 E2E 를 대신한다.

## R11. 문서

- **Decision**: `docs/observability/sentry.md` — 아티팩트·설정·태그 규약·프로젝트/SSM 이름·배포 순서·후속 알림 설정 안내(프로젝트별 규칙, 4xx 제외 조건 예시)·노이즈 조정 방법(코드별 `beforeSend` 제외, 샘플링).
