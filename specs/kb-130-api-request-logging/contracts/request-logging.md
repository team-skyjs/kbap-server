# Contract: 요청 흐름 로깅 (KB-130)

## 1. HTTP 계약 — 응답 헤더

모든 `/api/*` 응답(성공·실패·401 필터 거절 포함)에 상관 키 헤더가 실린다.

```
X-Request-Id: 550e8400-e29b-41d4-a716-446655440000
```

- 값: 서버 생성 UUID v4. 클라이언트가 보낸 동명 헤더는 무시한다(서버 생성 단일 출처).
- 기존 API 의 상태 코드·본문(`BaseResponse` 봉투)은 무변경. 유일한 응답 계약 변경: **미처리 예외가 Spring 기본 에러 응답 대신 `BaseResponse.fail("COMMON-003", ...)` + HTTP 500** 으로 표준화된다.

## 2. 에러 코드 채번

| code | status | 용도 |
|------|--------|------|
| `COMMON-003` | 500 | 미처리 예외(catch-all) — 클라이언트는 재시도/문의 안내 분기 |

## 3. 로그 출력 계약

### local/dev (텍스트 한 줄)

Boot 기본 패턴의 correlation 자리에 삽입:

```
2026-07-14T12:00:00.000+09:00  INFO 123 --- [nio-8080-exec-1] [550e8400-...][42] c.k.a.a.c.l.RequestLoggingFilter : --> GET /api/v1/members/me/profile
2026-07-14T12:00:00.012+09:00  INFO 123 --- [nio-8080-exec-1] [550e8400-...][42] c.k.a.a.c.l.RequestLoggingFilter : <-- 200 GET /api/v1/members/me/profile (12ms)
```

- `[requestId][memberId]` — memberId 부재 시 빈 칸(`%X{memberId:-}`).

### staging/prod (ECS JSON — Boot 내장 structured logging)

```json
{"@timestamp":"...","log.level":"INFO","message":"<-- 200 GET /api/v1/members/me/profile (12ms)",
 "requestId":"550e8400-...","memberId":"42","status":200,"elapsedMs":12,
 "ecs.version":"8.11","log.logger":"...RequestLoggingFilter","process.thread.name":"..."}
```

- MDC(`requestId`·`memberId`)는 최상위 필드로 자동 포함, `status`·`elapsedMs`·`errorCode`·`uri` 등 이벤트 필드는 SLF4J `addKeyValue` 로 포함.
- 수집기는 `requestId`·`memberId`·`status`·`elapsedMs` 필드 단위 검색 가능(FR-005).

## 4. 로그 이벤트 계약

| 이벤트 | 로거 | 레벨 | 필수 필드 |
|--------|------|------|-----------|
| 진입 | `RequestLoggingFilter` | INFO | requestId, method, path(+마스킹 쿼리) |
| 응답 | `RequestLoggingFilter` | INFO | requestId, status, elapsedMs (인증 시 memberId) |
| 에러 상세 | `GlobalExceptionHandler` | WARN(4xx) / ERROR(5xx, 스택 포함) | requestId, 예외 타입, errorCode, status, uri |

- 대상: `/api/*` 만. actuator·springdoc 은 진입/응답 로그를 남기지 않는다(FR-007).
- 금지: 인증 토큰·요청/응답 본문·`MASKED_QUERY_PARAMS` 원문 값(FR-008).
