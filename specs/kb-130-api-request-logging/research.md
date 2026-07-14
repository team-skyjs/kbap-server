# Research: API 요청 흐름 로깅 (KB-130)

**Date**: 2026-07-14 | **Spec**: [spec.md](spec.md)

## D1. 상관 컨텍스트 전파 — SLF4J MDC

- **Decision**: 상관 키·회원 식별자는 **SLF4J MDC**(`requestId`·`memberId` 키)로 전파한다.
- **Rationale**: FR-002(모든 로그에 키 자동 포함, 수동 전달 금지)를 만족하는 유일한 무침투 수단. 도메인·application 코드는 한 줄도 바뀌지 않고, 로그 출력 계층(패턴/인코더)이 MDC 를 읽는다. ThreadLocal 기반이므로 요청-스레드 생명주기와 일치하며, `:app:api` 의 요청 처리 경로는 전부 동기라 스레드 전환 유실이 없다.
- **Alternatives considered**:
  - *Micrometer Tracing (traceId 자동)* — 단일 서비스에 분산 추적 스택(bridge + 전파 규약)은 과함. APM/MSA 도입 시점에 승격.
  - *상관 키 파라미터 수동 전달 / 자체 ThreadLocal* — MDC 재발명. FR-002 위반이거나 전 계층 시그니처 오염.
- **주의(후속)**: `@Async`·`CompletableFuture`·가상스레드 fan-out 등 스레드 전환이 api 에 도입되면 MDC 전파 데코레이터가 필요하다(현재 해당 경로는 `:app:batch` 뿐 — 범위 밖).

## D2. 상관 키 형식 — UUID v4

- **Decision**: `UUID.randomUUID().toString()`.
- **Rationale**: stdlib 한 줄, 전역 유일(SC-002), 수집기·grep 어디서나 검색 가능. 길이가 부담이면 나중에 단축 — 지금은 아님.
- **Alternatives considered**: 시간 정렬 ID(ULID 등 — 의존성 추가), 8자 랜덤 hex(충돌 확률 계산 필요). 이득 없음.

## D3. 진입/응답 로그 지점 — 서블릿 필터(최전방)

- **Decision**: `OncePerRequestFilter` 구현 `RequestLoggingFilter` 를 `FilterRegistrationBean` 으로 **`Ordered.HIGHEST_PRECEDENCE`** 에 등록, URL 패턴 `/api/*`(FR-007 — actuator·springdoc 자동 제외). 책임: UUID 생성 → `MDC.put` → 응답 헤더 `X-Request-Id` → 진입 로그 → `chain.doFilter` → 응답 로그(상태·소요·회원) → `finally { MDC.clear() }`.
- **Rationale**: 필터가 가장 바깥이어야 (1) JWT 필터의 401 거절도 상관 키를 갖고(엣지케이스), (2) 소요 시간이 전체 처리를 덮으며, (3) `finally` 정리로 스레드풀 재사용 오염(FR-006)을 원천 차단한다. 기존 `JwtAuthenticationFilter` 등록(기본 order = `LOWEST_PRECEDENCE`)보다 항상 먼저 실행된다.
- **Alternatives considered**:
  - *HandlerInterceptor* — DispatcherServlet 안쪽이라 필터 단계 거절(401)·서블릿 에러를 못 덮는다.
  - *Spring `CommonsRequestLoggingFilter`* — 상관 키·응답 상태·소요 시간 개념이 없어 결국 커스텀 필요.
  - *AOP* — 컨트롤러 진입 이후만 커버, 요청 생명주기와 불일치.

## D4. 회원 식별자 MDC 주입 — JwtAuthenticationFilter 한 줄

- **Decision**: `JwtAuthenticationFilter` 가 토큰 파싱 성공 직후 `MDC.put("memberId", ...)` 한 줄 추가. 정리는 바깥 `RequestLoggingFilter` 의 `MDC.clear()` 가 일괄 담당.
- **Rationale**: 회원 식별이 확정되는 유일 지점이 이 필터다. 별도 필터/리졸버를 만들면 실행 순서 관리만 늘어난다. JWT 필터 미적용 경로(비인증 API)는 자연히 `memberId` 없이 남는다(FR-003).
- **Alternatives considered**: 응답 시점에 request attribute 에서만 읽기 — 응답 로그에는 충분하지만 처리 중간 로그(에러 포함)에 memberId 가 빠져 US2 미충족.

## D5. JSON 구조화 로그 — Spring Boot 내장 structured logging (신규 의존성 0)

- **Decision**: staging/prod 프로필 yml 에 **`logging.structured.format.console: ecs`** 한 줄. Boot 4.1 내장 기능으로 MDC(requestId·memberId)가 JSON 필드로 자동 포함된다. 상태·소요 시간 등 이벤트 필드는 SLF4J 2 fluent API(`atInfo().addKeyValue(...)`)로 실어 ECS 출력에 필드로 나가게 한다.
- **Rationale**: logstash-logback-encoder 를 추가하는 게 관례였지만 Boot 3.4+ 가 ECS/logstash/gelf 포맷을 내장했다 — 의존성 0, yml 1줄. 수집기 미확정 상태에서 ECS 는 가장 무난한 기본값(수집기 확정 시 포맷 값만 교체).
- **Alternatives considered**:
  - *logstash-logback-encoder* — 신규 의존성 + logback xml 커스텀. 내장 기능이 있는데 추가할 이유 없음.
  - *전 환경 JSON* — 로컬 가독성 훼손(clarify Q2 에서 기각).
