# Tasks: Sentry 노이즈 차단 — 존재하지 않는 경로(404) 이벤트 미전송

**Input**: Design documents from `/specs/kb-510-ignore-no-resource-found/`

**Prerequisites**: plan.md, spec.md, research.md(R1~R6), data-model.md, contracts/sentry-ignore-config.md, quickstart.md

**Tests**: 없음 — 원칙 I 예외(plan Complexity Tracking, research R5). 사용자 결정 2026-09-09(KB-508): Sentry 관련 테스트는 만들지 않는다. 검증은 로컬 가짜 DSN 의 SDK drop 로그 + dev 콘솔 실이벤트(quickstart).

**Organization**: 스토리 1개(P1). 런타임 변경은 yml 1줄, 나머지는 문서·검증·티켓.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완 태스크 의존 없음)
- **[Story]**: 스토리 라벨(US1)
- 설명에 정확한 파일 경로 포함

## Path Conventions

- api 설정: `api/src/main/resources/application.yml`
- 관측 문서: `docs/observability/sentry.md`
- 신규 Kotlin 소스·테스트·마이그레이션·batch 변경 없음

---

## Phase 1: Setup

**Purpose**: 없음 — 신규 의존·구조 변경 없음(Sentry SDK 는 KB-508 로 이미 도입). 이 단계는 비어 있다.

---

## Phase 2: Foundational

**Purpose**: 없음 — 스토리 1개가 다른 기반에 의존하지 않는다. 이 단계는 비어 있다.

**Checkpoint**: 바로 US1 로 진행.

---

## Phase 3: User Story 1 - 없는 경로를 두드린 404 는 Sentry 에 남지 않는다 (Priority: P1) 🎯 MVP

**Goal**: 매핑되지 않은 경로 요청(봇 스캔·오타 URL)의 `NoResourceFoundException` 을 SDK 설정으로 제외한다. HTTP 404 응답·그 외 4xx/5xx 수집은 무변경.

**Independent Test**: 로컬 가짜 DSN + `sentry.debug=true` 로 `GET /api/no-such-path` 시 `Event was dropped as the exception org.springframework.web.servlet.resource.NoResourceFoundException is ignored` 로그가 찍히고 응답은 404 `COMMON-002`. dev 배포 후 같은 요청 10건에 새 이벤트 0건, 앱 에러 코드 4xx·5xx 는 종전대로 이벤트(quickstart §2).

### Implementation for User Story 1

- [X] T001 [US1] `api/src/main/resources/application.yml` 의 `sentry:` 블록에 contracts §1 그대로 `ignored-exceptions-for-type:` 목록 추가 — 항목 `org.springframework.web.servlet.resource.NoResourceFoundException` 1개, `exception-resolver-order` 바로 아래 위치. 다른 키·`application-local.yml`·`api/src/test/resources/application.yml`·`batch/src/main/resources/application.yml` 은 무수정
- [X] T002 [P] [US1] `docs/observability/sentry.md` 갱신(contracts §2) — (1) "**수집되지 않는 것**" 문단(현재 31행)에 "매핑되지 않은 경로의 404(`NoResourceFoundException` — 봇 스캔·오타 URL): `sentry.ignored-exceptions-for-type` 으로 SDK 가 이벤트 프로세서 이전에 버린다(정확한 클래스 일치). 405·415·앱 에러 코드 4xx 는 계속 수집(KB-510)" 추가, (2) "후속·조정 → **노이즈**" 항목(현재 47행)을 "예외 종류 단위 제외는 `sentry.ignored-exceptions-for-type`(정확한 클래스 일치), 에러 코드 단위 제외는 `SentryRequestContextProcessor` 에서 `null` 반환, 양이 많으면 `sentry.sample-rate`" 로 교체. 태그 표 `http.status` 행은 유지
- [X] T003 [US1] 로컬 검증(quickstart §1) — `docker compose up -d mysql redis` → 메인 체크아웃 `.env` 를 `set -a; source` → `API_SENTRY_DSN='https://k@localhost.invalid/0' SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun --no-daemon --args='--sentry.debug=true'` 기동 → `curl -H 'X-API-Version: 1.0' http://localhost:8080/api/no-such-path` 와 `curl http://localhost:8080/` 가 404 응답 + drop 로그, `GET /api/foods/999999999`(헤더 포함) 은 drop 로그 없음을 확인. 부팅이 바인딩 오류로 실패하면 T001 의 클래스명 오타. 확인한 로그 한 줄을 research.md R5 아래에 "실증" 으로 기록 (T001 의존)
- [X] T004 [US1] `./gradlew :api:test` 전체 통과 확인 — 테스트 컨텍스트는 DSN 부재로 Sentry 자동구성이 뜨지 않아 컨텍스트 수·결과 무변화여야 한다 (T001 의존)

