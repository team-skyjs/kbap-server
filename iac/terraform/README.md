# kbap 인프라 Terraform (EKS 중심)

`iac/terraform` 루트는 **EKS용 단일 진입점**으로 정리했다.
기존 ECS용 모듈(`modules/env`)은 레거시 참고용으로만 유지한다.

## 소유권 경계 (중요)

| 대상 | 소유자 |
|---|---|
| EKS 클러스터·노드그룹·ALB 연계 리소스·RDS·Redis·SG | **Terraform** |
| 앱 이미지 태그·시크릿 값(운영 배포값) | **배포 파이프라인 / 수동** |

## 실행

이미 생성된 클러스터를 변경할 때는 반드시 해당 클러스터를 생성한 Terraform state를 사용해야 한다. `terraform plan`이 EKS 클러스터와 노드그룹 전체를 신규 생성으로 표시하면 `apply`하지 않는다.

```bash
cd iac/terraform
terraform init
terraform plan  -var-file=terraform.auto.tfvars.example
terraform apply -var-file=terraform.auto.tfvars.example
```

prod를 같은 설정 기반으로 배포할 때는 예시 파일을 복사한 `terraform.prod.tfvars`를 사용한다.

```bash
cd iac/terraform
cp terraform.prod.tfvars.example terraform.prod.tfvars
# terraform.prod.tfvars의 CIDR과 기존 리소스 ID를 실제 값으로 교체
terraform init
terraform workspace select prod || terraform workspace new prod
terraform plan  -var-file=terraform.prod.tfvars
terraform apply -var-file=terraform.prod.tfvars
```

dev/prod를 동시에 운영할 때는 꼭 상태 분리(workspace)해서 동일 리소스를 공유하지 않도록 한다.

`terraform.auto.tfvars`를 직접 사용하려면 예시 파일을 복사한 뒤 값을 채워 넣어 실행한다.

```bash
cp terraform.auto.tfvars.example terraform.auto.tfvars
# terraform.auto.tfvars 작성
terraform plan -var-file=terraform.auto.tfvars
terraform apply -var-file=terraform.auto.tfvars
```

적용 후 확인:

```bash
terraform output
kubectl get nodes -L workload,node.kubernetes.io/instance-type
```

추가로 `api` IAM 연결 확인:

```bash
terraform output api_pod_identity_role_arn
terraform output api_pod_identity_association_arn
```

## CloudWatch 애플리케이션 로그

`amazon-cloudwatch-observability` 애드온의 OpenTelemetry Collector가 각 노드의 파드 stdout/stderr를 `/aws/otel/containerinsights/<cluster>/application`으로 수집한다. Fluent Bit, Application Signals, 기존 Container Insights는 비활성화한다. OTel의 `application`·`host`, 기존 `/aws/containerinsights`의 `application`·`host`·`dataplane`, EKS 컨트롤 플레인 로그는 모두 7일간 보관한다. 기존 `dataplane` 그룹은 Fluent Bit 비활성화 후 신규 로그가 적재되지 않는다.

```bash
aws eks describe-addon \
  --profile kbap-infra \
  --region ap-northeast-2 \
  --cluster-name kbap-dev-eks \
  --addon-name amazon-cloudwatch-observability \
  --query 'addon.{Status:status,Version:addonVersion}' \
  --output table

kubectl get pods -n amazon-cloudwatch -o wide

aws logs describe-log-groups \
  --profile kbap-infra \
  --region ap-northeast-2 \
  --log-group-name-prefix /aws/containerinsights/kbap-dev-eks \
  --query 'logGroups[].{Name:logGroupName,Retention:retentionInDays,StoredBytes:storedBytes}' \
  --output table
```

## 구조

```
iac/terraform/
├── main.tf                 # EKS 모듈 호출
├── versions.tf             # provider/backend
├── variables.tf            # EKS 입력 변수
├── modules/
│   ├── eks-environment/    # 현재 사용 모듈(모든 EKS 인프라)
│   └── env/                # ECS 레거시(참조용)
```

> `modules/env`를 통한 ECS 배포는 더 이상 현재 경로에서 실행되지 않음.
