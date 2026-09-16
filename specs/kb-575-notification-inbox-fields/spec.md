# Feature Specification: 알림함 응답에 알림 유형·foodId 추가

**Feature Branch**: `kb-575-notification-inbox-fields`

**Created**: 2026-09-16

**Status**: Draft

**Jira**: [KB-575](https://simhani1.atlassian.net/browse/KB-575) — `[BE] 알림함 응답에 알림 유형·foodId 추가`

**Input**: User description (원문 인코딩 손상 — Jira KB-575 본문과 대조해 복원): "알림함 응답에 `type` 과 `foodId` 두 필드를 추가한다. 현재 알림함 항목은 id·title·body·receivedAt·read 5개뿐이고, 알림 저장 행에는 유형(type)과 발송 데이터(data)가 이미 남아 있다. 적용 대상은 최근 알림 목록 조회와 알림 읽음 처리 두 응답이며, 둘은 같은 항목 스키마를 쓴다. `type` 은 저장된 유형 문자열 그대로(HELPFUL·SCAN_SUGGESTION·REVIEW_REMINDER·NEWS·MEAL_TIME, 폐기된 NOTICE·NUDGE 행이 남아 있으면 그 문자열 그대로), `foodId` 는 nullable 64비트 정수로 REVIEW_REMINDER 일 때만 발송 data 의 foodId 를 꺼내고 그 외 유형은 null. 기존 필드·정렬·7일 범위·기기 단위 조회·X-Installation-Id 규칙은 그대로, X-API-Version 은 올리지 않는다. Swagger 스키마·예시 갱신. 테스트는 유형 5종 type 매핑, REVIEW_REMINDER 만 foodId non-null, 읽음 처리 응답에도 두 필드 포함. 범위 밖: 커서 페이징·전체 읽음·미읽음 수 엔드포인트. data 에서 foodId 를 꺼낼 때 타입(숫자/문자열) 처리 방식은 정하고 계약에 명시한다."

## 배경

FE 알림함(KB-499)이 서버 목록으로 전환 중이다. 푸시 알림을 탭하면 앱은 푸시 data 의 `type` 과 `foodId` 로 이동 위치를 정한다(KB-468 푸시 data 계약). 알림함 항목을 탭할 때도 같은 규칙을 쓰려면 목록 응답에 같은 정보가 필요하다. 알림 저장 행에는 유형과 발송 data 가 이미 있으므로(KB-467) 저장된 값을 응답에 노출하기만 하면 된다.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 알림함 항목의 유형을 알 수 있다 (Priority: P1)

회원이 앱 알림함을 열면 각 항목이 어떤 종류의 알림인지(리뷰 도움됨·스캔 제안·리뷰 리마인더·소식·식사 시간) 응답만으로 알 수 있어, 앱이 항목별 아이콘·이동 규칙을 푸시 탭과 동일하게 적용할 수 있다.

**Why this priority**: 알림함 전환의 핵심 차단 요소다. 유형이 없으면 앱은 항목을 탭해도 어디로 갈지 결정할 수 없다.

**Independent Test**: 유형이 서로 다른 알림 5건을 한 기기에 남기고 목록을 조회하면 각 항목의 `type` 이 저장된 유형 문자열과 일치한다. 이 이야기만 구현해도 앱은 유형별 표시·이동을 시작할 수 있다.

**Acceptance Scenarios**:

1. **Given** 한 기기에 HELPFUL·SCAN_SUGGESTION·REVIEW_REMINDER·NEWS·MEAL_TIME 알림이 각 1건씩 있을 때, **When** 회원이 그 기기로 최근 알림 목록을 조회하면, **Then** 각 항목의 `type` 은 저장된 유형 이름과 정확히 같은 문자열이고 기존 5개 필드(id·title·body·receivedAt·read)는 값·순서 규칙이 변하지 않는다.
2. **Given** 폐기된 유형 문자열(예: NOTICE·NUDGE)로 저장된 과거 알림 행이 남아 있을 때, **When** 목록을 조회하면, **Then** 그 항목은 오류 없이 저장된 문자열을 그대로 `type` 으로 돌려준다(앱은 모르는 유형을 이동 없이 처리한다).

---

### User Story 2 - 리뷰 리마인더 항목에서 대상 음식으로 이동할 수 있다 (Priority: P1)

회원이 알림함에서 리뷰 작성 리마인더를 탭하면 앱이 그 알림이 가리키는 음식 화면으로 바로 이동할 수 있어야 한다. 그러려면 항목에 대상 음식 식별자가 필요하다.

**Why this priority**: 리뷰 리마인더는 알림함에서 유일하게 대상 음식이 있는 유형이다. `type` 만으로는 어느 음식인지 알 수 없어 유형 노출과 함께 나가야 한다.

**Independent Test**: 발송 data 에 foodId 가 담긴 REVIEW_REMINDER 알림과 다른 유형 알림을 섞어 조회하면 REVIEW_REMINDER 항목만 `foodId` 가 그 값이고 나머지는 모두 null 이다.

**Acceptance Scenarios**:

1. **Given** 발송 data 에 `foodId = 7` 이 담긴 REVIEW_REMINDER 알림이 있을 때, **When** 목록을 조회하면, **Then** 그 항목의 `foodId` 는 7 이다.
2. **Given** HELPFUL·SCAN_SUGGESTION·NEWS·MEAL_TIME 알림이 있을 때(발송 data 에 다른 키가 있어도), **When** 목록을 조회하면, **Then** 그 항목들의 `foodId` 는 모두 null 이다.
3. **Given** REVIEW_REMINDER 알림인데 발송 data 가 없거나 foodId 키가 없을 때, **When** 목록을 조회하면, **Then** 그 항목의 `foodId` 는 null 이고 응답은 실패하지 않는다.
4. **Given** REVIEW_REMINDER 알림의 발송 data 에 foodId 가 정수, 또는 정수 문자열(`"7"`)로 저장돼 있을 때, **When** 목록을 조회하면, **Then** 두 경우 모두 `foodId` 는 정수 7 이다. 정수로 해석할 수 없는 값(예: `"abc"`, 소수, 빈 문자열)이면 `foodId` 는 null 이고 응답은 실패하지 않는다.

---

### User Story 3 - 읽음 처리 응답도 같은 항목 스키마를 돌려준다 (Priority: P2)

회원이 알림함 항목을 읽음 처리하면 앱은 돌아온 항목으로 목록의 그 항목을 그대로 교체한다. 그러려면 읽음 처리 응답에도 `type` 과 `foodId` 가 목록과 동일하게 들어 있어야 한다.

**Why this priority**: 두 응답이 같은 항목 스키마를 공유하므로 함께 바뀌어야 앱이 한 모델로 다룰 수 있다. 목록만 바뀌면 읽음 처리 후 항목 교체 시 유형 정보가 사라진다.

**Independent Test**: foodId 가 담긴 REVIEW_REMINDER 알림을 읽음 처리하면 응답에 `read = true` 와 함께 `type = REVIEW_REMINDER`, `foodId` 가 목록 조회 때와 같은 값으로 들어 있다.

**Acceptance Scenarios**:

1. **Given** 안 읽은 REVIEW_REMINDER 알림(foodId 7)이 있을 때, **When** 읽음 처리하면, **Then** 응답 항목은 `read = true`, `type = "REVIEW_REMINDER"`, `foodId = 7` 이고 이후 목록 조회의 같은 항목과 필드 값이 일치한다.
2. **Given** 안 읽은 NEWS 알림이 있을 때, **When** 읽음 처리하면, **Then** 응답 항목은 `type = "NEWS"`, `foodId = null` 이다.

---

### Edge Cases

- 발송 data 가 비어 있거나(null) foodId 키가 없는 REVIEW_REMINDER → `foodId = null`, 오류 없음.
- foodId 값이 정수 범위를 넘는 숫자·소수·비숫자 문자열 → `foodId = null`, 오류 없음(항목 하나 때문에 목록 전체가 실패하지 않는다).
- REVIEW_REMINDER 가 아닌 유형의 data 에 foodId 키가 들어 있어도 `foodId = null`(유형이 기준이다).
- 저장 유형이 현재 유형 목록에 없는 문자열(폐기 유형) → 저장 문자열 그대로 노출, 오류 없음.
- 알림이 0건인 기기 → 빈 배열(기존 동작 유지).
- 구 클라이언트가 같은 버전 헤더로 호출 → 필드가 추가됐을 뿐이므로 기존 5개 필드로 정상 동작.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 알림함 항목 응답은 기존 `id`·`title`·`body`·`receivedAt`·`read` 에 더해 `type` 과 `foodId` 를 항상 포함한다(키 누락 없이, 값이 없으면 `foodId` 는 null).
- **FR-002**: `type` 은 필수 문자열이며 저장된 알림 유형 이름을 그대로 돌려준다. 현재 유형은 HELPFUL·SCAN_SUGGESTION·REVIEW_REMINDER·NEWS·MEAL_TIME 이며, 저장 행에 폐기된 유형 문자열이 남아 있으면 변환·거절 없이 그 문자열을 돌려준다. 값은 푸시 data 의 `type` 과 같은 어휘다.
- **FR-003**: `foodId` 는 null 을 허용하는 64비트 정수다. 유형이 REVIEW_REMINDER 일 때만 발송 data 의 `foodId` 값을 정수로 해석해 돌려주고, 그 외 유형은 data 내용과 무관하게 null 이다.
- **FR-004**: REVIEW_REMINDER 의 `foodId` 해석은 관대하다 — data 가 없음·키 없음·정수로 해석 불가(비숫자 문자열·소수·범위 초과)는 모두 null 로 처리하고 응답을 실패시키지 않는다. 정수 또는 정수 문자열은 정수로 해석한다.
- **FR-005**: 두 필드는 최근 알림 목록 조회와 알림 읽음 처리 응답 모두에 같은 규칙으로 포함된다(두 응답은 하나의 항목 스키마를 공유한다).
- **FR-006**: 기존 동작은 변하지 않는다 — 항목 정렬(최신순), 7일 범위, 기기 단위 조회, `X-Installation-Id` 필수 규칙, 오류 코드, 기존 5개 필드의 값 규칙.
- **FR-007**: API 버전(`X-API-Version`)을 올리지 않는다. 필드 추가만이므로 기존 버전 헤더로 호출하는 구 클라이언트와 호환된다.
- **FR-008**: API 문서(Swagger)의 알림함 항목 스키마 설명과 응답 예시를 두 필드 포함으로 갱신한다. 예시는 REVIEW_REMINDER(`foodId` 있음)와 NEWS(`foodId` null) 두 항목을 보여준다.
- **FR-009**: 자동화 테스트가 다음을 검증한다 — (a) 유형 5종 행의 `type` 매핑, (b) REVIEW_REMINDER 만 `foodId` non-null 이고 나머지는 null, (c) 읽음 처리 응답에도 두 필드가 포함, (d) foodId 해석 불가·부재 시 null.

### 범위 밖

- 커서 페이징·전체 읽음 처리·미읽음 수 엔드포인트(KB-467 결정 유지).
- 알림 저장 구조·발송 data 내용·푸시 발송 로직의 변경. REVIEW_REMINDER 발송 자체는 별도 작업이며, 이 기능은 저장된 값을 읽어 노출만 한다.
- 폐기 유형 행의 정리·마이그레이션.

### Key Entities

- **알림(Notification)**: 회원의 특정 기기에 발송된 알림 1건. 유형, 제목, 본문, 발송 시 함께 실린 data(키-값), 수신 시각, 읽은 시각을 가진다. 이 기능은 새 속성을 추가하지 않고 기존 유형·data 를 응답에 노출한다.
- **알림함 항목(응답)**: 앱이 목록에 그리는 단위. `id`·`type`·`foodId`·`title`·`body`·`receivedAt`·`read` 7개 필드. 목록 조회와 읽음 처리가 같은 스키마를 쓴다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 알림함 목록·읽음 처리 응답의 모든 항목에 `type` 과 `foodId` 키가 100% 존재한다(값이 없어도 키는 있다).
- **SC-002**: 유형 5종 각 1건을 조회했을 때 `type` 이 저장 유형과 일치하는 비율 100%, REVIEW_REMINDER 이외 항목의 `foodId` null 비율 100%.
- **SC-003**: foodId 가 없거나 해석 불가한 REVIEW_REMINDER 행이 섞여 있어도 목록 조회 성공률 100%(항목 하나가 전체 응답을 실패시키지 않는다).
- **SC-004**: 기존 알림함 자동화 테스트(정렬·7일 범위·기기 단위·인증·오류 코드)가 수정 없이 전부 통과한다.
- **SC-005**: dev 배포 후 FE 가 dev Swagger 문서만 보고 어댑터를 반영할 수 있다(추가 문의 없이 두 필드의 타입·null 규칙·예시가 문서에 드러난다).

## Assumptions

- 알림 저장 행의 유형과 발송 data 는 이미 존재하며(KB-467) 이 기능은 읽기만 한다. 스키마 변경·마이그레이션은 없다.
- 발송 data 의 `foodId` 는 저장 경로에 따라 정수 또는 정수 문자열로 들어올 수 있다고 보고 둘 다 정수로 해석한다. 그 외는 null 이다(Jira 본문은 "발송 시 data.foodId 값" 이라고만 적어 해석 규칙은 이 스펙이 정한다).
- 현재 서버에는 REVIEW_REMINDER 를 발송하는 경로가 아직 없다. 테스트는 저장 행을 직접 만들어 검증하며, 향후 발송 구현은 data 에 `foodId` 키를 정수로 실어야 이 계약과 맞는다.
- 푸시 data 계약(KB-468)의 `type` 어휘와 저장 유형 이름은 동일하다.
- 폐기 유형(NOTICE·NUDGE)은 dev/운영 DB 에 남아 있을 수 있다는 전제로 관대하게 처리한다. 남아 있지 않다면 이 처리는 방어적 동작에 그친다.
- 완료 후 FE 알림(DoD 마지막 항목)은 배포 절차의 일부이며 이 스펙의 기능 범위 밖이다.
