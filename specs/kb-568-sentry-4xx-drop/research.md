# Research: Sentry 4xx·클라이언트 끊김 이벤트 미전송

로컬 Gradle 캐시의 `sentry-8.55.0.jar`·`sentry-logback-8.55.0.jar`·`spring-web-7.0.8.jar`·`tomcat-embed-core-11.0.22.jar` 를 `javap`/`unzip` 으로, 선행 PR 은 `gh pr view/diff 257·258` 로 확인했다(2026-09-15).

## R1. 선행 PR #257 의 결말과 이번 범위 (Jira DoD 1)

- **Decision**: PR #257 의 프로세서 diff·문서 변경을 그대로 되살린다(테스트 파일은 R8 결정으로 제외). 범위는 #257 과 동일(4xx drop + 인바운드 끊김 drop + 문서).
- **Rationale**: #257 은 2026-09-09 "KB-508 의 4xx 전부 수집 정책을 뒤집는다" 는 이유로 닫혔고 #258(404 한 종류만)로 대체됐다 — 코드 결함이 아니라 **정책 충돌**이 사유다(Codex 최종 리뷰 "no major issues"). KB-568 은 한도 5,000건/월·알림 노이즈 근거로 그 정책을 바꾸는 결정이므로 충돌이 해소된다. #257 은 Codex P1(아웃바운드 IOException 메시지 매칭 금지)·P2(문서 계약) 를 이미 반영한 상태다.
- **Alternatives considered**: (a) `ignored-exceptions-for-type` 목록 확장 — 정확한 클래스 일치라 `BusinessException` 의 4xx/5xx 를 못 가르고 `ResponseStatusException` 하위 클래스를 전부 나열해야 해 기각. (b) `sentry.ignored-errors`(메시지 정규식) — 메시지 결합이라 기각. (c) `exception-resolver-order` 를 기본값으로 올려 핸들러가 삼킨 예외를 안 잡기 — 5xx 도 같이 사라져 기각(spec Assumptions).

## R2. drop 수단 — `EventProcessor.process` 의 `null` 반환

- **Decision**: `SentryRequestContextProcessor.process` 반환 타입을 `SentryEvent?` 로 바꾸고 drop 시 `null`.
- **Rationale**: `io.sentry.EventProcessor.process(SentryEvent, Hint)` 는 `@Nullable SentryEvent` 를 반환한다(클래스 파일의 `org/jetbrains/annotations/Nullable` 확인). `SentryClient.processEvent` 는 프로세서가 `null` 을 돌려주면 "Event was dropped by a processor" 를 남기고 전송하지 않는다. `docs/observability/sentry.md` "후속·조정" 이 이미 "특정 에러 코드 단위 제외는 프로세서에서 `null` 반환" 을 안내하고 있어 계약과 일치한다.

## R3. 판정 순서 — 상태 계산을 먼저, 태그는 통과 이벤트에만

- **Decision**: `httpStatusOf(throwable)` 로 상태를 먼저 구하고 (1) 인바운드 끊김이면 drop, (2) `status in 400..499 && !hasOptimisticConflictCause` 면 drop, (3) 통과 이벤트에만 `http.status`·`error.code`·핑거프린트를 단다. 상태 매핑은 종전과 동일(`BusinessException` → `errorCode.status` / `ErrorResponse` → `statusCode` / `IllegalArgumentException`·`HttpMessageNotReadableException`·`MethodArgumentTypeMismatchException` → 400 / 그 외 → 낙관적 락 cause 409, 아니면 500).
- **Rationale**: `GlobalExceptionHandler` 와 같은 매핑이라 "핸들러가 4xx 로 응답한 것 = drop" 이 정확히 성립한다. `MethodArgumentNotValidException`·`HandlerMethodValidationException`·`MissingServletRequestParameterException`·`HttpRequestMethodNotSupportedException`·`HttpMediaTypeNotSupportedException` 은 전부 `ErrorResponse` 구현체라 별도 분기 없이 4xx 로 잡힌다.
- **Alternatives considered**: 상태 태그를 먼저 달고 나중에 drop — 버릴 이벤트에 태그를 다는 낭비라 #257 형태(먼저 계산) 채택.

