# Research: KB-394 스캔 rate-limit 분리

## R-1. 벤더 429 는 어떤 예외로 도착하는가 (Jira 의 전제 교정)

- **관측**: Spring AI 2.0 `OpenAiChatModel` 은 공식 **openai-java SDK**(`com.openai.client.OpenAIClient`)로 호출한다 — `RestClient`·`RetryTemplate`·`ResponseErrorHandler` 를 전혀 참조하지 않는다(jar 바이트코드 확인). `spring-ai-openai-2.0.0.jar` 의 어떤 클래스도 `TransientAiException`·`ResourceAccessException` 을 참조하지 않는다.
- **결론**: 비전 호출에서 나오는 예외는 SDK 원 예외다 — 429 `com.openai.errors.RateLimitException`, 5xx `InternalServerException`, 네트워크/타임아웃 `OpenAIIoException`, 4xx `BadRequestException`·`UnauthorizedException` 등(전부 `OpenAIServiceException`/`OpenAIException`). Jira 가 말한 "Spring AI 기본 RetryTemplate" 은 이 스택에서 동작하지 않는다.
- **부수 발견(버그)**: `OpenAiMenuBoardVisionExtractor` 의 `catch (TransientAiException)`·`catch (ResourceAccessException)` 는 **죽은 코드**다 — 5xx·타임아웃·연결 실패도 지금은 `catch (Exception)` 으로 떨어져 SCAN-002 로 나간다(SCAN-006 은 페이크로만 도달). Spring AI 2.0 전환 때 생긴 회귀로 보인다. 이번에 SDK 예외 타입으로 바로잡는다(KB-394 의 "장애 갈래 분리" 취지 안).

## R-2. SDK 의 재시도 규칙 (일시/비일시 판별의 실체)

- `RetryingHttpClient.shouldRetry(response)`: 응답 헤더 **`X-Should-Retry`** 가 `true`/`false` 면 그 값을 따르고, 없으면 408·409·429·5xx 를 재시도. 백오프는 `Retry-After-Ms`/`Retry-After` 헤더 우선, 없으면 0.5s×2ⁿ(최대 8s, 지터 25%). 최대 횟수는 `ClientOptions.maxRetries`(기본 **2**) — Spring AI 는 `OpenAiChatOptions.maxRetries` 로 넘기며 우리는 `kbap.llm.vision.max-retries`(`VisionProps.maxRetries: Int?`, 현재 미설정 → 2).
- **일시/비일시 판별**: OpenAI 는 요청 하나가 TPM 을 넘는 429 에 `x-should-retry: false` 를 실어 보낸다 → SDK 가 즉시 던짐(Jira 의 "1초 만에 실패"). RPM 초과 429 는 헤더가 없거나 `true` → SDK 가 `Retry-After`(OpenAI 는 보통 수 초~수십 초) 만큼 자며 최대 2회 재시도 → 소진 후 `RateLimitException`. 따라서 현재 최악 대기 = `min(Retry-After, SDK 상한) × 2` + 호출 시간 — 수십 초~분 단위가 가능하다(Jira 의 "수 분" 경로).
- **예외에서 읽을 수 있는 것**: `RateLimitException.headers().values(name)` 로 헤더를, `RateLimitException.code()` 로 본문 `error.code` 를 읽는다. 공식 헤더 표(rate-limits 가이드): `Retry-After`(초, "The minimum number of seconds to wait before retrying") · `x-ratelimit-limit-requests/tokens` · `x-ratelimit-remaining-requests/tokens` · `x-ratelimit-reset-requests/tokens`(`1s`·`6m0s` 같은 duration 문자열) · `x-ratelimit-*-project-tokens`(프로젝트 한도가 걸린 경우만). 성공 응답에도 같은 헤더가 오지만 Spring AI `ChatModel.call()` 은 HTTP 헤더를 노출하지 않으므로 **429 예외에 실린 헤더만** 쓸 수 있다(사전 스로틀은 범위 밖 — SDK `withRawResponse()` 직접 호출이 필요한 별도 태스크).
- **429 의 세 갈래(공식 error-codes 가이드)**: (a) 속도 초과 `rate_limit_exceeded` — "Pace your requests and follow the `Retry-After` header" → 재시도 대상. (b) 요청 하나가 TPM 을 넘음 — 서버가 `x-should-retry: false` 를 실어 SDK 가 즉시 던짐 → 재시도 무의미("Reduce the `max_tokens`…", 입력 축소가 처방). (c) 잔액·한도 `insufficient_quota`·`credit_balance_exhausted`·`organization/project_spend_limit_exceeded`·`organization_usage_limit_exceeded` — "**Don't retry quota, billing, or other errors that require you to take action.**" `Retry-After` 가 있어도 재시도로 안 풀림 → 재시도 금지·운영자 조치.
- **재시도 정석(공식)**: `Retry-After` 우선, 없으면 지수 백오프 + 지터("Adding random jitter to the delay helps retries from all hitting at the same time"), "Limit both the number of attempts and the total time spent retrying", 그리고 "unsuccessful requests contribute to your per-minute limit, so continuously resending a request won't work" — 짧은 간격의 연타는 한도를 더 소모한다.

