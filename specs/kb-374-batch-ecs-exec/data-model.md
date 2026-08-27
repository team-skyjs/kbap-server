# Data Model: 배치 잡 원격 트리거

새 영속 데이터는 없다. 잡 실행 이력은 배치 앱이 이미 Spring Batch 메타테이블(`BATCH_JOB_INSTANCE`·`BATCH_JOB_EXECUTION` 등, 스키마 owner=api Flyway)에 남기며, 원격 경로는 그 조회 엔드포인트를 그대로 쓴다.

## 권한 경계 모델 (환경당 1세트)

```
운영 IAM 사용자 kbap-<env>-ecs-batch-operator
  └─ 인라인 정책
       ├─ ecs:ListTasks · ecs:DescribeTasks      cond ecs:cluster = arn:...:cluster/kbap-<env>-ecs-cluster
       └─ ecs:ExecuteCommand                      res  arn:...:task/kbap-<env>-ecs-cluster/*
                                                  cond ecs:container-name = batch
                    │  (AWS API — 인터넷 어디서든)
                    ▼
ECS 서비스 kbap-<env>-ecs-batch  (enable_execute_command = true)
  └─ 태스크 (컨테이너 batch, SSM Exec 에이전트 주입)
       └─ 태스크 역할 kbap-<env>-ecs-batch-task-role
            └─ ssmmessages:{Create,Open}{Control,Data}Channel   ← 채널을 "밖으로" 여는 권한
                    │  (컨테이너 내부 localhost)
                    ▼
        배치 앱 :8080  POST /internal/batch/jobs?jobName=  ·  GET /internal/batch/executions/{id}
```

| 요소 | 소유 | 변경 |
|---|---|---|
| 운영 IAM 사용자·정책 | Terraform (`iam.tf`) | 신규 |
| 액세스 키 | 사람(콘솔 발급) → 젠킨스 크리덴셜 | Terraform 밖 — state 에 시크릿 남기지 않음 |
| 서비스 `enable_execute_command` | Terraform (`batch.tf`) | 신규 — `ignore_changes` 대상 아님 |
| 태스크 역할 ssmmessages 권한 | Terraform (`iam.tf`) | 신규 statement |
| 태스크 정의 리비전·컨테이너 정의 | 배포 스크립트 | 무변경 |
| 배치 트리거 포트 SG 규칙 | Terraform (`sg.tf`) | **무변경** (클러스터 내부 한정 유지) |

## 불변식

- dev 운영 사용자 정책의 리소스·조건에는 dev 클러스터 ARN 만, prod 에는 prod 만 나타난다 — 모듈 로컬 `name_prefix` 에서 파생되므로 tfvars 의 `env` 하나로 결정된다.
- `ecs:ExecuteCommand` 의 `ecs:container-name` 조건값은 `local.batch_container_name`("batch") — api 컨테이너 이름과 다르므로 api 에는 명령 실행이 불가하다.
- 잡 실행 상태의 단일 출처는 배치 앱(메타테이블) — 스크립트·젠킨스는 캐시하지 않는다.