## R4. API 버전 헤더 누락·미지원 400 의 예외 타입

- **Decision**: 별도 분기 없음 — `ErrorResponse` 4xx 로 drop 된다.
- **Rationale**: `MissingApiVersionException`·`InvalidApiVersionException`(및 하위 `NotAcceptableApiVersionException`) 은 `org.springframework.web.server.ResponseStatusException` 을 상속하고(javap 확인), `ResponseStatusException` 은 `ErrorResponse` 다. `WebConfig` 의 폴백 리졸버가 1.0 을 공급하지 않는 `/api/**` 요청은 이 예외로 핸들러 `handleUnexpected` 의 `ErrorResponse` 분기 → 400 `COMMON-002` 가 된다(CLAUDE.md 경로 규약). 2026-09-15 Slack 에 온 "잘못된 X-API-Version 400" 이 이 경로다.

## R5. 인바운드 끊김 판정 — 예외 타입 2종, 메시지 매칭 금지 (Codex P1)

- **Decision**: 원인 체인에 `org.apache.catalina.connector.ClientAbortException` 또는 `org.springframework.web.context.request.async.AsyncRequestNotUsableException` 이 있으면 drop. `IOException` 메시지("Broken pipe"·"Connection reset")는 보지 않는다.
- **Rationale**: 두 클래스는 **서블릿 응답 스트림**에서만 난다 — `ClientAbortException`(final, `BadRequestException`→`IOException` 하위) 은 Tomcat 커넥터가 응답 쓰기 실패를 감쌀 때, `AsyncRequestNotUsableException`(`IOException` 하위) 은 비동기 요청의 응답이 더 이상 쓸 수 없을 때 Spring MVC 가 던진다. DB(MySQL 드라이버)·S3·OpenAI 클라이언트의 "Connection reset" 은 각자의 `IOException`/`SocketException` 으로 오며 이 두 타입이 아니므로 500 으로 통과한다(FR-004). 원인 체인을 보는 이유: 스트리밍 응답 실패는 `HttpMessageNotWritableException` 등으로 감싸여 올 수 있다.
- **Alternatives considered**: `sentry.ignored-exceptions-for-type` 에 두 클래스 등록 — 정확한 클래스 일치라 감싸인 경우를 못 잡아 기각.

## R6. 앱 에러 코드 409 vs 낙관적 락 409

- **Decision**: `BusinessException` 의 409(`COMMON-004`·`FOOD-006`·`FOOD-009` 등)는 4xx 로 drop, 원인 체인의 `OptimisticLockingFailureException`/`OptimisticLockException` 만 409 로 전송.
- **Rationale**: 전자는 클라이언트가 재시도하면 되는 정상 충돌(중복 등록·stale 버전)이고 후자는 서버 측 경합 신호다. `httpStatusOf` 가 `BusinessException` 을 먼저 매칭하므로 `BusinessException(409)` 는 `hasOptimisticConflictCause` 가 거짓이면 drop 된다. 이론상 `BusinessException` 의 cause 에 락 예외가 있으면 전송 — spec Edge Case 대로 "놓치는 것보다 한 건 더" 를 택한다.

## R7. logback 캡처 경로도 같은 프로세서를 지난다

- **Decision**: 추가 조치 없음.
- **Rationale**: `SentryAppender.createEvent` 가 `IThrowableProxy.getThrowable()` 을 이벤트 throwable 로 싣는다(클래스 파일 문자열 확인). 프로세서는 이벤트 출처를 가리지 않으므로 ERROR 로그 + 4xx 예외 조합이 생겨도 같은 판정으로 drop 된다. 현재 `GlobalExceptionHandler.logFailure` 는 4xx 를 WARN(`log.atWarn()`, cause 없음), 5xx 만 ERROR + `setCause` 로 남기므로 실제로는 리졸버 경로만 걸린다. 예외 없는 ERROR 로그 이벤트(`throwable == null`)는 `?: return event` 로 즉시 통과(FR-005).

