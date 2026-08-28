# Tasks: state 복구 → prod Alloy → 공개 진입점 차단 (KB-390)

**Input**: Design documents from `/specs/kb-390-alb-allowlist-state-import/`

**Prerequisites**: plan.md, spec.md, research.md(R-1~R-9), contracts/backend-and-alb-rule.md, contracts/import-ids.md, quickstart.md

**Tests**: 작성하지 않는다(사용자 지시, plan Constitution Check 원칙 I 면제). 판정은 plan 숫자·`state list` 개수·curl·Grafana·카나리 1회. 스크립트는 dev state 와 id 전수 대조(`--check`)로 검증.

**Organization**: US1(장부 복구)이 US2(prod Alloy)·US3(prod 차단)의 전제. US3 의 dev 부분은 US1 의 dev 이관 뒤 바로 가능.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능 · **[Story]**: US1 장부 / US2 prod Alloy / US3 차단 규칙
- **(사용자)** 표시 = AWS 콘솔·자격증명·맥미니 조작이 필요해 사용자가 실행. 나머지는 이 세션이 맥북에서 실행

## Path Conventions

- `iac/terraform/{versions.tf,variables.tf,main.tf,.gitignore,README.md,dev.tfvars.example,prod.tfvars.example}`
- `iac/terraform/modules/ecs-environment/{alb.tf,variables.tf}` · `iac/scripts/gen-import-blocks.sh`

---

## Phase 1: Setup (코드 — 백엔드·규칙·스크립트, apply 전 전부 작성)

- [X] T001 `iac/terraform/versions.tf` 백엔드 주석 해제·수정 — contracts/backend-and-alb-rule.md §1 그대로(`bucket kbap-terraform-state`, `key ecs/terraform.tfstate`, `region`, `profile = "kbap-infra"`, `use_lockfile = true`, `encrypt = true`). 종전 "key 로 분리" 주석은 "workspace dev/prod 로 분리" 로 교체
- [X] T002 [P] `iac/terraform/.gitignore` 에 `import.*.tf`, `*.tfvars.generated`, `_local-state-archive/` 추가
- [X] T003 [P] `iac/terraform/modules/ecs-environment/variables.tf` 에 `blocked_path_patterns`(list(string), 기본 `["*actuator*"]`, description: 공개 진입점에서 404 로 막을 ALB path-pattern — CodeDeploy 가 기본 액션만 전환하므로 허용 목록이 아닌 거부 규칙, `*` 는 `/` 포함) 추가; 루트 `variables.tf`·`main.tf` 전달; `dev.tfvars.example`(기본) / `prod.tfvars.example`(`["*actuator*","*swagger*","*api-docs*"]`) 예시
- [X] T004 `iac/terraform/modules/ecs-environment/alb.tf` 에 `aws_lb_listener_rule.block_paths` — contracts §2 그대로(HTTPS 리스너, priority 10, `path_pattern { values = var.blocked_path_patterns }`, `fixed-response 404 text/plain`). 주석: 기본 액션은 CodeDeploy 소유라 건드리지 않음, `%61` 우회는 실측(R-6)
- [X] T005 `iac/scripts/gen-import-blocks.sh` 작성 — `usage: gen-import-blocks.sh <dev|prod> [--check] [aws-profile]`(기본 `kbap-infra`, 리전 고정). contracts/import-ids.md 의 49개를 순서대로 조회해 `iac/terraform/import.<env>.tf`(`import { to = module.ecs_environment.<addr>  id = "<id>" }`)와 `<env>.tfvars.generated`(research R-5 표) 를 쓴다. SG 규칙은 `describe-security-group-rules --filters Name=group-id` 결과를 방향·포트·referenced-group/cidr 로 매칭. 조회 실패·복수 매칭은 `>&2` 로 출력하고 **exit 1**(부분 파일 남기지 않음). `--check`: 파일을 쓰지 않고 `terraform state show <addr>` 의 id 와 대조해 불일치 목록 출력, 전수 일치면 exit 0
- [X] T006 `bash -n iac/scripts/gen-import-blocks.sh` + `shellcheck`(있으면) + `/opt/homebrew/bin/terraform fmt -recursive iac/terraform` + 스크래치 복사본 `terraform init -backend=false && terraform validate`
- [X] T007 커밋: `feat(infra): terraform S3 백엔드·prod import 스크립트·ALB 관리경로 거부 규칙 (KB-390)` — T001~T005(apply 는 아직)

