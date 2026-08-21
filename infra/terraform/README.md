# kbap 인프라 Terraform

prod 토폴로지(ECS on EC2 + ALB blue/green + RDS MySQL + ElastiCache Redis)를 환경 변수화한 모듈로, dev 환경을 코드로 띄운다. prod 는 추후 같은 모듈로 `import` 해 소급 코드화한다.

## 소유권 경계 (중요)

| 대상 | 소유자 |
|---|---|
| 클러스터·ASG·ALB·TG·RDS·Redis·SG·로그그룹 | **Terraform** |
| 태스크 정의 리비전(이미지 SHA·시크릿 값) | **배포 파이프라인 / 수동** — Terraform 은 최초 스켈레톤만 만들고 `ignore_changes` |
| 리스너 blue/green 가중치 전환 | **배포** — `ignore_changes = [default_action]` |

- 태스크 정의의 `CHANGE_ME` 시크릿(JWT·OPENAI 등)은 **apply 후 콘솔에서 새 리비전을 만들어 직접 채운다**. DB 비밀번호는 RDS 가 Secrets Manager 에 자동 생성 — `terraform output db_master_secret_arn` 의 시크릿에서 꺼내 채운다.
- 이미지는 **git SHA 태그만** 사용한다. dev 에서 검증한 SHA 를 prod 태스크 정의 리비전에 그대로 박는 것이 승격(재빌드 금지).

## 실행

```bash
cd infra/terraform
terraform init
terraform plan  -var aws_profile=<인프라 생성 권한 프로필>
terraform apply -var aws_profile=<프로필>
```

apply 후 수동 마무리 체크리스트:

1. Secrets Manager 에서 DB 비밀번호 확인 → 태스크 정의 새 리비전에 시크릿 전부 채움 → 서비스가 새 리비전 사용하도록 갱신
2. `main.tf` 의 `CHANGE_ME`(storage_bucket·cdn_base_url·image_public_base_url·api_image SHA) 를 실값으로 교체 후 재 apply
3. 443 이 필요하면 ACM 인증서 발급 후 `certificate_arn` 지정
4. state 백엔드: S3 버킷 생성 후 `versions.tf` 의 backend 블록 주석 해제 → `terraform init -migrate-state`

## 구조

```
infra/terraform/
├── main.tf            # module "dev" 호출 (환경별 사양·대수)
├── versions.tf        # provider·backend
├── modules/env/       # 환경 1개 = 모듈 1회 호출
│   ├── network.tf     # 기존 VPC/서브넷 data 참조 (VPC 는 만들지 않음)
│   ├── sg.tf          # alb→ec2(동적포트)→rds/redis 체인
│   ├── alb.tf         # ALB + blue/green TG + 80/443 리스너
│   ├── ecs.tf         # 클러스터·런치템플릿·ASG·캐퍼시티 프로바이더
│   ├── service.tf     # 태스크 정의 스켈레톤 + 서비스 (ignore_changes)
│   ├── rds.tf         # MySQL 8, master password 는 Secrets Manager 관리
│   └── redis.tf
```