## R8. 검증 방식 — 자동 테스트 없음 (사용자 결정)

- **Decision**: 테스트 코드를 두지 않는다. PR #257 의 `SentryRequestContextProcessorTest` 는 되살리지 않는다. 검증은 (1) 로컬 가짜 DSN + `sentry.debug=true` 로 `Event was dropped by a processor` 로그 확인, (2) dev 배포 후 콘솔에서 SC-001~005 확인(quickstart).
- **Rationale**: 사용자 결정 2026-09-15 "테스트 코드는 필요없음" — 2026-09-09(KB-508) 의 "Sentry 관련 테스트 전부 삭제, 이후 관측 작업에도 미작성" 을 분기 로직에도 그대로 적용한 것이다. 처음 플랜은 KB-510 결정을 "SDK 설정 한 줄 한정" 으로 좁게 읽어 단위 테스트를 넣었으나 기각됐다. 판정이 `GlobalExceptionHandler` 와 같은 매핑이라 회귀는 dev 실이벤트(4xx 요청에 0건·5xx 요청에 1건)로 즉시 드러난다.
- **Alternatives considered**: Spring 없는 단위 테스트(#257 의 12 시나리오) — 사용자 기각. 통합 테스트 — DSN 부재로 자동구성이 안 뜨고 새 컨텍스트가 필요해 기각.

## R9. batch

- **Decision**: 무변경. `docs/observability/sentry.md` 에 "batch 는 HTTP 경계가 없어 4xx·끊김 개념이 없다 — ERROR 로그·잡 실패 기준 유지" 한 줄.
- **Rationale**: batch `application.yml` 의 `sentry:` 블록에는 `exception-resolver-order` 도 없고 프로세서도 없다(수집원은 logback 과 잡 리스너뿐). 적용할 대상이 없다.

## 실증 (2026-09-15, 로컬)

가짜 DSN `https://k@localhost.invalid/0` + `--sentry.debug=true`, 일회용 MySQL 3307·Redis 6380, `--server.port=8081`. 요청별 응답 코드와 SDK DEBUG 로그:

```text
GET /api/foods/999999999?lang=ko (X-API-Version: 1.0)  → 400 FOOD-001        Event was dropped by a processor: ...SentryRequestContextProcessor
GET /api/foods/1?lang=ko (헤더 없음)                     → 400 COMMON-002      (MissingApiVersionException)  dropped by a processor
GET /api/foods/1?lang=ko (X-API-Version: 9.9)           → 400 COMMON-002      (InvalidApiVersionException)  dropped by a processor
GET /api/foods/abc?lang=ko                               → 400 COMMON-002      (MethodArgumentTypeMismatchException) dropped by a processor
DELETE /api/app-version                                  → 405 COMMON-002      (HttpRequestMethodNotSupportedException) dropped by a processor
GET /api/no-such-path                                    → 404 COMMON-002      Event was dropped as the exception class ...NoResourceFoundException is ignored (KB-510 경로 유지)
GET /v3/api-docs (--max-time 0.05, 클라이언트 끊김)       → (응답 없음)          AsyncRequestNotUsableException ← ClientAbortException ← IOException: Broken pipe
                                                                                 리졸버 경로 이벤트: dropped by a processor (SentryRequestContextProcessor)
                                                                                 logback ERROR 경로 이벤트: dropped by a processor (DuplicateEventDetectionEventProcessor)
```

- `Sending the event` 0건 — 7건 전부 전송 전에 버려졌다.
- `GlobalExceptionHandler` 의 `request failed: <예외> <코드> <상태> <경로>` 로그는 7건 모두 종전대로 남았다(FR-006). 끊김 건은 핸들러가 500 으로 분류해 ERROR 로 남기므로 logback 경로 이벤트도 생기는데, 같은 throwable 이라 SDK 중복 감지가 두 번째를 버린다 — 첫 번째는 우리 프로세서가 R5 대로 버렸다.
- R4 확인: 버전 헤더 400 은 `MissingApiVersionException`·`InvalidApiVersionException` 이 `ErrorResponse` 분기로 4xx 판정됐다.
