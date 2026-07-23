# 배치 배포·실행 가이드 (foodContentJob)

음식 콘텐츠 배치(:app:batch)는 상시 서비스가 아니라 **run-to-completion ECS 태스크**다.
컨테이너가 기동되어 잡을 돌리고, 잡이 끝나면 JVM 종료와 함께 태스크도 내려간다.

환경별 실행 기반이 다르다 — **prod 는 ECS(Fargate)**, **dev 는 api 와 동일하게 EC2+SSM**
(ECR 푸시 후 SSM Run Command 로 일회성 `docker run`).

| 구성 요소 | 트리거 | 역할 |
|---|---|---|
| `deploy-batch-prod.yml` | main push · 수동 | prod 배치 이미지 빌드(`Dockerfile.batch`)·푸시 + 태스크정의 리비전 갱신 |
| `deploy-batch-dev.yml` | develop push(배치 경로 변경 시) · 수동 | dev 배치 이미지 푸시 + `batch-latest` 태그 이동 |
| `run-batch.yml` | 수동(환경 선택) | prod: ECS run-task / dev: SSM 으로 dev EC2 에서 `docker run --rm` (둘 다 fire-and-forget) |
| EventBridge Scheduler | 매시 17분 (인프라 소유) | prod 시간별 자동 실행 |

- 빌드와 실행이 분리되어 있다: 이미지는 코드가 바뀔 때만 굽고, 실행은 준비된 이미지를
  기동만 한다. GitHub Actions 러너가 배치 종료를 관찰하지 않는다(비용 0 유지).
- `deploy-batch-dev.yml` 의 paths 필터는 `app/batch/**`·`Dockerfile.batch` 만 본다.
  공유 모듈(core·domain·infra)만 바뀐 경우는 수동 실행으로 갱신한다.
- dev 실행은 `batch-latest` 이동 태그를 쓴다. 컨테이너 이름(`kbap-batch`)이 고정이라
  이전 실행이 살아 있으면 docker run 이 이름 충돌로 실패한다 — dev 에선 이게 자연 직렬화.
- **중복 실행 방지는 의도적으로 없다** — 이전 실행이 1시간을 넘겨 다음 트리거와 겹쳐도
  작업별 커밋 구조라 데이터는 안전하고, 같은 음식에 대한 LLM 중복 과금만 발생한다.
  사고가 나면 배치 부팅 시 `JobExplorer.findRunningJobExecutions("foodContentJob")` 로
  실행 중이면 종료하는 가드를 추가한다.

## 최초 1회: dev EC2 env 파일 (인프라 소유)

dev 는 태스크정의가 없다. dev EC2 에 `/opt/kbap/kbap-batch.env` 를 만들어 두면 된다
(api 의 `/opt/kbap/<container>.env` 와 같은 방식):

```
SPRING_PROFILES_ACTIVE=dev
SPRING_BATCH_JOB_ENABLED=true
KBAP_LLM_OPENAI_ENABLED=true
DB_URL=...
DB_USERNAME=...
DB_PASSWORD=...
OPENAI_API_KEY=...
```

## 최초 1회: prod 태스크정의 등록 (인프라 소유)

파이프라인은 기존 리비전을 복제해 이미지만 바꾸므로, family 최초 등록은 수동이다.
아래 템플릿의 `<...>` 를 채워 등록한다:

```bash
aws ecs register-task-definition --cli-input-json file://batch-taskdef.json
```

```json
{
  "family": "kbap-batch-prod",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "executionRoleArn": "<ecsTaskExecutionRole ARN — ECR pull·로그·SSM 읽기 권한>",
  "taskRoleArn": "<태스크 롤 ARN — 현재 배치는 AWS API 미사용이라 최소 권한>",
  "containerDefinitions": [
    {
      "name": "kbap-batch",
      "image": "<ECR>/<repo>:batch-<sha>",
      "essential": true,
      "environment": [
        { "name": "SPRING_PROFILES_ACTIVE", "value": "prod" },
        { "name": "SPRING_BATCH_JOB_ENABLED", "value": "true" },
        { "name": "KBAP_LLM_OPENAI_ENABLED", "value": "true" }
      ],
      "secrets": [
        { "name": "DB_URL", "valueFrom": "<SSM 파라미터 ARN>" },
        { "name": "DB_USERNAME", "valueFrom": "<SSM 파라미터 ARN>" },
        { "name": "DB_PASSWORD", "valueFrom": "<SSM 파라미터 ARN>" },
        { "name": "OPENAI_API_KEY", "valueFrom": "<SSM 파라미터 ARN>" }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/kbap-batch-prod",
          "awslogs-region": "<region>",
          "awslogs-stream-prefix": "batch",
          "awslogs-create-group": "true"
        }
      }
    }
  ]
}
```

