# KBAP EKS 전환 + Terraform 학습용 가이드 (스터디용)

> 목적: 지금까지 한 작업 정리 + 쿠버네티스(EKS) + Terraform 핵심 개념을 **운영 설계 수준**으로 이해할 수 있도록 정리
> 대상: ECS 운영 경험이 있고, dev/prod 동일 환경으로 운영을 옮기려는 사용자

## 1) 지금까지 한 작업 정리(요약)

### 왜 EKS로 전환하려 했는가
- ECS는 컨테이너 실행/오케스트레이션이 쉬운 장점이 있지만, 운영 도구(모니터링, 배치, 워크로드 분리, 공통 배포 패턴) 관점에서 추가적인 서비스 설계가 필요함
- 배치/모니터링/메트릭이 늘어나는 구조를 만들려면 **쿠버네티스 기반 운영 패턴**이 장기적으로 익숙해질 필요가 있음
- dev에서 시험 운용 후, 같은 방식으로 prod까지 동일하게 적용하려는 목표가 명확함

### 지금 상태(이 대화 기반)
- AWS 기존 자원 **재사용** 전제로 진행
  - 기존 VPC, ALB, RDS, Redis, ECR 등을 최대한 재사용
- EKS는 기본적으로 `kbap-dev-eks` 생성 흐름으로 잡힘
- 노드그룹 분리 아이디어
  - `workload=api` 노드: API 서비스용
  - `workload=ops` 노드: 배치·모니터링(그라파나/프로메테우스) 용
- ALB는 “처음부터 새로 생성” 대신, 기존 HTTPS ALB를 재사용해서
  - AWS 로드밸런서 컨트롤러 + TargetGroupBinding을 쓰는 방향 검토
- 기본 배포 대상
  - `kbap-api` (Deployment + Service)
  - `kbap-batch` (Deployment + Service)
  - `kubectl`/`helm`로 선언형 관리
- 현재까지의 주요 고민
  - 노드/파드/서비스/루틴 개념 정리
  - Service가 파드 IP 변경에도 안정적으로 동작하는지
  - 콘솔에서 RBAC/권한 이슈
  - YAML/헬름 파일은 레포 관리 필수 여부

## 2) 용어 정리: ECS vs Kubernetes 한 번에 보기

| 항목 | ECS (기존 익숙한 모델) | Kubernetes/EKS |
| --- | --- | --- |
| 스케줄링 단위 | Task(태스크) | Pod(파드) |
| 배치 단위 | Service 또는 Task Definition | Deployment + Service |
| 런타임 노드 | ECS가 관리(또는 Fargate) | EC2 노드군이 파드를 수용 |
| 노드 개념 | 보통 사용자 정의가 적음 | 직접 Node, Node Selector, Taint/Toleration로 배치 제어 |
| 로드 밸런싱 | ALB/NLB Integration이 용이 | Ingress/Service + AWS LB Controller로 확장 |
| 운영 확장성 | 간단/빠름 | 구성 많음, 일관성 높은 패턴 가능 |
| dev/prod 동일화 | 상대적으로 쉬움(기본 템플릿) | Helm/Values + GitOps로 훨씬 깔끔 |

### 핵심 결론
- ECS가 나쁜 건 아님.
- 다만 “배치/모니터링/운영 툴 체인을 한 번에 붙이는 구조”로 갈수록 Kubernetes 패턴이 더 유리.

## 3) Kubernetes 기본 개념 (지금 수준에 맞게)

### 3.1 Node(노드)
- Kubernetes 클러스터의 실제 실행 단위(EC2 인스턴스)
- 각 노드는 여러 Pod를 실행 가능
- 노드 사양이 곧 그 노드의 컴퓨팅 용량

### 3.2 Pod(파드)
- **가장 작은 배포 단위**
- 보통 “컨테이너 1개”로도 구성할 수 있지만, 여러 개 묶는 것도 가능
- 스케줄링은 Node 레벨에서 이루어지고, Pod는 실행 가능한 단위로 이동되거나 재생성됨
- Pod IP는 바뀔 수 있음(특히 재시작/재배치 시)

