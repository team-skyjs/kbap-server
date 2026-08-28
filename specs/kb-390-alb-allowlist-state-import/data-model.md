# Data Model: state 복구·prod Alloy·공개 진입점 차단 (KB-390)

영속(DB) 변경 없음. 인프라 장부·설정 모델.

## 장부(state)

| 항목 | 값 |
|---|---|
| 저장소 | S3 `kbap-terraform-state`(ap-northeast-2, 버저닝·SSE-S3·퍼블릭 차단) |
| 객체 | `env:/dev/ecs/terraform.tfstate` · `env:/prod/ecs/terraform.tfstate` · 잠금 `….tflock` |
| workspace | `dev` · `prod` (구 로컬 `dev-ecs`/`prod`(EKS) 는 맥미니 `_local-state-archive/` 로 보관) |
| 내용 | 모듈 리소스 52개(alloy 3 포함) + data 소스. 비밀 값은 SSM ARN 참조뿐이나 엔드포인트·SG id·집 IP 포함 → 버킷 접근은 `kbap-infra` 만 |

## 환경 변수 파일(tfvars) — git 밖

`dev.tfvars` / `prod.tfvars`. 예시 파일 대비 다른 항목(README 표 + 위키에 값): `api_image`·`batch_image`·`admin_cidr`·`bastion_key_name`·`home_prometheus_remote_write_url`(+ prod 는 `blocked_path_patterns` 에 swagger 2개 추가).

## 공개 진입점 차단 규칙

| 필드 | dev | prod |
|---|---|---|
| `blocked_path_patterns` | `["*actuator*"]` | `["*actuator*", "*swagger*", "*api-docs*"]` |
| 액션 | fixed-response 404 `text/plain` "" | 동일 |
| priority | 10 | 10 |

## import 블록 (일시 파일, gitignore)

`import.<env>.tf` — 리소스 주소 49개 × `{ to, id }`. apply 성공 후 삭제. id 출처는 contracts/import-ids.md.

## 상태 전이

```
[맥미니 로컬 dev-ecs] --state pull--> 파일 --state push--> [S3 env:/dev]  ──plan 0 change──▶ 완료
[AWS prod 실리소스]   --gen-import-blocks--> import.prod.tf --plan(49 import + 3 add)--> apply --> [S3 env:/prod] (import.prod.tf 삭제)
[ALB 리스너]          --규칙 추가(dev)--> curl 3종 --%61 새면--> WAF 승격 결정 --카나리 1회--> prod 적용
```
