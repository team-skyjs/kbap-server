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

1. **앱 배포 먼저** — 평소 CI 경로(`deploy-api.sh dev <tag>`, `deploy-batch.sh dev <tag>`). api 는 태스크 정의 변경이 없으므로 이걸로 끝.
   확인:
   ```bash
   # ALB 콘솔: 타깃 전부 healthy 유지 (헬스체크 경로 무변경)
   # 인스턴스 안(SSM 세션)
   PORT=$(docker port <api-container> 8080 | cut -d: -f2); curl -s localhost:$PORT/actuator/prometheus | grep -c 'application="kbap-api"'
   ```
2. **terraform** — batch 태스크 정의에 헬스체크 리비전 강제
   ```bash
   cd iac/terraform
   terraform apply -var-file=dev.tfvars -replace=module.ecs_environment.aws_ecs_task_definition.batch
   ```
   서비스는 아직 구 리비전(`ignore_changes`). 
3. **batch CI 재배포 1회**(이미지 태그 동일해도 됨) → 새 리비전이 헬스체크를 승계.
   확인: `aws ecs describe-tasks … --query 'tasks[].containers[].healthStatus'` → `HEALTHY`; `describe-task-definition kbap-dev-ecs-batch` 에 `healthCheck` 존재(US4).

prod 는 같은 순서. batch 는 prod desired 0 이라 2 단계까지만(태스크 정의 갱신).

## 되돌리기

api: 구 이미지 재배포로 끝(인프라 무변경). batch: 구 이미지(actuator 없음)를 새 리비전에 올리면 헬스체크가 실패해 서킷브레이커가 롤백한다 — 되돌릴 땐 `batch.tf` 헬스체크 제거 후 `-replace` 를 먼저 한다.
