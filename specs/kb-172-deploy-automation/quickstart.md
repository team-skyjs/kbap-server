# Quickstart: KB-172 배포 자동화 — AWS 구성 런북 + 검증

저장소 변경은 워크플로 3파일뿐이다. 이 런북은 워크플로가 전제하는 **AWS·GitHub 측 1회성 구성**과 **환경별 검증(DoD)**·**롤백 절차**를 다룬다.

## §1. GitHub OIDC provider (AWS, 1회)

IAM → Identity providers 에 GitHub OIDC provider 가 없으면 추가:

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com
```

## §2. 환경별 IAM 역할 3개

역할 이름: `gha-deploy-dev` / `gha-deploy-staging` / `gha-deploy-prod`.

**신뢰 정책(공통 골격)** — `sub` 를 GitHub Environment 로 잠근다(교차 배포 차단, FR-006). `<ORG>/<REPO>` 와 `<ENV>` 만 치환:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
        "token.actions.githubusercontent.com:sub": "repo:<ORG>/<REPO>:environment:<ENV>"
      }
    }
  }]
}
```

**권한 정책**:

- `gha-deploy-dev`·`gha-deploy-staging` (동일 구조, 인스턴스 한정):
  - ECR: `GetAuthorizationToken`(전역) + push/pull 계열(`kbap/api` 리포지토리 ARN 한정)
  - SSM: `ssm:SendCommand`(document `AWS-RunShellScript` + 대상 EC2 인스턴스 ARN 한정), `ssm:GetCommandInvocation`·`ssm:ListCommands`(조회)
- `gha-deploy-prod` (ECS 네이티브 블루/그린 — CodeDeploy 미사용):
  - ECR: 위와 동일
  - ECS: `ecs:DescribeServices`·`ecs:DescribeTaskDefinition`·`ecs:RegisterTaskDefinition`·`ecs:UpdateService`
  - IAM: `iam:PassRole`(태스크 실행 역할·태스크 역할 ARN 한정 — register-task-definition 이 역할을 참조)

## §3. EC2 사전 조건 (dev·staging 공용 인스턴스)

- [ ] SSM Agent 동작 + 인스턴스 프로파일에 `AmazonSSMManagedInstanceCore`
- [ ] 인스턴스 프로파일에 ECR pull 권한(`kbap/api`) — SSM 스크립트가 인스턴스 자격으로 `docker pull` 한다
- [ ] env-file 존재: `/opt/kbap/api-dev.env`, `/opt/kbap/api-staging.env` — 기존 수동 `docker run` 에 쓰던 env 를 파일로 정리(파이프라인은 내용을 모른다, R3). `SPRING_PROFILES_ACTIVE=dev|staging` 포함 확인.
- [ ] 포트 확인: api-dev 호스트 8080, api-staging 호스트 8081 (컨테이너 내부는 둘 다 8080)

## §4. GitHub Environments + variables

Settings → Environments 에 `dev`/`staging`/`prod` 생성 후 **variables**(secret 아님 — secret 은 0개) 등록:

| variable | dev | staging | prod |
|---|---|---|---|
| `AWS_REGION` | 리전 | 리전 | 리전 |
| `AWS_ROLE_ARN` | gha-deploy-dev ARN | gha-deploy-staging ARN | gha-deploy-prod ARN |
| `ECR_REPOSITORY` | `kbap/api` | `kbap/api` | `kbap/api` |
| `EC2_INSTANCE_ID` | 공용 EC2 id | 공용 EC2 id | — |
| `CONTAINER_NAME` | `api-dev` | `api-staging` | 태스크정의 컨테이너명 |
| `HOST_PORT` | `8080` | `8081` | — |
| `ECS_CLUSTER` / `ECS_SERVICE` | — | — | 기존 클러스터/서비스 |

> prod 는 ECS 네이티브 블루/그린이라 `CODEDEPLOY_*`·`CONTAINER_NAME`·`CONTAINER_PORT` variables 가 필요 없다 — 컨테이너·포트·타깃그룹·블루그린 전략·bake time 은 **ECS 서비스/태스크정의에 사전 구성**(인프라 소유). 워크플로는 태스크정의의 이미지만 교체하고 `update-service` 로 블루/그린을 트리거한다. 서비스에 `deploymentConfiguration.strategy=BLUE_GREEN` 이 설정돼 있어야 한다(CodeDeploy 의 배포그룹을 대체하는 사전 조건).

