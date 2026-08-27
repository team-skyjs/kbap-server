# Research: 배치 잡 원격 트리거

## R1. 통로 — ECS Exec (SSM 채널) 채택

- **Decision**: 배치 ECS 서비스에 `enable_execute_command = true`, 운영자는 `aws ecs execute-command --container batch --command "curl ... localhost:8080/internal/batch/..."` 로 컨테이너 안에서 트리거를 호출한다.
- **Rationale**: 컨테이너가 SSM 엔드포인트로 **아웃바운드** 제어 채널을 열어 두고 명령이 그 채널로 들어오므로 SG·포트 개방이 0 이다(FR-003). 인증은 IAM(FR-004/005). 세션·명령 과금이 없어 추가 비용 0(SC-004). 인스턴스가 퍼블릭 서브넷이라 NAT 없이도 SSM 에 나간다(README 의 기존 전제).
- **Alternatives considered**: (a) SG 에서 8080 을 홈 IP 에 개방 — 트리거에 인증이 없어 IP 만 믿는 셈, 동적 IP 취약, FR-003 위배. (b) bastion SSH 터널 — 22 포트·키 관리·`admin_cidr` 갱신, 젠킨스에 SSH 키 보관. (c) 배치 앞 ALB + 인증 필터 — 배치가 공개 엔드포인트가 되고 ALB 비용·앱 코드 변경. (d) `ecs run-task` 일회성 태스크 — #171 이 확정한 "상시 기동 + HTTP 트리거 + 중복 방지 + 실행 이력" 구조를 되돌림. 전부 기각.

## R2. 권한 — 태스크 역할(채널) vs 호출자(명령)

- **Decision**: 두 주체를 분리한다.
  - **태스크 역할**(`kbap-<env>-ecs-batch-task-role`): `ssmmessages:CreateControlChannel/CreateDataChannel/OpenControlChannel/OpenDataChannel` on `*` — 컨테이너에 주입되는 SSM 에이전트가 채널을 여는 데 필요. 리소스 한정이 불가한 액션이라 `*`.
  - **호출자**(환경별 운영 IAM 사용자 `kbap-<env>-ecs-batch-operator`): `ecs:ListTasks`·`ecs:DescribeTasks`(조건 `ecs:cluster` = 이 클러스터) + `ecs:ExecuteCommand`(리소스 `task/kbap-<env>-ecs-cluster/*`, 조건 `ecs:container-name = batch`). `ssm:StartSession` 은 ExecuteCommand API 가 내부에서 처리하므로 호출자에 불필요.
- **Rationale**: 채널 권한은 컨테이너 쪽, 명령 권한은 사람 쪽 — 하나가 유출돼도 다른 쪽이 없으면 실행이 안 된다. 조건 키로 클러스터·컨테이너를 못 박아 FR-004/005 를 IAM 수준에서 강제한다.
- **Alternatives considered**: 태스크 역할에 `AmazonSSMManagedInstanceCore` 부착 — 필요 이상(인벤토리·패치 권한 포함). 단일 운영 사용자에 dev·prod 둘 다 허용 — 환경 착각 사고를 못 막음(US2 위배). 기각.

## R3. 운영 사용자·정책의 소유 — Terraform 모듈이 생성, 액세스 키만 사람이 발급

- **Decision**: `aws_iam_user` + `aws_iam_user_policy` 를 `ecs-environment` 모듈에 둔다(환경당 1개, 이름 `kbap-<env>-ecs-batch-operator`). `aws_iam_access_key` 는 만들지 않는다 — 콘솔에서 발급해 젠킨스 크리덴셜에만 저장.
- **Rationale**: 정책 JSON 을 문서로만 두면 dev/prod 가 손으로 달라지기 쉽다. 모듈이 만들면 클러스터 ARN·컨테이너명이 코드에서 자동 결합돼 교차 권한이 구조적으로 불가능하다. 키를 Terraform 이 만들면 state 파일에 시크릿이 남는다(README 의 "시크릿은 Terraform 밖" 원칙과 충돌).
- **Alternatives considered**: 정책 JSON 을 README 에만 기재하고 사용자가 콘솔에서 사용자·정책 생성 — 드리프트 위험, 기각. `aws_iam_access_key` 로 키까지 생성 — state 에 시크릿, 기각.

