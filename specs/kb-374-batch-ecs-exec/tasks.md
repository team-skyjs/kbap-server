# Tasks: 배치 잡 원격 트리거 — ECS Exec 활성화

**Input**: Design documents from `/specs/kb-374-batch-ecs-exec/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 앱 코드 변경이 없는 IaC 작업이라 Kotest 대상이 없다 — 대신 각 스토리의 "Red" 는 **구현 전에 실패를 관측하는 실행 가능한 검증**(quickstart 절차·shellcheck·terraform validate)으로 고정하고, 구현 후 같은 절차로 Green 을 판정한다. Red 관측 결과는 tasks 진행 중 해당 항목 옆에 기록한다.

**Organization**: 스토리별 독립 검증 가능. 단, Terraform apply·배치 재배포(Phase 2)는 dev 환경 실물을 바꾸므로 **인프라 권한 프로필(`kbap-infra`)이 있을 때만** 진행 — 없으면 Phase 2 의 정적 검증(T004~T006)까지 하고 apply 이후는 사용자에게 넘긴다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- Terraform 모듈: `iac/terraform/modules/ecs-environment/`
- 루트 스택: `iac/terraform/` (`main.tf`·`outputs.tf`·`README.md`)
- 운영 스크립트: `iac/scripts/`
- 배치 이미지: `Dockerfile.batch`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 호출 측 도구 확인과 정적 검증 기준선 — 변경 전 상태를 기록한다.

- [ ] T001 호출 호스트 전제 확인: `aws --version`(v2)·`session-manager-plugin --version` 실행, 없으면 `brew install --cask session-manager-plugin` 후 재확인 (quickstart 0단계)
- [x] T002 [P] 정적 기준선: `cd iac/terraform && terraform init -backend=false && terraform validate && terraform fmt -check -recursive` 가 변경 전에 통과함을 확인(이후 회귀 기준) — 통과(2026-08-25)
- [x] T003 [P] 배치 이미지 curl 유무 판정: `Dockerfile.batch` 런타임 스테이지 `eclipse-temurin:21-jre` 를 로컬에서 `docker run --rm eclipse-temurin:21-jre curl --version` 으로 확인 — 있으면 Dockerfile 무변경으로 확정, 없으면 T011 활성화 (research R4) — **curl 8.18 포함** → Dockerfile 무변경, T011 해당 없음

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Exec 채널 자체 — 서비스 플래그 + 태스크 역할 권한. 모든 스토리가 이 위에서만 동작한다.

**⚠️ CRITICAL**: 이 단계가 dev 에 적용·재배포되기 전엔 어떤 스토리도 Green 이 될 수 없다.

### Red (구현 전 실패 관측)

- [x] T004 (Red 기록 2026-08-25: `describe-services` → `enableExecuteCommand=false`, running 0 — 배치 태스크가 없어 execute-command 자체를 시도할 수 없음. `kbap-infra` 프로필은 user/kbap-infra 로 존재) Red 관측: 현재 dev 배치 태스크에 `aws ecs execute-command --cluster kbap-dev-ecs-cluster --task <arn> --container batch --interactive --command "true" --profile kbap-infra` 실행 → `execute command was not enabled` 류 오류로 실패함을 기록 (quickstart 4단계의 역상태). 인프라 프로필이 없으면 `aws ecs describe-services --query 'services[0].enableExecuteCommand'` 가 `false` 임을 deployer 프로필로 기록

### Implementation

- [x] T005 `iac/terraform/modules/ecs-environment/batch.tf` — `resource "aws_ecs_service" "batch"` 에 `enable_execute_command = true` 추가(`launch_type` 다음 줄). `lifecycle.ignore_changes` 는 `[task_definition]` 그대로 유지
- [x] T006 `iac/terraform/modules/ecs-environment/iam.tf` — `data "aws_iam_policy_document" "batch_task"` 에 statement 추가: `sid = "EcsExecChannel"`, actions `ssmmessages:CreateControlChannel`·`CreateDataChannel`·`OpenControlChannel`·`OpenDataChannel`, resources `["*"]` (contracts/operator-iam-policy.md 태스크 역할 절)
- [ ] T007 정적 검증: `cd iac/terraform && terraform fmt -recursive && terraform validate` 통과, `terraform plan -var-file=dev.tfvars` 결과가 **`aws_ecs_service.batch`(in-place: enable_execute_command) + `aws_iam_role_policy.batch_task`(in-place) 2건만**인지 확인 — SG·태스크 정의·ALB 변경이 보이면 중단
- [ ] T008 dev apply + 배치 강제 재배포: `terraform apply -var-file=dev.tfvars` → `aws ecs update-service --cluster kbap-dev-ecs-cluster --service kbap-dev-ecs-batch --force-new-deployment --profile kbap-infra` → `aws ecs wait services-stable` (research R5 — 에이전트는 새 태스크부터 주입)
- [ ] T009 Green 관측: `aws ecs describe-tasks ... --query 'tasks[0].containers[0].managedAgents[?name==\`ExecuteCommandAgent\`].lastStatus'` → `RUNNING`, T004 의 명령이 이제 성공

**Checkpoint**: Exec 채널 준비 — 스토리 구현 시작 가능

---

## Phase 3: User Story 1 - 운영자가 클러스터 밖에서 배치 잡을 실행한다 (Priority: P1) 🎯 MVP

**Goal**: `iac/scripts/batch-job.sh run|status` 로 클라우드 밖에서 잡 실행 지시·상태 조회. 배치 포트는 계속 미개방.

**Independent Test**: quickstart 5단계 — `run dev <jobName>` exit 0 + executionId → `status` 로 COMPLETED/FAILED 확인 → 잘못된 잡 exit 1(잡 목록) → 실행 중 재호출 exit 2(ALREADY_RUNNING) → 7단계 인스턴스 IP:8080 직접 접속 실패.

### Tests for User Story 1 (Test-First: 먼저 작성·실패 확인) ⚠️

- [x] T010 (Red 기록: 뼈대 `run dev foodVectorSyncJob` → exit 3, shellcheck 경고는 미구현 변수 2건뿐) [US1] 검증 스크립트 뼈대를 먼저 작성해 Red 확인: `iac/scripts/batch-job.sh` 를 **사용법·전제 검사만 있는 상태**로 만들고(`run`/`status` 는 `exit 3 "not implemented"`), `shellcheck iac/scripts/batch-job.sh` 통과 + `iac/scripts/batch-job.sh run dev foodVectorSyncJob` 이 exit 3 으로 실패함을 기록 (contracts/remote-job-run.md 의 종료 코드 표가 테스트 명세)
- [x] T011 (해당 없음 — T003 에서 curl 포함 확인) [US1] (T003 에서 curl 부재로 판정된 경우에만) `Dockerfile.batch` 런타임 스테이지에 `RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*` 추가 → `deploy-batch.sh dev <새 sha>` 로 재배포. curl 이 있으면 이 태스크는 "해당 없음"으로 체크

### Implementation for User Story 1

- [x] T012 (shellcheck 0, usage·플러그인 부재 exit 3 확인 — 라이브 202/404/409 는 T013) [US1] `iac/scripts/batch-job.sh` 구현 — `set -euo pipefail`; 인자 `run <env> <jobName> [profile]` / `status <env> <executionId> [profile]`; 프로필 기본값 `${AWS_PROFILE:-kbap-<env>-batch-operator}`; 전제 검사(`session-manager-plugin` 부재 → exit 3 안내); `aws ecs list-tasks --cluster kbap-<env>-ecs-cluster --service-name kbap-<env>-ecs-batch --desired-status RUNNING` 로 태스크 ARN(없으면 exit 3 "배치 미기동 또는 Exec 미적용"); `aws ecs execute-command --container batch --interactive --command "curl -s -w '\n%{http_code}' -X POST 'http://localhost:8080/internal/batch/jobs?jobName=<job>'"`(status 는 GET `/internal/batch/executions/<id>`); 출력 마지막 줄 HTTP 코드로 종료 코드 매핑(202/200→0, 404→1, 409→2), 본문은 가공 없이 stdout (research R6)
- [ ] T013 [US1] Green 확인 — quickstart 5단계 4개 케이스를 실제 dev 에서 실행해 종료 코드·본문이 contracts/remote-job-run.md 표와 일치함을 기록 (`shellcheck` 재통과 포함)
- [ ] T014 [US1] 포트 미개방 확인 — quickstart 7단계 `curl -m 5 http://<batch-instance-public-ip>:8080/internal/batch/jobs` 가 실패하고, `terraform plan` 에 `sg.tf` 변경이 없음을 재확인 (FR-003)

