# Quickstart: prod 콘텐츠 생성 요청 큐 분리 (운영 절차 = 검증 절차)

전제: 팀 계정 `118178010621`, 리전 `ap-northeast-2`, 관리자 프로필 `kbap-infra`. **KB-548(dev 토큰 복구) 전에 끝낸다.**

```bash
aws sts get-caller-identity --profile kbap-infra --query Account --output text   # 118178010621 이어야 함
```

## 1. 기존 공유 큐 속성 확인

```bash
Q_OLD=$(aws sqs get-queue-url --queue-name kbap-generate-content-queue --profile kbap-infra --region ap-northeast-2 --output text)
aws sqs get-queue-attributes --queue-url "$Q_OLD" --attribute-names VisibilityTimeout RedrivePolicy SqsManagedSseEnabled KmsMasterKeyId FifoQueue \
  --profile kbap-infra --region ap-northeast-2
```

`VisibilityTimeout`·`maxReceiveCount` 값을 적어 둔다. (콘솔 SQS → 큐 → 세부 정보에서 봐도 된다.)

## 2. 콘솔에서 큐 생성 (DLQ 먼저)

1. **DLQ** `kbap-prod-generate-content-dlq` — 표준, 메시지 보존 기간 **14일**, 암호화 SSE-SQS, 나머지 기본.
2. **요청 큐** `kbap-prod-generate-content-queue` — 표준, 가시성 타임아웃 = 1번 값, 메시지 보존 기간 **14일**, 암호화 SSE-SQS, 배달 못한 편지 대기열 **활성화** → 위 DLQ, 최대 수신 수 = 1번 `maxReceiveCount`.
3. 확인(실패 시 중단):

```bash
Q_NEW=$(aws sqs get-queue-url --queue-name kbap-prod-generate-content-queue --profile kbap-infra --region ap-northeast-2 --output text)
aws sqs get-queue-attributes --queue-url "$Q_NEW" --attribute-names MessageRetentionPeriod VisibilityTimeout RedrivePolicy \
  --profile kbap-infra --region ap-northeast-2
# MessageRetentionPeriod=1209600, VisibilityTimeout·maxReceiveCount = 1번 값, deadLetterTargetArn = …:kbap-prod-generate-content-dlq
```

DLQ 도 `MessageRetentionPeriod=1209600` 인지 같은 방식으로 확인한다.

## 3. tfvars 교체 + plan 게이트 (메인 체크아웃)

**:05~:25 또는 :35~:55 (KST) 에 시작** — 발행 잡(:00)·벡터 잡(:30)을 피한다.

```bash
cd /Users/simjonghan/source_code/swm-kbap/kbap/iac/terraform      # tfvars·.terraform 이 여기 있다(워크트리엔 없음)
git -C .. status --short iac/terraform                             # .tf 변경 없음 확인
# prod.tfvars: food_content_queue_name = "kbap-prod-generate-content-queue"
terraform workspace select prod && terraform workspace show        # prod
terraform plan -var-file=prod.tfvars -replace=module.ecs_environment.aws_ecs_task_definition.batch -out=kb547.plan
```

**게이트**: `1 to add, 1 to change, 1 to destroy` —
- `aws_ecs_task_definition.batch` 교체(env `FOOD_CONTENT_QUEUE_URL` 가 새 큐 URL)
- 배치 태스크 롤 정책 in-place(SQS 대상 ARN → 새 큐)

그 밖의 변경이 보이면 apply 하지 않는다. 큐 조회 오류(`no matching SQS Queue`)면 2번 이름을 확인한다.

## 4. apply → 롤링

```bash
terraform apply kb547.plan
# 현재 실행 중 이미지 태그
TD=$(aws ecs describe-services --cluster kbap-prod-ecs-cluster --services kbap-prod-ecs-batch --profile kbap-infra --region ap-northeast-2 \
  --query 'services[0].taskDefinition' --output text)
aws ecs describe-task-definition --task-definition "$TD" --profile kbap-infra --region ap-northeast-2 \
  --query 'taskDefinition.containerDefinitions[?name==`batch`].image' --output text   # …/kbap/batch:batch-<sha>
```

바로 GitHub Actions **Deploy batch prod** → Run workflow → `image_tag` = 위 태그(`batch-<sha>`). 빌드 없이 최신 리비전(방금 apply 한 env)을 복제해 롤링한다.

## 5. 검증

**(a) 태스크 env** — 롤링 완료 후:

```bash
TD=$(aws ecs describe-services --cluster kbap-prod-ecs-cluster --services kbap-prod-ecs-batch --profile kbap-infra --region ap-northeast-2 --query 'services[0].taskDefinition' --output text)
aws ecs describe-task-definition --task-definition "$TD" --profile kbap-infra --region ap-northeast-2 \
  --query 'taskDefinition.containerDefinitions[?name==`batch`] | [0].environment[?name==`FOOD_CONTENT_QUEUE_URL`] | [0].value' --output text
# = $Q_NEW. 옛 URL 이면 실패 — 4번 CI 가 최신 리비전을 복제했는지 확인
```

**(b) 배치 롤 권한** — 콘솔 IAM → `kbap-prod-ecs-batch-task-role` → SQS 문의 Resource 가 `…:kbap-prod-generate-content-queue` 하나.

**(c) 발행** — 다음 정시(:00) 이후 배치 로그(CloudWatch `kbap-prod-ecs` 배치 로그 그룹):
`음식 콘텐츠 아웃박스 발행 완료 attempted=N succeeded=N failed=0`. `failed>0` 이고 `AccessDenied` 면 구 태스크가 아직 떠 있거나 롤링 실패 — 서비스 이벤트 확인.
`attempted=0` 이면 발행할 PENDING 이 없던 것 — 자연 유입을 기다리거나 prod FAILED 음식 1건을 관리자 재요청한 뒤 다음 정시에 다시 본다.

**(d) 건수 대조** — 전환 시각 `T`(apply 시각) 기준, prod DB 읽기 전용(바스티온 터널 13308 + `mysql-prod` MCP):

```sql
SELECT COUNT(*) FROM food_content_outbox WHERE sent_at >= 'T' AND status = 'ACTIVE';
```

```bash
aws sqs get-queue-attributes --queue-url "$Q_NEW" --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
  --profile kbap-infra --region ap-northeast-2
```

두 값이 같아야 한다(소비자가 없으므로 = SC-002). 공유 큐로 간 prod 발행은 (a)·(b)에 의해 불가 = SC-001.

**(e) dev 무변경** — dev 배치 태스크의 `FOOD_CONTENT_QUEUE_URL` 이 여전히 `kbap-generate-content-queue` (클러스터 `kbap-dev-ecs-cluster`, 서비스 `kbap-dev-ecs-batch` 로 (a) 반복).

## 롤백

새 큐에 문제가 있을 때만: `prod.tfvars` 를 공유 큐 이름으로 되돌리고 3·4번 반복. 되돌리면 유출 경로가 다시 열리므로 KB-548 을 보류한다. 새 큐에 쌓인 메시지는 14일간 남는다.
