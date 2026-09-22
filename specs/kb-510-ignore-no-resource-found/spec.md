# Feature Specification: Sentry 노이즈 차단 — 존재하지 않는 경로(404) 이벤트 미전송

**Feature Branch**: `kb-510-ignore-no-resource-found`

**Created**: 2026-09-10

**Status**: Draft

**Input**: User description: "sentry.ignored-exceptions-for-type 에 org.springframework.web.servlet.resource.NoResourceFoundException 을 넣어 보내지 않도록 수정해서 PR". Jira KB-510 「[BE] Sentry 노이즈 차단」의 좁힌 범위 — 4xx 전부를 drop 하던 PR #257 은 머지 없이 닫혔고(4xx 수집 정책 유지), 이번엔 **경로 자체가 없는 404 한 종류만** 뺀다.

## 배경

KB-508 로 dev api 에 Sentry 가 켜진 뒤 첫 이슈들이 EC2 공인 IP 를 훑는 봇 스캔(`GET /`, `/api/v2/static/not.found` 류)의 404 였다. 이 404 는 앱이 만든 요청이 아니라 존재하지 않는 경로를 두드린 외부 트래픽이라 추적할 원인이 없고, 이슈 목록과 이벤트 한도만 소모한다. 반면 앱 에러 코드가 붙은 4xx(유효성·없는 리소스·미인증)는 클라이언트 계약 문제를 드러내므로 계속 수집한다(KB-508 결정 유지).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 없는 경로를 두드린 404 는 Sentry 에 남지 않는다 (Priority: P1)

운영자가 Sentry 의 api 프로젝트를 열었을 때, 매핑되지 않은 경로 요청(봇 스캔·오타 URL)으로 생긴 404 이벤트가 보이지 않는다. 그 요청에 대한 앱의 HTTP 응답(404 봉투)은 종전과 같다.

**Why this priority**: 이번 요구사항의 전부. 노이즈가 이슈 목록 상단을 차지하면 진짜 오류를 놓친다.

**Independent Test**: dev 에 배포 후 `GET /api/no-such-path` 를 보내면 404 응답은 받지만 Sentry 에 이벤트가 생기지 않는다.

**Acceptance Scenarios**:

1. **Given** dev 의 api, **When** 매핑되지 않은 경로로 요청하면, **Then** 클라이언트는 종전과 같은 404 응답을 받고 Sentry 에는 이벤트가 생기지 않는다.
2. **Given** dev 의 api, **When** 존재하는 경로에서 앱 에러 코드가 붙은 4xx(예: 없는 음식 id)가 나면, **Then** 종전과 같이 `http.status`·`error.code` 태그가 붙은 이벤트가 생긴다.
3. **Given** dev 의 api, **When** 5xx 가 나면, **Then** 종전과 같이 이벤트가 생긴다.

---

### Edge Cases

- 존재하는 경로에 잘못된 HTTP 메서드(405)나 지원하지 않는 미디어 타입(415)은 이번 제외 대상이 아니다 — 앱 계약 위반 신호라 계속 수집한다.
- 제외는 예외 종류로 판단하므로, 같은 404 라도 앱 코드가 명시적으로 던진 "리소스 없음"(에러 코드 있음)은 계속 수집된다.
- 제외 설정이 오타·삭제로 빠지면 종전(404 수집)으로 조용히 되돌아간다. 설정이 살아 있음을 고정하는 검증이 필요하다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 매핑되지 않은 경로 요청으로 생긴 404 를 Sentry 이벤트로 보내지 않아야 한다.
- **FR-002**: 시스템은 그 요청에 대한 HTTP 응답(상태·봉투)을 종전과 동일하게 유지해야 한다.
- **FR-003**: 시스템은 그 외 4xx(앱 에러 코드가 붙은 것·405·415 등)와 5xx 의 수집 동작을 바꾸지 않아야 한다.
- **FR-004**: 제외 대상은 설정으로 선언되며, 설정이 빠지는 회귀를 자동 검증으로 막아야 한다.
- **FR-005**: 관측 문서(`docs/observability/sentry.md`)의 "수집되지 않는 것" 항목에 이 제외를 기록해야 한다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: dev 배포 후 매핑되지 않은 경로 요청 10건을 보내도 Sentry api 프로젝트에 새 이벤트가 0건이다.
- **SC-002**: 같은 배포에서 앱 에러 코드 4xx 요청과 5xx 요청은 각각 1분 안에 이벤트로 보인다.
- **SC-003**: 설정을 제거하면 자동 검증이 실패한다.

## Assumptions

- 제외 범위는 "매핑되지 않은 경로" 한 종류다. 4xx 전체 drop(PR #257)은 채택하지 않았고, 이번 PR 도 그 결정을 바꾸지 않는다.
- 제외 수단은 Sentry SDK 가 제공하는 예외 타입 무시 설정을 그대로 쓴다. 이벤트 프로세서 코드는 손대지 않는다.
- batch 앱에는 웹 경로가 없어 대상 밖이다.
- KB-510 티켓은 이미 완료 상태이나 실제 머지된 변경이 없으므로, 이 PR 을 KB-510 에 연결하고 티켓 코멘트로 범위 축소를 기록한다.
