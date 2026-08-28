# Tasks: 호스트마다 Alloy DAEMON — 앱·호스트 메트릭을 홈 Prometheus 로 remote_write (KB-381)

**Input**: Design documents from `/specs/kb-381-alloy-daemon-remote-write/`

**Prerequisites**: plan.md, spec.md, research.md(R-1~R-9), data-model.md, contracts/alloy-config.md, quickstart.md

**Tests**: 작성하지 않는다(사용자 결정 — 인프라·설정 작업, plan Constitution Check 원칙 I 면제). 검증은 quickstart 의 dev 시나리오.

**Organization**: 스토리별. 코드는 terraform 파일 2개(`alloy.tf`·`alloy.config.alloy.tftpl`)에 집중되므로 US1 이 골격을 만들고 US3·US4 는 같은 템플릿에 블록을 더한다(같은 파일 → 순차). US2·US5 는 dev 에서의 검증 시나리오가 본체.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완 태스크 의존 없음)
- **[Story]**: US1 조회 / US2 자동 추종 / US3 카나리 라벨 / US4 호스트 자원 / US5 인증·앱 무지
- terraform·Alloy 설정 주석은 허용(Kotlin 없음)

## Path Conventions

- 모듈: `iac/terraform/modules/ecs-environment/` (`alloy.tf`·`alloy.config.alloy.tftpl`·`variables.tf`·`secrets.tf`·`outputs.tf`)
- 루트: `iac/terraform/{variables.tf,main.tf,dev.tfvars.example,prod.tfvars.example,README.md}`
- 참조 계약: `specs/kb-381-alloy-daemon-remote-write/contracts/alloy-config.md`

---

## Phase 1: Setup (변수·입력 통로)

- [X] T001 `iac/terraform/modules/ecs-environment/variables.tf` 에 변수 3개 추가 — `home_prometheus_remote_write_url`(string, description "홈서버 Prometheus remote_write 수신 URL — Cloudflare Tunnel 공개 호스트"), `alloy_image`(string, 기본값 구현 시점의 `grafana/alloy:v1.x.y` 고정 태그 — `latest` 금지), `alloy_secret_names`(list(string), 기본 `["CF_ACCESS_CLIENT_ID","CF_ACCESS_CLIENT_SECRET"]`, description 에 SSM 등록 명령 힌트)
- [X] T002 [P] `iac/terraform/variables.tf` + `iac/terraform/main.tf` 에 루트 변수 `home_prometheus_remote_write_url`·`alloy_image` 를 선언하고 모듈로 전달(기존 변수 나열 순서·주석 톤 유지)
- [X] T003 [P] `iac/terraform/dev.tfvars.example`·`prod.tfvars.example` 에 두 변수 예시 줄 추가(`home_prometheus_remote_write_url = "https://prom-write.<domain>/api/v1/write"`)
- [X] T004 `iac/terraform/modules/ecs-environment/secrets.tf` 의 `local.secret_names` 를 `concat(var.api_secret_names, var.batch_secret_names, var.alloy_secret_names)` 로 확장(ARN 맵 자동 생성 — 실행 롤 정책은 `${ssm_prefix}/*` 라 IAM 변경 없음, 주석 한 줄로 명시)

---

## Phase 2: Foundational (사용자 준비물 — 코드 밖, 블로킹)

**⚠️ 이 단계는 사용자가 수행한다.** quickstart §0. 끝나기 전엔 dev apply 를 해도 Alloy 가 403 을 받는다.

- [X] T005 홈서버: Prometheus 컨테이너 args 에 `--web.enable-remote-write-receiver` 추가·재기동, `/prometheus` 볼륨 영속 확인 (quickstart §0-1)
- [X] T006 [P] Cloudflare: Tunnel 공개 호스트 `prom-write.<도메인>` → `http://prometheus:9090` ingress, Access 셀프호스트 앱 + Service Auth 정책, 서비스 토큰 dev·prod 각 1쌍 발급 (quickstart §0-2·3)
- [X] T007 SSM 등록: `/kbap/dev/CF_ACCESS_CLIENT_ID`·`/kbap/dev/CF_ACCESS_CLIENT_SECRET` (+ prod) SecureString (quickstart §0-4) — `kbap-prod-deployer` 프로필
- [X] T008 수신 확인: 토큰 없이 `POST /api/v1/write` → 403, 토큰 헤더로 → 400/415(Prometheus 도달) (quickstart §0-5). 결과를 기록해 두면 US5 검증에 재사용