### 3.3 Deployment(디플로이먼트)
- Pod를 **개수/버전/롤링업데이트** 정책으로 관리하는 상위 리소스
- 예: API를 항상 2개 띄워두고, 새 버전 배포 시 점진적 교체

### 3.4 Service(서비스)
- 파드 IP가 바뀌어도 서비스 이름은 안정적으로 유지됨
- Service는 라벨 셀렉터로 대상 Pod를 찾음
- 사용자 입장에서 “파드의 변화가 있어도 주소는 유지”하는 핵심 장치
- 그래서 `Service`를 거치면 `pod의 IP가 바뀌어도` 문제를 크게 줄일 수 있음

### 3.5 Ingress/ALB 연동
- Kubernetes의 트래픽 진입점을 만들 수 있는 경로
- EKS + AWS 환경에서 AWS Load Balancer Controller를 쓰면 ALB 생성/연결 관리 자동화
- 기존 ALB 재사용할 경우 `TargetGroupBinding`으로 기존 TG에 연결

### 3.6 스케줄링 제어(노드 선택)
- `nodeSelector: workload: api`
  → 해당 Pod를 `workload=api` 라벨 노드에 배치 요청
- `taint/toleration`
  → 특정 노드에 “제한” 걸고, 허용 Pod만 붙게 만드는 제어
- 현재 고민한 구조는 이 방식과 잘 맞음
  - `api` 노드: API 서비스
  - `ops` 노드: 배치/모니터링

### 3.7 ConfigMap/Secret
- ConfigMap: 비밀이 아닌 설정값
- Secret: 민감 정보(DB 비밀번호, API 키 등)
- 실무에서는 Secret을 외부 비밀관리(Secrets Manager/SSM)와 연계해 파이프라인에서 주입

## 4) Terraform 핵심 개념 (딥 하지 않게)

### Terraform이 하는 일
- AWS 리소스를 코드로 관리(IaC)
- 상태(`state`)를 추적해 변경점만 계산

### 기본 블록
- `provider`: AWS 자격증명/리전
- `resource`: 실제 생성할 리소스
- `module`: 공통화된 인프라 뭉치
- `variable`/`locals`: 입력값
- `output`: 클러스터/SG 등 외부에서 사용 가능 값
- `data`: 조회용(기존 리소스 참조)
- `terraform.tfstate`: 현재 상태 기록 (공유 백엔드 권장)

### 환경 분리 방식
- dev/stg/prod를 각각 다른 workspace/폴더/루트로 분리
- 같은 모듈을 각 환경별 값으로 재사용
- 목적: **동일한 아키텍처를 다르게 구성(최소한 dev와 prod 설정값 차이)**

### 지금 구조와 연계
- Terraform은 ALB/VPC/RDS/Redis를 “있는 그대로 참조”하거나, EKS 자원을 신규 생성
- EKS 추가 후 Kubernetes 매니페스트는 별도로 관리(또는 Helm chart)
- 즉, 인프라와 앱배포를 같은 Terraform에 모두 몰아넣지 않아도 됨(권장: 경계 분리)

## 5) 지금까지 논의한 아키텍처 플로우 정리

1. 기존 네트워크 재사용
   - 기존 VPC와 서브넷 사용
   - EKS 노드가 ALB 연동 가능한 서브넷 태그/권한 구성
2. dev EKS 클러스터/노드 생성
   - `kbap-dev-eks`
   - 노드 그룹 분리(`api`, `ops`)
3. AWS LB Controller 설치 + OIDC/IRSA(필요 권한) 처리
4. 기존 ALB 재사용
   - HTTPS 인증서/리스너 유지
   - 새 `TargetGroup` 또는 기존 TG 연동 전략 선택
