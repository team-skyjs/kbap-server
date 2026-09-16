# Data Model: Sentry 4xx·클라이언트 끊김 이벤트 미전송

영속 엔티티·스키마 변경 없음. 모델은 프로세서의 **판정 규칙** 하나다.

## 판정 입력

| 입력 | 출처 |
|---|---|
| `event.throwable` | 리졸버 경로(`SentryExceptionResolver`) 또는 logback 경로(`SentryAppender`)가 실은 원인 예외. 없을 수 있음 |
| 원인 체인 | `generateSequence(throwable) { it.cause }` |

## 판정 규칙 (위에서부터 첫 일치)

| # | 조건 | 결과 | 태그 |
|---|---|---|---|
| 1 | `throwable == null` | 통과 | 종전(requestId·memberId·마스킹만) |
| 2 | 원인 체인에 `ClientAbortException` 또는 `AsyncRequestNotUsableException` | **drop** | — |
| 3 | `httpStatusOf` ∈ 400..499 이고 원인 체인에 낙관적 락 예외 없음 | **drop** | — |
| 4 | 그 외(5xx, 낙관적 락 409) | 통과 | `http.status`; `BusinessException` 이면 `error.code` + 핑거프린트 `["business", code]` |

## `httpStatusOf` (종전 `http.status` 매핑과 동일)

| throwable | status |
|---|---|
| `BusinessException` | `errorCode.status` |
| `ErrorResponse`(Spring MVC 4xx 전부 — 검증·파라미터 누락·405·415·API 버전 예외·`NoResourceFoundException`) | `statusCode.value()` |
| `IllegalArgumentException` · `HttpMessageNotReadableException` · `MethodArgumentTypeMismatchException` | 400 |
| 그 외 | 낙관적 락 cause 있으면 409, 없으면 500 |

## 불변

- 규칙 2·3 에서 drop 된 이벤트에는 태그를 달지 않는다(버릴 것에 작업 안 함).
- 규칙 4 의 태그·핑거프린트는 KB-508 계약과 바이트 단위로 동일하다.
- HTTP 응답·서버 로그는 이 규칙의 영향을 받지 않는다(프로세서는 전송 직전에만 관여).
