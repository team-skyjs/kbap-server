# Tasks: k6 스캔 부하 테스트

**Feature**: `kb-393-k6-scan-load-test` | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

## Format: `[ID] [P?] [Story] Description`

- **Test 태스크 없음**: 이 기능은 프로덕션 코드 변경이 없고 k6 스크립트 자체가 검증 도구다(plan Constitution Check — 원칙 I 적용 대상 없음). 따라서 "실패 테스트 우선" 태스크를 만들지 않는다.
- 대부분 단계는 **실행 게이트**(사람이 실물 dev 를 만짐)라 순차 의존이 크다 — [P] 는 파일이 겹치지 않는 문서·스크립트 작업에만 붙는다.
- 경로: 스크립트 `k6/`, 문서 `specs/kb-393-k6-scan-load-test/`, 위키 `../kbap-agenthub/`.

## Path Conventions

- 스크립트: `k6/seed-image.js`·`k6/scan-burst.js`·`k6/mint-token.py`(작성됨), 원본 이미지 `k6/menu-board.jpg`(gitignore).
- 시크릿(JWT_SECRET·토큰)은 셸 환경변수로만 — 채팅·커밋 금지.

---

## Phase 1: Setup (Shared Infrastructure)

- [X] T001 로컬 도구 확인 — `k6 version`(없으면 `brew install k6`), `python3 --version`. `k6/` 스크립트 3종 존재 확인(seed-image.js·scan-burst.js·mint-token.py)
- [X] T002 `.gitignore` 에 `k6/menu-board.jpg` 포함 확인(이미 추가됨) — 원본 이미지·objectKey 는 커밋 대상 아님
- [X] T003 실제 메뉴판 사진 1장을 `k6/menu-board.jpg` 로 배치(시드 입력 — 사용자 준비)

---

## Phase 2: Foundational (Blocking Prerequisites)

**목적**: 인증·계정 등 모든 회차의 공통 전제. 완료 전에는 어떤 회차도 못 돈다.

- [X] T004 dev 테스트 회원 확보 — 기존 dev 로그인 계정에 `UPDATE member SET scan_unlocked=true WHERE id=<id>` (없으면 quickstart §1(a) 더미 INSERT, `provider_uid='loadtest-k6'`). `memberId` 확보
- [X] T005 access token 발급 — 셸에 `export JWT_SECRET='<SSM /kbap/dev/JWT_SECRET>'` 후 `TOKEN=$(python3 k6/mint-token.py <memberId> 2)`
- [X] T006 인증 스모크 — `curl -s -X POST https://dev.kbap.site/api/scans/tickets -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 1.0"` 가 `success:true` 200 (401/403 이면 T004~T005 재점검)

---

## Phase 3: User Story 1 - 스캔 체인 동시 부하 실측 (Priority: P1) 🎯 MVP

**Goal**: 시드 이미지를 재사용해 티켓+스캔 루프를 5→50→145 로 반복, 스캔 p95·실패율을 얻는다(스캔 총량 ≤200건).

**Independent Test**: 동시 5 리허설만 단독 실행해 티켓+스캔이 전부 200 이고 요약이 나오면 검증 성공.

- [X] T007 [US1] **이미지 재사용(시드 대체)** — 기존 버킷 객체 `test/images/scan/한식마당.jpg`는 한글 키라 CDN/OpenAI fetch 실패(SCAN-002). ASCII 키 `test/images/scan/hansik-madang.jpg`로 서버사이드 복사(내용 동일) → `SCAN_IMAGE_PATH=test/images/scan/hansik-madang.jpg`. VUS=1 검증 scan 200(15.81s)
- [X] T008 [US1] **리허설 VUS=5** — `k6 run -e ACCESS_TOKEN=$TOKEN -e SCAN_IMAGE_PATH=$SCAN_IMAGE_PATH -e VUS=5 k6/scan-burst.js`. `L1 ticket 200`·`L2 scan 200` 체크 100%, `scan_failed==0` 확인(비용 ≈50원). 실패 시 로그로 원인 파악 후 재점검(다음 회차 금지)
- [X] T009 [US1] **본 회차 VUS=50** — `... -e VUS=50 ... --summary-export=run-50.json`. scan p95·실패율 기록(비용 ≈500원)
- [X] T010 [US1] **본 회차 VUS=145** — 서버 회복 대기 후 `... -e VUS=145 ... --summary-export=run-145.json`. 누적 스캔 200건 확인(비용 ≈1450원)
- [X] T011 [US1] 회차별 수치 취합 — run-5/50/145 의 scan p95·실패율·429(scan_rate_limited)를 data-model 의 회차 표 형식으로 정리

