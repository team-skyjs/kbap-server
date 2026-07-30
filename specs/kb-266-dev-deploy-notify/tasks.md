# Tasks: dev/prod 배포 GitHub Release 자동 발행 + 슬랙 API 변경 알림

**Input**: Design documents from `/specs/kb-266-dev-deploy-notify/`

**Prerequisites**: plan.md, spec.md, research.md(R1~R8), contracts/(release-format·slack-message), quickstart.md

**Tests**: 유일한 소스 코드 산출물이 테스트 자체(`OpenApiSnapshotTest` — 헌법 I 충족). 워크플로 YAML 은 테스트 프레임워크 대상이 아니므로 각 스토리의 검증 태스크가 quickstart.md 시나리오(사용자 확정 검증 기준 8종)를 실행한다.

**Organization**: 스토리별 그룹. 단, **US1(슬랙)은 US2(릴리즈)의 산출물(Release 링크·diff 요약 출력)을 소비하므로 구현 순서는 US2 → US1** 이다(spec 우선순위는 사용자 가치 순 — P1 슬랙이 최종 목적이지만, 전달할 내용물이 먼저 있어야 한다).

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

**Purpose**: 사람이 해야 하는 외부 준비(코드 아님)

- [ ] T001 [사람 작업] 슬랙 알림 채널 생성 + Incoming Webhook 발급 → repo Secret `SLACK_RELEASE_WEBHOOK_URL` 등록 (quickstart.md "사전 준비" — 구현과 병행 가능, T014 전까지만 완료)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 두 스토리가 공유하는 스냅샷 생성 수단 + 워크플로 골격·배선

**⚠️ CRITICAL**: 이 페이즈 완료 전에 스토리 구현 불가

- [X] T002 `OpenApiSnapshotTest` 작성 — `api/src/test/kotlin/com/kbap/api/openapi/OpenApiSnapshotTest.kt`: BehaviorSpec(given/when/then 한국어), 기존 통합테스트 인프라(@SpringBootTest + Testcontainers MySQL + @AutoConfigureMockMvc) 재사용, MockMvc `GET /v3/api-docs` 200 응답을 `api/build/openapi.json` 에 기록하고 유효성 assert(파싱 가능한 JSON + `paths` 비어있지 않음 + `openapi` 버전 필드 존재). Red 확인: assert 를 먼저 작성하고 기록 로직 없이 실행해 실패 확인 → 기록 로직 추가로 Green
- [X] T003 로컬 검증 — `./gradlew :api:test --tests '*OpenApiSnapshotTest*'` 실행 후 `jq '.paths | keys | length' api/build/openapi.json` > 0 확인 (quickstart.md "로컬 검증")
- [X] T004 `release-notes.yml` 골격 신규 — `.github/workflows/release-notes.yml`: `workflow_call` 트리거, inputs(`environment`/`prerelease`/`sha`/`redeploy`/`deploy_result`)·secret(`SLACK_RELEASE_WEBHOOK_URL`)·release 잡(permissions `contents: write`)·notify 잡(`needs: [release]`, `if: always()`) 빈 골격 (contracts/release-format.md 계약)
- [X] T005 `deploy-dev.yml` 배선 — `.github/workflows/deploy-dev.yml`: deploy 잡에 `outputs`(build 여부·배포 SHA) 노출(기존 스텝 무변경) + `release-notes.yml` 호출 잡 추가(`needs: deploy`, `if: always()`, environment=dev·prerelease=true·redeploy=build==false·deploy_result 전달, secrets 전달)
- [X] T006 [P] `deploy-prod.yml` 배선 — `.github/workflows/deploy-prod.yml`: T005 와 동일 패턴(environment=prod, prerelease=false)

**Checkpoint**: 스냅샷 생성 가능 + dev/prod 가 재사용 워크플로를 호출(빈 잡이라도 배포 무영향)

---

## Phase 3: User Story 2 - GitHub Release 자동 발행 (Priority: P2 — 구현 선행)

**Goal**: 배포 성공 시 태그 `dev|prod-YYYYMMDD-<short-sha>` 릴리즈를 멱등 발행, 본문 자동 생성, `openapi.json`·`openapi-diff.md` 첨부

**Independent Test**: dev 배포 1회로 릴리즈·asset 확인, Re-run 으로 중복 미생성 확인 (슬랙 없이 완결)

### Implementation for User Story 2

> 전부 `.github/workflows/release-notes.yml` release 잡 내부 — 같은 파일이라 [P] 없음, 순차

- [X] T007 [US2] 스냅샷 생성 스텝 — `actions/checkout`(`ref: inputs.sha`) + JDK 21 셋업 + `./gradlew :api:test --tests '*OpenApiSnapshotTest*'` + jq 로 `servers` 등 환경 종속 필드 제거 정규화 (research.md R2)
- [X] T008 [US2] baseline 확보 스텝 — `gh release list` 로 같은 환경 `<env>-*` 최신 태그 조회 → `openapi.json` asset 다운로드, 없으면 "초기 스냅샷 모드" output 설정 (research.md R3, dev/prod 기준점 분리 = FR-006)
- [X] T009 [US2] oasdiff 스텝 — 버전 고정 바이너리 다운로드(+체크섬 검증, 버전·체크섬은 파일 상단 env 로), `changelog` 실행 → `openapi-diff.md`(마크다운) + json 출력에서 추가/변경/삭제/breaking 카운트·엔드포인트 목록을 잡 outputs 로 노출 (research.md R4)
- [X] T010 [US2] 멱등 발행 스텝 — 태그 `<env>-YYYYMMDD-<short-sha>`(KST 날짜) 조립 → `gh release view` 존재 시 skip → `gh release create --target <sha> --generate-notes --notes-start-tag <직전태그>`(prod 는 본문 상단 "배포 시작 기준" 문구, dev 는 `--prerelease`) + asset 2종 업로드 + 초기 스냅샷이면 diff 대신 "초기 OpenAPI 스냅샷" 표기 + 실패 원인 `$GITHUB_STEP_SUMMARY` 기록 (research.md R5, data-model.md)
- [ ] T011 [US2] 검증 — quickstart.md 시나리오 1(릴리즈 발행·asset)·3(Re-run 멱등)·6(초기 스냅샷): API 변경 커밋을 develop 에 머지해 실배포로 확인