---

## Phase 2: Foundational — 사용자 준비물 (블로킹)

- [X] T008 (사용자) 맥북에 `kbap-infra` 프로필 등록 — 맥미니 `~/.aws/credentials` 의 `[kbap-infra]` 복사 또는 콘솔에서 재발급. 확인 `aws sts get-caller-identity --profile kbap-infra` → 118178010621
- [X] T009 (사용자) state 버킷 생성 — contracts §1 의 명령 3개(생성·버저닝·퍼블릭 차단). 확인 `aws s3api get-bucket-versioning --bucket kbap-terraform-state --profile kbap-infra` → Enabled
- [X] T010 **대체 — 맥미니 없이 dev 도 import 로 복구(사용자 결정).** (사용자·맥미니) dev 장부 내보내기 — quickstart §2-a: `workspace select dev-ecs && terraform state pull > ~/dev-ecs.tfstate`, `terraform state list | wc -l` 기록, `dev.tfvars` 와 함께 맥북으로 전달(scp/AirDrop, 채팅 금지)

**Checkpoint**: 맥북에 `kbap-infra`·버킷·`dev-ecs.tfstate`·`dev.tfvars` 준비 → US1 착수

---

## Phase 3: User Story 1 — 이 컴퓨터에서 dev·prod 를 terraform 으로 다룬다 (Priority: P1) 🎯 MVP

**Goal**: S3 백엔드 + workspace `dev`(이관)·`prod`(import). 양쪽 `plan` "No changes"(prod 는 Alloy 3 add 제외).

**Independent Test**: 맥북 `terraform plan -var-file=dev.tfvars` No changes · `prod` plan "49 import / 3 add / 0 change / 0 destroy" → apply → 재plan No changes · 맥미니 재초기화 후 dev plan No changes.

### Implementation for User Story 1

- [X] T011 [US1] **import 로 수행: 55 import / 1 add(규칙) / 7 change(무해, bastion ami 는 ignore_changes 로 고정) / 0 destroy → apply → 재plan No changes.** 맥북 `iac/terraform`: `terraform init`(S3 백엔드) → `terraform workspace new dev` → `terraform state push ~/dev-ecs.tfstate` → `terraform state list | wc -l` 이 맥미니 기록과 동일 → `plan -var-file=dev.tfvars` **"No changes"**. 차이가 있으면 apply 없이 보고(맥미니 로컬과 S3 가 어긋난 것)
- [X] T012 [US1] **대체 — dev import plan 이 replace 0·재plan No changes 로 스크립트·tfvars 정확성 입증.** 스크립트 신뢰 검증 — `iac/scripts/gen-import-blocks.sh dev --check` → 49개 id 전수 일치(불일치 있으면 스크립트 수정 후 재실행). 이 단계가 prod 의 안전장치
- [ ] T013 [US1] `terraform workspace new prod` → `iac/scripts/gen-import-blocks.sh prod` → `import.prod.tf`·`prod.tfvars.generated` 생성 → `prod.tfvars` 로 복사 후 값 검토(`home_prometheus_remote_write_url`·`blocked_path_patterns` 포함)
- [ ] T014 [US1] `terraform plan -var-file=prod.tfvars -out=prod-import.tfplan` → **판정: "49 to import, 3 to add, 0 to change, 0 to destroy"**. add 3 = `aws_cloudwatch_log_group.alloy`·`aws_ecs_task_definition.alloy`·`aws_ecs_service.alloy`. 아니면 apply 금지 → research R-4 분류(태그=무해 / 런치템플릿=주의 / replace=중단) 후 사용자 보고
- [ ] T015 [US1] (사용자 승인 후) `terraform apply prod-import.tfplan` → `rm iac/terraform/import.prod.tf` → `terraform plan -var-file=prod.tfvars` "No changes" → `terraform state list | wc -l` = 52. prod 리소스 생성 시각 불변 확인(`aws ecs describe-clusters`·`describe-load-balancers` 의 CreatedTime 이 import 전과 동일)
- [ ] T016 [US1] (사용자·맥미니) 재초기화 — quickstart §2-c: `git pull`(T007 머지 후 또는 브랜치 체크아웃) → `mv terraform.tfstate.d _local-state-archive && mv terraform.tfstate* _local-state-archive/` → `terraform init -reconfigure` → `workspace select dev` → `plan -var-file=dev.tfvars` "No changes"; `workspace select prod` → `plan` "No changes"
- [ ] T017 [US1] 잠금 확인 — 맥북에서 `terraform plan -lock-timeout=0s` 를 돌리는 동안 맥미니에서 같은 plan → 한쪽이 lock 오류. (동시 실행이 번거로우면 S3 에 `.tflock` 객체가 plan 중 생기는지로 대체)

