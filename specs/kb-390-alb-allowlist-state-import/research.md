# Research: state 복구·prod Alloy·공개 진입점 차단 (KB-390)

## R-1. 공유 장부 — S3 백엔드 + 잠금 + workspace

- **Decision**: `versions.tf` 주석 해제 → `backend "s3" { bucket = "kbap-terraform-state", key = "ecs/terraform.tfstate", region = "ap-northeast-2", profile = "kbap-infra", use_lockfile = true, encrypt = true }`. 환경은 **terraform workspace `dev` / `prod`** 로 분리(S3 객체는 `env:/dev/ecs/terraform.tfstate`, `env:/prod/ecs/…`). 버킷은 버저닝 on·퍼블릭 차단·SSE-S3, terraform 밖에서 1회 생성.
- **Rationale**: `use_lockfile` 은 1.10+ 에서 DynamoDB 없이 같은 버킷의 `.tflock` 으로 잠금(현재 1.15.8). 버저닝이 잘못된 apply 전 state 로 되돌리는 수단. workspace 를 쓰면 `workspace select` 만으로 환경 전환 — 종전 주석의 "key 로 분리(`-backend-config`)" 는 init 을 매번 다시 해야 해서 실수 유발. 백엔드 블록은 변수 불가라 `profile` 을 고정.
- **Alternatives**: git 에 state — 비밀 평문·잠금 없음·병합 불가, 기각. 로컬 유지 — 이번 사고 재발, 기각. DynamoDB 잠금 — lockfile 로 대체됨.

## R-2. dev 장부 — import 가 아니라 이관(state pull/push)

- **Decision**: 맥미니 `workspace dev-ecs` 에서 `terraform state pull > dev-ecs.tfstate` → 맥북(S3 백엔드 init 후) `terraform workspace new dev && terraform state push dev-ecs.tfstate` → `plan -var-file=dev.tfvars` 가 0 change 인지 확인. 맥미니는 코드 머지 후 `terraform init -reconfigure` 로 S3 를 바라보게 하고 로컬 `terraform.tfstate.d/` 는 `_local-state-archive/` 로 이동(삭제 안 함).
- **Rationale**: `init -migrate-state` 는 로컬 workspace 전부(EKS 유산 `prod`·default 포함)를 옮겨 S3 에 쓰레기가 남는다. pull/push 는 필요한 것만 정확한 이름으로. 사용자 지시 "dev 도 import" 는 결과(맥북에서 dev 관리)가 같고 위험이 훨씬 작은 이관으로 대체 — spec Assumptions 에 기록.

## R-3. prod 장부 — `import` 블록 생성 스크립트 + 1회 apply

- **Decision**: `iac/scripts/gen-import-blocks.sh <env>` 가 AWS 를 조회해 `iac/terraform/import.<env>.tf`(gitignore) 를 만든다 — 모듈의 리소스 49개(alloy 3개 제외, for_each 포함) 각각 `import { to = module.ecs_environment.<addr>  id = "<id>" }`. `terraform plan -var-file=prod.tfvars` 가 **"49 to import, 3 to add(alloy), 0 change, 0 destroy"** 일 때만 apply. apply 후 `import.prod.tf` 삭제.
- **Rationale**: terraform 1.5+ `import` 블록은 plan 에서 import 결과와 드리프트를 **함께** 보여 준다(CLI `terraform import` 는 하나씩 즉시 state 에 쓰고 되돌리기 어려움). 이름 규칙이 `kbap-prod-ecs-*` 로 일정해 id 조회가 스크립트로 된다.
- **id 형식(contracts/import-ids.md)**: IAM 롤·프로파일·유저·로그그룹·클러스터·대시보드·알람·배포설정·배포그룹(`app:group`)·롤정책(`role:name`)·유저정책(`user:name`)·정책부착(`role/arn`) 은 이름 기반. ALB·리스너·TG·태스크정의는 ARN. SG·SG 규칙(`sgr-…`)·런치템플릿(`lt-…`)·인스턴스(`i-…`) 는 id 조회. ECS 서비스는 `cluster/service`. ASG 는 이름.
- **주의**: 태스크 정의는 **현재 ACTIVE 최신 리비전 ARN** 으로 import — CI 가 만든 리비전이라 코드(초기 이미지)와 다르지만 `ignore_changes=[container_definitions]` 라 diff 없음. 서비스의 `task_definition`·`load_balancer`(CodeDeploy 가 바꿈)도 ignore 대상. 리스너 `default_action` 도 ignore.

## R-4. 드리프트 처리 원칙

- **Decision**: import plan 에서 0 change 가 아니면 apply 하지 않는다. 항목별로 (a) `default_tags`/태그 차이 → 무해, 코드를 실제에 맞추거나 `lifecycle ignore` 없이 그대로 두고 apply 로 태그만 맞춤(리소스 교체 아님 확인), (b) 런치 템플릿 `user_data`/AMI 차이 → **`$Latest` 참조라 새 버전 생성 = 다음 instance refresh 트리거 가능성** → apply 전 `instance_refresh` 동작 확인, 필요하면 코드 값을 실제로 맞춤, (c) **replace(-/+) 가 하나라도 있으면 중단** 후 보고. SC-002(재생성 0) 가 게이트.

