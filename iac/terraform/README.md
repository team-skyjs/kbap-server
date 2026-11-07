# kbap ECS 환경 (Terraform)

dev·prod 를 **같은 모듈(`modules/ecs-environment`) + 환경별 tfvars** 로 세운다. 기존 VPC·RDS·Redis·S3·SQS 는 재사용하고, 그 위에 ECS 클러스터·ALB·CloudWatch 를 새로 만든다(배포는 ECS 롤링). **현재 운영 중인 `kbap-prod-cluster`(ECS)·`kbap-devstg-alb` 는 건드리지 않는다** — 이 구성은 `dev-ecs.kbap.site` / `prod-ecs.kbap.site` 로 별도 ALB 를 받는다.

## 구성 요약

| 항목 | 값 |
|---|---|
| 컨테이너 인스턴스 | **arm64 Graviton** — api 풀 t4g.medium **1대**(dev 는 Spot `spot_instances`, prod 온디맨드, 상한 1대 고정 `api_instance_max_count`), prod batch 풀 t4g.small 1대(`batch_instance_type`), dev 배치 없음. 2026-11-03 비용 고정 결정 — 인스턴스 장애·Spot 회수 땐 ASG 가 새로 띄울 때까지 몇 분 다운. ECS 인스턴스 속성 `workload=api|batch` 로 배치 분리 |
| api 태스크 | bridge 모드·동적 호스트 포트, CPU 상한 없음 / **1,750 MiB**(카나리 중 구·신 2개가 한 인스턴스에 공존 — 3,579 ÷ 2), desired 1, `runtime_platform ARM64`, `JAVA_TOOL_OPTIONS=-Xmx1100m -XX:MaxDirectMemorySize=64m -XX:ReservedCodeCacheSize=96m`·`MALLOC_ARENA_MAX=2` — 태스크 단위 memory 만 있어 `MaxRAMPercentage` 는 호스트(4 GB) 기준으로 계산돼 힙 상한이 1,918 MB 였다(+ 비힙 ≈290 MB 로 1,750 MiB 초과 → 2026-11-04 prod OOM exit 137 두 번). 힙을 1,100 MB 로 고정한다(td `:34`) |
| batch 태스크 | 단일 태스크, 잡 트리거 HTTP 는 배치 인스턴스 8080 고정(클러스터 내부만) |
| 배포 | **CodeDeploy 블루/그린 카나리** — 20% 트래픽 15분 → 100% → 구버전 15분 유지 후 종료. 5xx 알람 시 자동 롤백. 2026-11-01~03 사흘간 ECS 롤링이었다가 1대 구성과 함께 복귀 |
| 진입 | ALB(80→443 리다이렉트, `*.kbap.site` ACM) + Route53 alias `<subdomain>.kbap.site` |
| 로그 | CloudWatch `/kbap/<env>/api`·`/kbap/<env>/batch`, **보관 7일**, Container Insights, 대시보드 `kbap-<env>-ecs` |
| 시크릿 | SSM SecureString `/kbap/<env>/<NAME>` → 태스크 정의 `secrets` 로 주입 (값은 Terraform 밖) |
| AWS 권한 | 태스크 롤(api: S3 접두사 한정, batch: SQS+S3+ECS Exec 채널) — 액세스 키를 env 에 넣지 않는다. 배치 원격 실행은 환경별 운영 사용자 `kbap-<env>-ecs-batch-operator`(아래 절) |

## 소유권 경계

