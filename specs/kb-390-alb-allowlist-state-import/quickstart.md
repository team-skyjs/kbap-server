# Quickstart: state 복구 → prod Alloy → 공개 차단 (KB-390)

## 0. 사용자 준비물

1. **맥북에 `kbap-infra` 프로필** — 맥미니의 `~/.aws/credentials` `[kbap-infra]` 를 옮기거나 콘솔에서 그 IAM 사용자 액세스 키 재발급. 확인: `aws sts get-caller-identity --profile kbap-infra` → 계정 118178010621.
2. **state 버킷** — contracts/backend-and-alb-rule.md §1 의 명령 3개(생성·버저닝·퍼블릭 차단).
3. 맥미니 세션에 dev 장부 내보내기 지시(§2-a).

## 1. 백엔드 전환 코드 (맥북, 이 브랜치)

`versions.tf` 백엔드 주석 해제 → `terraform init`(로컬 state 없으니 "Successfully configured the backend") → `terraform workspace list` 가 `default` 만.

## 2. dev 장부 이관

a. **맥미니**:
```bash
cd <kbap>/iac/terraform && git checkout develop && git pull
terraform workspace select dev-ecs && terraform state pull > ~/dev-ecs.tfstate && terraform state list | wc -l   # 49 (alloy 포함 52)
# ~/dev-ecs.tfstate 를 맥북으로(scp / AirDrop). 비밀 포함 — 채팅에 붙이지 말 것
```
b. **맥북**:
```bash
terraform workspace new dev
terraform state push ~/dev-ecs.tfstate
terraform state list | wc -l                       # 맥미니와 같은 수
terraform plan -var-file=dev.tfvars                # dev.tfvars 는 맥미니 것을 같이 복사 → 기대 "No changes"
```
c. 맥미니: `git pull`(백엔드 코드 머지 후) → `mv terraform.tfstate.d _local-state-archive && mv terraform.tfstate* _local-state-archive/` → `terraform init -reconfigure` → `workspace select dev` → `plan` "No changes".

## 3. prod 장부 복구 + Alloy (맥북)

```bash
terraform workspace new prod
iac/scripts/gen-import-blocks.sh dev  --check       # dev state 와 id 전수 대조 → 스크립트 신뢰
iac/scripts/gen-import-blocks.sh prod               # → iac/terraform/import.prod.tf + prod.tfvars.generated
cp prod.tfvars.generated prod.tfvars                # 검토 후
terraform plan -var-file=prod.tfvars -out=prod-import.tfplan
```
기대: **`49 to import, 3 to add, 0 to change, 0 to destroy`** — add 는 alloy 3개. change/destroy/replace 가 있으면 apply 금지 → research R-4 로 분류.
```bash
terraform apply prod-import.tfplan
rm import.prod.tf
terraform plan -var-file=prod.tfvars                # "No changes"
```
확인: `aws ecs describe-services --cluster kbap-prod-ecs-cluster --services kbap-prod-ecs-alloy --profile kbap-prod-deployer --region ap-northeast-2 --query 'services[0].runningCount'` = prod 인스턴스 수; Grafana `up{env="prod", job="prometheus.scrape.ecs_apps"}` = 2, `count by (host)(node_memory_MemAvailable_bytes{env="prod"})` = 인스턴스 수; `aws logs tail /kbap/prod/alloy --since 5m | grep -iE "403|error" | grep -v udev` 비어 있음.

## 4. 공개 차단 규칙 (dev → prod)

```bash
terraform workspace select dev && terraform plan -var-file=dev.tfvars      # 기대 1 to add (listener rule)
terraform apply -var-file=dev.tfvars
for p in /actuator/prometheus //actuator/prometheus /api/../actuator/prometheus /%61ctuator/prometheus; do
  printf '%-40s ' "$p"; curl -s -o /dev/null -w '%{http_code}\n' "https://dev.kbap.site$p"; done
for p in /api/app-version /admin/login /swagger-ui/index.html; do printf '%-30s ' "$p"; curl -s -o /dev/null -w '%{http_code}\n' "https://dev.kbap.site$p"; done
```
- 앞 4개 전부 404 → 통과. `/%61ctuator` 만 200 → **WAF 승격 결정 지점**(R-6): 사용자에게 보고 후 진행.
- 카나리 1회(deploy-dev workflow_dispatch) → 전환 후 `curl https://dev.kbap.site/api/app-version` 200, Grafana `version` 증가.
- prod: `prod.tfvars` 의 `blocked_path_patterns = ["*actuator*","*swagger*","*api-docs*"]` → `workspace select prod` → plan 1 add → apply → 같은 curl (swagger 는 404 기대).

## 5. 되돌리기

- 규칙: `terraform destroy -target=module.ecs_environment.aws_lb_listener_rule.block_paths`.
- 백엔드: S3 객체 버저닝으로 이전 state 복원. 맥미니 `_local-state-archive/` 는 최소 한 달 보관 후 삭제.
