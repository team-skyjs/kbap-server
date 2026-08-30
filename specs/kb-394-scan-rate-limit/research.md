# Research: KB-394 스캔 rate-limit 분리

## R-1. 벤더 429 는 어떤 예외로 도착하는가 (Jira 의 전제 교정)

- **관측**: Spring AI 2.0 `OpenAiChatModel` 은 공식 **openai-java SDK**(`com.openai.client.OpenAIClient`)로 호출한다 — `RestClient`·`RetryTemplate`·`ResponseErrorHandler` 를 전혀 참조하지 않는다(jar 바이트코드 확인). `spring-ai-openai-2.0.0.jar` 의 어떤 클래스도 `TransientAiException`·`ResourceAccessException` 을 참조하지 않는다.
- **결론**: 비전 호출에서 나오는 예외는 SDK 원 예외다 — 429 `com.openai.errors.RateLimitException`, 5xx `InternalServerException`, 네트워크/타임아웃 `OpenAIIoException`, 4xx `BadRequestException`·`UnauthorizedException` 등(전부 `OpenAIServiceException`/`OpenAIException`). Jira 가 말한 "Spring AI 기본 RetryTemplate" 은 이 스택에서 동작하지 않는다.
- **부수 발견(버그)**: `OpenAiMenuBoardVisionExtractor` 의 `catch (TransientAiException)`·`catch (ResourceAccessException)` 는 **죽은 코드**다 — 5xx·타임아웃·연결 실패도 지금은 `catch (Exception)` 으로 떨어져 SCAN-002 로 나간다(SCAN-006 은 페이크로만 도달). Spring AI 2.0 전환 때 생긴 회귀로 보인다. 이번에 SDK 예외 타입으로 바로잡는다(KB-394 의 "장애 갈래 분리" 취지 안).

## R-2. SDK 의 재시도 규칙 (일시/비일시 판별의 실체)

- `RetryingHttpClient.shouldRetry(response)`: 응답 헤더 **`X-Should-Retry`** 가 `true`/`false` 면 그 값을 따르고, 없으면 408·409·429·5xx 를 재시도. 백오프는 `Retry-After-Ms`/`Retry-After` 헤더 우선, 없으면 0.5s×2ⁿ(최대 8s, 지터 25%). 최대 횟수는 `ClientOptions.maxRetries`(기본 **2**) — Spring AI 는 `OpenAiChatOptions.maxRetries` 로 넘기며 우리는 `kbap.llm.vision.max-retries`(`VisionProps.maxRetries: Int?`, 현재 미설정 → 2).
- **일시/비일시 판별**: OpenAI 는 요청 하나가 TPM 을 넘는 429 에 `x-should-retry: false` 를 실어 보낸다 → SDK 가 즉시 던짐(Jira 의 "1초 만에 실패"). RPM 초과 429 는 헤더가 없거나 `true` → SDK 가 `Retry-After`(OpenAI 는 보통 수 초~수십 초) 만큼 자며 최대 2회 재시도 → 소진 후 `RateLimitException`. 따라서 현재 최악 대기 = `min(Retry-After, SDK 상한) × 2` + 호출 시간 — 수십 초~분 단위가 가능하다(Jira 의 "수 분" 경로).
- **예외에서 읽을 수 있는 것**: `RateLimitException.headers().values(name)` 로 `x-should-retry`·`retry-after`·`retry-after-ms`·`x-ratelimit-remaining-requests/tokens`·`x-ratelimit-reset-*` 를 읽는다. 갈래 판정 = `x-should-retry == "false"` → 즉시 거절(비일시적), 그 외 → 일시적.

## R-3. 재시도 상한을 어디서 거는가

