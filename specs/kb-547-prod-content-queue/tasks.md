---

description: "Task list — prod 콘텐츠 생성 요청 큐 분리 (KB-547)"
---

# Tasks: prod 콘텐츠 생성 요청 큐 분리

**Input**: Design documents from `/specs/kb-547-prod-content-queue/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/prod-content-queue.md, quickstart.md

**Tests**: 앱 코드 변경이 없어 Kotest 대상이 없다(plan Constitution Check I — 정당화 기록). Test-First 는 **변경 전 현재 상태를 먼저 측정해 "Red"(공유 큐를 가리킴)를 확인 → 변경 → 같은 측정으로 "Green"** 판정하는 순서로 지킨다. 측정 명령은 전부 `quickstart.md` 에 있다.

**Organization**: 사용자 스토리별. 운영 작업(콘솔·apply·CI)은 AWS 팀 계정 `118178010621`·`ap-northeast-2`·프로필 `kbap-infra`, terraform 은 **메인 체크아웃** `/Users/simjonghan/source_code/swm-kbap/kbap/iac/terraform` 에서 실행한다(워크트리엔 tfvars·`.terraform` 없음). 저장소 커밋은 워크트리에서 한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 대상, 미완료 작업 의존 없음)
- **[Story]**: US1·US2·US3 (spec.md 사용자 스토리)

---

## Phase 1: Setup

**Purpose**: 계정·선후관계 확인, 기존 큐 값 확보

- [X] T001 선행 조건 확인 — `aws sts get-caller-identity --profile kbap-infra --query Account --output text` 가 `118178010621` 인지, Jira KB-548(dev 토큰 복구)이 아직 진행 전인지 확인한다. 둘 중 하나라도 어긋나면 중단하고 사용자에게 보고한다(quickstart.md 전제).
- [X] T002 기존 공유 큐 `kbap-generate-content-queue` 의 `VisibilityTimeout`·`RedrivePolicy`(maxReceiveCount)·`FifoQueue`(false 여야 함)·`SqsManagedSseEnabled`/`KmsMasterKeyId` 를 quickstart.md §1 명령으로 조회하고, 실제 값을 `specs/kb-547-prod-content-queue/data-model.md` 큐 자원 표의 "공유 큐와 동일" 칸과 `specs/kb-547-prod-content-queue/contracts/prod-content-queue.md` 큐 절에 숫자로 채운다. `FifoQueue=true` 거나 CMK 암호화면 research.md R2 전제가 깨지므로 중단·보고.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: prod 전용 큐가 존재해야 terraform 이 이름으로 조회할 수 있다 — US1·US2 모두 이것에 의존

- [X] T003 AWS 콘솔 SQS 에서 DLQ `kbap-prod-generate-content-dlq` 생성 — 표준, 메시지 보존 기간 14일, 암호화 SSE-SQS, 나머지 기본값(quickstart.md §2-1).
- [X] T004 AWS 콘솔 SQS 에서 요청 큐 `kbap-prod-generate-content-queue` 생성 — 표준, 가시성 타임아웃 = T002 값, 메시지 보존 기간 14일, 암호화 SSE-SQS, 배달 못한 편지 대기열 활성화 → `kbap-prod-generate-content-dlq`, 최대 수신 수 = T002 maxReceiveCount(quickstart.md §2-2). 소비자(Lambda 트리거)는 연결하지 않는다(FR-007).

**Checkpoint**: 두 큐 존재 — US1 apply 와 US2 속성 검증을 시작할 수 있다

---

## Phase 3: User Story 1 - prod 요청이 dev 로 흘러가지 않는다 (Priority: P1) 🎯 MVP

**Goal**: prod 배치가 prod 전용 큐로만 발행한다

**Independent Test**: 전환 후 prod 발행분이 전부 새 큐에 있고(SENT 건수 = 새 큐 메시지 수), prod 배치 env·권한이 새 큐만 가리킨다

### 검증 — Red 먼저 (변경 전 상태 측정)

- [X] T005 [US1] 현재 상태 기록 — quickstart.md §5(a) 명령으로 prod 배치 태스크(`kbap-prod-ecs-cluster`/`kbap-prod-ecs-batch`)의 `FOOD_CONTENT_QUEUE_URL` 이 **공유 큐 URL** 임을, §5(b) 로 IAM 역할 `kbap-prod-ecs-batch-task-role` 인라인 정책의 SQS Resource 가 **공유 큐 ARN** 임을 확인해 기록한다. §5(e) 로 dev 배치 태스크 env 값도 기준값으로 기록한다.

### 구현

- [X] T006 [US1] 전환 창 확인 후(KST :05~:25 또는 :35~:55 — 발행 잡 :00·벡터 잡 :30 회피) 메인 체크아웃의 로컬 `iac/terraform/prod.tfvars`(gitignore, 커밋 금지) 11번째 줄 `food_content_queue_name = "kbap-generate-content-queue"` 를 `"kbap-prod-generate-content-queue"` 로 바꾼다. 같은 디렉터리에서 `git status --short` 로 `.tf` 변경이 없는지 확인한다.
- [X] T007 [US1] 메인 체크아웃 `iac/terraform` 에서 `terraform workspace select prod && terraform workspace show` → `terraform plan -var-file=prod.tfvars -replace=module.ecs_environment.aws_ecs_task_definition.batch -out=kb547.plan`. **게이트**: `1 to add, 1 to change, 1 to destroy` — `module.ecs_environment.aws_ecs_task_definition.batch` 교체(env `FOOD_CONTENT_QUEUE_URL` 새 큐 URL) + `module.ecs_environment.aws_iam_role_policy.batch_task` in-place 뿐. 다른 변경이 있으면 apply 하지 않고 보고한다(T006 원복 포함).
- [X] T008 [US1] 메인 체크아웃 `iac/terraform` 에서 `terraform apply kb547.plan` 실행, 완료 시각 `T`(KST, 초 단위)를 기록한다. 이후 `kb547.plan` 파일을 삭제한다.
- [X] T009 [US1] 현재 실행 중 배치 이미지 태그(`batch-<sha>`)를 quickstart.md §4 명령으로 얻고, GitHub Actions `.github/workflows/deploy-batch-prod.yml`(Deploy batch prod)을 `workflow_dispatch` 로 `image_tag` = 그 태그로 실행한다(`gh workflow run deploy-batch-prod.yml -f image_tag=<tag>` 가능). 서비스 이벤트에서 롤링 완료(steady state, running 1)를 확인한다.

### 검증 — Green

- [X] T010 [US1] quickstart.md §5(a) 재실행 → `FOOD_CONTENT_QUEUE_URL` = `kbap-prod-generate-content-queue` URL, §5(b) → 배치 롤 SQS Resource 가 새 큐 ARN 하나. 옛 URL 이면 T009 가 최신 리비전을 복제하지 않은 것 — 서비스 태스크 정의 리비전을 확인해 보고한다.
- [ ] T011 [US1] 다음 정시(:00) 이후 CloudWatch 배치 로그에서 `음식 콘텐츠 아웃박스 발행 완료 attempted=N succeeded=N failed=0` 확인. `attempted=0` 이면 자연 유입을 기다리거나(사용자 확인 후) prod FAILED 음식 1건을 관리자 재요청해 다음 정시에 재확인. 이어서 quickstart.md §5(d) — prod DB 읽기 전용(바스티온 터널 13308 + `mysql-prod` MCP) `SELECT COUNT(*) FROM food_content_outbox WHERE sent_at >= 'T' AND status = 'ACTIVE'` 와 새 큐 `ApproximateNumberOfMessages + ApproximateNumberOfMessagesNotVisible` 가 같은지 대조한다(SC-001·SC-002).
- [X] T012 [US1] quickstart.md §5(e) 재실행 → dev 배치 `FOOD_CONTENT_QUEUE_URL` 이 T005 기준값(공유 큐)과 같음을 확인한다(FR-006·SC-004).

**Checkpoint**: prod→dev 유출 경로 차단 — KB-548 진행 가능

---

## Phase 4: User Story 2 - 소비자 연결 전까지 prod 요청이 유실되지 않는다 (Priority: P2)

**Goal**: prod 큐·DLQ 가 14일 보존·기존과 같은 재시도 정책을 갖고, 소비자 없이 메시지가 대기한다

**Independent Test**: 두 큐 속성 조회 결과가 data-model.md 검증 규칙과 일치하고, 새 큐에 이벤트 소스 매핑이 없다

- [X] T013 [P] [US2] quickstart.md §2-3 명령으로 `kbap-prod-generate-content-queue` 의 `MessageRetentionPeriod=1209600`, `VisibilityTimeout`·`maxReceiveCount` = T002 값, `deadLetterTargetArn` = `…:kbap-prod-generate-content-dlq` 확인(T004 직후 가능 — US1 과 병렬).
- [X] T014 [P] [US2] 같은 방식으로 `kbap-prod-generate-content-dlq` 의 `MessageRetentionPeriod=1209600` 확인.
- [ ] T015 [US2] `aws lambda list-event-source-mappings --event-source-arn <새 큐 ARN> --profile kbap-infra --region ap-northeast-2` 가 빈 목록인지, T011 이후 새 큐 메시지 수가 10분 간격 두 번 조회에서 줄지 않는지 확인한다(FR-007, 대기 보존).

**Checkpoint**: KB-549·KB-551 이 contracts/prod-content-queue.md 를 전제로 진행 가능

---

## Phase 5: User Story 3 - 다음 셋업에서 prod 가 공유 큐로 회귀하지 않는다 (Priority: P3)

**Goal**: 저장소 예시·문서가 prod 전용 큐를 명시한다

**Independent Test**: `grep food_content_queue_name iac/terraform/prod.tfvars.example` 가 prod 전용 큐를 가리킨다

- [X] T016 [P] [US3] Red: `grep -n food_content_queue_name iac/terraform/prod.tfvars.example` 가 비어 있음을 확인 → `storage_key_prefix` 줄 다음 빈 줄 뒤에 `food_content_queue_name = "kbap-prod-generate-content-queue"` 를 추가하고, 바로 위 한 줄 주석으로 "콘텐츠 생성 요청 큐는 환경별(KB-547) — 생략하면 기본값(dev 공유 큐)으로 떨어져 prod 요청이 dev 로 샌다" 를 단다 → 같은 grep 이 새 줄을 찾는지 확인(Green). 파일 `iac/terraform/prod.tfvars.example`.
- [X] T017 [P] [US3] `iac/terraform/README.md` 27번째 줄 표(`RDS·Redis·VPC·S3·SQS·Route53 존·ACM | 기존 인프라 (data 로 조회 …)`) 설명 칸에 "SQS 콘텐츠 요청 큐는 환경별 — dev `kbap-generate-content-queue`, prod `kbap-prod-generate-content-queue`(콘솔 생성, `food_content_queue_name`)" 을 덧붙인다. 큐 이름을 바꾸면 태스크 정의 `-replace` + 배치 CI 재배포가 필요하다는 점도 같은 칸에 짧게 적는다.

**Checkpoint**: 저장소 변경 완료 — 운영 작업과 무관하게 머지 가능

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T018 [P] 지식 위키 `../kbap-agenthub/wiki/langchain-food-ingest-contract.md` 에 "요청 큐는 환경별(dev 공유 큐 / prod `kbap-prod-generate-content-queue`, KB-547, 2026-09-12) — prod 메시지의 id 는 prod DB 값이라 결과는 prod 적재 API 로만" 을 추가하고 `../kbap-agenthub/INDEX.md` 해당 줄 설명을 갱신, 허브 저장소에서 커밋한다.
- [ ] T019 Jira KB-547 에 결과 코멘트 초안 작성(새 큐·DLQ ARN, T002 속성값, apply 시각 T, T010~T015 결과) → **사용자 승인 후** 등록하고 DoD 3항목 체크. KB-548 이 진행 가능해졌음을 함께 적는다.
- [ ] T020 워크트리에서 `specs/kb-547-prod-content-queue/`·`iac/terraform/prod.tfvars.example`·`iac/terraform/README.md` 를 커밋하고(`prod.tfvars` 는 gitignore — 스테이징되지 않았는지 `git status` 로 확인), `open-draft-pr-to-develop` 절차로 develop 대상 draft PR 을 연다.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (T001→T002)**: 즉시 시작. T002 값이 T004 에 들어간다.
- **Foundational (T003→T004)**: T002 완료 후. US1·US2 를 막는다.
- **US1 (T005→T012)**: T004 후. 순차 — T006~T009 는 한 전환 창 안에서 연속 실행.
- **US2**: T013·T014 는 T004 직후(US1 과 병렬), T015 는 T011 후.
- **US3 (T016·T017)**: 어떤 단계에도 의존하지 않는다 — 언제든(맨 처음이라도) 가능.
- **Polish**: T018 은 T011 후 언제든, T019 는 T015 후, T020 은 T016·T017 후(운영 작업 결과는 PR 본문에 반영 가능하면 T019 이후).

### User Story Dependencies

- **US1 (P1)**: Foundational 에만 의존.
- **US2 (P2)**: 속성 검증은 Foundational 에만, 대기 보존 확인(T015)은 US1 발행 결과(T011)에 의존.
- **US3 (P3)**: 독립.

### Within Each User Story

- 측정(Red) → 변경 → 같은 측정(Green). US1 에서 plan 게이트(T007) 실패 시 이후 작업 전부 중단.

## Parallel Opportunities

- T013·T014 (US2 속성 검증) ‖ US1 T005~T009
- T016·T017 (US3 저장소 변경) ‖ 모든 운영 작업
- T018 (위키) ‖ T019 초안

## Parallel Example: US2 + US3 while US1 waits for the :00 publish

```bash
# T009 롤링 후 다음 정시를 기다리는 동안:
Task: "T013 prod 큐 속성 검증 (quickstart §2-3)"
Task: "T014 prod DLQ 보존 기간 검증"
Task: "T016 iac/terraform/prod.tfvars.example 에 food_content_queue_name 추가"
Task: "T017 iac/terraform/README.md SQS 행에 환경별 큐 명시"
```

---

## Implementation Strategy

### MVP First (US1)

1. T001~T004 (계정·큐 생성)
2. T005~T012 — 한 전환 창에서 apply + CI 롤링, 다음 정시 발행으로 검증
3. **STOP and VALIDATE**: SENT 건수 = 새 큐 메시지 수, dev 무변경 → KB-548 차단 해제

### Incremental Delivery

1. US3 (T016·T017) 는 운영 작업과 독립이라 먼저 커밋해 둬도 된다
2. US1 → 유출 차단(핵심 가치)
3. US2 → 소비자 연결(KB-551) 전 보존 확인
4. Polish → 위키·Jira·PR

---

## Notes

- 운영 작업(T001~T015)은 사용자 AWS 권한·콘솔·GitHub Actions 가 필요하다 — 에이전트는 명령·확인값을 준비하고, 콘솔 생성·apply·워크플로 실행은 사용자 승인 하에 진행한다.
- prod DB 는 읽기만 한다(쓰기 금지).
- 공유 큐·공유 DLQ 의 기존 메시지와 SENT 에 멈춘 prod 요청 173건은 건드리지 않는다(FR-009, research R5).
- 롤백: quickstart.md "롤백" 절 — 되돌리면 KB-548 보류.

## 실행 기록

- 2026-09-12 T001: `kbap-infra` → 계정 `118178010621`. KB-548 상태 "할 일"(미착수).
- 2026-09-12 T002: 공유 큐 표준(FifoQueue 없음)·SSE-SQS(CMK 없음)·VisibilityTimeout 1800·maxReceiveCount 2·보관 172800(2일)·DLQ `kbap-generate-content-dlq`. 큐 목록은 이 둘뿐.
- 2026-09-12 T005 (Red): prod 배치 `kbap-prod-ecs-batch:2`(running 1, 이미지 `batch-b7f56e60dcf3c131409be5e2836f0159ba1aef36`)·dev 배치 `kbap-dev-ecs-batch:34` 모두 `FOOD_CONTENT_QUEUE_URL` = 공유 큐 URL. `kbap-prod-ecs-batch-task-role` SQS Resource = 공유 큐 ARN.
- 2026-09-12 T003·T004: 콘솔 대신 `kbap-infra` CLI(`aws sqs create-queue`)로 생성(사용자 선택). T013·T014: 큐 보관 1209600·VT 1800·redrive → prod DLQ maxReceiveCount 2·SSE-SQS·표준, DLQ 보관 1209600·SSE-SQS. T015(전반): 새 큐 이벤트 소스 매핑 없음.
- 2026-09-12 T006: 메인 체크아웃 `iac/terraform/prod.tfvars` 11행을 `kbap-prod-generate-content-queue` 로 교체. 메인 체크아웃 develop `c35969bc`·`.tf` 가 origin/develop 과 같음·workspace `prod`. tfvars `batch_image` = 실행 중 태그와 동일.
- 2026-09-12 T007 1차 plan: `3 to add, 1 to change, 1 to destroy` — 기대분(배치 태스크 정의 교체·배치 롤 정책 in-place) 외에 #240(`e0bae18c`, api 오토스케일링 2~4·CPU 40%)의 미적용 리소스 `aws_appautoscaling_target.api`·`aws_appautoscaling_policy.api_cpu` 가 섞임(dev 포함 어디에도 scalable target 없음). 게이트 불통과 → plan 파일 삭제, 사용자 결정으로 `-target` 두 리소스만 재plan. 오토스케일링은 #240 자체 롤아웃(dev 먼저)으로 남긴다.
- 2026-09-12 T007 2차(타깃) plan: `-target` 배치 태스크 정의·`aws_iam_role_policy.batch_task` → `1 to add, 1 to change, 1 to destroy`, 변경은 `FOOD_CONTENT_QUEUE_URL`·SQS Resource 두 값뿐, 이미지 불변. 게이트 통과.
- 2026-09-12 T008: apply 완료 **T = 2026-09-12 05:14:08 KST**(1/1/1, `-target` 경고는 정상). 새 리비전 `kbap-prod-ecs-batch:4`(새 큐 URL). plan 파일 삭제.
- 2026-09-12 T009: `gh workflow run deploy-batch-prod.yml --ref main -f image_tag=batch-b7f56e60…` → run `34643121217` success, 서비스가 `:4` 복제본 `:5` 로 롤링(env 승계 확인).
- 2026-09-12 T010(b): `kbap-prod-ecs-batch-task-role` 인라인 `sqs-s3` SQS Resource = 새 큐 ARN 하나. T012: dev `kbap-dev-ecs-batch:34` 여전히 공유 큐 URL.
- 2026-09-12 quickstart §5(a) 조회식 수정 — `containerDefinitions[?…].environment[?…]` 는 바깥 리스트를 필터해 항상 빈 값이라 `| [0]` 파이프로 교정.
- 2026-09-12 05:22 T009 1차 실패: CI run 34643121217 은 success 였으나 ECS 가 `:5` 를 못 띄우고 서킷브레이커로 `:2` 롤백 — `-replace` 리비전에 #256(Sentry, develop 전용 롤아웃)의 `batch_secret_names` 기본값 `BATCH_SENTRY_DSN` 이 붙었는데 SSM `/kbap/prod/BATCH_SENTRY_DSN` 부재. 그 사이 prod 는 `:2`(옛 URL) + IAM 새 큐 = fail-closed.
- 2026-09-14 사용자가 Sentry prod 프로젝트(api·batch) 생성, SSM `/kbap/prod/API_SENTRY_DSN`·`BATCH_SENTRY_DSN` SecureString 등록(기본 KMS). 로컬 awscli 는 brew 의존(aws-c-s3) 불일치로 깨져 `brew upgrade awscli` 로 복구.
- 2026-09-14 13:08 T009 재실행: run 34804921037 success → `:6`(`:5` 복제, 이미지 `batch-b7f56e60…` 동일) 롤링, 13:12 RUNNING·HEALTHY. T010(a): `:6` env `FOOD_CONTENT_QUEUE_URL` = 새 큐 URL, secrets DB_PASSWORD·OPENAI_API_KEY·BATCH_SENTRY_DSN.
- 2026-09-12 T016·T017: 예시에 `food_content_queue_name` 추가(+ 낡은 배치 0/0 주석을 실제 결정 1/1 로 정정), README 27행에 환경별 큐·`-replace` 필요 명시.
- 2026-09-14 13:15 지난 이틀 prod 발행 로그 전부 `attempted=0`·AccessDenied 0 — 전환 창에 PENDING 이 없어 fail-closed 경로도 실제로는 안 밟음. 새 큐 0건·ESM 0. T011 은 실제 발행 1건이 필요 → 사용자 관리자 재요청 대기. T018: 허브 `58f4819` 커밋(다른 세션 미커밋 변경은 스테이징 제외).
