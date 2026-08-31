# Quickstart: 앱 내부 메트릭 노출 (KB-380) — 검증·롤아웃 런북

## 로컬 검증

```bash
./gradlew :api:test :batch:test                       # 기존 테스트 회귀만 (신규 테스트 없음 — 사용자 지시)
./gradlew :api:bootRun                                 # SPRING_PROFILES_ACTIVE=local
curl -s localhost:8080/actuator/prometheus | grep -E '^(jvm_memory_used_bytes|hikaricp_connections_active|http_server_requests_seconds_count)' | head
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/actuator/env                                          # 404 (노출 목록 밖)
```

batch: `./gradlew :batch:bootRun` 후 잡을 한 번 트리거(`POST localhost:8080/internal/batch/jobs?jobName=foodContentOutboxPublishJob`)하고 `curl -s localhost:8080/actuator/prometheus | grep spring_batch_job_seconds`.

## dev 롤아웃 (순서 — R-5)

1. **머지 전 terraform** — batch 태스크 정의에 헬스체크 리비전을 등록한다. 서비스는 `ignore_changes=[task_definition]` 이라 실행 중 태스크에 영향 없음.
   ```bash
   cd iac/terraform
   terraform plan  -var-file=dev.tfvars -replace=module.ecs_environment.aws_ecs_task_definition.batch   # diff 가 healthCheck 뿐인지
   terraform apply -var-file=dev.tfvars -replace=module.ecs_environment.aws_ecs_task_definition.batch
   ```
2. **PR 머지** → CI(`deploy-dev.yml`·`deploy-batch-dev.yml`)가 api·batch 를 배포. batch 는 1 의 리비전을 복제하므로 헬스체크가 승계된다.
   확인:
   ```bash
   aws ecs describe-task-definition --task-definition kbap-dev-ecs-batch --query 'taskDefinition.containerDefinitions[0].healthCheck'
   aws ecs describe-tasks --cluster kbap-dev-ecs-cluster --tasks <batch task arn> --query 'tasks[].containers[].healthStatus'   # HEALTHY
   # ALB 콘솔: api 타깃 전부 healthy 유지 (헬스체크 경로 무변경)
   # 인스턴스 안(SSM 세션)
   PORT=$(docker port <api-container> 8080 | cut -d: -f2); curl -s localhost:$PORT/actuator/prometheus | grep -c 'application="kbap-api"'
   ```

1 과 2 사이에 actuator 없는 구 이미지로 CI 가 돌면 헬스체크 실패로 서킷브레이커 롤백 — 창을 짧게. prod 는 같은 순서(`prod.tfvars`), batch 는 desired 0 이라 1 단계만.

## 되돌리기

api: 구 이미지 재배포로 끝(인프라 무변경). batch: 구 이미지(actuator 없음)를 새 리비전에 올리면 헬스체크가 실패해 서킷브레이커가 롤백한다 — 되돌릴 땐 `batch.tf` 헬스체크 제거 후 `-replace` 를 먼저 한다.
