# Feature Specification: Sentry 연동 — api·batch 컨테이너 에러 로그 수집

**Feature Branch**: `kb-508-sentry-error-alerting`

**Created**: 2026-09-08

**Status**: Draft

**Input**: User description: "508 워크트리" — Jira KB-508 「[BE] Sentry 연동 — api·batch 컨테이너 에러 이벤트 수집과 Slack 알림」(에픽 KB-267 시스템 운영/관리, SP 2). 명확화(2026-09-08): **이번 범위는 "에러가 Sentry 에 남게 하는 것"** 이다. 4xx·5xx 를 가리지 않고 전부 수집한다. Slack 알림 규칙은 Sentry 콘솔에서 나중에 설정하며 이 기능 범위 밖이다. 대신 **어느 컨테이너(api/batch, 어느 인스턴스)에서 난 에러인지 구분**할 수 있어야 한다 — 나중에 컨테이너별로 알림을 나눌 근거가 된다.

## 배경

api·batch 두 컨테이너(ECS dev/prod)는 지금 메트릭(처리량·지연·JVM·DB 풀)만 Grafana 로 보고, 에러는 CloudWatch 로그에 스택트레이스로만 남는다. "같은 에러가 몇 번 났나·어느 배포부터 시작됐나·어떤 요청(회원·경로·요청 id)에서 났나·어느 컨테이너에서 났나" 를 알려면 로그를 뒤져야 한다.

이 기능은 두 컨테이너의 에러 응답과 에러 로그를 Sentry 로 모아, 같은 원인끼리 이슈로 묶고, 환경·배포·컨테이너·요청 맥락을 태그로 실어 원인 추적을 한 화면에서 끝내는 **수집 기반**을 만든다. 알림은 이 기반 위에 콘솔 설정으로 얹는다.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - api 의 에러 응답이 요청 맥락과 함께 Sentry 에 남는다 (Priority: P1)

api 가 4xx 든 5xx 든 오류 응답을 내면 그 원인 예외가 Sentry 이벤트로 수집된다. 이벤트에는 어느 환경(dev/prod)·어느 배포(release)·어느 서비스(api)·어느 인스턴스에서 났는지와, 요청 id·경로·HTTP 메서드·응답 상태·에러 코드(`COMMON-002` 같은 앱 에러 코드), 로그인 요청이면 회원 id 가 붙는다. 같은 원인의 오류는 하나의 이슈로 묶인다.

**Why this priority**: 수집이 곧 이번 요구사항. 이게 되면 로그를 뒤지지 않고 Sentry 이슈 하나로 원인·범위·시작 배포·발생 위치를 안다.

**Independent Test**: dev 배포 후 4xx 요청(없는 id)과 5xx 요청(의도적 예외)을 보내면 각각 Sentry 이벤트가 생기고 태그가 채워져 있다. 같은 요청 세 번은 이슈 하나·이벤트 3건.

**Acceptance Scenarios**:

1. **Given** dev 의 api, **When** 5xx 를 내는 요청을 보내면, **Then** environment·release·service=api·인스턴스·requestId·경로·메서드·status=5xx 태그가 붙은 이벤트가 생긴다.
2. **Given** dev 의 api, **When** 4xx 를 내는 요청(유효성 실패·없는 리소스·미인증)을 보내면, **Then** status=4xx 와 앱 에러 코드 태그가 붙은 이벤트가 생긴다.
3. **Given** 로그인한 회원의 요청에서 오류, **When** 이벤트를 열면, **Then** memberId 태그가 있다. 게스트면 비어 있다.
4. **Given** 같은 원인의 오류가 세 번, **When** Sentry 를 보면, **Then** 이슈 하나에 이벤트 3건이다.
5. **Given** api 컨테이너 두 대가 같은 오류, **When** 이벤트를 보면, **Then** 이슈는 하나이고 각 이벤트의 인스턴스 태그로 어느 컨테이너인지 구분된다.

---

### User Story 2 - 코드가 남긴 에러 로그와 batch 잡 실패가 Sentry 에 남는다 (Priority: P1)

api·batch 어디서든 코드가 ERROR 레벨로 남긴 로그는 Sentry 이벤트가 된다(예외가 붙어 있으면 스택트레이스 포함, 없으면 메시지만). batch 잡이 실패하면 잡 이름이 태그로 붙은 이벤트가 생긴다. 이벤트에는 그 직전에 남은 로그들이 빵부스러기(breadcrumb)로 함께 실려 "무슨 일을 하다가 났는지" 를 이벤트 안에서 본다.

**Why this priority**: 요청 응답으로 드러나지 않는 에러(배치·비동기·아웃박스 발행 실패)가 여기서 잡힌다.

