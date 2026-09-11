# Data Model: Sentry 이벤트 태그 스키마

앱 DB 변경 없음. 이 문서는 Sentry 로 보내는 이벤트의 **태그·컨텍스트 계약**을 고정한다(검색·알림 조건의 키가 된다).

## 이벤트 공통 (api·batch)

| 키 | 출처 | 값 예 | 용도 |
|---|---|---|---|
| `environment` | yml `sentry.environment: ${SPRING_PROFILES_ACTIVE}` | `dev` / `prod` | 환경 구분·알림 분리 |
| `release` | yml `sentry.release: ${SENTRY_RELEASE:}` ← 배포 워크플로 | `api-3f2a1c9` / `batch-3f2a1c9` | "이 배포부터" = 커밋 |
| `service` (tag) | yml `sentry.tags.service` | `api` / `batch` | 프로젝트가 나뉘어 있어도 이벤트 단독으로 식별 |
| `server_name` | SDK 기본(호스트명) | `a1b2c3d4e5f6` (ECS 컨테이너 id) | 인스턴스 구분 |
| `logger` / `level` | logback 경로 | `com.kbap.…`, `error` | 로그 이벤트일 때 |
| breadcrumbs | logback INFO 이상 + HTTP 클라이언트 | 직전 로그 ≤100 | 에러 직전 흐름 |

## api 이벤트 추가 태그 (`SentryRequestContextProcessor`)

| 키 | 출처 | 값 예 | 비고 |
|---|---|---|---|
| `requestId` | MDC `requestId` | UUID | CloudWatch 로그와 대조 |
| `memberId` | MDC `memberId` | `42` | 게스트면 미설정 |
| `http.status` | `BusinessException.errorCode.status` / `ErrorResponse.statusCode` / `IllegalArgumentException`·`HttpMessageNotReadableException`·`MethodArgumentTypeMismatchException` 400 / 낙관락(cause 포함) 409 / 그 외 500 — `GlobalExceptionHandler` 매핑과 동일 | `400`·`503`·`500` | 4xx/5xx 필터·알림 조건 |
| `error.code` | `BusinessException.errorCode.code` | `COMMON-002` | 앱 에러 코드별 집계 |
| request(URL·method·headers·경로 템플릿) | SDK 요청 필터 자동. 쿼리스트링은 `maskQuery`(`q`·`latitude`·`longitude` → `***`), `Authorization` 헤더 제거 | `GET /api/foods/{foodId}` | 트랜잭션 이름 = 경로 템플릿 |

핑거프린트: `BusinessException` 은 `["business", <error.code>]` 로 고정 → 같은 코드는 던진 위치와 무관하게 이슈 1개. 그 외 예외는 SDK 기본(스택 기반).

## batch 이벤트 추가 태그

| 키 | 출처 | 값 예 |
|---|---|---|
| `job` | MDC `job` ← `JobNameMdcListener`, yml `sentry.context-tags: job` 이 태그로 승격(없으면 contexts.MDC 부가 데이터라 필터 불가) | `foodVectorSyncJob` |

## 넣지 않는 것

요청 본문(`max-request-body-size=none`), `Authorization` 헤더(프로세서 제거). `send-default-pii=true`(2026-09-09 변경) 로 IP·그 외 헤더·쿠키는 싣는다.

## 상태 전이

없음(이벤트는 불변 기록). Sentry 이슈의 resolve/ignore 는 콘솔 운영.
