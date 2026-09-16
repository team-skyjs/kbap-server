# Data Model: prod 콘텐츠 생성 요청 큐 분리

새 영속 데이터·스키마 변경은 없다. 다루는 것은 AWS 큐 자원과, 전환 중 기존 outbox 상태 전이다.

## 큐 자원

| 자원 | 이름 | 종류 | 보관 | 가시성 타임아웃 | maxReceiveCount | DLQ | 암호화 | 발행자 | 소비자 |
|---|---|---|---|---|---|---|---|---|---|
| prod 요청 큐 | `kbap-prod-generate-content-queue` | 표준 | 14일 | 1800초 | 2 | prod DLQ | SSE-SQS | prod 배치 | 없음(KB-551 에서 prod Lambda) |
| prod DLQ | `kbap-prod-generate-content-dlq` | 표준 | 14일 | 기본 | — | — | SSE-SQS | (redrive) | 없음 |
| 공유 큐 (변경 없음) | `kbap-generate-content-queue` | 표준 | 2일 | 1800초 | 2 | `kbap-generate-content-dlq`(4일) | SSE-SQS | dev 배치 | dev Lambda |

공유 큐 값은 2026-09-12 `kbap-infra` 로 실측(`get-queue-attributes`).

검증 규칙:
- 두 prod 큐의 보관 기간 = 1,209,600초(14일).
- prod 큐의 `VisibilityTimeout`·`RedrivePolicy.maxReceiveCount` = 공유 큐 값.
- prod 큐의 `RedrivePolicy.deadLetterTargetArn` = prod DLQ ARN.

## 테라폼 바인딩

```
prod.tfvars: food_content_queue_name ──▶ data.aws_sqs_queue.food_content (이름 조회)
                                            ├─▶ batch_env.FOOD_CONTENT_QUEUE_URL (태스크 정의 — ignore_changes, -replace 로만 갱신)
                                            └─▶ batch_task 롤 정책 sqs:SendMessage 대상 ARN (apply 즉시 갱신)
```

## 전환 중 outbox 상태 (`food_content_outbox`, 규칙 무변경)

| 시점 | 발행 결과 | outbox 상태 | 비고 |
|---|---|---|---|
| apply 전 | 공유 큐로 성공 | PENDING → SENT | 현재 동작(유출 경로) |
| apply 후 · 롤링 전 (구 태스크) | 옛 URL 로 발행 → `AccessDenied` | PENDING 유지, `attempts+1` | fail-closed |
| 롤링 후 (신 태스크) | 새 큐로 성공 | PENDING → SENT | 다음 정시(:00)에 재발행분 포함 |
| KB-551 가동 후 | Lambda 적재 | SENT → COMPLETE | 범위 밖 |