**Checkpoint**: 로컬에서 404 drop 로그 확인, 기존 테스트 그린, 문서 반영.

---

## Phase 4: Polish & Cross-Cutting Concerns

**Purpose**: 커밋·PR·티켓·dev 검증

- [X] T005 커밋 1개 — `fix(observability): 매핑 없는 경로 404(NoResourceFoundException) Sentry 미전송 (KB-510)` 형식, 본문에 R1~R4 요지(SDK 가 프로세서 이전에 정확한 클래스 일치로 drop, 4xx 정책 유지, 프로세서 무수정)와 로컬 검증 로그. 파일: `api/src/main/resources/application.yml`, `docs/observability/sentry.md`, `specs/kb-510-ignore-no-resource-found/*` (T001~T004 의존)
- [ ] T006 `open-draft-pr-to-develop` 스킬로 base=develop draft PR — 제목은 커밋과 동일, 본문에 Jira KB-510 링크·무엇을/왜(봇 스캔 404 노이즈, PR #257 의 4xx 전체 drop 은 폐기)·변경 사항 2줄·검증(T003 로그, T004 결과)·`Refs KB-510` (T005 의존)
- [ ] T007 [P] Jira KB-510 코멘트(`mcp__atlassian__addCommentToJiraIssue`) — 범위 축소 기록: "4xx 전체 drop(PR #257) 폐기 → `NoResourceFoundException` 한 종류만 `sentry.ignored-exceptions-for-type` 으로 제외. 4xx 수집 정책(KB-508) 유지. PR 링크" (T006 의존)
- [ ] T008 dev 검증(quickstart §2) — 머지·`deploy-dev` 배포 후 `GET https://dev.kbap.site/api/no-such-path` ×10 과 `GET https://dev.kbap.site/` ×10 에 Sentry `kbap-server-dev` 새 이벤트 0건(SC-001), 앱 에러 코드 4xx 1건이 1분 안에 `http.status`·`error.code` 태그로 보임(SC-002). 결과를 PR 본문 "검증" 에 추기 (T006 의존, 머지 후)

---

## Dependencies & Execution Order

### Phase Dependencies

- Phase 1·2: 비어 있음
- Phase 3 (US1): T001 → T003·T004. T002 는 T001 과 병렬
- Phase 4: T005 → T006 → T007·T008

### Parallel Opportunities

- T001 ∥ T002 (yml 과 문서, 다른 파일)
- T003 ∥ T004 (bootRun 과 gradle test 는 Gradle 데몬·포트 충돌을 피하려면 순차 권장 — `--no-daemon` 유지)
- T007 ∥ T008 (PR 생성 후)

---

## Implementation Strategy

### MVP (US1 전부)

1. T001 yml 1줄 → T003 로컬 drop 로그로 즉시 확인
2. T002 문서, T004 기존 테스트 그린
3. T005~T006 커밋·PR → T007 티켓 코멘트 → 머지 후 T008 dev 확인

### Notes

- 코드 변경 0개. `SentryRequestContextProcessor`·`GlobalExceptionHandler`·`WebConfig` 를 열지 않는다.
- 자동 테스트를 추가하지 않는다 — 추가하려는 유혹(yml 읽기·`Binder` 바인딩)은 KB-508 에서 삭제된 형태와 같다(research R5).
- 워크트리엔 `.env` 가 없다. 메인 체크아웃이 이미 8080 을 쓰고 있으면 `--args` 에 `--server.port=8081` 을 더한다.
- spec FR-004·SC-003(설정 삭제 시 자동 검증 실패)은 이 결정으로 충족하지 않는다 — 사용자가 뒤집으면 T004 뒤에 `SentryProperties` 바인딩 테스트 1개를 추가한다.
