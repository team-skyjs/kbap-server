# Contract: Sentry 무시 예외 설정

## 1. `api/src/main/resources/application.yml`

기존 `sentry:` 블록(KB-508 contracts §2)에 한 항목을 추가한다. 다른 키는 그대로.

```yaml
sentry:
  dsn: ${API_SENTRY_DSN:}
  environment: ${SPRING_PROFILES_ACTIVE:local}
  release: ${SENTRY_RELEASE:}
  send-default-pii: true
  exception-resolver-order: -2147483648
  ignored-exceptions-for-type:
    - org.springframework.web.servlet.resource.NoResourceFoundException
  tags:
    service: api
  logging:
    minimum-event-level: error
    minimum-breadcrumb-level: info
```

- `application-local.yml`·`api/src/test/resources/application.yml`·batch `application.yml` 무변경.
- `exception-resolver-order` 는 손대지 않는다(5xx·그 외 4xx 캡처 경로 보존).

## 2. `docs/observability/sentry.md`

- "**수집되지 않는 것**" 문단에 추가: 매핑되지 않은 경로의 404(`NoResourceFoundException` — 봇 스캔·오타 URL) 은 `sentry.ignored-exceptions-for-type` 으로 SDK 가 프로세서 이전에 버린다. 405·415·앱 에러 코드 4xx 는 계속 수집.
- "후속·조정 → 노이즈" 항목 갱신: **예외 종류** 단위 제외는 `ignored-exceptions-for-type`(정확한 클래스 일치), **에러 코드** 단위 제외는 프로세서 `null` 반환, 양이 많으면 `sample-rate`.
- 태그 표 `http.status` 행의 "Spring `ErrorResponse` 상태" 설명은 유지(405·415 에 여전히 적용).

## 3. 코드 계약

- `SentryRequestContextProcessor`·`GlobalExceptionHandler`·`WebConfig` 무수정.
- HTTP 응답 무변경: 매핑 없는 경로는 종전과 같이 404 + `BaseResponse.fail("COMMON-002", ...)`.

## 4. 티켓·PR

- PR 은 KB-510 에 연결(제목/본문 `Refs KB-510`). KB-510 코멘트로 범위 축소(4xx 전체 drop 폐기 → `NoResourceFoundException` 한 종류) 기록.