| 대상 | 소유자 |
|---|---|
| 클러스터·ASG·ALB·타깃그룹·IAM·로그그룹·대시보드 | Terraform |
| 배치 운영 IAM 사용자·정책(`kbap-<env>-ecs-batch-operator`) | Terraform |
| 운영 사용자 **액세스 키** | 사람(콘솔 발급) → 젠킨스 크리덴셜 — Terraform·레포에 두지 않는다 |
| 태스크 정의 **리비전**(이미지 태그)·서비스 desired | 배포 스크립트 / CI / 오토스케일링 (`lifecycle.ignore_changes`) |
| SSM 파라미터(이름·값 모두) | 사람/CI (`aws ssm put-parameter`) — Terraform 은 ARN 문자열만 참조 |
| RDS·Redis·VPC·S3·SQS·Route53 존·ACM | 기존 인프라 (data 로 조회, SG 인바운드 규칙만 추가). SQS 콘텐츠 요청 큐는 환경별 — dev `kbap-generate-content-queue`, prod `kbap-prod-generate-content-queue`(콘솔 생성, `food_content_queue_name`, KB-547). 이름을 바꾸면 배치 태스크 정의 `-replace` apply + 배치 CI 재배포까지 해야 env 가 바뀐다 |

## 처음 세우기 (dev)

```bash
cd iac/terraform
cp dev.tfvars.example dev.tfvars      # api_image / batch_image 태그를 ECR 실태그로
terraform init
terraform plan  -var-file=dev.tfvars
terraform apply -var-file=dev.tfvars
```

**apply 전에** 시크릿을 SSM 에 등록해 둔다(없으면 태스크가 기동 못 함). 이미 등록돼 있으면 건너뛴다:

```bash
for k in DB_PASSWORD JWT_SECRET OPENAI_API_KEY GOOGLE_PLACES_API_KEY ANTHROPIC_API_KEY; do
  aws ssm put-parameter --profile kbap-infra --name /kbap/dev/$k --type SecureString --overwrite --value '...'
done
aws ssm put-parameter --profile kbap-infra --name /kbap/dev/FIREBASE_CREDENTIALS_JSON \
  --type SecureString --overwrite --value "$(cat firebase-service-account.json)"

```

확인: `https://dev.kbap.site/api/app-version` 200 → CloudWatch 대시보드 `kbap-dev-ecs`. (`/actuator/**`·`/swagger-ui/**`·`/v3/api-docs` 는 ALB 규칙이 404 로 막는다 — 아래 "관리 경로 차단".)

prod 는 `prod.tfvars.example` 로 동일하게.

## state — S3 백엔드 + workspace (KB-390)

state 는 **S3 `kbap-terraform-state`**(키 `ecs/terraform.tfstate`, 프로필 `kbap-infra`, `use_lockfile` 잠금)에 있고 환경은 **workspace `dev` / `prod`** 로 나뉜다(S3 키 `env:/<ws>/ecs/terraform.tfstate`). 로컬 `terraform.tfstate*`·`terraform.tfstate.d/` 는 쓰지 않는다 — 있으면 `_local-state-archive/`(gitignore) 로 치운다.

```bash
terraform init                     # 백엔드는 versions.tf 에 고정 — 옵션 없음
terraform workspace select dev     # 또는 prod. apply 전 반드시 `terraform workspace show`
terraform plan -var-file=dev.tfvars
```

- 처음 세우는 머신: `aws configure --profile kbap-infra`(관리자 키) → `terraform init` → `workspace select` → `<env>.tfvars` 복원 → `plan` 이 **No changes** 여야 시작 가능. tfvars 는 git 에 없다 — `iac/scripts/gen-import-blocks.sh <env>` 가 AWS 실물에서 `<env>.tfvars.generated` 를 뽑아 주니 예시 파일과 대조해 `<env>.tfvars` 로 복사한다(집 IP 등 실값은 지식 위키).
- **state 를 잃었을 때(import 복구)**: `gen-import-blocks.sh <env>` → `import.<env>.tf` 생성(리소스별 `import {}` 블록) → `plan -out` 으로 게이트 — **`N to import` + 의도한 `to add` + `0 to destroy`, replacement 0** 이어야 apply. apply 후 `import.<env>.tf` 삭제 → 재plan No changes. 2026-08-28 복구 기록: dev 55 import, prod 50 import(+ KB-374 배치 운영자 IAM 2 add).
- 동시 apply 는 S3 lockfile 이 막는다. 다른 사람이 plan 중이면 lock 오류가 나니 기다린다.

