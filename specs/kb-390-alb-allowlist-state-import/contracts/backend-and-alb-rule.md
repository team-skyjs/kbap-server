# Contract: S3 백엔드 · 거부 규칙 · 변수 (KB-390)

## 1. `iac/terraform/versions.tf` 백엔드

```hcl
backend "s3" {
  bucket       = "kbap-terraform-state"
  key          = "ecs/terraform.tfstate"
  region       = "ap-northeast-2"
  profile      = "kbap-infra"
  use_lockfile = true
  encrypt      = true
}
```
workspace `dev`·`prod` → 객체 `env:/<ws>/ecs/terraform.tfstate`. 버킷은 terraform 밖에서 생성:
```bash
aws s3api create-bucket --bucket kbap-terraform-state --region ap-northeast-2 --create-bucket-configuration LocationConstraint=ap-northeast-2 --profile kbap-infra
aws s3api put-bucket-versioning --bucket kbap-terraform-state --versioning-configuration Status=Enabled --profile kbap-infra
aws s3api put-public-access-block --bucket kbap-terraform-state --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true --profile kbap-infra
```

## 2. 거부 규칙 (`alb.tf`)

```hcl
resource "aws_lb_listener_rule" "block_paths" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 10
  action {
    type = "fixed-response"
    fixed_response { content_type = "text/plain" status_code = "404" }
  }
  condition {
    path_pattern { values = var.blocked_path_patterns }   # 최대 5개
  }
  tags = local.common_tags
}
```
- 기본 액션(forward)은 그대로 — CodeDeploy 가 blue↔green 전환 시 기본 액션만 바꾸므로 거부 규칙은 전환과 독립.
- 변수 `blocked_path_patterns`(list(string), 기본 `["*actuator*"]`), 루트 전달 + tfvars(prod 에 swagger·api-docs 추가).

## 3. 기대 응답 (dev 적용 후)

| 요청 | 기대 |
|---|---|
| `GET /actuator/prometheus` · `//actuator/prometheus` · `/api/../actuator/prometheus` | **404** (ALB fixed-response, 본문 없음) |
| `GET /%61ctuator/prometheus` | 404 면 완료 / **200 이면 WAF 승격**(R-6) |
| `GET /api/app-version` · `/admin/login` · `/swagger-ui/index.html`(dev) | 적용 전과 동일(200) |
| ALB 타깃그룹 헬스 | healthy 유지 |
| Grafana `up{env="dev"}` | 유지 |

## 4. 신규 변수

| 변수 | 위치 | 타입/기본 |
|---|---|---|
| `blocked_path_patterns` | 모듈·루트 | list(string), `["*actuator*"]` |