## R-3. 재시도 상한을 어디서 거는가

- **Decision**: 비전 호출의 SDK 재시도를 끄고(`maxRetries = 0`) 어댑터가 **예산 기반 재시도**를 직접 돈다 — `kbap.llm.vision.retry-budget`(기본 10s). 규칙: (0) `RateLimitException.code()` 가 잔액·한도 계열이면 재시도 없이 `MenuBoardVisionQuotaExhaustedException(code)`(→ SCAN-006 + ERROR 로그). (1) `x-should-retry=false` → 즉시 `MenuBoardVisionRateLimitedException(retryAfterSeconds, exhausted=false)`. (2) 일시적 429·5xx(`InternalServerException`)·IO(`OpenAIIoException`) → `Retry-After` 가 있으면 그만큼, 없으면 **지수 백오프 + 지터**(0.5s·1s·2s… ×(0.75~1.25)) 만큼 자고 재시도하되, 다음 시도 예상 시각이 예산을 넘으면 중단 — 429 는 `RateLimited(exhausted=true)`, 5xx/IO 는 `MenuBoardVisionUnavailableException`. (3) 그 밖의 4xx 는 그대로 전파(오늘처럼 SCAN-002). 공식 처방 4가지(Retry-After 우선·지터·횟수+총시간 제한·연타 금지)를 예산 하나로 만족한다.
- **Rationale**: SDK 재시도는 총 시간을 제한할 방법이 없다(`Retry-After` 를 그대로 자고 횟수만 센다). 스펙 FR-005/SC-003 은 총 대기 상한(10s)을 요구한다. 자체 루프는 ~20줄이고 `sleep` 을 생성자 주입(`(Duration) -> Unit`, 기본 `Thread.sleep`)하면 Spring 없이 테스트된다. 5xx/IO 도 같은 루프에 태워 SDK 재시도를 끈 대가(일시 장애 복원력)를 잃지 않는다.
- **Alternatives**: SDK `maxRetries` 만 낮춤(1) → `Retry-After` 30s 하나로도 상한 초과 → 기각. `maxRetries=0` + 무재시도(사용자에게 바로 SCAN-008) → 가장 단순하지만 수 초면 풀리는 RPM 버스트도 전부 실패로 보임 → 스펙 US3 미충족. SDK 재시도 유지 + 자체 루프 병행 → 이중 재시도 → 기각.
- **구현 시 확인**: `Headers.values()` 가 대소문자 무관인지(SDK 는 소문자 정규화 — 테스트로 고정), `InternalServerException.builder()` 존재(RateLimitException 과 동일 패턴).

## R-4. 에러코드·응답