- **Decision**: 비전 호출의 SDK 재시도를 끄고(`maxRetries = 0`) 어댑터가 **예산 기반 재시도**를 직접 돈다 — `kbap.llm.vision.retry-budget`(기본 10s). 규칙: (1) `RateLimitException` 이고 `x-should-retry=false` → 즉시 `MenuBoardVisionRateLimitedException(retryAfterSeconds, exhausted=false)`. (2) 일시적 429·5xx(`InternalServerException`)·IO(`OpenAIIoException`) → `Retry-After`(없으면 1s) 만큼 자고 재시도하되, 다음 시도 예상 시각이 예산을 넘으면 중단 — 429 는 `RateLimited(exhausted=true)`, 5xx/IO 는 `MenuBoardVisionUnavailableException`. (3) 그 밖의 4xx 는 그대로 전파(오늘처럼 SCAN-002).
- **Rationale**: SDK 재시도는 총 시간을 제한할 방법이 없다(`Retry-After` 를 그대로 자고 횟수만 센다). 스펙 FR-005/SC-003 은 총 대기 상한(10s)을 요구한다. 자체 루프는 ~20줄이고 `sleep` 을 생성자 주입(`(Duration) -> Unit`, 기본 `Thread.sleep`)하면 Spring 없이 테스트된다. 5xx/IO 도 같은 루프에 태워 SDK 재시도를 끈 대가(일시 장애 복원력)를 잃지 않는다.
- **Alternatives**: SDK `maxRetries` 만 낮춤(1) → `Retry-After` 30s 하나로도 상한 초과 → 기각. `maxRetries=0` + 무재시도(사용자에게 바로 SCAN-008) → 가장 단순하지만 수 초면 풀리는 RPM 버스트도 전부 실패로 보임 → 스펙 US3 미충족. SDK 재시도 유지 + 자체 루프 병행 → 이중 재시도 → 기각.
- **구현 시 확인**: `Headers.values()` 가 대소문자 무관인지(SDK 는 소문자 정규화 — 테스트로 고정), `InternalServerException.builder()` 존재(RateLimitException 과 동일 패턴).

## R-4. 에러코드·응답

- **Decision**: `ErrorCode.SCAN_RATE_LIMITED("SCAN-008", 503, "일시적으로 요청이 많습니다. 잠시 후 다시 시도해 주세요")`. `ScanService` 가 `MenuBoardVisionRateLimitedException` 을 잡아 `BusinessException(SCAN_RATE_LIMITED, payload = retryAfterSeconds?.let { mapOf("retryAfterSeconds" to it) })`. HTTP 503 — SCAN-002/006 과 같은 "재시도 유도" 등급이라 앱 분기(503 모달)에 그대로 얹힌다(스펙 가정).
- 스캔 횟수: v2 `ScanFacade` 가 `catch (Exception)` 으로 예약을 해제하고 v1 은 성공 후에만 `increaseScanCount` → 추가 코드 없이 FR-002 충족(테스트로 고정).

## R-5. 로그

- **Decision**: `ScanService` 에 `log.warn("메뉴판 비전 rate-limit — kind={} retryAfterSeconds={} imagePath={}", kind, retryAfterSeconds, imagePath, e)` — 고정 마커 `rate-limit`, `kind` = `IMMEDIATE`(x-should-retry=false) / `EXHAUSTED`(예산 소진). 남은 한도 헤더는 예외 메시지에 실어(어댑터에서 `x-ratelimit-remaining-*` 를 메시지 문자열로) 스택트레이스에 남긴다 — MDC/구조화 필드 추가는 ecs 포맷에서 메시지 안 `key=value` 로도 검색되므로 별도 필드 배선은 하지 않는다.

## R-6. 문서·계약

- `ScanApi`·`ScanV2Api` 의 503 설명과 `errorCodes` 목록에 SCAN-008 추가 → `OpenApiSnapshotTest` 의 스캔 설명 단언에 SCAN-008 추가(에러 코드 표는 enum 전수 검증이라 자동).
- 위키 `scan-credit-limit-design.md`(SCAN-006 설명에 "어댑터가 Spring AI TransientAiException…" 이라 적힌 부분)를 SDK 예외 기준으로 정정, 새 코드·재시도 예산 기록. ADR 없음.

## R-7. 테스트 (원칙 I)

- 어댑터 단위 테스트(`OpenAiMenuBoardVisionExtractorTest`, Spring-free): `RateLimitException.builder().headers(Headers.builder().put("x-should-retry", "false").build()).build()` 를 던지는 `ChatModel` → `MenuBoardVisionRateLimitedException(exhausted=false)`; `Retry-After: 1` 후 성공 → 성공·sleep 1회; 예산 초과 → `exhausted=true`; `InternalServerException` 후 성공 → 성공; 예산 초과 → `MenuBoardVisionUnavailableException`; `BadRequestException` → 그대로 전파.
- 컨트롤러 통합 테스트(`ScanControllerTest`, `FakeMenuBoardVisionExtractor.rateLimitedOn(path, retryAfterSeconds)`): v1·v2 모두 503 `SCAN-008`, v2 횟수 불변, payload `retryAfterSeconds` 유무 2케이스, 기존 SCAN-002/006 케이스 유지.
- Red 순서: 포트 예외·에러코드가 없어 컴파일 실패 → 추가 → 어댑터 테스트 실패(아직 `RuntimeException` 전파) → 루프 구현.
