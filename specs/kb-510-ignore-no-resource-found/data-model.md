# Data Model: Sentry 존재하지 않는 경로 404 미전송

엔티티·스키마·영속 변경 없음. 모델은 설정 값 하나뿐이다.

| 항목 | 값 |
|---|---|
| 설정 키 | `sentry.ignored-exceptions-for-type` (api `application.yml`) |
| 타입 | `Set<Class<out Throwable>>` — `io.sentry.SentryOptions.getIgnoredExceptionsForType()` (getter 전용, Boot 바인더가 병합) |
| 값 | `org.springframework.web.servlet.resource.NoResourceFoundException` 1개 |
| 판정 | `throwable.getClass()` 정확 일치 → `SentryClient.captureEvent` 초입에서 drop(이벤트 프로세서 이전) |
| 오류 시 | 클래스명 오타 → 바인딩 실패로 api 부팅 중단. 줄 삭제 → 종전대로 404 수집(조용한 회귀, R5) |

## 수집 계약 변화 (docs/observability/sentry.md 반영)

| 경로/예외 | 종전 | 이후 |
|---|---|---|
| 매핑 없는 경로 → `NoResourceFoundException` (404) | 이벤트 + `http.status=404` | **미전송** |
| 앱 에러 코드 4xx (`BusinessException`) | 이벤트 + `http.status`·`error.code` | 동일 |
| 405·415 등 그 외 `ErrorResponse` | 이벤트 + `http.status` | 동일 |
| 5xx | 이벤트 | 동일 |
| 필터 단계 401(JWT)·400(`X-API-Version`) | 미전송 | 동일 |