5. 앱 배포
   - `kbap-api`, `kbap-batch`를 YAML/Helm로 배포
6. 서비스 라우팅 고정
   - Service로 파드 IP 변경을 흡수
7. 보안 그룹/포트 점검
   - ALB → Node(8080), Node → RDS/Redis(3306/6379)

### 실전 체크포인트
- `kubectl get nodes`, `kubectl get pods -o wide`
- `kubectl describe`로 상태 확인
- `aws elbv2 describe-target-health`로 TargetGroup 정상 등록 확인
- `curl`로 헬스체크 엔드포인트 확인

## 6) 실수하기 쉬운 포인트

- Service 없이 Pod IP만 직접 호출: 재시작 시 장애 유발
- Node 라벨만 보고 `nodeSelector`만 쓰고 taint를 안 쓰는 경우: 예기치 않은 스케줄링
- SG 인바운드에 IP/CIDR만 열고 Pod IP를 전제: 운영 불안정
- 이벤트 브리지/스케줄러를 나중에 바꾸려다 기존 파이프라인 깨짐
- 시크릿을 `values.yaml` 평문 삽입

## 7) 학습용 1주차 실행 체크리스트

### Day 1~2
- `kubectl` 기본 10개 명령 연습
  - `get nodes`, `get pods`, `get deploy`, `get svc`, `logs`, `describe`, `apply`, `delete`
- Service+Deployment 조합으로 샘플 앱 배포
- `kubectl rollout status`, `kubectl rollout undo` 연습

### Day 3~4
- `Deployment` 2개 + `Service` + `Ingress`(또는 ALB 연동) 실습
- Service가 IP 변경을 어떻게 숨기는지 확인 실습
- nodeSelector로 `api`/`ops` 분리 체감

### Day 5~6
- Terraform으로 EKS 네트워크/노드/SG 최소 생성
- `terraform plan`/`apply`/`destroy` (개념 중심) 실습
- 상태관리(state) 중요성 이해

### Day 7
- dev용 앱 매니페스트를 Helm 차트로 정리
- values 분리(`values-dev.yaml`, `values-prod.yaml`) 체험
- CI/CD에서 같은 helm chart + 환경값만 바꿔서 배포해보기

## 8) 앞으로 작업으로 바로 이어가기용 문서 템플릿

다음 단계에서 레포에 파일을 분리해두면 운영이 쉬워집니다.

### 예시 디렉터리 구조
- `k8s/dev/api/`
- `k8s/dev/batch/`
- `k8s/prod/api/`
- `k8s/prod/batch/`
- `k8s/charts/kbap/` (helm chart)
- `docs/notes/dev-eks-운영메모.md`

### 최소 YAML(개념)
- Deployment
- Service
- ServiceAccount/RoleBinding
- HorizontalPodAutoscaler(향후)
- ConfigMap/Secret references
- nodeSelector + tolerations

## 9) 지금 상태에서 꼭 기억할 포인트(짧은 재확인)

- 파드는 “임시 IP”의 개념을 받아들이면 된다.
  → Service는 IP 변동을 가립니다.
- 배포 정의(Deployment/Service)는 레포에서 관리해야 추적 가능하다.
- Terraform은 “클라우드 자원 배치”의 기준선이고, 쿠버 매니페스트는 “앱 런타임 상태”의 기준선이다.
- dev에서 검증한 설정/이미지 태그를 prod에 그대로 쓰려면, 환경별 값은 최소화하고 공통 chart를 공유해야 한다.
- 비용 최적화가 목적이면 dev 노드 타입/개수를 적극 조절하고, 오토스케일 정책을 명확히 두는 것이 중요하다.

---

원하면 이 파일을 바로 다음 단계용으로 확대해서
- `docs/learning/`에 **실습용 명령 체크리스트**
- `k8s/` 템플릿(helm chart 예시)
- `terraform/` 출력 변수 표준화
까지 한 번에 이어서 만들어드릴 수 있습니다.
