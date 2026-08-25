# kbap ECS 환경 (Terraform)

dev·prod 를 **같은 모듈(`modules/ecs-environment`) + 환경별 tfvars** 로 세운다. 기존 VPC·RDS·Redis·S3·SQS 는 재사용하고, 그 위에 ECS 클러스터·ALB·CodeDeploy 카나리·CloudWatch 를 새로 만든다. **현재 운영 중인 `kbap-prod-cluster`(ECS)·`kbap-devstg-alb` 는 건드리지 않는다** — 이 구성은 `dev-ecs.kbap.site` / `prod-ecs.kbap.site` 로 별도 ALB 를 받는다.

## 구성 요약

| 항목 | 값 |
|---|---|
| 컨테이너 인스턴스 | t3.medium × 3 — api 2대(AZ 분산) + batch 1대. ECS 인스턴스 속성 `workload=api|batch` 로 배치 분리 |
| api 태스크 | bridge 모드·동적 호스트 포트, 512 cpu / 1536 MiB, desired 2, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70` |
| batch 태스크 | 단일 태스크, 잡 트리거 HTTP 는 배치 인스턴스 8080 고정(클러스터 내부만) |
| 배포 | **CodeDeploy 블루/그린 카나리** — 20% 트래픽 15분 → 100% → 구버전 15분 유지 후 종료. 5xx 알람 시 자동 롤백 |
| 진입 | ALB(80→443 리다이렉트, `*.kbap.site` ACM) + Route53 alias `<subdomain>.kbap.site` |
| 로그 | CloudWatch `/kbap/<env>/api`·`/kbap/<env>/batch`, **보관 7일**, Container Insights, 대시보드 `kbap-<env>-ecs` |
| 시크릿 | SSM SecureString `/kbap/<env>/<NAME>` → 태스크 정의 `secrets` 로 주입 (값은 Terraform 밖) |
| AWS 권한 | 태스크 롤(api: S3 접두사 한정, batch: SQS+S3) — 액세스 키를 env 에 넣지 않는다 |

## 소유권 경계

| 대상 | 소유자 |
|---|---|
| 클러스터·ASG·ALB·타깃그룹·CodeDeploy·IAM·로그그룹·대시보드 | Terraform |
| 태스크 정의 **리비전**(이미지 태그)·리스너의 blue/green 포워딩·서비스 desired | 배포 스크립트 / CodeDeploy (`lifecycle.ignore_changes`) |
| SSM 파라미터(이름·값 모두) | 사람/CI (`aws ssm put-parameter`) — Terraform 은 ARN 문자열만 참조 |
| RDS·Redis·VPC·S3·SQS·Route53 존·ACM | 기존 인프라 (data 로 조회, SG 인바운드 규칙만 추가) |

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
for k in DB_PASSWORD JWT_SECRET OPENAI_API_KEY GOOGLE_PLACES_API_KEY; do
  aws ssm put-parameter --profile kbap-infra --name /kbap/dev/$k --type SecureString --overwrite --value '...'
done
aws ssm put-parameter --profile kbap-infra --name /kbap/dev/FIREBASE_CREDENTIALS_JSON \
  --type SecureString --overwrite --value "$(cat firebase-service-account.json)"

```

확인: `https://dev-ecs.kbap.site/actuator/health` → CloudWatch 대시보드 `kbap-dev-ecs`.

prod 는 `prod.tfvars.example` 로 동일하게 — state 는 dev 와 분리한다(로컬이면 디렉터리 분리 또는 `-state=prod.tfstate`, S3 백엔드면 `key` 분리).

## 배포

**api — 카나리 (`iac/scripts/deploy-api.sh <env> <tag>`)**

1. 현재 태스크 정의에서 이미지만 바꿔 새 리비전 등록
2. CodeDeploy 배포 생성 → green 타깃그룹에 신버전 태스크 기동(인스턴스당 blue 1 + green 1)
3. **20% 트래픽을 green 으로 15분** — 대시보드 "정상 타깃 수 blue/green"·5xx 로 관찰. 5xx 알람 발화 시 자동 롤백
4. 100% 전환. 이후 **15분간 blue 태스크 유지** — 이 창에서 `aws deploy stop-deployment --auto-rollback-enabled` 하면 트래픽만 되돌아가 즉시 복구
5. 15분 뒤 blue 종료 → 완료

**batch — 롤링 (`iac/scripts/deploy-batch.sh <env> <tag>`)**: 단일 인스턴스·고정 포트라 구 태스크를 먼저 내리고 신 태스크를 올린다(잠깐 다운). 서킷브레이커로 기동 실패 시 자동 롤백.

## 카나리 파라미터 바꾸기

`canary_percentage`·`canary_interval_minutes`·`blue_termination_wait_minutes` (tfvars). 바꾸면 배포 구성(`aws_codedeploy_deployment_config`)이 새로 만들어진다.

## 알아둘 것

- **NAT 게이트웨이가 없다** — 인스턴스는 퍼블릭 서브넷 + 퍼블릭 IP 로 ECR·SSM 에 나간다. 인바운드는 ALB 보안그룹에서만 열려 있다.
- 인스턴스 사이징: 평상시 **api 인스턴스당 컨테이너 1개**(desired 2 / 인스턴스 2대 spread). 카나리 진행 중(최대 30분)에만 구버전 1 + 신버전 1 로 **인스턴스당 2개**가 잠깐 공존한다. t3.medium(4 GiB)에 `ECS_RESERVED_MEMORY=256` → 태스크 가용 ≈ 3.6 GiB 라 1536 × 2 = 3072 MiB 까지는 들어가지만, **태스크 메모리를 더 올리면 카나리 중 신버전이 배치되지 못해 배포가 멈춘다**.
- 시크릿 4 KB 초과(Firebase JSON 이 큰 경우)는 SSM Advanced tier 로 파라미터를 바꾼다.
- prod 는 새 스키마 `kbap-prod` 를 쓴다(운영 `/kbap` 무접촉). apply 전에 `CREATE DATABASE `kbap-prod`` 를 해 두면 Flyway 가 첫 기동 때 스키마를 채운다 — 즉 **prod-ecs 는 빈 데이터로 시작**하며, 운영 데이터 이전은 별도 작업이다.
- 기존 prod ECS 를 지우기 전까지 `kbap-prod-api` 태스크 정의 패밀리와 이름이 겹치지 않는다(새 이름은 `kbap-prod-ecs-*`).