### 환경변수 정리

| 변수 | 값 | 비고 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod`(또는 `dev`) | 데이터소스 프로필 |
| `SPRING_BATCH_JOB_ENABLED` | `true` | 잡 실행 스위치(기본 false) |
| `KBAP_LLM_OPENAI_ENABLED` | `true` | 없으면 번역·설명 클라이언트 미구성으로 전 건 스킵 |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | secrets | api 와 동일 값 |
| `OPENAI_API_KEY` | secrets | SSM SecureString |
| `KBAP_BATCH_CONTENT_CHUNK_SIZE` | (선택) | 기본 10 |

추후 변경: 다중 모델 복원 시 `KBAP_LLM_UPSTAGE_ENABLED`/`UPSTAGE_API_KEY`·
`KBAP_LLM_GEMINI_ENABLED`/`GOOGLE_API_KEY` 추가(+ batch application.yml 의
`kbap.llm.avoidance.min-agreement: 1` 줄 삭제). 이미지 생성 개방 시
`KBAP_STORAGE_ENABLED=true` + `KBAP_STORAGE_REGION` + `KBAP_STORAGE_BUCKET`.

## 최초 1회: EventBridge Scheduler 등록 (prod 시간별 실행, 인프라 소유)

GitHub cron 대신 EventBridge Scheduler 가 매시 RunTask 를 직접 호출한다
(러너·폴링 없음, 사실상 무료). 태스크정의 ARN 을 리비전 없이 주면 항상 최신 리비전을 쓴다.

```bash
aws scheduler create-schedule \
  --name kbap-batch-hourly-prod \
  --schedule-expression "cron(17 * * * ? *)" \
  --schedule-expression-timezone "Asia/Seoul" \
  --flexible-time-window '{"Mode": "OFF"}' \
  --target '{
    "Arn": "<ECS 클러스터 ARN>",
    "RoleArn": "<스케줄러 실행 롤 ARN — ecs:RunTask + 두 태스크 롤 iam:PassRole>",
    "EcsParameters": {
      "TaskDefinitionArn": "arn:aws:ecs:<region>:<account>:task-definition/kbap-batch-prod",
      "LaunchType": "FARGATE",
      "NetworkConfiguration": {
        "awsvpcConfiguration": {
          "Subnets": ["<subnet-aaa>", "<subnet-bbb>"],
          "SecurityGroups": ["<sg-xxx>"],
          "AssignPublicIp": "DISABLED"
        }
      }
    }
  }'
```

실패 확인은 CloudWatch 로그(`/ecs/kbap-batch-prod`)로 한다. 알림이 필요해지면
EventBridge 규칙(ECS Task State Change, `stopCode`/`exitCode` 비정상) → SNS 를 추가한다.

## GitHub 환경별 vars

**prod 환경** — 기존 api 배포 vars(`AWS_ROLE_ARN`·`AWS_REGION`·`ECR_REPOSITORY`·`ECS_CLUSTER`)에 더해:

| var | 값 예시 | 용도 |
|---|---|---|
| `ECS_BATCH_TASK_FAMILY` | `kbap-batch-prod` | 태스크정의 family |
| `BATCH_SUBNETS` | `subnet-aaa,subnet-bbb` | run-task awsvpc 서브넷(프라이빗 — OpenAI 아웃바운드용 NAT 필요) |
| `BATCH_SECURITY_GROUPS` | `sg-xxx` | RDS 인바운드 허용된 SG |
| `ECR_BATCH_REPOSITORY` | `kbap-batch` | (선택) 배치 전용 ECR 저장소 — 없으면 `ECR_REPOSITORY` 에 `batch-` 태그로 공존 |

**dev 환경** — 추가 var 없음. 기존 api dev vars(`AWS_ROLE_ARN`·`AWS_REGION`·`ECR_REPOSITORY`·
`EC2_INSTANCE_ID`)를 그대로 쓴다. `ECR_BATCH_REPOSITORY` 는 prod 와 동일하게 선택.

OIDC 롤 권한: prod 롤에는 기존 권한에 더해 `ecs:RunTask`·`ecs:DescribeTaskDefinition`·
`ecs:RegisterTaskDefinition` 과 태스크정의의 execution/task 롤에 대한 `iam:PassRole` 이 필요하다.
dev 롤은 api 배포가 이미 쓰는 SSM(send-command·get-command-invocation)·ECR 권한이면 충분하다.
