# Quickstart: KB-985 로컬 확인

```bash
docker compose up -d mysql redis
./gradlew :batch:test --tests "com.kbap.batch.notification.discovery.*"
# 로컬 배치 수동 트리거 (09~21시 KST 사이에만 발송)
curl -X POST "localhost:<batch-port>/internal/batch/jobs?jobName=foodDiscoveryPushJob"
```
