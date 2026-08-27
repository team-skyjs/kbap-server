# kbap ECS 환경 (Terraform)

dev·prod 를 **같은 모듈(`modules/ecs-environment`) + 환경별 tfvars** 로 세운다. 기존 VPC·RDS·Redis·S3·SQS 는 재사용하고, 그 위에 ECS 클러스터·ALB·CodeDeploy 카나리·CloudWatch 를 새로 만든다. **현재 운영 중인 `kbap-prod-cluster`(ECS)·`kbap-devstg-alb` 는 건드리지 않는다** — 이 구성은 `dev-ecs.kbap.site` / `prod-ecs.kbap.site` 로 별도 ALB 를 받는다.

## 구성 요약

| 항목 | 값 |
|---|---|
| 컨테이너 인스턴스 | t3.medium × 3 — api 2대(AZ 분산) + batch 1대. ECS 인스턴스 속성 `workload=api|batch` 로 배치 분리 |
| api 태스크 | bridge 모드·동적 호스트 포트, 512 cpu / 1536 MiB, desired 2, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70` |
| batch 태스크 | 단일 태스크, 잡 트리거 HTTP 는 배치 인스턴스 8080 고정(클러스터 내부만) |
| 배포 | **CodeDeploy 블루/그린 카나리** — 20% 트래픽 15분 → 100% → 구버전 15분 유지 후 종료. 5xx 알람 시 자동 롤백 |
| 진입 | ALB(80→443 리다이렉트, `*.kbap.site` ACM) + Route53 alias `<subdomain>.kbap.site` |
| 로그 | CloudWatch `/kbap/<env>/api`·`/kbap/<env>/batch`, **보관 7일**, Container Insights, 대시보드 `kbap-<env>-ecs` |
| 시크릿 | SSM SecureString `/kbap/<env>/<NAME>` → 태스크 정의 `secrets` 로 주입 (값은 Terraform 밖) |
| AWS 권한 | 태스크 롤(api: S3 접두사 한정, batch: SQS+S3+ECS Exec 채널) — 액세스 키를 env 에 넣지 않는다. 배치 원격 실행은 환경별 운영 사용자 `kbap-<env>-ecs-batch-operator`(아래 절) |

## 소유권 경계

| 대상 | 소유자 |
|---|---|
| 클러스터·ASG·ALB·타깃그룹·CodeDeploy·IAM·로그그룹·대시보드 | Terraform |
| 배치 운영 IAM 사용자·정책(`kbap-<env>-ecs-batch-operator`) | Terraform |
| 운영 사용자 **액세스 키** | 사람(콘솔 발급) → 젠킨스 크리덴셜 — Terraform·레포에 두지 않는다 |
| 태스크 정의 **리비전**(이미지 태그)·리스너의 blue/green 포워딩·서비스 desired | 배포 스크립트 / CodeDeploy (`lifecycle.ignore_changes`) |
| SSM 파라미터(이름·값 모두) | 사람/CI (`aws ssm put-parameter`) — Terraform 은 ARN 문자열만 참조 |
| RDS·Redis·VPC·S3·SQS·Route53 존·ACM | 기존 인프라 (data 로 조회, SG 인바운드 규칙만 추가) |

## 처음 세우기 (dev)

```bash
cd iac/terraform
cp dev.tfvars.example dev.tfvars      # api_image / batch_image 태그를 ECR 실태그로
terraform init
terraform plan  -var-file=dev.tfvars
terraform apply -var-file=dev.tfvars
```

**apply 전에** 시크릿을 SSM 에 등록해 둔다(없으면 태스크가 기동 못 함). 이미 등록돼 있으면 건너뛴다:

```bash
for k in DB_PASSWORD JWT_SECRET OPENAI_API_KEY GOOGLE_PLACES_API_KEY; do
  aws ssm put-parameter --profile kbap-infra --name /kbap/dev/$k --type SecureString --overwrite --value '...'
done
aws ssm put-parameter --profile kbap-infra --name /kbap/dev/FIREBASE_CREDENTIALS_JSON \
  --type SecureString --overwrite --value "$(cat firebase-service-account.json)"

```

확인: `https://dev-ecs.kbap.site/actuator/health` → CloudWatch 대시보드 `kbap-dev-ecs`.

prod 는 `prod.tfvars.example` 로 동일하게 — state 는 dev 와 분리한다(로컬이면 디렉터리 분리 또는 `-state=prod.tfstate`, S3 백엔드면 `key` 분리).

## 배포

**api — 카나리 (`iac/scripts/deploy-api.sh <env> <tag>`)**

