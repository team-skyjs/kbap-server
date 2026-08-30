# Data Model: KB-394 (영속 변경 없음)

## 예외 → 에러코드 매핑 (비전 호출 실패)

| 벤더/SDK 예외 | 헤더 | 어댑터 동작 | 포트 예외 | ScanService → 코드 | 횟수 |
|---|---|---|---|---|---|
| `RateLimitException`(429) | 본문 `error.code` ∈ 잔액·한도(`insufficient_quota`·`credit_balance_exhausted`·`*_spend_limit_exceeded`·`organization_usage_limit_exceeded`) | 즉시(재시도 금지) | `MenuBoardVisionQuotaExhaustedException(code)` | SCAN-006 503 + **ERROR** 로그 | 미차감 |
| `RateLimitException`(429) | `x-should-retry: false` | 즉시 | `MenuBoardVisionRateLimitedException(retryAfterSeconds, exhausted=false)` | **SCAN-008** 503 | 미차감 |
| `RateLimitException`(429) | 없음/`true` | `Retry-After`(없으면 지수 백오프+지터) 만큼 대기 후 재시도, 예산(10s) 초과 시 중단 | `…RateLimitedException(retryAfterSeconds, exhausted=true)` | **SCAN-008** 503 | 미차감 |
| `InternalServerException`(5xx) | — | 예산 내 재시도 | `MenuBoardVisionUnavailableException` | SCAN-006 503 | 미차감 |
| `OpenAIIoException`(타임아웃·연결) | — | 예산 내 재시도(1s 간격) | `MenuBoardVisionUnavailableException` | SCAN-006 503 | 미차감 |
| 그 외 4xx(`BadRequest`·`Unauthorized`…)·파싱 실패 | — | 전파 | (원 예외) | SCAN-002 503 | 미차감 |
| 정상 응답, 메뉴 없음 | — | — | — | SCAN-003 400 (v2 `requireDetectedMenu`) | 미차감 |

현행(변경 전): 1~4행 전부 → `catch (Exception)` → SCAN-002.

## 설정

| 키 | 타입 | 기본 | 의미 |
|---|---|---|---|
| `kbap.llm.vision.max-retries` | Int | **0**(변경: 종전 미설정 → SDK 기본 2) | SDK 자체 재시도 횟수. 0 으로 두고 어댑터 예산 루프가 대신한다 |
| `kbap.llm.vision.retry-budget` | Duration | `10s` | 일시적 429·5xx·IO 에 대해 어댑터가 재시도에 쓰는 총 시간 |

## 로그 (ScanService WARN)

`메뉴판 비전 rate-limit — kind=IMMEDIATE|EXHAUSTED retryAfterSeconds=<n|null> limits=limit-requests=… limit-tokens=… remaining-requests=… remaining-tokens=… reset-requests=… reset-tokens=… imagePath=<path>`(프로젝트 한도 헤더는 있을 때만). 잔액·한도: `ERROR 메뉴판 비전 quota 소진 — code=<code> imagePath=<path>`. 기존 "메뉴판 비전 인식 실패"/"메뉴판 비전 서버 장애" 는 그대로.