- **구현 중 정정 (검증 결과)**: 애초 가정("`base.xml` include 뿐이니 structured 가 적용된다")은 **틀렸다**. Boot 의 `base.xml` 은 패턴 인코더를 쓰는 `console-appender.xml` 을 하드 include 하며, 커스텀 logback 설정이 존재하면 Boot 은 structured 인코더(`structured-console-appender.xml`)를 자동 적용하지 않는다 → `logging.structured.format.console` 이 **조용히 무시**된다. 해결: `app/api/src/main/resources/logback-spring.xml` 을 **삭제**했다(내용이 `base.xml` include 한 줄뿐이라 Boot 기본 초기화와 동등하고, 기본 초기화는 correlation 패턴과 structured 포맷을 모두 처리한다). 부수효과로 `base.xml` 이 붙이던 FILE 어펜더(`${java.io.tmpdir}/spring.log`)가 사라지는데, 로그 파이프라인은 stdout 기준이라 무해하다. 회귀 방지: `LogOutputConfigTest`(커스텀 logback 설정 부재 가드) + `StructuredConsoleLoggingTest`(ECS 인코더가 `requestId`·`memberId` 를 JSON 필드로 출력).

## D6. local/dev 텍스트 로그 — `logging.pattern.correlation`

- **Decision**: 베이스 `application.yml` 에 `logging.pattern.correlation: "[%X{requestId:-}][%X{memberId:-}] "`. Boot 기본 콘솔 패턴의 correlation 자리에 삽입돼 **logback xml 없이** 전 로그 라인에 두 필드가 붙는다(D5 정정에 따라 `logback-spring.xml` 은 삭제됨).
- **Rationale**: 패턴 전체를 재정의(`logging.pattern.console`)하면 Boot 기본 포맷(색상·정렬)을 통째로 복붙해야 한다. correlation 프로퍼티는 정확히 이 용도로 존재.
- **Alternatives considered**: `logback-spring.xml` 에 springProfile 별 패턴 — 파일 수정 범위만 커짐.

## D7. 에러 상세 표준화 — GlobalExceptionHandler 보강 + catch-all

- **Decision**: (1) `GlobalExceptionHandler` 의 기존 핸들러 로그를 표준 형식(예외 타입·`ErrorCode.code`·HTTP 상태·요청 URI)으로 통일하고, (2) **catch-all `@ExceptionHandler(Exception::class)`** 를 신설해 미처리 예외도 표준 에러 로그 + `BaseResponse` 봉투(500)로 응답한다. `ErrorCode` 에 `INTERNAL_SERVER_ERROR("COMMON-003", 500, ...)` 채번.
- **Rationale**: FR-010. 현재 미처리 예외는 Spring 기본 에러 응답으로 새어 나가 API 응답 규약(모든 응답 = `BaseResponse`)도 깨고 표준 에러 로그도 없다. 요청 URI 는 핸들러에서 `HttpServletRequest` 주입으로 얻는다.
- **Alternatives considered**: 필터에서 예외를 잡아 로깅 — `@RestControllerAdvice` 가 이미 예외→응답 변환 지점이므로 로그도 같은 곳이 응집적. 필터 응답 로그는 상태 코드만 기록(중복 스택 로그 없음).

## D8. 쿼리 파라미터 마스킹 — 필터 내 상수 목록

- **Decision**: `RequestLoggingFilter` 안에 `MASKED_QUERY_PARAMS: Set<String>`(현재 `emptySet()`) 과 마스킹 함수를 두고, 진입 로그의 경로를 `path?key=value` 로 기록하되 목록에 오른 파라미터 값만 `***` 로 치환.
- **Rationale**: clarify Q3 결정. 목록이 비어 있어도 메커니즘을 지금 두는 게 사용자 선택 — 순수 함수라 단위 테스트로 고정한다.

## D9. 테스트 전략

- **Decision**:
  - *순수 로직 단위 테스트*(Kotest BehaviorSpec): 쿼리 마스킹 함수.
  - *MockMvc 통합 테스트*(`@SpringBootTest` + `@AutoConfigureMockMvc`): Logback `ListAppender` 를 대상 로거에 부착해 — 진입/응답 로그 쌍·동일 requestId·`X-Request-Id` 응답 헤더·인증 요청의 memberId 포함·비인증 요청의 memberId 부재·예외 시 표준 에러 로그+응답 로그·요청 종료 후 MDC 청소를 검증.
- **Rationale**: 헌법 원칙 I. 로그는 부수효과라 ListAppender 캡처가 표준 검증 수단. 동시성(SC-002)은 UUID 유일성이 보장하므로 별도 부하 테스트는 두지 않는다.