## R-5. `prod.tfvars` 복원 — AWS 실값에서

| 변수 | 출처(AWS 조회) |
|---|---|
| `vpc_name`·`subdomain`(`prod-ecs`)·`hosted_zone_name`·`spring_profile`·`storage_*`·`cdn_base_url`·`image_public_base_url`·`food_content_queue_name`·`db_url`·`db_username`·`redis_host` | api/batch 태스크 정의 `environment` + `prod.tfvars.example` |
| `rds_security_group_id`·`redis_security_group_id` | SG 규칙 `rds_from_instance`/`redis_from_instance` 의 group-id |
| `admin_cidr` | bastion SG 인바운드 22 의 cidr |
| `bastion_key_name` | bastion 인스턴스 `KeyName` |
| `api_image`·`batch_image` | 현재 태스크 정의 image(ignored 이지만 값 기록) |
| `api_instance_count`·`batch_instance_count`·`api_desired_count`·`batch_desired_count` | ASG desired / 서비스 desired |
| `log_retention_days`·`canary_*`·`blue_termination_wait_minutes` | 로그 그룹 retention / 배포 설정 이름·배포 그룹 설정 |
| `home_prometheus_remote_write_url` | `https://prom-write.handev.site/api/v1/write` |

스크립트 `gen-import-blocks.sh` 가 같은 조회로 `prod.tfvars.generated` 초안도 내놓는다. README 에 "예시 대비 다른 값" 표로 기록(FR-004) — 값 자체(IP·SG id)는 README 가 아니라 **위키/맥북 로컬**에.

## R-6. 공개 진입점 차단 — 리스너 **거부 규칙**(fixed-response 404), 허용 목록 아님

- **Decision**: HTTPS 리스너에 `aws_lb_listener_rule` 1개(priority 10): 조건 `path-pattern` = `["*actuator*"]` (+ prod 는 `"*swagger*"`, `"*api-docs*"`) → `fixed-response 404`. 기본 액션(forward, CodeDeploy 소유)은 그대로. 패턴은 변수 `blocked_path_patterns`(env 별 tfvars).
- **Rationale — 허용 목록을 못 쓰는 이유**: CodeDeploy ECS blue/green 은 `prod_traffic_route.listener_arns` 로 지정된 리스너의 **기본 액션만** blue↔green 으로 바꾼다. "허용 경로 → forward TG" 규칙을 따로 두면 그 규칙은 전환 뒤에도 blue 를 가리켜 카나리가 깨진다. 거부 규칙은 forward 를 하지 않으니 전환과 무관.
- **경로 변형**: ALB `path-pattern` 의 `*` 는 `/` 를 포함한 임의 문자열이라 `*actuator*` 가 `//actuator/…`·`/./actuator`·`/api/../actuator` 를 전부 잡는다(Tomcat 정규화 전 원문에 "actuator" 가 그대로 있음). **퍼센트 인코딩(`/%61ctuator`)은 ALB 가 매칭 전에 디코딩하는지 문서로 확정하지 못했다** → dev 적용 후 curl 로 실측(quickstart). 통과하면 끝, 새면 **WAF Web ACL**(URL_DECODE + NORMALIZE_PATH 변환 후 regex 차단, 월 ~$7/ALB)로 승격 — 이 결정 지점을 tasks 에 둔다.
- **부수 효과**: 경로에 "actuator" 가 들어간 정상 경로는 없다(코드 확인). prod 의 `*swagger*`·`*api-docs*` 도 동일. 헬스체크(리스너 규칙 미경유)·Alloy(docker 네트워크) 무영향.
- **Alternatives**: 앱 필터 — 사용자 기각(KB-380). WAF 먼저 — 비용, 실측 후 필요 시.

## R-7. prod Alloy

- **Decision**: R-3 의 import apply 가 곧 prod Alloy 적용(코드에 이미 있음 → "3 to add"). 별도 작업 없음. 검증은 `up{env="prod", job="prometheus.scrape.ecs_apps"}` = 2, 호스트 = prod 인스턴스 수.

## R-8. 맥북 자격증명 — `kbap-infra` 프로필

- **Decision**: 맥북에 `kbap-infra` 프로필(terraform 용 IAM 사용자 액세스 키)을 등록한다 — 코드 기본값 `aws_profile = "kbap-infra"`, 백엔드 `profile` 도 같은 값. `kbap-prod-deployer` 는 S3 state·IAM·SG 조회 권한이 없어 불가.
- **Rationale**: 맥미니에는 이 프로필이 있을 것(거기서 apply 했으니). 키를 옮기거나 새 키를 발급한다(콘솔). 사용자 준비물.

## R-9. 검증 — 테스트 코드 없음

- **Decision**: plan 출력(숫자 판정)·`terraform state list` 개수·curl·Grafana·카나리 1회. 스크립트(`gen-import-blocks.sh`)는 `bash -n` + dev 에 대해 dry-run(기존 state 와 id 비교) 으로 점검 — dev 는 이미 state 가 있으니 스크립트가 낸 id 가 `terraform state show` 의 id 와 일치하는지 대조하면 스크립트 정확도가 검증된다.
