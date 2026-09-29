# Tasks: dev 로그 출력을 prod 와 동일하게

**Input**: Design documents from `/specs/kb-652-dev-log-prod-parity/`

**Prerequisites**: plan.md, spec.md, research.md(D1~D5), data-model.md, quickstart.md · Jira [KB-652](https://simhani1.atlassian.net/browse/KB-652)

**Tests**: 프레임워크 토글이라 새 테스트를 만들지 않는다(research D3, KB-380·KB-411 선례). 기존 `StructuredConsoleLoggingTest` 의 프로필 목록에 dev 를 추가하는 것이 유일한 테스트 변경이며, 이 한 줄이 yml 수정 전엔 실패(Red)·후엔 통과(Green)한다. 실노출은 로컬 dev 프로필 기동과 dev 배포 후 CloudWatch 로 확인한다.

**Organization**: US1(측정치 대표성)이 설정 변경 전부를 담는다. US3(로컬 불변)은 변경 범위 검증, US2(필드 검색)는 머지 후 dev 에서만 확인 가능해 마지막 단계다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·선행 미완 없음 → 병렬 가능
- **[Story]**: US1·US2·US3

## Path Conventions

api 설정 `api/src/main/resources/`, batch 설정 `batch/src/main/resources/`, 테스트 `api/src/test/kotlin/com/kbap/api/core/logging/`.

---

## Phase 1: Setup — 기준선

- [X] T001 `./gradlew :api:test` 로 현재 `StructuredConsoleLoggingTest` 가 그린임을 확인한다(staging·prod 두 프로필 검사 통과)

---

## Phase 2: Foundational

해당 없음 — 새 패키지·의존·스키마가 없다.

---

## Phase 3: User Story 1 — 부하 테스트 측정치가 prod 를 대표한다 (Priority: P1) 🎯 MVP

**Goal**: api dev 를 JSON 구조화 로그 + SQL 미출력으로, batch dev 를 SQL 미출력으로 바꿔 각 앱의 prod 프로필과 같게 한다.

**Independent Test**: 로컬에서 dev 프로필로 api 를 띄워 로그 줄이 `{` 로 시작하고 `requestId` 필드가 있으며 SQL 문이 0건인지 본다(quickstart §3). 설정 수준에서는 dev yml 의 두 키가 prod yml 과 같은 값이다.

### Implementation for User Story 1

- [X] T002 [US1] `api/src/test/kotlin/com/kbap/api/core/logging/StructuredConsoleLoggingTest.kt` 의 `listOf("staging", "prod")` 를 `listOf("dev", "staging", "prod")` 로 바꾸고 given 설명 "staging·prod 의 JSON 구조화 로그 설정" 을 "dev·staging·prod 의 JSON 구조화 로그 설정" 으로 갱신한 뒤 `./gradlew :api:test` 를 돌려 **dev 프로필에서 실패(Red)** 함을 확인한다
- [X] T003 [US1] `api/src/main/resources/application-dev.yml` 파일 맨 위에 `application-prod.yml` 1~6행과 동일한 블록(`logging:` + 주석 2줄 + `structured: format: console: ecs`)을 넣고, `spring.jpa.show-sql: true` 를 `false` 로 바꾼다. 다른 키는 손대지 않는다
- [X] T004 [P] [US1] `batch/src/main/resources/application-dev.yml` 의 `spring.jpa.show-sql: true` 를 `false` 로 바꾼다. `logging.structured` 는 넣지 않는다(batch prod 와 동일 — research D2)
- [X] T005 [US1] `./gradlew :api:test` 그린 확인(Green). `StructuredConsoleLoggingTest` 가 세 프로필 모두 통과
- [X] T006 [US1] quickstart §1 정적 검사 실행 — api dev yml 에 `console: ecs`·`show-sql: false`, batch dev yml 에 `show-sql: false` 만 있고 `structured` 없음
- [X] T007 [US1] quickstart §3 로컬 실노출 확인 — 일회용 MySQL(3307)·Redis(6380) 컨테이너 + 메인 체크아웃 `.env` source + `SPRING_PROFILES_ACTIVE=dev` 로 `./gradlew :api:bootRun` 기동, `curl -H 'X-API-Version: 1.0' 'http://localhost:8080/api/home?lang=en'` 호출 후 로그 파일에서 `^{` 줄 > 0, `"requestId"` 존재, `select .* from` 0건을 확인하고 컨테이너를 정리한다
- [ ] T008 [US1] 커밋 — `chore(config): dev 로그 출력을 prod 와 동일하게 — api ECS JSON 구조화·show-sql off, batch show-sql off` (본문에 KB-652 근거: 1만 동접 램프업 부하 테스트의 측정치 대표성, `Refs KB-652`)

**Checkpoint**: dev 설정이 각 앱의 prod 와 같고 테스트 그린, 로컬 dev 프로필에서 JSON 로그 확인.

---

## Phase 4: User Story 3 — 로컬 개발 경험은 변하지 않는다 (Priority: P3)

**Goal**: local·staging·prod 프로필 파일이 한 줄도 바뀌지 않았음을 확인한다.

**Independent Test**: 6개 파일 diff 가 비어 있다.

### Implementation for User Story 3

- [X] T009 [P] [US3] `git diff --stat develop -- api/src/main/resources/application-local.yml api/src/main/resources/application-staging.yml api/src/main/resources/application-prod.yml batch/src/main/resources/application-local.yml batch/src/main/resources/application-staging.yml batch/src/main/resources/application-prod.yml` 이 비어 있음을 확인한다(SC-003). 베이스 `application.yml` 두 파일과 `logback-spring.xml` 도 diff 없음

**Checkpoint**: 변경 범위가 dev yml 2개 + 테스트 1개로 한정됨.

---

## Phase 5: User Story 2 — 부하 중 오류를 필드로 추적한다 (Priority: P2, 머지 후)

**Goal**: dev 배포 후 CloudWatch Logs Insights 에서 `requestId` 로 한 요청의 로그만 골라낼 수 있다.

**Independent Test**: quickstart §4 쿼리로 특정 `requestId` 의 줄만 반환된다(SC-004).

### Implementation for User Story 2

- [X] T010 [US2] `open-draft-pr-to-develop` 스킬로 base=develop draft PR 을 연다 — 본문에 `> **Jira:** [KB-652](https://simhani1.atlassian.net/browse/KB-652)`·`Refs KB-652`, spec/plan 경로, "설정 변경만, 새 테스트 없음, 로컬 dev 프로필 실노출 확인" 을 적는다. 이후 사용자 확인으로 ready 전환·머지
- [ ] T011 [US2] 머지 후 `deploy-dev.yml`·`deploy-batch-dev.yml` 완료를 확인하고, quickstart §4 의 Logs Insights 쿼리를 dev api 로그 그룹에서 실행해 `requestId` 필드가 있고 특정 값으로 필터하면 그 요청의 줄만 나오는지 확인한다. batch 로그 그룹에서는 `select` 문이 없는지 확인한다
- [ ] T012 [US2] 확인 결과(요청 id 예시·쿼리·결과 줄 수)를 Jira KB-652 의 DoD 4번째 항목 체크와 함께 코멘트 없이 항목 상태만 갱신한다(create-jira-task 규약 — DoD 코멘트 금지)

---

## Phase 6: Polish

- [ ] T013 research D5 의 범위 밖 발견(배치 베이스 yml 의 죽은 `logging.level.com.kbap.infra.llm.provider`)을 별도 Jira 태스크 후보로 사용자에게 보고한다. 이 브랜치에서 고치지 않는다

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1** → 선행 없음.
- **Phase 3(US1)** → Phase 1 이후. T002(Red) → T003(Green 을 만드는 변경) → T005(Green 확인). T004 는 T003 과 다른 파일이라 병렬. T006·T007 은 T003·T004 이후. T008 은 T005~T007 이후.
- **Phase 4(US3)** → T003·T004 이후 언제든(T009 는 읽기 전용, T006 과 병렬 가능).
- **Phase 5(US2)** → T008·T009 이후. T011·T012 는 **머지 후**에만 가능.
- **Phase 6** → 독립(보고만).

### User Story Dependencies

- **US1**: 설정 변경 전부. 단독으로 SC-001·002·005 를 만족.
- **US3**: US1 의 변경 범위 검증. US1 없이는 의미 없음.
- **US2**: US1 이 dev 에 배포된 뒤에만 검증 가능. 코드 변경은 없다.

### Parallel Opportunities

- T003 ∥ T004 (api yml / batch yml).
- T006 ∥ T009 (둘 다 읽기 전용 검사).
- 나머지는 순차.

---

## Parallel Example: User Story 1

```bash
# T003 과 T004 를 함께 (다른 파일):
Task: "api/src/main/resources/application-dev.yml 에 structured 블록 추가 + show-sql false"
Task: "batch/src/main/resources/application-dev.yml show-sql false"

# 둘 다 끝나면 T005 → (T006 ∥ T009) → T007 → T008
```

---

## Implementation Strategy

### MVP (US1 만)

1. T001 기준선 → T002 Red → T003·T004 → T005 Green → T006 → T007 로컬 실노출 → T008 커밋.
2. 여기까지가 코드 산출물 전부다. 이후는 검증·배포 확인이다.

### 전체

1. T009 로 변경 범위 확인 → T010 draft PR → 사용자 확인 후 머지.
2. dev 자동 배포 후 T011 CloudWatch 확인 → T012 Jira DoD 갱신.
3. T013 범위 밖 보고.

### 판정 규칙

- T002 에서 dev 가 실패하지 않으면 dev yml 에 이미 structured 가 있다는 뜻이다 — 조사가 틀린 것이므로 멈추고 확인한다.
- T007 에서 SQL 이 찍히면 `show-sql` 외의 경로(예: `logging.level.org.hibernate.SQL`)가 켜져 있는 것이다 — 어느 파일이 켰는지 찾고 plan 을 갱신한 뒤 진행한다.

---

## Notes

- 태스크 총 13개: Setup 1, US1 7(T002~T008), US3 1(T009), US2 3(T010~T012), Polish 1(T013).
- 코드 변경 파일은 3개뿐이다. 나머지 태스크는 검증과 배포 확인이다.
- 커밋은 T008 한 번. PR 은 T010.