**Checkpoint**: T005~T008 완료 → US1 apply 가능

---

## Phase 3: User Story 1 — 홈서버 Grafana 에서 dev·prod 앱 내부 상태를 본다 (Priority: P1) 🎯 MVP

**Goal**: 각 호스트의 Alloy 가 api·batch 컨테이너를 찾아 `/actuator/prometheus` 를 15초마다 읽고 홈 Prometheus 로 보낸다. `env`·`host` 라벨 부착.

**Independent Test**: `up{env="dev"}` = 실행 중 앱 태스크 수, JVM 힙 그래프가 15s 간격으로 이어짐, batch 잡 1회 후 `spring_batch_job_seconds_count` 증가.

### Implementation for User Story 1

- [X] T009 [US1] `iac/terraform/modules/ecs-environment/alloy.config.alloy.tftpl` 작성 — contracts §4 중 **US1 범위**: `discovery.docker "ecs"`(docker.sock, `refresh_interval="15s"`) → `discovery.relabel "apps"`(keep: 컨테이너 이름 `api|batch`, 포트 `8080`; `__metrics_path__=/actuator/prometheus`) → `prometheus.scrape "ecs_apps"`(15s/10s) → `prometheus.remote_write "home"`(url `${remote_write_url}`, CF Access 헤더 2개는 `sys.env`, `external_labels { env = "${env}", host = constants.hostname }`). templatefile 이스케이프(`$${…}`) 주의
- [X] T010 [US1] `iac/terraform/modules/ecs-environment/alloy.tf` 작성 — contracts §2·§3: `aws_cloudwatch_log_group.alloy`(`/kbap/<env>/alloy`), `aws_ecs_task_definition.alloy`(family `${local.name_prefix}-alloy`, `network_mode="host"`, cpu 128, memory 384, execution role `task_execution`, volume `docker_sock`→`/var/run/docker.sock`, 컨테이너 `alloy`: `var.alloy_image`, `memoryReservation 128`, `command` = `sh -c 'printf "%s" "$ALLOY_CONFIG" > /etc/alloy/config.alloy && exec alloy run --server.http.listen-addr=127.0.0.1:12345 --storage.path=/var/lib/alloy/data /etc/alloy/config.alloy'`, `environment` `ALLOY_CONFIG = templatefile("${path.module}/alloy.config.alloy.tftpl", { env = var.env, remote_write_url = var.home_prometheus_remote_write_url })`, `secrets` = `var.alloy_secret_names` → `local.secret_arns`, awslogs), `aws_ecs_service.alloy`(`scheduling_strategy="DAEMON"`, `launch_type="EC2"`, min healthy 0 / max 100, `depends_on` ASG 두 풀). **`ignore_changes` 없음**(terraform 이 완전 소유 — 주석)
- [X] T011 [P] [US1] `iac/terraform/modules/ecs-environment/outputs.tf` 에 `alloy_service_name` 추가
- [X] T012 [US1] 로컬 문법 점검 — quickstart §1: 템플릿을 sed 로 치환해 `docker run --rm -v …:/c.alloy grafana/alloy:<tag> fmt /c.alloy` 통과. 로컬에 terraform 이 있으면 `terraform -chdir=iac/terraform fmt -recursive` 도(없으면 정렬은 손으로 `api.tf` 스타일에 맞춤)
- [X] T013 [US1] 커밋: `feat(infra): ECS Alloy DAEMON — actuator 수집 → 홈 Prometheus remote_write (KB-381)` — T001~T011
- [ ] T014 [US1] dev apply — **사용자 수행**(state 있는 머신): `terraform plan -var-file=dev.tfvars` 가 신규 3 리소스(log group·task definition·service)뿐인지 확인 후 apply. `aws ecs list-tasks --cluster kbap-dev-ecs-cluster --service-name kbap-dev-ecs-alloy` 가 인스턴스 수와 같음, `aws logs tail /kbap/dev/alloy` 에 remote_write 오류 없음
- [ ] T015 [US1] dev 검증 — 홈 Grafana Explore: `up{env="dev"}` = 태스크 수(api 2 + batch 1), `jvm_memory_used_bytes{env="dev",application="kbap-api",area="heap"}` 15s 그래프, batch 잡 1회 트리거(ECS Exec) 후 `spring_batch_job_seconds_count{env="dev"}` 증가. 결과 기록(PR 본문용)

