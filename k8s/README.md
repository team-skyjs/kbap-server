# KBAP K8s(Helm) 시작 가이드

현재 `k8s/charts/kbap`에 dev/prod 공통 배포 템플릿을 넣어두었습니다.

## 폴더 구조
- `k8s/charts/kbap/` : Helm chart 본체
- `k8s/charts/kbap/values-dev.yaml` : dev 전용 값
- `k8s/charts/kbap/values-prod.yaml` : prod 전용 값

## 현재 생성된 리소스
- API: Deployment + Service + TargetGroupBinding(옵션)
- Batch: Deployment + Service

## 실행 순서 (dev)

1) 값 채우기
- `k8s/charts/kbap/values-dev.yaml`의 `<...>` 값을 실제 값으로 교체
- `api.network.targetGroup.arn`이 준비되면 `enabled: true`로 변경
- 비밀값은 Secret으로 넣고 `envFrom`으로 주입합니다.

Note: 배포 네임스페이스는 helm 커맨드의 `-n`/`--create-namespace`로 생성합니다.

### Secret 준비 (dev)

앱이 실제로 읽는 환경변수(`application*.yml` 의 `${...}`) 기준. `VISION_API_KEY`·`UPSTAGE_API_KEY`·`GOOGLE_API_KEY` 는 폐기된 이름이므로 쓰지 않는다.

| Secret | 키 | 꽂히는 곳 |
|---|---|---|
| `kbap-api-secrets` | `DB_PASSWORD` | datasource |
| | `JWT_SECRET` | 자체 JWT + 스캔 티켓 서명(jjwt) |
| | `OPENAI_API_KEY` | 스캔 비전(gpt-5.6-luna) — 없으면 api 부팅 실패 |
| | `GOOGLE_PLACES_API_KEY` | 장소 검색(Places New) + 주문 역지오코딩(Geocoding) — 콘솔에서 두 API 모두 활성화 필요 |
| | `FIREBASE_CREDENTIALS_JSON` | 소셜 로그인 ID 토큰 검증(서비스 계정 JSON 통째. 파일 마운트를 쓰면 대신 `FIREBASE_CREDENTIALS_PATH`) |
| | `VECTOR_DB_URI` (VECTOR_ENABLED 시) | DocumentDB 접속 문자열(계정 포함) |
| `kbap-batch-secrets` | `DB_PASSWORD` · `OPENAI_API_KEY` · `VECTOR_DB_URI`(임베딩 동기화 시) | datasource · 임베딩 · DocumentDB |

```bash
kubectl create namespace kbap-dev

kubectl create secret generic kbap-api-secrets \
  -n kbap-dev \
  --from-literal=DB_PASSWORD= \
  --from-literal=JWT_SECRET= \
  --from-literal=OPENAI_API_KEY= \
  --from-literal=GOOGLE_PLACES_API_KEY= \
  --from-literal=FIREBASE_CREDENTIALS_JSON=

kubectl create secret generic kbap-batch-secrets \
  -n kbap-dev \
  --from-literal=DB_PASSWORD= \
  --from-literal=OPENAI_API_KEY=
```

> AWS 자격증명(S3·SQS)은 Secret 에 넣지 않는다 — EKS Pod Identity(애드온 설치됨)로 IAM 롤을 붙이는 게 원칙. 차트에 ServiceAccount 추가 + Terraform 의 pod identity association 이 후속 작업.

2) 배포
```bash
helm upgrade --install kbap-dev ./k8s/charts/kbap \
  -n kbap-dev --create-namespace \
  -f k8s/charts/kbap/values-dev.yaml \
  --wait --timeout 10m
```

3) 확인
```bash
kubectl -n kbap-dev rollout status deployment/kbap-dev-kbap-api --timeout=10m
kubectl -n kbap-dev get pods -o wide
kubectl -n kbap-dev get targetgroupbinding
kubectl get nodes -L workload,node.kubernetes.io/instance-type
kubectl -n kbap-dev get pods -l app=kbap-api \
  -o custom-columns='POD:.metadata.name,NODE:.spec.nodeName,READY:.status.containerStatuses[0].ready'
```

4) 새 이미지 배포(동일 릴리스 이름 유지)
```bash
helm upgrade --install kbap-dev ./k8s/charts/kbap \
  -n kbap-dev \
  -f k8s/charts/kbap/values-dev.yaml \
  --set image.api.tag=<새-태그> \
  --set image.batch.tag=<새-태그>
```

5) ALB 연동 (선택)
```bash
helm upgrade --install kbap-dev ./k8s/charts/kbap \
  -n kbap-dev \
  -f k8s/charts/kbap/values-dev.yaml \
  --set api.network.targetGroup.enabled=true \
  --set api.network.targetGroup.arn=<기존-타겟그룹-ARN>
```

6) prod로 동일 배포
```bash
helm upgrade --install kbap-prod ./k8s/charts/kbap \
  -n kbap-prod --create-namespace \
  -f k8s/charts/kbap/values-prod.yaml
```

## 현재 값 반영 참고사항

`values-dev.yaml`에 현재 다음 값은 실제 조회 가능한 값으로 채워 넣었습니다.

- ECR API: `118178010621.dkr.ecr.ap-northeast-2.amazonaws.com/kbap/api`
- ECR Batch: `118178010621.dkr.ecr.ap-northeast-2.amazonaws.com/kbap/batch`
- DB URL: `jdbc:mysql://kbap-db-devstg.cfy0goiqwlg7.ap-northeast-2.rds.amazonaws.com:3306/kbap`
- Redis Host: `kbap-devstg-redis-0001-001.kbap-devstg-redis.fu1mox.apn2.cache.amazonaws.com`
- ALB: `kbap-devstg-alb` (기존 재사용 대상)
- ALB HTTPS 리스너: `arn:aws:elasticloadbalancing:ap-northeast-2:118178010621:listener/app/kbap-devstg-alb/137a378fa85d51b5/f32f21dc4b84d1b2`
- ALB HTTPS 도메인: `kbap-devstg-alb-1500400334.ap-northeast-2.elb.amazonaws.com`
- dev API 규칙 호스트: `dev.kbap.site`