## 관리 경로 차단 (KB-390)

`blocked_path_patterns`(dev `["*actuator*"]`, prod `["*actuator*","*swagger*","*api-docs*"]`)이 HTTPS 리스너 priority 10 **fixed-response 404** 규칙이 된다. ALB 는 매칭 전에 퍼센트 디코딩을 하므로 `/%61ctuator` 우회도 404 — WAF 불필요. Alloy 는 호스트 네트워크에서 컨테이너 포트로 직접 긁어 영향 없다.

## 배포

**api — CodeDeploy 카나리 (`iac/scripts/deploy-api.sh <env> <tag>` / CI `deploy-<env>.yml`)**

1. 현재 태스크 정의에서 이미지만 바꿔 새 리비전 등록 → appspec 으로 CodeDeploy 배포 생성
2. 그린 태스크를 **같은 인스턴스**에 띄운다(인스턴스 1대·태스크 1,750 × 2). 헬스체크를 넘으면 리스너가 그린 타깃그룹으로 20% → 15분 뒤 100%
3. 구버전(블루)은 15분 더 살아 있어 그 안에는 트래픽만 되돌리는 즉시 롤백이 된다. 5xx 알람·헬스체크 실패는 자동 롤백
4. CI 는 배포 상태를 폴링해 Succeeded 가 아니면 실패로 끝낸다. 수동 롤백은 `aws deploy stop-deployment --auto-rollback-enabled`

**이미지는 arm64** — 인스턴스가 Graviton 이라 CI 가 `docker buildx build --platform linux/arm64` 로 만든다. Dockerfile 의 빌드 스테이지는 `--platform=$BUILDPLATFORM`(러너 네이티브)이라 Gradle 컴파일은 에뮬레이션되지 않고, 런타임 스테이지만 arm64 로 뽑힌다. 로컬(Apple Silicon)에서는 네이티브.

**batch — 롤링 (`iac/scripts/deploy-batch.sh <env> <tag>`)**: 단일 인스턴스·고정 포트라 구 태스크를 먼저 내리고 신 태스크를 올린다(잠깐 다운). 서킷브레이커로 기동 실패 시 자동 롤백.

**batch 컨테이너 헬스체크(KB-380) 처음 적용 순서** — 태스크 정의는 `ignore_changes = [container_definitions]` 라 일반 apply 로는 리비전이 안 생긴다. CI 는 최신 리비전을 복제해 이미지만 바꾸므로 **terraform 으로 리비전을 먼저 등록한 뒤 CI 를 태운다**:

1. **머지 전** `terraform apply -var-file=<env>.tfvars -replace=module.ecs_environment.aws_ecs_task_definition.batch` — 헬스체크가 담긴 새 리비전 등록. 서비스는 `ignore_changes = [task_definition]` 이라 그대로(실행 중 태스크 무영향)
2. PR 머지 → CI(`deploy-batch.sh`)가 그 리비전을 복제해 actuator 포함 이미지로 배포 → 헬스체크 승계. `aws ecs describe-tasks … --query 'tasks[].containers[].healthStatus'` 가 `HEALTHY` 면 끝

1 과 2 사이에 actuator 없는 구 이미지로 CI 를 돌리면 헬스체크 실패로 롤백되므로 창을 짧게 가져간다.

## 롤링 → 카나리·인스턴스 1대 전환 절차 (2026-11-03, 1회성)

2026-11-01~03 의 ECS 롤링(api 2대, 태스크 3,000)에서 CodeDeploy 카나리(api 1대, 태스크 1,750 × 2 공존)로 되돌리는 apply 는 **서비스 재생성을 동반해 몇 분 다운**된다(컨트롤러 변경은 제자리 수정 불가). dev → prod, prod 는 트래픽 적은 시간에.

