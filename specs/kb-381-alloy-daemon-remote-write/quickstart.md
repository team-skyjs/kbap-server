# Quickstart: Alloy DAEMON → 홈 Prometheus (KB-381) — 준비·적용·검증 런북

## 0. 사용자 준비물 (구현 전)

1. **홈서버 Prometheus**: 컨테이너 args 에 `--web.enable-remote-write-receiver` 추가 후 재기동. `/prometheus` 가 호스트 볼륨에 마운트돼 있는지 확인.
2. **Cloudflare Tunnel**: 공개 호스트 `prom-write.<도메인>` → `http://prometheus:9090` ingress 추가.
3. **Cloudflare Access**: 셀프호스트 앱(도메인 `prom-write.<도메인>`) 생성 → 정책 "Service Auth" → 서비스 토큰 **dev·prod 각 1쌍** 발급(Client ID / Secret).
4. **SSM 등록** (각 env):
   ```bash
   for env in dev prod; do
     aws ssm put-parameter --profile kbap-prod-deployer --region ap-northeast-2 --type SecureString --overwrite \
       --name /kbap/$env/CF_ACCESS_CLIENT_ID --value '<id>'
     aws ssm put-parameter --profile kbap-prod-deployer --region ap-northeast-2 --type SecureString --overwrite \
       --name /kbap/$env/CF_ACCESS_CLIENT_SECRET --value '<secret>'
   done
   ```
5. 수신 확인(토큰 검증):
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -X POST https://prom-write.<도메인>/api/v1/write                      # 403 (토큰 없음)
   curl -s -o /dev/null -w '%{http_code}\n' -X POST https://prom-write.<도메인>/api/v1/write \
        -H "CF-Access-Client-Id: <id>" -H "CF-Access-Client-Secret: <secret>"                                  # 400/415 (Prometheus 까지 도달 — 본문이 없어서)
   ```

## 1. 로컬 설정 문법 점검 (선택)

```bash
cd iac/terraform/modules/ecs-environment
sed -e 's/\${env}/dev/g' -e 's#\${remote_write_url}#https://example/api/v1/write#g' -e 's/\$\${/${/g' alloy.config.alloy.tftpl > /tmp/config.alloy
docker run --rm -v /tmp/config.alloy:/c.alloy grafana/alloy:<tag> fmt /c.alloy >/dev/null && echo "syntax ok"
```

## 2. dev 적용 (terraform state 가 있는 머신에서)

```bash
cd iac/terraform
terraform plan  -var-file=dev.tfvars    # + : log group, task definition, DAEMON service 3개 (그 외 변경 0)
terraform apply -var-file=dev.tfvars
```
tfvars 에 `home_prometheus_remote_write_url`·`alloy_image` 추가.

## 3. dev 검증 (spec 의 US 순서)

```bash
# US1 — 조회
aws ecs list-tasks --cluster kbap-dev-ecs-cluster --service-name kbap-dev-ecs-alloy --query 'length(taskArns)'   # = 인스턴스 수
# Grafana Explore (Prometheus DS):
#   up{env="dev", job="prometheus.scrape.ecs_apps"}   → api 태스크 수 + batch 1 만큼 1
#   count by (host) (node_memory_MemAvailable_bytes{env="dev"})   → 인스턴스 수
#   jvm_memory_used_bytes{env="dev",application="kbap-api",area="heap"}  → 15s 간격 그래프
# batch 잡 1회 트리거(ECS Exec, README) 후 spring_batch_job_seconds_count{env="dev"} 증가

# US3 — 카나리 버전 비교: api 배포 1회 후 카나리 창 안에서
#   sum by (version) (rate(http_server_requests_seconds_count{env="dev",application="kbap-api"}[5m]))  → 두 줄
#   count by (instance, host) (up{env="dev", instance=~"dev-api-.*"})  → 같은 host 에 instance 2개

# US2 — 스케일/교체: ASG instance refresh 1대 → 5분 내 새 host 라벨 등장, 옛 host 시계열 stale
# US2 — 단절: 홈 Prometheus 30분 정지 → 재기동 → 그래프 공백이 채워짐(Alloy 로그에 재전송)
# US5 — 토큰: SSM 값을 틀린 값으로 바꾸고 alloy 서비스 force-new-deployment → Grafana 갱신 멈춤 + Alloy 로그 403, api 는 정상. 복구 후 재개
aws logs tail /kbap/dev/alloy --since 10m --profile kbap-prod-deployer --region ap-northeast-2
```

## 4. prod 적용

`prod.tfvars` 에 같은 변수 추가 → `terraform apply -var-file=prod.tfvars`. batch 는 desired 0 이라 batch 풀 호스트는 호스트 메트릭만 보낸다. `up{env="prod"}` 확인.

## 5. 되돌리기

`terraform destroy -target=module.ecs_environment.aws_ecs_service.alloy`(또는 서비스 desired 는 DAEMON 이라 서비스 삭제) — 앱·ALB·SG 무영향. 홈서버 쪽은 Access 토큰 폐기만으로 즉시 차단된다.