**Checkpoint**: 장부 복구 완료 = prod Alloy 도 이미 떠 있음(US2 검증만 남음)

---

## Phase 4: User Story 2 — prod 앱·호스트 메트릭이 홈 Grafana 에 나온다 (Priority: P1)

**Goal**: T015 로 생성된 prod Alloy 가 홈서버로 보내고 있음을 확인.

**Independent Test**: `up{env="prod", job="prometheus.scrape.ecs_apps"}` = 2, 호스트 = prod 인스턴스 수.

### Implementation for User Story 2

- [ ] T018 [US2] AWS 확인 — `aws ecs describe-services --cluster kbap-prod-ecs-cluster --services kbap-prod-ecs-alloy --profile kbap-prod-deployer --region ap-northeast-2 --query 'services[0].{running:runningCount,pending:pendingCount}'` running = 인스턴스 수(api 2 + batch 풀 N); `aws logs tail /kbap/prod/alloy --since 5m | grep -iE "403|401|error" | grep -v udev` 비어 있음
- [ ] T019 [US2] (사용자·Grafana) `up{env="prod", job="prometheus.scrape.ecs_apps"}` → 2행(`instance=prod-api-…`, `version=<리비전>`); `count by (host) (node_memory_MemAvailable_bytes{env="prod"})` = 인스턴스 수; `jvm_memory_used_bytes{env="prod",application="kbap-api",area="heap"}` 15s 그래프; dev 와 섞이지 않음(`env` 전환). 결과 숫자 보고 → tasks 반영

**Checkpoint**: KB-381 DoD 마지막 항목(prod) 닫힘

---

## Phase 5: User Story 3 — 공개 도메인에서 관리 경로가 보이지 않는다 (Priority: P2)

**Goal**: 거부 규칙 dev 적용·실측 → (필요 시 WAF 결정) → 카나리 1회 → prod.

**Independent Test**: curl 4종 404(`%61` 은 결정 지점), 허용 3종 200, 타깃 healthy, 카나리 정상, prod 동일.

### Implementation for User Story 3

- [X] T020 [US3] **dev import apply 에 포함(1 add).** 맥북 `workspace select dev` → `terraform plan -var-file=dev.tfvars` **"1 to add"**(`aws_lb_listener_rule.block_paths`) → `apply`
- [ ] T021 [US3] 실측 — quickstart §4 의 curl 루프: `/actuator/prometheus`·`//actuator/prometheus`·`/api/../actuator/prometheus` → 404, `/%61ctuator/prometheus` → 결과 기록, `/api/app-version`·`/admin/login`·`/swagger-ui/index.html` → 200, ALB 타깃 healthy, Grafana `up{env="dev"}` 유지
- [ ] T022 [US3] **결정 지점**: `/%61ctuator` 가 404 면 통과. 200 이면 사용자에게 보고하고 WAF 승격 여부 결정(승격 시 별도 태스크: Web ACL + URL_DECODE·NORMALIZE_PATH regex 규칙, 월 ~$7/ALB). 결과를 research R-6 아래 한 줄로 기록
- [ ] T023 [US3] (사용자) 카나리 1회 — GitHub Actions `deploy-dev` Run workflow(현재 태그) → 전환 완료 후 `curl -s https://dev.kbap.site/api/app-version` 200, Grafana `version` 증가, 규칙이 전환을 방해하지 않음(전환 후 신버전 응답)
- [ ] T024 [US3] prod 적용 — `prod.tfvars` 의 `blocked_path_patterns = ["*actuator*","*swagger*","*api-docs*"]` 확인 → `workspace select prod` → `plan -var-file=prod.tfvars` **"1 to add"** → `apply` → 같은 curl(`https://prod-ecs.kbap.site` 또는 현재 prod 도메인; swagger·api-docs 도 404)

