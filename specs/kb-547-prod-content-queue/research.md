# Research: prod 콘텐츠 생성 요청 큐 분리

## R1. 큐 이름

- **Decision**: 큐 `kbap-prod-generate-content-queue`, DLQ `kbap-prod-generate-content-dlq`.
- **Rationale**: 팀 리소스 명명 `kbap-<env>-…`(`kbap-prod-ecs-*`·`kbap-prod-redis`)에 맞춘다. 기존 공유 큐는 이름을 유지하고 사실상 dev 전용이 된다 — 개명하면 dev Lambda 트리거·dev tfvars 까지 건드려야 한다.
- **Alternatives**: `kbap-generate-content-queue-prod`(접미) — 관례와 어긋나 기각. 공유 큐를 `kbap-dev-…` 로 개명 — 범위가 dev 까지 번져 기각.

## R2. 큐 속성

- **Decision**: 표준 큐. 보관 기간 14일(큐·DLQ 모두). 가시성 타임아웃·`maxReceiveCount` 는 기존 공유 큐 값을 콘솔에서 읽어 그대로. 암호화 SSE-SQS(콘솔 기본). 나머지(지연·최대 크기·수신 대기) 콘솔 기본.
- **Rationale**:
  - 표준: 발행 어댑터가 `sendMessageBatch` 에 `MessageGroupId` 를 주지 않는다 — FIFO 면 발행이 전부 실패한다.
  - 14일: 소비자(KB-551)는 dev READY 음식 이관 뒤에 가동되므로 공백이 길다. 공유 DLQ(4일)처럼 짧으면 요청이 조용히 만료된다.
  - 가시성·재시도 동일: 같은 Lambda 이미지가 소비한다(KB-549). SQS 트리거는 큐 가시성 타임아웃 ≥ 함수 타임아웃을 요구하는데, 기존 값이 이미 이를 만족한다.
  - SSE-SQS: 배치 롤에 KMS 권한이 없는데 기존 큐로 발행이 동작한다 — 기존 큐는 CMK 암호화가 아니다. CMK 를 쓰면 배치 롤·Lambda 롤 모두 KMS 권한이 필요해진다.
  - 팀 CLI 프로필은 SQS 조회 권한이 없어(`kbap-prod-deployer` → `NonExistentQueue`) 기존 값을 콘솔에서 확인한다. `kbap-infra`(관리자)로는 `get-queue-attributes` 가능.
- **Alternatives**: SSE-KMS CMK — 권한 추가 비용만 있고 이득 없음, 기각. DLQ redrive allow policy 를 prod 큐로 한정 — 오조작 방지 이득이 작아 콘솔 기본(allowAll) 유지.

## R3. prod 배치를 새 큐로 전환하는 방법

- **Decision**: (1) 로컬 `prod.tfvars` 의 `food_content_queue_name` 교체 → (2) `terraform apply -var-file=prod.tfvars -replace=module.ecs_environment.aws_ecs_task_definition.batch` → (3) `deploy-batch-prod.yml` 을 **현재 실행 중 이미지 태그**로 `workflow_dispatch`.
- **Rationale**:
  - 큐 URL 은 태스크 정의 env(`FOOD_CONTENT_QUEUE_URL`)에 박힌다. 태스크 정의는 `ignore_changes = [container_definitions]`, 서비스는 `ignore_changes = [task_definition]` 이라 일반 apply 는 IAM 정책만 바꾸고 env 는 그대로 둔다.
  - `-replace` 로 새 env 를 담은 리비전을 최신으로 등록하면, CI 가 **최신 리비전을 복제해 이미지만 교체**하므로 env 를 승계한다. KB-380(헬스체크)·KB-328(벡터 env) 에서 같은 경로를 이미 썼다(README).
  - `-replace` 리비전의 이미지는 tfvars 의 `batch_image`(갱신 안 된 옛 태그일 수 있음)라 서비스에 직접 물리면 이미지가 되돌아간다. CI 가 이미지를 현재 태그로 덮으므로 CI 경유가 안전하다. `image_tag` 를 주면 빌드 없이 롤링만 한다.