**Checkpoint**: 동시성 5/50/145 세 지점의 스캔 p95·실패율 확보. 스캔 총량 ≤200 · 비용 ≤≈2,000원(SC-001).

---

## Phase 4: User Story 2 - 동시 스캔 중 타 API 영향 관찰 (Priority: P2)

**Goal**: 동시 스캔 실행 창 동안 타 API p95·서버 자원을 관찰하고 병목을 판정한다.

**Independent Test**: 동시 50 실행 창에서 대시보드 타 API p95·자원 그래프를 캡처.

- [X] T012 [US2] VUS=50/145 실행 창 동안 Grafana(env="dev") 캡처 — 스캔 p95 vs 타 API p95(`http_server_requests_seconds`), HikariCP active/pending, JVM heap/GC
- [X] T013 [P] [US2] 같은 창의 호스트 자원(integrations/unix — CPU/mem)과 CloudWatch(ALB TargetResponseTime·5xx) 캡처
- [X] T014 [US2] 병목 판정 — 스캔 중 타 API p95 상승 유무, 최초 포화 지점(앱 스레드/Hikari/heap/호스트CPU/LLM-429)을 근거 지표와 함께 1건 이상 기록. 429 는 앱 5xx 와 분리(SC-004·SC-005)

---

## Phase 5: User Story 3 - 결과 기록과 정리 (Priority: P3)

**Goal**: 결과를 위키에 남기고 스크립트를 커밋, 더미 계정을 정리한다.

**Independent Test**: 위키 문서·커밋·계정 상태 확인.

- [ ] T015 [US3] 지식 위키 기록 — `../kbap-agenthub/wiki/<topic>.md` 에 회차별 수치·병목 판정·한계치, `INDEX.md` 한 줄 추가 후 허브 커밋(update-agenthub 스킬)
- [X] T016 [P] [US3] 스크립트 커밋 — `k6/seed-image.js`·`scan-burst.js`·`mint-token.py`(+quickstart 링크). `menu-board.jpg`·objectKey·토큰 미포함 확인
- [ ] T017 [US3] 더미 계정 정리 — T004 에서 더미를 만들었으면 `UPDATE member SET status='DELETED' WHERE provider_uid='loadtest-k6'`. 기존 계정 재사용이면 생략(scan_unlocked 원복 여부 판단)

---

## Phase 6: Polish & Cross-Cutting

- [X] T018 SpecKit 산출물 정합 확인 — spec/plan/research/contracts/quickstart 가 실제 실행 결과와 어긋난 곳 없는지 점검(수치는 위키, 스펙엔 절차만)
- [ ] T019 draft PR(open-draft-pr-to-develop) — base develop, k6 스크립트 + specs, 본문에 회차 요약·비용 실적·병목 판정 링크. Jira KB-393 DoD 체크

---

## Dependencies & 실행 순서

- **Setup(T001~T003) → Foundational(T004~T006) → US1(T007~T011)** 순차. T007 시드가 US1·US2 의 전제(SCAN_IMAGE_PATH).
- **US2(T012~T014)** 는 US1 의 VUS=50/145 실행(T009~T010) **창 안에서** 관찰 — 시간적으로 US1 과 겹쳐 수행(별도 실행 아님). T014 판정은 T011 취합 후.
- **US3(T015~T017)** 는 US1·US2 결과가 있어야 기록 가능.
- **Polish(T018~T019)** 최후.
- **[P] 병렬**: T013(US2 캡처 분담), T016(스크립트 커밋 — 문서와 파일 다름). 그 외는 실물 dev 를 만지는 실행 게이트라 순차.

## Parallel 예시

- US2 관찰 창에서 T012(앱 지표)와 T013(호스트·ALB 지표)를 두 사람이/두 탭으로 동시 캡처 가능.

## Implementation Strategy (MVP)

- **MVP = US1(T001~T011)**: 시드 + 5/50/145 회차로 스캔 p95·실패율만 얻어도 "부하에서 스캔이 버티는가" 라는 1차 질문에 답한다.
- US2 는 같은 실행 창에 관찰을 얹는 증분, US3 는 기록·정리. 예산(200건) 안에서 US1 이 끝나면 재실행 없이 US2 관찰이 따라온다.