**Checkpoint**: 세 스토리 완료

---

## Phase 6: Polish & Cross-Cutting

- [ ] T025 `iac/terraform/README.md` 개정 — "처음 세우기": S3 백엔드·`kbap-infra`·workspace `dev`/`prod`·버킷 생성(1회)·tfvars 복원표(예시 대비 다른 항목 이름만, 값은 위키/로컬) / "알아둘 것": 로컬 state 금지·잠금 해제(`terraform force-unlock <id>`)·prod import 절차(`gen-import-blocks.sh` + plan 게이트)·거부 규칙(새 공개 경로는 규칙 무관, 차단 패턴 추가는 tfvars)·`%61` 판정 결과·WAF 조건. 종전 "state 는 맥미니에만" 문구 제거
- [ ] T026 [P] Jira 갱신(Atlassian MCP): KB-390 DoD 를 거부 규칙·실측 결과로 갱신, KB-381 DoD-6(prod) 완료 체크, KB-379 에픽 본문에 state 복구 완료 한 줄
- [ ] T027 [P] 위키 `terraform-state-workspaces-and-aws-profiles.md` 개정 — S3 백엔드 전환 완료·workspace 이름 변경(`dev-ecs`→`dev`)·import 절차와 plan 게이트·tfvars 복원 값(집 IP 등 실값은 여기)·`_local-state-archive` 보관 기간; `observability-…` 문서에 prod Alloy 확인·거부 규칙 실측 한 줄; INDEX 갱신; 허브 커밋·푸시
- [ ] T028 커밋·`open-draft-pr-to-develop` — 제목 `feat(infra): terraform S3 백엔드 전환·prod state 복구·ALB 관리경로 차단`, 본문에 plan 게이트 결과(dev No changes / prod 49 import + 3 add / 규칙 1 add)·curl 표·Grafana prod 확인·`%61` 판정

---

## Dependencies & Execution Order

- **Phase 1(코드)** 은 준비물과 무관 — 먼저 끝냄. **Phase 2(사용자)** 가 US1 을 블로킹.
- **US1**: T011(dev 이관) → T012(스크립트 검증, dev state 필요) → T013~T015(prod) → T016(맥미니) → T017.
- **US2**: T015 이후.
- **US3**: dev 부분(T020~T023)은 T011 이후 바로 가능(prod 와 독립). T024 는 T015 이후.
- **Polish**: 전부 이후. T026 ∥ T027.

### Parallel Opportunities

- T002 ∥ T003 · T008 ∥ T009 ∥ T010 · T020~T023(dev 차단) ∥ T013~T015(prod import) — 워크스페이스가 달라 충돌 없음 · T026 ∥ T027

### 판정 게이트 (여기서 멈춘다)

| 게이트 | 통과 조건 | 실패 시 |
|---|---|---|
| T011 | dev plan No changes | S3/로컬 불일치 보고 |
| T012 | `--check dev` 전수 일치 | 스크립트 수정 |
| T014 | 49 import / 3 add / 0 change / 0 destroy | R-4 분류 후 사용자 보고, apply 금지 |
| T021/T022 | `%61` 404 | WAF 승격 결정 |

---

## Implementation Strategy

### MVP First (US1)

1. T001~T007 코드 → 사용자 T008~T010 → T011(dev 이관) → T012 → T013~T015(prod import + Alloy)
2. **STOP and VALIDATE**: 양쪽 plan No changes. 이 시점에 prod Alloy 도 떠 있음

### Incremental Delivery

1. US1 → 맥북에서 dev·prod 관리 가능 + prod Alloy
2. US2 → Grafana prod 확인(조회만)
3. US3 → dev 규칙·실측·카나리 → prod 규칙
4. Polish → README·Jira·위키·PR

---

## Notes

- 테스트 코드 없음(사용자 지시). 증거는 plan 출력·curl 표·Grafana 숫자를 PR 본문에
- `import.prod.tf`·`*.tfvars.generated`·`*.tfvars`·state 파일은 절대 커밋하지 않는다(gitignore 확인)
- prod apply(T015) 는 plan 파일(`-out`)로만 — plan 과 apply 사이에 코드가 바뀌면 재plan
- 맥미니 세션 위임은 "기대 plan 수 불일치 = STOP" 조건을 반드시 포함(위키 `terraform-state-…` 참조)