- **Alternatives**:
  - 일반 apply 만 — env 가 안 바뀌어 기각(IAM 만 바뀌면 발행이 거부만 되고 전환이 안 됨).
  - `aws ecs update-service --task-definition <terraform 리비전>` — 이미지 회귀 위험, 기각.
  - `variables.tf` 기본값 변경 — dev 까지 바뀌어 기각.
  - 큐를 테라폼으로 관리(`aws_sqs_queue`) — 기존 큐가 비관리라 import·dev 동시 정비가 필요해 범위 초과. 후속 과제로만 남긴다.

## R4. 전환 창에서의 유출 가능성

- **Decision**: 발행 잡 정시(:00)·벡터 잡(:30)을 피해 **:05~:25 또는 :35~:55** 에 apply → CI 를 이어서 실행. 창 안에서 발행이 일어나도 dev 로 새지 않는다.
- **Rationale**:
  - apply 순간 배치 롤 정책의 대상이 새 큐 ARN 으로 바뀐다(태스크 롤 권한은 요청 시점 평가). 롤링 전 구 태스크는 옛 URL 로 발행을 시도하지만 `AccessDenied` 로 실패한다.
  - 발행 실패는 `recordPublishFailed` — `attempts+1` 만 하고 **PENDING 유지** → 다음 정시에 새 태스크가 새 큐로 재발행한다. 시도 횟수 상한도 없다. 즉 전환은 fail-closed 다.
  - 롤링은 구 태스크 종료 → 신 태스크 기동(단일 인스턴스)이라 두 태스크가 동시에 발행하지 않는다.
- **Alternatives**: 배치를 desired 0 으로 내렸다 올리기 — 같은 효과를 더 많은 조작으로 얻어 기각.

## R5. 기존 메시지와 멈춘 요청

- **Decision**: 공유 큐·공유 DLQ 에 있는 prod 메시지는 건드리지 않는다. SENT 에 멈춘 prod 요청 173건도 이 작업에서 재발행하지 않는다.
- **Rationale**: dev·prod 메시지가 섞여 구분 재처리가 불가하다(KB-548 방침 — 자연 만료). 배치는 SENT 를 재발행하지 않으며, 해당 음식은 dev READY 이관·KB-551 쪽에서 다룬다.

## R6. 검증 방법

- **Decision**: (a) 실행 중 태스크의 `FOOD_CONTENT_QUEUE_URL` = 새 큐 URL, (b) 배치 롤 정책의 SQS 대상 = 새 큐 ARN 하나, (c) 전환 후 첫 발행 로그 `failed=0`, (d) prod `food_content_outbox` 의 전환 이후 `sent_at` 건수 = 새 큐 `ApproximateNumberOfMessages`(소비자가 없어 정확히 일치해야 함).
- **Rationale**: 공유 큐는 dev 발행분이 섞여 "prod 가 안 들어왔다"를 공유 큐 쪽에서 직접 셀 수 없다. 발행 쪽(env·권한)이 새 큐만 가리키고, 발행 성공 건수가 새 큐에 전부 있으면 SC-001·SC-002 가 성립한다.
- 발행할 PENDING 이 없으면 자연 유입(신규 스캔 음식)을 기다리거나, prod FAILED 음식 1건을 관리자 재요청해 PENDING 을 만든다(그 요청은 KB-551 까지 새 큐에 대기 — 어차피 처리될 대상).
- prod DB 는 바스티온 터널 + `mysql-prod` MCP 로 **읽기만** 한다.

## R7. 저장소 변경과 실행 위치

- **Decision**: 커밋 대상은 `prod.tfvars.example`(큐 이름 명시)·README(환경별 큐 한 줄)·이 spec 디렉터리. apply 는 **메인 체크아웃**의 `iac/terraform` 에서 실행한다.
- **Rationale**: 실제 tfvars 는 gitignore 라 예시에 없으면 새 머신에서 기본값(공유 큐)으로 회귀한다(FR-008). 워크트리엔 tfvars·`.terraform` 이 없고, 이 작업은 `.tf` 를 바꾸지 않으므로 메인 체크아웃(develop)에서 돌려도 코드 차이가 없다.
- 지식 위키(`kbap-agenthub`)의 파이프라인 문서에 "큐는 환경별" 을 남긴다.
