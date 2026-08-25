# Quickstart: dev 적용·검증 절차 (= 이 기능의 Green 판정)

구현 전에 확정하는 검증 절차다. 아래 항목이 전부 통과해야 Green.

## dev 실측 기록 (2026-08-25)

| 단계 | 결과 |
|---|---|
| 0 | AWS CLI 2.36 ✓ · **Session Manager plugin 미설치**(pkg 설치에 sudo 필요 — 사람이 `brew install --cask session-manager-plugin`) |
| 1 | `eclipse-temurin:21-jre` 에 curl 8.18 포함 → Dockerfile 무변경 |
| 2 | ECS state 가 유실돼 있어(워크트리 삭제로 추정) 실물 50개를 `import` 블록으로 먼저 편입 — plan "50 import / 2 add / 9 change / 0 destroy", 15:06 apply 성공. state 는 워크트리 로컬 + 메인 체크아웃 `terraform.tfstate.d/dev-ecs/` 백업(S3 백엔드는 사용자 결정으로 미도입) |
| 3 | 운영 사용자 `kbap-dev-ecs-batch-operator` 생성됨 — 액세스 키 발급은 사람 몫(미완) |
| 4 | 강제 재배포 전 **부수 장애 발견·복구**: 인스턴스 3대가 삭제된 프로파일 `kbap-dev-ecs-instance-profile`(10:23 리네임 apply 잔재)을 물고 있어 10:2x 부터 ECS·SSM 에이전트 단절 → batch ASG 리프레시(신규 인스턴스) + api 는 프로파일 재연결 후 ASG 리프레시로 교체 → 3대 모두 `agentConnected=true`. 배치 태스크 `kbap-dev-ecs-batch:3` 에서 `enableExecuteCommand=true`, `ExecuteCommandAgent=RUNNING` ✓ |
| 5~7 | 미실행 — 플러그인(0단계)·운영 키(3단계) 확보 후 진행 |
| 8 | README 절 작성 완료, 재현 검증은 5단계 이후 |

## 0. 호출 호스트 준비 (한 번)

```bash
aws --version                      # v2
session-manager-plugin --version   # 없으면: brew install --cask session-manager-plugin
```

## 1. 배치 이미지에 curl 이 있는지 확인 (Dockerfile 변경 여부 결정)

apply 전, 현재 dev 배치 컨테이너(Exec 미적용이면 컨테이너 인스턴스에 SSM 으로 들어가 `docker exec`)에서:

```bash
curl --version
```
있으면 `Dockerfile.batch` 무변경. 없으면 런타임 스테이지에 `apt-get install -y curl` 추가 후 이미지 재빌드가 선행.

## 2. Terraform 적용 (dev)

```bash
cd iac/terraform
terraform fmt -check -recursive && terraform validate
terraform plan  -var-file=dev.tfvars   # 기대 변경: aws_ecs_service.batch(플래그) · batch_task 정책 · aws_iam_user/policy 신규. 그 외 0
terraform apply -var-file=dev.tfvars
```

**plan 에 SG·태스크 정의·ALB 변경이 보이면 중단** — 이 기능의 변경 범위 밖이다.

## 3. 운영 자격증명 (사람)

콘솔 → IAM → 사용자 `kbap-dev-ecs-batch-operator` → 액세스 키 발급 → `aws configure --profile kbap-dev-batch-operator`.

## 4. 배치 재배포 → Exec 에이전트 확인

```bash
aws ecs update-service --cluster kbap-dev-ecs-cluster --service kbap-dev-ecs-batch --force-new-deployment --profile kbap-infra
aws ecs wait services-stable --cluster kbap-dev-ecs-cluster --services kbap-dev-ecs-batch --profile kbap-infra
TASK=$(aws ecs list-tasks --cluster kbap-dev-ecs-cluster --service-name kbap-dev-ecs-batch --query 'taskArns[0]' --output text --profile kbap-dev-batch-operator)
aws ecs describe-tasks --cluster kbap-dev-ecs-cluster --tasks $TASK --profile kbap-dev-batch-operator \
  --query 'tasks[0].containers[0].managedAgents[?name==`ExecuteCommandAgent`].lastStatus' --output text
# 기대: RUNNING
```

## 5. 잡 원격 실행·조회 (US1)

```bash
iac/scripts/batch-job.sh run dev <jobName>        # 기대: exit 0, JSON 에 executionId·status STARTED
iac/scripts/batch-job.sh status dev <executionId> # 기대: exit 0, status → COMPLETED (또는 FAILED — 잡 자체 결과)
iac/scripts/batch-job.sh run dev no-such-job      # 기대: exit 1, message 에 실행 가능 잡 목록
iac/scripts/batch-job.sh run dev <jobName>  (실행 중에 재호출)  # 기대: exit 2, ALREADY_RUNNING + executionId
```

## 6. 권한 경계 (US2)

```bash
AWS_PROFILE=kbap-dev-batch-operator aws ecs list-tasks --cluster kbap-prod-ecs-cluster            # 기대: AccessDenied
AWS_PROFILE=kbap-dev-batch-operator aws ecs execute-command --cluster kbap-dev-ecs-cluster \
  --task <api-task-arn> --container api --interactive --command "true"                            # 기대: AccessDenied
```

## 7. 포트 미개방 유지 (FR-003)

```bash
curl -m 5 http://<batch-instance-public-ip>:8080/internal/batch/jobs   # 기대: 연결 실패(timeout)
```

## 8. 문서 (US3)

`iac/terraform/README.md` 의 "배치 잡 원격 실행" 절만 보고 팀원이 5단계를 재현할 수 있는지 확인.

## prod

dev 8단계 전부 통과 후, 같은 절차를 `prod.tfvars`·`kbap-prod-batch-operator` 로 반복(배치 재배포는 잠깐 다운 — 잡 미실행 시간대에).