**Checkpoint**: 앱 메트릭이 홈서버에 쌓임(MVP). KB-383 대시보드 착수 가능

---

## Phase 4: User Story 5 — 수신 경로는 인증되고, 앱은 홈서버를 모른다 (Priority: P1)

**Goal**: 토큰 없는 요청은 엣지에서 거부, 토큰은 SSM→Alloy 에만, 앱·SG·ALB diff 0.

**Independent Test**: T008 의 403 + 토큰 폐기 시 수집 정지·앱 무영향 + `git diff`/`terraform plan` 에 api·batch 태스크정의·sg·alb 변경 0.

### Implementation for User Story 5

- [ ] T016 [US5] 무변경 확인 — `terraform plan` 출력에서 `aws_security_group*`·`aws_lb*`·`aws_ecs_task_definition.api|batch` 가 변경 목록에 없음을 기록. `git diff develop --stat` 에 `api.tf`·`batch.tf`·`sg.tf`·`alb.tf`·앱 소스 없음
- [ ] T017 [US5] 토큰 폐기 시나리오(dev) — SSM `CF_ACCESS_CLIENT_SECRET` 을 틀린 값으로 교체 → `aws ecs update-service --cluster kbap-dev-ecs-cluster --service kbap-dev-ecs-alloy --force-new-deployment` → Grafana 갱신 정지 + Alloy 로그 `403`/non-recoverable + api `https://dev-ecs.kbap.site` 정상 → 값 복원·재배포 → 수집 재개. **403 은 유실(백로그 아님)** 임을 확인해 README 주의 문구 근거로 기록
- [ ] T018 [US5] 앱 컨테이너에 홈서버 정보가 없음 확인 — `aws ecs describe-task-definition --task-definition kbap-dev-ecs-api --query 'taskDefinition.containerDefinitions[0].{env:environment,secrets:secrets}'` 에 `CF_ACCESS_*`·remote_write URL 부재

**Checkpoint**: US1+US5 = 안전한 MVP

---

## Phase 5: User Story 2 — 배포·스케일아웃을 수집이 스스로 따라간다 (Priority: P1)

**Goal**: 카나리 교체·호스트 교체·홈 단절을 사람 개입 없이 흡수. 코드는 US1 의 DAEMON + 15s discovery 로 이미 충족 — 이 단계는 실증.

**Independent Test**: 카나리 중 구·신 컨테이너 둘 다 `up=1`; instance refresh 후 5분 내 새 `host` 등장; 30분 단절 후 백필.

### Implementation for User Story 2

- [ ] T019 [US2] 카나리 추종(dev) — api 를 CI 로 1회 배포(이미지 태그만). 카나리 창 안에서 `count(up{env="dev",application="kbap-api"})` 가 4(구2+신2) → 완료 후 2. 구 컨테이너 타깃이 5분 내 stale 로 사라짐
- [ ] T020 [US2] 호스트 교체(dev) — ASG `kbap-dev-ecs-api-asg` instance refresh(또는 인스턴스 1대 종료) → 새 인스턴스 합류 후 5분 내 `count by (host) (up{env="dev"})` 에 새 host, Alloy 태스크 수 = 인스턴스 수 유지. 사람 개입 0 확인
- [ ] T021 [US2] 홈 단절(dev) — 홈 Prometheus 컨테이너를 30분 정지 → 재기동 → Grafana 에서 정지 구간이 채워짐(Alloy WAL 재전송, 로그로 확인). 3시간 이상은 유실이 정상임을 README 문구로
- [X] T022 [US2] `iac/terraform/README.md` 에 위 세 시나리오의 기대 동작을 "알아둘 것" 으로 2~3줄 (카나리 중 타깃 2배·refresh 5분·WAL ~2h)

