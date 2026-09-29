# Tasks: 톰캣 스레드 풀을 버추얼 스레드로 전환

**Input**: Design documents from `/specs/kb-653-virtual-threads/` · Jira [KB-653](https://simhani1.atlassian.net/browse/KB-653)

**Tests**: 새 테스트 없음(research D4). 기존 스위트 그린 + 로컬 dev 프로필 실노출(스레드 이름·지표 이름) + dev 배포 후 사용자 재측정.

**Organization**: US1·US2 는 설정 1줄이 담당하고 판정은 머지 후 재측정에서 난다. US3(대시보드)은 로컬 실노출로 지표 이름을 확정한 뒤 진행. US4(핀닝)는 머지 후 JFR.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup — 로컬 기준선(전환 전)

- [X] T001 quickstart §3 레시피로 **전환 전** dev 프로필 bootRun 을 띄워 홈 호출 로그의 `process.thread.name` 이 `http-nio-8080-exec-*` 임을 확인하고, `curl -s localhost:8080/actuator/prometheus | grep '^http_server_requests_active'` 로 **처리 중 요청 지표의 정확한 이름**을 기록한다(대시보드 질의에 쓴다). 컨테이너는 T003 까지 유지

---

## Phase 2: User Story 1+2 — 버추얼 스레드 전환 (Priority: P1)

**Goal**: dev 프로필에서 Tomcat 요청 처리가 버추얼 스레드로 바뀐다. DB 풀은 그대로(사용자 결정).

**Independent Test**: 로컬 dev 프로필 요청 로그의 스레드 이름이 `tomcat-handler-*`. 판정 수치(SC-001·002)는 머지 후 재측정.

- [X] T002 [US1] `api/src/main/resources/application-dev.yml` 의 `spring:` 블록에 `threads: virtual: enabled: true` 를 추가한다(yml 주석으로 "KB-653 — dev 만 먼저. RDS max_connections 60 이라 Hikari 풀은 그대로, 병목이 DB 로 옮겨가는지 재측정" 명시). `application-prod.yml`·`staging`·`local`·batch 는 손대지 않는다
- [X] T003 [US1] bootRun 을 재기동해 같은 홈 호출 로그의 스레드 이름이 `tomcat-handler-*` 로 바뀌었는지, `tomcat_threads_busy_threads`·`_config_max_threads` 값이 어떻게 나오는지(무의미 값) 기록한다. 확인 후 컨테이너 정리
- [X] T004 [US1] `./gradlew :api:test` 그린 확인

---

## Phase 3: User Story 3 — 대시보드에서 포화를 계속 본다 (Priority: P2)

**Goal**: 앱 대시보드에 처리 중 요청 수 패널을 추가하고 Tomcat 패널에 모드 주석을 단다.

**Independent Test**: JSON 에 id 8 패널이 있고 GC 가 y=24, Tomcat 패널 description 에 버추얼 스레드 안내. 문서 표에 행 추가.

- [ ] T005 [P] [US3] (사용자가 그라파나에서 직접 추가 — 저장소 JSON 은 이번 PR 에서 제외) `docs/observability/grafana-app-dashboard.json` — Tomcat 패널(id 3) 제목을 "Tomcat 스레드풀 (플랫폼 스레드 모드)" 로, `description` 에 "버추얼 스레드 모드(dev, KB-653)에서는 풀이 없어 이 패널이 무의미하다. 포화는 '처리 중 HTTP 요청 수' 패널로 본다" 를 넣는다. GC 패널(id 5) `gridPos.y` 를 24 로. 패널 id 8 을 추가한다: type timeseries, title "처리 중 HTTP 요청 수", gridPos `{h:8,w:24,x:0,y:16}`, datasource `${DS_PROMETHEUS}`, target expr `<T001 에서 확정한 지표 이름>{env=~"$env"}`, legendFormat `{{env}}-{{instance}}`, fieldConfig/options 는 Tomcat 패널을 복제
- [ ] T006 [P] [US3] (사용자 수행 — 저장소 문서는 이번 PR 에서 제외) `docs/observability/grafana-app-dashboard.md` — 패널 표에 "처리 중 HTTP 요청 수" 행(질의·해석: "지금 응답을 만들고 있는 요청 수. 버추얼 스레드 모드의 포화 지표. Tomcat max 같은 상한선이 없으므로 Hikari pending 과 함께 본다")을 추가하고, Tomcat 행 해석에 "dev 는 버추얼 스레드 모드라 무의미(KB-653)" 를 덧붙인다. 개요 단락에 버추얼 스레드 모드 안내 한 문장
- [X] T007 [US3] quickstart §1 정적 검사(패널 목록·gridPos·diff 없음) 실행

---

## Phase 4: 커밋·PR

- [X] T008 커밋 — `perf(api): dev 프로필 Tomcat 요청 처리를 버추얼 스레드로 전환 + 처리 중 요청 수 패널` 본문에 RDS 60 제약·풀 미변경 결정·로컬 확인 결과, `Refs KB-653`
- [X] T009 `open-draft-pr-to-develop` 스킬로 draft PR — 본문에 Jira 링크, "dev 만, DB 풀 그대로, 예상 관측: Hikari 대기 초과 500 증가 가능" 을 명시

---

## Phase 5: 머지 후 — User Story 1·2·4 판정 (사용자 수행)

- [ ] T010 [US1] AWS 안에서 같은 k6 스크립트(2천 VU × 5회) 재측정 → 대시보드 "처리 중 HTTP 요청 수" 최대값(SC-001 > 200)·p95(SC-002 < 4.5s)·Hikari pending·500 건수 기록
- [ ] T011 [US4] `JFR_ENABLED=true scripts/perf/run-endpoint.sh home-auth read 5 1m` 캠페인 JFR 에서 `jfr print --events jdk.VirtualThreadPinned` 건수·최상위 프레임 기록
- [ ] T012 Jira KB-653 DoD 갱신(1·3 완료, 2 는 "풀 미변경 — RDS 60 제약, 근거 research D1" 로 표기, 4·5 는 측정 결과)

---

## Dependencies

- T001 → T002 → T003 → T004. T005·T006 은 T001(지표 이름) 이후 병렬, T007 은 T005·T006 이후. T008 → T009. T010~T012 는 머지 후.

## Notes

- 총 12개: 로컬 4, 대시보드 3, 커밋·PR 2, 머지 후 3.
- Kotlin 소스 변경 0. 설정 1줄 + JSON + 문서.
