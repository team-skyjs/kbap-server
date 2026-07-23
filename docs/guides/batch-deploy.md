# 배치 배포·실행 가이드 (foodContentJob)

음식 콘텐츠 배치(:app:batch)는 상시 서비스가 아니라 **run-to-completion ECS 태스크**다.
두 워크플로우가 역할을 나눈다:

| 워크플로우 | 트리거 | 역할 |
|---|---|---|
| `deploy-batch-prod.yml` | main push · 수동 | 배치 이미지 빌드(`Dockerfile.batch`)·푸시(`batch-<sha>` 태그) + 태스크정의 리비전 갱신 |
| `run-batch-prod.yml` | 매시 17분 cron · 수동 | 최신 태스크정의로 `run-task` 기동, 중복 방지, 종료 코드 확인 |

schedule 트리거는 **main 에 워크플로우 파일이 있어야** 동작한다. 이전 실행이 살아 있으면
그 회차는 자동 스킵된다(기동 측 직렬화 — RunIdIncrementer 는 동시 실행을 막지 않는다).

## 최초 1회: 태스크정의 등록 (인프라 소유)

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
      "image": "<ECR>/kbap-server:batch-<sha>",
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
| `SPRING_PROFILES_ACTIVE` | `prod` | 데이터소스 프로필 |
| `SPRING_BATCH_JOB_ENABLED` | `true` | 잡 실행 스위치(기본 false) |
| `KBAP_LLM_OPENAI_ENABLED` | `true` | 없으면 번역·설명 클라이언트 미구성으로 전 건 스킵 |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | secrets | api 와 동일 값 |
| `OPENAI_API_KEY` | secrets | SSM SecureString |
| `KBAP_BATCH_CONTENT_CHUNK_SIZE` | (선택) | 기본 10 |

추후 변경: 다중 모델 복원 시 `KBAP_LLM_UPSTAGE_ENABLED`/`UPSTAGE_API_KEY`·
`KBAP_LLM_GEMINI_ENABLED`/`GOOGLE_API_KEY` 추가(+ batch application.yml 의
`kbap.llm.avoidance.min-agreement: 1` 줄 삭제). 이미지 생성 개방 시
`KBAP_STORAGE_ENABLED=true` + `KBAP_STORAGE_REGION` + `KBAP_STORAGE_BUCKET`.

## GitHub 환경(prod) vars

기존 api 배포 vars(`AWS_ROLE_ARN`·`AWS_REGION`·`ECR_REPOSITORY`·`ECS_CLUSTER`)에 더해:

| var | 값 예시 | 용도 |
|---|---|---|
| `ECS_BATCH_TASK_FAMILY` | `kbap-batch-prod` | 태스크정의 family |
| `BATCH_SUBNETS` | `subnet-aaa,subnet-bbb` | run-task awsvpc 서브넷(프라이빗 — OpenAI 아웃바운드용 NAT 필요) |
| `BATCH_SECURITY_GROUPS` | `sg-xxx` | RDS 인바운드 허용된 SG |
| `ECR_BATCH_REPOSITORY` | `kbap-batch` | (선택) 배치 전용 ECR 저장소 — 없으면 `ECR_REPOSITORY` 에 `batch-` 태그로 공존 |

OIDC 롤(`AWS_ROLE_ARN`)에는 기존 권한에 더해 `ecs:RunTask`·`ecs:ListTasks`·
`ecs:DescribeTasks` 와 태스크정의의 execution/task 롤에 대한 `iam:PassRole` 이 필요하다.