- **Decision**: `ErrorCode.SCAN_RATE_LIMITED("SCAN-008", 503, "일시적으로 요청이 많습니다. 잠시 후 다시 시도해 주세요")`. `ScanService` 가 `MenuBoardVisionRateLimitedException` 을 잡아 `BusinessException(SCAN_RATE_LIMITED, payload = retryAfterSeconds?.let { mapOf("retryAfterSeconds" to it) })`. HTTP 503 — SCAN-002/006 과 같은 "재시도 유도" 등급이라 앱 분기(503 모달)에 그대로 얹힌다(스펙 가정).
- **잔액·한도 429** 는 SCAN-008 로 보내면 "잠시 후" 가 거짓이다 — 사용자 관점엔 서버 측 문제이므로 기존 **SCAN-006**(서버 일시 장애, 재시도 모달)으로 보내고, 로그는 `ERROR "메뉴판 비전 quota 소진 — code={}"` 로 남겨 운영 알림 대상으로 만든다. 새 사용자 코드는 만들지 않는다(운영자 조치 없이는 어떤 안내도 사용자가 할 수 있는 게 없다).
- 스캔 횟수: v2 `ScanFacade` 가 `catch (Exception)` 으로 예약을 해제하고 v1 은 성공 후에만 `increaseScanCount` → 추가 코드 없이 FR-002 충족(테스트로 고정).

## R-5. 로그

- **Decision**: `ScanService` 에 `log.warn("메뉴판 비전 rate-limit — kind={} retryAfterSeconds={} limits={} imagePath={}", kind, retryAfterSeconds, e.message, imagePath, e)` — 고정 마커 `rate-limit`, `kind` = `IMMEDIATE`(x-should-retry=false) / `EXHAUSTED`(예산 소진). `limits` 는 어댑터가 예외 메시지로 만든 `limit-requests=… limit-tokens=… remaining-requests=… remaining-tokens=… reset-requests=… reset-tokens=…`(프로젝트 한도 헤더는 있을 때만) — 이것으로 RPM/TPM 중 어느 한도에 걸렸는지 로그만 보고 판정한다. MDC/구조화 필드 추가는 ecs 포맷에서 메시지 안 `key=value` 로도 검색되므로 별도 필드 배선은 하지 않는다. 잔액·한도는 `log.error("메뉴판 비전 quota 소진 — code={} imagePath={}")`.

## R-6. 문서·계약

- `ScanApi`·`ScanV2Api` 의 503 설명과 `errorCodes` 목록에 SCAN-008 추가 → `OpenApiSnapshotTest` 의 스캔 설명 단언에 SCAN-008 추가(에러 코드 표는 enum 전수 검증이라 자동).
- 위키 `scan-credit-limit-design.md`(SCAN-006 설명에 "어댑터가 Spring AI TransientAiException…" 이라 적힌 부분)를 SDK 예외 기준으로 정정, 새 코드·재시도 예산 기록. ADR 없음.

## R-7. 테스트 (원칙 I)

- 어댑터 단위 테스트(`OpenAiMenuBoardVisionExtractorTest`, Spring-free): `RateLimitException.builder().headers(Headers.builder().put("x-should-retry", "false").build()).build()` 를 던지는 `ChatModel` → `MenuBoardVisionRateLimitedException(exhausted=false)`; `Retry-After: 1` 후 성공 → 성공·sleep 1회; 예산 초과 → `exhausted=true`; `InternalServerException` 후 성공 → 성공; 예산 초과 → `MenuBoardVisionUnavailableException`; `BadRequestException` → 그대로 전파.
- 컨트롤러 통합 테스트(`ScanControllerTest`, `FakeMenuBoardVisionExtractor.rateLimitedOn(path, retryAfterSeconds)`): v1·v2 모두 503 `SCAN-008`, v2 횟수 불변, payload `retryAfterSeconds` 유무 2케이스, 기존 SCAN-002/006 케이스 유지.
- Red 순서: 포트 예외·에러코드가 없어 컴파일 실패 → 추가 → 어댑터 테스트 실패(아직 `RuntimeException` 전파) → 루프 구현.