**Checkpoint**: 클라우드 밖에서 잡 실행·조회 가능, 포트 개방 0 — MVP

---

## Phase 4: User Story 2 - dev 와 prod 실행 권한이 분리된다 (Priority: P2)

**Goal**: 환경별 운영 IAM 사용자·정책을 Terraform 이 생성. dev 자격증명은 dev 클러스터의 `batch` 컨테이너에만 유효.

**Independent Test**: quickstart 6단계 — dev 사용자로 prod 클러스터 `list-tasks` → AccessDenied, dev 사용자로 api 컨테이너 `execute-command` → AccessDenied, 자기 환경 `batch` 는 성공.

### Tests for User Story 2 (Test-First: 먼저 작성·실패 확인) ⚠️

- [x] T015 [US2] (Red 기록 2026-08-25: `get-user` → `NoSuchEntity`) Red 관측: 운영 사용자가 아직 없음을 `aws iam get-user --user-name kbap-dev-ecs-batch-operator --profile kbap-infra` 의 `NoSuchEntity` 로 기록. quickstart 6단계의 거부 시나리오 2건을 이 시점엔 실행 불가(사용자 부재)로 기록

### Implementation for User Story 2

- [x] T016 [US2] `iac/terraform/modules/ecs-environment/iam.tf` — 섹션 `# --- batch 운영 사용자 (원격 잡 실행) ---` 추가: `resource "aws_iam_user" "batch_operator"`(name `${local.name_prefix}-batch-operator`, tags), `data "aws_iam_policy_document" "batch_operator"` 2 statement — (1) `ecs:ListTasks`·`ecs:DescribeTasks` on `*`, condition `ArnEquals ecs:cluster = aws_ecs_cluster.this.arn`; (2) `ecs:ExecuteCommand` on `"arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:task/${aws_ecs_cluster.this.name}/*"`, condition `StringEquals ecs:container-name = local.batch_container_name` — `resource "aws_iam_user_policy" "batch_operator"` 로 부착. **`aws_iam_access_key` 는 만들지 않는다**(research R3)
- [x] T017 [P] [US2] `iac/terraform/modules/ecs-environment/outputs.tf` 에 `output "batch_operator_user_name" { value = aws_iam_user.batch_operator.name }` 추가, `iac/terraform/outputs.tf` 에 동명 패스스루 추가
- [ ] T018 [US2] 정적 검증: `terraform fmt -recursive && terraform validate`, `terraform plan -var-file=dev.tfvars` 가 `aws_iam_user.batch_operator`·`aws_iam_user_policy.batch_operator` 신규 2건(+ output)만 추가함을 확인 → `terraform apply -var-file=dev.tfvars`
- [ ] T019 [US2] 액세스 키 발급(사람): 콘솔 IAM → `kbap-dev-ecs-batch-operator` → 액세스 키 생성 → `aws configure --profile kbap-dev-batch-operator`. 키는 어떤 파일·레포에도 커밋하지 않는다
- [ ] T020 [US2] Green 확인 — quickstart 6단계: `AWS_PROFILE=kbap-dev-batch-operator` 로 (a) `list-tasks --cluster kbap-prod-ecs-cluster` AccessDenied, (b) api 태스크에 `--container api` execute-command AccessDenied, (c) `iac/scripts/batch-job.sh run dev <jobName>` 성공(운영 사용자 자격증명만으로 US1 경로 재현). 세 결과를 기록

