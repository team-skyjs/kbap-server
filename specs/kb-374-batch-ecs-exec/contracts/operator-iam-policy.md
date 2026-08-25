# Contract: 환경별 운영 IAM 정책 경계

Terraform `modules/ecs-environment/iam.tf` 가 환경당 1개 생성한다. 아래는 dev 기준 — prod 는 `dev` → `prod` 만 다르다(모듈 `name_prefix` 파생).

## 운영 사용자 `kbap-dev-ecs-batch-operator` 인라인 정책

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "FindBatchTask",
      "Effect": "Allow",
      "Action": ["ecs:ListTasks", "ecs:DescribeTasks"],
      "Resource": "*",
      "Condition": {
        "ArnEquals": { "ecs:cluster": "arn:aws:ecs:ap-northeast-2:118178010621:cluster/kbap-dev-ecs-cluster" }
      }
    },
    {
      "Sid": "ExecIntoBatchOnly",
      "Effect": "Allow",
      "Action": "ecs:ExecuteCommand",
      "Resource": "arn:aws:ecs:ap-northeast-2:118178010621:task/kbap-dev-ecs-cluster/*",
      "Condition": {
        "StringEquals": { "ecs:container-name": "batch" }
      }
    }
  ]
}
```

- 액세스 키는 Terraform 이 만들지 않는다 — 콘솔에서 발급해 젠킨스 크리덴셜(`aws-dev` / `aws-prod`)에만 저장.
- `ssm:StartSession` 불필요 — `ecs:ExecuteCommand` API 가 내부에서 세션을 연다.

## 태스크 역할 `kbap-dev-ecs-batch-task-role` 추가 statement

```json
{
  "Sid": "EcsExecChannel",
  "Effect": "Allow",
  "Action": [
    "ssmmessages:CreateControlChannel",
    "ssmmessages:CreateDataChannel",
    "ssmmessages:OpenControlChannel",
    "ssmmessages:OpenDataChannel"
  ],
  "Resource": "*"
}
```

기존 statement(SQS 발행·S3 images 읽기/쓰기)는 그대로.

## 거부되어야 하는 호출 (검증 항목)

| 시도 | 기대 |
|---|---|
| dev 사용자로 `--cluster kbap-prod-ecs-cluster` | `AccessDeniedException` |
| dev 사용자로 `--container api`(api 태스크) | `AccessDeniedException` |
| 자격증명 없이 배치 인스턴스 IP:8080 접속(인터넷) | 연결 거부/타임아웃 (SG 무변경) |