**Independent Test**: batch 에서 의도적으로 실패하는 잡을 돌리면 service=batch·잡 이름 태그와 직전 로그 breadcrumb 가 있는 이벤트가 생긴다. api 에서 예외 없이 `error` 로그만 남기는 경로를 타면 메시지 이벤트가 생긴다.

**Acceptance Scenarios**:

1. **Given** batch 잡 실패, **When** Sentry 를 보면, **Then** service=batch·잡 이름·environment·release·인스턴스 태그가 붙은 이벤트가 있다.
2. **Given** 코드의 ERROR 로그(예외 없음), **When** Sentry 를 보면, **Then** 로그 메시지·로거 이름이 담긴 이벤트가 있다.
3. **Given** 에러 직전에 INFO 로그 몇 줄, **When** 이벤트를 열면, **Then** 그 로그들이 시간순 breadcrumb 로 붙어 있다.
4. **Given** WARN 이하 로그만 남긴 경로, **When** Sentry 를 보면, **Then** 이벤트가 생기지 않는다.

---

### User Story 3 - 컨테이너별로 구분되고, 비밀값 없이 환경별로 켜고 끈다 (Priority: P2)

Sentry 에서 api 와 batch 가 서로 다른 프로젝트로 나뉘어, 각각의 이슈 목록과 나중의 알림 규칙을 따로 둘 수 있다. 한 프로젝트 안에서도 인스턴스(ECS 태스크) 단위로 구분된다. 접속 키(DSN)는 코드·이미지·레포에 없고 환경마다 배포 설정으로 주입되며, 로컬 개발은 Sentry 가 꺼져 있다. 키가 없으면 앱은 정상 기동하고 Sentry 만 비활성이다.

**Why this priority**: "어디서 났나" 를 프로젝트·인스턴스 두 층으로 구분해 두어야 나중에 컨테이너별 알림을 나눌 수 있다. 키 유출·로컬 노이즈 방지는 운영 안전 요건.

**Independent Test**: api 오류는 api 프로젝트에, batch 오류는 batch 프로젝트에만 생긴다. 레포 검색에 DSN 0건, 로컬 기동 시 이벤트 0건, dev 배포본은 environment=dev.

**Acceptance Scenarios**:

1. **Given** api·batch 각각의 오류, **When** Sentry 를 보면, **Then** 서로 다른 프로젝트에 각각 생기고 섞이지 않는다.
2. **Given** 레포·이미지, **When** DSN 값을 검색하면, **Then** 없다.
3. **Given** 로컬 프로필 기동, **When** 예외가 나면, **Then** 이벤트가 생기지 않고 앱은 정상이다.
4. **Given** DSN 이 주입되지 않은 환경, **When** 기동하면, **Then** 정상 기동하고 Sentry 만 비활성이다.
5. **Given** dev/prod 배포, **When** 이벤트를 보면, **Then** environment 가 각각 dev/prod 이고 release 가 그 배포의 이미지 식별자와 같다.

---

### Edge Cases

