# KB-172 IAM 역할 정책 (붙여넣기용)

GitHub OIDC 로 assume 하는 배포 역할 3개의 신뢰정책·권한정책 완성본. 아래 **자리표시자만 치환**해 콘솔에 붙여넣는다.

| 자리표시자 | 값 | 어디서 |
|---|---|---|
| `<ACCOUNT_ID>` | AWS 계정 ID(12자리) | 콘솔 우상단 계정 메뉴 |
| `<REGION>` | 배포 리전 (예: `ap-northeast-2`) | ECR·EC2·ECS 가 있는 리전 |
| `<INSTANCE_ID>` | 공용 EC2 인스턴스 ID (`i-...`) | EC2 콘솔 |
| `<ECS_TASK_EXECUTION_ROLE>` · `<ECS_TASK_ROLE>` | prod 태스크정의가 참조하는 역할 이름 | ECS 태스크정의 |

- GitHub repo: `team-skyjs/kbap-server` (고정)
- ECR repo: `kbap/api` (고정)
- OIDC provider: `token.actions.githubusercontent.com` (§1 에서 생성)

**콘솔 생성 흐름 (역할마다)**: IAM → 역할 → 역할 생성 → **사용자 지정 신뢰 정책** 선택 → 아래 트러스트 JSON 붙여넣기 → 다음(관리형 정책 없이) → 역할 이름 입력 → 생성 → 생성된 역할의 **권한 탭 → 인라인 정책 추가 → JSON** → 아래 권한 JSON 붙여넣기 → 저장.

---

## 1. `gha-deploy-dev`

### 신뢰정책
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
        "token.actions.githubusercontent.com:sub": "repo:team-skyjs/kbap-server:environment:dev"
      }
    }
  }]
}
```

### 권한정책 (ECR push + SSM)
```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "EcrAuth", "Effect": "Allow", "Action": "ecr:GetAuthorizationToken", "Resource": "*" },
    { "Sid": "EcrPush", "Effect": "Allow",
      "Action": ["ecr:BatchCheckLayerAvailability","ecr:InitiateLayerUpload","ecr:UploadLayerPart","ecr:CompleteLayerUpload","ecr:PutImage"],
      "Resource": "arn:aws:ecr:<REGION>:<ACCOUNT_ID>:repository/kbap/api" },
    { "Sid": "SsmSendCommand", "Effect": "Allow", "Action": "ssm:SendCommand",
      "Resource": ["arn:aws:ssm:<REGION>::document/AWS-RunShellScript","arn:aws:ec2:<REGION>:<ACCOUNT_ID>:instance/<INSTANCE_ID>"] },
    { "Sid": "SsmRead", "Effect": "Allow", "Action": ["ssm:GetCommandInvocation","ssm:ListCommands"], "Resource": "*" }
  ]
}
```

## 2. `gha-deploy-staging`

**dev 와 동일** — 신뢰정책의 `sub` 만 `...:environment:staging` 으로, 권한정책은 그대로(같은 EC2·ECR 이라 `<INSTANCE_ID>` 동일). 신뢰정책 `sub` 한 줄:
```
"token.actions.githubusercontent.com:sub": "repo:team-skyjs/kbap-server:environment:staging"
```

## 3. `gha-deploy-prod`

### 신뢰정책
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
        "token.actions.githubusercontent.com:sub": "repo:team-skyjs/kbap-server:environment:prod"
      }
    }
  }]
}
```

### 권한정책 (ECR push + ECS 블루/그린 + PassRole)
```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "EcrAuth", "Effect": "Allow", "Action": "ecr:GetAuthorizationToken", "Resource": "*" },
    { "Sid": "EcrPush", "Effect": "Allow",
      "Action": ["ecr:BatchCheckLayerAvailability","ecr:InitiateLayerUpload","ecr:UploadLayerPart","ecr:CompleteLayerUpload","ecr:PutImage"],
      "Resource": "arn:aws:ecr:<REGION>:<ACCOUNT_ID>:repository/kbap/api" },
    { "Sid": "EcsDeploy", "Effect": "Allow",
      "Action": ["ecs:DescribeServices","ecs:DescribeTaskDefinition","ecs:RegisterTaskDefinition","ecs:UpdateService"],
      "Resource": "*" },
    { "Sid": "PassRole", "Effect": "Allow", "Action": "iam:PassRole",
      "Resource": ["arn:aws:iam::<ACCOUNT_ID>:role/<ECS_TASK_EXECUTION_ROLE>","arn:aws:iam::<ACCOUNT_ID>:role/<ECS_TASK_ROLE>"] }
  ]
}
```

> 비고: `ecs:RegisterTaskDefinition`·`DescribeTaskDefinition` 은 리소스 수준 제약이 약해 `Resource: "*"`. 더 조이려면 `EcsDeploy` 의 `DescribeServices`·`UpdateService` 를 서비스 ARN(`arn:aws:ecs:<REGION>:<ACCOUNT_ID>:service/<CLUSTER>/<SERVICE>`)으로 좁힐 수 있다. EC2 의 `docker pull` 은 **인스턴스 프로파일**이 담당하므로(§3) 이 CI 역할엔 ECR **pull 권한이 불필요**하고 push 만 있으면 된다.