**deployment branch policy (필수 — 브랜치→환경 격리의 실제 집행자)**: 각 environment → Settings → Environments → `<env>` → **Deployment branches and tags → Selected branches and tags** 에서 브랜치를 잠근다 — prod→`main`, staging→`staging-*`(패턴), dev→`develop`. 이게 없으면 **아무 브랜치의 워크플로가 `environment: prod` 를 선언해 prod 역할을 assume 할 수 있다** — OIDC `sub` 는 `repo:…:environment:prod` 로 브랜치를 담지 않으므로 IAM 신뢰 정책만으로는 브랜치를 못 막는다(§2 의 `sub` 조건은 "어느 environment 냐"만 검증). 교차 배포 차단은 **IAM sub(어느 환경) + deployment branch policy(어느 브랜치) 2겹**으로 완성된다.

prod 승인 게이트: 이번엔 미설정. 필요 시 `prod` environment 의 protection rules → required reviewers 만 켜면 된다(워크플로 무변경).

## §5. staging 브랜치 (릴리스마다 임시)

staging 은 릴리스마다 develop 에서 따서 실험하고, 합격하면 main·develop 에 병합 후 삭제하는 **임시 브랜치**다. 워크플로는 **`staging-*` 패턴** 푸시에 트리거되므로(`deploy-staging.yml`), 날짜 등을 붙인 이름을 매 사이클 새로 만든다.

```bash
git push origin develop:staging-20260720   # develop 기준 임시 staging-* 생성 → deploy-staging 트리거
# ... 테스트·핫픽스 커밋 (푸시마다 재배포) ...
# 합격 후: staging-20260720 을 main·develop 에 병합 → 브랜치 삭제(git push origin :staging-20260720)
```

> 여러 `staging-*` 브랜치가 동시에 있어도 배포는 같은 `api-staging` 컨테이너를 대상으로 하므로 `concurrency: deploy-staging` 하나로 직렬화된다(마지막 푸시가 최종 상태). 한 번에 하나의 릴리스 후보만 실험하는 것을 전제로 한다.

## §6. 환경별 검증 (DoD — 각 1회)

1. **dev**: develop 에 커밋 푸시 → Actions 의 `deploy-dev` 실행 확인 → 성공 후 EC2 에서 `docker ps` 로 `api-dev` 이미지 태그 = **푸시 커밋 sha** 확인, `curl localhost:8080/actuator/health` = UP. 같은 시각 `api-staging` 컨테이너 재시작 없음(`docker ps` STATUS 유지).
2. **staging**: staging 에 푸시 → 동일 확인(:8081) — 이미지 태그도 **커밋 sha**(dev·prod 와 동일). `api-dev` 비간섭 확인.
3. **prod**: main 병합 → `deploy-prod` 실행 → 새 태스크정의 리비전 이미지 태그 = **커밋 sha** 확인 → ECS 콘솔의 서비스 배포 탭에서 블루/그린 트래픽 전환 확인 → 서비스 헬스 UP. (`aws ecs describe-services --query 'services[0].deployments'` 로 새 배포가 PRIMARY 로 전환됐는지 확인.)
4. **실패 전파 확인(권장)**: 존재하지 않는 image_tag 로 dev workflow_dispatch 실행 → 워크플로가 실패로 표시되고 기존 컨테이너가 살아있는지 확인(FR-010, US1-AC3).

## §7. 롤백 절차

Actions → 해당 환경의 deploy 워크플로 → Run workflow → `image_tag` 에 **이전 정상 커밋 sha** 입력 → 실행. 빌드 없이 해당 이미지를 재배포한다(R8, FR-009). 전 환경 공통 — 이전 sha 는 Actions 이력·`git log`·ECR 태그 목록에서 확인. 커밋마다 유일 태그라 덮어쓰기·버전 관리 없음.