**Checkpoint**: US1 + US2 — 환경 착각 사고가 IAM 수준에서 차단됨

---

## Phase 5: User Story 3 - 원격 실행 절차가 문서로 남는다 (Priority: P3)

**Goal**: 팀원이 README 만 보고 잡 원격 실행에 첫 시도 성공.

**Independent Test**: quickstart 8단계 — README 의 "배치 잡 원격 실행" 절만 따라 `run`/`status` 재현.

### Tests for User Story 3 (Test-First) ⚠️

- [x] T021 [US3] Red 관측: `grep -n "원격 실행\|batch-job.sh\|execute-command" iac/terraform/README.md` 가 0건임을 기록(문서 부재) — 0건 확인(2026-08-25)

### Implementation for User Story 3

- [x] T022 [US3] `iac/terraform/README.md` — "## 배치 잡 원격 실행 (ECS Exec)" 절 추가: 전제(AWS CLI v2 + Session Manager plugin, 환경별 운영 프로필 `kbap-<env>-batch-operator` — 키는 콘솔 발급), 명령 예시 `iac/scripts/batch-job.sh run dev <jobName>` / `status dev <id>`, 종료 코드 표(0/1/2/3/≥100), 젠킨스 파이프라인 예시(run → executionId 파싱 → status 30초 폴링), "배치 재배포 후 Exec 에이전트 RUNNING 확인" 한 줄, 포트 미개방·권한 경계 요약. 기존 "소유권 경계" 표에 `운영 IAM 사용자·정책 | Terraform` / `액세스 키 | 사람(콘솔)` 행 추가, "AWS 권한" 행에 batch operator 언급
- [ ] T023 [US3] Green 확인 — 세션 밖 팀원(또는 새 셸에서 README 만 보고) 5단계 재현 성공 기록; `grep` 재실행으로 절 존재 확인

