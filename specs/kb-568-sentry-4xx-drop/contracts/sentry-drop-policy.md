# Contract: Sentry 수집 정책 (api)

HTTP API 계약 변경 없음. 계약은 (1) 프로세서의 이벤트 판정, (2) 관측 문서 두 가지다.

## 1. `SentryRequestContextProcessor.process(event, hint): SentryEvent?`

| 입력 throwable | 반환 | 검증 관점(자동 테스트 없음 — 리뷰·dev 검증 기준) |
|---|---|---|
| `BusinessException(INVALID_ACCESS_TOKEN)` (401) | `null` | 4xx 앱 에러 코드 drop |
| `BusinessException(CONFLICT)` (409) | `null` | 앱 에러 코드 409 drop |
| `BusinessException(SOCIAL_ACCOUNT_DELETE_FAILED)` (500) | event, `http.status=500`, `error.code=AUTH-007`, fingerprints `["business","AUTH-007"]` | 5xx 앱 에러 코드 통과 |
| `NoResourceFoundException(GET, "/", "/")` (404) | `null` | 정적 404 drop(SDK 설정이 먼저 버리지만 프로세서도 같은 판정) |
| `IllegalArgumentException` (400) | `null` | 바인딩 실패 drop |
| `RuntimeException` | event, `http.status=500` | 예상 밖 예외 통과 |
| `RuntimeException(cause = OptimisticLockingFailureException)` | event, `http.status=409` | 낙관적 락 통과 |
| `ClientAbortException(IOException("Broken pipe"))` | `null` | 인바운드 끊김 drop |
| `RuntimeException(cause = AsyncRequestNotUsableException)` | `null` | 비동기 응답 불가 drop |
| `IOException("Connection reset")` (아웃바운드) | event, `http.status=500` | 메시지로 오인하지 않음 |
| `RuntimeException(cause = IOException("Broken pipe"))` (아웃바운드) | event, `http.status=500` | 메시지로 오인하지 않음 |
| `null` (로그 이벤트) | event | 통과 |

불변: 통과 이벤트의 `requestId`·`memberId` 태그, `Authorization` 헤더 제거, 쿼리 마스킹은 종전과 동일.

## 2. `docs/observability/sentry.md`

- 첫 문단 "핸들러 예외(4xx·5xx 전부)" → "5xx·낙관적 락 409·예상 밖 예외와 ERROR 로그·배치 잡 실패" 로 수정.
- "수집되지 않는 것" 절: 필터 단계 401/400(종전) + **모든 4xx(앱 에러 코드 4xx·검증·파라미터 누락·타입 불일치·API 버전 헤더·405·415)** + **인바운드 클라이언트 끊김(`ClientAbortException`·`AsyncRequestNotUsableException`)** + 매핑 없는 404(PR #258, SDK 설정). "405·415·앱 에러 코드가 붙은 4xx 는 계속 수집한다" 문장 삭제.
- 아웃바운드 I/O 실패(DB·S3·LLM "Broken pipe"/"Connection reset")는 500 으로 계속 수집한다는 문장 추가.
- "후속·조정" 의 알림 안내 "4xx 를 빼려면 조건 `http.status` starts with 5" 삭제(이벤트 자체가 없다).
- batch 항목: "HTTP 경계가 없어 4xx·끊김 개념이 없다 — 변경 없음(KB-568 결정)" 한 줄.
- 제목의 이슈 키에 KB-568 병기.
