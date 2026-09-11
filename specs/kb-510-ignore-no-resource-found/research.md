# Research: Sentry 존재하지 않는 경로 404 미전송

전부 로컬 Gradle 캐시의 `sentry-8.55.0.jar`·`sentry-spring-boot-4-8.55.0.jar` 를 `javap` 으로 읽어 확인했다(2026-09-10).

## R1. 제외 수단 — `sentry.ignored-exceptions-for-type`

- **Decision**: api `application.yml` 의 `sentry:` 블록에 `ignored-exceptions-for-type` 목록으로 `org.springframework.web.servlet.resource.NoResourceFoundException` 하나를 등록한다.
- **Rationale**: `io.sentry.spring.boot4.SentryProperties` 는 `@ConfigurationProperties("sentry")` 이며 `io.sentry.SentryOptions` 를 상속한다. `SentryOptions` 는 `getIgnoredExceptionsForType(): Set<Class<out Throwable>>`·`addIgnoredExceptionForType` 만 두고 setter 가 없다 — Spring Boot `JavaBeanBinder` 는 getter 전용 컬렉션에 바인딩 결과를 병합하므로 yml 목록이 그대로 들어간다. 문자열 → `Class` 변환은 Boot 바인더의 기본 `ClassEditor` 가 담당한다. 스타터의 `sentryHub` 는 `Sentry.init(properties)` 직전에 이 집합에서 `Throwable` 이 아닌 클래스만 `removeIf` 로 걸러낸다(그대로 통과).
- **Alternatives considered**: (a) `SentryRequestContextProcessor` 에서 `NoResourceFoundException` 이면 `null` 반환 — 코드 변경·spec Assumptions(프로세서 무수정) 위반이라 기각. (b) `sentry.ignored-errors`(메시지 정규식) — 메시지 텍스트 결합이라 취약. (c) 4xx 전체 drop(PR #257) — 정책상 폐기.

## R2. drop 시점 — 이벤트 프로세서·태그 이전

- **Decision**: 프로세서·핸들러 코드는 무수정.
- **Rationale**: `SentryClient.captureEvent` 는 초입에서 `options.containsIgnoredExceptionForType(event.throwable)` 이 참이면 "Event was dropped as the exception %s is ignored" 를 DEBUG 로 남기고 `SentryId.EMPTY_ID` 를 반환한다. `getEventProcessors()` 순회(`processEvent`)는 그 뒤다. 따라서 `SentryRequestContextProcessor` 의 `ErrorResponse → http.status=404` 분기는 이 예외에 대해 더 이상 호출되지 않지만 다른 `ErrorResponse`(405·415)에는 그대로 적용된다.

## R3. 일치 규칙 — 정확한 클래스

- **Decision**: 클래스 한 개만 등록하면 충분하다. 하위·상위 타입 고려 불필요.
- **Rationale**: `containsIgnoredExceptionForType` 은 `ignoredExceptionsForType.contains(throwable.getClass())` — `getClass()` 정확 일치다. Spring MVC 는 매핑 없는 경로를 `ResourceHttpRequestHandler` 로 보내 `NoResourceFoundException` 을 **그 클래스 그대로** 던진다(KB-465 research 에서 확인: 버전 불일치 매핑도 이 예외 → 404 COMMON-002). 앱이 던지는 "리소스 없음" 은 `BusinessException` 이라 영향 없다(spec Edge Case 충족).

## R4. 다른 캡처 경로 — logback

- **Decision**: 추가 조치 없음.
- **Rationale**: `GlobalExceptionHandler.logFailure` 는 4xx 를 `WARN` 으로만 남긴다(`ERROR` 는 5xx). `sentry.logging.minimum-event-level: error` 이므로 404 는 로그 경로로 이벤트가 되지 않는다. 리졸버 경로(`SentryExceptionResolver`)만 존재하고 R2 로 막힌다.

## R5. 회귀 검증 방식 — 자동 테스트 대신 dev 검증

- **Decision**: 테스트 코드를 두지 않는다. 검증은 (1) 로컬 가짜 DSN + `sentry.debug=true` 로 drop 로그 확인, (2) dev 배포 후 콘솔에서 SC-001~002 확인(quickstart).
- **Rationale**: 사용자 결정 2026-09-09(KB-508) — Sentry 관련 테스트 전부 삭제, 이후 관측 작업에도 미작성. 테스트 컨텍스트는 `sentry.dsn` 부재로 `SentryAutoConfiguration`(`@ConditionalOnProperty("sentry.dsn")`)이 뜨지 않아 SDK 동작을 검증하려면 새 컨텍스트가 필요하고, 그것은 KB-392 단일 컨텍스트 규율을 깬다. 클래스명 오타는 바인딩 실패로 부팅이 멈추므로 시끄럽게 드러난다.
- **Alternatives considered**: yml 문자열 읽기 테스트(삭제된 `SentryConfigTest` 재도입) — 기각. `Binder` 로 `SentryProperties` 바인딩 후 집합 확인 — 같은 성격이라 기각. spec FR-004·SC-003 의 "자동 검증" 은 이 결정으로 충족하지 않으며 plan.md Complexity Tracking 에 기록했다.

**실증(2026-09-10, 로컬 가짜 DSN `https://k@localhost.invalid/0` + `--sentry.debug=true`)**:

```text
GET /api/no-such-path  → 404 COMMON-002
DEBUG: Capturing event: d0ef7b4696fe49678f422d589bb7b3dd
DEBUG: Event was dropped as the exception class org.springframework.web.servlet.resource.NoResourceFoundException is ignored
GET /                  → 404 COMMON-002, 같은 drop 로그
GET /api/foods/999999999?lang=ko → 400 FOOD-001
DEBUG: Capturing event: 756896fce59245ff8f5de6a61b7f33a2   (drop 없음 → 전송 시도, 가짜 호스트라 "Sending the event failed" 는 예상)
```

부팅 시 Sentry 초기화 로그가 정상 출력됐으므로 클래스명 바인딩도 검증됐다.

## R6. yml 표기

- **Decision**: 목록 표기.
  ```yaml
  sentry:
    ignored-exceptions-for-type:
      - org.springframework.web.servlet.resource.NoResourceFoundException
  ```
- **Rationale**: 콤마 구분 문자열도 되지만 목록이 diff·추가에 명확하다. `application-local.yml`·테스트 `application.yml` 에는 두지 않는다(DSN 부재 = 비활성, KB-508 계약 유지). batch `application.yml` 은 웹 경로가 없어 무변경.
