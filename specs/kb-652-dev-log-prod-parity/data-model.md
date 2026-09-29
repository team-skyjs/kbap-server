# Data Model: dev 로그 출력을 prod 와 동일하게

**스키마·엔티티·코드 변경 없음.** 바뀌는 것은 프로필 설정 값과 테스트의 프로필 목록뿐이다.

## 프로필별 로그 설정 (변경 전 → 후)

| 앱 | 프로필 | `logging.structured.format.console` | `spring.jpa.show-sql` | 변경 |
|----|--------|--------------------------------------|------------------------|------|
| api | local | (없음, 텍스트) | true | 없음 |
| api | **dev** | (없음) → **ecs** | true → **false** | ✔ |
| api | staging | ecs | false | 없음 |
| api | prod | ecs | false | 없음 |
| batch | local | (없음) | true | 없음 |
| batch | **dev** | (없음) | true → **false** | ✔ |
| batch | staging | (없음) | false | 없음 |
| batch | prod | (없음) | false | 없음 |

변경 후 dev 행은 각 앱의 prod 행과 같다.

## 로그 줄 (api, dev 변경 후 = prod)

ECS 형식 JSON 한 줄. Boot 구조화 로깅이 MDC 를 최상위 필드로 넣으므로 아래 필드가 보장된다.

| 필드 | 출처 | 비고 |
|------|------|------|
| `@timestamp`·`log.level`·`message`·`log.logger`·`process.thread.name` | Boot ecs 인코더 | 표준 ECS |
| `requestId` | `RequestLoggingFilter` MDC | 요청 상관 키. 수집기 검색 키 |
| `memberId` | `RequestLoggingFilter` MDC | 인증 요청만. 비회원은 빈 값 |
| `error.*` | 예외 시 | 스택 포함 |

`logging.pattern.correlation` 의 `[reqId=…][memberId=…]` 접두는 텍스트 패턴 전용이라 JSON 출력에는 나타나지 않는다(prod 와 동일).

## 검증 규칙

- FR-001·FR-002: api dev yml 두 키가 prod yml 과 값이 같다.
- FR-003: batch dev yml 의 `show-sql` 이 false, `logging.structured` 는 없다.
- FR-004·FR-005: local·staging·prod yml 4파일(api 3 + batch 3 중 local·staging·prod) diff 0.
- FR-006: `StructuredConsoleLoggingTest` 프로필 목록에 `dev` 포함.