1. **선행**: Alloy 제거(#544)가 적용돼 있어야 1,750 × 2 가 인스턴스에 들어간다(등록 3,579). `aws ecs list-services` 에 `-alloy` 가 없는지 확인.
2. **tfvars**: `api_instance_count = 1`·`api_desired_count = 1`·`api_task_memory = 1750`(모듈 기본값과 같음 — 명시했다면 맞춘다). `api_instance_max_count` 는 기본 1.
3. **apply — 서비스는 `-replace`**:
   ```bash
   terraform workspace select <env>
   terraform apply -var-file=<env>.tfvars -replace=module.ecs_environment.aws_ecs_service.api
   ```
   순서상 옛 서비스가 먼저 지워지고(다운 시작) blue/green 타깃그룹·CodeDeploy 앱이 생긴다. **단일 타깃그룹 `kbap-<env>-ecs-api` 삭제가 "in use by a listener" 로 실패하면** 리스너 443 기본 액션을 `kbap-<env>-ecs-api-blue` 로 `aws elbv2 modify-listener` 한 뒤 다시 apply(2026-11-03 반대 방향 전환 때 같은 함정). 서비스가 생기면 블루 태스크가 뜨고 ALB 가 붙는다.
4. **인스턴스 축소**: ASG min/max 가 1 이 돼 한 대가 종료된다(ECS 매니지드 드레인). 태스크 정의 메모리가 1,750 으로 바뀌는 건 리비전 교체이므로 서비스는 `ignore_changes` — 첫 CodeDeploy 배포가 새 리비전을 올린다.
5. **워크플로와 순서**: 이 커밋의 `deploy-<env>.yml`(CodeDeploy)은 ECS 컨트롤러 서비스에 돌면 앱이 없어 실패하고, 롤링 워크플로는 CODE_DEPLOY 서비스를 `update-service` 하지 못해 실패한다. **apply 직후 같은 커밋을 workflow_dispatch 로 배포**해 같은 날 맞춘다. 그 첫 배포가 1,750 리비전을 올리는 배포다.
6. 확인: `aws deploy get-deployment` Succeeded, 타깃그룹 blue(또는 green) healthy 1, `aws autoscaling describe-auto-scaling-groups` api 풀 1대, 태스크 메모리 1,750.

## 벡터 검색·적재 켜기 (S3 Vectors, KB-319/KB-328)

벡터 버킷 `kbap-<env>-ecs-vectors` + 인덱스 `foods`(cosine·256·`longDescription` non-filterable)와 태스크 롤 권한은 `vectors.tf` 가 **항상** 만든다(서버리스 — 상시 비용 0, 시크릿 없음). 앱은 기본 꺼져 있다 — 배치 로그에 `스케줄 대상 잡이 이 환경에 구성되지 않아 건너뜁니다 job=foodVectorSyncJob` 이 매시 찍히면 이 상태다. `vector_enabled = true` 하나로 api(유사 음식 검색)·batch(`foodVectorSyncJob`) 양쪽에 `VECTOR_ENABLED`·`EMBEDDING_ENABLED=true`·`VECTOR_BUCKET`·`VECTOR_INDEX` 가 붙는다.

1. `<env>.tfvars` 에 `vector_enabled = true` → 태스크 정의는 `ignore_changes = [container_definitions]` 라 리비전을 직접 갈아 끼운다: `terraform apply -var-file=<env>.tfvars -replace=module.ecs_environment.aws_ecs_task_definition.batch`(api 도 켜려면 `-replace=…aws_ecs_task_definition.api` 추가). 서비스는 그대로라 실행 중 태스크 무영향. 버킷·인덱스·IAM 은 이 apply 에서 함께 생긴다.
2. 재배포 — CI 가 최신 리비전을 복제해 env 를 승계한다: `deploy-batch-dev.yml` 을 `workflow_dispatch` 로 실행(image_tag 에 현재 태그를 넣으면 빌드 없이 롤링). api 는 `deploy-dev.yml` 동일.
3. 확인: 배치 로그에서 위 건너뜀 메시지 소멸 + 30분 정각 `스케줄 트리거 job=foodVectorSyncJob` → `attempted≥1, failed=0`(`attempted=0` 은 빈 성공, `AccessDenied` 면 태스크 롤 권한). 기존 음식 적재는 `/admin/foods` 의 "미적재 음식 벡터 적재" 버튼으로 아웃박스를 채운 뒤 잡이 소화한다. 데이터 확인: `aws s3vectors get-vectors --vector-bucket-name kbap-<env>-ecs-vectors --index-name foods --keys <foodId> --return-metadata`.
4. 인덱스는 `prevent_destroy` — 차원·거리·non-filterable 키를 바꾸려면 재생성이 필요하므로 lifecycle 을 풀고 전량 재적재(관리자 "미적재 음식 벡터 적재")를 계획한다.

**부팅 시 잡 자동 실행은 항상 off** — 배치는 상시 기동이고 잡은 스케줄러·`POST /internal/batch/jobs` 트리거로만 돈다(`application.yml` 이 `spring.batch.job.enabled=false` 고정). 태스크 env 에 `SPRING_BATCH_JOB_ENABLED=true` 를 넣으면 잡이 2개 이상인 순간 `Job name must be specified in case of multiple jobs` 로 부팅이 실패한다(2026-08-31 dev 벡터 잡 활성화 때 실제로 밟음 — 서킷브레이커가 롤백).

## 배치 잡 원격 실행 (ECS Exec)

배치 잡 트리거(`POST /internal/batch/jobs`)는 클러스터 내부에서만 열려 있고 인증이 없다. 클러스터 밖(홈서버 젠킨스·운영자 PC)에서는 **ECS Exec** 로 배치 컨테이너 안에서 `curl localhost:8080` 을 실행한다 — 컨테이너가 SSM 채널을 아웃바운드로 열어 두므로 **인바운드 포트 개방 0, 추가 비용 0**, 접근 통제는 IAM 이 담당한다.

**전제 (호출 호스트)**

- AWS CLI v2 + Session Manager plugin: `brew install --cask session-manager-plugin`
- 환경별 운영 자격증명 프로필 `kbap-<env>-batch-operator` — IAM 사용자는 Terraform 이 만들고(`terraform output batch_operator_user_name`), **액세스 키는 콘솔에서 발급**해 `aws configure --profile kbap-dev-batch-operator` 로 등록한다. dev 키로는 prod 클러스터·api 컨테이너에 접근할 수 없다(정책이 클러스터·`batch` 컨테이너로 한정).
- 배치 서비스에 Exec 가 적용된 태스크가 떠 있어야 한다 — **Terraform apply 후 배치를 한 번 재배포**해야 새 태스크에 에이전트가 주입된다:
  ```bash
  aws ecs update-service --cluster kbap-dev-ecs-cluster --service kbap-dev-ecs-batch --force-new-deployment --profile kbap-infra
  aws ecs describe-tasks --cluster kbap-dev-ecs-cluster --tasks <task-arn> --profile kbap-dev-batch-operator \
    --query 'tasks[0].containers[0].managedAgents[?name==`ExecuteCommandAgent`].lastStatus'   # RUNNING
  ```

**실행·조회** — 실행 중 배치 태스크를 찾아 컨테이너 안에서 트리거 HTTP 를 호출한다(래퍼 스크립트는 레포에 두지 않는다 — 호출 호스트의 젠킨스/셸에서 아래를 그대로 쓴다):

```bash
export AWS_PROFILE=kbap-dev-batch-operator AWS_REGION=ap-northeast-2
TASK=$(aws ecs list-tasks --cluster kbap-dev-ecs-cluster --service-name kbap-dev-ecs-batch \
  --desired-status RUNNING --query 'taskArns[0]' --output text)

# 실행 — 202 → {"jobName","executionId","status":"STARTED",...} / 404 잡 없음 / 409 이미 실행 중
aws ecs execute-command --cluster kbap-dev-ecs-cluster --task "$TASK" --container batch --interactive \
  --command "curl -s -w '\n%{http_code}' -X POST 'http://localhost:8080/internal/batch/jobs?jobName=<jobName>'"

# 조회 — 200 → status COMPLETED / FAILED / STARTED
aws ecs execute-command --cluster kbap-dev-ecs-cluster --task "$TASK" --container batch --interactive \
  --command "curl -s -w '\n%{http_code}' 'http://localhost:8080/internal/batch/executions/<executionId>'"
```

`execute-command` 출력은 세션 시작/종료 안내가 앞뒤로 붙는다 — 마지막 3자리 숫자 줄이 HTTP 코드, 그 앞의 `{` 로 시작하는 줄이 본문이다. 교차 환경 클러스터·`api` 컨테이너를 지정하면 `AccessDeniedException`, 태스크가 없으면(미기동·Exec 미적용) `list-tasks` 가 `None` 을 돌려준다.

실행 호출은 잡 완료를 기다리지 않는다(잡 실행 시간만큼 세션을 붙잡지 않기 위해). 젠킨스는 실행 응답의 `executionId` 를 파싱해 조회를 30초 간격으로 폴링하고, `FAILED` 면 빌드를 실패시킨다.

정기 실행은 배치 앱의 인앱 스케줄러가 하며, 이 경로는 수동·임시 실행용이다. 배치 트리거 포트 SG 규칙은 그대로다 — 인스턴스 IP:8080 은 인터넷에서 계속 닿지 않는다.

적용 상태: dev 부터 적용·검증(절차 `specs/kb-374-batch-ecs-exec/quickstart.md`) 후 prod 는 잡 미실행 시간대에 같은 절차로 — 배치 재배포가 잠깐 다운을 동반한다.

## 관측 — 앱이 OTLP 로 직접 푸시 (2026-11-03, Alloy 대체)

api·batch 가 Micrometer OTLP 레지스트리로 15초마다 홈서버 Prometheus 의 OTLP 수신 엔드포인트(`/api/v1/otlp/v1/metrics`, `--web.enable-otlp-receiver`)에 직접 보낸다. 인스턴스에 사이드카가 없다 — 2026-11-01 까지 쓰던 Alloy DAEMON(KB-381)은 태스크 단위로 384 MiB 를 선점해 제거했다. 호스트 자원 지표(CPU·메모리·디스크)는 더 이상 Prometheus 로 가지 않는다 — Grafana 의 CloudWatch 데이터소스(EC2·ECS 기본 지표)로 본다.

**필요 입력** (SSM, 실행 롤 정책이 `/kbap/<env>/*` 라 IAM 변경 없음)

| 이름 | 타입 | 값 |
|---|---|---|
| `/kbap/<env>/OTLP_METRICS_URL` | String | `https://<host>/api/v1/otlp/v1/metrics` (remote_write 때와 같은 호스트) |
| `/kbap/<env>/CF_ACCESS_CLIENT_ID` · `CF_ACCESS_CLIENT_SECRET` | SecureString | Cloudflare Access 서비스 토큰(기존 값 그대로) |

앱 쪽 스위치는 태스크 env `OTLP_METRICS_ENABLED=true`(모듈이 넣는다). 로컬·테스트는 기본 꺼짐.

**라벨 규약** (Prometheus OTLP 수신기가 리소스 속성을 라벨로 올린다 — `prometheus.yml` 의 `otlp.promote_resource_attributes` 에 `env`·`service.instance.id`·`service.version` 이 있어야 한다)

| 라벨 | 값 | 출처 |
|---|---|---|
| `job` | `kbap-api` \| `kbap-batch` | `service.name` = `spring.application.name` |
| `instance` | `<env>-<api\|batch>-<컨테이너 id 12자>` | `service.instance.id` — **태스크 단위**(카나리·롤링 중 구·신 태스크 구분) |
| `env` | `dev` \| `prod` | 리소스 속성 `env` |
| `service_version` | 이미지 태그(git sha) | 리소스 속성 `service.version` (= SENTRY_RELEASE). 옛 `version`(태스크 정의 리비전)·`host` 라벨은 없다 |
| `application` | `kbap-api` \| `kbap-batch` | 앱(Micrometer 태그) |

메트릭 이름은 `base-time-unit: seconds` 라 Prometheus 스크랩 때와 같다(`http_server_requests_seconds_bucket`, `jvm_memory_used_bytes`, `…_total`). 스크랩이 아니라 `up` 은 없다 — 수신 확인은 `process_uptime_seconds{env="dev"}` 처럼 앱 지표로 한다.

**알아둘 것**
- 403 은 유실이다 — Access 토큰이 틀리면 앱 로그에 `OtlpMeterRegistry … 403` 경고가 15초마다 찍히고 앱은 정상 동작한다.
- 수신 주소 변경은 SSM 값 교체 → 태스크 재기동(`update-service --force-new-deployment`).
## api 스케일 아웃 — 서비스 CPU 80% + capacity provider

**목표**: 서비스 평균 CPU 가 80% 를 넘으면 "인스턴스 1대 + 컨테이너 1개"를 한 단위로 늘린다. 평시 인스턴스당 api 태스크 1개(인스턴스 자원 전부).

- **태스크**: `api-autoscaling.tf` — `ECSServiceAverageCPUUtilization` 80% 타깃 트래킹(`api_desired_count`~`api_max_count`). 태스크 CPU 예약이 없으므로 분모는 인스턴스 CPU 다.
- **인스턴스**: `cluster.tf` — api ASG(`api_instance_count`~`api_instance_max_count`) 를 capacity provider `kbap-<env>-ecs-api` 가 조정한다. `target_capacity = 100` — 빈 인스턴스를 두지 않고, 태스크를 놓을 자리가 없을 때만 인스턴스를 늘린다. 50 은 "인스턴스마다 절반"이 아니라 "빈 인스턴스를 같은 수만큼 더"라서 쓰지 않는다(dev 에서 2 → 4대 확인). batch 풀은 고정.
- **배포와의 결합**: 서비스가 `capacity_provider_strategy` 로 직접 묶여 있다(ECS 컨트롤러라 Terraform 이 소유). 롤링(max 100%)은 desired 를 넘지 않아 배포 중 인스턴스가 늘지 않는다.
- **이력**: #240(40%, 인스턴스 고정) → 2026-09-07 prod 카나리 롤백(그린 JVM 부팅 버스트가 40% 알람 → desired 3 → 자리 부족) → 인프라에서만 제거(#244, 미병합 종료) → 80% + capacity provider + scale-out cooldown 300s 로 재도입.

**처음 적용 순서**: ① `terraform apply` ② 기존 api 인스턴스에 scale-in 보호를 건다(ASG 플래그는 신규 인스턴스에만 적용) — `aws autoscaling set-instance-protection --auto-scaling-group-name kbap-<env>-ecs-api-asg --instance-ids <ids> --protected-from-scale-in` ③ api 재배포(`deploy-<env>.yml` workflow_dispatch) ④ `aws ecs describe-services … --query 'services[].taskSets[].capacityProviderStrategy'` 로 확인.

## 알아둘 것

- **NAT 게이트웨이가 없다** — 인스턴스는 퍼블릭 서브넷 + 퍼블릭 IP 로 ECR·SSM 에 나간다. 인바운드는 ALB 보안그룹에서만 열려 있다.
- 인스턴스 사이징: **api 인스턴스 1대에 태스크 2개까지**(카나리 중 구·신 공존). 태스크 상한 = ECS 등록 메모리(이미 `ECS_RESERVED_MEMORY=256` 을 뺀 값 — t4g.medium ≈3,579, t4g.small 1,590) ÷ 2 → 1,750. api 는 기동만으로 1.2 GiB 를 넘고 prod 실사용 최대가 1.5 GiB 라 t4g.small 에 못 들어간다(2026-11-03 실측). **상한을 넘기면 그린 태스크가 PROVISIONING 에 걸려 배포가 멈춘다**(`aws ecs stop-task` 로 치운다).
- **tfvars 가 태스크 정의 env 의 단일 출처다.** 리비전에 콘솔로 env 를 넣어 두면 Terraform 이 태스크 정의를 다시 만들 때(`-replace`, `runtime_platform`·메모리 변경) 사라진다 — 토글은 `api_extra_env`·`batch_extra_env`, 시크릿은 `api_secret_names`·`batch_secret_names` 로 넣는다(2026-11-01 REVIEW_BOT_*·ADS_REWARD_UNIT_IDS 가 그렇게 들어가 있던 것을 tfvars 로 옮김).
  - 메뉴판 패스트패스(Jev, KB-1017): `api_extra_env` 의 `PROCESSED_FOOD_MENU_FAST_PATH_ENABLED`·`_MEMBER_IDS`·`_ROLLOUT_PERCENT`(확대는 MEMBER_IDS → ROLLOUT_PERCENT), 키 `JEV_API_KEY` 는 모듈 기본 `api_secret_names` — `/kbap/<env>/JEV_API_KEY` SSM 이 먼저 있어야 태스크가 뜬다.
- **런치 템플릿이 바뀌는 apply 는 EC2 를 롤링한다.** ASG 가 템플릿 버전을 숫자로 참조해 instance refresh 가 자동으로 걸린다(api min healthy 50%, batch 는 1대라 몇 분 다운). 추천 AMI(SSM 파라미터)가 갱신된 뒤에는 무관한 apply 에서도 걸린다 — plan 에 `launch_template.version` 변경이 보이면 그 뜻이고, 지금 롤링하면 안 되면 그 apply 를 미룬다.
- dev 인스턴스는 Spot — 회수되면 `ECS_ENABLE_SPOT_INSTANCE_DRAINING` 으로 2분 전 드레인되고 ASG 가 새로 띄운다(몇 분 1대). 부하 테스트 중 수치가 튀면 ASG 활동 이력에서 회수 시각을 먼저 확인한다.
- 시크릿 4 KB 초과(Firebase JSON 이 큰 경우)는 SSM Advanced tier 로 파라미터를 바꾼다.
- **batch 헬스체크 이후 구 이미지(actuator 없음) 재배포는 실패한다** — 새 리비전이 `/actuator/health/readiness` 를 치므로 서킷브레이커가 롤백한다. 되돌려야 하면 `batch.tf` 의 `healthCheck` 를 지우고 `-replace` 로 리비전을 먼저 갱신한다.
- **`/actuator/**`(prometheus·health)는 api 서비스 포트(8080)에 그대로 열려 있고 앱은 접근 제어를 하지 않는다.** 공개 차단은 ALB 리스너 규칙으로 후속 처리한다 — `/actuator/*` 차단 목록은 `//actuator/…`·`/%61ctuator/…` 처럼 ALB(raw 경로 매칭)와 Tomcat(정규화·디코딩 후 라우팅)이 다르게 보는 경로를 놓치므로 **`/api/*` 만 forward 하는 허용 목록**으로 만든다. ALB 헬스체크는 리스너 규칙을 거치지 않아 `/actuator/health/readiness` 는 계속 통과한다(specs/kb-380 research R-3).
- prod 는 새 스키마 `kbap-prod` 를 쓴다(운영 `/kbap` 무접촉). apply 전에 `CREATE DATABASE `kbap-prod`` 를 해 두면 Flyway 가 첫 기동 때 스키마를 채운다 — 즉 **prod-ecs 는 빈 데이터로 시작**하며, 운영 데이터 이전은 별도 작업이다.
- 기존 prod ECS 를 지우기 전까지 `kbap-prod-api` 태스크 정의 패밀리와 이름이 겹치지 않는다(새 이름은 `kbap-prod-ecs-*`).