**Checkpoint**: 세 스토리 모두 독립 검증 완료

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T024 [P] `specs/kb-374-batch-ecs-exec/quickstart.md` 의 각 단계 옆에 dev 실측 결과(날짜·명령·출력 요약) 기록 — 이 문서가 prod 적용 시 체크리스트가 된다
- [x] T025 [P] `shellcheck iac/scripts/batch-job.sh` 및 `terraform fmt -check -recursive` 최종 통과, `./gradlew :batch:test` 그린(앱 무변경 확인) — 셋 다 통과(2026-08-25)
- [x] T026 커밋 — 한국어 Conventional Commits: `feat(infra): 배치 잡 원격 트리거 — ECS Exec 활성화·운영 IAM 사용자·batch-job.sh` (본문에 소유권 경계·키 미보관·prod 미적용 명시)
- [x] T027 (README 에 적용 상태 한 줄 명시 — prod 는 dev 검증 후 별도 창) prod 적용 판단 메모: quickstart "prod" 절대로 `prod.tfvars` 반복은 **배포 창에 맞춰 별도 실행** — 이 PR 범위는 dev 검증까지. README 에 prod 미적용 상태를 한 줄 명시

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 즉시 시작. T002·T003 병렬
- **Foundational (Phase 2)**: T004(Red) → T005·T006(다른 파일, 병렬 가능) → T007 → T008(apply·재배포 — 인프라 프로필 필요) → T009(Green). **전 스토리 차단**
- **US1 (Phase 3)**: Phase 2 완료 후. T010(Red) → T011(조건부) → T012 → T013·T014
- **US2 (Phase 4)**: Phase 2 완료 후, US1 과 독립(단 T020(c) 가 US1 스크립트를 쓰므로 T012 이후가 자연스러움). T015 → T016·T017(병렬) → T018(apply) → T019(사람) → T020
- **US3 (Phase 5)**: US1·US2 의 결과(명령·프로필명)를 문서화하므로 그 뒤. T021 → T022 → T023
- **Polish (Phase 6)**: 전부 완료 후

### Parallel Opportunities

- Phase 1: T002 ∥ T003
- Phase 2: T005 ∥ T006 (batch.tf / iam.tf)
- Phase 4: T016 ∥ T017 (iam.tf / outputs.tf)
- Phase 6: T024 ∥ T025

---

## Parallel Example: Phase 2

```bash
# 다른 파일 — 동시 편집 가능
Task: "batch.tf — aws_ecs_service.batch 에 enable_execute_command = true"
Task: "iam.tf — batch_task 정책에 EcsExecChannel statement"
# 이후 순차: fmt/validate/plan → apply → force-new-deployment → 에이전트 RUNNING 확인
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 → Phase 2 (dev apply + 재배포 — 여기서 인프라 프로필 필요)
2. Phase 3: `batch-job.sh` 구현 → quickstart 5·7단계 통과
3. **STOP and VALIDATE** — 이 시점에 홈서버 젠킨스가 `kbap-infra` 프로필로도 잡을 돌릴 수 있음(운영 사용자는 US2)

### Incremental Delivery

- US1 → 잡 원격 실행 가능(MVP)
- US2 → 최소 권한 운영 사용자로 교체, 교차 환경 차단
- US3 → 팀 공유 가능
- prod 는 dev 8단계 통과 후 별도 배포 창에 동일 절차(T027)

---

## Notes

- 인프라 권한 프로필(`kbap-infra`)이 없으면 T008·T018·T019 이후는 사용자가 실행 — Claude 는 그 전까지(코드·정적 검증·Red 기록)를 마치고 명령을 넘긴다.
- `deploy-batch.sh` 의 기본 프로필이 `kbap-prod-deployer` 인 것과 별개로, apply·force-new-deployment 는 인프라 프로필이 필요하다.
- 태스크 정의(`ignore_changes`)·SG·ALB 는 이 기능이 건드리지 않는다 — plan 에 나타나면 회귀다.