**Checkpoint**: 릴리즈·첨부·멱등이 dev 실배포에서 확인됨 (US2 독립 완결)

---

## Phase 4: User Story 1 - 배포 결과 슬랙 알림 + API 변경 요약 (Priority: P1 — 최종 목적)

**Goal**: 배포마다 슬랙에 환경·날짜·SHA·API 변경 요약·Release 링크 발송, 실패/재배포 케이스 포함

**Independent Test**: dev 배포로 성공 메시지 수신, 웹훅 시크릿 제거 후 배포로 실패 격리 확인

### Implementation for User Story 1

- [X] T012 [US1] notify 잡 성공 메시지 — `.github/workflows/release-notes.yml`: release 잡 outputs 소비해 contracts/slack-message.md 형식 조립(카운트+엔드포인트 목록, 10건 초과 절단, "API 변경 없음"·"초기 스냅샷" 케이스, `[DEV]`/`[PROD]` 구분, prod "블루/그린 배포 시작" 문구) → `curl` POST(Incoming Webhook)
- [X] T013 [US1] notify 잡 분기 매트릭스 — deploy 실패(실행 링크 포함 실패 메시지) / redeploy=true(재배포 메시지, 릴리즈 없음) / release 실패·deploy 성공("릴리즈 노트 생성 실패" 경고 메시지) 분기, 슬랙 curl 실패는 notify 잡 실패로만 남김(배포 판정 무영향 = FR-007)
- [ ] T014 [US1] 검증 — quickstart.md 시나리오 2("API 변경 없음" 명시)·5(재배포 알림)·7(웹훅 제거 시 배포 무영향) + 성공 메시지 실수신 확인 (T001 선행 필요)

**Checkpoint**: 슬랙 메시지 전 케이스가 dev 실배포에서 확인됨

---

## Phase 5: User Story 3 - 배포 이력·API 변경 회고 조회 (Priority: P3 — 검증 전용)

**Goal**: 별도 구현 없음 — US2 산출물(릴리즈 + asset)이 이력 그 자체

**Independent Test**: 과거 릴리즈에서 당시 API 외형 확인 가능

- [ ] T015 [US3] 검증 — 배포 2회 이상 누적 후 과거 릴리즈의 `openapi.json`·`openapi-diff.md` 를 내려받아 해당 시점 API 외형·변경 내역 확인 (spec US3 수용 시나리오)

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T016 quickstart.md 검증 시나리오 8종 최종 일괄 점검 — 특히 4(prod 릴리즈·"배포 시작" 문구·누적 PR 집계)·8(로그에 웹훅/토큰 노출 0) 확인 후 결과를 spec 체크 표로 기록
- [X] T017 [P] 전체 빌드 회귀 확인 — `./gradlew build` 통과(스냅샷 테스트 포함, ArchUnit 포함) + `deploy-batch-*`·`deploy-staging`·`run-batch` 워크플로 무변경 확인

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (P1: T001)**: 사람 작업 — 병행 가능, T014 전까지만
- **Foundational (P2: T002~T006)**: T002→T003, T004→(T005 ∥ T006). 모든 스토리를 블록
- **US2 (P3: T007~T011)**: Foundational 완료 후. T007→T008→T009→T010→T011(같은 파일 순차)
- **US1 (P4: T012~T014)**: **US2 완료 후**(release outputs 소비 — 의도된 스토리 간 의존). T012→T013→T014
- **US3 (P5: T015)**: US2 완료 후 언제든
- **Polish (P6: T016~T017)**: 전 스토리 완료 후

### Parallel Opportunities

- T005 ∥ T006 (dev/prod 배선 — 다른 파일)
- T001(사람) 은 전 구간과 병행
- T015 ∥ US1 구현, T017 ∥ T016

---

## Implementation Strategy

**MVP 슬라이스는 "dev 경로 전체"다**: Foundational → US2 → US1 을 dev 워크플로만으로 먼저 완성·검증(quickstart 1·2·3·5·6·7)하고, prod 는 배선(T006)이 이미 같은 재사용 워크플로를 가리키므로 main 머지 1회(시나리오 4)로 검증만 하면 된다. 스펙상 P1(슬랙)이 최종 가치지만 내용물(릴리즈·diff)이 선행돼야 하므로 구현 순서는 US2 → US1 — 이 순서 역전은 spec 의 "Why this priority"(P2 는 P1 의 링크 대상) 와 정합.

각 태스크(또는 논리 그룹) 완료 시 커밋한다. T011·T014 는 실배포가 필요하므로 develop 머지 후 검증한다 — 머지 전 로컬 확인 수단은 T003 과 yml 문법 검토뿐임을 감안해 T005·T006 변경은 리뷰를 거친다.