**Checkpoint**: P1 스토리 3개 완료

---

## Phase 6: User Story 3 — 카나리 배포 중 구버전과 신버전을 나눠 비교한다 (Priority: P2)

**Goal**: `instance`(태스크 단위)·`version`(태스크 정의 리비전) 라벨 relabel.

**Independent Test**: 배포 1회 후 `sum by (version)(rate(http_server_requests_seconds_count{env="dev"}[5m]))` 가 두 값, 같은 host 의 두 컨테이너가 다른 `instance`.

### Implementation for User Story 3

- [X] T023 [US3] `iac/terraform/modules/ecs-environment/alloy.config.alloy.tftpl` 의 `discovery.relabel "apps"` 에 규칙 2개 추가(contracts §4): `instance` = `${env}-$${1}-$${2}`(source container-name + task-arn, regex `(.+);.*/([0-9a-f]{6})$`), `version` = `com_amazonaws_ecs_task_definition_version` 라벨
- [ ] T024 [US3] 커밋 + dev apply(사용자): `feat(infra): Alloy relabel — instance(태스크)·version(리비전) 라벨 (KB-381)`. apply 후 `up{env="dev"}` 의 `instance` 가 `dev-api-xxxxxx` 형식, `version` 이 현재 리비전 번호
- [ ] T025 [US3] 카나리 비교 검증(dev) — T019 와 같은 배포 1회 중 `sum by (version) (rate(http_server_requests_seconds_count{env="dev",application="kbap-api"}[5m]))` 두 줄, `count by (instance, host) (up{env="dev",application="kbap-api"})` 에서 같은 host 에 instance 2개. 완료 후 구 version 시계열이 더 이상 갱신되지 않음

**Checkpoint**: 버전 비교 가능 — KB-383 의 version 변수 패널 전제 충족

---

## Phase 7: User Story 4 — 호스트 자원(메모리·디스크)도 같은 경로로 본다 (Priority: P2)

**Goal**: `prometheus.exporter.unix` 로 호스트 CPU·메모리·디스크·load 를 같은 remote_write 로.

**Independent Test**: `count by (host) (node_memory_MemAvailable_bytes{env="dev"})` = 인스턴스 수, 루트 파일시스템 사용률이 15s 로 그려짐.

### Implementation for User Story 4

- [X] T026 [US4] `iac/terraform/modules/ecs-environment/alloy.tf` 태스크 정의에 호스트 볼륨 3개(`proc`→`/proc`, `sys`→`/sys`, `root`→`/`)와 컨테이너 `mountPoints`(`/host/proc`·`/host/sys`·`/host/root`, `readOnly=true`) 추가
- [X] T027 [US4] `alloy.config.alloy.tftpl` 에 `prometheus.exporter.unix "host"`(procfs/sysfs/rootfs 경로, `filesystem { mount_points_exclude = "^/(dev|proc|sys|run|var/lib/docker/.+)($|/)" }`) + `prometheus.scrape "host"`(15s → remote_write.home) 추가(contracts §4)
- [ ] T028 [US4] 커밋 + dev apply(사용자): `feat(infra): Alloy unix exporter — 호스트 CPU·메모리·디스크 (KB-381)`. 검증: `count by (host) (node_memory_MemAvailable_bytes{env="dev"})` = 인스턴스 수, `1 - node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"}` 가 0~1 사이 값. `node_filesystem_*` 에 `/var/lib/docker/...` 오버레이가 없음

**Checkpoint**: 전 스토리 완료(dev)

---

## Phase 8: Polish & Cross-Cutting