- 4xx 를 전부 수집하면 미인증(401)·유효성(400)이 이벤트의 대다수가 될 수 있다. 이번엔 의도된 동작이고, 이슈는 에러 코드별로 묶이므로 "종류" 는 늘지 않는다. 이벤트 한도가 부담되면 후속에서 4xx 샘플링 또는 코드별 제외를 둔다(알림 규칙은 콘솔에서 5xx 만 대상으로 걸 수 있다).
- Sentry 서비스 자체가 불가하면 앱 요청 처리는 영향을 받지 않는다(전송 실패 무시, 응답 지연 없음).
- 이벤트 폭주(같은 오류가 초당 수십 건)가 나도 앱이 느려지지 않는다 — 전송은 비동기·큐 상한.
- 그룹핑 키에 requestId·memberId 같은 가변값이 들어가면 같은 오류가 이슈 수백 개로 갈라진다. 태그로만 붙이고 메시지·핑거프린트에는 넣지 않는다.
- release 식별자는 컨테이너 이미지 태그(빌드 시 git 커밋)와 같아야 "이 배포부터" 를 커밋으로 읽을 수 있다. 파이프라인이 그 값을 컨테이너에 넘기지 않으면 넘기게 한다.
- 개인정보: 회원 id(숫자)는 태그로 붙이되 이메일·닉네임·요청 본문·토큰·쿠키는 넣지 않는다. 헤더 중 인증 헤더는 마스킹된다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 api 가 오류 응답(4xx·5xx)을 낼 때 그 원인 예외를 Sentry 이벤트로 수집해야 한다.
- **FR-002**: 시스템은 api·batch 에서 ERROR 레벨로 남긴 로그를 이벤트로 수집해야 한다(예외 있으면 스택트레이스 포함). WARN 이하는 수집하지 않는다.
- **FR-003**: 시스템은 batch 잡 실패를 잡 이름 태그와 함께 이벤트로 수집해야 한다.
- **FR-004**: 시스템은 모든 이벤트에 environment(dev/prod)·release(배포 이미지 식별자)·service(api/batch)·인스턴스 식별자를 붙여야 한다.
- **FR-005**: 시스템은 api 이벤트에 요청 id·경로(템플릿)·HTTP 메서드·응답 상태·앱 에러 코드를 붙이고, 로그인 요청이면 회원 id 를 붙여야 한다.
- **FR-006**: 시스템은 이벤트 직전의 로그를 breadcrumb 로 실어야 한다.
- **FR-007**: 시스템은 요청 본문·토큰·쿠키·이메일 등 개인정보를 이벤트에 넣지 않아야 한다.
- **FR-008**: 시스템은 같은 원인의 오류를 하나의 이슈로 묶어야 하며, 가변값이 그룹핑에 섞이지 않게 해야 한다.
- **FR-009**: 시스템은 api 와 batch 를 서로 다른 Sentry 프로젝트로 보내야 한다.
- **FR-010**: 시스템은 DSN 을 코드·레포·이미지에 두지 않고 환경별 배포 설정으로 주입해야 한다.
- **FR-011**: 시스템은 로컬 프로필에서 Sentry 를 비활성으로 두고, DSN 이 없으면 정상 기동하되 Sentry 만 비활성이어야 한다.
- **FR-012**: 시스템은 Sentry 전송 실패·지연이 요청 처리와 잡 실행에 영향을 주지 않게 해야 한다.
- **FR-013**: 연동 방법·태그 규약·프로젝트 구분·후속 알림 설정 안내를 관측 문서(docs/observability)에 기록해야 한다.

### Key Entities *(include if data involves)*

- **이벤트(Sentry Event)**: 오류 1건. 태그(environment·release·service·인스턴스·requestId·경로·메서드·status·에러 코드·memberId·잡 이름)·스택트레이스·breadcrumb·시각. 앱 DB 에는 저장하지 않는다.
- **이슈(Sentry Issue)**: 같은 원인 이벤트의 묶음. 첫/마지막 발생·횟수. 나중의 알림 규칙 대상.
- **프로젝트(Sentry Project)**: api·batch 각 1개. 컨테이너별 구분과 알림 분리의 단위.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: dev 에서 4xx 요청과 5xx 요청을 각각 보내면 둘 다 1분 안에 Sentry 에 이벤트로 보이고, 태그만으로 환경·배포·컨테이너·요청 id·상태·에러 코드를 읽을 수 있다.
- **SC-002**: api 오류와 batch 오류가 서로 다른 프로젝트에 나타나고, 같은 프로젝트 안에서 인스턴스별로 구분된다.
- **SC-003**: 같은 원인의 오류 N건이 이슈 1개로 묶인다.
- **SC-004**: 레포 전체 검색에서 DSN 문자열이 0건이고, 로컬 프로필 기동 시 Sentry 이벤트가 0건이다.
- **SC-005**: Sentry 이슈 화면에서 "이 배포(release)부터 발생" 을 커밋 단위로 식별할 수 있다.

## Assumptions

- Slack 알림 규칙은 이 기능 범위 밖이다. Sentry 콘솔에서 프로젝트별로 나중에 설정하며, 이 기능은 그 전제(프로젝트 분리·태그)만 갖춘다.
- 4xx 도 수집한다(2026-09-08 결정). 이벤트 한도가 문제 되면 후속에서 샘플링·코드별 제외를 둔다.
- "컨테이너별 구분" 은 두 층이다: 앱 단위(api/batch)는 **별도 Sentry 프로젝트**, 인스턴스 단위(ECS 태스크)는 이벤트의 **인스턴스 식별자 태그**.
- Sentry 조직·프로젝트(api·batch 각 1개) 생성은 콘솔에서 한다. 코드 범위는 SDK 연동·태그·배포 설정 주입.
- release 식별자는 배포 파이프라인이 만드는 컨테이너 이미지 태그(git 커밋 기반)를 그대로 쓴다.
- DSN 주입은 기존 비밀값(LLM 키)과 같은 경로(SSM 파라미터 → ECS 태스크 환경변수)를 따른다.
- 성능 트레이싱(APM)은 하지 않는다. 오류·에러 로그 수집만.
- 메트릭(Grafana)과 역할을 나눈다: "얼마나 느린가·많은가" 는 Grafana, "무엇이 어디서 깨졌나" 는 Sentry.
