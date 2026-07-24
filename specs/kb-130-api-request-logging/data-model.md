# Data Model: API 요청 흐름 로깅 (KB-130)

영속 엔티티·스키마 변경 없음 — 데이터는 로그 스트림상의 레코드뿐이다.

## MDC 컨텍스트 (요청 스코프)

| 키 | 타입 | 세팅 지점 | 정리 지점 | 비고 |
|----|------|-----------|-----------|------|
| `requestId` | String (UUID v4) | `RequestLoggingFilter` 진입 시 | `RequestLoggingFilter` `finally` (`MDC.clear()`) | 모든 `/api/*` 요청에 존재 |
| `memberId` | String (Long 문자열) | `JwtAuthenticationFilter` 파싱 성공 시 | 위와 동일(일괄 clear) | JWT 필터 미적용/실패 경로엔 부재 |

## 로그 이벤트 3종

### 1. 진입 로그 (`RequestLoggingFilter`, INFO)

| 필드 | 예 | 전달 방식 |
|------|----|-----------|
| method | `GET` | 메시지 + key-value |
| path(+마스킹된 쿼리) | `/api/v1/foods/search?keyword=kimchi` | 메시지 + key-value |

### 2. 응답 로그 (`RequestLoggingFilter`, INFO)

| 필드 | 예 | 전달 방식 |
|------|----|-----------|
| method·path | `GET /api/v1/foods/search` | 메시지 |
| status | `200` | key-value (`addKeyValue`) |
| elapsedMs | `12` | key-value (`addKeyValue`) |

### 3. 표준 에러 로그 (`GlobalExceptionHandler`, WARN 4xx / ERROR 5xx)

| 필드 | 예 | 전달 방식 |
|------|----|-----------|
| 예외 타입 | `BusinessException` | 메시지 + key-value |
| errorCode | `MEMBER-003` (미처리 예외는 `COMMON-003`) | key-value |
| status | `400` | key-value |
| uri | `/api/v1/members/me/profile` | key-value |
| 스택트레이스 | — | 5xx 만 포함 |

## 검증 규칙 (스펙 FR 매핑)

- 한 요청의 이벤트 1·2·3(발생 시)은 같은 `requestId` 를 가진다 — FR-001·002.
- 이벤트 2는 에러 응답을 포함해 요청당 정확히 1회 — FR-004, SC-004.
- `MASKED_QUERY_PARAMS`(현재 빈 Set)에 오른 파라미터 값은 `***` 치환 — FR-008.
- 요청 종료 후 MDC 는 비어 있다(다음 요청 오염 금지) — FR-006.

## 상태 전이

없음 — 무상태 로깅.