1. 현재 태스크 정의에서 이미지만 바꿔 새 리비전 등록
2. CodeDeploy 배포 생성 → green 타깃그룹에 신버전 태스크 기동(인스턴스당 blue 1 + green 1)
3. **20% 트래픽을 green 으로 15분** — 대시보드 "정상 타깃 수 blue/green"·5xx 로 관찰. 5xx 알람 발화 시 자동 롤백
4. 100% 전환. 이후 **15분간 blue 태스크 유지** — 이 창에서 `aws deploy stop-deployment --auto-rollback-enabled` 하면 트래픽만 되돌아가 즉시 복구
5. 15분 뒤 blue 종료 → 완료

**batch — 롤링 (`iac/scripts/deploy-batch.sh <env> <tag>`)**: 단일 인스턴스·고정 포트라 구 태스크를 먼저 내리고 신 태스크를 올린다(잠깐 다운). 서킷브레이커로 기동 실패 시 자동 롤백.

**batch 컨테이너 헬스체크(KB-380) 처음 적용 순서** — 태스크 정의는 `ignore_changes = [container_definitions]` 라 일반 apply 로는 리비전이 안 생기고, 헬스체크가 `/actuator/health/readiness` 를 치므로 actuator 가 있는 이미지가 먼저 떠 있어야 한다:

1. actuator 포함 batch 이미지 배포 (`deploy-batch.sh`)
2. `terraform apply -var-file=<env>.tfvars -replace=module.ecs_environment.aws_ecs_task_definition.batch` — 헬스체크가 담긴 새 리비전 등록(서비스는 아직 구 리비전)
3. batch 재배포 1회 — `deploy-batch.sh` 가 최신 리비전을 복제하므로 헬스체크가 승계된다. `aws ecs describe-tasks … --query 'tasks[].containers[].healthStatus'` 가 `HEALTHY` 면 끝

## 배치 잡 원격 실행 (ECS Exec)

배치 잡 트리거(`POST /internal/batch/jobs`)는 클러스터 내부에서만 열려 있고 인증이 없다. 클러스터 밖(홈서버 젠킨스·운영자 PC)에서는 **ECS Exec** 로 배치 컨테이너 안에서 `curl localhost:8080` 을 실행한다 — 컨테이너가 SSM 채널을 아웃바운드로 열어 두므로 **인바운드 포트 개방 0, 추가 비용 0**, 접근 통제는 IAM 이 담당한다.

**전제 (호출 호스트)**

- AWS CLI v2 + Session Manager plugin: `brew install --cask session-manager-plugin`
- 환경별 운영 자격증명 프로필 `kbap-<env>-batch-operator` — IAM 사용자는 Terraform 이 만들고(`terraform output batch_operator_user_name`), **액세스 키는 콘솔에서 발급**해 `aws configure --profile kbap-dev-batch-operator` 로 등록한다. dev 키로는 prod 클러스터·api 컨테이너에 접근할 수 없다(정책이 클러스터·`batch` 컨테이너로 한정).
- 배치 서비스에 Exec 가 적용된 태스크가 떠 있어야 한다 — **Terraform apply 후 배치를 한 번 재배포**해야 새 태스크에 에이전트가 주입된다:
  ```bash
  aws ecs update-service --cluster kbap-dev-ecs-cluster --service kbap-dev-ecs-batch --force-new-deployment --profile kbap-infra
  aws ecs describe-tasks --cluster kbap-dev-ecs-cluster --tasks <task-arn> --profile kbap-dev-batch-operator \
    --query 'tasks[0].containers[0].managedAgents[?name==`ExecuteCommandAgent`].lastStatus'   # RUNNING
  ```

**실행·조회** — 실행 중 배치 태스크를 찾아 컨테이너 안에서 트리거 HTTP 를 호출한다(래퍼 스크립트는 레포에 두지 않는다 — 호출 호스트의 젠킨스/셸에서 아래를 그대로 쓴다):

```bash
export AWS_PROFILE=kbap-dev-batch-operator AWS_REGION=ap-northeast-2
TASK=$(aws ecs list-tasks --cluster kbap-dev-ecs-cluster --service-name kbap-dev-ecs-batch \
  --desired-status RUNNING --query 'taskArns[0]' --output text)

# 실행 — 202 → {"jobName","executionId","status":"STARTED",...} / 404 잡 없음 / 409 이미 실행 중
aws ecs execute-command --cluster kbap-dev-ecs-cluster --task "$TASK" --container batch --interactive \
  --command "curl -s -w '\n%{http_code}' -X POST 'http://localhost:8080/internal/batch/jobs?jobName=<jobName>'"

# 조회 — 200 → status COMPLETED / FAILED / STARTED
aws ecs execute-command --cluster kbap-dev-ecs-cluster --task "$TASK" --container batch --interactive \
  --command "curl -s -w '\n%{http_code}' 'http://localhost:8080/internal/batch/executions/<executionId>'"
```