- [X] T029 `iac/terraform/README.md` 에 "## 관측 — Alloy DAEMON" 절: 구성 요약(DAEMON·host net·docker.sock·unix exporter), 필요 입력(tfvars 2개·SSM 2개·홈서버/Cloudflare 전제), 라벨 규약(env/application/instance/version/host), 카나리 메모리 여유(api 1536×2 + Alloy 128), **403=유실 주의**, 설정 변경 절차(템플릿 수정 → apply → DAEMON 롤링), 되돌리기
- [ ] T030 prod 적용 — **사용자 수행**: `prod.tfvars` 변수 추가 → `terraform apply -var-file=prod.tfvars` → `up{env="prod"}` 확인(batch 풀은 호스트 메트릭만). 홈 Prometheus 를 잠시 내렸다 올려 재전송 확인
- [ ] T031 [P] Jira KB-381 DoD 갱신(Atlassian MCP `editJiraIssue`): "설정은 SSM Parameter 주입" → "terraform templatefile → env 주입(SSM 은 토큰만)", 검증 결과·리비전 번호 기록. KB-383 에 "version 변수 전제 충족" 코멘트는 불필요(DoD 에 이미 반영)
- [ ] T032 [P] 위키 `../kbap-agenthub/wiki/observability-app-metrics-and-ecs-healthcheck.md` 갱신 — Alloy 구성 확정(host net·templatefile 전달·403 유실·카나리 타깃 2배), INDEX 한 줄 갱신, 허브 커밋
- [ ] T033 `open-draft-pr-to-develop` 스킬로 draft PR — 제목 `feat(infra): ECS Alloy DAEMON — 앱·호스트 메트릭을 홈 Prometheus 로 remote_write`, 본문에 설계 요점·라벨 규약·dev 검증 결과(T015·T017·T019~T021·T025·T028)

---

## Dependencies & Execution Order

- **Phase 1 → Phase 2(사용자) → US1(T009~T015)**: T014 apply 는 T005~T008 이 끝나야 의미 있음(그 전엔 403)
- **US5(T016~T018)**: T014 이후. T017 은 SSM 값을 건드리므로 US2·US3 검증과 겹치지 않게 단독 실행
- **US2(T019~T022)**: T014 이후. T019 의 배포 1회는 US3 의 T025 와 **같은 배포**로 묶어 한 번만 (T023·T024 를 먼저 적용해 두면 됨)
- **US3(T023~T025)·US4(T026~T028)**: 같은 템플릿/`alloy.tf` 파일 → 순차. 둘 다 US1 이후. 각각 apply 1회(또는 T024·T028 을 한 apply 로 합쳐도 됨 — 권장: **T009 에서 템플릿을 contracts §4 전문으로 한 번에 쓰고** T023·T027 은 확인만)
- **Polish**: 전 스토리 후. T031 ∥ T032 ∥ T029

### Parallel Opportunities

- T002 ∥ T003 · T005 ∥ T006 · T011 ∥ T010 · T031 ∥ T032

### 권장 실행 경로 (apply 횟수 최소화)

1. T001~T004 → **T009 를 contracts §4 전문으로 작성**(US3 relabel·US4 unix exporter 포함) + T010(볼륨 3개 포함) + T011 → T012 → T013 커밋
2. 사용자: T005~T008 → T014 apply 1회
3. 검증 일괄: T015 → T016·T018 → T019+T025(배포 1회) → T020 → T021 → T017(마지막, SSM 건드림)
4. T022·T029 문서 → T030 prod → T031·T032 → T033 PR

---

## Notes

- 테스트 코드 없음(사용자 지시). 증거는 Grafana 조회 결과·Alloy 로그·`terraform plan` 출력을 PR 본문에
- terraform state 는 이 머신에 없다(KB-380 에서 확인) — apply 는 state 보유 머신에서 사용자가. 로컬은 `alloy fmt` 문법 점검까지
- 이미지 태그는 구현 시점에 Docker Hub 에서 최신 v1.x 를 확인해 고정. `latest` 금지
- Alloy 설정 변경은 `alloy.config.alloy.tftpl` 수정 → apply → DAEMON 이 인스턴스마다 롤링(min healthy 0 이라 잠깐 공백)