## R4. 호출 측 전제 — Session Manager plugin, curl

- **Decision**: 호출 호스트(젠킨스·운영자 PC)에 **AWS CLI v2 + Session Manager plugin** 설치를 전제하고 README·스크립트가 부재 시 즉시 실패하도록 한다. 컨테이너 안 HTTP 호출은 `curl` — 배치 런타임 이미지 `eclipse-temurin:21-jre`(Ubuntu 계열, curl 포함)를 quickstart 1단계에서 `curl --version` 으로 확인하고, 없을 때만 `Dockerfile.batch` 런타임 스테이지에 `apt-get install -y curl` 을 추가한다.
- **Rationale**: `execute-command` 는 CLI 가 SSM 플러그인으로 세션을 연결하므로 플러그인이 없으면 "SessionManagerPlugin is not found" 로 실패한다 — 가장 흔한 첫 장애물이라 문서 최상단에 둔다. curl 은 이미지 확인이 먼저(추측으로 Dockerfile 을 바꾸지 않는다).
- **Alternatives considered**: 컨테이너 내부에 별도 트리거 CLI 동봉 — 불필요(curl 로 충분). 기각.

## R5. 롤아웃 순서 — 플래그는 새 태스크부터 적용

- **Decision**: `terraform apply`(서비스 플래그 + 역할 정책) → **배치 서비스 강제 재배포**(`aws ecs update-service --force-new-deployment` 또는 `deploy-batch.sh` 동일 태그 재배포) → `describe-tasks` 의 `managedAgents[?name=='ExecuteCommandAgent'].lastStatus == RUNNING` 확인 → 잡 트리거 검증. dev 완료 후 prod 는 같은 절차(배포 창에 맞춰 별도 판단).
- **Rationale**: Exec 에이전트는 태스크 기동 시 주입되므로 기존 실행 중인 태스크엔 적용되지 않는다. 서비스 플래그는 `ignore_changes` 대상이 아니라 Terraform 이 정상 관리하고, 태스크 정의 리비전 소유권(배포)은 그대로다. 배치는 단일 태스크라 재배포 시 잠깐 다운(기존 롤링 특성과 동일).
- **Alternatives considered**: 태스크 정의에 `initProcessEnabled=true` 추가 — 좀비 프로세스 방지 권장 옵션이지만 컨테이너 정의는 배포 소유(`ignore_changes`)라 이번엔 건드리지 않는다(후속 검토 메모).

## R6. 원격 경로의 응답 계약 — 배치 트리거 응답을 그대로 통과

- **Decision**: 스크립트는 `curl -s -w '\n%{http_code}'` 로 본문+상태코드를 받아 그대로 출력하고, 상태코드 202/200 이 아니면 비0 종료한다. 잡 이름 오류(404 + 실행 가능 잡 목록), 중복 실행(409 + 실행 ID), 실행 조회(200/404)는 배치 앱 응답(`BatchJobRunResponse`)을 가공하지 않는다(FR-007).
- **Rationale**: 젠킨스가 종료 코드로 성공/실패를 판정하고 본문은 로그로 남기면 충분하다. 배치 앱의 계약을 스크립트가 재해석하면 두 계약이 어긋날 때 디버깅이 어려워진다.
- **Alternatives considered**: 스크립트가 완료까지 폴링해 최종 상태를 반환 — 잡 실행 시간만큼 세션을 붙잡는 안티패턴(#171 이 202+폴링을 택한 이유와 동일). `status` 서브커맨드로 분리한다.