`execute-command` 출력은 세션 시작/종료 안내가 앞뒤로 붙는다 — 마지막 3자리 숫자 줄이 HTTP 코드, 그 앞의 `{` 로 시작하는 줄이 본문이다. 교차 환경 클러스터·`api` 컨테이너를 지정하면 `AccessDeniedException`, 태스크가 없으면(미기동·Exec 미적용) `list-tasks` 가 `None` 을 돌려준다.

실행 호출은 잡 완료를 기다리지 않는다(잡 실행 시간만큼 세션을 붙잡지 않기 위해). 젠킨스는 실행 응답의 `executionId` 를 파싱해 조회를 30초 간격으로 폴링하고, `FAILED` 면 빌드를 실패시킨다.

정기 실행은 배치 앱의 인앱 스케줄러가 하며, 이 경로는 수동·임시 실행용이다. 배치 트리거 포트 SG 규칙은 그대로다 — 인스턴스 IP:8080 은 인터넷에서 계속 닿지 않는다.

적용 상태: dev 부터 적용·검증(절차 `specs/kb-374-batch-ecs-exec/quickstart.md`) 후 prod 는 잡 미실행 시간대에 같은 절차로 — 배치 재배포가 잠깐 다운을 동반한다.

## 카나리 파라미터 바꾸기

`canary_percentage`·`canary_interval_minutes`·`blue_termination_wait_minutes` (tfvars). 바꾸면 배포 구성(`aws_codedeploy_deployment_config`)이 새로 만들어진다.

## 알아둘 것

- **NAT 게이트웨이가 없다** — 인스턴스는 퍼블릭 서브넷 + 퍼블릭 IP 로 ECR·SSM 에 나간다. 인바운드는 ALB 보안그룹에서만 열려 있다.
- 인스턴스 사이징: 평상시 **api 인스턴스당 컨테이너 1개**(desired 2 / 인스턴스 2대 spread). 카나리 진행 중(최대 30분)에만 구버전 1 + 신버전 1 로 **인스턴스당 2개**가 잠깐 공존한다. t3.medium(4 GiB)에 `ECS_RESERVED_MEMORY=256` → 태스크 가용 ≈ 3.6 GiB 라 1536 × 2 = 3072 MiB 까지는 들어가지만, **태스크 메모리를 더 올리면 카나리 중 신버전이 배치되지 못해 배포가 멈춘다**.
- 시크릿 4 KB 초과(Firebase JSON 이 큰 경우)는 SSM Advanced tier 로 파라미터를 바꾼다.
- **batch 헬스체크 이후 구 이미지(actuator 없음) 재배포는 실패한다** — 새 리비전이 `/actuator/health/readiness` 를 치므로 서킷브레이커가 롤백한다. 되돌려야 하면 `batch.tf` 의 `healthCheck` 를 지우고 `-replace` 로 리비전을 먼저 갱신한다.
- **`/actuator/**`(prometheus·health)는 api 서비스 포트(8080)에 그대로 열려 있고 앱은 접근 제어를 하지 않는다.** 공개 차단은 ALB 리스너 규칙으로 후속 처리한다 — `/actuator/*` 차단 목록은 `//actuator/…`·`/%61ctuator/…` 처럼 ALB(raw 경로 매칭)와 Tomcat(정규화·디코딩 후 라우팅)이 다르게 보는 경로를 놓치므로 **`/api/*` 만 forward 하는 허용 목록**으로 만든다. ALB 헬스체크는 리스너 규칙을 거치지 않아 `/actuator/health/readiness` 는 계속 통과한다(specs/kb-380 research R-3).
- prod 는 새 스키마 `kbap-prod` 를 쓴다(운영 `/kbap` 무접촉). apply 전에 `CREATE DATABASE `kbap-prod`` 를 해 두면 Flyway 가 첫 기동 때 스키마를 채운다 — 즉 **prod-ecs 는 빈 데이터로 시작**하며, 운영 데이터 이전은 별도 작업이다.
- 기존 prod ECS 를 지우기 전까지 `kbap-prod-api` 태스크 정의 패밀리와 이름이 겹치지 않는다(새 이름은 `kbap-prod-ecs-*`).
